package io.github.mangi.eta.agent.phoneuse

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Independently releases a stalled screen even when no next model/tool call arrives. */
internal class PhoneUseIdleLease(private val onExpired: () -> Unit) : AutoCloseable {
    private val lock = Any()
    private var retired = false
    private var generation = 0L
    private var timer: ScheduledFuture<*>? = null
    fun renew() = synchronized(lock) {
        if (retired) return@synchronized
        timer?.cancel(false)
        val expected = ++generation
        timer = scheduler.schedule({
            val expire = synchronized(lock) {
                if (retired || generation != expected) false else { retired = true; true }
            }
            if (expire) runCatching(onExpired)
        }, PhoneUseSession.IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }
    override fun close() = synchronized(lock) { retired = true; ++generation; timer?.cancel(false); Unit }
    companion object {
        private val scheduler = ScheduledThreadPoolExecutor(1, { work ->
            Thread(work, "eta-phone-use-idle-watchdog").apply { isDaemon = true }
        }).apply { removeOnCancelPolicy = true }
    }
}
