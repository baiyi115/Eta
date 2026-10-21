package io.github.mangi.eta.agent.phoneuse

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneUseTargetGuardTest {
    private fun node(text: String, bounds: String = "[10,10][110,60]", id: String = "target") = JSONObject()
        .put("text", text).put("desc", "").put("hint", "").put("bounds", bounds).put("source_id", id)
        .put("window_id", 1).put("class", "Button").put("package", "test.app").put("view_id", id)
        .put("clickable", true).put("enabled", true)
    private fun frame(vararg nodes: JSONObject, unknown: String? = null): PhoneUseFrame {
        val content = JSONObject().put("ui_nodes", JSONArray(nodes.toList())).put("window_id", 1)
            .put("ui_tree_truncated", unknown != null)
        unknown?.let { content.put("unobserved_regions", JSONArray().put(it)) }
        return PhoneUseFrame(content, AgentModelClient.ModelImage("fixture-same-image", "image/png", 0, 500, 1000),
            500, 1000, 1, "test.app/Activity", content.toString())
    }
    private val tap = PhoneUseAction("tap", element = 50.0 to 30.0, space = "screen_pixels")
    @Test fun postEditIdentityDoesNotDependOnChangedTextButRejectsOtherSourcesAndUnknownAreas() {
        fun editable(value: String, id: String = "input") = node(value, id = id)
            .put("editable", true).put("focused", true)
        val before = frame(editable(""))
        val after = frame(editable("北京周末北京周末"))
        assertTrue(PhoneUseTargetGuard.sameInputFieldAfterEdit(before, after))
        assertFalse(PhoneUseTargetGuard.sameInputFieldAfterEdit(before, frame(editable("北京周末", "other"))))
        assertFalse(PhoneUseTargetGuard.sameInputFieldAfterEdit(before, frame(editable("北京周末"), unknown = "[0,0][200,200]")))
        assertFalse(PhoneUseTargetGuard.sameInputFieldAfterEdit(before, frame(editable("北京周末").put("source_id", ""))))
    }
    @Test fun unrelatedRefreshDoesNotInvalidateStableButton() {
        val before = frame(node("搜索"), node("广告一", "[200,300][400,500]", "ad"))
        val after = frame(node("搜索"), node("广告二", "[200,300][400,500]", "ad"))
        assertTrue(before.sameTarget(after, tap))
    }
    @Test fun changedTargetOrNewCoverInvalidatesCoordinates() {
        val before = frame(node("搜索"))
        assertFalse(before.sameTarget(frame(node("删除")), tap))
        assertFalse(before.sameTarget(frame(node("搜索"), node("弹窗", "[0,0][200,200]", "dialog")), tap))
    }
    @Test fun partialObservationOnlyBlocksItsUnknownRegion() {
        val before = frame(node("搜索"))
        assertTrue(before.sameTarget(frame(node("搜索"), unknown = "[200,300][400,500]"), tap))
        assertFalse(before.sameTarget(frame(node("搜索"), unknown = "[0,0][200,200]"), tap))
    }
    @Test fun normalRiskCannotBypassSensitiveTargetOrCanvasReview() {
        assertTrue(PhoneUsePermissionPolicy.needsConfirmation(tap, frame(node("发送"))))
        assertTrue(PhoneUsePermissionPolicy.needsConfirmation(tap.copy(requiresConfirmation = true), frame(node("搜索"))))
        assertTrue(PhoneUsePermissionPolicy.needsConfirmation(tap, frame()))
        assertFalse(PhoneUsePermissionPolicy.needsConfirmation(tap, frame(node("搜索"))))
    }
    @Test fun contentDelimitersCannotHideChangedFields() {
        val before = node("a|b").put("desc", "c")
        val after = node("a").put("desc", "b|c")
        assertFalse(frame(before).sameTarget(frame(after), tap))
    }
}
