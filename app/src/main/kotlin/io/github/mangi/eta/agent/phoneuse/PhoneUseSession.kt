package io.github.mangi.eta.agent.phoneuse

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Per-run, serial protocol. close() may interrupt a backend concurrently with execute(). */
internal class PhoneUseSession(
    private val factory: (String) -> PhoneUseBackend,
    private val checkActive: () -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    @Volatile private var backend: PhoneUseBackend? = null
    private var owner = ""
    private var goal = ""
    private var pageChanges = 0
    private var previousAction = ""
    private var repeats = 0
    private val expired = AtomicBoolean(false)
    private val idleLease = PhoneUseIdleLease { expired.set(true); closed.set(true); backend?.close() }
    private var session = ""
    private var observation = ""
    private var frame: PhoneUseFrame? = null
    private var remaining = 0
    private var expiresAt = 0L
    private var stopped = false
    private val selectedScreenshots = linkedSetOf<String>()
    private data class InputAttempt(val action: PhoneUseAction, val before: PhoneUseFrame, val manual: Boolean)
    private data class PendingInput(val attempt: InputAttempt, val diagnostic: String, var checks: Int = 0)
    private var pendingInput: PendingInput? = null

    val hasSession: Boolean get() = session.isNotEmpty()

    fun execute(tool: String, args: JSONObject): AgentModelClient.ToolResult {
        var inputAttempt: InputAttempt? = null
        return try {
            active()
            if (stopped) return failure("SESSION_STOPPED", "手机操作会话已经结束。", terminal = true)
            if (session.isEmpty()) {
                require(!args.has("session") && !args.has("action")) { "首次调用只提供 goal 和可选 max_steps" }
                require(args.getString("goal").isNotBlank()) { "goal 不能为空" }
                require(args.getString("goal").length <= 8000) { "goal 过长" }
                goal = args.getString("goal")
                remaining = integer(args, "max_steps", 30, 1, 200)
                owner = tool
                session = UUID.randomUUID().toString()
                val created = backendCall { factory(tool) }
                backend = created
                active() // close racing with factory must also release the newly created backend.
                backendCall { created.start() }
                return observe(backendCall { created.observe() })
            }
            require(tool == owner) { "会话不能切换前台/后台工具" }
            require(args.getString("session") == session) { "session 无效或属于另一次运行" }
            if (now() >= expiresAt) return failure("SESSION_EXPIRED", "手机操作会话空闲超时，已停止。", true)
            require(args.getString("observation_id") == observation) { "observation_id 已过期，请使用最近返回的观察" }
            val action = PhoneUseAction.parse(args)
            pendingInput?.let { pending ->
                if (action.name == "verify_input") return verifyInput(action, pending)
                if (action.name !in setOf("ask_user", "take_over")) {
                    remaining--
                    pending.checks++
                    if (remaining <= 0 || pending.checks >= 3) {
                        return textReadbackFailure(PhoneUseFailure("INPUT_REVIEW_REQUIRED", "输入尚未完成核对，未重复输入或继续提交。"))
                    }
                    return inputReview(checkNotNull(frame), "输入已尝试写入。请先读图并用verify_input核对，禁止再次type、点击搜索/提交或finish；无法核对时交给用户。")
                }
            }
            require(action.name != "verify_input") { "当前没有待核对的输入，verify_input不能写入文字或自行创建核对状态" }
            if (action.name == "attach_screenshot") {
                val selected = checkNotNull(frame)
                require(selected.image.reference in selectedScreenshots || selectedScreenshots.size < 7) {
                    "已保留7张截图；finish还可附上最后一张。请完成任务并在当前聊天回复。"
                }
                selectedScreenshots.add(selected.image.reference)
                // Publish exactly the frame the model saw, never capture another display here.
                val observed = observe(selected)
                return observed.copy(
                    replyImages = listOf(selected.image),
                    content = JSONObject(observed.content)
                        .put("message", "已保留本页截图，任务成功结束后随回复发送到当前聊天；可继续读取其他页面。")
                        .put("reply_screenshot_count", selectedScreenshots.size).toString(),
                )
            }
            if (action.name == "finish") {
                // The previous observation image was consumed by the action-planning round.
                // Retain that evidence before stop() releases the session and clears frame.
                val lastFrame = checkNotNull(frame)
                stop()
                return AgentModelClient.ToolResult(
                    JSONObject().put("ok", true).put("status", "COMPLETED")
                        .put("reason", "finish").put("message", action.message)
                        .put("observation", lastFrame.content)
                        .put("completion_verified", false).toString(),
                    images = listOf(lastFrame.image), sensitive = true, finalizeResponse = true,
                    replyImages = if (action.attachScreenshot) listOf(lastFrame.image) else emptyList(),
                )
            }
            if (action.name in TERMINAL_ACTIONS) {
                val message = when (action.name) {
                    "ask_user" -> "手机操作已暂停，需要用户补充信息。"
                    else -> "手机操作已暂停，请用户接管。"
                } + action.message.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                stop()
                return AgentModelClient.ToolResult(
                    JSONObject().put("ok", true).put("status", "STOPPED")
                        .put("reason", action.name).put("message", message).toString(),
                    sensitive = true, stopMessage = message,
                )
            }
            if (remaining == 0) return failure("STEP_LIMIT", "手机操作步数已用完，已停止。", true)
            val previous = checkNotNull(frame)
            var current = backendCall { checkNotNull(backend).observe() }
            active()
            if (!previous.sameTarget(current, action)) return pageChanged(current)
            var translated = action.inScreenPixels(current)
            if (PhoneUsePermissionPolicy.needsConfirmation(translated, current)) {
                if (PhoneUsePermissionPolicy.systemAuthorization(current) &&
                    (current.content.optBoolean("ui_tree_truncated") || PhoneUseTargetGuard.nodes(current).isEmpty())) {
                    return failure("AUTHORIZATION_UNOBSERVED", "系统授权界面无法完整核对，请用户手动接管；未执行授权。", true)
                }
                val approved = backendCall { checkNotNull(backend).confirm(translated, current, goal) }
                active()
                if (!approved) return failure("USER_APPROVAL_REQUIRED", "本步未获用户确认（已取消、超时或确认窗口不可用），动作未执行，手机操作已停止。", true)
                val afterApproval = backendCall { checkNotNull(backend).observe() }
                active()
                // Approval is bound to this exact candidate; it never authorizes another prompt or field.
                if (!current.sameTarget(afterApproval, action) ||
                    (PhoneUsePermissionPolicy.systemAuthorization(current) && current.treeSignature != afterApproval.treeSignature)) {
                    return pageChanged(afterApproval, "确认期间页面或目标已变化，本步未执行；旧确认已作废，请按新观察重新规划。")
                }
                current = afterApproval
                translated = action.inScreenPixels(current)
            }
            val fingerprint = actionFingerprint(translated, current)
            if (action.name != "wait" && fingerprint == previousAction) repeats++ else repeats = 0
            if (repeats >= 3) return failure("NO_PROGRESS", "同一目标上的相同动作连续没有可确认进展，已停止，避免重复提交或反复点击。", true)
            previousAction = fingerprint
            remaining-- // Reserve before dispatch, including ambiguous/partially delivered actions.
            observation = ""
            if (translated.name == "type") {
                inputAttempt = InputAttempt(translated, current, PhoneUsePermissionPolicy.needsConfirmation(translated, current))
            }
            backendCall { checkNotNull(backend).perform(translated, current) }
            pageChanges = 0
            // Gesture completion is not animation completion. Give the application a frame-settling interval.
            if (action.name != "wait") {
                val settle = System.nanoTime() + 350_000_000L
                while (System.nanoTime() < settle) { active(); Thread.sleep(25) }
            }
            active()
            observe(backendCall { checkNotNull(backend).observe() })
        } catch (error: PhoneUseFailure) {
            if (error.code == "TEXT_READBACK_TIMEOUT") textReadbackFailure(error, inputAttempt)
            else failure(error.code, error.message ?: "手机执行失败", terminal = true)
        } catch (error: IllegalArgumentException) {
            failure("INVALID_ARGUMENT", error.message ?: "参数无效", terminal = false)
        } catch (error: org.json.JSONException) {
            failure("INVALID_ARGUMENT", error.message ?: "参数无效", terminal = false)
        } catch (error: Exception) {
            // Check cancellation before turning a backend failure into a normal tool result.
            try { checkActive() } catch (cancelled: Exception) { close(); throw cancelled }
            failure("PHONE_USE_FAILED", "手机执行失败，已停止以避免重复操作。", terminal = true)
        }
    }

    private fun pageChanged(value: PhoneUseFrame, message: String = "页面或操作区域已变化，本步未执行；请根据新观察重新规划，未观察区域禁止点击。"): AgentModelClient.ToolResult {
        remaining-- // Replanning consumes the same finite budget as MiAIPlus, rather than renewing forever.
        pageChanges++
        if (pageChanges >= 6) return failure("PAGE_UNSTABLE", "连续6次无法确认操作目标稳定，未继续执行；请等待页面稳定或用户接管。", true)
        val result = observe(value, "PAGE_CHANGED")
        return result.copy(content = JSONObject(result.content).put("message", message).toString())
    }

    private fun actionFingerprint(action: PhoneUseAction, value: PhoneUseFrame): String {
        val target = PhoneUseTargetGuard.target(value, action)
        return listOf(action.name, action.element, action.start, action.end, action.text, action.app,
            value.foreground, value.displayId, target?.structure, target?.content,
            value.visualAnchor(action)).joinToString("|")
    }

    private fun observe(value: PhoneUseFrame, code: String? = null): AgentModelClient.ToolResult {
        active()
        frame = value
        observation = UUID.randomUUID().toString()
        expiresAt = now() + IDLE_TIMEOUT_MS
        idleLease.renew()
        val payload = JSONObject().put("ok", true).put("status", "CONTINUE")
            .put("session", session).put("observation_id", observation).put("remaining_steps", remaining)
            .put("screen", JSONObject().put("width", value.width).put("height", value.height))
            .put("screenshot", JSONObject().put("width", value.image.width).put("height", value.image.height))
            .put("display_id", value.displayId).put("foreground", value.foreground)
            .put("coordinate_space", "screenshot_pixels").put("observation", value.content)
        if (code != null) payload.put("code", code).put("message", "页面已变化，动作未执行；请根据新观察重新规划。")
        return AgentModelClient.ToolResult(payload.toString(), listOf(value.image), sensitive = true)
    }

    private fun inputReview(value: PhoneUseFrame, message: String): AgentModelClient.ToolResult {
        val pending = checkNotNull(pendingInput)
        val observed = observe(value)
        val input = checkNotNull(PhoneUseTargetGuard.target(value, pending.attempt.action))
        return observed.copy(content = JSONObject(observed.content).put("code", "INPUT_REVIEW_REQUIRED")
            .put("message", message).put("input_review", JSONObject()
                .put("expected_utf16_length", pending.attempt.action.text.length)
                .put("bounds_screen_pixels", input.json.optString("bounds"))
                .put("exact_readback_verified", false).put("write_dispatched", true)
                .put("manual_required", pending.attempt.manual)
                .put("readback_diagnostic", pending.diagnostic)
                .put("warning", "接口回读不一致，截图不能证明隐藏字符不存在。仅普通输入可进行模型视觉核对；不清晰/截断或敏感场景请请求人工确认，不得把请求原文抄成读图结果。"))
            .toString())
    }

    private fun verifyInput(action: PhoneUseAction, pending: PendingInput): AgentModelClient.ToolResult {
        if (remaining <= 0) return textReadbackFailure(PhoneUseFailure("STEP_LIMIT", "输入核对已用完本次动作预算。"))
        remaining--
        pending.checks++
        val observed = checkNotNull(frame)
        var current = backendCall { checkNotNull(backend).observe() }
        active()
        val original = pending.attempt
        if (!PhoneUseTargetGuard.sameInputFieldAfterEdit(original.before, current) ||
            !observed.sameTarget(current, original.action)) {
            return textReadbackFailure(PhoneUseFailure("INPUT_REVIEW_CHANGED", "核对时原输入框、文字或遮挡发生变化，未继续操作。"))
        }
        val input = checkNotNull(PhoneUseTargetGuard.target(current, original.action))
        var manual = original.manual || PhoneUsePermissionPolicy.needsConfirmation(action, current) ||
            PhoneUsePermissionPolicy.needsConfirmation(original.action, current) ||
            !action.inputFullyVisible || action.observedText != original.action.text
        if (!manual && !PhoneUseVisualGuard.stable(observed, current, input.bounds)) {
            // A caret can blink between frames. Re-read at most twice; never replay the write.
            if (pending.checks < 3 && remaining > 0) {
                return inputReview(current, "输入框像素在读图期间变化（可能包含光标闪烁）；请核对这张新截图后再verify_input，禁止重新输入。")
            }
            manual = true
        }
        if (manual) {
            val review = action.copy(text = original.action.text, requiresConfirmation = true)
            val approved = backendCall { checkNotNull(backend).confirm(review, current, goal) }
            active()
            if (!approved) {
                return textReadbackFailure(PhoneUseFailure("INPUT_REVIEW_DECLINED", "输入回读不一致，未获得用户核对确认；没有重新输入或继续提交。"))
            }
            val after = backendCall { checkNotNull(backend).observe() }
            active()
            if (!PhoneUseTargetGuard.sameInputFieldAfterEdit(original.before, after) ||
                !current.sameTarget(after, original.action)) {
                return textReadbackFailure(PhoneUseFailure("INPUT_REVIEW_CHANGED", "人工确认期间输入框或内容发生变化，确认已作废，未继续操作。"))
            }
            current = after
        }
        pendingInput = null
        pageChanges = 0
        previousAction = actionFingerprint(original.action, current)
        val result = observe(current)
        return result.copy(content = JSONObject(result.content).put("code", "INPUT_REVIEWED")
            .put("message", "输入已完成${if (manual) "人工" else "模型视觉"}核对，可根据当前画面执行下一步；这不是程序精确读回，禁止重复type。后续敏感操作仍需单独确认。")
            .put("input_verification", JSONObject().put("basis", if (manual) "user_review" else "model_visual")
                .put("exact_readback_verified", false).put("write_replayed", false)).toString())
    }

    /** Capture safety-checked evidence; uncertain writes may be reviewed, never silently replayed. */
    private fun textReadbackFailure(error: PhoneUseFailure, attempt: InputAttempt? = null): AgentModelClient.ToolResult {
        val before = frame
        val evidence = try {
            active()
            val after = backend?.let { owned -> backendCall { owned.observe() } }
            active()
            after?.takeIf {
                before != null && it.displayId == before.displayId &&
                    it.foreground.substringBefore('/') == before.foreground.substringBefore('/')
            }
        } catch (_: Exception) {
            // Cancellation must not become a normal result or publish a late screenshot.
            try { checkActive() } catch (cancelled: Exception) { close(); throw cancelled }
            null
        }
        active()
        if (attempt != null && evidence != null && remaining > 0 &&
            !PhoneUsePermissionPolicy.systemAuthorization(evidence) &&
            PhoneUseTargetGuard.sameInputFieldAfterEdit(attempt.before, evidence)) {
            pendingInput = PendingInput(attempt.copy(manual = attempt.manual || !error.visualReviewEligible), error.message.orEmpty())
            // The write was accepted but not verified. Keep this task's display alive and prohibit
            // further side effects until a separately budgeted, fresh-frame review completes.
            return inputReview(evidence, "输入动作已提交，但无障碍精确回读未通过。请读取截图中原输入框的完整可见文字，再调用verify_input；不要重输，也不要直接搜索/提交。看不全或不确定时requires_confirmation=true请求人工核对。")
        }
        val message = error.message.orEmpty() + if (evidence != null) {
            "\n已附上停止时目标屏截图，可核对输入内容；本次手机操作会话已关闭，未自动重输或继续搜索。"
        } else {
            "\n未能安全获取停止时目标屏截图；本次手机操作会话已关闭，未自动重输或继续搜索。"
        }
        val result = failure(error.code, message, terminal = true)
        if (evidence == null) return result
        return result.copy(
            content = JSONObject(result.content).put("observation", evidence.content)
                .put("diagnostic_screenshot", true).toString(),
            // This is the post-dispatch frame, never the old planning frame or earlier selections.
            images = listOf(evidence.image), replyImages = listOf(evidence.image),
        )
    }

    private fun failure(code: String, message: String, terminal: Boolean): AgentModelClient.ToolResult {
        if (terminal) stop()
        return AgentModelClient.ToolResult(
            JSONObject().put("ok", false).put("code", code)
                .put("status", if (terminal) "STOPPED" else "INVALID_ARGUMENT")
                .put("message", message).toString(), sensitive = true,
            stopMessage = message.takeIf { terminal },
        )
    }

    private fun active() {
        if (expired.get()) throw PhoneUseFailure("SESSION_EXPIRED", "手机操作空闲超过4分钟，会话和虚拟屏已释放。")
        checkActive()
        if (closed.get()) throw PhoneUseFailure("SESSION_CLOSED", "手机操作已取消。")
    }

    // Backend failures are never classified as model argument errors, even if a platform API throws one.
    private inline fun <T> backendCall(block: () -> T): T = try { block() } catch (error: Exception) {
        checkActive()
        if (error is PhoneUseFailure) throw error
        throw PhoneUseFailure("BACKEND_FAILED", "手机执行后端失败，已停止以避免重复操作。")
    }

    private fun stop() {
        stopped = true
        pendingInput = null
        idleLease.close()
        val owned = backend
        backend = null
        frame = null
        runCatching { owned?.close() }
    }
    override fun close() { closed.set(true); pendingInput = null; idleLease.close(); runCatching { backend?.close() } }

    companion object {
        const val IDLE_TIMEOUT_MS = 240_000L
        val TERMINAL_ACTIONS = setOf("finish", "ask_user", "take_over")
        fun integer(args: JSONObject, key: String, default: Int, min: Int, max: Int): Int {
            if (!args.has(key)) return default
            val value = args.get(key)
            require(value is Number && value.toDouble().isFinite() && value.toDouble() % 1 == 0.0 && value.toDouble() in min.toDouble()..max.toDouble()) { "$key 必须为 $min..$max 的整数" }
            return value.toInt()
        }
    }
}

internal interface PhoneUseBackend : AutoCloseable {
    fun start() {}
    fun observe(): PhoneUseFrame
    fun perform(action: PhoneUseAction, frame: PhoneUseFrame)
    fun confirm(action: PhoneUseAction, frame: PhoneUseFrame, goal: String): Boolean = false
}

internal class PhoneUseFailure(val code: String, message: String, val visualReviewEligible: Boolean = false) : RuntimeException(message)

internal data class PhoneUseFrame(
    val content: JSONObject,
    val image: AgentModelClient.ModelImage,
    val width: Int,
    val height: Int,
    val displayId: Int,
    val foreground: String,
    val treeSignature: String,
    // Backend supplies a bounded visual anchor for canvas/anonymous controls as well as semantic nodes.
    val visualAnchor: (PhoneUseAction) -> String = { image.reference },
) {
    init { require(width > 1 && height > 1 && (image.width ?: 0) > 1 && (image.height ?: 0) > 1) }
    fun sameTarget(other: PhoneUseFrame, action: PhoneUseAction): Boolean =
        PhoneUseTargetGuard.sameTarget(this, other, action)
}

internal data class PhoneUseAction(
    val name: String,
    val element: Pair<Double, Double>? = null,
    val start: Pair<Double, Double>? = null,
    val end: Pair<Double, Double>? = null,
    val space: String = "screenshot_pixels",
    val text: String = "",
    val observedText: String = "",
    val inputFullyVisible: Boolean = false,
    val app: String = "",
    val durationMs: Int = 500,
    val message: String = "",
    val attachScreenshot: Boolean = true,
    val requiresConfirmation: Boolean = false,
    val riskLevel: String = "normal",
    val intent: String = "",
) {
    fun inScreenPixels(frame: PhoneUseFrame): PhoneUseAction {
        fun convert(point: Pair<Double, Double>?): Pair<Double, Double>? = point?.let { (x, y) ->
            val sourceWidth = when (space) { "normalized" -> 1001; "screen_pixels" -> frame.width; else -> checkNotNull(frame.image.width) }
            val sourceHeight = when (space) { "normalized" -> 1001; "screen_pixels" -> frame.height; else -> checkNotNull(frame.image.height) }
            require(x in 0.0..(sourceWidth - 1).toDouble() && y in 0.0..(sourceHeight - 1).toDouble()) { "坐标超出最近观察的范围" }
            Pair((x * (frame.width - 1) / (sourceWidth - 1)).roundToInt().toDouble(),
                (y * (frame.height - 1) / (sourceHeight - 1)).roundToInt().toDouble())
        }
        return copy(element = convert(element), start = convert(start), end = convert(end), space = "screen_pixels")
    }

    companion object {
        fun parse(args: JSONObject): PhoneUseAction {
            val action = args.getJSONObject("action")
            val name = action.getString("action")
            require(name in setOf("tap", "double_tap", "long_press", "swipe", "type", "verify_input", "back", "home", "launch", "wait", "attach_screenshot") + PhoneUseSession.TERMINAL_ACTIONS) { "不支持的 action" }
            val space = args.optString("coordinate_space", "screenshot_pixels")
            require(space in setOf("screenshot_pixels", "screen_pixels", "normalized")) { "coordinate_space 无效" }
            fun point(key: String): Pair<Double, Double> {
                val pair = action.getJSONArray(key)
                require(pair.length() == 2) { "$key 必须含两个坐标" }
                require((0..1).all { pair.get(it) is Number && pair.getDouble(it).isFinite() }) { "$key 必须为有限数值" }
                return Pair(pair.getDouble(0), pair.getDouble(1))
            }
            val text = if (name == "type") action.getString("text") else ""
            require(text.length <= 8000) { "输入文本过长" }
            val observedText = if (name == "verify_input") action.getString("observed_text") else ""
            require(observedText.length <= 8000) { "核对文本过长" }
            if (action.has("input_fully_visible")) require(action.get("input_fully_visible") is Boolean) { "input_fully_visible必须为布尔值" }
            val app = if (name == "launch") action.getString("app") else ""
            require(name != "launch" || app.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "app 必须为安装应用的包名" }
            val risk = action.optString("risk_level", "normal")
            require(risk in setOf("normal", "sensitive", "critical")) { "risk_level 无效" }
            if (action.has("requires_confirmation")) require(action.get("requires_confirmation") is Boolean) { "requires_confirmation 必须为布尔值" }
            return PhoneUseAction(name,
                element = if (name in setOf("tap", "double_tap", "long_press")) point("element") else null,
                start = if (name == "swipe") point("start") else null,
                end = if (name == "swipe") point("end") else null, space = space, text = text, observedText = observedText,
                inputFullyVisible = action.optBoolean("input_fully_visible"), app = app,
                durationMs = PhoneUseSession.integer(action, "duration_ms", if (name == "long_press") 800 else 500,
                    if (name == "long_press") 300 else 100, 3000),
                message = action.optString("message", "").take(4000),
                attachScreenshot = if (action.has("attach_screenshot")) {
                    require(action.get("attach_screenshot") is Boolean) { "attach_screenshot 必须为布尔值" }
                    action.getBoolean("attach_screenshot")
                } else true,
                requiresConfirmation = action.optBoolean("requires_confirmation"),
                riskLevel = risk, intent = action.optString("intent").take(300))
        }
    }
}
