package dev.mjwatch.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackStatusTest {
    @Test fun onlyBriefRebufferingUsesCompactFeedback() {
        assertFalse(bufferingStatus(firstFrame=false).compact)
        assertTrue(bufferingStatus(firstFrame=true).compact)
        assertTrue(bufferingStatus(firstFrame=true,elapsedMs=29_999).compact)
        val delayed=bufferingStatus(firstFrame=true,elapsedMs=30_000)
        assertFalse(delayed.compact)
        assertTrue(delayed.loading)
        assertTrue(delayed.message.isNotBlank())
        assertFalse(bufferingStatus(firstFrame=false,elapsedMs=30_000).compact)
        assertFalse(PlaybackStatus("Ошибка",retry=true).compact)
    }
}
