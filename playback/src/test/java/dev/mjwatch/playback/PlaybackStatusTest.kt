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
    @Test fun codecInitializationAudioOutputAndContainerErrorsAreNotAllUnsupportedFormats() {
        assertEquals("Не удалось запустить декодер",playbackFailureStatus(4001,"video/hevc").title)
        val audio=playbackFailureStatus(4005,"audio/vnd.dts","Каналов: 6")
        assertEquals("Аудиодорожка не поддерживается",audio.title)
        assertTrue(audio.message.contains("4005 · audio/vnd.dts · Каналов: 6"))
        assertFalse(audio.retry)
        assertEquals("Видеодорожка не поддерживается",playbackFailureStatus(4004,"video/hevc","3840×2160").title)
        assertEquals("Не удалось вывести звук",playbackFailureStatus(5001,"audio/ac3").title)
        assertEquals("Ошибка декодирования",playbackFailureStatus(4003).title)
        assertEquals("Не удалось прочитать контейнер",playbackFailureStatus(3003).title)
        assertTrue(playbackFailureStatus(2002).retry)
        assertTrue(playbackFailureStatus(null).retry)
    }
}
