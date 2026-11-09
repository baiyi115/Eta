package io.github.mangi.eta.agent.virtualdisplay

import android.view.Display
import java.util.Locale

/**
 * 虚拟屏与物理主屏的空间路由契约。
 *
 * 核心原则：
 * 1. 原地延续走物理主屏：目标应用已经处于物理主屏前台（用户正在注视）时，直接在主屏操作，避免抽走 Task 导致闪退回桌面。
 * 2. 系统全局界面走物理主屏：桌面、SystemUI、Eta 自身留在主屏。
 * 3. 跨应用委托默认走后台虚拟屏：从 Eta 聊天页发起，或在 A 应用唤醒但需打开后台 B 应用时，在虚拟屏静默执行。
 * 4. 临门一脚台前呈现：遇到收银台、安全窗口（FLAG_SECURE）或模型调用 present_to_user 时，原位迁移回物理主屏。
 */
internal object CanvasRoutingPolicy {

    const val VIRTUAL_DISPLAY_NAME = "EtaAgentVirtualDisplay"

    // 建屏标志位不在这里维护：ROM 会改写这些比特（例如 DEVICE_DISPLAY_GROUP 与
    // STEAL_TOP_FOCUS_DISABLED 都曾占用 1 shl 15），唯一来源是 VirtualDisplayFlags.interactive()。

    /** android.view.Display.getFlags() 返回的受信任标志位，仅用于日志核对虚拟屏是否取得系统级 TRUSTED 权限。 */
    const val DISPLAY_FLAG_TRUSTED = 1 shl 7
    const val DISPLAY_FLAG_OWN_FOCUS = 1 shl 11
    const val DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 12

    /** WindowManager IME 显示策略：禁止虚拟屏在主屏回退弹出或抢占软键盘。 */
    const val DISPLAY_IME_POLICY_LOCAL = 0
    const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1
    const val DISPLAY_IME_POLICY_HIDE = 2

    // Android 13 尚无独立焦点与禁止抢占顶层焦点的组合能力，后台画布从 Android 14 起启用。
    fun supportsBackgroundDisplay(sdkInt: Int): Boolean = sdkInt >= 34

    fun hasRequiredDisplayFlags(flags: Int): Boolean {
        val required = DISPLAY_FLAG_TRUSTED or DISPLAY_FLAG_OWN_FOCUS or DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED
        return flags and required == required
    }

    private val MAIN_DISPLAY_ONLY_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.miui.home",
        "com.oppo.launcher",
        "com.bbk.launcher2",
        "com.huawei.android.launcher",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
    )

    enum class CanvasTarget(val wireName: String) {
        MAIN_DISPLAY("main"),
        VIRTUAL_DISPLAY("virtual"),
    }

    enum class RequestedDisplay {
        AUTO,
        VIRTUAL,
        MAIN;

        companion object {
            fun parse(raw: String?): RequestedDisplay? = when (raw?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
                "", "auto" -> AUTO
                "virtual", "background" -> VIRTUAL
                "main", "foreground", "physical" -> MAIN
                else -> null
            }
        }
    }

    fun decideLaunchTarget(
        virtualDisplayEnabled: Boolean,
        backendAvailable: Boolean,
        requested: RequestedDisplay = RequestedDisplay.AUTO,
        targetPackage: String? = null,
        foregroundPackageOnMainDisplay: String? = null,
        ownPackage: String = "io.github.mangi.eta",
    ): CanvasTarget {
        if (!virtualDisplayEnabled || !backendAvailable) return CanvasTarget.MAIN_DISPLAY
        if (requested == RequestedDisplay.MAIN) return CanvasTarget.MAIN_DISPLAY
        val normalizedTarget = targetPackage?.trim().orEmpty()
        if (normalizedTarget.isNotEmpty()) {
            if (normalizedTarget == ownPackage || normalizedTarget in MAIN_DISPLAY_ONLY_PACKAGES) {
                return CanvasTarget.MAIN_DISPLAY
            }
            // 同包名应用正处于物理主屏前台：若在虚拟屏启动，Android 会将该 Task 从主屏抽走，直接破坏用户当前视线。
            val normalizedForeground = foregroundPackageOnMainDisplay?.trim().orEmpty()
            if (normalizedForeground.isNotEmpty() && normalizedTarget == normalizedForeground) {
                return CanvasTarget.MAIN_DISPLAY
            }
        }
        return when (requested) {
            RequestedDisplay.VIRTUAL,
            RequestedDisplay.AUTO -> CanvasTarget.VIRTUAL_DISPLAY
            RequestedDisplay.MAIN -> CanvasTarget.MAIN_DISPLAY
        }
    }

    fun isVirtualDisplay(displayId: Int): Boolean =
        displayId != Display.DEFAULT_DISPLAY && displayId > 0
}
