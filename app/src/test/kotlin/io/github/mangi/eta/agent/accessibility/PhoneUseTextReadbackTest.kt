package io.github.mangi.eta.agent.accessibility

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [PhoneUseTextReadbackTest.RefreshNodeShadow::class])
class PhoneUseTextReadbackTest {
    private val controller = Robolectric.buildService(AgentAccessibilityService::class.java)
    private lateinit var service: AgentAccessibilityService
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo

    @Before fun setUp() {
        service = controller.create().get()
        window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply {
            setId(RefreshNodeShadow.WINDOW_ID)
            setDisplayId(7)
            setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        }
        root = node(editable = false, value = "")
        shadowOf(window).setRoot(root)
        shadowOf(service).setWindowsOnDisplay(7, listOf(window))
    }

    @After fun tearDown() {
        RefreshNodeShadow.onRefresh = null
        controller.destroy()
    }

    @Test fun refreshesEditedChildInsteadOfVerifyingItsCachedHint() {
        val field = node(value = "搜索").apply { isShowingHintText = true }
        shadowOf(root).addChild(field)
        var fieldRefreshes = 0
        RefreshNodeShadow.onRefresh = { refreshed ->
            if (refreshed == field) {
                fieldRefreshes++
                refreshed.text = "北京周末"
                refreshed.isShowingHintText = false
            }
            true
        }
        val actual = read(field)
        assertEquals("北京周末", property(actual, "getText"))
        assertEquals(1, fieldRefreshes)
        assertTrue(shadowOf(field).performedActions.isEmpty())
    }

    @Test fun rejectsCachedExpectedTextIfEditedNodeCannotRefresh() {
        val field = node(value = "北京周末")
        shadowOf(root).addChild(field)
        RefreshNodeShadow.onRefresh = { refreshed -> refreshed != field }
        val actual = read(field)
        assertNull(property(actual, "getText"))
        val reason = property(actual, "getUnavailableReason").toString()
        assertTrue(reason, reason.contains("刷新失败"))
    }

    @Test fun cannotVerifyAgainstDifferentFocusedFieldWithSameValue() {
        val original = node(value = "旧值")
        val replacement = node(value = "北京周末").apply { isFocused = true }
        shadowOf(root).addChild(replacement)
        assertNull(property(read(original), "getText"))
    }

    @Test fun nodeChangingPackageDuringRefreshCannotVerifyInput() {
        val field = node(value = "北京周末")
        shadowOf(root).addChild(field)
        RefreshNodeShadow.onRefresh = { refreshed ->
            if (refreshed == field) refreshed.packageName = "other.app"
            true
        }
        assertNull(property(read(field), "getText"))
    }

    @Test fun nullValueIsNotVerifiedAsEmptyAndMissingDisplayNeverReadsMainScreen() {
        val field = node(value = null)
        shadowOf(root).addChild(field)
        assertNull(property(read(field), "getText"))
        field.text = ""
        assertEquals("", property(read(field), "getText"))
        assertNull(property(read(field, displayId = 8), "getText"))
    }

    private fun node(editable: Boolean = true, value: String?): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            packageName = "test.input"
            isEditable = editable
            isEnabled = true
            isVisibleToUser = true
            text = value
            shadowOf(this).setAccessibilityWindowInfo(window)
        }

    private fun read(original: AccessibilityNodeInfo, displayId: Int = 7): Any {
        val method = AgentAccessibilityService::class.java.getDeclaredMethod(
            "readPhoneUseText", Int::class.javaPrimitiveType, String::class.java, AccessibilityNodeInfo::class.java,
        )
        method.isAccessible = true
        return requireNotNull(method.invoke(service, displayId, "test.input", original))
    }

    private fun property(value: Any, getter: String): Any? = value.javaClass.getDeclaredMethod(getter).run {
        isAccessible = true
        invoke(value)
    }

    @Implements(AccessibilityNodeInfo::class)
    class RefreshNodeShadow : ShadowAccessibilityNodeInfo() {
        @RealObject
        lateinit var realNode: AccessibilityNodeInfo

        @Implementation override fun refresh(): Boolean {
            return onRefresh?.invoke(realNode) ?: true
        }

        @Implementation override fun getWindowId(): Int = WINDOW_ID

        companion object {
            // Robolectric does not link AccessibilityNodeInfo.windowId to the window fixture.
            const val WINDOW_ID = 42
            var onRefresh: ((AccessibilityNodeInfo) -> Boolean)? = null
        }
    }
}
