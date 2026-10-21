package io.github.mangi.eta.agent.overlay

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Shared service-window host, extracted from AgentRuntimeService's background overlays.
 * Callers retain their own content, window flags and action/lifecycle semantics.
 * All operations must run on the main thread.
 */
internal class AgentOverlayWindowHost(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedStateOwner: SavedStateRegistryOwner,
) {
    val windowManager: WindowManager =
        requireNotNull(context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
    // addView can succeed before the first attach/traversal; isAttachedToWindow alone is not enough.
    private val windows = mutableSetOf<View>()

    fun createComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent(content)
        }

    fun addView(view: View, params: WindowManager.LayoutParams) {
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(savedStateOwner)
        try {
            windowManager.addView(view, params)
            windows.add(view)
        } catch (error: RuntimeException) {
            if (!view.isAttachedToWindow && view is ComposeView) view.disposeComposition()
            throw error
        }
    }

    /** Synchronous removal; approvals must not continue while their window is still attached. */
    fun removeView(view: View): Boolean {
        try {
            if (view in windows || view.isAttachedToWindow) windowManager.removeViewImmediate(view)
            windows.remove(view)
        } finally {
            if (!view.isAttachedToWindow && view is ComposeView) view.disposeComposition()
        }
        return view !in windows && !view.isAttachedToWindow
    }
}
