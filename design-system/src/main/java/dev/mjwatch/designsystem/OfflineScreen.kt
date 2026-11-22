package dev.mjwatch.designsystem

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import dev.mjwatch.playback.PlaybackEpisode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OfflineScreen(config: Config, offline: Boolean = false) {
    val context = LocalContext.current
    val store = remember(context) { OfflineDownloads.get(context) }
    val downloads by key(config.profileKey, offline) {
        remember(store, config, offline) { store.observe(config, readyOnly = offline) }.collectAsState(initial = null)
    }
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
        val items = downloads
        if (items == null) item(span = { GridItemSpan(maxLineSpan) }) {
            LoadingIndicator("Открываем загрузки…")
        } else if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.FileDownload, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Загрузок пока нет", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (offline) "Подключитесь к сети, чтобы скачать фильмы и сериалы."
                    else "Чтобы смотреть без сети, выберите раздачу и нажмите «Скачать».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        val groups = items.orEmpty().groupBy { item ->
            if (item.season != null) Triple(item.metadataId ?: item.releaseId, item.season, null)
            else Triple(null, null, item.id)
        }
        groups.values.forEach { group ->
            val first = group.first()
            if (first.season != null) item(key = "season-${first.metadataId ?: first.releaseId}-${first.season}",
                span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val cover = group.firstOrNull { it.state == OfflineState.READY } ?: first
                    DownloadPoster(store, config, cover)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(first.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() })
                        val seasonLabel = if (first.season == 0) "Спецвыпуски" else "Сезон ${first.season}"
                        val ready = group.count { it.state == OfflineState.READY }
                        Text(if (offline) "$seasonLabel · Серий: $ready" else "$seasonLabel · Готово $ready из ${group.size}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            group.sortedBy { it.episode ?: Int.MAX_VALUE }.forEach { download ->
                item(key = download.id) { DownloadCard(store, config, download, play = {
                    run { player.launch(offlinePlaybackIntent(context, config, download, true)) }
                }, cancel = { run { store.cancel(config, download.id) } },
                    retry = { run { store.retry(config, download.id) } },
                    remove = { error = ""; removing = download })
                }
            }
        }
    }
}

@Composable
private fun DownloadPoster(store: OfflineDownloads, config: Config, item: OfflineDownload) {
    val poster by key(config.profileKey, item.id) {
        produceState<java.io.File?>(null, config, item.state) { value = store.localPoster(config, item.id) }
    }
    Box(Modifier.size(52.dp, 74.dp).clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(model = poster, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun DownloadCard(store: OfflineDownloads, config: Config, item: OfflineDownload,
    play: () -> Unit, cancel: () -> Unit, retry: () -> Unit, remove: () -> Unit
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item.season == null) DownloadPoster(store, config, item)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val title = item.episode?.let { "Серия $it" } ?: item.title
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val subtitle = fileLabel(item.path)
                    if (subtitle.isNotBlank() && !subtitle.equals(title, ignoreCase = true)) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
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
                    OfflineState.READY -> Action("Смотреть", false, Modifier.testTag("offline-play-${item.id}"),
                        icon = Icons.Outlined.PlayArrow, onClick = play)
                    OfflineState.QUEUED, OfflineState.DOWNLOADING -> Action("Отменить", false, secondary = true, onClick = cancel)
                    OfflineState.FAILED, OfflineState.CANCELLED -> Action("Повторить", false, icon = Icons.Outlined.Refresh, onClick = retry)
                }
                if (item.state !in setOf(OfflineState.QUEUED, OfflineState.DOWNLOADING)) {
                    Action("Удалить", false, secondary = true, icon = Icons.Outlined.Delete, onClick = remove)
                }
            }
        }
    }
}

@Composable
internal fun rememberNetworkAvailable(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    var available by remember(manager) { mutableStateOf(manager.activeNetwork != null) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { available = true }
            override fun onLost(network: Network) { available = false }
        }
        manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        available = manager.activeNetwork != null
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return available
}

@Composable
internal fun OfflineHome(config: Config) {
    WatchTheme(config.theme) {
        val colors = MaterialTheme.colorScheme
        val window = androidx.activity.compose.LocalActivity.current?.window
        SideEffect {
            window?.let {
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = colors.background.luminance() > .5f
                    isAppearanceLightNavigationBars = isAppearanceLightStatusBars
                }
            }
        }
        Surface(color = colors.background, modifier = Modifier.fillMaxSize().testTag("offline-home")) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Загрузки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    Surface(shape = MaterialTheme.shapes.small, color = colors.secondaryContainer) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.WifiOff, null, Modifier.size(16.dp))
                            Text("Без сети", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                OfflineScreen(config, offline = true)
            }
        }
    }
}

internal suspend fun offlinePlaybackIntent(context: Context, config: Config, item: OfflineDownload, mobile: Boolean): Intent {
    val file = OfflineDownloads.get(context).localFile(config, item.id)
        ?: error("Файл недоступен. Удалите запись и загрузите видео снова.")
    val episode = PlaybackEpisode(Uri.fromFile(file).toString(), item.path, item.fileId, item.contentKey)
    return PlaybackActivity.offlineIntent(context, episode, item.title, item.releaseId, mobile)
        .putExtra("metadataId",item.metadataId)
        .putExtra("offlineDownloadId",item.id)
}
