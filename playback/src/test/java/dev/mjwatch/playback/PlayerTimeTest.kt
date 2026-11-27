package dev.mjwatch.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTimeTest {
    @Test fun hourBoundaryWithUnknownDuration() {
        assertEquals("59:59", playerTime(3_599_999))
        assertEquals("1:00:00", playerTime(3_600_000))
        assertEquals("1:00:00", playerTime(3_600_000, Long.MIN_VALUE))
    }

    @Test fun longTimelineUsesSameFormatForPositionAndDuration() {
        val duration = 7_567_000L
        assertEquals("0:09:05", playerTime(545_000, duration))
        assertEquals("2:01:04", playerTime(7_264_000, duration))
        assertEquals("2:06:07", playerTime(duration))
    }

    @Test fun shortTimelineKeepsExistingFormat() {
        assertEquals("0:07", playerTime(7_000, 90_000))
        assertEquals("1:30", playerTime(90_000, 7_000))
    }
}
