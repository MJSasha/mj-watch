package dev.mjwatch.playback

import androidx.compose.runtime.*

/** Remote interaction state, independent of the video engine and clock. All times are milliseconds. */
internal class TvPlaybackState {
    var preview: Long? by mutableStateOf(null)
        private set
    var lastInteraction = 0L
        private set
    var prompt: String? by mutableStateOf(null)
        private set
    var nextElapsed by mutableLongStateOf(0L)
        private set
    private var previousPosition: Long? = null
    private var nextDismissed = false
    private var openingDismissed = false
    private var advancing = false

    fun interact(now: Long) { lastInteraction = now }
    fun shouldHide(now: Long, modal: Boolean) = !modal && preview == null && now - lastInteraction >= 6_000
    fun previewSeek(position: Long, duration: Long, direction: Int, repeats: Int, step: Long) {
        val multiplier = when { repeats >= 20 -> 6; repeats >= 10 -> 3; else -> 1 }
        preview = ((preview ?: position) + direction * step * multiplier).coerceIn(0, duration.coerceAtLeast(0))
    }
    fun cancelSeek() { preview = null }
    fun confirmSeek(): Long? = preview.also { preview = null }
    fun dismissPrompt() {
        if (prompt == "next") nextDismissed = true
        if (prompt == "opening") openingDismissed = true
        prompt = null
    }
    fun discontinuity() { previousPosition = null }
    fun reset() {
        preview = null; prompt = null; nextElapsed = 0; previousPosition = null
        nextDismissed = false; openingDismissed = false; advancing = false
    }
    fun update(position: Long, duration: Long, playing: Boolean, episode: PlaybackEpisode?, hasNext: Boolean, blocked: Boolean, autoNext: Boolean): Boolean {
        if (duration <= 0 || advancing) { prompt = null; previousPosition = null; return false }
        val opening = episode?.openingTarget(position, duration)
        val inTail = position >= nextThreshold(episode?.endingStartMs, duration)
        if (opening == null) openingDismissed = false
        if (!inTail) { nextDismissed = false; nextElapsed = 0 }
        val nextPrompt = when {
            opening != null && !openingDismissed -> "opening"
            inTail && hasNext && !nextDismissed -> "next"
            else -> null
        }
        if (nextPrompt != "next") nextElapsed = 0
        val delta = previousPosition?.let { (position - it).coerceAtLeast(0) } ?: 0
        // Seeking never counts as time watched; the Player discontinuity listener clears the sample.
        if (!blocked && playing && prompt == "next" && nextPrompt == "next") nextElapsed += delta
        previousPosition = if (blocked || !playing) null else position
        prompt = nextPrompt
        if (!blocked && autoNext && nextPrompt == "next" && nextElapsed >= 10_000) {
            advancing = true; prompt = null; return true
        }
        return false
    }
}

internal fun nextThreshold(endingStart: Long?, duration: Long): Long =
    endingStart?.takeIf { it > duration * .7 && it < duration && duration - it <= 180_000 }
        ?: (duration - 30_000).coerceAtLeast(0)

internal fun PlaybackEpisode.openingTarget(position: Long, duration: Long): Long? =
    markerTarget(openingStartMs, openingEndMs, position, duration)

internal fun markerTarget(start: Long?, end: Long?, position: Long, duration: Long): Long? =
    end?.takeIf { start != null && start >= 0 && it > start && it <= duration && position >= start && position < it }
