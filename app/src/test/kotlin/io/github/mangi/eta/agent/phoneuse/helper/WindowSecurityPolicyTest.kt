package io.github.mangi.eta.agent.phoneuse.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowSecurityPolicyTest {
    private fun window(display: Int, flags: String) = "  Window #0 Window{abc test.app}:\n    mDisplayId=$display\n    mAttrs={fl=$flags }\n"
    private fun dump(vararg windows: String) = "WINDOW MANAGER WINDOWS (dumpsys window windows)\n" + windows.joinToString("")
    @Test fun secureFlagIsDisplayLocalAndSupportsNumericOrSymbolicFlags() {
        for (flags in listOf("0x2000", "81812100", "SECURE HARDWARE_ACCELERATED")) {
            assertEquals("SECURE", WindowSecurityPolicy.inspect(dump(window(8, flags)), 8, true))
            assertEquals("SAFE", WindowSecurityPolicy.inspect(dump(window(0, flags), window(8, "81810100")), 8, true))
        }
        assertEquals("SAFE", WindowSecurityPolicy.inspect(dump(window(8, "LAYOUT_IN_SCREEN HARDWARE_ACCELERATED")), 8, true))
    }
    @Test fun unknownFormatsAndMissingForegroundWindowFailClosed() {
        assertEquals("UNKNOWN", WindowSecurityPolicy.inspect("permission denied", 8, false))
        assertEquals("UNKNOWN", WindowSecurityPolicy.inspect(dump(window(8, "0")).replace('\n', '|'), 8, false))
        assertEquals("UNKNOWN", WindowSecurityPolicy.inspect(dump(window(8, "0")).replace("mDisplayId=8", "mDisplayId=999999999999999999999"), 8, true))
        assertEquals("UNKNOWN", WindowSecurityPolicy.inspect(dump(window(8, "?")), 8, true))
        assertEquals("UNKNOWN", WindowSecurityPolicy.inspect(dump(window(0, "0")), 8, true))
        assertEquals("UNKNOWN", WindowSecurityPolicy.inspect(dump(window(8, "0")), 0, true))
        assertEquals("SAFE", WindowSecurityPolicy.inspect(dump(), 8, false))
    }
}
