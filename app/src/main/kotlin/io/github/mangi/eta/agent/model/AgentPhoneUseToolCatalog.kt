package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Session protocol adapted from MiAIPlus, without XiaoAI hooks or a separate model client. */
internal object AgentPhoneUseToolCatalog {
    val names = setOf("phone_use", "background_phone_use")
    fun appendTo(
        tools: JSONArray,
        includeForeground: Boolean,
        includeBackground: Boolean,
    ) {
        names.forEach { name ->
            if (name == "phone_use" && !includeForeground) return@forEach
            if (name == "background_phone_use" && !includeBackground) return@forEach
            val point = JSONObject().put("type", "array").put("items", JSONObject().put("type", "number"))
                .put("minItems", 2).put("maxItems", 2)
            fun string(description: String) = JSONObject().put("type", "string").put("description", description)
            val action = JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf(
                        "tap", "double_tap", "long_press", "swipe", "type", "verify_input", "back", "home", "launch", "wait", "attach_screenshot", "finish", "take_over", "ask_user"))))
                    .put("element", point).put("start", point).put("end", point)
                    .put("intent", string("每步简短说明实际操作意图，不含隐私；仅进入页面、搜索、浏览、聚焦或填写输入框不是发送/支付提交。"))
                    .put("requires_confirmation", JSONObject().put("type", "boolean")
                        .put("description", "实际发送、发布、删除、支付、系统授权等提交步骤（含滑动删除等手势）必须true；普通导航、搜索、浏览、滑动、聚焦和填写输入框默认false。不因任务以后包含敏感步骤而提前要求每步确认，不能由模型自行批准。"))
                    .put("risk_level", JSONObject().put("type", "string").put("enum", JSONArray(listOf("normal", "sensitive", "critical")))
                        .put("description", "只声明本步实际风险，不是整个任务风险；普通操作normal，sensitive/critical均需用户确认，normal不能绕过宿主检测。"))
                    .put("text", string("type：替换当前聚焦的非密码输入框内容。"))
                    .put("observed_text", string("verify_input必填：实际从最新截图中读出的原输入框完整内容，不得照抄期望文本；不确定时填已读部分并requires_confirmation=true交用户核对。此动作绝不写入。"))
                    .put("input_fully_visible", JSONObject().put("type", "boolean")
                        .put("description", "verify_input：仅当原输入框全部文字清晰可见、没有截断/省略时true；否则false并请求用户核对。"))
                    .put("app", string("launch：已安装应用的包名，可先用 search_apps 查询。"))
                    .put("duration_ms", JSONObject().put("type", "integer").put("minimum", 100).put("maximum", 3000)
                        .put("description", "wait/swipe/long_press时长；long_press最少300，默认800；其他默认500。"))
                    .put("attach_screenshot", JSONObject().put("type", "boolean")
                        .put("description", "finish是否附上最后观察的截图，默认true；用户要求纯文字时设false。已通过attach_screenshot动作选取的截图仍保留。"))
                    .put("message", string("finish：填写实际读取内容和完成证据；敏感提交：说明具体对象、内容和后果，并显式requires_confirmation=true；仅有说明文字不代表需要确认，普通意图用intent。ask_user/take_over会结束运行。")))
                .put("required", JSONArray().put("action"))
            tools.put(AgentToolSchema.function(name,
                (if (name == "phone_use") "在主屏逐步操作手机。" else "仅用户明确要求后台操作时使用：在Root独立虚拟屏操作，ROM不支持则停止，禁止回退主屏。vision_only表示仅有虚拟屏截图，可按图点击/滑动或等待；type需要该屏可读取的聚焦输入框。") +
                    "首次传goal和可选max_steps（1–200，默认30），返回session、observation_id和截图。后续必须带同一session与最新observation_id，每次一个action；每步后返回新截图。" +
                    "INPUT_REVIEW_REQUIRED表示输入动作已提交但精确回读不一致，不代表未写入；此时只能verify_input、ask_user或take_over，禁止重复type、点击搜索/提交或finish。" +
                    "请实际读取附图中原输入框文字，用verify_input提交observed_text和input_fully_visible；完整读图结果与原请求完全一致、目标/像素未变且input_review.manual_required=false时，普通输入可按模型视觉核对继续；manual_required=true时必须人工核对。看不全/不确定时requires_confirmation=true请求原生人工核对，敏感输入始终人工核对。" +
                    "INPUT_REVIEWED后执行下一步，不要重输；basis=model_visual/user_review不代表程序精确回读。" +
                    "读取到的内容必须直接回复到当前聊天。attach_screenshot动作保留当前观察截图作为回复附件（最多7张），不操作屏幕；finish默认再附最后截图，最多8张并自动去重。" +
                    "需要多页截图时在离开每页前调用attach_screenshot；图片由宿主直接展示，不要伪造链接。" +
                    "tap/double_tap/long_press用element:[x,y]，swipe用start/end；默认截图原始像素。normalized为0–1000。PAGE_CHANGED表示动作未执行，应重新规划，也消耗一步；连续6次页面不稳定会停止。partial表示有未观察区域，禁止向这些区域操作。launch_state=awaiting_confirmation不代表应用已打开，应根据当前界面继续。" +
                    "密码、验证码和安装界面交用户接管；系统权限或敏感提交由宿主显示截图和动作详情，用户允许后才执行这一步，页面变化会作废确认。禁止混用另一种Phone use或直接手势工具；只读get_current_context/web_search/fetch_url可配合使用。4分钟空闲后自动释放会话。",
                JSONObject().put("type", "object").put("additionalProperties", false)
                    .put("properties", JSONObject()
                        .put("goal", string("首次调用必填，完整任务；不与action同时传。"))
                        .put("max_steps", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 200))
                        .put("session", string("后续调用必填，首次返回的会话ID。"))
                        .put("observation_id", string("后续调用必填，最近返回的观察ID。"))
                        .put("coordinate_space", JSONObject().put("type", "string")
                            .put("enum", JSONArray(listOf("screenshot_pixels", "screen_pixels", "normalized"))))
                        .put("action", action))))
        }
    }
}
