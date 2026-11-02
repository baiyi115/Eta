package io.github.mangi.eta.agent.virtualdisplay

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 虚拟屏会话与生命周期中枢。
 *
 * 画面通过常驻 [ImageReader] 接收：
 * - 收起态（仅胶囊微缩取景器可见）按 ~6fps 节流更新，兼顾真实动感与低功耗；
 * - 展开态（画廊装裱视窗 / 用户手动接管）提升至 ~30fps 实时更新；
 * - Agent 调用 observe_screen(include_screenshot=true) 时直接提取最新原尺寸 Bitmap，无需等待无障碍截图冷却。
 */
internal object VirtualDisplayController {

    /** 完整画面保留节流；观察请求会绕过节流先 flush 暂存帧。 */
    private const val FRAME_RETAIN_INTERVAL_MS = 200L

    private val lock = Any()
    private val mutableSnapshots = MutableStateFlow(VirtualDisplaySnapshot.Inactive)
    val snapshots: StateFlow<VirtualDisplaySnapshot> = mutableSnapshots.asStateFlow()

    private val frameSequenceGenerator = AtomicLong(0L)
    private val touchSequenceGenerator = AtomicLong(0L)

    @Volatile
    private var highFrequencyPreview: Boolean = false

    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var inProcessVirtualDisplay: VirtualDisplay? = null
    private var rootBridgeProcess: Process? = null
    private var processSupervisor: ShellProcessSupervisor? = null

    /** 最近一帧完整画面的保留节流，见 [retainPendingFrameLocked]；只暂存一张 Image。 */
    private var pendingFrame: Image? = null
    private var lastRetainedAtElapsedMs: Long = 0L

    /** Root 桥请求/响应通道：同一时刻只允许一个在途请求，虚拟屏输入本身也是串行的。 */
    private val bridgeLock = java.lang.Object()
    private val bridgeRequestMutex = Any()
    private var bridgeRequestSeq: Long = 0L
    private var pendingBridgeRequestId: Long = -1L
    private var pendingBridgeResponse: JSONObject? = null
    private var bridgeDisplayId: Int = Display.INVALID_DISPLAY

    private var activeDisplayId: Int = Display.DEFAULT_DISPLAY
    private var displayWidth: Int = 1080
    private var displayHeight: Int = 2400
    private var displayDensityDpi: Int = 420
    private var latestFullBitmap: Bitmap? = null
    private var lastUiFrameAtElapsedMs: Long = 0L
    private val mutedPackages = linkedSetOf<String>()

    fun isBackendAvailable(rootAvailable: Boolean, lsposedAvailable: Boolean): Boolean =
        rootAvailable || lsposedAvailable

    fun setStageExpanded(expanded: Boolean) {
        highFrequencyPreview = expanded
    }

    fun setUserControlling(controlling: Boolean) {
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || current.userControlling == controlling) return
            mutableSnapshots.value = current.copy(userControlling = controlling)
            if (controlling) highFrequencyPreview = true
        }
    }

    fun currentDisplayId(): Int = synchronized(lock) {
        activeDisplayId.takeIf { CanvasRoutingPolicy.isVirtualDisplay(it) } ?: Display.DEFAULT_DISPLAY
    }

    /**
     * 通过 Root 桥把一次触摸定向注入虚拟屏；桥不存在或 display 不匹配时返回 false，由调用方回退。
     */
    fun injectTouch(displayId: Int, x: Int, y: Int, durationMs: Int = 60): Boolean {
        if (!isBridgeDisplay(displayId)) return false
        val duration = durationMs.toLong().coerceIn(40L, 5_000L)
        val action = if (duration > 120L) "longpress" else "tap"
        return bridgeSend(
            JSONObject()
                .put("action", action)
                .put("x", x)
                .put("y", y)
                .put("duration_ms", duration),
            timeoutMs = duration + 1_500L,
        )?.optBoolean("ok") == true
    }

    fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        if (!isBridgeDisplay(displayId)) return false
        val duration = durationMs.toLong().coerceIn(60L, 4_000L)
        return bridgeSend(
            JSONObject()
                .put("action", "swipe")
                .put("x1", x1)
                .put("y1", y1)
                .put("x2", x2)
                .put("y2", y2)
                .put("duration_ms", duration),
            timeoutMs = duration + 2_000L,
        )?.optBoolean("ok") == true
    }

    fun injectKey(displayId: Int, keyCode: Int): Boolean {
        if (!isBridgeDisplay(displayId)) return false
        return bridgeSend(
            JSONObject().put("action", "key").put("key_code", keyCode),
            timeoutMs = 2_000L,
        )?.optBoolean("ok") == true
    }

    /** 目标包有可见任务的 display；无桥或查询失败时返回 null（不阻断启动）。 */
    private fun taskConflictDisplay(displayId: Int, packageName: String): Int? {
        if (packageName.isBlank() || !isBridgeDisplay(displayId)) return null
        val response = bridgeSend(
            JSONObject().put("action", "task_conflict").put("package_name", packageName),
            timeoutMs = 2_500L,
        ) ?: return null
        return response.optInt("display", -1).takeIf { it >= 0 }
    }

    private fun isBridgeDisplay(displayId: Int): Boolean = synchronized(lock) {
        rootBridgeProcess?.isAlive == true &&
            CanvasRoutingPolicy.isVirtualDisplay(displayId) &&
            displayId == bridgeDisplayId
    }

    /**
     * 向桥进程发送一条命令并等待应答。
     *
     * 输入本身是串行的，所以同一时刻只跟踪一个在途 id；超时返回 null，不会阻塞到下一个手势。
     */
    private fun bridgeSend(payload: JSONObject, timeoutMs: Long): JSONObject? = synchronized(bridgeRequestMutex) {
        val process = rootBridgeProcess ?: return null
        if (!process.isAlive) return null
        val id = synchronized(bridgeLock) {
            val next = ++bridgeRequestSeq
            pendingBridgeRequestId = next
            pendingBridgeResponse = null
            next
        }
        val line = JSONObject(payload.toString()).put("id", id).toString() + "\n"
        val written = runCatching {
            synchronized(bridgeLock) {
                process.outputStream.write(line.toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }
            true
        }.getOrDefault(false)
        if (!written) return null

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        synchronized(bridgeLock) {
            while (pendingBridgeResponse == null) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    pendingBridgeRequestId = -1L
                    return null
                }
                try {
                    bridgeLock.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    pendingBridgeRequestId = -1L
                    return null
                }
            }
            val response = pendingBridgeResponse
            pendingBridgeRequestId = -1L
            pendingBridgeResponse = null
            return response
        }
    }

    /**
     * 当前画布是否仍可复用；Root 桥进程退出后其 display 已经失效，必须重新建立。
     * 调用方必须持有 [lock]。
     */
    private fun hasUsableDisplayLocked(): Boolean =
        CanvasRoutingPolicy.isVirtualDisplay(activeDisplayId) &&
            imageReader != null &&
            (activeDisplayId != bridgeDisplayId || rootBridgeProcess?.isAlive == true)

    /**
     * 确保受信任虚拟屏已就绪并返回其 displayId；不可用时返回 null，由调用方平滑回退至物理主屏。
     */
    fun ensureVirtualDisplay(
        context: Context,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ): Int? {
        synchronized(lock) {
            if (hasUsableDisplayLocked()) {
                return activeDisplayId
            }
        }
        val appContext = context.applicationContext
        val (width, height, densityDpi) = resolvePhysicalDisplayMetrics(appContext)
        synchronized(lock) {
            if (hasUsableDisplayLocked()) {
                return activeDisplayId
            }
            releaseLocked(appContext, rootAvailable)

            val thread = HandlerThread("eta-virtual-display-frame").apply { start() }
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener(::onFrameAvailable, handler)

            frameThread = thread
            frameHandler = handler
            imageReader = reader
            displayWidth = width
            displayHeight = height
            displayDensityDpi = densityDpi

            // 有 Root 时优先走桥进程：建屏、定向输入与 IME 隔离都在这条本地已验证的链路上完成；
            // 桥失败或无 Root 时回退进程内创建（依赖 HiddenApiBypass / LSPosed 权限升级）。
            if (rootAvailable()) {
                val rootId = tryCreateRootBridgedVirtualDisplay(
                    appContext = appContext,
                    surface = reader.surface,
                    width = width,
                    height = height,
                    densityDpi = densityDpi,
                    rootAvailable = rootAvailable,
                )
                if (rootId != null && CanvasRoutingPolicy.isVirtualDisplay(rootId)) {
                    activeDisplayId = rootId
                    bridgeDisplayId = rootId
                    mutableSnapshots.value = VirtualDisplaySnapshot(
                        active = true,
                        displayId = rootId,
                        width = width,
                        height = height,
                    )
                    AndroidAgentLogger.info("Virtual display created via root bridge: displayId=$rootId size=${width}x$height")
                    return rootId
                }
            }

            val createdInProcess = tryCreateInProcessVirtualDisplay(
                appContext = appContext,
                surface = reader.surface,
                width = width,
                height = height,
                densityDpi = densityDpi,
            )
            if (createdInProcess != null) {
                val id = createdInProcess.display?.displayId ?: Display.INVALID_DISPLAY
                if (CanvasRoutingPolicy.isVirtualDisplay(id)) {
                    inProcessVirtualDisplay = createdInProcess
                    activeDisplayId = id
                    bridgeDisplayId = Display.INVALID_DISPLAY
                    mutableSnapshots.value = VirtualDisplaySnapshot(
                        active = true,
                        displayId = id,
                        width = width,
                        height = height,
                    )
                    AndroidAgentLogger.info("Virtual display created in-process: displayId=$id size=${width}x$height")
                    return id
                }
                runCatching { createdInProcess.release() }
            }

            releaseLocked(appContext, rootAvailable)
            return null
        }
    }

    /**
     * 在虚拟屏上启动指定应用，并对其施加后台音频焦点静默保护。
     */
    fun launchAppOnVirtualDisplay(
        context: Context,
        packageName: String,
        appName: String,
        launchIntent: Intent,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ): Int? {
        val displayId = ensureVirtualDisplay(context, rootAvailable) ?: return null
        val appContext = context.applicationContext
        // 目标包已在其它 display 上有可见任务时，单实例启动会把用户正在看的 Task 搬走；
        // 释放本次画布并让调用方回退主屏，避免留下一个空的后台屏。
        if (taskConflictDisplay(displayId, packageName) != null) {
            AndroidAgentLogger.info("Virtual display launch skipped: $packageName already has a visible task elsewhere")
            release(appContext, rootAvailable)
            return null
        }
        mutePackageAudioFocusIfPossible(appContext, packageName, rootAvailable)

        // 桥建屏时进程内 start 可能被跨屏栈迁移静默吞掉，直接使用本地验证过的 am start --display；
        // 进程内建屏则只走进程内 start，避免同一个 Intent 启动两次。
        val startedInProcess = if (isBridgeDisplay(displayId)) {
            false
        } else {
            runCatching {
                val intent = Intent(launchIntent).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                appContext.startActivity(intent, options.toBundle())
                true
            }.getOrElse { throwable ->
                AndroidAgentLogger.debug {
                    "Virtual display startActivity in-process fallback: type=${throwable.safeLogType()}"
                }
                false
            }
        }

        var startedByRoot = false
        if (!startedInProcess && rootAvailable()) {
            val userId = DeviceToolContract.appUserId(appContext)
            val component = launchIntent.component?.flattenToShortString()
            val target = if (!component.isNullOrBlank()) {
                "-n ${DeviceToolContract.quote(component)}"
            } else {
                "-p ${DeviceToolContract.quote(packageName)}"
            }
            val cmd = "am start -W --user $userId --display $displayId -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER $target"
            val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
            startedByRoot = try {
                exec.execute(cmd, timeoutMillis = 10_000L).exitCode == 0
            } catch (_: Throwable) {
                false
            } finally {
                exec.close()
            }
        }

        if (!startedInProcess && !startedByRoot) return null

        synchronized(lock) {
            val current = mutableSnapshots.value
            if (current.active && current.displayId == displayId) {
                mutableSnapshots.value = current.copy(
                    appName = appName,
                    packageName = packageName,
                    materializingToMain = false,
                )
            }
        }
        return displayId
    }

    /**
     * 在虚拟屏上打开外部 URI。
     */
    fun openUriOnVirtualDisplay(
        context: Context,
        intent: Intent,
        targetLabel: String,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ): Int? {
        val displayId = ensureVirtualDisplay(context, rootAvailable) ?: return null
        val appContext = context.applicationContext
        val started = runCatching {
            val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            appContext.startActivity(launch, options.toBundle())
            true
        }.getOrDefault(false)

        if (!started && rootAvailable()) {
            val uriString = intent.dataString ?: return null
            val userId = DeviceToolContract.appUserId(appContext)
            val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
            val ok = try {
                exec.execute(
                    "am start --user $userId --display $displayId -a android.intent.action.VIEW -d ${DeviceToolContract.quote(uriString)}",
                    timeoutMillis = 6_000L,
                ).exitCode == 0
            } finally {
                exec.close()
            }
            if (!ok) return null
        } else if (!started) {
            return null
        }

        synchronized(lock) {
            val current = mutableSnapshots.value
            if (current.active && current.displayId == displayId) {
                mutableSnapshots.value = current.copy(
                    appName = targetLabel.ifBlank { current.appName },
                    materializingToMain = false,
                )
            }
        }
        return displayId
    }

    /**
     * 将虚拟屏中正在运行的应用无缝破壁移交到物理主屏（DEFAULT_DISPLAY）。
     * 先触发 UI 层的 260ms 全屏放大贴合过渡，同时在底层原位迁移 RootTask。
     */
    fun handoffToMainDisplay(
        context: Context,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ): Boolean {
        val snapshot = synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || !CanvasRoutingPolicy.isVirtualDisplay(current.displayId)) {
                return false
            }
            mutableSnapshots.value = current.copy(
                userControlling = false,
                materializingToMain = true,
            )
            highFrequencyPreview = true
            current
        }

        val appContext = context.applicationContext
        restoreMutedPackages(appContext, rootAvailable)

        val moved = moveVirtualTaskToMainDisplay(
            context = appContext,
            displayId = snapshot.displayId,
            packageName = snapshot.packageName,
            rootAvailable = rootAvailable,
        )

        // 留出 260ms 让 VirtualDisplayMaterializeOverlay 完成从画板到物理全屏的弹簧贴合动画
        try {
            Thread.sleep(260L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        release(appContext, rootAvailable)
        return moved
    }

    /**
     * 提取虚拟屏最新一帧的完整 Bitmap 副本（供 observe_screen 使用，调用方负责 recycle）。
     */
    fun captureScreenshotBitmap(waitUpToMs: Long = 450L): Bitmap? {
        val deadline = SystemClock.elapsedRealtime() + waitUpToMs.coerceAtLeast(0L)
        while (true) {
            synchronized(lock) {
                // 观察可能落在 200ms 节流窗口内：先把暂存帧解码出来，返回的是动作后的画面。
                if (pendingFrame != null) retainPendingFrameLocked(SystemClock.elapsedRealtime())
                val bmp = latestFullBitmap
                if (bmp != null && !bmp.isRecycled) {
                    return runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                }
                if (!CanvasRoutingPolicy.isVirtualDisplay(activeDisplayId)) return null
            }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            try {
                Thread.sleep(40L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    fun emitTapEffect(x: Int, y: Int, longPress: Boolean = false) {
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || current.width <= 0 || current.height <= 0) return
            val nx = (x.toFloat() / current.width).coerceIn(0f, 1f)
            val ny = (y.toFloat() / current.height).coerceIn(0f, 1f)
            val effect = VirtualTouchEffect.Tap(
                sequence = touchSequenceGenerator.incrementAndGet(),
                createdAtMs = SystemClock.uptimeMillis(),
                normalizedX = nx,
                normalizedY = ny,
                longPress = longPress,
            )
            mutableSnapshots.value = current.copy(touchEffect = effect)
        }
    }

    fun emitSwipeEffect(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 420) {
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || current.width <= 0 || current.height <= 0) return
            val effect = VirtualTouchEffect.Swipe(
                sequence = touchSequenceGenerator.incrementAndGet(),
                createdAtMs = SystemClock.uptimeMillis(),
                startX = (x1.toFloat() / current.width).coerceIn(0f, 1f),
                startY = (y1.toFloat() / current.height).coerceIn(0f, 1f),
                endX = (x2.toFloat() / current.width).coerceIn(0f, 1f),
                endY = (y2.toFloat() / current.height).coerceIn(0f, 1f),
                durationMs = durationMs.coerceIn(160, 1_500),
            )
            mutableSnapshots.value = current.copy(touchEffect = effect)
        }
    }

    /**
     * 用户在画廊视窗画布上直接触摸时，将归一化坐标注入虚拟屏。
     */
    fun dispatchUserTapOnCanvas(
        normalizedX: Float,
        normalizedY: Float,
        longPress: Boolean = false,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ) {
        val snapshot = mutableSnapshots.value
        if (!snapshot.active || !CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId)) return
        val x = (normalizedX.coerceIn(0f, 1f) * (snapshot.width - 1)).roundToInt()
        val y = (normalizedY.coerceIn(0f, 1f) * (snapshot.height - 1)).roundToInt()
        emitTapEffect(x, y, longPress = longPress)
        thread(name = "eta-vd-user-tap", isDaemon = true) {
            val duration = if (longPress) 650L else 55L
            if (injectTouch(snapshot.displayId, x, y, durationMs = duration.toInt())) return@thread
            val dispatched = AgentAccessibilityService.current()
                ?.gestureTap(x.toFloat(), y.toFloat(), durationMs = duration, displayId = snapshot.displayId)
                ?.ok == true
            if (!dispatched && rootAvailable()) {
                val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
                try {
                    val cmd = if (longPress) {
                        "input -d ${snapshot.displayId} swipe $x $y $x $y 650"
                    } else {
                        "input -d ${snapshot.displayId} tap $x $y"
                    }
                    exec.execute(cmd, timeoutMillis = 5_000L)
                } finally {
                    exec.close()
                }
            }
        }
    }

    fun dispatchUserSwipeOnCanvas(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Int = 320,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ) {
        val snapshot = mutableSnapshots.value
        if (!snapshot.active || !CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId)) return
        val x1 = (startX.coerceIn(0f, 1f) * (snapshot.width - 1)).roundToInt()
        val y1 = (startY.coerceIn(0f, 1f) * (snapshot.height - 1)).roundToInt()
        val x2 = (endX.coerceIn(0f, 1f) * (snapshot.width - 1)).roundToInt()
        val y2 = (endY.coerceIn(0f, 1f) * (snapshot.height - 1)).roundToInt()
        val safeDuration = durationMs.coerceIn(140, 1_000)
        emitSwipeEffect(x1, y1, x2, y2, safeDuration)
        thread(name = "eta-vd-user-swipe", isDaemon = true) {
            if (injectSwipe(snapshot.displayId, x1, y1, x2, y2, safeDuration)) return@thread
            val dispatched = AgentAccessibilityService.current()
                ?.gestureSwipe(
                    x1.toFloat(),
                    y1.toFloat(),
                    x2.toFloat(),
                    y2.toFloat(),
                    durationMs = safeDuration.toLong(),
                    displayId = snapshot.displayId,
                )?.ok == true
            if (!dispatched && rootAvailable()) {
                val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
                try {
                    exec.execute(
                        "input -d ${snapshot.displayId} swipe $x1 $y1 $x2 $y2 $safeDuration",
                        timeoutMillis = 5_000L,
                    )
                } finally {
                    exec.close()
                }
            }
        }
    }

    fun release(
        context: Context? = null,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ) {
        synchronized(lock) {
            releaseLocked(context, rootAvailable)
        }
    }

    private fun releaseLocked(
        context: Context?,
        rootAvailable: () -> Boolean,
    ) {
        highFrequencyPreview = false
        activeDisplayId = Display.DEFAULT_DISPLAY
        bridgeDisplayId = Display.INVALID_DISPLAY
        mutableSnapshots.value = VirtualDisplaySnapshot.Inactive

        // 桥即将关闭：唤醒等待中的输入请求，避免它们等满超时。
        synchronized(bridgeLock) {
            pendingBridgeRequestId = -1L
            pendingBridgeResponse = JSONObject().put("ok", false).put("code", "BRIDGE_CLOSED")
            bridgeLock.notifyAll()
        }

        if (context != null && mutedPackages.isNotEmpty()) {
            restoreMutedPackages(context.applicationContext, rootAvailable)
        } else {
            mutedPackages.clear()
        }

        inProcessVirtualDisplay?.let { vd ->
            runCatching { vd.release() }
        }
        inProcessVirtualDisplay = null

        val bridge = rootBridgeProcess
        val supervisor = processSupervisor
        rootBridgeProcess = null
        processSupervisor = null
        if (bridge != null) {
            thread(name = "eta-vd-bridge-cleanup", isDaemon = true) {
                runCatching {
                    // 与 bridgeSend 共用同一把写锁，避免 release 与在途命令的字节交错。
                    synchronized(bridgeLock) {
                        bridge.outputStream.write("release\n".toByteArray(Charsets.UTF_8))
                        bridge.outputStream.flush()
                        bridge.outputStream.close()
                    }
                }
                if (supervisor != null) {
                    supervisor.beginClosing()
                    if (bridge.isAlive) supervisor.terminateAndReap(bridge) else supervisor.reapProcess(bridge)
                    supervisor.unregisterProcess(bridge)
                } else {
                    runCatching { bridge.destroyForcibly() }
                }
            }
        }

        imageReader?.let { reader ->
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
        }
        imageReader = null

        frameThread?.let { thread ->
            runCatching { thread.quitSafely() }
        }
        frameThread = null
        frameHandler = null

        pendingFrame?.let { frame -> runCatching { frame.close() } }
        pendingFrame = null
        lastRetainedAtElapsedMs = 0L
        latestFullBitmap?.let { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        latestFullBitmap = null
        lastUiFrameAtElapsedMs = 0L
    }

    private fun onFrameAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

        var replaced: Image? = null
        var closeDirectly = false
        try {
            runCatching {
                synchronized(lock) {
                    if (!CanvasRoutingPolicy.isVirtualDisplay(activeDisplayId)) {
                        closeDirectly = true
                        return@synchronized
                    }
                    // 旧帧留给锁外关闭；最新帧只暂存一张，完整 Bitmap 按节流保留，避免每帧复制整屏像素。
                    replaced = pendingFrame
                    pendingFrame = image
                    val now = SystemClock.elapsedRealtime()
                    val retainIntervalMs = if (highFrequencyPreview) 30L else FRAME_RETAIN_INTERVAL_MS
                    if (latestFullBitmap == null || now - lastRetainedAtElapsedMs >= retainIntervalMs) {
                        retainPendingFrameLocked(now)
                    }
                }
            }.onFailure { throwable ->
                // 帧回调线程没有调用方接管异常：单帧失败只丢这一帧，不能让 HandlerThread 终止进程。
                AndroidAgentLogger.debug {
                    "Virtual display frame dropped: type=${throwable.safeLogType()}"
                }
            }
        } finally {
            runCatching { replaced?.close() }
            if (closeDirectly) runCatching { image.close() }
        }
    }

    /**
     * 解码暂存帧并更新快照；调用方必须持有 [lock]。
     *
     * 后台屏的完整画面最多每 [FRAME_RETAIN_INTERVAL_MS] 保留一次，静止时不再每帧复制十几 MB 像素；
     * 展开画布或用户接管时跟随高频预览刷新，观察请求也会先调用本方法 flush，动作后的截图始终是最新画面。
     */
    private fun retainPendingFrameLocked(now: Long): Boolean {
        val pending = pendingFrame ?: return false
        pendingFrame = null
        val bitmap = try {
            decodeImageToBitmap(pending)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { pending.close() }
        } ?: return false

        val previous = latestFullBitmap
        latestFullBitmap = bitmap
        lastRetainedAtElapsedMs = now
        previous?.let { if (!it.isRecycled) it.recycle() }

        val minIntervalMs = if (highFrequencyPreview) 30L else 160L
        if (now - lastUiFrameAtElapsedMs >= minIntervalMs) {
            lastUiFrameAtElapsedMs = now
            // 预览缩放失败只影响这一帧的 UI，不应波及已保留的完整画面或调用线程。
            runCatching {
                val previewBitmap = createUiPreviewBitmap(bitmap)
                val current = mutableSnapshots.value
                if (current.active) {
                    mutableSnapshots.value = current.copy(
                        frame = previewBitmap.asImageBitmap(),
                        frameSequence = frameSequenceGenerator.incrementAndGet(),
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.debug {
                    "Virtual display preview dropped: type=${throwable.safeLogType()}"
                }
            }
        }
        return true
    }

    private fun decodeImageToBitmap(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer ?: return null
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        if (paddedWidth < width) return null

        val rawBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        rawBitmap.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == width) {
            rawBitmap
        } else {
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
            rawBitmap.recycle()
            cropped
        }
    }

    private fun createUiPreviewBitmap(source: Bitmap): Bitmap {
        val maxTargetHeight = if (highFrequencyPreview) 1080 else 480
        if (source.height <= maxTargetHeight) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val ratio = maxTargetHeight.toFloat() / source.height.toFloat()
        val targetW = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val targetH = maxTargetHeight
        return source.scale(targetW, targetH)
    }

    @android.annotation.SuppressLint("WrongConstant")
    private fun tryCreateInProcessVirtualDisplay(
        appContext: Context,
        surface: android.view.Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): VirtualDisplay? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/display/") }
        }
        val dm = appContext.getSystemService(DisplayManager::class.java) ?: return@runCatching null
        val flags = VirtualDisplayFlags.interactive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = VirtualDisplayConfig.Builder(
                CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
            )
                .setSurface(surface)
                .setFlags(flags)
                .build()
            dm.createVirtualDisplay(config)
        } else {
            dm.createVirtualDisplay(
                CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                surface,
                flags,
            )
        }
    }.getOrNull()

    private fun tryCreateRootBridgedVirtualDisplay(
        appContext: Context,
        surface: android.view.Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        rootAvailable: () -> Boolean,
    ): Int? {
        val token = VirtualDisplaySurfaceRegistry.register(surface)
        val supervisor = ShellProcessSupervisor(rootAvailable = rootAvailable)
        val apkPath = appContext.applicationInfo.sourceDir
        val cmd = "CLASSPATH=${DeviceToolContract.quote(apkPath)} app_process /system/bin " +
            "io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayCommandMain"
        val process = supervisor.startShellProcess("root", cmd, mergeStderr = true)
        if (process == null) {
            VirtualDisplaySurfaceRegistry.unregister(token)
            return null
        }

        val readyLatch = CountDownLatch(1)
        var resolvedDisplayId: Int? = null
        val payload = JSONObject()
            .put("action", "hold_display")
            .put("user_id", DeviceToolContract.appUserId(appContext))
            .put("surface_token", token)
            .put("width", width)
            .put("height", height)
            .put("density_dpi", densityDpi)
            .toString() + "\n"

        thread(name = "eta-vd-bridge-stdout", isDaemon = true) {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith(VirtualDisplayCommandMain.READY_PREFIX)) {
                            resolvedDisplayId = line
                                .removePrefix(VirtualDisplayCommandMain.READY_PREFIX)
                                .trim()
                                .toIntOrNull()
                            readyLatch.countDown()
                        } else if (line.startsWith(VirtualDisplayCommandMain.RESULT_PREFIX)) {
                            val payload = runCatching {
                                JSONObject(line.removePrefix(VirtualDisplayCommandMain.RESULT_PREFIX))
                            }.getOrNull()
                            val responseId = payload?.optLong("id", -1L) ?: -1L
                            if (payload != null && responseId >= 0L) {
                                synchronized(bridgeLock) {
                                    if (responseId == pendingBridgeRequestId) {
                                        pendingBridgeResponse = payload
                                        bridgeLock.notifyAll()
                                    }
                                }
                            } else {
                                // 没有请求 id 的 RESULT 是建屏阶段的失败应答。
                                readyLatch.countDown()
                            }
                        }
                    }
                }
            }
            readyLatch.countDown()
            // 桥进程退出（崩溃或 stdin 关闭）时唤醒在途请求，避免调用方等满超时。
            synchronized(bridgeLock) {
                if (pendingBridgeRequestId >= 0L) {
                    pendingBridgeResponse = JSONObject().put("ok", false).put("code", "BRIDGE_CLOSED")
                    bridgeLock.notifyAll()
                }
            }
        }

        val written = runCatching {
            process.outputStream.write(payload.toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            true
        }.getOrDefault(false)

        if (!written) {
            VirtualDisplaySurfaceRegistry.unregister(token)
            supervisor.beginClosing()
            supervisor.terminateAndReap(process)
            supervisor.unregisterProcess(process)
            return null
        }

        val awaited = runCatching { readyLatch.await(4, TimeUnit.SECONDS) }.getOrDefault(false)
        val id = resolvedDisplayId
        if (!awaited || id == null || !CanvasRoutingPolicy.isVirtualDisplay(id)) {
            VirtualDisplaySurfaceRegistry.unregister(token)
            supervisor.beginClosing()
            if (process.isAlive) supervisor.terminateAndReap(process) else supervisor.reapProcess(process)
            supervisor.unregisterProcess(process)
            return null
        }

        rootBridgeProcess = process
        processSupervisor = supervisor
        return id
    }

    private fun moveVirtualTaskToMainDisplay(
        context: Context,
        displayId: Int,
        packageName: String,
        rootAvailable: () -> Boolean,
    ): Boolean {
        // 1. 尝试进程内通过 system_server Hook 放行的 moveRootTaskToDisplay 迁移
        val movedInProcess = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityTaskManager", "Landroid/app/IActivityTaskManager")
            }
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = atmClass.getMethod("getService").invoke(null) ?: return@runCatching false
            val getTasks = service.javaClass.methods.firstOrNull { it.name == "getTasks" && it.parameterCount >= 1 }
            val tasks = when (getTasks?.parameterCount) {
                1 -> getTasks.invoke(service, 40)
                3 -> getTasks.invoke(service, 40, false, false)
                4 -> getTasks.invoke(service, 40, false, false, Display.INVALID_DISPLAY)
                else -> null
            } as? List<*> ?: emptyList<Any?>()
            var targetTaskId = -1
            for (item in tasks) {
                val info = item as? android.app.TaskInfo ?: continue
                val taskDisplay = runCatching {
                    android.app.TaskInfo::class.java.getField("displayId").getInt(info)
                }.getOrDefault(Display.INVALID_DISPLAY)
                val topPkg = info.topActivity?.packageName ?: info.baseActivity?.packageName
                if (taskDisplay == displayId || (packageName.isNotEmpty() && topPkg == packageName)) {
                    targetTaskId = info.taskId
                    if (taskDisplay == displayId) break
                }
            }
            if (targetTaskId > 0) {
                val moveMethod = service.javaClass.getMethod(
                    "moveRootTaskToDisplay",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                moveMethod.invoke(service, targetTaskId, Display.DEFAULT_DISPLAY)
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (movedInProcess) return true

        // 2. Root 特权桥调用 moveRootTaskToDisplay
        if (rootAvailable()) {
            val apkPath = context.applicationInfo.sourceDir
            val cmd = "CLASSPATH=${DeviceToolContract.quote(apkPath)} app_process /system/bin " +
                "io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayCommandMain"
            val req = JSONObject()
                .put("action", "move_to_main")
                .put("display_id", displayId)
                .put("package_name", packageName)
                .toString() + "\n"
            val supervisor = ShellProcessSupervisor(rootAvailable = rootAvailable)
            val proc = supervisor.startShellProcess("root", cmd, mergeStderr = true)
            if (proc != null) {
                val movedByRoot = try {
                    proc.outputStream.write(req.toByteArray(Charsets.UTF_8))
                    proc.outputStream.flush()
                    proc.outputStream.close()
                    val out = proc.inputStream.bufferedReader().readText()
                    proc.waitFor(4, TimeUnit.SECONDS)
                    out.contains("\"ok\":true")
                } catch (_: Throwable) {
                    false
                } finally {
                    supervisor.beginClosing()
                    if (proc.isAlive) supervisor.terminateAndReap(proc) else supervisor.reapProcess(proc)
                    supervisor.unregisterProcess(proc)
                }
                if (movedByRoot) return true
            }
        }

        // 3. 兜底：以 SINGLE_TOP + NEW_TASK 在 DEFAULT_DISPLAY 唤起该应用
        if (packageName.isNotBlank()) {
            return runCatching {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    ?: return@runCatching false
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                context.startActivity(intent, options.toBundle())
                true
            }.getOrDefault(false)
        }
        return false
    }

    private fun mutePackageAudioFocusIfPossible(
        context: Context,
        packageName: String,
        rootAvailable: () -> Boolean,
    ) {
        if (!rootAvailable() || !DeviceToolContract.validPackageName(packageName)) return
        val added = synchronized(lock) { mutedPackages.add(packageName) }
        if (!added) return
        val userId = DeviceToolContract.appUserId(context)
        thread(name = "eta-vd-audio-shield", isDaemon = true) {
            val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
            try {
                exec.execute(
                    "cmd appops set --user $userId ${DeviceToolContract.quote(packageName)} TAKE_AUDIO_FOCUS ignore",
                    timeoutMillis = 3_000L,
                )
            } catch (_: Throwable) {
                // 忽略部分 ROM appops 差异
            } finally {
                exec.close()
            }
        }
    }

    private fun restoreMutedPackages(
        context: Context,
        rootAvailable: () -> Boolean,
    ) {
        val toRestore = synchronized(lock) {
            val copy = mutedPackages.toList()
            mutedPackages.clear()
            copy
        }
        if (toRestore.isEmpty() || !rootAvailable()) return
        val userId = DeviceToolContract.appUserId(context)
        thread(name = "eta-vd-audio-restore", isDaemon = true) {
            val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
            try {
                for (pkg in toRestore) {
                    if (!DeviceToolContract.validPackageName(pkg)) continue
                    exec.execute(
                        "cmd appops set --user $userId ${DeviceToolContract.quote(pkg)} TAKE_AUDIO_FOCUS allow",
                        timeoutMillis = 3_000L,
                    )
                }
            } catch (_: Throwable) {
            } finally {
                exec.close()
            }
        }
    }

    private fun resolvePhysicalDisplayMetrics(context: Context): Triple<Int, Int, Int> {
        val dm = context.resources.displayMetrics
        var width = dm.widthPixels.coerceAtLeast(720)
        var height = dm.heightPixels.coerceAtLeast(1280)
        val density = dm.densityDpi.coerceIn(160, 640)
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val point = Point()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealSize(point)
            if (point.x > 0 && point.y > 0) {
                width = point.x
                height = point.y
            }
        }
        return Triple(width, height, density)
    }
}
