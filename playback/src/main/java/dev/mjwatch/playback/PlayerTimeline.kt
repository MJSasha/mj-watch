package dev.mjwatch.playback

import dev.mjwatch.core.SkipSegment
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.describeSkipSegments(segments: List<SkipSegment>, duration: Long): Modifier {
    val description = remember(segments, duration) {
        segments.filter { it.isValidFor(duration) }.joinToString(". ") {
            "${it.kind.label}, ${playerTime(it.startMs)} – ${playerTime(it.endMs)}"
        }
    }
    if (description.isEmpty()) return this
    return semantics { contentDescription = description }
}

@Composable
internal fun PlayerTimelineTrack(
    position: Long,
    duration: Long,
    segments: List<SkipSegment>,
    accent: Color,
    focused: Boolean = false,
) {
    Canvas(Modifier.fillMaxWidth().height(7.dp).testTag("player-timeline-track")) {
        val y = size.height / 2
        val x = size.width * (position.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f)
        val thickness = (if (focused) 3.dp else 2.dp).toPx()
        val start = Offset(0f, y)
        val thumb = Offset(x, y)
        drawLine(Color(0xFF78818A), start, Offset(size.width, y), thickness, StrokeCap.Round)
        drawLine(accent, start, thumb, thickness, StrokeCap.Round)

        val markerHeight = 6.dp.toPx()
        for (segment in segments) {
            if (!segment.isValidFor(duration)) continue
            val left = size.width * (segment.startMs.toFloat() / duration)
            val right = size.width * (segment.endMs.toFloat() / duration)
            drawRoundRect(
                Color(0xFF58CDEB),
                topLeft = Offset(left, y - markerHeight / 2),
                size = Size(right - left, markerHeight),
                cornerRadius = CornerRadius(markerHeight / 2),
            )
        }

        if (focused) {
            drawCircle(Color(0xFF101722), 7.5.dp.toPx(), thumb)
            drawCircle(Color(0xFFF0F2EC), 7.5.dp.toPx(), thumb, style = Stroke(1.5.dp.toPx()))
        }
        drawCircle(accent, (if (focused) 4.5.dp else 3.5.dp).toPx(), thumb)
    }
}
