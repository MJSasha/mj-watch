package dev.mjwatch.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.mjwatch.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

internal data class Episode(val number: Int, val name: String, val minutes: Int = 0, val image: String? = null)

/** Only the current title and four recently visited seasons are held in memory. */
internal class EpisodeCatalog(val id: String, val config: Config) {
    var seasons: List<Int>? = null
    val episodes = android.util.LruCache<Int, List<Episode>>(4)
}

private fun JSONObject.rows(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

private suspend fun fetchTvMetadata(item: Metadata, config: Config, model: WatchModel, season: Int? = null): JSONObject {
    val id = item.id.removePrefix("tmdb:tv:")
    require(id.matches(Regex("[0-9]+")))
    if (config.managed) {
        return ManagedApi(config, model.secrets).authorized("metadata/tv/$id" + (season?.let { "/seasons/$it" } ?: ""))
    }
    val url = endpoint("https://api.themoviedb.org").child("3/tv/$id" + (season?.let { "/season/$it" } ?: ""))
        .newBuilder().addQueryParameter("language", "ru-RU").build()
    val request = Request.Builder().url(url).header("Authorization", "Bearer ${model.secrets.get("tmdb")}").build()
    return JSONObject(String(guardedClient(config).bytes(request)))
}

private fun episodeStill(path: String, config: Config): String? {
    if (config.managed) return path.takeIf { it.startsWith("/api/v1/metadata/images/") }?.let { endpoint(config.managedUrl).child(it.drop(1)).toString() }
    return path.takeIf { it.matches(Regex("/[A-Za-z0-9._-]+")) }?.let { "https://image.tmdb.org/t/p/w500$it" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ManagedEpisodes(
    item: Metadata, config: Config, model: WatchModel, tv: Boolean, progress: List<Progress> = emptyList(),
    header: @Composable () -> Unit = {}
) {
    val catalog = remember(item.id, config) { model.episodeCatalog(item.id, config) }
    var season by rememberSaveable(item.id) { mutableStateOf<Int?>(null) }
    var seasons by remember(catalog) { mutableStateOf(catalog.seasons) }
    var episodes by remember(catalog, season) { mutableStateOf(season?.let { catalog.episodes.get(it) }) }
    var message by remember(item.id, config, season) { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var menu by remember(item.id, season) { mutableStateOf<Episode?>(null) }
    var connectFor by remember(item.id, season) { mutableStateOf<Episode?>(null) }
    val positions = remember(progress) { progress.sortedBy { it.updatedAt }.associateBy { episodeNumber(it.path) } }
    val demo = item.id == "demo:series" && config.demo

    LaunchedEffect(item.id, config, season, retry) {
        message = ""
        try {
            val available = seasons ?: withContext(Dispatchers.IO) {
                if (demo) listOf(1) else fetchTvMetadata(item, config, model).rows("seasons")
                    .map { it.getInt("season_number") }.distinct().sortedWith(compareBy<Int> { it == 0 }.thenBy { it })
            }.also { catalog.seasons = it; seasons = it }
            val number = season
            if (available.isNotEmpty() && number !in available) {
                season = positions.filterKeys { it.season in available }.maxByOrNull { it.value.updatedAt }?.key?.season ?: available.first()
            } else if (number != null && number in available && episodes == null) {
                episodes = withContext(Dispatchers.IO) {
                    if (demo) {
                        DemoGateway.status(item.id).files.filter { it.video && !it.sample }
                            .mapNotNull { episodeNumber(it.path).episode }.distinct().sorted().map { Episode(it, "Серия $it") }
                    } else fetchTvMetadata(item, config, model, number).rows("episodes").map { row ->
                        val episode = row.getInt("episode_number")
                        val name = row.optString("name").takeUnless { it.isBlank() || it == "null" } ?: "Серия $episode"
                        Episode(episode, name, row.optInt("runtime"), episodeStill(row.optString("still_path"), config))
                    }.distinctBy { it.number }.sortedBy { it.number }
                }.also { catalog.episodes.put(number, it) }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = safeError(e) }
    }
    fun releases(episode: Episode, targetId: String? = null, download: Boolean = false) {
        val number = season ?: return
        menu = null
        model.findEpisodeReleases(item, number, episode.number, targetId, download)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val inset = if (tv) tvUnit(40f) else if (maxWidth >= 600.dp) 28.dp else 16.dp
        val gap = if (tv) 16.dp else 12.dp
        val cellWidth = (if (tv) 200.dp else 152.dp) * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val columns = ((maxWidth - inset * 2 + gap) / (cellWidth + gap)).toInt().coerceIn(2, 8)
        val groups = remember(episodes, columns) { episodes.orEmpty().chunked(columns) }
        val listState = rememberLazyListState()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("detail-episode-grid"),
            contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            item("detail-header") { header() }
            if (!seasons.isNullOrEmpty()) item("seasons") {
                val strip = rememberLazyListState(initialFirstVisibleItemIndex = seasons.orEmpty().indexOf(season).coerceAtLeast(0))
                LazyRow(state = strip, contentPadding = PaddingValues(horizontal = inset, vertical = if (tv) 6.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("season-selector")) {
                    items(seasons.orEmpty(), key = { it }) { number ->
                        Pill(if (number == 0) "Спецвыпуски" else "Сезон $number", season == number, tv) { season = number }
                    }
                }
            }
            when {
                message.isNotBlank() -> item("error") {
                    Column(Modifier.padding(horizontal = inset), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OperationMessage(message, error = true)
                        Action("Повторить загрузку", tv, secondary = true) { retry++ }
                    }
                }
                seasons?.isEmpty() == true -> item("empty") { Text("В каталоге пока нет сезонов.", Modifier.padding(horizontal = inset)) }
                episodes == null -> item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = inset).semantics { contentDescription = "Загрузка серий" }) }
                groups.isEmpty() -> item("empty") { Text("Серии этого сезона пока не добавлены.", Modifier.padding(horizontal = inset)) }
                else -> items(groups, key = { "season-$season-row-${it.first().number}" }) { group ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        group.forEach { episode ->
                            val position = positions[EpisodeNumber(season, episode.number)]
                            EpisodeTile(item, episode, position, demo, tv, Modifier.weight(1f),
                                onMenu = { menu = episode }, onPlay = {
                                    if (position != null && !position.completed && position.positionMs > 0) model.resume(position)
                                    else releases(episode)
                                })
                        }
                        repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    menu?.let { episode ->
        val position = positions[EpisodeNumber(season, episode.number)]
        val actions: @Composable () -> Unit = {
            if (position != null && !position.completed && position.positionMs > 0) {
                Action("Продолжить", tv, Modifier.fillMaxWidth(), icon = Icons.Outlined.PlayArrow) { menu = null; model.resume(position) }
            }
            Action("Раздачи", tv, Modifier.fillMaxWidth().testTag("episode-sources"), secondary = true, icon = Icons.Outlined.Tune) { releases(episode) }
            if (!tv) {
                Action("Скачать", false, Modifier.fillMaxWidth(), enabled = !config.demo, secondary = true, icon = Icons.Outlined.FileDownload) {
                    releases(episode, download = true)
                }
                Action("Смотреть на ТВ", false, Modifier.fillMaxWidth(), secondary = true, icon = Icons.Outlined.Cast) {
                    val receiver = model.remote.value
                    if (receiver.canLaunch && receiver.profileKey == config.profileKey) releases(episode, targetId = receiver.target?.id)
                    else { menu = null; connectFor = episode }
                }
            }
        }
        if (tv) AlertDialog(onDismissRequest = { menu = null }, title = { Text(episode.name) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() } },
            confirmButton = { TextButton(onClick = { menu = null }) { Text("Закрыть") } })
        else ModalBottomSheet(onDismissRequest = { menu = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Сезон $season · Серия ${episode.number}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(episode.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                actions()
            }
        }
    }
    connectFor?.let { episode ->
        TargetSheet(model.state.value, model, onDismiss = { connectFor = null }, onConnected = {
            connectFor = null
            releases(episode, targetId = it)
        })
    }
}

@Composable private fun EpisodeTile(item: Metadata, episode: Episode, position: Progress?, demo: Boolean, tv: Boolean,
    modifier: Modifier, onMenu: () -> Unit, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val number = episode.number
    val hasProgress = position != null && position.positionMs > 0
    val caption = listOfNotNull(
        if (position?.completed == true) "Просмотрено" else if (hasProgress) "Продолжить" else null,
        episode.minutes.takeIf { it > 0 }?.let { "$it мин" }
    ).joinToString(" · ")
    Box(modifier) {
        NativeCard(tv, Modifier.fillMaxWidth().testTag("episode-$number"), onLongClick = onMenu, onClick = onPlay) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).background(colors.surfaceContainerHigh).testTag("episode-banner-$number")) {
                    val image = episode.image ?: item.backdrop ?: item.poster
                    if (demo) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.demo_orbit), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    else {
                        Icon(Icons.Outlined.Movie, null, Modifier.align(Alignment.Center).size(28.dp), tint = colors.onSurfaceVariant)
                        if (image != null) AsyncImage(model = image, imageLoader = LocalImages.current, contentDescription = null,
                            modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    }
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .58f)))))
                    if (episode.name != "Серия $number") Text("Серия $number", Modifier.align(Alignment.BottomStart).padding(10.dp), color = Color.White,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    val duration = position?.durationMs ?: 0
                    if (hasProgress && duration > 0) LinearProgressIndicator(
                        progress = { (position.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).height(3.dp))
                }
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(episode.name, fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        if (!tv) IconButton(onClick = onMenu, modifier = Modifier.align(Alignment.TopEnd).size(48.dp).testTag("episode-menu-$number")) {
            Box(Modifier.size(30.dp).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MoreVert, "Действия серии $number", tint = Color.White, modifier = Modifier.size(19.dp))
            }
        }
    }
}
