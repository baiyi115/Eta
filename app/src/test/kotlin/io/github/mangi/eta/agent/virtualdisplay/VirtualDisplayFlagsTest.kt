package io.github.mangi.eta.agent.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayFlagsTest {

    @Test
    fun focusFlagsComeFromTheRunningPlatformValues() {
        val defined = mapOf(
            "VIRTUAL_DISPLAY_FLAG_PUBLIC" to (1 shl 0),
            "VIRTUAL_DISPLAY_FLAG_TRUSTED" to (1 shl 10),
            "VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP" to (1 shl 15),
            "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS" to (1 shl 14),
            "VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED" to (1 shl 16),
        )

        val flags = VirtualDisplayFlags.compose(defined)

        assertTrue(flags and (1 shl 14) != 0)
        assertTrue(flags and (1 shl 16) != 0)
        // 不再把 1<<15 当成 STEAL_TOP_FOCUS_DISABLED；它在本 ROM 上是 DEVICE_DISPLAY_GROUP。
        assertTrue(flags and (1 shl 15) != 0)
        assertEquals(1 shl 14, flags and (1 shl 14))
    }

    @Test
    fun missingFocusFlagsStayAbsentInsteadOfBorrowingConstants() {
        val defined = mapOf(
            "VIRTUAL_DISPLAY_FLAG_TRUSTED" to (1 shl 10),
            "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP" to (1 shl 11),
        )

        val flags = VirtualDisplayFlags.compose(defined)

        // 基础标志位用 AOSP 兜底，但焦点标志位没有定义时绝不补一个猜测值。
        assertTrue(flags and (1 shl 10) != 0)
        assertTrue(flags and (1 shl 11) != 0)
        assertEquals(0, flags and (1 shl 14))
        assertEquals(0, flags and (1 shl 16))
    }

    @Test
    fun platformWithoutAnyDefinedFlagsFallsBackToBaseAospValues() {
        val flags = VirtualDisplayFlags.compose(emptyMap())

        assertEquals(
            (1 shl 0) or (1 shl 1) or (1 shl 3) or (1 shl 6) or (1 shl 7) or (1 shl 8) or
                (1 shl 10) or (1 shl 11) or (1 shl 12) or (1 shl 13) or (1 shl 15),
            flags,
        )
    }
}
