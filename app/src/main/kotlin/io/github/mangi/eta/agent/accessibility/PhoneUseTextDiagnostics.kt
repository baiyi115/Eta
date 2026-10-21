package io.github.mangi.eta.agent.accessibility

/** Describe a mismatch's structure without leaking or accepting the actual text. Never normalizes input. */
internal object PhoneUseTextDiagnostics {
    private val reviewablePadding = setOf('\u200B', '\u2060', '\uFEFF')

    /** Eligibility only, never exact verification: these three markers have no visible glyph.
     * Joiners, variation selectors, bidi controls, whitespace and meaningful suffixes are not ignored.
     * If the request intentionally contains any marker, only exact readback can verify it.
     */
    fun mayVisuallyReview(actual: String, expected: String): Boolean =
        expected.isNotEmpty() && actual != expected && expected.none { it in reviewablePadding } &&
            actual.any { it in reviewablePadding } && actual.filterNot { it in reviewablePadding } == expected

    fun mismatch(actual: String, expected: String): String = buildString {
        if (actual.isEmpty()) append("原输入框仍报告为空，期望${expected.length}个UTF-16单位")
        else append("原输入框报告${actual.length}个UTF-16单位，与请求的${expected.length}个UTF-16单位不一致")
        val sample = actual.take(16_000)
        val controls = sample.codePoints().toArray().filter {
            Character.getType(it) in setOf(Character.FORMAT.toInt(), Character.CONTROL.toInt())
        }.groupingBy { it }.eachCount()
        if (controls.isNotEmpty()) {
            append("；格式/控制符：")
            append(controls.entries.take(8).joinToString(",") { (cp, count) -> "U+${cp.toString(16).uppercase().padStart(4, '0')}×$count" })
        }
        if (expected.isNotEmpty() && actual.length <= 16_000 && actual.length % expected.length == 0) {
            val copies = actual.length / expected.length
            if (copies in 2..16 && actual == expected.repeat(copies)) append("；回读结构为请求文本完整重复${copies}次")
        }
    }
}
