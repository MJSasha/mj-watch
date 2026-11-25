package dev.mjwatch.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupLoadingProgressTest {
    @Test fun estimatesFromNewCacheBytesAndHidesTimeWhenGrowthStops() {
        val estimate = StartupLoadingEstimate()
        assertNull(estimate.sample(0, 0, 8_000_000, 2_000_000, 0f))

        val loading = requireNotNull(estimate.sample(1_000, 0, 8_500_000, 2_000_000, 0f))
        assertEquals(.2f, loading.fraction, .001f)
        assertEquals(3, loading.remainingSeconds)

        val stalled = requireNotNull(estimate.sample(4_000, 0, 8_500_000, 2_000_000, 0f))
        assertEquals(loading.fraction, stalled.fraction, 0f)
        assertNull(stalled.remainingSeconds)
    }

    @Test fun usesHttpBytesWithoutServerStatsAndKeepsProgressWhenBufferBecomesMeasurable() {
        val estimate = StartupLoadingEstimate()
        assertNull(estimate.sample(0, 0, null, 2_000_000, 0f))
        val downloaded = requireNotNull(estimate.sample(1_000, 1_000_000, null, 2_000_000, 0f))
        assertEquals(.4f, downloaded.fraction, .001f)

        val playbackBuffer = requireNotNull(estimate.sample(2_000, 1_000_000, null, 2_000_000, .5f))
        assertEquals(.695f, playbackBuffer.fraction, .001f)
        assertNull(playbackBuffer.remainingSeconds)
        assertEquals(.99f, requireNotNull(estimate.sample(3_000, 3_000_000, null, 2_000_000, 1f)).fraction, .001f)
    }
}
