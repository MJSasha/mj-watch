package dev.mjwatch.playback

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player

internal enum class SegmentKind(val apiKey: String, val label: String, val action: String) {
    INTRO("intro", "Опенинг", "Пропустить опенинг"),
    RECAP("recap", "В предыдущих сериях", "Пропустить «В предыдущих сериях»"),
    OUTRO("outro", "Титры", "Пропустить титры"),
    PREVIEW("preview", "Анонс следующей серии", "Пропустить анонс"),
}

internal const val AUTO_SKIP = "auto-skip"
private const val SKIP_DELAY_MS = 5_000L

internal fun SegmentKind.preferenceKey() = "skip-segment-$apiKey"
internal fun SharedPreferences.segmentEnabled(kind: SegmentKind) = getBoolean(kind.preferenceKey(), true)

internal data class SkipSegment(val kind: SegmentKind, val startMs: Long, val endMs: Long) {
    fun isValidFor(duration: Long) = startMs >= 0 && endMs > startMs && endMs <= duration

    fun contains(position: Long, duration: Long) = isValidFor(duration) && position in startMs until endMs
}

internal fun PlaybackEpisode.legacySegments(): List<SkipSegment> = buildList {
    if (openingStartMs != null && openingEndMs != null) add(SkipSegment(SegmentKind.INTRO, openingStartMs, openingEndMs))
    if (endingStartMs != null && endingEndMs != null) add(SkipSegment(SegmentKind.OUTRO, endingStartMs, endingEndMs))
}

internal data class SkipOffer(val segment: SkipSegment, val remainingMs: Long?) {
    val label: String get() = remainingMs?.let { "${segment.kind.action} · ${(it + 999) / 1000}" } ?: segment.kind.action
}

/** Counts real playback time and keeps a dismissed segment hidden until the viewer leaves it. */
internal class SegmentSkipState {
    var offer by mutableStateOf<SkipOffer?>(null)
        private set
    private var current: SkipSegment? = null
    private var dismissed = false
    private var elapsed = 0L
    private var previousTick: Long? = null

    fun update(
        segments: List<SkipSegment>, position: Long, duration: Long,
        playing: Boolean, blocked: Boolean, automatic: Boolean, now: Long,
        enabled: (SegmentKind) -> Boolean = { true },
    ) {
        val next = segments.firstOrNull { it.contains(position, duration) && enabled(it.kind) }
        if (next != current) {
            current = next
            dismissed = false
            discontinuity()
        }
        if (!automatic) discontinuity()
        if (next == null || dismissed) {
            offer = null
            return
        }
        if (automatic && playing && !blocked) {
            previousTick?.let { elapsed += (now - it).coerceIn(0, 500) }
            previousTick = now
        } else {
            previousTick = null
        }
        offer = if (blocked) null else SkipOffer(next, if (automatic) (SKIP_DELAY_MS - elapsed).coerceAtLeast(0) else null)
    }

    fun takeTarget(position: Long, duration: Long): Long? {
        val segment = offer?.segment?.takeIf { it.contains(position, duration) } ?: return null
        dismiss()
        return segment.endMs
    }

    fun dismiss() {
        dismissed = true
        offer = null
        discontinuity()
    }

    fun discontinuity() {
        elapsed = 0
        previousTick = null
    }

    fun reset() {
        dismiss()
        current = null
    }
}

internal fun SegmentSkipState.seek(player: Player) {
    if (player.isCurrentMediaItemSeekable) takeTarget(player.currentPosition, player.duration)?.let(player::seekTo)
}
