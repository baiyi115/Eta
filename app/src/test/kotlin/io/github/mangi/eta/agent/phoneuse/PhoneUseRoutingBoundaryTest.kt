package io.github.mangi.eta.agent.phoneuse

import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpToolExecutor
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.*
import org.junit.Test

class PhoneUseRoutingBoundaryTest {
    @Test fun sessionGuardRunsBeforeEitherLocalOrRemoteRouting() {
        var localCalls = 0
        val checked = mutableListOf<String>()
        val executor = RoutingToolExecutor(
            local = { localCalls++; error("An owned phone session must stop mixed tools before dispatch") },
            mcp = McpToolExecutor(McpRunSnapshot.EMPTY),
            beforeTool = { name ->
                checked += name
                AgentModelClient.ToolResult("{\"ok\":false}", sensitive = true, stopMessage = "session owns device")
            },
        )
        executor.use {
            for (name in listOf("terminal", "mcp_server_remote_action")) {
                assertEquals("session owns device", it.execute(AgentModelClient.ToolCall("t", name, "{}")).stopMessage)
            }
        }
        assertEquals(listOf("terminal", "mcp_server_remote_action"), checked)
        assertEquals(0, localCalls)
    }
}
