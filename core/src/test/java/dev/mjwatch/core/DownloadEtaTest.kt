package dev.mjwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadEtaTest {
    @Test fun waitsForEstablishedSpeedAndResetsWhenProgressStops() {
        val eta = DownloadEta()
        assertNull(eta.secondsLeft(10_000, 2_000, 1000))
        assertNull(eta.secondsLeft(10_000, 3_000, 2000))
        assertEquals(6L, eta.secondsLeft(10_000, 4_000, 3000))
        assertNull(eta.secondsLeft(10_000, 4_000, 4000))
        assertNull(eta.secondsLeft(10_000, 5_000, 5000))
        assertEquals(4L, eta.secondsLeft(10_000, 6_000, 6000))
    }

    @Test fun hidesEstimateWhenSizeIsUnknownOrTransferHasFinished() {
        val eta = DownloadEta()
        assertNull(eta.secondsLeft(0, 1_000, 1000))
        assertNull(eta.secondsLeft(0, 2_000, 2000))
        assertNull(eta.secondsLeft(3_000, 3_000, 3000))
    }
}
