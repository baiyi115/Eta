package io.github.mangi.eta.agent.phoneuse

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal class ForegroundPhoneUseBackend(
    private val context: Context,
    private val execute: (AgentModelClient.ToolCall) -> AgentModelClient.ToolResult,
    private val checkActive: () -> Unit,
) : PhoneUseBackend {
    private val closed = AtomicBoolean(false)
    private fun active() {
        checkActive()
        if (closed.get()) throw PhoneUseFailure("SESSION_CLOSED", "手机操作已取消。")
        PhoneUseScreenGuard.unlocked(context)
    }

    override fun observe(): PhoneUseFrame {
        active()
        val result = call("observe_screen", JSONObject().put("include_screenshot", true).put("include_ui_tree", true).put("max_nodes", 250))
        val json = JSONObject(result.content)
        val screenshot = json.optJSONObject("screenshot")
        // Root screencap may silently black out FLAG_SECURE. Only window capture can attest completeness.
        if (screenshot?.optString("source") != "accessibility") {
            throw PhoneUseFailure("SAFE_SCREENSHOT_UNAVAILABLE", "无法取得可验证的无障碍窗口截图，需要 Android 14 或更高版本及截图权限。")
        }
        if (screenshot.optBoolean("critical_window_missing")) {
            throw PhoneUseFailure("INCOMPLETE_SCREENSHOT", "顶层应用或安全授权窗口不可完整观察，已停止。")
        }
        // Non-critical windows are drawn as opaque safety regions. They must not block an otherwise usable frame.
        if (!screenshot.optBoolean("complete") && !screenshot.optBoolean("partial")) {
            throw PhoneUseFailure("INCOMPLETE_SCREENSHOT", "无障碍截图状态不一致，已停止。")
        }
        val image = result.images.singleOrNull()
            ?: throw PhoneUseFailure("SCREENSHOT_UNAVAILABLE", "没有完整屏幕截图，已停止。")
        val screen = json.getJSONObject("screen")
        val foreground = json.optJSONObject("focus")?.optString("package").orEmpty()
        val snapshot = AgentAccessibilityService.current()?.captureDisplayNodes(0, foreground)
        val semantic = snapshot?.let(PhoneUseScreenGuard::semanticContent)
            ?: JSONObject().put("ui_nodes", JSONArray()).put("window_id", -1).put("ui_tree_truncated", false)
        semantic.keys().forEach { key -> json.put(key, semantic.get(key)) }
        val nodes = json.getJSONArray("ui_nodes")
        json.put("ui_tree_status", if (snapshot == null) "unavailable" else if (snapshot.truncated) "partial" else "available")
        PhoneUseScreenGuard.safeNodes(nodes, foreground)
        active()
        val width = screen.getInt("width")
        val height = screen.getInt("height")
        // Strip the older primitive-tool 0..999 hint; this protocol owns its own 0..1000 contract.
        json.remove("coordinate_contract")
        return PhoneUseFrame(json, image, width, height, 0, foreground, nodes.toString(),
            PhoneUseScreenGuard.visualAnchor(image, width, height))
    }

    override fun confirm(action: PhoneUseAction, frame: PhoneUseFrame, goal: String): Boolean {
        active()
        val service = AgentAccessibilityService.current() ?: return false
        return service.confirmPhoneUseAction(PhoneUsePermissionPolicy.confirmationText(goal, action, frame), frame.image.reference) {
            !closed.get() && AgentAccessibilityService.current() === service && runCatching { active() }.isSuccess
        }
    }

    override fun perform(action: PhoneUseAction, frame: PhoneUseFrame) {
        active()
        if (action.name !in setOf("launch", "wait", "home") && frame.foreground.isBlank()) {
            throw PhoneUseFailure("FOREGROUND_UNAVAILABLE", "无法确认前台应用，已停止。")
        }
        fun point(name: String, point: Pair<Double, Double>, duration: Int? = null) {
            active()
            val args = JSONObject().put("x", point.first.toInt()).put("y", point.second.toInt()).put("coordinate_space", "screen")
            if (duration != null) args.put("duration_ms", duration)
            call(name, args)
        }
        when (action.name) {
            "tap" -> point("tap", checkNotNull(action.element))
            "double_tap" -> {
                point("tap", checkNotNull(action.element))
                Thread.sleep(100)
                active()
                if (!frame.sameTarget(observe(), action)) {
                    throw PhoneUseFailure("DOUBLE_TAP_PAGE_CHANGED", "双击首击已执行，页面或目标随后变化，未执行第二击；请先检查当前页面。")
                }
                point("tap", action.element)
            }
            "long_press" -> point("long_press", checkNotNull(action.element), action.durationMs)
            "swipe" -> call("swipe", JSONObject().put("x1", checkNotNull(action.start).first.toInt())
                .put("y1", action.start.second.toInt()).put("x2", checkNotNull(action.end).first.toInt())
                .put("y2", action.end.second.toInt()).put("duration_ms", action.durationMs).put("coordinate_space", "screen"))
            "type" -> {
                val result = AgentAccessibilityService.current()?.setPhoneUseForegroundText(frame.foreground, action.text) {
                    !closed.get() && runCatching { active() }.isSuccess
                } ?: throw PhoneUseFailure("ACCESSIBILITY_UNAVAILABLE", "文本输入需要无障碍服务。")
                if (!result.ok || result.verified != true) {
                    val code = result.code.ifBlank { "TEXT_NOT_VERIFIED" }
                    val reason = result.message.ifBlank { "未能确认输入结果；文字可能已写入，请先查看输入框，避免重复输入" }
                    throw PhoneUseFailure(code, "文本输入已停止：$reason（$code）", result.visualReviewEligible)
                }
            }
            "back", "home" -> call("press_key", JSONObject().put("button", action.name.uppercase(java.util.Locale.ROOT)))
            "launch" -> call("launch_app", JSONObject().put("package_name", action.app))
            "wait" -> waitFor(action.durationMs)
            else -> throw PhoneUseFailure("UNSUPPORTED_ACTION", "不支持的手机动作。")
        }
    }

    private fun waitFor(durationMs: Int) {
        val deadline = android.os.SystemClock.elapsedRealtime() + durationMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) { active(); Thread.sleep(25) }
    }

    private fun call(name: String, args: JSONObject): AgentModelClient.ToolResult {
        active()
        val result = execute(AgentModelClient.ToolCall("phone-use-internal", name, args.toString()))
        val json = JSONObject(result.content)
        if (!json.optBoolean("ok")) throw PhoneUseFailure(json.optString("code", "ACTION_FAILED"), "手机动作未完成，已停止以避免重复操作。")
        active()
        return result
    }
    override fun close() { closed.set(true) }
}
