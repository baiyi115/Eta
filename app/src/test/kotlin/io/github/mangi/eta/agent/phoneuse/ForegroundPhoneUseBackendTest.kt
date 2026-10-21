package io.github.mangi.eta.agent.phoneuse

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ForegroundPhoneUseBackendTest {
    private fun observation(): JSONObject = JSONObject()
        .put("ok", true)
        .put("screen", JSONObject().put("width", 720).put("height", 1280))
        .put("focus", JSONObject().put("package", "test.app"))
        .put("ui_nodes", JSONArray())
        .put("coordinate_contract", JSONObject().put("normalized", "0..999"))
        .put("screenshot", JSONObject().put("source", "accessibility").put("complete", true))

    private fun backend(payload: JSONObject, imageCount: Int = 1) = ForegroundPhoneUseBackend(
        RuntimeEnvironment.getApplication(),
        execute = { AgentModelClient.ToolResult(payload.toString(), List(imageCount) {
            AgentModelClient.ModelImage(reference = "data:image/png;base64,fixture", mimeType = "image/png", bytes = 0, width = 360, height = 640)
        }) },
        checkActive = {},
    )

    @Test fun captureRequiresAccessibilityAndAllCriticalWindows() {
        for (metadata in listOf(
            JSONObject().put("source", "root").put("complete", true),
            JSONObject().put("source", "accessibility").put("complete", false),
            JSONObject().put("source", "accessibility").put("complete", true).put("critical_window_missing", true),
        )) {
            backend(observation().put("screenshot", metadata)).use {
                assertThrows(PhoneUseFailure::class.java) { it.observe() }
            }
        }
    }

    @Test fun partialCaptureWithCompleteCriticalWindowsCanStartAnObservation() {
        backend(observation().put("screenshot",
            JSONObject().put("source", "accessibility").put("complete", false).put("partial", true))).use {
            assertEquals("test.app", it.observe().foreground)
        }
    }

    @Test fun missingDisplayLocalTreeIsReportedButMissingImageStillStops() {
        backend(observation().put("ui_tree_truncated", true)).use {
            val frame = it.observe()
            assertEquals("unavailable", frame.content.getString("ui_tree_status"))
            assertEquals(0, frame.content.getJSONArray("ui_nodes").length())
        }
        backend(observation(), imageCount = 0).use {
            assertEquals("SCREENSHOT_UNAVAILABLE", assertThrows(PhoneUseFailure::class.java) { it.observe() }.code)
        }
    }

    @Test fun observationRetainsBothGeometriesAndRemovesPrimitiveCoordinateHint() {
        backend(observation()).use {
            val frame = it.observe()
            assertEquals(720, frame.width)
            assertEquals(1280, frame.height)
            assertEquals(360, frame.image.width)
            assertEquals(640, frame.image.height)
            assertFalse(frame.content.has("coordinate_contract"))
            assertEquals("test.app", frame.foreground)
            assertEquals(0, frame.displayId)
        }
    }
}
