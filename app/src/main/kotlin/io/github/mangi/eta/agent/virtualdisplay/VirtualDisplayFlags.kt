/*
 * Adapted from MiAIPlus (astoHu1), commit dc9107ab65fc5db2968e2f854a86735cff86a196,
 * via the fork's former Phone use helper (DisplayHelper.java).
 * SPDX-License-Identifier: Apache-2.0
 * Modifications: Eta namespace, runtime flag resolution for the shared virtual display, no
 * isolation verification. See assets/third_party/miaiplus.
 */
package io.github.mangi.eta.agent.virtualdisplay

import android.hardware.display.DisplayManager
import io.github.mangi.eta.core.AndroidAgentLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 虚拟屏标志位解析。
 *
 * 不同 ROM 的 `VIRTUAL_DISPLAY_FLAG_*` 取值并不一致（例如 `DEVICE_DISPLAY_GROUP` 与
 * `STEAL_TOP_FOCUS_DISABLED` 都曾占用 `1 shl 15`），照抄源码常量可能把另一个标志位当成焦点隔离。
 * 因此每个标志位都以运行中的 [DisplayManager] 反射值为准，只有在 ROM 完全没有定义该名字时才使用
 * AOSP 字面量；焦点相关标志位缺失时宁可不加，也不猜测。
 *
 * 这与本地 Phone use helper 的成熟做法一致，但不做任何回读校验：平台忽略某个标志位时只记录日志，
 * 会话继续。
 */
internal object VirtualDisplayFlags {

    private const val PUBLIC = "VIRTUAL_DISPLAY_FLAG_PUBLIC"
    private const val PRESENTATION = "VIRTUAL_DISPLAY_FLAG_PRESENTATION"
    private const val OWN_CONTENT_ONLY = "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY"
    private const val SUPPORTS_TOUCH = "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH"
    private const val ROTATES_WITH_CONTENT = "VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT"
    private const val DESTROY_CONTENT_ON_REMOVAL = "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL"
    private const val TRUSTED = "VIRTUAL_DISPLAY_FLAG_TRUSTED"
    private const val OWN_DISPLAY_GROUP = "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP"
    private const val ALWAYS_UNLOCKED = "VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED"
    private const val TOUCH_FEEDBACK_DISABLED = "VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED"
    private const val DEVICE_DISPLAY_GROUP = "VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP"
    private const val OWN_FOCUS = "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS"
    private const val STEAL_TOP_FOCUS_DISABLED = "VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED"

    /** AOSP 字面量仅覆盖基础标志位；焦点标志位没有任何兜底值。 */
    private val fallbacks = linkedMapOf(
        PUBLIC to (1 shl 0),
        PRESENTATION to (1 shl 1),
        OWN_CONTENT_ONLY to (1 shl 3),
        SUPPORTS_TOUCH to (1 shl 6),
        ROTATES_WITH_CONTENT to (1 shl 7),
        DESTROY_CONTENT_ON_REMOVAL to (1 shl 8),
        TRUSTED to (1 shl 10),
        OWN_DISPLAY_GROUP to (1 shl 11),
        ALWAYS_UNLOCKED to (1 shl 12),
        TOUCH_FEEDBACK_DISABLED to (1 shl 13),
        DEVICE_DISPLAY_GROUP to (1 shl 15),
    )

    private val baseNames = listOf(
        PUBLIC, PRESENTATION, OWN_CONTENT_ONLY, SUPPORTS_TOUCH, ROTATES_WITH_CONTENT,
        DESTROY_CONTENT_ON_REMOVAL, TRUSTED, OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED,
        TOUCH_FEEDBACK_DISABLED, DEVICE_DISPLAY_GROUP,
    )

    @Volatile
    private var cached: Int? = null

    /** 受信任后台交互画布所需的完整标志组合，按当前 ROM 的真实取值拼装。 */
    fun interactive(): Int = cached ?: synchronized(this) {
        cached ?: run {
            val flags = compose(definedFlags())
            AndroidAgentLogger.info("Virtual display flags resolved: 0x${Integer.toHexString(flags)}")
            flags
        }.also { cached = it }
    }

    /** 纯组合逻辑，便于单元测试；不访问任何 Android 隐藏字段。 */
    fun compose(defined: Map<String, Int>): Int {
        var flags = 0
        for (name in baseNames) {
            val value = defined[name] ?: fallbacks[name] ?: continue
            flags = flags or value
        }
        // 独立焦点与“不抢占主屏顶部焦点”成对使用；ROM 未定义时保持缺席，而不是套用可能冲突的常量。
        defined[OWN_FOCUS]?.let { flags = flags or it }
        defined[STEAL_TOP_FOCUS_DISABLED]?.let { flags = flags or it }
        return flags
    }

    private fun definedFlags(): Map<String, Int> {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/display/") }
        val result = linkedMapOf<String, Int>()
        val fields = runCatching { DisplayManager::class.java.fields }.getOrElse {
            AndroidAgentLogger.debug {
                "Virtual display flag reflection unavailable: type=${it.javaClass.simpleName}"
            }
            return emptyMap()
        }
        for (field in fields) {
            if (!field.name.startsWith("VIRTUAL_DISPLAY_FLAG_")) continue
            runCatching {
                field.isAccessible = true
                result[field.name] = field.getInt(null)
            }
        }
        return result
    }
}
