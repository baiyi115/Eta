package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.agent.subagent.SubagentProfile
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 工具摘要面向用户展示，不包含敏感参数；终端命令通过独立字段提供给用户核对。 */
internal class AgentTraceFormatter {
    fun summarizeArguments(toolCall: AgentModelClient.ToolCall): String =
        when (toolCall.name) {
            "phone_use" -> "前台手机操作"
            "background_phone_use" -> "后台手机操作"
            BROWSER_TOOL_NAME -> summarizeBrowserArguments(toolCall.argumentsJson)
            "web_search" -> "搜索网页"
            "fetch_url" -> summarizeFetchArguments(toolCall.argumentsJson)
            "open_uri" -> summarizeOpenUriArguments(toolCall.argumentsJson)
            in AgentTerminalToolCatalog.names -> summarizeTerminalArguments(toolCall.name, toolCall.argumentsJson)
            "run_command" -> "执行命令 · Android"
            "write_file" -> summarizeTextLength("写入文件", toolCall.argumentsJson, "content")
            "read_file" -> "读取文件"
            "list_directory" -> "列出目录"
            "edit_file" -> "精确编辑文件"
            "stat_file" -> "查看文件信息"
            "glob_files" -> "按路径查找文件"
            "grep_files" -> "搜索文件内容"
            "inspect_app" -> "检查应用"
            "type_text" -> summarizeTypeText(toolCall.argumentsJson)
            "input_text" -> summarizeTextLength("输入文本", toolCall.argumentsJson, "text")
            "replace_text" -> summarizeTextLength("替换文本", toolCall.argumentsJson, "text")
            "paste_text", "set_clipboard" ->
                summarizeTextLength("粘贴文本", toolCall.argumentsJson, "text")
            "clear_text" -> "清空文本"
            "get_clipboard" -> "读取剪贴板"
            "search_apps" -> summarizeQueryArguments("搜索应用", toolCall.argumentsJson)
            "launch_app" -> "打开应用"
            "get_current_context" -> "读取时间与位置"
            "observe_screen" -> summarizeObservationArguments(toolCall.argumentsJson)
            "tap" -> summarizePointArguments("点击屏幕", toolCall.argumentsJson)
            "long_press" -> summarizePointArguments("长按屏幕", toolCall.argumentsJson)
            "tap_area" -> "点击区域"
            "tap_element" -> summarizeElementArguments("点击元素", toolCall.argumentsJson)
            "long_press_element" -> summarizeElementArguments("长按元素", toolCall.argumentsJson)
            "swipe" -> "滑动屏幕"
            "scroll" -> summarizeScrollArguments("滚动屏幕", toolCall.argumentsJson)
            "scroll_element" ->
                summarizeScrollArguments("滚动元素", toolCall.argumentsJson, withIndex = true)
            "press_key" -> summarizePressKeyArguments(toolCall.argumentsJson)
            "wait" -> summarizeWaitArguments(toolCall.argumentsJson)
            "wait_for_text" -> "等待文本出现"
            "wait_for_package" -> "等待应用就绪"
            "open_system_panel" -> "打开系统面板"
            "read_image" -> "查看图片"
            AgentSubagentToolCatalog.TOOL_NAME -> summarizeSubagentArguments(toolCall.argumentsJson)
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME -> summarizeSubagentProfileArguments(toolCall.argumentsJson)
            AgentConversationToolCatalog.READ_HISTORY -> "读取当前会话历史"
            "memory_get", "character_memory_get" -> summarizeMemoryGetArguments(toolCall.argumentsJson)
            "memory_write", "character_memory_write" -> summarizeMemoryWriteArguments(toolCall.argumentsJson)
            "skills_list" -> "查看技能列表"
            "skills_read" -> "读取技能"
            "skills_read_resource" -> "读取技能资源"
            "skills_list_curated" -> "浏览精选技能"
            "skills_inspect_github" -> "查看技能详情"
            "skills_install_from_github" -> "安装技能"
            else -> {
                val label = DEVICE_ACTION_LABELS[toolCall.name]
                    ?: AgentPhoneToolCatalog.entries.firstOrNull { it.name == toolCall.name }?.title
                    ?: io.github.mangi.eta.agent.context.PersonalSearchTools.searches.firstOrNull { it.name == toolCall.name }?.title
                    ?: when (toolCall.name) { "search_notes" -> "查询便签"; "search_system_memories" -> "查询系统记忆"; "read_personal_item" -> "读取检索详情"; "summarize_bills" -> "汇总账单"; else -> null }
                when {
                    toolCall.name.startsWith("mcp_") -> "MCP 工具"
                    label == null -> "准备执行"
                    toolCall.name.startsWith("search_") ->
                        summarizeQueryArguments(label, toolCall.argumentsJson)
                    else -> label
                }
            }
        }

    /** 命令以脱敏后的用户可见投影进入运行轨迹；日志仍只记录长度。 */
    fun displayCommand(toolCall: AgentModelClient.ToolCall): String? =
        if (toolCall.name == AgentSubagentToolCatalog.TOOL_NAME) {
            SubagentActivitySnapshot.initialFromArguments(toolCall.argumentsJson)?.encode()
        } else if (toolCall.name in AgentTerminalToolCatalog.names || toolCall.name == "run_command") {
            runCatching {
                JSONObject(toolCall.argumentsJson)
                    .optString("command")
                    .trim()
                    .takeIf { it.isNotBlank() && it.length <= MAX_DISPLAY_COMMAND_CHARS }
                    ?.sanitizeDisplayText()
                    ?.redactDisplaySecrets()
            }.getOrNull()
        } else {
            null
        }

    /** 文件修改成功后才返回规范路径指纹，避免为计数把完整本地路径写入会话。 */
    fun modifiedFileKey(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String? {
        if (toolName !in FILE_MUTATION_TOOL_NAMES || !isSuccessResult(result)) return null
        val path = parseResultJson(result)
            ?.optString("path")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_MODIFIED_FILE_PATH_CHARS }
            ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun String.redactDisplaySecrets(): String =
        replace(SENSITIVE_JSON_FIELD) { match ->
            "${match.groupValues[1]}\"<已隐藏>\""
        }
            .replace(SENSITIVE_ASSIGNMENT) { match ->
                "${match.groupValues[1]}=<已隐藏>"
            }
            .replace(SENSITIVE_FLAG) { match ->
                "${match.groupValues[1]}<已隐藏>"
            }
            .replace(SENSITIVE_KEY_VALUE) { match ->
                "${match.groupValues[1]}<已隐藏>"
            }
            .replace(SENSITIVE_HEADER) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}<已隐藏>"
            }
            .replace(BEARER_TOKEN) { match ->
                "${match.groupValues[1]}<已隐藏>"
            }

    /** 远端内容和命令都可能带 ANSI 控制序列或双向文本覆盖符，展示前统一清除。 */
    private fun String.sanitizeDisplayText(): String =
        replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(ANSI_ESCAPE, "")
            .replace(BIDI_CONTROLS, "")
            .filterNot { character ->
                Character.isISOControl(character) && character != '\n' && character != '\t'
            }

    /** 外部 URI 摘要不记录 path、query、fragment 或用户信息。 */
    fun summarizeOpenUriArguments(argumentsJson: String): String =
        runCatching {
            val raw = JSONObject(argumentsJson).optString("uri").trim()
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()?.take(24)
            val host = uri.host?.lowercase()?.take(160)
            listOfNotNull("交给外部应用", scheme, host).joinToString(" · ")
        }.getOrDefault("交给外部应用")

    /** browser_use 摘要只暴露动作和安全提取的 host。 */
    fun summarizeBrowserArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").browserActionLabel()
            val host = safeHttpHost(arguments.optString("url"))
            listOfNotNull(action, host).joinToString(" · ")
        }.getOrElse { "浏览器操作" }

    private fun summarizeFetchArguments(argumentsJson: String): String = runCatching {
        val arguments = JSONObject(argumentsJson)
        val label = if (arguments.has("document_id")) "继续读取网页" else "读取网页"
        listOfNotNull(label, safeHttpHost(arguments.optString("url"))?.removePrefix("www.")).joinToString(" · ")
    }.getOrDefault("读取网页")

    private fun summarizeTerminalArguments(toolName: String, argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = terminalActionLabel(toolName, arguments.optString("action", if (toolName == "terminal") "exec" else ""))
            val hasEnvironment = !arguments.has("session_id") && (
                toolName == "terminal" && arguments.optString("action", "exec") in setOf("open", "exec", "open_and_exec") ||
                    arguments.optString("action") in setOf("start", "daemon_start"))
            val environment = if (hasEnvironment) arguments.optString("environment", "android").terminalEnvironmentLabel() else null
            val identity = arguments.optString("identity")
                .takeIf { it == "root" || it == "user" }
            buildList {
                add(terminalLabel(toolName))
                add(action)
                environment?.let(::add)
                identity?.let(::add)
                if (arguments.optBoolean("async", false)) add("后台")
            }.joinToString(" · ")
        }.getOrDefault("终端")

    private fun summarizeTextLength(
        label: String,
        argumentsJson: String,
        key: String,
    ): String =
        runCatching {
            val chars = JSONObject(argumentsJson).optString(key).length
            "$label · $chars 字符"
        }.getOrDefault(label)

    /** 只展示动作与长度，不回显正文：输入内容可能是密码或私人消息。 */
    private fun summarizeTypeText(argumentsJson: String): String =
        runCatching {
            val args = JSONObject(argumentsJson)
            val chars = args.optString("text").length
            val label = when {
                chars == 0 -> "清空文本"
                args.optString("mode") == "append" -> "追加文本"
                else -> "输入文本"
            }
            buildString {
                append(label)
                if (chars > 0) append(" · $chars 字符")
                if (args.optBoolean("submit")) append(" · 提交")
            }
        }.getOrDefault("输入文本")

    /** 搜索关键词是用户自己发起的查询，直接展示；仍做单行化与长度截断。 */
    private fun summarizeQueryArguments(label: String, argumentsJson: String): String =
        runCatching {
            val query = sanitizeSummaryValue(
                JSONObject(argumentsJson).optString("query"),
                MAX_QUERY_SUMMARY_CHARS,
            )
            if (query.isNotBlank()) "$label · $query" else label
        }.getOrDefault(label)

    private fun summarizePointArguments(label: String, argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            "$label · (${arguments.optInt("x")}, ${arguments.optInt("y")})"
        }.getOrDefault(label)

    private fun summarizeElementArguments(label: String, argumentsJson: String): String =
        runCatching {
            val index = JSONObject(argumentsJson).optInt("index", -1)
            if (index >= 0) "$label · #$index" else label
        }.getOrDefault(label)

    private fun summarizeScrollArguments(
        label: String,
        argumentsJson: String,
        withIndex: Boolean = false,
    ): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            buildList {
                add(label)
                if (withIndex) {
                    arguments.optInt("index", -1).takeIf { it >= 0 }?.let { add("#$it") }
                }
                arguments.optString("direction").scrollDirectionLabel()?.let(::add)
            }.joinToString(" · ")
        }.getOrDefault(label)

    private fun summarizePressKeyArguments(argumentsJson: String): String =
        runCatching {
            val button = JSONObject(argumentsJson).optString("button").pressKeyLabel()
            listOfNotNull("按键", button).joinToString(" · ")
        }.getOrDefault("按键")

    private fun summarizeWaitArguments(argumentsJson: String): String =
        runCatching {
            val durationMs = JSONObject(argumentsJson).optInt("duration_ms", 1_000)
                .coerceAtLeast(0)
            val duration = if (durationMs >= 1_000) {
                String.format(Locale.US, "%.1f", durationMs / 1_000f)
                    .trimEnd('0').trimEnd('.') + " 秒"
            } else {
                "$durationMs 毫秒"
            }
            "等待 · $duration"
        }.getOrDefault("等待")

    private fun summarizeObservationArguments(argumentsJson: String): String =
        runCatching {
            val options = AgentScreenObservationContract.resolve(JSONObject(argumentsJson))
            buildList {
                add("观察屏幕")
                if (options.includeScreenshot) add("含截图")
                if (options.includeUiTree) add("含界面树")
            }.joinToString(" · ")
        }.getOrDefault("观察屏幕")

    private fun summarizeMemoryGetArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            if (arguments.optString("query").isNotBlank()) "检索记忆" else "读取记忆"
        }.getOrDefault("读取记忆")

    private fun summarizeMemoryWriteArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val mode = when (arguments.optString("mode")) {
                "replace_range" -> "替换片段"
                "append" -> "追加"
                "clear" -> "清空"
                else -> null
            }
            val content = arguments.optString("content")
            val lines = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
            buildList {
                add("更新记忆")
                mode?.let(::add)
                add("$lines 行")
                add("${content.toByteArray(Charsets.UTF_8).size} 字节")
            }.joinToString(" · ")
        }.getOrDefault("更新记忆")

    /** 结果成败供事件与 UI 状态使用，不再依赖摘要文本里的标记。 */
    fun isSuccessResult(result: AgentModelClient.ToolResult): Boolean =
        parseResultJson(result)?.optBoolean("ok", true) ?: true

    fun summarizeResult(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String {
        val json = parseResultJson(result)
        if (toolName in setOf("phone_use", "background_phone_use")) {
            return when {
                json?.optString("status") == "COMPLETED" -> "手机操作已结束，正在整理观察结果"
                json?.optString("status") == "STOPPED" -> "手机操作已停止"
                (json?.optInt("reply_screenshot_count", 0) ?: 0) > 0 -> "已保留 ${json?.optInt("reply_screenshot_count")} 张截图，将随回复发送"
                json?.optBoolean("ok") == true -> "已观察手机屏幕"
                else -> "手机操作未执行"
            }
        }
        if (toolName == "web_search" || toolName == "fetch_url") return summarizeWebResult(toolName, json)
        // 终端 exit_code != 0 时 ok=false 但没有 code 字段，必须走专用分支保留退出码与输出
        if (toolName in AgentTerminalToolCatalog.names || toolName == "run_command") {
            return summarizeTerminalResult(toolName, json)
        }
        if (toolName.startsWith("mcp_")) return summarizeMcpResult(json, result)
        if (json?.optString("status") == "unconfirmed") return "操作已提交，效果未确认"
        if (!isSuccessResult(result)) return summarizeFailure(json)
        if (json != null) {
            // 本机工具的细节摘要优先；未覆盖的工具交给分类 Formatter 或通用摘要。
            summarizeToolResult(toolName, json, result)?.let { return it }
            val summary = AgentDeviceResultFormatter.summarize(toolName, json)
                ?: AgentPhoneResultFormatter.summarize(toolName, json)
                ?: AgentFileResultFormatter.summarize(toolName, json)
            if (summary != null) return summary
        }
        return when (toolName) {
            BROWSER_TOOL_NAME -> json?.let(::summarizeBrowserResult) ?: "浏览器操作完成"
            AgentSubagentToolCatalog.TOOL_NAME -> json?.let(::summarizeSubagentResult) ?: "完成"
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME -> json?.let(::summarizeSubagentProfileResult) ?: "完成"
            AgentConversationToolCatalog.READ_HISTORY -> "已读取历史分页"
            "memory_get", "memory_write", "character_memory_get", "character_memory_write" ->
                json?.let { summarizeMemoryResult(toolName, it) } ?: "完成"
            "search_apps" -> json?.let(::summarizeSearchAppsResult) ?: "完成"
            "launch_app" -> json?.let(::summarizeLaunchAppResult) ?: "已打开"
            "skills_list", "skills_list_curated", "skills_inspect_github", "skills_install_from_github" -> json?.let {
                summarizeSkillResult(toolName, it) ?: summarizeGenericResult(it, result)
            } ?: "完成"
            else -> json?.let { summarizeGenericResult(it, result) } ?: "完成"
        }
    }

    private fun parseResultJson(result: AgentModelClient.ToolResult): JSONObject? =
        runCatching { JSONObject(result.content) }.getOrNull()

    /**
     * 网页错误正文、搜索词和页面标题可能回显私密 URL，只保留计数。动作与主机已在参数摘要中，
     * 结果只给出结论，避免工作过程同一行重复。
     */
    private fun summarizeWebResult(toolName: String, json: JSONObject?): String {
        val label = if (toolName == "web_search") "网页搜索" else "网页读取"
        if (json == null) return "完成"
        if (!json.optBoolean("ok", true)) {
            val code = json.optString("code").let(::sanitizeErrorCode)
            return listOfNotNull("${label}失败", code?.let { "code=$it" }).joinToString(" · ")
        }
        if (toolName == "web_search") {
            val count = json.optJSONArray("results")?.length() ?: json.firstNonNegativeInt("count")
            return when (count) {
                null -> "完成"
                0 -> "没有找到结果"
                else -> "$count 条结果"
            }
        }
        val chars = (json.opt("text") as? String)?.length
            ?: json.firstNonNegativeInt("returned_chars", "text_chars")
            ?: return "完成"
        return if (json.optBoolean("has_more")) "已读前 ${formatCharCount(chars)}" else formatCharCount(chars)
    }

    /** 失败摘要保留 code= 标记，供运行日志提取稳定错误码；message 是工具侧给出的中文原因。 */
    private fun summarizeFailure(json: JSONObject?): String {
        val code = json?.optString("code")?.let(::sanitizeErrorCode)
        val reason = json?.optString("message")
            ?.let(::sanitizeSummaryValue)
            ?.takeIf { it.isNotBlank() }
        return buildList {
            add("失败")
            reason?.let(::add)
            code?.let { add("code=$it") }
        }.joinToString(" · ")
    }

    private fun summarizeMemoryResult(toolName: String, json: JSONObject): String =
        buildList {
            add(if (toolName.endsWith("memory_get")) "已读取记忆" else "已更新记忆")
            if (json.has("line_count")) add("${json.optInt("line_count")} 行")
            if (json.has("bytes")) add("${json.optInt("bytes")} 字节")
        }.joinToString(" · ")

    /**
     * MCP 结果保留有界正文预览，便于用户核对远端工具实际返回了什么。摘要仍会脱敏常见凭据，
     * 并限制行数与字符数；完整结果只存在于本次运行上下文，不进入持久会话。
     */
    private fun summarizeMcpResult(
        json: JSONObject?,
        result: AgentModelClient.ToolResult,
    ): String {
        if (json == null) return "MCP 工具 · 完成"
        val content = json.optJSONArray("content") ?: JSONArray()
        val server = sanitizeMcpIdentity(json.optString("server"), MAX_MCP_SERVER_CHARS)
        val tool = sanitizeMcpIdentity(json.optString("tool"), MAX_MCP_TOOL_CHARS)
        val ok = json.optBoolean("ok", true)
        val header = buildList {
            if (server.isNotBlank()) add(server)
            if (tool.isNotBlank()) add(tool)
            if (!ok) {
                add("失败")
                json.optString("message")
                    .let(::sanitizeSummaryValue)
                    .takeIf(String::isNotBlank)
                    ?.let(::add)
                json.optString("code")
                    .let(::sanitizeErrorCode)
                    ?.let { add("code=$it") }
            } else {
                when {
                    content.length() > 0 -> add("返回 ${content.length()} 项")
                    json.has("structured_content") -> add("返回结构化结果")
                    else -> add("完成")
                }
            }
            if (result.images.isNotEmpty()) add("${result.images.size} 张图片")
            if (json.optBoolean("truncated")) add("已截断")
            val omitted = json.optInt("omitted_items", 0)
            if (omitted > 0) add("另有 $omitted 项未显示")
        }.joinToString(" · ").ifBlank { if (ok) "MCP 工具 · 完成" else "MCP 工具 · 失败" }
        val preview = summarizeMcpPreview(json, content) ?: return header
        return "$header\n$preview"
    }

    private fun summarizeMcpPreview(json: JSONObject, content: JSONArray): String? {
        val chunks = mutableListOf<String>()
        for (index in 0 until content.length()) {
            content.optString(index).takeIf(String::isNotBlank)?.let(chunks::add)
        }
        if (chunks.isEmpty()) {
            json.opt("structured_content")
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                ?.takeIf(String::isNotBlank)
                ?.let(chunks::add)
        }
        val normalized = chunks.joinToString("\n")
            .replace("\r\n", "\n")
            .sanitizeDisplayText()
            .redactDisplaySecrets()
            .replace(BASE64_DATA_URL, "<图片数据已隐藏>")
            .trim()
        if (normalized.isBlank()) return null
        val allLines = normalized.lines()
        var preview = allLines
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .take(MAX_MCP_PREVIEW_LINES)
            .joinToString("\n")
        var clipped = allLines.count(String::isNotBlank) > MAX_MCP_PREVIEW_LINES
        if (preview.length > MAX_MCP_PREVIEW_CHARS) {
            preview = preview.take(MAX_MCP_PREVIEW_CHARS)
            clipped = true
        }
        return if (clipped) "$preview\n…" else preview
    }

    private fun summarizeToolResult(
        toolName: String,
        json: JSONObject,
        result: AgentModelClient.ToolResult,
    ): String? = when (toolName) {
        "device_status" -> summarizeDeviceStatus(json)
        "network_info" -> summarizeNetworkInfo(json)
        "get_current_location" -> summarizeCurrentLocation(json)
        "get_device_environment" -> summarizeDeviceEnvironment(json)
        "get_clipboard" -> summarizeTextResult("已读取剪贴板", json.optString("text"), json)
        "get_setting" -> summarizeSettingResult(json)
        "stat_file" -> summarizeStatFileResult(json)
        "observe_screen" -> summarizeScreenObservationResult(json, result)
        "inspect_app" -> summarizeInspectAppResult(json)
        "skills_list" -> summarizeCountResult("技能", json, "items", "count")
        "skills_read" -> summarizeSkillReadResult(json)
        "skills_read_resource" -> summarizeSkillResourceResult(json)
        "get_logcat" -> summarizeLogcatResult(json)
        else -> null
    }

    private fun summarizeDeviceStatus(json: JSONObject): String = buildList {
        add("设备状态")
        json.optString("model").let(::sanitizeSummaryValue).takeIf(String::isNotBlank)?.let(::add)
        json.optString("android_version").let(::sanitizeSummaryValue).takeIf(String::isNotBlank)?.let {
            add("Android $it")
        }
        json.optInt("battery_percent", -1).takeIf { it >= 0 }?.let { add("$it% 电量") }
        if (json.optBoolean("charging")) add("充电中")
        if (json.has("memory_available_bytes") && json.has("memory_total_bytes")) {
            add(
                "内存 ${formatByteCount(json.optLong("memory_available_bytes"))} / " +
                    formatByteCount(json.optLong("memory_total_bytes")),
            )
        }
        if (json.has("storage_available_bytes") && json.has("storage_total_bytes")) {
            add(
                "存储 ${formatByteCount(json.optLong("storage_available_bytes"))} / " +
                    formatByteCount(json.optLong("storage_total_bytes")),
            )
        }
    }.joinToString(" · ")

    private fun summarizeNetworkInfo(json: JSONObject): String = buildList {
        add("网络信息")
        val connected = json.optBoolean("connected")
        val transports = json.optJSONArray("transports")
            ?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    when (array.optString(index)) {
                        "wifi" -> "Wi-Fi"
                        "cellular" -> "移动网络"
                        "ethernet" -> "以太网"
                        "vpn" -> "VPN"
                        else -> null
                    }
                }
            }
            .orEmpty()
        if (transports.isNotEmpty()) add(transports.joinToString("、"))
        if (!connected) add("未连接")
        else if (json.optBoolean("validated")) add("已联网")
        if (connected && json.has("metered") && !json.isNull("metered")) {
            add(if (json.optBoolean("metered")) "计费网络" else "非计费网络")
        }
    }.joinToString(" · ")

    private fun summarizeCurrentLocation(json: JSONObject): String = buildList {
        add("已获取位置")
        json.optInt("accuracy_m", -1).takeIf { it >= 0 }?.let { add("精度 $it 米") }
        json.optLong("age_ms", -1).takeIf { it >= 0 }?.let {
            add("更新于 ${formatDuration(it)}")
        }
    }.joinToString(" · ")

    private fun summarizeDeviceEnvironment(json: JSONObject): String = buildList {
        add("设备环境")
        if (json.has("interactive") && !json.isNull("interactive")) {
            add(if (json.optBoolean("interactive")) "屏幕亮起" else "屏幕熄灭")
        }
        if (json.has("device_locked") && !json.isNull("device_locked")) {
            add(if (json.optBoolean("device_locked")) "已锁屏" else "已解锁")
        }
        json.optJSONArray("audio_outputs")?.length()?.takeIf { it > 0 }?.let { add("$it 个音频输出") }
        json.optInt("display_count", 0).takeIf { it > 0 }?.let { add("$it 块屏幕") }
    }.joinToString(" · ")

    private fun summarizeSettingResult(json: JSONObject): String = buildList {
        add("已读取系统设置")
        json.optString("namespace").let(::sanitizeSummaryValue).takeIf(String::isNotBlank)?.let(::add)
        json.optString("key").let(::sanitizeSummaryValue).takeIf(String::isNotBlank)?.let(::add)
        if (!json.isNull("value")) {
            val value = json.optString("value")
            add("${value.length} 字符")
        } else {
            add("无值")
        }
    }.joinToString(" · ")

    private fun summarizeStatFileResult(json: JSONObject): String {
        val file = json.optJSONObject("file") ?: return "已读取文件信息"
        return buildList {
            add("文件信息")
            file.optString("kind").let(::sanitizeSummaryValue).takeIf(String::isNotBlank)?.let(::add)
            if (file.has("size_bytes")) add(formatByteCount(file.optLong("size_bytes")))
        }.joinToString(" · ")
    }

    private fun summarizeScreenObservationResult(
        json: JSONObject,
        result: AgentModelClient.ToolResult,
    ): String = buildList {
        add("观察屏幕")
        json.optJSONArray("ui_nodes")?.length()?.let { add("$it 个界面节点") }
        if (result.images.isNotEmpty()) add("${result.images.size} 张截图")
        if (json.optBoolean("ui_tree_truncated")) add("节点已截断")
    }.joinToString(" · ")

    private fun summarizeInspectAppResult(json: JSONObject): String = buildList {
        add("应用信息")
        json.optString("package_name").let(::sanitizeSummaryValue).takeIf(String::isNotBlank)?.let(::add)
        if (json.has("version_name") && !json.isNull("version_name")) {
            json.optString("version_name").let(::sanitizeSummaryValue).takeIf(String::isNotBlank)?.let {
                add("版本 $it")
            }
        }
        json.optInt("permission_count", -1).takeIf { it >= 0 }?.let { count ->
            val total = json.optInt("permission_total", count)
            add("权限 $count / $total")
        }
        if (!json.optBoolean("enabled", true)) add("已停用")
        if (json.optBoolean("has_more")) add("权限可继续读取")
    }.joinToString(" · ")

    private fun summarizeSkillReadResult(json: JSONObject): String = buildList {
        add("已读取技能")
        json.optString("name").let(::sanitizeSummaryValue).takeIf(String::isNotBlank)?.let(::add)
        val bodyChars = json.optString("bodyMarkdown").length
        if (bodyChars > 0) add("$bodyChars 字")
        json.optJSONArray("references")?.length()?.takeIf { it > 0 }?.let { add("$it 个引用") }
    }.joinToString(" · ")

    private fun summarizeSkillResourceResult(json: JSONObject): String = buildList {
        add("已读取技能资源")
        val chars = json.optString("text").length
        if (chars > 0) add("$chars 字")
        if (json.optBoolean("truncated")) add("已截断")
    }.joinToString(" · ")

    private fun summarizeLogcatResult(json: JSONObject): String = buildList {
        add("系统日志")
        json.optInt("count", -1).takeIf { it >= 0 }?.let { add("返回 $it 行") }
        json.optInt("matched_lines", -1).takeIf { it >= 0 }?.let { add("匹配 $it 行") }
        if (json.optBoolean("has_more")) add("可继续读取")
        if (json.optBoolean("scan_limited")) add("采集范围有限")
        if (json.optBoolean("truncated") &&
            !json.optBoolean("has_more") &&
            !json.optBoolean("scan_limited")
        ) {
            add("结果已截断")
        }
    }.joinToString(" · ")

    private fun summarizeCountResult(
        label: String,
        json: JSONObject,
        arrayKey: String,
        countKey: String,
    ): String {
        val count = json.optJSONArray(arrayKey)?.length() ?: json.firstNonNegativeInt(countKey)
            ?: return label
        return if (count == 0) "未找到$label" else "$count 个$label"
    }

    private fun summarizeTextResult(label: String, value: String, json: JSONObject): String =
        buildList {
            add(label)
            if (value.isNotEmpty()) add("${value.length} 字符")
            if (json.optBoolean("truncated")) add("已截断")
        }.joinToString(" · ")

    private fun summarizeGenericResult(
        json: JSONObject,
        result: AgentModelClient.ToolResult,
    ): String = buildList {
        add(if (json.has("verified") && !json.optBoolean("verified")) "已执行，效果未确认" else "完成")
        summarizeArrayCount(json)?.let(::add)
        json.firstNonNegativeInt("count")?.takeIf { !json.hasArrayCount() }?.let { count ->
            add(if (count == 0) "没有找到结果" else "$count 个结果")
        }
        if (json.has("bytes_read")) add("已读 ${json.optLong("bytes_read")} 字节")
        if (json.has("line_count")) add("${json.optInt("line_count")} 行")
        if (json.has("replacements")) add("替换 ${json.optInt("replacements")} 处")
        json.optJSONObject("file")?.let { file ->
            file.optString("kind").let(::sanitizeSummaryValue).takeIf(String::isNotBlank)?.let(::add)
            if (file.has("size_bytes")) add(formatByteCount(file.optLong("size_bytes")))
        }
        textLength(json, "bodyMarkdown")?.let { add("$it 字") }
        textLength(json, "text")?.let { add("$it 字符") }
        textLength(json, "content")?.let { add("$it 字符") }
        if (json.optBoolean("has_more")) add("可继续读取")
        if (json.optBoolean("truncated")) add("结果已截断")
        if (json.optBoolean("partial") || json.optBoolean("scan_limited")) add("采集范围有限")
        json.optJSONArray("apps")?.length()?.let { add("找到 $it 个应用") }
        json.optJSONArray("candidates")?.length()?.let { add("$it 个候选") }
        if (result.images.isNotEmpty()) add("${result.images.size} 张图片")
    }.distinct().joinToString(" · ")

    private fun summarizeArrayCount(json: JSONObject): String? {
        val fields = listOf(
            "ui_nodes" to "个界面节点",
            "items" to "个结果",
            "entries" to "个条目",
            "matches" to "个匹配",
            "lines" to "行",
        )
        val counts = fields.mapNotNull { (key, unit) ->
            json.optJSONArray(key)?.length()?.let { count -> count to unit }
        }
        if (counts.isEmpty()) return null
        val match = counts.firstOrNull { it.first > 0 } ?: counts.first()
        return if (match.first == 0) "没有找到结果" else "${match.first} ${match.second}"
    }

    private fun JSONObject.hasArrayCount(): Boolean =
        listOf("ui_nodes", "items", "entries", "matches", "lines")
            .any { optJSONArray(it) != null }

    private fun textLength(json: JSONObject, key: String): Int? =
        json.opt(key)
            ?.takeUnless { it == JSONObject.NULL }
            ?.let { value -> if (value is String) value.length else null }
            ?.takeIf { it > 0 }

    private fun formatByteCount(bytes: Long): String {
        if (bytes < 0L) return "未知大小"
        if (bytes < 1_024L) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1_024 && unitIndex < units.lastIndex) {
            value /= 1_024
            unitIndex++
        }
        val formatted = String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')
        return "$formatted ${units[unitIndex]}"
    }

    private fun formatDuration(milliseconds: Long): String = when {
        milliseconds < 1_000 -> "${milliseconds} 毫秒前"
        milliseconds < 60_000 -> "${milliseconds / 1_000} 秒前"
        else -> "${milliseconds / 60_000} 分钟前"
    }

    private fun summarizeSubagentArguments(argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson)
        val resumeId = sanitizeSummaryValue(args.optString("resume"), 32)
        val subagentType = sanitizeSummaryValue(args.optString("subagent_type"), 32)
            .lowercase()
            .ifBlank { if (resumeId.isNotBlank()) "subagent" else SubagentProfile.DEFAULT_TYPE }
        val description = sanitizeSummaryValue(args.optString("description"), MAX_QUERY_SUMMARY_CHARS)
        listOfNotNull(
            subagentType,
            description.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { "Subagent" }
    }.getOrDefault("Subagent")

    private fun summarizeSubagentResult(json: JSONObject): String {
        val toolCalls = json.optInt("tool_calls", 0)
        return if (toolCalls > 0) "完成 · $toolCalls 次工具调用" else "完成"
    }

    private fun summarizeSubagentProfileArguments(argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson)
        val action = args.optString("action").trim().lowercase()
        val name = sanitizeSummaryValue(args.optString("name"), 32)
        val label = when (action) {
            "save" -> "保存 Subagent"
            "delete" -> "删除 Subagent"
            "get" -> "读取 Subagent"
            else -> "查看 Subagent 列表"
        }
        if (name.isNotBlank() && action != "list") "$label · $name" else label
    }.getOrDefault("管理 Subagent")

    private fun summarizeSubagentProfileResult(json: JSONObject): String =
        when (json.optString("action")) {
            "save" -> listOfNotNull("已保存", json.optString("name").takeIf(String::isNotBlank)).joinToString(" · ")
            "delete" -> listOfNotNull("已删除", json.optString("name").takeIf(String::isNotBlank)).joinToString(" · ")
            "list" -> "${json.optJSONArray("profiles")?.length() ?: 0} 个 Subagent"
            else -> "完成"
        }

    private fun summarizeSkillResult(toolName: String, json: JSONObject): String? {
        if (toolName == "skills_install_from_github") {
            val installed = json.optJSONArray("installed") ?: return null
            return "已安装 ${installed.length()} 个技能" +
                if (json.optString("available") == "next_turn") " · 下一轮可用" else ""
        }
        val items = json.optJSONArray("items") ?: return null
        val installed = (0 until items.length()).count { items.optJSONObject(it)?.opt("installed") == true }
        return "找到 ${items.length()} 个技能" + if (installed > 0) " · $installed 个已安装" else ""
    }

    private fun summarizeSearchAppsResult(json: JSONObject): String {
        val apps = json.optJSONArray("apps") ?: return "未找到匹配应用"
        val total = apps.length()
        if (total == 0) return "未找到匹配应用"
        val names = (0 until total).mapNotNull { index ->
            apps.optJSONObject(index)?.optString("app_name")
                ?.let(::sanitizeSummaryValue)
                ?.takeIf { it.isNotBlank() }
        }
        return buildString {
            append("已找到 $total 个应用")
            val shown = names.take(MAX_LISTED_APP_NAMES)
            if (shown.isNotEmpty()) {
                append(" · ").append(shown.joinToString("、"))
                if (total > shown.size) append(" 等")
            }
        }
    }

    private fun summarizeLaunchAppResult(json: JSONObject): String {
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        return if (appName.isNotBlank()) "已打开 · $appName" else "已打开"
    }

    /**
     * 终端结果面向用户展示退出状态与输出预览；输出可能很长，
     * 只保留开头几行，截断时追加省略标记。
     */
    private fun summarizeTerminalResult(toolName: String, json: JSONObject?): String {
        if (json == null) return terminalLabel(toolName)
        if (json.optString("code").isNotBlank()) return summarizeFailure(json)
        if (!json.has("exit_code") || json.isNull("exit_code")) {
            val action = terminalActionLabel(toolName, json.optString("action"))
            return if (json.optBoolean("ok", true)) "${terminalLabel(toolName)} · $action" else "失败 · $action"
        }
        val exitCode = json.optInt("exit_code")
        val timedOut = json.optBoolean("timed_out", false)
        val status = when {
            timedOut -> "失败 · 执行超时"
            exitCode == 0 -> "执行完成"
            else -> "失败 · 退出码 $exitCode"
        }
        val output = if (exitCode == 0) {
            json.optString("stdout", json.optString("output"))
        } else {
            json.optString("stderr").ifBlank { json.optString("stdout", json.optString("output")) }
        }
        val truncated = json.optBoolean("stdout_truncated", false) ||
            json.optBoolean("stderr_truncated", false)
        val preview = terminalOutputPreview(output, truncated) ?: return status
        return "$status\n$preview"
    }

    private fun terminalOutputPreview(output: String, truncated: Boolean): String? {
        val normalized = output
            .sanitizeDisplayText()
            .redactDisplaySecrets()
            .trim()
        if (normalized.isEmpty()) return null
        val allLines = normalized.lines()
        var preview = allLines.take(MAX_TERMINAL_PREVIEW_LINES).joinToString("\n")
        var capped = allLines.size > MAX_TERMINAL_PREVIEW_LINES || truncated
        if (preview.length > MAX_TERMINAL_PREVIEW_CHARS) {
            preview = preview.take(MAX_TERMINAL_PREVIEW_CHARS)
            capped = true
        }
        return if (capped) "$preview\n…" else preview
    }

    private fun summarizeBrowserResult(json: JSONObject): String {
        val page = json.optJSONObject("page")
            ?: json.optJSONObject("page_info")
            ?: json.optJSONObject("pageInfo")
        val action = json.optString("action")
            .takeIf { it in BROWSER_ACTIONS }
            ?: "unknown"
        val host = sequenceOf(json, page)
            .filterNotNull()
            .flatMap { source ->
                sequenceOf("url", "current_url", "currentUrl", "final_url", "finalUrl")
                    .map(source::optString)
            }
            .mapNotNull(::safeHttpHost)
            .firstOrNull()
        val title = sequenceOf(json, page)
            .filterNotNull()
            .map { it.opt("title") }
            .filterIsInstance<String>()
            .map(::sanitizeSummaryValue)
            .firstOrNull { it.isNotBlank() }
        val textChars = sequenceOf(json, page)
            .filterNotNull()
            .mapNotNull { source ->
                source.firstNonNegativeInt("text_length", "textLength", "text_chars", "textChars")
            }
            .firstOrNull()
            ?: sequenceOf(json, page)
                .filterNotNull()
                .flatMap { source -> sequenceOf("text", "readable", "content").map(source::opt) }
                .filterIsInstance<String>()
                .map(String::length)
                .firstOrNull()
        val elementCount = json.firstNonNegativeInt("element_count", "elementCount", "elements_count")
            ?: json.optJSONArray("elements")?.length()

        return buildList {
            add(action.browserSuccessLabel())
            host?.let(::add)
            title?.let { add("《$it》") }
            if (action in BROWSER_TEXT_ACTIONS) {
                textChars?.let { add("约 ${formatCharCount(it)}") }
            }
            elementCount?.let { add("$it 个元素") }
            if (json.optBoolean("truncated", false)) add("已截断")
        }.joinToString(" · ")
    }

    private fun formatCharCount(chars: Int): String =
        if (chars >= 10_000) {
            String.format(Locale.US, "%.1f", chars / 10_000f).trimEnd('0').trimEnd('.') + " 万字"
        } else {
            "$chars 字"
        }

    private fun JSONObject.firstNonNegativeInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key)) return@firstNotNullOfOrNull null
            optInt(key, -1).takeIf { it >= 0 }
        }

    private fun sanitizeSummaryValue(value: String, maxChars: Int = 80): String =
        value.sanitizeDisplayText()
            .redactDisplaySecrets()
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(',', '，')
            .replace('=', '＝')
            .let { if (it.length <= maxChars) it else it.take(maxChars) + "..." }

    private fun sanitizeMcpIdentity(value: String, maxChars: Int): String =
        sanitizeSummaryValue(value, maxChars).replace(" · ", " • ")

    private fun sanitizeErrorCode(value: String): String? =
        value.trim().takeIf { it.matches(ERROR_CODE) }

    private fun safeHttpHost(rawUrl: String): String? =
        rawUrl.trim()
            .takeIf(String::isNotEmpty)
            ?.let { value ->
                runCatching {
                    val uri = URI(value)
                    uri.host
                        ?.takeIf {
                            uri.scheme.equals("http", ignoreCase = true) ||
                                uri.scheme.equals("https", ignoreCase = true)
                        }
                        ?.lowercase()
                        ?.take(160)
                }.getOrNull()
            }

    private fun String.browserActionLabel(): String = when (this) {
        "navigate" -> "打开网页"
        "get_readable" -> "提取正文"
        "get_text" -> "读取文本"
        "find_elements" -> "查找元素"
        "click" -> "点击网页"
        "type" -> "输入内容"
        "scroll" -> "滚动网页"
        "screenshot" -> "网页截图"
        "get_page_info" -> "查看网页信息"
        "go_back" -> "网页后退"
        "go_forward" -> "网页前进"
        "reload" -> "刷新网页"
        "wait_for_selector" -> "等待网页元素"
        else -> "浏览器操作"
    }

    private fun String.browserSuccessLabel(): String = when (this) {
        "navigate" -> "已打开"
        "get_readable" -> "已提取正文"
        "get_text" -> "已读取文本"
        "find_elements" -> "已找到元素"
        "click" -> "已点击网页"
        "type" -> "已输入内容"
        "scroll" -> "已滚动网页"
        "screenshot" -> "已截图"
        "get_page_info" -> "已读取页面信息"
        "go_back" -> "已后退"
        "go_forward" -> "已前进"
        "reload" -> "已刷新"
        "wait_for_selector" -> "已等到目标元素"
        else -> "浏览器操作完成"
    }

    private fun String.scrollDirectionLabel(): String? = when (lowercase(Locale.US)) {
        "up" -> "向上"
        "down" -> "向下"
        "left" -> "向左"
        "right" -> "向右"
        else -> null
    }

    private fun String.pressKeyLabel(): String? = when (lowercase(Locale.US)) {
        "back" -> "返回"
        "home" -> "主页"
        "recents", "recent" -> "最近任务"
        "notifications" -> "通知栏"
        "quick_settings" -> "控制中心"
        "power" -> "电源"
        "volume_up" -> "音量加"
        "volume_down" -> "音量减"
        "mute" -> "静音"
        else -> null
    }

    private fun terminalLabel(toolName: String): String = when (toolName) {
        "terminal_job" -> "异步命令"
        "terminal_daemon" -> "后台服务"
        else -> "终端"
    }

    private fun terminalActionLabel(toolName: String, action: String): String = when (toolName) {
        "terminal_job" -> when (action) {
            "start" -> "启动命令"
            "list" -> "任务列表"
            "read" -> "读取输出"
            "wait" -> "等待完成"
            "stop" -> "停止命令"
            else -> "任务操作"
        }
        "terminal_daemon" -> when (action) {
            "start" -> "启动服务"
            "list" -> "服务列表"
            "logs" -> "查看日志"
            "stop" -> "停止服务"
            else -> "服务操作"
        }
        else -> action.terminalActionLabel()
    }

    private fun String.terminalActionLabel(): String = when (this) {
        "check_root" -> "检测 Root 授权"
        "open" -> "创建会话"
        "exec" -> "执行命令"
        "open_and_exec" -> "单次执行"
        "read_async_result" -> "读取后台输出"
        "close" -> "关闭终端"
        "daemon_start" -> "启动守护任务"
        "daemon_list" -> "守护任务列表"
        "daemon_logs" -> "查看守护日志"
        "daemon_stop" -> "停止守护任务"
        else -> "终端操作"
    }

    private fun String.terminalEnvironmentLabel(): String = when (this) {
        "linux" -> "Linux"
        "alpine" -> "Alpine"
        "debian" -> "Debian"
        else -> "Android"
    }

    private companion object {
        const val BROWSER_TOOL_NAME = "browser_use"
        const val MAX_DISPLAY_COMMAND_CHARS = 4_000
        const val MAX_QUERY_SUMMARY_CHARS = 30
        const val MAX_LISTED_APP_NAMES = 3
        const val MAX_TERMINAL_PREVIEW_LINES = 10
        const val MAX_TERMINAL_PREVIEW_CHARS = 800
        const val MAX_MODIFIED_FILE_PATH_CHARS = 4_096
        const val MAX_MCP_SERVER_CHARS = 48
        const val MAX_MCP_TOOL_CHARS = 64
        const val MAX_MCP_PREVIEW_LINES = 6
        const val MAX_MCP_PREVIEW_CHARS = 1_200
        val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,79}")
        val BASE64_DATA_URL = Regex("data:[^\\s,;]+;base64,[A-Za-z0-9+/=]+")
        val ANSI_ESCAPE = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
        val BIDI_CONTROLS = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")
        val FILE_MUTATION_TOOL_NAMES = setOf("write_file", "edit_file")
        val SENSITIVE_JSON_FIELD = Regex(
            """(?i)("(?:api[_-]?key|x-api-key|access[_-]?key|secret[_-]?key|private[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|id[_-]?token|session[_-]?token|token|password|passwd|secret|client[_-]?secret|authorization|proxy-authorization|set-cookie|cookie)"\s*:\s*)("(?:\\.|[^"\\])*"|[^,\]\}\s]+)"""
        )
        val SENSITIVE_ASSIGNMENT = Regex(
            """(?i)\b([A-Z0-9_]*(?:API[_-]?KEY|ACCESS[_-]?TOKEN|AUTH[_-]?TOKEN|TOKEN|PASSWORD|PASSWD|SECRET)[A-Z0-9_]*)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_KEY_VALUE = Regex(
            """(?i)\b((?:password|passwd|token|api[_-]?key|x-api-key|access[_-]?key|secret[_-]?key|private[_-]?key|session[_-]?token|client[_-]?secret|set-cookie|cookie)\s*:\s*)(?:"(?:\\.|[^"\\])*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_FLAG = Regex(
            """(?i)(--?(?:password|passwd|token|api[-_]?key|secret)(?:\s*=\s*|\s+))(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_HEADER = Regex(
            """(?i)\b(Authorization|Proxy-Authorization|X-Api-Key)(\s*:\s*)(?:"(?:\\.|[^"\\])*"|'[^']*'|(?:Bearer|Basic)\s+[^\s'";&|]+|[^\s'";&|]+)"""
        )
        val BEARER_TOKEN = Regex("""(?i)\b(Bearer\s+)[A-Za-z0-9._~+/=-]{8,}""")
        val BROWSER_ACTIONS = setOf(
            "navigate",
            "get_readable",
            "get_text",
            "find_elements",
            "click",
            "type",
            "scroll",
            "screenshot",
            "get_page_info",
            "go_back",
            "go_forward",
            "reload",
            "wait_for_selector",
        )
        val BROWSER_TEXT_ACTIONS = setOf("get_readable", "get_text")

        /** 结构化设备工具只展示动作标签，不暴露任何参数。 */
        val DEVICE_ACTION_LABELS = mapOf(
            "set_alarm" to "设置闹钟",
            "set_timer" to "设置计时器",
            "device_status" to "查看设备状态",
            "network_info" to "查看网络信息",
            "top_memory_apps" to "查看内存占用排行",
            "top_storage_apps" to "查看存储占用排行",
            "media_control" to "控制媒体播放",
            "set_volume" to "调整音量",
            "get_setting" to "读取系统设置",
            "wifi_credentials" to "读取 Wi-Fi 密码",
            "recent_notifications" to "读取最近通知",
            "search_notification_history" to "搜索通知历史",
            "recent_app_activity" to "查看应用活动",
            "app_usage_summary" to "查看应用使用统计",
            "get_current_location" to "获取当前位置",
            "get_device_environment" to "查看设备环境",
            "list_alarms" to "查看闹钟列表",
            "list_active_timers" to "查看计时器",
            "search_clipboard_history" to "搜索剪贴板历史",
            "get_health_summary" to "查看健康摘要",
            "read_sms_code" to "读取短信验证码",
            "get_logcat" to "读取系统日志",
            "search_media" to "搜索媒体文件",
            "search_audio" to "搜索音频",
            "search_recordings" to "搜索录音",
            "search_files" to "搜索文件",
            "search_calendar_events" to "搜索日程",
            "search_contacts" to "搜索联系人",
            "search_call_history" to "搜索通话记录",
            "search_messages" to "搜索短信",
            "search_downloads" to "搜索下载内容",
            "search_coloros_notes" to "搜索便签",
            "search_coloros_recordings" to "搜索录音机",
            "search_recording_summaries" to "搜索录音摘要",
            "search_coloros_memories" to "搜索小布记忆",
            "search_saved_places" to "搜索收藏地点",
            "search_personal_orders" to "搜索个人订单",
            "search_qq_chat_images" to "搜索 QQ 聊天图片",
            "search_wechat_chat_images" to "搜索微信聊天图片",
            "set_setting" to "修改系统设置",
            "set_device_state" to "修改设备状态",
            "app_state_control" to "管理应用状态",
        )
    }
}
