package io.github.mangi.eta.agent.phoneuse

import android.app.KeyguardManager
import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

internal object PhoneUseScreenGuard {
    fun unlocked(context: Context) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
            ?: throw PhoneUseFailure("KEYGUARD_UNAVAILABLE", "无法确认锁屏状态，已停止。")
        if (keyguard.isKeyguardLocked || keyguard.isDeviceLocked) {
            throw PhoneUseFailure("DEVICE_LOCKED", "手机已锁定，请解锁后重新开始。")
        }
    }

    fun safeNodes(nodes: JSONArray, foreground: String) {
        if (foreground.contains("packageinstaller", ignoreCase = true)) {
            throw PhoneUseFailure("USER_PERMISSION_REQUIRED", "安装界面需要用户接管。")
        }
        val captcha = Regex("验证码|人机验证|滑块验证|安全验证|captcha|verify you are human|verification code", RegexOption.IGNORE_CASE)
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            if (node.optBoolean("password")) throw PhoneUseFailure("PASSWORD_REQUIRED", "密码界面需要用户接管。")
            if (captcha.containsMatchIn(listOf("text", "desc", "hint").joinToString(" ") { node.optString(it) })) {
                throw PhoneUseFailure("VERIFICATION_REQUIRED", "验证界面需要用户接管。")
            }
        }
    }

    fun semanticContent(snapshot: AgentAccessibilityService.PhoneUseDisplaySnapshot): JSONObject {
        val nodes = JSONArray()
        snapshot.nodes.forEachIndexed { index, node ->
            nodes.put(JSONObject().put("text", if (node.password) "" else node.text)
                .put("desc", if (node.password) "" else node.desc).put("hint", if (node.password) "" else node.hint)
                .put("class", node.className).put("package", node.packageName).put("view_id", node.viewId)
                .put("source_id", snapshot.sourceIds.getOrElse(index) { "" }).put("window_id", snapshot.windowId)
                .put("bounds", node.bounds.toShortString()).put("clickable", node.clickable)
                .put("long_clickable", node.longClickable).put("scrollable", node.scrollable)
                .put("editable", node.editable).put("focused", node.focused).put("password", node.password)
                .put("enabled", node.enabled).put("checked", node.checked ?: JSONObject.NULL).put("selected", node.selected))
        }
        return JSONObject().put("ui_nodes", nodes).put("window_id", snapshot.windowId)
            .put("ui_tree_truncated", snapshot.truncated)
            .put("unobserved_regions", JSONArray(snapshot.unobservedRegions.map { it.toShortString() }))
    }

    /** Only a small, quantized target region participates: clocks or unrelated animations must not replay actions. */
    fun visualAnchor(image: AgentModelClient.ModelImage, width: Int, height: Int): (PhoneUseAction) -> String {
        return { action ->
            val pixels = action.inScreenPixels(PhoneUseFrame(JSONObject(), image, width, height, 0, "", ""))
            val bitmap = PhoneUseImageSampling.decode(
                image.reference,
                PhoneUseImageSampling.MAX_GUARD_PIXELS,
            )
                ?: throw PhoneUseFailure("IMAGE_UNAVAILABLE", "无法解码截图。")
            try {
                val points = listOfNotNull(pixels.element, pixels.start, pixels.end)
                buildString {
                    // type validates the focused node via the semantic tree; point actions also anchor actual pixels.
                    points.forEach { (screenX, screenY) ->
                        val x = (screenX * (bitmap.width - 1) / (width - 1)).toInt()
                        val y = (screenY * (bitmap.height - 1) / (height - 1)).toInt()
                        for (dy in -24..24 step 8) for (dx in -24..24 step 8) {
                            val color = bitmap.getPixel((x + dx).coerceIn(0, bitmap.width - 1), (y + dy).coerceIn(0, bitmap.height - 1))
                            append((color shr 20) and 15).append(',').append((color shr 12) and 15)
                                .append(',').append((color shr 4) and 15).append(';')
                        }
                    }
                    if (points.isEmpty() && action.name != "type") append(image.reference.hashCode())
                }
            } finally { bitmap.recycle() }
        }
    }
}
