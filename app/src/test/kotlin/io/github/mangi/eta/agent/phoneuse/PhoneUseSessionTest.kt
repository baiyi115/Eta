package io.github.mangi.eta.agent.phoneuse

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneUseSessionTest {
    private class Backend : PhoneUseBackend {
        var frame = frame()
        val actions = mutableListOf<PhoneUseAction>()
        var starts = 0
        var closes = 0
        var failure: Exception? = null
        var performFailure: Exception? = null
        var observeFailure: Exception? = null
        var afterPerform: (() -> Unit)? = null
        var approval = true
        val confirmations = mutableListOf<String>()
        var afterApproval: (() -> Unit)? = null
        override fun confirm(action: PhoneUseAction, frame: PhoneUseFrame, goal: String): Boolean { confirmations += action.name; afterApproval?.invoke(); return approval }
        override fun start() { starts++ }
        override fun observe(): PhoneUseFrame { observeFailure?.let { throw it }; failure?.let { throw it }; return frame }
        override fun perform(action: PhoneUseAction, frame: PhoneUseFrame) {
            actions += action
            afterPerform?.invoke()
            failure?.let { throw it }
            performFailure?.let { throw it }
        }
        override fun close() { closes++ }
    }
    private class Fixture {
        val backend = Backend()
        var time = 1L
        var cancelled = false
        var factories = 0
        val session = PhoneUseSession({ factories++; backend }, { if (cancelled) throw InterruptedException("cancelled") }, { time })
        val initial get() = JSONObject(session.execute("phone_use", JSONObject().put("goal", "test")).content)
        fun action(observed: JSONObject, name: String = "tap", tool: String = "phone_use", space: String = "normalized") = session.execute(tool,
            JSONObject().put("session", observed.getString("session")).put("observation_id", observed.getString("observation_id"))
                .put("coordinate_space", space).put("action", JSONObject().put("action", name).put("element", org.json.JSONArray(listOf(1000, 1000)))))
    }

    @Test fun normalizedEndpointsMapToLastScreenPixel() {
        val f = Fixture(); val first = f.initial
        val next = JSONObject(f.action(first).content)
        assertEquals(Pair(719.0, 1279.0), f.backend.actions.single().element)
        assertEquals(29, next.getInt("remaining_steps"))
        assertNotEquals(first.getString("observation_id"), next.getString("observation_id"))
    }
    @Test fun screenshotPixelsUseScreenshotGeometry() {
        val f = Fixture(); val first = f.initial
        val args = JSONObject().put("session", first.getString("session")).put("observation_id", first.getString("observation_id"))
            .put("action", JSONObject().put("action", "tap").put("element", org.json.JSONArray(listOf(359, 639))))
        assertTrue(JSONObject(f.session.execute("phone_use", args).content).getBoolean("ok"))
        assertEquals(Pair(719.0, 1279.0), f.backend.actions.single().element)
    }
    @Test fun staleObservationAndWrongOwnerNeverDispatchOrStartAnotherBackend() {
        val f = Fixture(); val first = f.initial
        assertNull(f.action(first, tool = "background_phone_use").stopMessage)
        assertEquals("INVALID_ARGUMENT", JSONObject(f.action(first, space = "invalid").content).getString("code"))
        f.action(first)
        assertEquals("INVALID_ARGUMENT", JSONObject(f.action(first).content).getString("code"))
        assertEquals(1, f.backend.actions.size); assertEquals(1, f.factories)
    }
    @Test fun changedPageReturnsNewObservationAndConsumesReplanningBudget() {
        val f = Fixture(); val first = f.initial
        f.backend.frame = frame(signature = "changed")
        val response = JSONObject(f.action(first).content)
        assertEquals("PAGE_CHANGED", response.getString("code")); assertEquals(29, response.getInt("remaining_steps"))
        assertTrue(f.backend.actions.isEmpty()); assertNotEquals(first.getString("observation_id"), response.getString("observation_id"))
        f.action(response); assertEquals(1, f.backend.actions.size)
    }
    @Test fun visualChangeAndDisplayChangePreventInput() {
        for (changed in listOf(frame(anchor = "other"), frame(displayId = 5))) {
            val f = Fixture(); val first = f.initial; f.backend.frame = changed
            assertEquals("PAGE_CHANGED", JSONObject(f.action(first).content).getString("code")); assertTrue(f.backend.actions.isEmpty())
        }
    }
    @Test fun outOfRangeAndFractionalBudgetAreRejectedBeforeDispatch() {
        val f = Fixture()
        val invalid = f.session.execute("phone_use", JSONObject().put("goal", "test").put("max_steps", 1.5))
        assertEquals("INVALID_ARGUMENT", JSONObject(invalid.content).getString("code")); assertEquals(0, f.factories)
        val first = f.initial
        assertEquals("INVALID_ARGUMENT", JSONObject(f.action(first, space = "screen_pixels").content).getString("code"))
        assertTrue(f.backend.actions.isEmpty())
    }
    @Test fun exhaustedBudgetStopsAndCannotRestart() {
        val f = Fixture(); val first = JSONObject(f.session.execute("phone_use", JSONObject().put("goal", "test").put("max_steps", 1)).content)
        val next = JSONObject(f.action(first).content)
        assertNotNull(f.action(next).stopMessage); assertEquals(1, f.backend.actions.size); assertEquals(1, f.backend.closes)
        assertNotNull(f.session.execute("background_phone_use", JSONObject().put("goal", "fallback")).stopMessage)
        assertEquals(1, f.factories)
    }
    @Test fun takeoverActionsReleaseBackendAndStopWholeRun() {
        for (name in setOf("ask_user", "take_over")) {
            val f = Fixture(); val result = f.action(f.initial, name)
            assertNotNull(result.stopMessage); assertTrue(result.sensitive); assertEquals(1, f.backend.closes); assertTrue(f.backend.actions.isEmpty())
        }
    }
    @Test fun finishReleasesBackendAndPreservesObservationForFinalAnswer() {
        val f = Fixture(); val first = f.initial
        val result = f.action(first, "finish")
        assertNull(result.stopMessage)
        assertTrue(result.finalizeResponse)
        assertTrue(result.sensitive)
        assertEquals("COMPLETED", JSONObject(result.content).getString("status"))
        assertEquals(first.getJSONObject("observation").toString(), JSONObject(result.content).getJSONObject("observation").toString())
        assertEquals(listOf(f.backend.frame.image), result.images)
        assertEquals(1, f.backend.closes)
        assertTrue(f.backend.actions.isEmpty())
        assertNotNull(f.session.execute("phone_use", JSONObject().put("goal", "retry")).stopMessage)
        assertEquals(1, f.factories)
    }
    @Test fun idleTimeoutIsRenewedByObservation() {
        val f = Fixture(); val first = f.initial
        f.time += PhoneUseSession.IDLE_TIMEOUT_MS - 1
        val next = JSONObject(f.action(first).content)
        f.time += PhoneUseSession.IDLE_TIMEOUT_MS
        assertEquals("SESSION_EXPIRED", JSONObject(f.action(next).content).getString("code")); assertEquals(1, f.backend.actions.size)
    }
    @Test fun backendArgumentExceptionIsFatalAndCannotFallBack() {
        val f = Fixture(); val first = f.initial; f.backend.failure = IllegalArgumentException("platform failure")
        val result = f.action(first)
        assertNotNull(result.stopMessage); assertEquals("BACKEND_FAILED", JSONObject(result.content).getString("code")); assertEquals(1, f.backend.closes)
        assertNotNull(f.session.execute("phone_use", JSONObject().put("goal", "retry")).stopMessage); assertEquals(1, f.factories)
    }
    @Test fun cancellationPropagatesAndClosePreventsDispatch() {
        val f = Fixture(); val first = f.initial; f.cancelled = true
        assertThrows(InterruptedException::class.java) { f.action(first) }; assertTrue(f.backend.actions.isEmpty())
        f.cancelled = false; f.session.close()
        assertNotNull(f.action(first).stopMessage); assertTrue(f.backend.actions.isEmpty())
    }
    @Test fun closeRacingWithFactoryClosesNewlyCreatedBackend() {
        val backend = Backend()
        lateinit var session: PhoneUseSession
        session = PhoneUseSession({ session.close(); backend }, {})
        assertNotNull(session.execute("phone_use", JSONObject().put("goal", "test")).stopMessage)
        assertEquals(0, backend.starts); assertEquals(1, backend.closes)
    }
    @Test fun rejectedApprovalNeverDispatches() {
        val f = Fixture(); val first = f.initial
        f.backend.approval = false
        val result = f.action(first)
        assertEquals("USER_APPROVAL_REQUIRED", JSONObject(result.content).getString("code"))
        assertTrue(f.backend.actions.isEmpty()); assertNotNull(result.stopMessage)
    }
    @Test fun approvalCannotAuthorizeTheChangedPage() {
        val f = Fixture(); val first = f.initial
        f.backend.afterApproval = { f.backend.frame = frame(signature = "changed-during-confirmation") }
        val result = JSONObject(f.action(first).content)
        assertEquals("PAGE_CHANGED", result.getString("code"))
        assertTrue(f.backend.actions.isEmpty())
    }
    @Test fun sixUnstablePagesTerminateWithoutExecuting() {
        val f = Fixture(); var observed = f.initial
        for (i in 1..6) {
            f.backend.frame = frame(signature = "changed-$i")
            observed = JSONObject(f.action(observed).content)
        }
        assertEquals("PAGE_UNSTABLE", observed.getString("code"))
        assertTrue(f.backend.actions.isEmpty()); assertEquals(1, f.backend.closes)
    }
    @Test fun fourthIdenticalUnchangedActionIsNotRepeated() {
        val f = Fixture(); var observed = f.initial
        repeat(3) { observed = JSONObject(f.action(observed).content) }
        val result = f.action(observed)
        assertEquals("NO_PROGRESS", JSONObject(result.content).getString("code"))
        assertEquals(3, f.backend.actions.size)
    }
    @Test fun textTimeoutReturnsOnlyFreshPostDispatchScreenshotAndStops() {
        val f = Fixture(); val first = f.initial
        val postImage = f.backend.frame.image.copy(reference = "data:image/png;base64,after-input")
        f.backend.afterPerform = {
            f.backend.frame = f.backend.frame.copy(image = postImage, content = JSONObject().put("state", "after-input"))
        }
        f.backend.performFailure = PhoneUseFailure("TEXT_READBACK_TIMEOUT", "回读未确认")
        val result = f.action(first)
        assertEquals("TEXT_READBACK_TIMEOUT", JSONObject(result.content).getString("code"))
        assertTrue(JSONObject(result.content).getBoolean("diagnostic_screenshot"))
        assertEquals("after-input", JSONObject(result.content).getJSONObject("observation").getString("state"))
        assertEquals(listOf(postImage), result.images)
        assertEquals(listOf(postImage), result.replyImages)
        assertNotNull(result.stopMessage)
        assertEquals(1, f.backend.actions.size); assertEquals(1, f.backend.closes)
        assertNotNull(f.action(first).stopMessage)
        assertEquals(1, f.backend.actions.size)
    }
    @Test fun textTimeoutDoesNotReuseOldScreenshotWhenFreshCaptureIsUnsafe() {
        val f = Fixture(); val first = f.initial
        f.backend.afterPerform = { f.backend.observeFailure = PhoneUseFailure("PASSWORD_REQUIRED", "需要接管") }
        f.backend.performFailure = PhoneUseFailure("TEXT_READBACK_TIMEOUT", "回读未确认")
        val result = f.action(first)
        assertEquals("TEXT_READBACK_TIMEOUT", JSONObject(result.content).getString("code"))
        assertTrue(result.images.isEmpty()); assertTrue(result.replyImages.isEmpty())
        assertFalse(JSONObject(result.content).has("diagnostic_screenshot"))
        assertEquals(1, f.backend.closes)
    }
    @Test fun textTimeoutCannotAttachEvidenceFromAnotherDisplay() {
        val f = Fixture(); val first = f.initial
        f.backend.afterPerform = { f.backend.frame = f.backend.frame.copy(displayId = 9) }
        f.backend.performFailure = PhoneUseFailure("TEXT_READBACK_TIMEOUT", "回读未确认")
        val result = f.action(first)
        assertTrue(result.images.isEmpty()); assertTrue(result.replyImages.isEmpty())
        assertEquals(1, f.backend.actions.size); assertEquals(1, f.backend.closes)
    }
    @Test fun textTimeoutCancellationDoesNotPublishScreenshot() {
        val f = Fixture(); val first = f.initial
        f.backend.afterPerform = { f.cancelled = true }
        f.backend.performFailure = PhoneUseFailure("TEXT_READBACK_TIMEOUT", "回读未确认")
        assertThrows(InterruptedException::class.java) { f.action(first) }
        assertEquals(1, f.backend.actions.size); assertEquals(1, f.backend.closes)
    }
    private class InputFixture {
        val backend = Backend().apply { frame = inputFrame("") }
        val session = PhoneUseSession({ backend }, {})
        val initial = JSONObject(session.execute("background_phone_use", JSONObject().put("goal", "搜索北京周末")).content)
        fun send(observed: JSONObject, action: JSONObject) = session.execute("background_phone_use", JSONObject()
            .put("session", observed.getString("session")).put("observation_id", observed.getString("observation_id"))
            .put("action", action))
        fun type(manual: Boolean = false, visualEligible: Boolean = true): JSONObject {
            backend.performFailure = PhoneUseFailure("TEXT_READBACK_TIMEOUT", "接口报告8个字符，请求4个字符", visualEligible)
            backend.afterPerform = { backend.frame = inputFrame(if (visualEligible) "北\u200B京\u200B周\u200B末\u200B" else "北京周末北京周末") }
            val result = send(initial, JSONObject().put("action", "type").put("text", "北京周末").put("requires_confirmation", manual))
            backend.performFailure = null; backend.afterPerform = null
            assertNull(result.stopMessage)
            assertEquals(0, backend.closes)
            return JSONObject(result.content)
        }
        fun review(observed: JSONObject, text: String = "北京周末", complete: Boolean = true) = send(observed,
            JSONObject().put("action", "verify_input").put("observed_text", text).put("input_fully_visible", complete))
    }
    @Test fun ordinaryVisualInputReviewContinuesSameDisplayWithoutRewriting() {
        val f = InputFixture(); val pending = f.type()
        assertEquals("INPUT_REVIEW_REQUIRED", pending.getString("code"))
        assertFalse(pending.getJSONObject("input_review").getBoolean("exact_readback_verified"))
        val reviewed = f.review(pending)
        val json = JSONObject(reviewed.content)
        assertNull(reviewed.stopMessage)
        assertEquals("INPUT_REVIEWED", json.getString("code"))
        assertEquals("model_visual", json.getJSONObject("input_verification").getString("basis"))
        assertFalse(json.getJSONObject("input_verification").getBoolean("exact_readback_verified"))
        assertEquals(listOf("type"), f.backend.actions.map { it.name })
        assertTrue(f.backend.confirmations.isEmpty()); assertEquals(0, f.backend.closes)
        val next = f.send(json, JSONObject().put("action", "tap").put("element", org.json.JSONArray(listOf(445, 30))))
        assertTrue(JSONObject(next.content).getBoolean("ok"))
        assertEquals(listOf("type", "tap"), f.backend.actions.map { it.name })
        f.session.close()
    }
    @Test fun pendingInputCannotBeRewrittenOrSubmittedBeforeReview() {
        val f = InputFixture(); var pending = f.type()
        for (action in listOf(
            JSONObject().put("action", "type").put("text", "北京周末"),
            JSONObject().put("action", "tap").put("element", org.json.JSONArray(listOf(445, 30))),
            JSONObject().put("action", "finish"),
        )) {
            val result = f.send(pending, action)
            pending = JSONObject(result.content)
        }
        assertEquals(listOf("type"), f.backend.actions.map { it.name })
        assertEquals("STOPPED", pending.getString("status"))
        assertEquals(1, f.backend.closes)
    }
    @Test fun sensitiveInputCannotUseModelVisualClaimToBypassUserReview() {
        val f = InputFixture(); val pending = f.type(manual = true)
        assertTrue(pending.getJSONObject("input_review").getBoolean("manual_required"))
        val result = JSONObject(f.review(pending).content)
        assertEquals(listOf("type", "verify_input"), f.backend.confirmations)
        assertEquals("user_review", result.getJSONObject("input_verification").getString("basis"))
        assertEquals(1, f.backend.actions.size)
        f.session.close()
    }
    @Test fun meaningfulExtraAccessibilityTextCannotBeAutoApprovedByACorrectLookingScreenshot() {
        val f = InputFixture(); val pending = f.type(visualEligible = false)
        assertTrue(pending.getJSONObject("input_review").getBoolean("manual_required"))
        f.backend.approval = false
        val result = f.review(pending)
        assertEquals("INPUT_REVIEW_DECLINED", JSONObject(result.content).getString("code"))
        assertEquals(listOf("verify_input"), f.backend.confirmations)
        assertEquals(1, f.backend.actions.size); assertEquals(1, f.backend.closes)
    }
    @Test fun incompleteOrDifferentVisualTextRequiresActualUserNotSubstringMatching() {
        for ((text, complete) in listOf("北京周末额外文字" to true, "北京周末" to false)) {
            val f = InputFixture(); val pending = f.type()
            f.backend.approval = false
            val result = f.review(pending, text, complete)
            assertEquals("INPUT_REVIEW_DECLINED", JSONObject(result.content).getString("code"))
            assertNotNull(result.stopMessage)
            assertEquals(listOf("verify_input"), f.backend.confirmations)
            assertEquals(1, f.backend.actions.size); assertEquals(1, f.backend.closes)
        }
    }
    @Test fun inputReviewCannotSwitchToAnotherSourceOrReuseChangedApproval() {
        val f = InputFixture(); val pending = f.type()
        f.backend.frame = inputFrame("北京周末北京周末", source = "another-input")
        val changed = f.review(pending)
        assertEquals("INPUT_REVIEW_CHANGED", JSONObject(changed.content).getString("code"))
        assertEquals(1, f.backend.actions.size); assertEquals(1, f.backend.closes)
        val g = InputFixture(); val sensitive = g.type(manual = true)
        g.backend.afterApproval = { g.backend.frame = inputFrame("changed") }
        assertEquals("INPUT_REVIEW_CHANGED", JSONObject(g.review(sensitive).content).getString("code"))
        assertEquals(1, g.backend.actions.size); assertEquals(1, g.backend.closes)
    }
    @Test fun verifyInputWithoutPendingWriteCannotManufactureVerification() {
        val f = InputFixture()
        val result = f.review(f.initial)
        assertEquals("INVALID_ARGUMENT", JSONObject(result.content).getString("code"))
        assertTrue(f.backend.actions.isEmpty()); assertTrue(f.backend.confirmations.isEmpty())
        f.session.close()
    }
    companion object {
        private fun inputFrame(value: String, source: String = "input-source"): PhoneUseFrame {
            val input = JSONObject().put("text", value).put("desc", "").put("hint", "搜索")
                .put("bounds", "[10,10][390,80]").put("source_id", source).put("window_id", 17)
                .put("class", "EditText").put("package", "test.app").put("view_id", "search_input")
                .put("editable", true).put("focused", true).put("enabled", true).put("password", false)
            val search = JSONObject().put("text", "搜索").put("bounds", "[400,10][490,80]")
                .put("source_id", "search-button").put("window_id", 17).put("class", "Button")
                .put("package", "test.app").put("clickable", true).put("enabled", true)
            val content = JSONObject().put("window_id", 17).put("ui_nodes", org.json.JSONArray().put(input).put(search))
            return PhoneUseFrame(content, AgentModelClient.ModelImage("fixture-input-pixels", "image/png", 0, 500, 1000),
                500, 1000, 7, "test.app/Activity", content.toString())
        }
        private fun frame(signature: String = "page", displayId: Int = 0, anchor: String = "pixels") = PhoneUseFrame(
            JSONObject(), AgentModelClient.ModelImage("data:image/png;base64,test", "image/png", 4, 360, 640),
            720, 1280, displayId, "test.app", signature, { anchor },
        )
    }
}
