package dev.mjwatch.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val demo = id == "demo:series" && config.demo
    init {
        if (demo) {
            val files = DemoGateway.files.filter { it.video && !it.sample }.map { episodeNumber(it.path) }
                .filter { it.season != null && it.episode != null }.groupBy { it.season!! }
            seasons = files.keys.sorted()
            files.forEach { (season, numbers) ->
                episodes.put(season, numbers.mapNotNull { it.episode }.distinct().sorted().map { Episode(it, "Серия $it") })
            }
        }
    }
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
    var season by rememberSaveable(item.id) { mutableStateOf(catalog.seasons?.firstOrNull().takeIf { catalog.demo }) }
    var seasons by remember(catalog) { mutableStateOf(catalog.seasons) }
    var episodes by remember(catalog, season) { mutableStateOf(season?.let { catalog.episodes.get(it) }) }
    var message by remember(item.id, config, season) { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var menu by remember(item.id, season) { mutableStateOf<Episode?>(null) }
    var connectFor by remember(item.id, season) { mutableStateOf<Episode?>(null) }
    val positions = remember(progress) { progress.sortedBy { it.updatedAt }.associateBy { episodeNumber(it.path) } }

    LaunchedEffect(item.id, config, season, retry) {
        message = ""
        if (catalog.demo) return@LaunchedEffect
        try {
            val available = seasons ?: withContext(Dispatchers.IO) {
                fetchTvMetadata(item, config, model).rows("seasons")
                    .map { it.getInt("season_number") }.distinct().sortedWith(compareBy<Int> { it == 0 }.thenBy { it })
            }.also { catalog.seasons = it; seasons = it }
            val number = season
            if (available.isNotEmpty() && number !in available) {
                season = positions.filterKeys { it.season in available }.maxByOrNull { it.value.updatedAt }?.key?.season ?: available.first()
            } else if (number != null && number in available && episodes == null) {
                episodes = withContext(Dispatchers.IO) {
                    fetchTvMetadata(item, config, model, number).rows("episodes").map { row ->
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
        val wide = tv || maxWidth >= 600.dp
        val inset = detailContentInset(wide,maxWidth)
        val contentWidth = maxWidth.coerceAtMost(1200.dp)
        val gap = 12.dp
        val columns = if (wide) 3 else 2
        val groups = remember(episodes, columns) { episodes.orEmpty().chunked(columns) }
        val listState = rememberLazyListState()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("detail-episode-grid"),
            contentPadding = PaddingValues(bottom = if (wide) 48.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            item("detail-header") { header() }
            if (!seasons.isNullOrEmpty()) item("seasons") {
                val strip = rememberLazyListState(initialFirstVisibleItemIndex = seasons.orEmpty().indexOf(season).coerceAtLeast(0))
                LazyRow(state = strip, contentPadding = PaddingValues(start = inset, end = inset, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("season-selector")) {
                    items(seasons.orEmpty(), key = { it }) { number ->
                        val selected = season == number
                        val shape = RoundedCornerShape(50)
                        val colors = MaterialTheme.colorScheme
                        Box(Modifier.heightIn(min=48.dp).tvFocusRing(true,shape).clip(shape)
                            .background(colors.surface).then(if (selected) Modifier.border(1.dp,colors.primary,shape) else Modifier)
                            .selectable(selected=selected, role=Role.RadioButton, onClick={ season = number })
                            .padding(horizontal=13.dp,vertical=6.dp), contentAlignment=Alignment.Center) {
                            Text(if (number == 0) "Спецвыпуски" else "Сезон $number",
                                color=if (selected) colors.primary else colors.onSurface, style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (groups.isNotEmpty()) item("episode-count") {
                Row(Modifier.width(contentWidth).padding(horizontal = inset).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Эпизоды", style = MaterialTheme.typography.titleLarge, fontSize = if (wide) 24.sp else 22.sp)
                    val count = episodes.orEmpty().size
                    Text(if (catalog.demo) "В демо · $count" else count.toString(), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                else -> itemsIndexed(groups, key = { _,row -> "season-$season-row-${row.first().number}" }) { index,group ->
                    Row(Modifier.width(contentWidth).padding(horizontal = inset).padding(top = if (index == 0) 12.dp else 0.dp).height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(gap)) {
                        group.forEach { episode ->
                            val position = positions[EpisodeNumber(season, episode.number)]
                            EpisodeTile(item, episode, position, catalog.demo, tv, Modifier.weight(1f),
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
    val elapsed = position?.positionMs?.coerceAtLeast(0) ?: 0
    val total = position?.durationMs?.takeIf { it > 0 } ?: episode.minutes.toLong() * 60_000
    val continuing = position?.completed == false && elapsed > 0
    val caption = when {
        position?.completed == true -> "Просмотрено"
        continuing && total > 0 -> {
            val minutes = total >= 60_000
            val unit = if (minutes) 60_000 else 1_000
            "Продолжить · ${elapsed.coerceAtMost(total) / unit} из ${total / unit} ${if (minutes) "мин" else "с"}"
        }
        continuing -> "Продолжить · ${time(elapsed)}"
        episode.minutes > 0 -> "${episode.minutes} мин"
        else -> null
    }
    var focused by remember { mutableStateOf(false) }
    val panel = when {
        tv && focused -> colors.primaryContainer
        colors.background.luminance() < .5f -> Color(0xFF202732)
        else -> colors.surface
    }
    Box(modifier.fillMaxHeight()) {
        NativeCard(tv, Modifier.fillMaxSize().onFocusChanged { focused = it.hasFocus }.testTag("episode-$number"), onLongClick = onMenu, onClick = onPlay) {
            Column(Modifier.fillMaxSize().background(panel)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.35f).background(colors.surfaceContainerHigh).testTag("episode-banner-$number")) {
                    val image = episode.image ?: item.backdrop ?: item.poster
                    if (demo) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.demo_orbit), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    else {
                        Icon(Icons.Outlined.Movie, null, Modifier.align(Alignment.Center).size(28.dp), tint = colors.onSurfaceVariant)
                        if (image != null) AsyncImage(model = image, imageLoader = LocalImages.current, contentDescription = null,
                            modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    }
                    Text(
                        number.toString().padStart(2, '0'),
                        Modifier.align(Alignment.TopStart).padding(10.dp)
                            .background(Color(0xBE10171C), RoundedCornerShape(7.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                            .clearAndSetSemantics { contentDescription = "Серия $number" },
                        color = Color.White,
                        style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(episode.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall,
                        fontSize = 13.sp, color = colors.onSurfaceVariant)
                    if (continuing && total > 0) LinearProgressIndicator(
                        progress = { (elapsed.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(4.dp),
                        color = colors.primary, trackColor = colors.onSurfaceVariant.copy(alpha = .3f),
                        gapSize = 0.dp, drawStopIndicator = {})
                }
            }
        }
        if (!tv) IconButton(onClick = onMenu, modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp).size(48.dp).testTag("episode-menu-$number")) {
            Box(Modifier.size(40.dp).background(Color(0xBD10171C), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MoreVert, "Действия серии $number", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}
