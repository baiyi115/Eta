package io.github.mangi.eta.agent.model

/**
 * MCP 结果正文只在当前运行中展示。持久化投影只保留由客户端生成的稳定头部，
 * 去掉成功预览和失败原因，避免远端文本绕过启发式脱敏进入数据库。
 */
internal object McpResultSummary {
    fun persistentHeader(summary: String, failed: Boolean): String {
        val firstLine = summary.lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
        if (firstLine.isBlank()) return ""

        val fields = firstLine.split(FIELD_SEPARATOR)
        val failureIndex = if (failed) {
            fields.take(MAX_MCP_IDENTITY_FIELDS).indexOfFirst { it == FAILURE_LABEL }
        } else {
            -1
        }
        val projected = if (failureIndex < 0) {
            fields
        } else {
            fields.take(failureIndex + 1) +
                fields.drop(failureIndex + 1).filter(::isSafeFailureSuffix)
        }
        return projected.joinToString(FIELD_SEPARATOR).take(MAX_PERSISTED_HEADER_CHARS)
    }

    private fun isSafeFailureSuffix(value: String): Boolean =
        value == TRUNCATED_LABEL ||
            SAFE_ERROR_CODE.matches(value) ||
            SAFE_IMAGE_COUNT.matches(value) ||
            SAFE_OMITTED_COUNT.matches(value)

    private const val FIELD_SEPARATOR = " · "
    private const val FAILURE_LABEL = "失败"
    private const val TRUNCATED_LABEL = "已截断"
    private const val MAX_MCP_IDENTITY_FIELDS = 3
    private const val MAX_PERSISTED_HEADER_CHARS = 256
    private val SAFE_ERROR_CODE = Regex("code=[A-Z][A-Z0-9_]{0,79}")
    private val SAFE_IMAGE_COUNT = Regex("\\d{1,9} 张图片")
    private val SAFE_OMITTED_COUNT = Regex("另有 \\d{1,9} 项未显示")
}
