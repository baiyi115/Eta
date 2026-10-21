package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.*
import org.junit.Test

class PhoneUseTextDiagnosticsTest {
    @Test fun distinguishesRepeatedTextWithoutEchoingActualOrTreatingItAsAReplacement() {
        val message = PhoneUseTextDiagnostics.mismatch("北京周末北京周末", "北京周末")
        assertTrue(message.contains("8个UTF-16单位"))
        assertTrue(message.contains("完整重复2次"))
        assertFalse(message.contains("北京周末"))
    }
    @Test fun reportsInvisibleFormattingByCodePointButDoesNotDropIt() {
        val actual = "北\u200B京\u200B周\u200B末\u200B"
        val message = PhoneUseTextDiagnostics.mismatch(actual, "北京周末")
        assertTrue(message.contains("U+200B×4"))
        assertFalse(message.contains("完整重复"))
        assertEquals(8, actual.length)
    }
    @Test fun eligibilityNeverAcceptsVisibleSuffixesDuplicatesBidiJoinersOrIntentionalMarkers() {
        assertTrue(PhoneUseTextDiagnostics.mayVisuallyReview("北\u200B京\u2060周\uFEFF末", "北京周末"))
        for ((actual, expected) in listOf(
            "北京周末北京周末" to "北京周末", "北京周末额外" to "北京周末", "\u202E北京周末" to "北京周末",
            "北\u200D京周末" to "北京周末", "北京周末 " to "北京周末", "\u200B" to "",
            "北\u200B京\u200B" to "北\u200B京",
        )) assertFalse(PhoneUseTextDiagnostics.mayVisuallyReview(actual, expected))
    }
    @Test fun clearingAndSameLengthDifferentTextCannotBecomeSubstringSuccess() {
        assertFalse(PhoneUseTextDiagnostics.mismatch("额外文本", "").contains("完整重复"))
        assertFalse(PhoneUseTextDiagnostics.mismatch("北京其他", "北京周末").contains("完整重复"))
    }
}
