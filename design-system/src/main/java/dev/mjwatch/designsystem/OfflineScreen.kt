package dev.mjwatch.designsystem

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import dev.mjwatch.playback.PlaybackEpisode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OfflineScreen(model: WatchModel, tv: Boolean) {
    val context = LocalContext.current
    val state by model.state.collectAsState()
    val config = state.config
    val store = remember(context) { OfflineDownloads.get(context) }
    val downloads by remember(store, config) { store.observe(config) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val player = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    var error by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<OfflineDownload?>(null) }

    fun run(action: suspend () -> Unit) {
        error = ""
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = safeError(e)
            }
        }
    }

    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Удалить загрузку?") },
            text = {
                Text(listOfNotNull(item.title, item.season?.let { "Сезон $it" }, item.episode?.let { "Серия $it" }, fileLabel(item.path)).joinToString(" · "))
            },
            confirmButton = {
                TextButton(onClick = { removing = null; run { store.remove(config, item.id) } }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Отмена") } }
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        modifier = Modifier.fillMaxSize().testTag("offline-list"),
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (error.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (downloads.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Загрузок пока нет. Чтобы смотреть без сети, выберите раздачу и нажмите «Скачать».", modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(downloads, key = { it.id }) { item ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val subtitle = listOfNotNull(
                        item.season?.let { "Сезон $it" },
                        item.episode?.let { "Серия $it" },
                        fileLabel(item.path).takeUnless { it.equals(item.title, ignoreCase = true) }
                    ).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val status = when (item.state) {
                        OfflineState.QUEUED -> "В очереди"
                        OfflineState.DOWNLOADING -> if (item.totalBytes > 0) {
                            val percent = (item.bytesDownloaded.toDouble() / item.totalBytes * 100).toInt().coerceIn(0, 100)
                            "Загрузка · $percent% · ${sizeLabel(item.bytesDownloaded)} из ${sizeLabel(item.totalBytes)}"
                        } else "Загрузка · ${sizeLabel(item.bytesDownloaded)}"
                        OfflineState.READY -> "Готово · ${sizeLabel(item.totalBytes)}"
                        OfflineState.FAILED -> "Не удалось загрузить" + (item.error?.let { " · $it" } ?: "")
                        OfflineState.CANCELLED -> "Отменено"
                    }
                    Text(
                        status,
                        color = if (item.state == OfflineState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (item.state == OfflineState.DOWNLOADING) Modifier else Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    if (item.state == OfflineState.DOWNLOADING && item.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (item.bytesDownloaded.toFloat() / item.totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (item.state) {
                            OfflineState.READY -> Action("Смотреть", tv, Modifier.testTag("offline-play-${item.id}"), icon = Icons.Outlined.PlayArrow) {
                                run { player.launch(offlinePlaybackIntent(context, config, item, !tv)) }
                            }
                            OfflineState.QUEUED, OfflineState.DOWNLOADING -> Action("Отменить", tv, secondary = true) {
                                run { store.cancel(config, item.id) }
                            }
                            OfflineState.FAILED, OfflineState.CANCELLED -> Action("Повторить", tv, icon = Icons.Outlined.Refresh) {
                                run { store.retry(config, item.id) }
                            }
                        }
                        if (item.state !in setOf(OfflineState.QUEUED, OfflineState.DOWNLOADING)) {
                            Action("Удалить", tv, secondary = true, icon = Icons.Outlined.Delete) {
                                error = ""
                                removing = item
                            }
                        }
                    }
                }
            }
        }
    }
}


internal suspend fun offlinePlaybackIntent(context: Context, config: Config, item: OfflineDownload, mobile: Boolean): Intent {
    val file = OfflineDownloads.get(context).localFile(config, item.id)
        ?: error("Файл недоступен. Удалите запись и загрузите видео снова.")
    val episode = PlaybackEpisode(Uri.fromFile(file).toString(), item.path, item.fileId, item.contentKey)
    return PlaybackActivity.offlineIntent(context, episode, item.title, item.releaseId, mobile)
}
