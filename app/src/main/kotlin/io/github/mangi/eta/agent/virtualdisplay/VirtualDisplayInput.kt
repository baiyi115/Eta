/*
 * Adapted from MiAIPlus (astoHu1), commit dc9107ab65fc5db2968e2f854a86735cff86a196,
 * via the fork's former Phone use helper (DisplayHelper.java).
 * SPDX-License-Identifier: Apache-2.0
 * Modifications: Eta namespace, display-targeted input for the shared virtual display, no
 * isolation verification. See assets/third_party/miaiplus.
 */
package io.github.mangi.eta.agent.virtualdisplay

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * 特权进程内的定向输入注入。
 *
 * 只在 Root 桥进程（UID 0 → 1000）里使用：事件自带 displayId，因此按键与触摸按身份路由到后台虚拟屏，
 * 而不是落到用户当前注视的窗口；触摸事件同时携带平台的 `FLAG_NO_FOCUS_CHANGE`，避免 InputDispatcher
 * 因屏幕外按下而通知 WindowManager 改变焦点。
 *
 * 与旧的 Phone use helper 不同，这里不校验 flag 是否生效：取不到 `FLAG_NO_FOCUS_CHANGE` 时按 0 注入，
 * 输入服务不可用时返回失败，由调用方回退无障碍手势。
 */
internal object VirtualDisplayInput {

    @Volatile
    private var configured = false
    private var inputService: Any? = null
    private var injectInputEvent: Method? = null
    private var motionSetDisplayId: Method? = null
    private var keySetDisplayId: Method? = null
    private var motionNoFocusChangeFlag: Int = 0
    private var failure: String = ""

    fun configure(context: Context) {
        if (configured) return
        synchronized(this) {
            if (configured) return
            configured = true
            runCatching {
                val service = context.getSystemService("input")
                    ?: error("input service unavailable")
                val inject = service.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                val motion = MotionEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                val key = KeyEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                inject.isAccessible = true
                motion.isAccessible = true
                key.isAccessible = true
                inputService = service
                injectInputEvent = inject
                motionSetDisplayId = motion
                keySetDisplayId = key
                motionNoFocusChangeFlag = runCatching {
                    MotionEvent::class.java.getField("FLAG_NO_FOCUS_CHANGE").getInt(null)
                }.getOrDefault(0)
            }.onFailure {
                failure = "${it.javaClass.simpleName}: ${it.message}"
            }
        }
    }

    fun tap(displayId: Int, x: Float, y: Float): Boolean = hold(displayId, x, y, 60L)

    fun longPress(displayId: Int, x: Float, y: Float, durationMs: Long): Boolean =
        hold(displayId, x, y, durationMs.coerceIn(300L, 5_000L))

    fun key(displayId: Int, keyCode: Int): Boolean {
        val inject = injectInputEvent ?: throw IllegalStateException("input injection unavailable: $failure")
        val service = inputService ?: throw IllegalStateException("input injection unavailable: $failure")
        val setDisplay = keySetDisplayId ?: throw IllegalStateException("display-targeted key injection unavailable")
        val now = SystemClock.uptimeMillis()
        var delivered = true
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(now, now, action, keyCode, 0)
            event.source = InputDevice.SOURCE_KEYBOARD
            setDisplay.invoke(event, displayId)
            delivered = delivered && inject.invoke(service, event, INJECT_MODE_WAIT_FOR_FINISH) == true
        }
        return delivered
    }

    fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        requireInjection()
        val duration = durationMs.coerceIn(60L, 4_000L)
        val downTime = SystemClock.uptimeMillis()
        var down = false
        var accepted = true
        var x = x1
        var y = y1
        try {
            for (step in 0..SWIPE_STEPS) {
                waitUntil(downTime + duration * step / SWIPE_STEPS)
                val fraction = step.toFloat() / SWIPE_STEPS
                x = x1 + (x2 - x1) * fraction
                y = y1 + (y2 - y1) * fraction
                val action = when (step) {
                    0 -> MotionEvent.ACTION_DOWN
                    SWIPE_STEPS -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                val delivered = injectMotion(displayId, action, x, y, downTime)
                accepted = accepted && delivered
                if (action == MotionEvent.ACTION_DOWN) {
                    down = delivered
                    if (!down) break
                }
                if (action == MotionEvent.ACTION_UP && !delivered) {
                    runCatching { injectMotion(displayId, MotionEvent.ACTION_CANCEL, x, y, downTime) }
                }
            }
        } catch (throwable: Throwable) {
            if (down) runCatching { injectMotion(displayId, MotionEvent.ACTION_CANCEL, x, y, downTime) }
            throw throwable
        }
        return accepted
    }

    private fun hold(displayId: Int, x: Float, y: Float, durationMs: Long): Boolean {
        requireInjection()
        val downTime = SystemClock.uptimeMillis()
        if (!injectMotion(displayId, MotionEvent.ACTION_DOWN, x, y, downTime)) return false
        return try {
            waitUntil(downTime + durationMs)
            val up = injectMotion(displayId, MotionEvent.ACTION_UP, x, y, downTime)
            if (!up) runCatching { injectMotion(displayId, MotionEvent.ACTION_CANCEL, x, y, downTime) }
            up
        } catch (throwable: Throwable) {
            runCatching { injectMotion(displayId, MotionEvent.ACTION_CANCEL, x, y, downTime) }
            throw throwable
        }
    }

    private fun injectMotion(displayId: Int, action: Int, x: Float, y: Float, downTime: Long): Boolean {
        val inject = injectInputEvent ?: throw IllegalStateException("input injection unavailable: $failure")
        val service = inputService ?: throw IllegalStateException("input injection unavailable: $failure")
        val setDisplay = motionSetDisplayId ?: throw IllegalStateException("display-targeted touch injection unavailable")
        val pointer = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            1,
            arrayOf(pointer),
            arrayOf(coordinates),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            motionNoFocusChangeFlag,
        )
        return try {
            setDisplay.invoke(event, displayId)
            inject.invoke(service, event, INJECT_MODE_WAIT_FOR_FINISH) == true
        } finally {
            event.recycle()
        }
    }

    private fun requireInjection() {
        if (injectInputEvent == null) throw IllegalStateException("input injection unavailable: $failure")
    }

    private fun waitUntil(targetUptimeMs: Long) {
        var remaining = targetUptimeMs - SystemClock.uptimeMillis()
        while (remaining > 0) {
            try {
                Thread.sleep(remaining)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
            remaining = targetUptimeMs - SystemClock.uptimeMillis()
        }
    }

    private const val INJECT_MODE_WAIT_FOR_FINISH = 2
    private const val SWIPE_STEPS = 12
}
