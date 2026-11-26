package dev.mjwatch.designsystem

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.mjwatch.core.DownloadEta
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun DownloadEstimate(transfer: Any, total: Long, bytes: Long) {
    if (bytes <= 0 || bytes >= total) return
    val received by rememberUpdatedState(bytes)
    val remaining by key(transfer, total) {
        produceState<Long?>(null) {
            val estimate = DownloadEta()
            while (isActive) {
                value = estimate.secondsLeft(total, received, SystemClock.elapsedRealtime())
                delay(1_000)
            }
        }
    }
    remaining?.let { seconds ->
        val minutes = seconds / 60 + if (seconds % 60 == 0L) 0 else 1
        val hours = minutes / 60
        val remainder = minutes % 60
        val roundedSeconds = ((seconds % 60 + 4) / 5 * 5).toInt()
        val label = when {
            seconds < 60 -> "$roundedSeconds с"
            minutes < 60 -> "$minutes мин"
            remainder == 0L -> "$hours ч"
            else -> "$hours ч $remainder мин"
        }
        val spoken = when {
            seconds < 60 -> pluralStringResource(R.plurals.download_seconds, roundedSeconds, roundedSeconds)
            minutes < 60 -> pluralStringResource(R.plurals.download_minutes, minutes.toInt(), minutes)
            else -> {
                val hourLabel = pluralStringResource(R.plurals.download_hours, (hours % 100).toInt(), hours)
                if (remainder == 0L) hourLabel
                else "$hourLabel ${pluralStringResource(R.plurals.download_minutes, remainder.toInt(), remainder)}"
            }
        }
        val description = stringResource(R.string.download_time_remaining, spoken)
        val accent = MaterialTheme.colorScheme.primary
        Row(
            modifier = Modifier.testTag("download-estimate").clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Schedule, contentDescription = null, Modifier.size(16.dp), tint = accent)
            Text(label, style = MaterialTheme.typography.bodySmall, color = accent)
        }
    }
}
