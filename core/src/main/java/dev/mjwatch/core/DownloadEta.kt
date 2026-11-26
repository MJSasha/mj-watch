package dev.mjwatch.core

import kotlin.math.ceil

/** Estimate only after two consecutive intervals with a positive transfer rate. */
class DownloadEta {
    private var previousTime: Long? = null
    private var previousBytes = 0L
    private var rate = 0.0
    private var samples = 0

    fun secondsLeft(total: Long, bytes: Long, elapsedRealtime: Long): Long? {
        val elapsed = elapsedRealtime - (previousTime ?: elapsedRealtime)
        val delta = bytes - previousBytes
        previousTime = elapsedRealtime
        previousBytes = bytes
        if (total <= bytes || bytes <= 0 || delta <= 0 || elapsed !in 500..5_000) {
            rate = 0.0
            samples = 0
            return null
        }
        val sample = delta * 1_000.0 / elapsed
        rate = if (samples == 0) sample else rate * .7 + sample * .3
        samples = (samples + 1).coerceAtMost(2)
        return if (samples == 2) ceil((total - bytes) / rate).toLong() else null
    }
}
