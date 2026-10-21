package io.github.mangi.eta.agent.phoneuse

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.media.AgentImageCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Per-run root process. All capture/input commands are bound to the created non-default display. */
internal class BackgroundPhoneUseBackend(
    private val context: Context,
    private val checkActive: () -> Unit,
    private val rootAvailable: () -> Boolean,
) : PhoneUseBackend {
    private val closed = AtomicBoolean(false)
    private val replies = ArrayBlockingQueue<String>(32)
    @Volatile private var process: Process? = null
    @Volatile private var directory: File? = null
    private var sequence = 0L
    private var displayId = -1
    private var displayGeneration = 0L
    private var pendingLaunch = ""
    private lateinit var geometry: PhoneUseDisplayGeometry
    private val width get() = geometry.width
    private val height get() = geometry.height

    private fun active() {
        checkActive()
        if (closed.get()) throw PhoneUseFailure("SESSION_CLOSED", "后台手机操作已取消。")
        if (!rootAvailable()) throw PhoneUseFailure("ROOT_REQUIRED", "后台手机操作需要Root授权。")
        PhoneUseScreenGuard.unlocked(context)
    }

    override fun start() {
        active()
        val folder = File(context.cacheDir, "phone-use-${java.util.UUID.randomUUID()}")
        if (!folder.mkdirs()) throw PhoneUseFailure("CACHE_UNAVAILABLE", "无法创建后台截图目录。")
        directory = folder
        if (closed.get()) { folder.delete(); throw PhoneUseFailure("SESSION_CLOSED", "后台操作已取消。") }
        val apk = context.applicationInfo.sourceDir
        fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        val command = "CLASSPATH=${quote(apk)} exec /system/bin/app_process /system/bin " +
            "io.github.mangi.eta.agent.phoneuse.helper.DisplayHelper"
        val launched = ProcessBuilder("su", "-c", command).start()
        process = launched
        if (closed.get()) { launched.destroyForcibly(); folder.delete(); throw PhoneUseFailure("SESSION_CLOSED", "后台操作已取消。") }
        thread("eta-phone-use-replies") {
            try {
                launched.inputStream.bufferedReader().use { reader ->
                    val line = StringBuilder()
                    while (true) {
                        val character = reader.read()
                        if (character < 0) break
                        if (character == 10) {
                            val value = line.toString().trim(); line.setLength(0)
                            // Ignore app_process diagnostics; protocol messages must carry a numeric token.
                            if (value.startsWith("{\"token\":")) {
                                if (!replies.offer(value)) { launched.destroy(); break }
                            }
                        } else {
                            if (line.length >= 65_536) { launched.destroy(); break }
                            line.append(character.toChar())
                        }
                    }
                }
            } finally { replies.offer(EOF) }
        }
        thread("eta-phone-use-stderr") { launched.errorStream.use(::drain) }
        val ping = request("ping")
        if (ping.optString("uid") != "0") throw PhoneUseFailure("ROOT_REQUIRED", "后台helper未以Root身份启动。")
        // Read only display metadata from the helper's system context, without app-window compatibility scaling.
        // Keep native pixels AND density: some apps cache main-display pixel sizes across displays.
        geometry = PhoneUseDisplayGeometry.fromHelper(request("default_display_metrics"))
        val created = request("create $width $height ${geometry.densityDpi}")
        displayId = created.optInt("displayId", -1)
        displayGeneration = created.optLong("displayGeneration", 0)
        if (displayId <= 0 || displayGeneration <= 0 || created.optString("imePolicy") != "HIDE") {
            throw PhoneUseFailure("DISPLAY_ISOLATION_FAILED", "系统未提供独立虚拟屏及IME隔离。")
        }
        verifyDisplay()
    }

    private fun verifyDisplay() {
        active()
        val display = request("display")
        if (display.optInt("displayId", -1) != displayId || displayId <= 0 ||
            display.optLong("displayGeneration", 0) != displayGeneration) {
            throw PhoneUseFailure("DISPLAY_CHANGED", "虚拟屏身份发生变化，已停止。")
        }
        val actual = PhoneUseDisplayGeometry.fromHelper(display)
        if (actual != geometry) {
            throw PhoneUseFailure("DISPLAY_GEOMETRY_CHANGED",
                "系统实际虚拟屏参数与请求不一致：请求 $width×$height@${geometry.densityDpi}，实际 ${actual.width}×${actual.height}@${actual.densityDpi}。")
        }
        val ime = request("ime")
        if (ime.optString("status") != "ime" || ime.optString("policy") != "HIDE" || !ime.optBoolean("verified")) {
            throw PhoneUseFailure("IME_ISOLATION_FAILED", "后台输入法隔离失效。")
        }
    }

    override fun observe(): PhoneUseFrame {
        verifyDisplay()
        val folder = directory ?: throw PhoneUseFailure("CACHE_UNAVAILABLE", "截图目录不可用。")
        val raw = File(folder, "frame.png")
        val jpeg = File(folder, "frame.jpg")
        try {
            val reply = request("capture ${raw.absolutePath}")
            if (reply.optString("path") != jpeg.absolutePath || !jpeg.isFile || jpeg.length() !in 1..10_485_760) {
                throw PhoneUseFailure("CAPTURE_FAILED", "虚拟屏截图缺失或路径不匹配。")
            }
            if (reply.optInt("displayWidth") != width || reply.optInt("displayHeight") != height) {
                throw PhoneUseFailure("CAPTURE_GEOMETRY_CHANGED", "虚拟屏截图尺寸与创建的屏幕不同。")
            }
            val image = AgentImageCodec.fromScreenBytes(jpeg.readBytes(), "phone-use-display-$displayId", "image/jpeg")
            val foreground = reply.optString("foreground")
            val service = AgentAccessibilityService.current()
                ?: throw PhoneUseFailure("ACCESSIBILITY_UNAVAILABLE", "无障碍服务不可访问。")
            val snapshot = if (foreground.isBlank()) null else service.captureDisplayNodes(displayId, foreground)
            val nodes = snapshot?.nodes.orEmpty()
            val treeStatus = when {
                foreground.isBlank() -> "no_foreground"
                snapshot == null -> "unavailable"
                nodes.isEmpty() -> "empty"
                snapshot.truncated -> "partial"
                else -> "available"
            }
            val semantic = snapshot?.let(PhoneUseScreenGuard::semanticContent)
                ?: JSONObject().put("ui_nodes", JSONArray()).put("window_id", -1).put("ui_tree_truncated", false)
            val tree = semantic.getJSONArray("ui_nodes")
            PhoneUseScreenGuard.safeNodes(tree, foreground)
            // Capture and semantic tree must refer to the same display-local foreground.
            if (request("focus").optString("display") != foreground) {
                throw PhoneUseFailure("FOCUS_CHANGED", "截图期间虚拟屏应用发生变化。")
            }
            active()
            val content = semantic.put("source", "isolated_virtual_display")
                .put("density_dpi", geometry.densityDpi)
                .put("ui_tree_status", treeStatus)
                .put("observation_mode", if (nodes.isEmpty()) "vision_only" else "vision_and_tree")
                .put("text_input_available", nodes.any { it.focused && it.editable && it.enabled && !it.password })
            if (pendingLaunch.isNotEmpty()) {
                if (foreground.substringBefore('/') == pendingLaunch) pendingLaunch = ""
                else content.put("launch_state", "awaiting_confirmation").put("requested_app", pendingLaunch)
                    .put("launch_message", "目标应用尚未打开，请根据当前启动确认界面继续；授权动作由宿主请求用户确认。")
            }
            if (nodes.isEmpty()) {
                content.put("message", "当前仅有目标虚拟屏截图，请根据截图判断并操作；应用尚未出现时可启动或等待。type需要此虚拟屏自己的可读取输入框。")
            }
            return PhoneUseFrame(content,
                image, width, height, displayId, foreground, "$treeStatus:$tree",
                PhoneUseScreenGuard.visualAnchor(image, width, height))
        } finally { raw.delete(); jpeg.delete() }
    }

    override fun confirm(action: PhoneUseAction, frame: PhoneUseFrame, goal: String): Boolean {
        active()
        val service = AgentAccessibilityService.current() ?: return false
        return service.confirmPhoneUseAction(PhoneUsePermissionPolicy.confirmationText(goal, action, frame), frame.image.reference) {
            !closed.get() && AgentAccessibilityService.current() === service && runCatching { active() }.isSuccess
        }
    }

    override fun perform(action: PhoneUseAction, frame: PhoneUseFrame) {
        verifyDisplay()
        if (frame.displayId != displayId || request("focus").optString("display") != frame.foreground) {
            throw PhoneUseFailure("FOCUS_CHANGED", "虚拟屏应用发生变化，动作未执行。")
        }
        request("safety")
        if (action.name !in setOf("launch", "wait", "home") && frame.foreground.isBlank()) {
            throw PhoneUseFailure("FOREGROUND_UNAVAILABLE", "虚拟屏当前没有可确认的应用。")
        }
        fun point(verb: String, duration: Int? = null) {
            active()
            val (x, y) = checkNotNull(action.element)
            val reply = request("$verb ${x.toInt()} ${y.toInt()}" + (duration?.let { " $it" } ?: ""))
            if (!reply.optBoolean("down") || !reply.optBoolean("up")) {
                throw PhoneUseFailure("INPUT_FAILED", "后台触摸未完整送达，已停止以避免重复执行。")
            }
        }
        when (action.name) {
            "tap" -> point("tap")
            "double_tap" -> {
                point("tap")
                Thread.sleep(100)
                active()
                val afterFirst = observe()
                if (!frame.sameTarget(afterFirst, action)) {
                    throw PhoneUseFailure("DOUBLE_TAP_PAGE_CHANGED", "双击首击已执行，页面或目标随后变化，未执行第二击；请先检查当前页面，避免重复操作。")
                }
                point("tap")
            }
            "long_press" -> point("longpress", action.durationMs)
            "swipe" -> {
                val (x1, y1) = checkNotNull(action.start); val (x2, y2) = checkNotNull(action.end)
                if (!request("swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} ${action.durationMs}").optBoolean("accepted")) {
                    throw PhoneUseFailure("INPUT_FAILED", "后台滑动未完整送达。")
                }
            }
            "type" -> {
                val result = AgentAccessibilityService.current()?.setPhoneUseDisplayText(displayId, frame.foreground, action.text) {
                    !closed.get() && runCatching { active() }.isSuccess
                } ?: throw PhoneUseFailure("ACCESSIBILITY_UNAVAILABLE", "后台文本输入需要无障碍服务。")
                if (!result.ok || result.verified != true) {
                    val code = result.code.ifBlank { "TEXT_NOT_VERIFIED" }
                    val reason = result.message.ifBlank { "未能确认输入结果；文字可能已写入，请先查看输入框，避免重复输入" }
                    throw PhoneUseFailure(code, "后台文本输入已停止：$reason（$code）", result.visualReviewEligible)
                }
            }
            "back", "home" -> {
                if (!request("key ${if (action.name == "back") 4 else 3}").optBoolean("delivered")) {
                    throw PhoneUseFailure("INPUT_FAILED", "后台按键未送达。")
                }
            }
            "launch" -> launch(action.app)
            "wait" -> {
                val deadline = android.os.SystemClock.elapsedRealtime() + action.durationMs
                while (android.os.SystemClock.elapsedRealtime() < deadline) { active(); Thread.sleep(25) }
            }
            else -> throw PhoneUseFailure("UNSUPPORTED_ACTION", "不支持的后台动作。")
        }
    }

    private fun launch(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw PhoneUseFailure("APP_NOT_FOUND", "应用没有可启动入口。")
        val component = intent.component ?: intent.resolveActivity(context.packageManager)
            ?: throw PhoneUseFailure("APP_NOT_FOUND", "无法解析应用入口。")
        if (component.packageName != packageName) throw PhoneUseFailure("INVALID_COMPONENT", "应用入口包名不匹配。")
        val flattened = component.flattenToString()
        if (!flattened.matches(Regex("[A-Za-z0-9_.$/]+"))) throw PhoneUseFailure("INVALID_COMPONENT", "应用入口格式无效。")
        val launched = request("launch $flattened")
        when (launched.optString("status")) {
            "launched" -> pendingLaunch = ""
            "awaiting_confirmation" -> pendingLaunch = packageName
            else -> throw PhoneUseFailure("LAUNCH_FAILED", "后台应用启动结果无法确认，已停止；未切换主屏。")
        }
    }

    /** Single caller, asynchronous bounded reader: a partial line can never block cancellation/deadlines. */
    private fun request(command: String): JSONObject {
        active()
        val child = process ?: throw PhoneUseFailure("HELPER_UNAVAILABLE", "后台helper不可用。")
        val token = ++sequence
        try {
            child.outputStream.write("$token $command\n".toByteArray(Charsets.UTF_8))
            child.outputStream.flush()
            val deadline = android.os.SystemClock.elapsedRealtime() + 25_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                active()
                val line = replies.poll(25, TimeUnit.MILLISECONDS) ?: continue
                if (line == EOF) throw PhoneUseFailure("HELPER_EXITED", "后台helper已经退出。")
                val reply = JSONObject(line)
                if (reply.optLong("token", -1) != token) continue
                if (reply.optString("status") == "error") {
                    throw PhoneUseFailure(reply.optString("code", "HELPER_FAILED"), "后台操作失败（${reply.optString("code", "HELPER_FAILED")}），已停止；未切换主屏。")
                }
                return reply
            }
            throw PhoneUseFailure("HELPER_TIMEOUT", "后台操作超时，结果不确定，已停止以避免重复执行。")
        } catch (failure: Exception) { close(); throw failure }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        replies.offer(EOF)
        val child = process
        val folder = directory
        // No request lock and no synchronous read/stream close on the cancelling thread.
        thread("eta-phone-use-cleanup") {
            try {
                if (child != null) {
                    // A wedged pipe must not prevent the independent teardown deadline.
                    thread("eta-phone-use-stop") {
                        child.outputStream.write("9223372036854775807 stop\n".toByteArray())
                        child.outputStream.flush()
                        child.outputStream.close()
                    }
                    if (!child.waitFor(750, TimeUnit.MILLISECONDS)) child.destroy()
                    if (!child.waitFor(250, TimeUnit.MILLISECONDS)) child.destroyForcibly()
                }
            } finally {
                folder?.listFiles()?.forEach { if (it.isFile) it.delete() }
                folder?.delete()
            }
        }
    }

    private fun thread(name: String, action: () -> Unit) {
        Thread({ runCatching(action) }, name).apply { isDaemon = true; start() }
    }
    private fun drain(input: InputStream) {
        val buffer = ByteArray(1024)
        while (input.read(buffer) >= 0) { /* Do not persist helper diagnostics or screen content. */ }
    }
    companion object { private const val EOF = "<helper-eof>" }
}
