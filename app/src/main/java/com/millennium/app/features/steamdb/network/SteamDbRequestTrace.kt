package com.millennium.app.features.steamdb.network

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Per-request timings; creating a trace never emits a framework log. */
internal class SteamDbRequestTrace(
    private val source: String,
    private val emit: (Int, String) -> Unit,
) {
    private val requestId = requestIds.incrementAndGet()
    private val started = SystemClock.elapsedRealtime()

    val elapsedMs: Long
        get() = SystemClock.elapsedRealtime() - started

    fun log(priority: Int, event: String, details: String) {
        emit(priority, "steamdb network $event source=$source requestId=$requestId $details")
    }

    fun <T> withNetworkTracing(block: () -> T): T {
        val previous = activeRequest.get()
        activeRequest.set(this)
        return try {
            block()
        } finally {
            if (previous == null) activeRequest.remove() else activeRequest.set(previous)
        }
    }

    fun <T> measure(stage: String, block: () -> T): T {
        log(Log.INFO, "http phase", "stage=$stage event=start totalMs=$elapsedMs")
        val stageStarted = SystemClock.elapsedRealtime()
        return try {
            val result = block()
            val duration = SystemClock.elapsedRealtime() - stageStarted
            val slow = duration >= SLOW_PHASE_MS
            log(
                if (slow) Log.WARN else Log.INFO,
                "http phase",
                "stage=$stage event=end elapsedMs=$duration totalMs=$elapsedMs slow=$slow",
            )
            result
        } catch (error: Exception) {
            log(
                Log.ERROR,
                "http phase",
                "stage=$stage event=failed elapsedMs=${SystemClock.elapsedRealtime() - stageStarted} " +
                        "totalMs=$elapsedMs error=${error.javaClass.simpleName}:${error.message}",
            )
            throw error
        }
    }

    companion object {
        private val activeRequest = ThreadLocal<SteamDbRequestTrace>()
        val current: SteamDbRequestTrace?
            get() = activeRequest.get()

        private val requestIds = AtomicLong()
        private const val SLOW_PHASE_MS = 2_000L
    }
}
