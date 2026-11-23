package dev.mjwatch.playback

import dev.mjwatch.core.SegmentKind
import dev.mjwatch.core.SkipSegment
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
    val skip = SegmentSkipState()
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
        if (prompt != "next") skip.dismiss()
        prompt = null
    }
    fun discontinuity() { previousPosition = null; skip.discontinuity() }
    fun reset() {
        preview = null; prompt = null; nextElapsed = 0; previousPosition = null
        nextDismissed = false; skip.reset(); advancing = false
    }
    fun update(
        position: Long, duration: Long, playing: Boolean, episode: PlaybackEpisode?, hasNext: Boolean,
        blocked: Boolean, autoNext: Boolean, segments: List<SkipSegment> = episode?.legacySegments().orEmpty(),
        autoSkip: AutoSkip = AutoSkip(AutoSkipStart.OFF), now: Long = 0, markedTail: Boolean = false,
        enabled: (SegmentKind) -> Boolean = { true },
    ): Boolean {
        if (duration <= 0 || advancing) { prompt = null; previousPosition = null; skip.reset(); return false }
        skip.update(segments, position, duration, playing, blocked, autoSkip, now, enabled)
        val inTail = !markedTail && position >= nextThreshold(episode?.endingStartMs, duration)
        if (!inTail) { nextDismissed = false; nextElapsed = 0 }
        val nextPrompt = when {
            skip.offer != null -> skip.offer?.segment?.kind?.let { if (it == SegmentKind.INTRO) "opening" else it.apiKey }
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
