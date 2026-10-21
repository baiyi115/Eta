package io.github.mangi.eta.agent.phoneuse

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneUsePolicyTest {
    @Test fun installerCaptchaAndPasswordPagesRequireTakeover() {
        for ((nodes, foreground) in listOf(
            JSONArray().put(JSONObject().put("password", true)) to "test.app",
            JSONArray().put(JSONObject().put("text", "请输入验证码")) to "test.app",
            JSONArray() to "com.android.packageinstaller",
        )) assertThrows(PhoneUseFailure::class.java) { PhoneUseScreenGuard.safeNodes(nodes, foreground) }
        PhoneUseScreenGuard.safeNodes(JSONArray().put(JSONObject().put("text", "搜索")), "test.app")
        // A readable system permission page is observed, then reviewed per action instead of hard-stopped.
        PhoneUseScreenGuard.safeNodes(JSONArray(), "com.android.permissioncontroller")
    }
    @Test fun sensitiveArgumentsAndObservationNeverReachTraceSummary() {
        val formatter = AgentTraceFormatter()
        for (name in listOf("phone_use", "background_phone_use")) {
            val args = JSONObject().put("goal", "private-goal").put("session", "private-session")
                .put("action", JSONObject().put("text", "private-text")).toString()
            assertFalse(formatter.summarizeArguments(AgentModelClient.ToolCall("t", name, args)).contains("private-"))
            assertFalse(formatter.summarizeResult(name, AgentModelClient.ToolResult(
                JSONObject().put("ok", true).put("observation", "private-screen").toString(), sensitive = true)).contains("private-"))
        }
    }
    @Test fun foregroundWorksWithoutRootAndBackgroundAlwaysRequiresIt() {
        assertFalse(AgentToolRequirements.rootDenied("phone_use", JSONObject(), false))
        assertTrue(AgentToolRequirements.rootDenied("background_phone_use", JSONObject(), false))
        assertTrue(AgentToolRequirements.requiresAccessibility("phone_use"))
        assertTrue(AgentToolRequirements.requiresAccessibility("background_phone_use"))
    }
}
