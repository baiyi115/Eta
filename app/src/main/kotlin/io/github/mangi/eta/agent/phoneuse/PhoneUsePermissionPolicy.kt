package io.github.mangi.eta.agent.phoneuse

/** Single-step approval for actual commitments, explicit review or an unclassifiable target. */
internal object PhoneUsePermissionPolicy {
    private val submissions = setOf(
        "发送", "发布", "提交", "删除", "清空", "卸载", "允许", "同意", "授权", "本次允许", "仅本次允许",
        "支付", "付款", "转账", "提现", "充值", "下单", "send", "publish", "post", "submit", "delete", "clear",
        "uninstall", "allow", "allow once", "agree", "grant", "pay", "transfer", "purchase", "place order",
    )
    private val commitment = Regex(
        """^(?:(?:点击|按下|执行|确认|立即|确定|永久|一键)\s*)*(?:发送|发布|提交|删除|清空|卸载|允许|同意|授权|支付|付款|转账|提现|充值|下单|注销账号|注销账户|删除账号|删除账户|恢复出厂|抹掉|修改密码|更改密码|重设密码|关闭安全|停用安全|关闭两步验证|停用两步验证|永久授权|始终允许|总是允许)""",
    )
    private val englishCommitment = Regex(
        """^(?:(?:click|tap|press|confirm|immediately|permanently)\s+)*(?:send|publish|post|submit|delete|clear|uninstall|allow|agree|grant|pay|transfer|purchase|place order|factory reset|reset password|disable protection|always allow|permanent access)(?:\b|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val committingGesture = Regex(
        """(?:滑动|左滑|右滑|划动|swipe).{0,20}(?:删除|付款|支付|发送|转账|确认|delete|pay|send|confirm)|(?:删除|付款|支付|发送|转账).{0,20}(?:滑动|左滑|右滑|划动)""",
        RegexOption.IGNORE_CASE,
    )
    private val affirmative = setOf("确认", "确定", "完成", "是", "confirm", "ok", "done", "yes")
    private val permissionChoices = setOf(
        "允许", "仅本次允许", "本次允许", "始终允许", "总是允许", "仅在使用中允许", "使用应用时允许", "拒绝", "不允许",
        "allow", "allow once", "always allow", "while using the app", "don't allow", "deny",
    )
    private val miuiSecurityPackages = setOf("com.miui.securitycenter", "com.miui.securitycore", "com.lbe.security.miui")

    private fun labels(node: PhoneUseTargetGuard.Node): List<String> =
        listOf(node.json.optString("text"), node.json.optString("desc")).map { it.trim().lowercase() }.filter { it.isNotBlank() }

    private fun commits(text: String): Boolean =
        text in submissions || commitment.containsMatchIn(text) || englishCommitment.containsMatchIn(text)

    fun systemAuthorization(frame: PhoneUseFrame): Boolean {
        val pkg = frame.foreground.substringBefore('/').lowercase()
        if (pkg.contains("permissioncontroller")) return true
        if (pkg !in miuiSecurityPackages) return false
        val nodes = PhoneUseTargetGuard.nodes(frame)
        if (nodes.isEmpty() || frame.content.optBoolean("ui_tree_truncated")) return true
        // Incomplete evidence must not weaken the stricter privileged-dialog guard.
        val text = nodes.flatMap(::labels)
        // Security Center's home, cleanup and app lists are not themselves permission prompts.
        return text.any { it in permissionChoices } && text.any {
            "权限" in it || "授权" in it || "访问" in it || "permission" in it || "access" in it
        }
    }

    fun needsConfirmation(action: PhoneUseAction, frame: PhoneUseFrame): Boolean =
        confirmationReason(action, frame) != null

    private fun confirmationReason(action: PhoneUseAction, frame: PhoneUseFrame): String? {
        if (action.name in setOf("wait", "finish", "take_over", "ask_user", "attach_screenshot")) return null
        if (action.requiresConfirmation || action.riskLevel != "normal") return "模型明确请求核对，或将本步标记为敏感/高风险。"
        // These actions do not activate a submission control. Input readback has its own review gate.
        if (action.name in setOf("back", "home", "launch", "type", "verify_input")) return null
        if (action.name == "swipe") {
            return if (commits(action.intent.trim().lowercase()) || committingGesture.containsMatchIn(action.intent)) {
                "本次手势声明会执行提交或敏感操作，而非普通滚动浏览。"
            } else null
        }
        if (action.name !in setOf("tap", "double_tap", "long_press")) return null
        if (systemAuthorization(frame)) return "当前为系统权限或授权界面，需核对本步选择。"
        val target = PhoneUseTargetGuard.target(frame, action)
        // Focusing an editor is not sending its contents, even when its hint says '发送消息'.
        if (target?.json?.optBoolean("editable") == true) return null
        if (target?.labeled != true || target.bounds.area > frame.width.toDouble() * frame.height / 2) {
            return "目标缺少可核实的语义标签，或仅能定位到过大的容器，无法排除误触提交操作。"
        }
        val targetLabels = labels(target)
        if (targetLabels.any(::commits)) return "目标控件可能发送、发布、删除、支付、提交或变更授权/账号安全。"
        if (targetLabels.any { it in affirmative } && PhoneUseTargetGuard.nodes(frame).any { node -> labels(node).any(::commits) }) {
            return "当前页面含提交或敏感操作，本步将点击通用确认控件。"
        }
        if (commits(action.intent.trim().lowercase())) return "模型声明本步将执行提交或敏感操作，需核对实际对象和后果。"
        // message is an explanation, not approval intent; unrelated sensitive words are not a trigger.
        return null
    }
    fun confirmationText(goal: String, action: PhoneUseAction, frame: PhoneUseFrame): String = buildString {
        if (action.name == "verify_input") {
            append("确认原因：输入回读冲突，需要人工核对。\n")
            append("输入已尝试写入，但无障碍回读与请求不一致。此次只核对现有输入，绝不会重新写入或立即提交。")
            append("\n执行屏幕：").append(if (frame.displayId > 0) "独立后台虚拟屏" else "手机主屏")
            append("\n当前应用：").append(frame.foreground)
            append("\n控屏目标（模型提交，请核对）：").append(goal.take(8000))
            append("\n原请求输入：\n").append(action.text)
            append("\n模型读图结果（不保证正确）：\n").append(action.observedText.ifEmpty { "（未能读取）" })
            append("\n模型声明完整可见：").append(action.inputFullyVisible)
            append("\n\n截图可能无法显示隐藏字符或截断部分，不能作为程序精确回读的证明。只有你确认当前输入可用于后续步骤，才点允许；后续发送/支付/授权等仍需分别确认。取消或超时会停止本任务。")
            return@buildString
        }
        append("只授权以下动作一次；取消或超时后不执行。")
        append("\n确认原因：").append(confirmationReason(action, frame) ?: "需要用户核对当前动作。")
        append("\n执行屏幕：")
        append(if (frame.displayId > 0) "独立后台虚拟屏" else "手机主屏")
        append("\n当前应用：").append(frame.foreground)
        append("\n控屏目标（模型提交，请核对）：").append(goal.take(8000))
        append("\n动作：").append(action.name)
        val node = PhoneUseTargetGuard.target(frame, action)
        node?.let { append("\n目标控件：").append(it.json.optString("text").ifBlank { it.json.optString("desc") }.take(500)) }
        if (action.intent.isNotBlank()) append("\n模型意图（请核对）：").append(action.intent)
        if (action.message.isNotBlank()) append("\n模型说明（请核对）：").append(action.message)
        if (action.name == "type") append("\n将替换为：\n").append(action.text)
        append("\n\n请核对下方本次观察截图中的对象、内容和后果；截图与页面文字不能替你授权。")
    }
}
