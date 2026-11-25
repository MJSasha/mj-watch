package dev.mjwatch.playback

import kotlin.math.ceil

internal data class StartupLoadingProgress(val fraction: Float, val remainingSeconds: Int?)

internal class StartupLoadingEstimate {
    private var cacheBaseline: Long? = null
    private var fraction = 0f
    private var rateSample: Pair<Long, Float>? = null
    private var ratePerSecond = 0f
    private var lastAdvanceMs = 0L
    private var bufferStartFraction: Float? = null

    fun sample(
        nowMs: Long,
        receivedBytes: Long,
        cachedBytes: Long?,
        expectedBytes: Long,
        bufferedFraction: Float,
    ): StartupLoadingProgress? {
        if (cachedBytes != null) cacheBaseline = minOf(cacheBaseline ?: cachedBytes, cachedBytes)
        val cacheGrowth = cachedBytes?.let { it - (cacheBaseline ?: it) } ?: 0L
        if (bufferStartFraction == null && bufferedFraction > 0f) {
            bufferStartFraction = fraction
            rateSample = null
            ratePerSecond = 0f
        }

        // Cache and HTTP bytes overlap. They give an estimate until Media3 can measure playable time.
        val next = bufferStartFraction?.let { start ->
            start + (.99f - start) * bufferedFraction.coerceIn(0f, 1f)
        } ?: run {
            .8f * (maxOf(receivedBytes, cacheGrowth).toDouble() / expectedBytes).coerceIn(0.0, 1.0).toFloat()
        }
        if (next > fraction) {
            fraction = next
            lastAdvanceMs = nowMs
        }
        val previous = rateSample
        if (previous == null) {
            rateSample = nowMs to fraction
        } else if (nowMs - previous.first >= 1_000) {
            val rate = (fraction - previous.second) * 1_000 / (nowMs - previous.first)
            if (rate > 0f) ratePerSecond = if (ratePerSecond > 0f) (ratePerSecond + rate) / 2 else rate
            rateSample = nowMs to fraction
        }
        if (fraction <= 0f) return null
        val stalled = nowMs - lastAdvanceMs >= 2_500
        if (stalled) ratePerSecond = 0f
        val stageTarget = if (bufferStartFraction != null) .99f else .8f
        val seconds = if (ratePerSecond > 0f && fraction < stageTarget) {
            ceil((stageTarget - fraction) / ratePerSecond).toInt().takeIf { it in 1..180 }
        } else null
        return StartupLoadingProgress(fraction, seconds)
    }
}

internal fun startupByteTarget(fileBytes: Long, durationMs: Long, bufferMs: Int): Long {
    // Use a typical 8 Mbit/s stream when the file's average bitrate is unavailable.
    if (fileBytes <= 0 || durationMs <= 0) return bufferMs * 1_000L
    return (fileBytes.toDouble() * bufferMs / durationMs).toLong().coerceIn(256L * 1024, fileBytes.coerceAtLeast(256L * 1024))
}
