package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.mjwatch.core.HttpFailure
import dev.mjwatch.core.safeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Renders live or fixture snapshots through the same polling and action flows. */
@Composable
fun JacredJobsPanel(
    tv: Boolean,
    load: suspend () -> JSONObject,
    start: suspend (String, String) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var checkedAt by remember { mutableLongStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf("") }
    var actionMessage by remember { mutableStateOf("") }
    var actionFailed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var starting by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<JSONObject?>(null) }
    val currentLoad by rememberUpdatedState(load)

    LaunchedEffect(lifecycle, refresh, starting) {
        if (!starting) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    data = currentLoad()
                    checkedAt = Instant.now().epochSecond
                    loadError = ""
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val status = (failure as? HttpFailure)?.status
                    if (status == 401 || status == 403 || status == 404) {
                        data = null
                        checkedAt = 0
                    }
                    loadError = if (status == 404) "Обновите сервер MJWatch для просмотра задач JacRed."
                    else safeError(failure)
                } finally {
                    loading = false
                }
                delay(15_000)
            }
        }
    }

    val jobs = data.objects("jobs")
    val starts = data.objects("availableStarts")
    val runningTrackers = jobs.map { it.optString("tracker") }.toSet()
    val available = starts.filter { it.optString("tracker") !in runningTrackers }
    val colors = MaterialTheme.colorScheme
    SettingsGroup(tv) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Активные задачи", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    if (checkedAt > 0) Text(
                        (if (loadError.isEmpty()) "Обновлено в " else "Последние данные на ") + jobClock(checkedAt),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                }
                OutlinedIconButton(
                    onClick = { loading = true; refresh++ },
                    enabled = !loading && !starting,
                    modifier = Modifier.size(48.dp).tvFocusRing(tv, MaterialTheme.shapes.small)
                        .semantics { contentDescription = if (loading) "Обновляются задачи" else "Обновить задачи" },
                    shape = MaterialTheme.shapes.small,
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            if (loadError.isNotEmpty()) Text(loadError, color = colors.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (jobs.isEmpty() && loadError.isEmpty()) {
                Text(if (data == null) "Загружаем задачи…" else "Нет активных задач",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            jobs.forEach { job ->
                key(job.optString("id")) { JacredJobProgress(job) }
            }
        }
    }
    if (starts.isNotEmpty() || actionMessage.isNotEmpty()) {
        SettingsCard("Ручной запуск", Icons.Outlined.PlayArrow, tv) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Обновление раздач", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                actionMessage.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = if (actionFailed) colors.error else colors.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (available.isEmpty() && starts.isNotEmpty()) {
                    Text("Все доступные трекеры заняты", style = MaterialTheme.typography.bodyMedium)
                }
                available.forEach { option ->
                    val tracker = option.optString("tracker")
                    key(tracker) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tracker, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            FilledTonalButton(
                                onClick = { confirmation = option },
                                enabled = !starting && !loading && loadError.isEmpty(),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = colors.primaryContainer,
                                    contentColor = colors.onPrimaryContainer,
                                ),
                                modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(tv)
                                    .semantics { contentDescription = "Запустить обновление $tracker" },
                            ) {
                                Text("Запустить")
                            }
                        }
                    }
                }
            }
        }
    }

    confirmation?.let { option ->
        val tracker = option.optString("tracker")
        val job = option.optString("job")
        val canStart = available.any { it.optString("tracker") == tracker && it.optString("job") == job }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("Обновить $tracker?") },
            text = {
                Text(when {
                    loadError.isNotEmpty() -> "Не удалось проверить состояние трекера."
                    !canStart -> "Трекер сейчас недоступен для запуска."
                    else -> "JacRed проверит актуальность раздач этого трекера."
                })
            },
            confirmButton = {
                TextButton(modifier = Modifier.tvFocusRing(tv), onClick = {
                    confirmation = null
                    starting = true
                    actionFailed = false
                    actionMessage = "Отправляем запрос…"
                    scope.launch {
                        try {
                            start(tracker, job)
                            actionMessage = "JacRed принял запуск для $tracker."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            actionFailed = true
                            actionMessage = safeError(failure)
                        } finally {
                            loading = true
                            refresh++
                            starting = false
                        }
                    }
                }, enabled = !loading && !starting && loadError.isEmpty() && canStart) {
                    Text("Запустить")
                }
            },
            dismissButton = {
                TextButton(modifier = Modifier.tvFocusRing(tv), onClick = { confirmation = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun JacredJobProgress(job: JSONObject) {
    val kind = when (val type = job.optString("job")) {
        "ParseAllTask" -> "Полный сбор"
        "UpdateTasksParse" -> "Обновление раздач"
        else -> type
    }
    val start = runCatching { Instant.parse(job.optString("startedAtUtc")).epochSecond }.getOrNull()
    val elapsed = job.optLong("elapsedSeconds", -1)
    val pages = job.optInt("pagesCompleted", -1)
    val total = job.optInt("pagesTotal")
    val percent = job.optDouble("percent")
    val page = job.optInt("currentPage")
    val pageCount = pages.takeIf { it >= 0 }?.let {
        if (total > 0) "$it из $total страниц" else "Страниц обработано: $it"
    }
    val position = listOfNotNull(
        (job.opt("currentCategory") as? String)?.takeIf { it.isNotBlank() },
        page.takeIf { it > 0 }?.let { "страница $it" },
    )
    val progress = when {
        percent in 0.0..100.0 -> percent / 100
        total > 0 && pages >= 0 -> (pages.toDouble() / total).coerceIn(0.0, 1.0)
        else -> null
    }
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = colors.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(job.optString("tracker"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(kind, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (progress != null) Text("${(progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleLarge, color = colors.primary)
            }
            if (progress != null) {
                LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth(),
                    trackColor = colors.surfaceContainerLowest, gapSize = 0.dp, drawStopIndicator = {})
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pageCount.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (elapsed >= 0) Text(jacredElapsed(elapsed), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
            }
            if (position.isNotEmpty()) Text(position.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            job.optString("summary", "").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            start?.let {
                Text("Начало ${jacredTime(it)}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

private fun jobClock(seconds: Long): String = DateTimeFormatter.ofPattern("HH:mm:ss")
    .format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))

private fun JSONObject?.objects(key: String): List<JSONObject> {
    val rows = this?.optJSONArray(key) ?: return emptyList()
    return (0 until rows.length()).mapNotNull(rows::optJSONObject)
}

private fun jacredElapsed(seconds: Long): String = when {
    seconds < 60 -> "$seconds с"
    seconds < 3600 -> "${seconds / 60} мин ${seconds % 60} с"
    else -> "${seconds / 3600} ч ${seconds / 60 % 60} мин"
}
