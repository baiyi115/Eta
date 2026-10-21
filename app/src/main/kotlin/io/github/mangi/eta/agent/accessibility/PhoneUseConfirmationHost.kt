package io.github.mangi.eta.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import io.github.mangi.eta.agent.overlay.AgentOverlayWindowHost
import io.github.mangi.eta.agent.phoneuse.PhoneUseImageSampling
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** An actual user gesture, never a model token, approves one immutable pending action. */
internal class PhoneUseConfirmationHost(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)
    private val pending = AtomicReference<Request?>()
    private class Request(val bitmap: Bitmap?) {
        val resolved = AtomicBoolean(false)
        val invalidated = AtomicBoolean(false)
        val accepted = AtomicBoolean(false)
        val done = CountDownLatch(1)
        var window: FrameLayout? = null // UI fields are main-thread only.
        var host: AgentOverlayWindowHost? = null
        var composition: ComposeView? = null
        var owner: ConfirmationWindowOwner? = null
        var approveAfter = Long.MAX_VALUE
        var timeout: Runnable? = null
        fun resolve(allow: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            accepted.set(allow)
            done.countDown()
        }
    }
    fun confirm(message: String, imageReference: String, canExecute: () -> Boolean): Boolean {
        fun active() = !stopped.get() && runCatching(canExecute).getOrDefault(false)
        if (Looper.myLooper() == Looper.getMainLooper() || !active()) return false
        val bitmap = preview(imageReference) ?: return false
        val request = Request(bitmap)
        if (!pending.compareAndSet(null, request)) { request.bitmap?.recycle(); return false }
        var accepted = false
        var clean = false
        try {
            val posted = main.post { show(request, message) }
            if (posted) {
                val deadline = SystemClock.elapsedRealtime() + 120_000L
                while (!request.done.await(100, TimeUnit.MILLISECONDS)) {
                    if (!active() || SystemClock.elapsedRealtime() >= deadline) break
                }
                accepted = request.accepted.get() && SystemClock.elapsedRealtime() < deadline && active()
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        finally {
            request.resolve(false)
            // Never inject until the confirmation window has actually been removed.
            val removed = CountDownLatch(1)
            if (main.post {
                try { dismiss(request) } finally { pending.compareAndSet(request, null); removed.countDown() }
            }) {
                clean = try { removed.await(3, TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            }
        }
        return accepted && clean && !request.invalidated.get() && active()
    }
    private fun show(request: Request, message: String) {
        if (stopped.get() || request.resolved.get() || pending.get() !== request) { request.resolve(false); return }
        try {
            val owner = ConfirmationWindowOwner()
            request.owner = owner
            val host = AgentOverlayWindowHost(service, owner, owner)
            request.host = host
            // Reuse the background result card's actual window host, not a nested Dialog.
            val root = object : FrameLayout(service) {
                override fun onFilterTouchEventForSecurity(event: MotionEvent): Boolean {
                    val obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED or
                        MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
                    return event.flags and obscured == 0 && super.onFilterTouchEventForSecurity(event)
                }
            }.apply {
                filterTouchesWhenObscured = true
            }
            request.window = root
            val view = host.createComposeView {
                val settings = remember { AppearanceSettingsRepository.settingsFlow() }
                val appearance by settings.collectAsState(initial = AppearanceSettings())
                AgentAppTheme(appearance = appearance, applyInterfaceScale = true) {
                    // Match Eta's other service overlays: no RuntimeShader on software Canvas.
                    CompositionLocalProvider(LocalSquircleEnabled provides false) {
                        PhoneUseConfirmationContent(
                            message = message,
                            bitmap = request.bitmap!!,
                            onCancel = { request.resolve(false) },
                            onConfirm = {
                                if (!stopped.get() && !request.invalidated.get() &&
                                    pending.get() === request &&
                                    SystemClock.elapsedRealtime() >= request.approveAfter) {
                                    request.resolve(true)
                                }
                            },
                        )
                    }
                }
            }.apply { filterTouchesWhenObscured = true }
            request.composition = view
            root.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_SECURE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                title = "EtaPhoneUseConfirmation"
            }
            host.addView(root, params)
            request.approveAfter = SystemClock.elapsedRealtime() + 500L
            // The UI deadline must also work while the worker is paused at an execution checkpoint.
            request.timeout = Runnable {
                if (pending.get() === request) {
                    request.invalidated.set(true)
                    request.resolve(false)
                    dismiss(request)
                }
            }.also { main.postDelayed(it, 120_000L) }
        } catch (_: RuntimeException) { request.resolve(false) }
    }
    fun activate() { stopped.set(false) }
    fun close() { stopped.set(true); cancelPending() }
    fun cancelPending() {
        val request = pending.getAndSet(null) ?: return
        request.invalidated.set(true)
        request.resolve(false)
        if (Looper.myLooper() == Looper.getMainLooper()) dismiss(request) else main.post { dismiss(request) }
    }
    private fun dismiss(request: Request) {
        request.timeout?.let(main::removeCallbacks)
        request.timeout = null
        // Dispose before recycling the screenshot; no composition may draw a recycled bitmap.
        runCatching { request.composition?.disposeComposition() }
            .onFailure { request.invalidated.set(true) }
        val removed = runCatching {
            request.window?.let { root -> request.host?.removeView(root) ?: false } ?: true
        }.getOrElse { request.invalidated.set(true); false }
        val detached = removed && request.composition?.isAttachedToWindow != true &&
            request.window?.isAttachedToWindow != true
        if (!detached) request.invalidated.set(true)
        request.owner?.close()
        if (detached) {
            request.composition = null
            request.window = null
            request.host = null
            request.owner = null
            if (request.bitmap?.isRecycled == false) request.bitmap.recycle()
        }
    }
    private fun preview(reference: String): Bitmap? =
        PhoneUseImageSampling.decode(reference, PhoneUseImageSampling.MAX_PREVIEW_PIXELS)
}

/** A separate lifecycle per confirmation; no Activity or foreground handoff is needed. */
private class ConfirmationWindowOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    init {
        controller.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun close() {
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
