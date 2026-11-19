package dev.mjwatch.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mjwatch.core.*
import kotlinx.coroutines.CancellationException

@Composable
internal fun DetailScreen(
    s: WatchState, entries: List<LibraryEntry>, progress: List<Progress>,
    model: WatchModel, tv: Boolean, compact: Boolean = false
) {
    val release = s.selected
    val metadata = s.selectedMetadata
    val title = metadata?.title ?: release?.displayTitle() ?: return
    val id = metadata?.id ?: release!!.id
    val series = when {
        metadata?.kind == "tv" -> metadata
        release?.id == "demo:series" -> Metadata(release.id, title, null, null, kind = "tv")
        else -> null
    }
    val showEpisodes = series != null && (s.config.managed || s.config.metadataProvider == "tmdb" || series.id == "demo:series")
    val about = if (series == null) "О фильме" else "О сериале"
    val tabs = listOfNotNull("Серии".takeIf { showEpisodes }, about, "Раздачи", "Похожие")
    var selectedTab by rememberSaveable(id) { mutableStateOf(tabs.first()) }
    val tab = selectedTab.takeIf { it in tabs } ?: tabs.first()
    val inset = if (tv) tvUnit(48f) else if (!compact && LocalConfiguration.current.screenWidthDp >= 600) 32.dp else 20.dp
    val linkedIds = remember(entries,metadata?.id) {
        entries.filter { metadata != null && it.metadataId == metadata.id }.mapTo(mutableSetOf()) { it.id }
    }
    val titleProgress = remember(progress,linkedIds,release?.id) {
        progress.filter { it.releaseId == release?.id || it.releaseId in linkedIds }
    }
    val saved = titleProgress.filter { it.positionMs > 0 && !it.completed }.maxByOrNull { it.updatedAt }
    val bookmarked = entries.any { it.id == id && it.bookmarked }
    val choose: () -> Unit = { if (release == null) model.findReleases() else model.page("releases") }
    val primaryFocus = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    var focusTab by remember(id) { mutableStateOf(false) }
    var firstFocus by remember(id) { mutableStateOf(true) }
    val header: @Composable () -> Unit = {
        Column {
            DetailHero(
                s, model, title, series != null, bookmarked, saved, tv, compact, inset, primaryFocus, choose
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = inset).padding(top = 12.dp)) {
                if (!tv) {
                    RemoteStatus(model,release?.id.orEmpty(),metadata?.id,onStart=saved?.let { { model.resume(it,onTv=true) } })
                }
                WatchTabs(tabs,tab,tv,selectedFocus=tabFocus,tabTag={ label ->
                    when (label) {
                        "Раздачи" -> if (saved == null) "detail-tab-releases" else "detail-sources"
                        "Серии" -> "detail-tab-episodes"
                        "Похожие" -> "detail-tab-related"
                        else -> "detail-tab-about"
                    }
                }) { next -> if (next != tab) { focusTab = tv; selectedTab = next } }
                HorizontalDivider()
            }
        }
        LaunchedEffect(id,tab) {
            if (tv && firstFocus) {
                firstFocus = false
                primaryFocus.requestFocus()
            } else if (focusTab) {
                focusTab = false
                tabFocus.requestFocus()
            }
        }
    }
    val tabStates = rememberSaveableStateHolder()
    val reveal = remember(id,tab) { Animatable(.76f) }
    LaunchedEffect(reveal) { reveal.animateTo(1f,tween(durationMillis=180)) }
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha=reveal.value
        translationY=(1f-reveal.value)*18.dp.toPx()
    }) { tabStates.SaveableStateProvider(tab) { when {
        tab == "Серии" && series != null -> ManagedEpisodes(series,s.config,model,tv,titleProgress,header=header)
        tab == "Раздачи" -> DetailSources(s,model,tv,inset,header)
        else -> LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom=24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item(key="detail-header") { header() }
            if (tab == "Похожие") {
                val related = s.catalog.filter { it.id != metadata?.id && it.kind == metadata?.kind && it.animation == metadata?.animation }
                if (related.isEmpty()) item {
                    Box(Modifier.padding(horizontal=inset)) {
                        Empty("Похожих пока нет","Попробуйте поиск по названию",tv,onClick={model.page("search")})
                    }
                } else item {
                    val cards: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                        items(related,key={it.id}) { item ->
                            Box(Modifier.width(if(tv) tvUnit(180f) else 156.dp)) {
                                MetadataCard(item,s.config,tv) {model.metadata(item)}
                            }
                        }
                    }
                    if (tv) TvCarousel(inset,content=cards)
                    else LazyRow(contentPadding=PaddingValues(horizontal=inset),horizontalArrangement=Arrangement.spacedBy(12.dp),content=cards)
                }
            } else item {
                Column(
                    Modifier.padding(horizontal=inset).widthIn(max=760.dp),
                    verticalArrangement=Arrangement.spacedBy(12.dp)
                ) {
                    Text("Описание",style=MaterialTheme.typography.titleLarge)
                    Text(metadata?.overview?.takeIf { it.isNotBlank() }
                        ?: if (release?.id?.startsWith("demo:")==true) "Демонстрационный фрагмент MJWatch. Доступно локальное тестовое видео."
                        else "Описание пока недоступно.",
                        color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyLarge)
                    if (release != null && metadata == null && release.title != title) {
                        Text("Исходное название",style=MaterialTheme.typography.titleMedium)
                        Text(release.title,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (saved != null) {
                        Text("Вы остановились на ${time(saved.positionMs)}",modifier=Modifier.testTag("saved-position"))
                        saved.durationMs?.takeIf { it > 0 }?.let { duration ->
                            LinearProgressIndicator(progress={ (saved.positionMs.toFloat()/duration).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    } } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHero(
    state: WatchState, model: WatchModel, title: String, series: Boolean, bookmarked: Boolean,
    saved: Progress?, tv: Boolean, compact: Boolean, inset: Dp, focus: FocusRequester, choose: () -> Unit
) {
    val m = state.selectedMetadata
    val r = state.selected
    val demoArt = r?.id?.startsWith("demo:") == true
    val art = demoArt || (state.config.metadataProvider == "tmdb" && (m?.backdrop != null || m?.poster != null))
    val configuration = LocalConfiguration.current
    val minHeight = when {
        !art -> 0.dp
        tv -> (configuration.screenHeightDp.dp * .59f).coerceAtMost(tvUnit(490f))
        configuration.screenHeightDp < 480 -> 220.dp
        compact -> 325.dp
        configuration.screenWidthDp >= 600 -> (configuration.screenHeightDp.dp * .52f).coerceIn(340.dp,520.dp)
        else -> 365.dp
    }
    WatchTheme(if (art) "dark" else state.config.theme) {
        val colors = MaterialTheme.colorScheme
        Box(Modifier.fillMaxWidth()) {
            if (art) {
                Artwork(m,state.config,Modifier.matchParentSize(),backdrop=true,demo=demoArt)
                ArtScrim(Modifier.matchParentSize())
                ArtScrim(Modifier.matchParentSize(),horizontal=true)
            }
            Column(
                Modifier.fillMaxWidth().heightIn(min=minHeight).padding(horizontal=inset,vertical=16.dp),
                verticalArrangement=Arrangement.SpaceBetween
            ) {
                Row(Modifier.fillMaxWidth().testTag("detail-toolbar"),horizontalArrangement=Arrangement.SpaceBetween) {
                    IconButton(onClick=model::back,modifier=Modifier.size(48.dp).tvFocusRing(tv)
                        .background(colors.surface.copy(alpha=.84f),CircleShape)) {
                        Icon(Icons.Outlined.ArrowBack,"Назад",tint=colors.onSurface)
                    }
                    if (!tv) RemoteCastButton(state,model,art)
                }
                Column(Modifier.widthIn(max=760.dp).padding(top=20.dp,bottom=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    val kind = if (series) "Сериал" else if (m != null || r?.id=="demo:orbit") "Фильм" else "Раздача"
                    Text(kind.uppercase(),fontSize=12.sp,letterSpacing=1.2.sp,fontWeight=FontWeight.Bold,color=colors.primary)
                    Text(title,Modifier.testTag("detail-title").semantics { heading() },fontWeight=FontWeight.Bold,
                        fontSize=if(tv) 42.sp else if(compact) 32.sp else 36.sp,
                        lineHeight=if(tv) 48.sp else if(compact) 38.sp else 42.sp,
                        maxLines=3,overflow=TextOverflow.Ellipsis,color=colors.onSurface)
                    val facts = listOfNotNull(m?.year?.toString(),r?.source?.takeIf { it.isNotBlank() },
                        r?.resolution?.takeUnless { it=="Не указано" }).joinToString(" · ")
                    if (facts.isNotEmpty()) Text(facts,color=colors.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        val episode = saved?.let { episodeNumber(it.path).episode }
                        val label = when {
                            saved == null -> "Выбрать раздачу"
                            series && episode != null -> "Продолжить · серия $episode"
                            else -> "Продолжить с ${time(saved.positionMs)}"
                        }
                        Action(label,tv,Modifier.focusRequester(focus).testTag(if(saved==null)"detail-sources" else "detail-resume"),
                            loading=state.busy && state.page=="detail",icon=Icons.Outlined.PlayArrow) {
                            if (saved == null) choose() else model.resume(saved)
                        }
                        val bookmarkLabel = if (bookmarked) "Убрать из «Буду смотреть»" else "В «Буду смотреть»"
                        val bookmarkIcon = if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder
                        if (tv) {
                            Action(if (bookmarked) "В моём" else "В моё", true,
                                Modifier.testTag("detail-bookmark").semantics { contentDescription = bookmarkLabel },
                                secondary = true, icon = bookmarkIcon, onClick = model::bookmark)
                        } else {
                            FilledTonalIconToggleButton(
                                checked = bookmarked, onCheckedChange = { model.bookmark() },
                                modifier = Modifier.size(52.dp).testTag("detail-bookmark"),
                                shape = RoundedCornerShape(14.dp)
                            ) { Icon(bookmarkIcon, bookmarkLabel) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSources(s: WatchState, model: WatchModel, tv: Boolean, inset: Dp, header: @Composable () -> Unit) {
    val m=s.selectedMetadata
    val release=s.selected
    var candidates by remember(m?.id,release?.id) { mutableStateOf<List<Release>?>(
        if(m==null) listOfNotNull(release) else null
    ) }
    var retry by remember { mutableIntStateOf(0) }
    var error by remember(m?.id,release?.id) { mutableStateOf("") }
    LaunchedEffect(m?.id,release?.id,s.config,retry) {
        if (m==null) return@LaunchedEffect
        error=""
        try {
            val result=model.titleReleases(m.title,refresh=retry>0)
            candidates=(listOfNotNull(release)+result.releases).distinctBy { it.id }
            if (candidates.isNullOrEmpty() && result.failures.isNotEmpty()) error="Источники не ответили."
        } catch (cancel:CancellationException) { throw cancel }
        catch (e:Exception) { error=safeError(e) }
    }
    val showStatus = error.isNotBlank() || candidates.isNullOrEmpty()
    val status: (@Composable () -> Unit)? = if (!showStatus) null else {{
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            when {
                error.isNotEmpty() -> Text(error,color=MaterialTheme.colorScheme.error)
                candidates==null -> LoadingIndicator("Ищем раздачи…")
                else -> Text("Раздачи пока не найдены.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(error.isNotEmpty()||candidates!=null) Action("Повторить поиск",tv,secondary=true) {candidates=null;retry++}
        }
    }}
    val shown = s.copy(results=candidates.orEmpty(),busy=false,releaseSeason=null,releaseEpisode=null,
        episodeDownload=false,episodeTargetId=null)
    ReleasesScreen(shown,model,tv,header,inset,status)
}
