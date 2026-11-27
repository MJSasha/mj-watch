package dev.mjwatch.core

import kotlin.math.ceil

private const val MIN_SAMPLE_MS = 500L
private const val STALLED_AFTER_MS = 8_000L

/** Estimate after two progress samples and keep the last rate through short gaps. */
class DownloadEta {
    private var previousTime: Long? = null
    private var previousBytes = 0L
    private var rate = 0.0
    private var samples = 0

    fun secondsLeft(total: Long, bytes: Long, elapsedRealtime: Long): Long? {
        val elapsed = elapsedRealtime - (previousTime ?: elapsedRealtime)
        val active = total > bytes && bytes > 0
        if (!active || previousTime == null || bytes < previousBytes || elapsed < 0 || elapsed >= STALLED_AFTER_MS) {
            previousTime = elapsedRealtime.takeIf { active }
            previousBytes = bytes
            rate = 0.0
            samples = 0
            return null
        }
        if (bytes > previousBytes && elapsed >= MIN_SAMPLE_MS) {
            val sample = (bytes - previousBytes) * 1_000.0 / elapsed
            rate = if (samples == 0) sample else rate * .7 + sample * .3
            samples = (samples + 1).coerceAtMost(2)
            previousTime = elapsedRealtime
            previousBytes = bytes
        }
        return if (samples == 2) ceil((total - bytes) / rate).toLong() else null
    }
}
