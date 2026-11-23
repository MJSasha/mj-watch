package dev.mjwatch.playback

import dev.mjwatch.core.SegmentKind
import dev.mjwatch.core.SkipSegment
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

internal const val AUTO_SKIP = "auto-skip"
internal const val AUTO_SKIP_START = "auto-skip-start"
internal const val AUTO_SKIP_SECONDS = "auto-skip-seconds"
internal val AUTO_SKIP_DURATIONS = listOf(5, 10, 15, 0)

internal enum class AutoSkipStart(val label: String, val leadMs: Long = 0) {
    EARLY("За 5 секунд до метки", 5_000),
    AT_MARKER("В момент метки"),
    OFF("Отключить"),
}

internal data class AutoSkip(val start: AutoSkipStart, val seconds: Int = 5) {
    val delayMs: Long? get() = if (start == AutoSkipStart.OFF) null else seconds * 1000L
}

internal fun SharedPreferences.autoSkip(): AutoSkip {
    val savedName = getString(AUTO_SKIP_START, null)
    val savedStart = AutoSkipStart.entries.firstOrNull { it.name == savedName }
    val start = savedStart ?: if (getBoolean(AUTO_SKIP, true)) AutoSkipStart.EARLY else AutoSkipStart.OFF
    val seconds = getInt(AUTO_SKIP_SECONDS, 5).takeIf { it in AUTO_SKIP_DURATIONS } ?: 5
    return AutoSkip(start, seconds)
}

internal fun autoSkipDurationLabel(seconds: Int) = if (seconds == 0) "Нет, пропускать сразу" else "$seconds с"

internal fun SegmentKind.preferenceKey() = "skip-segment-$apiKey"
internal fun SharedPreferences.segmentEnabled(kind: SegmentKind) = getBoolean(kind.preferenceKey(), true)

internal fun PlaybackEpisode.legacySegments(): List<SkipSegment> = buildList {
    if (openingStartMs != null && openingEndMs != null) add(SkipSegment(SegmentKind.INTRO, openingStartMs, openingEndMs))
    if (endingStartMs != null && endingEndMs != null) add(SkipSegment(SegmentKind.OUTRO, endingStartMs, endingEndMs))
}

internal data class SkipOffer(val segment: SkipSegment, val remainingMs: Long?)

@Composable
internal fun SkipOverlay(
    offer: SkipOffer, modifier: Modifier, skipModifier: Modifier, cancelModifier: Modifier = Modifier,
    onSkip: () -> Unit, onCancel: () -> Unit,
    tv: Boolean = false,
) {
    Row(
        modifier.background(Color(0xAA12151B), CircleShape).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipIconButton(Icons.Outlined.SkipNext, offer.segment.kind.action, skipModifier, tv, onSkip)
        offer.remainingMs?.takeIf { it > 0 }?.let {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("${(it + 999) / 1000}")
            }
        }
        SkipIconButton(Icons.Outlined.Close, "Отменить пропуск", cancelModifier, tv, onCancel)
    }
}

@Composable
private fun SkipIconButton(icon: ImageVector, label: String, modifier: Modifier, tv: Boolean, onClick: () -> Unit) {
    if (tv) {
        val interaction = remember { MutableInteractionSource() }
        OutlinedIconButton(onClick = onClick, modifier = modifier, shape = CircleShape,
            interactionSource = interaction, border = tvPromptBorder(interaction, icon = true)) {
            Icon(icon, label)
        }
    } else {
        IconButton(onClick = onClick, modifier = modifier) { Icon(icon, label) }
    }
}

/** Counts real playback time and keeps a dismissed segment hidden until the viewer leaves it. */
internal class SegmentSkipState {
    var offer by mutableStateOf<SkipOffer?>(null)
        private set
    private var current: SkipSegment? = null
    private var dismissed = false
    private var elapsed = 0L
    private var previousTick: Long? = null
    private var timing = AutoSkip(AutoSkipStart.OFF)

    fun update(
        segments: List<SkipSegment>, position: Long, duration: Long,
        playing: Boolean, blocked: Boolean, autoSkip: AutoSkip, now: Long,
        enabled: (SegmentKind) -> Boolean = { true },
    ) {
        val next = segments.firstOrNull { it.contains(position, duration) && enabled(it.kind) }
            ?: segments.firstOrNull { it.inOfferWindow(position, duration, autoSkip.start.leadMs) && enabled(it.kind) }
        if (next != current) {
            current = next
            dismissed = false
            discontinuity()
        }
        if (timing != autoSkip) {
            timing = autoSkip
            discontinuity()
        }
        if (next == null || dismissed) {
            offer = null
            return
        }
        val delayMs = autoSkip.delayMs
        if (delayMs != null && playing && !blocked) {
            previousTick?.let { elapsed += (now - it).coerceIn(0, 500) }
            previousTick = now
        } else {
            previousTick = null
        }
        offer = if (blocked) null else SkipOffer(next, delayMs?.let { (it - elapsed).coerceAtLeast(0) })
    }

    fun takeTarget(position: Long, duration: Long, advanceAfterCredits: () -> Boolean = { false }): Long? {
        val segment = offer?.segment?.takeIf { it.inOfferWindow(position, duration, timing.start.leadMs) } ?: return null
        dismiss()
        return segment.endMs.takeUnless { segment.kind == SegmentKind.OUTRO && advanceAfterCredits() }
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

private fun SkipSegment.inOfferWindow(position: Long, duration: Long, leadMs: Long) =
    isValidFor(duration) && position in (startMs - leadMs).coerceAtLeast(0) until endMs

internal fun SegmentSkipState.seek(player: Player, advanceAfterCredits: () -> Boolean) {
    if (player.isCurrentMediaItemSeekable) takeTarget(player.currentPosition, player.duration, advanceAfterCredits)?.let(player::seekTo)
}
