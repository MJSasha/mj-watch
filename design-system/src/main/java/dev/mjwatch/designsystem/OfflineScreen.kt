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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    var removing by remember { mutableStateOf<List<OfflineDownload>>(emptyList()) }
    var selection by remember(config.profileKey) { mutableStateOf<Set<String>?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val selected = downloads.orEmpty().filter { it.id in selection.orEmpty() }

    fun toggle(items: List<OfflineDownload>) {
        val ids = items.map { it.id }.toSet()
        val current = selection.orEmpty()
        selection = if (ids.all(current::contains)) current - ids else current + ids
    }

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

    if (removing.isNotEmpty()) {
        val items = removing
        AlertDialog(
            onDismissRequest = { removing = emptyList() },
            title = { Text(if (items.size == 1) "Удалить загрузку?" else "Удалить выбранные загрузки?") },
            text = {
                val item = items.singleOrNull()
                Text(if (item != null) listOfNotNull(item.title, item.season?.let { "Сезон $it" },
                    item.episode?.let { "Серия $it" }).joinToString(" · ")
                    else "Файлов: ${items.size} · ${sizeLabel(items.sumOf { it.bytesDownloaded })}")
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = emptyList()
                    deleting = true
                    run {
                        try {
                            items.forEach { store.remove(config, it.id) }
                            selection = null
                        } finally { deleting = false }
                    }
                }, modifier = Modifier.testTag("offline-confirm-delete")) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { removing = emptyList() }) { Text("Отмена") } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (!downloads.isNullOrEmpty()) Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selection == null) TextButton(onClick = { selection = emptySet() },
                modifier = Modifier.testTag("offline-select")) { Text("Выбрать") }
            else {
                TextButton(onClick = { selection = null }, enabled = !deleting) { Text("Отмена") }
                TextButton(onClick = { removing = selected }, enabled = selected.isNotEmpty() && !deleting,
                    modifier = Modifier.testTag("offline-delete-selected")) { Text("Удалить (${selected.size})") }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("offline-list"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + LocalFloatingNavigationClearance.current),
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
                if (item.season != null) "${item.metadataId ?: item.releaseId}:season:${item.season}"
                else if (item.metadataId?.startsWith("tmdb:tv:") == true) "${item.metadataId}:episodes"
                else item.id
            }
            groups.forEach { (groupId, group) ->
                val first = group.first()
                val series = groupId != first.id
                item(key = groupId) {
                    val play: (OfflineDownload) -> Unit = { download ->
                        run { player.launch(offlinePlaybackIntent(context, config, download, true)) }
                    }
                    val cancel: (OfflineDownload) -> Unit = { download -> run { store.cancel(config, download.id) } }
                    val retry: (OfflineDownload) -> Unit = { download -> run { store.retry(config, download.id) } }
                    val remove: (OfflineDownload) -> Unit = { download -> removing = listOf(download) }
                    if (series) SeasonDownloadCard(store, config, group, offline, selection,
                        onSelect = { if (!deleting) toggle(it) }, play = play, cancel = cancel, retry = retry, remove = remove)
                    else DownloadCard(store, config, first, offline, selection?.contains(first.id), onSelect = {
                        if (!deleting) toggle(group)
                    }, play = { play(first) }, cancel = { cancel(first) }, retry = { retry(first) }, remove = { remove(first) })
                }
            }
        }
    }
}

@Composable
private fun DownloadPoster(store: OfflineDownloads, config: Config, items: List<OfflineDownload>, offline: Boolean) {
    val ids = items.map { it.id }
    val poster by produceState<java.io.File?>(null, config, ids, offline, items.map { it.state }) {
        value = store.localPoster(config, ids, allowNetwork = !offline)
    }
    Box(Modifier.size(60.dp, 86.dp).clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (poster != null) AsyncImage(model = poster, contentDescription = null,
            modifier = Modifier.fillMaxSize().testTag("offline-poster"), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun SeasonDownloadCard(
    store: OfflineDownloads, config: Config, group: List<OfflineDownload>, offline: Boolean,
    selection: Set<String>?, onSelect: (List<OfflineDownload>) -> Unit, play: (OfflineDownload) -> Unit,
    cancel: (OfflineDownload) -> Unit, retry: (OfflineDownload) -> Unit, remove: (OfflineDownload) -> Unit,
) {
    val first = group.first()
    val ready = group.count { it.state == OfflineState.READY }
    val season = when (first.season) { null -> "Эпизоды"; 0 -> "Спецвыпуски"; else -> "Сезон ${first.season}" }
    var expanded by rememberSaveable { mutableStateOf(true) }
    val rows = remember(group) { group.sortedBy { it.episode ?: Int.MAX_VALUE } }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().testTag("offline-season")) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DownloadPoster(store, config, rows, offline)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(first.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    Text(season, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Готово $ready из ${group.size} · ${sizeLabel(group.sumOf { it.bytesDownloaded })}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selection != null) {
                    val count = group.count { it.id in selection }
                    TriStateCheckbox(
                        state = when (count) { 0 -> ToggleableState.Off; group.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                        onClick = { onSelect(group) },
                        modifier = Modifier.testTag("offline-select-season").semantics { contentDescription = "Выбрать $season" },
                    )
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "Свернуть серии" else "Показать серии")
            }
            if (expanded) rows.forEach { item ->
                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.episode?.let { "Серия $it" } ?: fileLabel(item.path),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        DownloadStatus(item)
                    }
                    if (selection != null) Checkbox(checked = item.id in selection,
                        onCheckedChange = { onSelect(listOf(item)) }, modifier = Modifier.testTag("offline-select-${item.id}")
                            .semantics { contentDescription = item.episode?.let { "Выбрать серию $it" } ?: "Выбрать файл" })
                    else {
                        when (item.state) {
                            OfflineState.READY -> IconButton(onClick = { play(item) },
                                modifier = Modifier.testTag("offline-play-${item.id}")) { Icon(Icons.Outlined.PlayArrow, "Смотреть") }
                            OfflineState.QUEUED, OfflineState.DOWNLOADING -> IconButton(onClick = { cancel(item) }) {
                                Icon(Icons.Outlined.Close, "Отменить загрузку")
                            }
                            OfflineState.FAILED, OfflineState.CANCELLED -> IconButton(onClick = { retry(item) }) {
                                Icon(Icons.Outlined.Refresh, "Повторить загрузку")
                            }
                        }
                        if (item.state !in setOf(OfflineState.QUEUED, OfflineState.DOWNLOADING)) IconButton(onClick = { remove(item) }) {
                            Icon(Icons.Outlined.DeleteOutline, "Удалить загрузку")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(store: OfflineDownloads, config: Config, item: OfflineDownload, offline: Boolean,
    selected: Boolean?, onSelect: () -> Unit, play: () -> Unit, cancel: () -> Unit, retry: () -> Unit, remove: () -> Unit
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DownloadPoster(store, config, listOf(item), offline)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val title = item.episode?.let { "Серия $it" } ?: item.title
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val subtitle = fileLabel(item.path)
                    if (subtitle.isNotBlank() && !subtitle.equals(title, ignoreCase = true)) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
            DownloadStatus(item)
            if (selected != null) Checkbox(checked = selected, onCheckedChange = { onSelect() },
                modifier = Modifier.testTag("offline-select-${item.id}").semantics { contentDescription = "Выбрать «${item.title}»" })
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun DownloadStatus(item: OfflineDownload) {
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
        status, style = MaterialTheme.typography.bodySmall,
        color = if (item.state == OfflineState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (item.state == OfflineState.DOWNLOADING) Modifier else Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
    if (item.state == OfflineState.DOWNLOADING && item.totalBytes > 0) {
        LinearProgressIndicator(
            progress = { (item.bytesDownloaded.toFloat() / item.totalBytes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        if (item.error == null) DownloadEstimate(item.id, item.totalBytes, item.bytesDownloaded)
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
