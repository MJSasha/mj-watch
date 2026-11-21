package dev.mjwatch.designsystem

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mjwatch.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun detailContentInset(wide:Boolean,width:Dp):Dp =
    if (wide) (width * .05f).coerceIn(24.dp,72.dp) else 20.dp

@Composable
internal fun DetailScreen(
    s: WatchState, entries: List<LibraryEntry>, progress: List<Progress>,
    model: WatchModel, tv: Boolean, compact: Boolean = false
) {
    val parentColors = MaterialTheme.colorScheme
    val palette = if (parentColors.background.luminance() < .5f) parentColors.copy(
        background=Color(0xff0c1015),surface=Color(0xff202732),onSurface=Color(0xfff0f2ec),
        surfaceContainer=Color(0xff202732),surfaceContainerHigh=Color(0xff202732),onSurfaceVariant=Color(0xffb3bdc8)
    ) else parentColors
    MaterialTheme(colorScheme=palette) {
        BoxWithConstraints(Modifier.fillMaxSize().background(palette.background)) {
            DetailContent(s,entries,progress,model,tv,compact,maxWidth)
        }
    }
}

@Composable
private fun DetailContent(
    s: WatchState, entries: List<LibraryEntry>, progress: List<Progress>,
    model: WatchModel, tv: Boolean, compact: Boolean, panelWidth: Dp
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
    val castTitle = metadata?.takeIf {
        !s.config.demo && s.config.metadataProvider == "tmdb" &&
            (it.id.startsWith("tmdb:movie:") || it.id.startsWith("tmdb:tv:"))
    }
    val tabs = listOfNotNull("Серии".takeIf { showEpisodes }, about, "Раздачи", "Актёры".takeIf { castTitle != null }, "Похожие")
    var selectedTab by rememberSaveable(id) { mutableStateOf(tabs.first()) }
    val tab = selectedTab.takeIf { it in tabs } ?: tabs.first()
    LaunchedEffect(id,s.config) { metadata?.let { model.loadTitleFacts(it,s.config) } }
    val palette=MaterialTheme.colorScheme
    val wide = tv || panelWidth >= 600.dp
    val inset = detailContentInset(wide,panelWidth)
    val linkedIds = remember(entries,metadata?.id) {
        entries.filter { metadata != null && it.metadataId == metadata.id }.mapTo(mutableSetOf()) { it.id }
    }
    val titleProgress = remember(progress,linkedIds,release?.id) {
        progress.filter { it.releaseId == release?.id || it.releaseId in linkedIds }
    }
    val saved = titleProgress.filter { it.positionMs > 0 && !it.completed }.maxByOrNull { it.updatedAt }
    val offline = if (!tv && series == null) {
        val context = LocalContext.current
        key(s.config) {
            val store = remember(context) { OfflineDownloads.get(context) }
            val downloads by remember(store, s.config) { store.observe(s.config) }.collectAsState(initial = emptyList())
            downloads.firstOrNull { item ->
                item.state == OfflineState.READY && item.episode == null &&
                    (item.releaseId == release?.id || metadata?.id?.let { it == item.metadataId } == true ||
                        item.releaseId in linkedIds)
            }
        }
    } else null
    val offlinePosition = offline?.let { item ->
        val key = item.contentKey.ifBlank { "${item.releaseId}|${item.path}" }
        progress.filter {
            it.key == key && it.positionMs > 0 && !it.completed
        }.maxByOrNull { it.updatedAt }
    }
    val bookmarked = entries.any { it.id == id && it.bookmarked }
    val choose: () -> Unit = { if (release == null) model.findReleases() else model.page("releases") }
    val primaryFocus = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    var focusTab by remember(id) { mutableStateOf(false) }
    var firstFocus by remember(id) { mutableStateOf(true) }
    val header: @Composable () -> Unit = {
        Column {
            DetailHero(
                s, model, title, series != null, bookmarked, saved, offline, offlinePosition, tv, compact, wide, panelWidth, inset, primaryFocus, choose
            )
            Column(Modifier.widthIn(max=1200.dp).fillMaxWidth().padding(horizontal=inset).padding(top=if(wide) 24.dp else 20.dp)) {
                if (!tv) {
                    RemoteStatus(model,release?.id.orEmpty(),metadata?.id,onStart=saved?.let { { model.resume(it,onTv=true) } })
                }
                DetailTabs(tabs,tab,tabFocus,tag={ label ->
                    when (label) {
                        "Раздачи" -> if (saved == null) "detail-tab-releases" else "detail-sources"
                        "Серии" -> "detail-tab-episodes"
                        "Актёры" -> "detail-tab-cast"
                        "Похожие" -> "detail-tab-related"
                        else -> "detail-tab-about"
                    }
                }) { next -> if (next != tab) { focusTab = tv; selectedTab = next } }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color=palette.onSurface.copy(alpha=.15f))
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
    }) {
        tabStates.SaveableStateProvider(tab) {
            when {
                tab == "Серии" && series != null -> ManagedEpisodes(series,s.config,model,tv,titleProgress,header=header)
                tab == "Раздачи" -> DetailSources(s,model,tv,inset,header)
                tab == "Актёры" && castTitle != null -> TitleCast(castTitle,s.config,model,tv,inset,header)
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
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHero(
    state: WatchState, model: WatchModel, title: String, series: Boolean, bookmarked: Boolean,
    saved: Progress?, offline: OfflineDownload?, offlinePosition: Progress?, tv: Boolean, compact: Boolean, wide: Boolean, width: Dp, inset: Dp, focus: FocusRequester, choose: () -> Unit
) {
    val context = LocalContext.current
    val player = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val scope = rememberCoroutineScope()
    var offlineError by remember(state.config, state.selectedMetadata?.id, state.selected?.id) { mutableStateOf("") }
    val m = state.selectedMetadata
    val r = state.selected
    val demoArt = r?.id?.startsWith("demo:") == true
    val art = demoArt || (state.config.metadataProvider == "tmdb" && (m?.backdrop != null || m?.poster != null))
    val configuration = LocalConfiguration.current
    val minHeight = when {
        !art -> 0.dp
        configuration.screenHeightDp < 480 -> 220.dp
        compact -> 380.dp
        wide -> (configuration.screenHeightDp.dp * .65f).coerceAtMost(620.dp)
        else -> 390.dp
    }
    val titleSize = if (wide && !compact) (width.value*.06f).coerceIn(42f,78f) else 42f
    val bottom = if(wide && !compact) 44.dp else 28.dp
    val fadeEnd = MaterialTheme.colorScheme.background
    WatchTheme(if (art) "dark" else state.config.theme) {
        val colors = MaterialTheme.colorScheme
        Box(Modifier.fillMaxWidth()) {
            if (art) {
                Artwork(m,state.config,Modifier.matchParentSize(),backdrop=true,demo=demoArt,alignment=BiasAlignment(0f,-.3f))
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                    0f to Color(0xdf070d15), .55f to Color(0x60070d15), 1f to Color.Transparent)))
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                    .66f to fadeEnd.copy(alpha=0f), 1f to fadeEnd)))
            }
            Column(
                Modifier.fillMaxWidth().heightIn(min=minHeight).padding(top=16.dp,bottom=bottom-2.dp),
                verticalArrangement=Arrangement.SpaceBetween
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal=20.dp).testTag("detail-toolbar"),horizontalArrangement=Arrangement.SpaceBetween) {
                    IconButton(onClick=model::back,modifier=Modifier.size(48.dp).tvFocusRing(tv)
                        .background(if(art) Color(0xcf10171c) else colors.surface,CircleShape)) {
                        Icon(Icons.Outlined.ArrowBack,"Назад",tint=colors.onSurface)
                    }
                    if (!tv) RemoteCastButton(state,model,art)
                }
                Column(Modifier.padding(horizontal=inset).widthIn(max=690.dp).padding(top=16.dp),verticalArrangement=Arrangement.spacedBy(if(wide) 12.dp else 9.dp)) {
                    val kind = if (series) "Сериал" else if (m != null || r?.id=="demo:orbit") "Фильм" else "Раздача"
                    Text(kind.uppercase(),fontSize=12.sp,lineHeight=16.sp,letterSpacing=1.55.sp,fontWeight=FontWeight.SemiBold,color=if(art) Color(0xffd7f7b7) else colors.primary)
                    Text(title,Modifier.testTag("detail-title").semantics { heading() },fontWeight=FontWeight.Bold,
                        fontSize=titleSize.sp,lineHeight=(titleSize*1.02f).sp,letterSpacing=(-titleSize*.04f).sp,
                        maxLines=3,overflow=TextOverflow.Ellipsis,color=colors.onSurface)
                    val facts = if (m!=null) listOfNotNull(m.year?.toString(),m.genre?.takeIf { it.isNotBlank() },
                        if(series) "Сериал" else m.runtimeMinutes?.takeIf { it>0 }?.let { "$it мин" } ?: "Фильм")
                    else listOfNotNull(r?.source?.takeIf { it.isNotBlank() && !it.contains("fixture",ignoreCase=true) },
                        r?.resolution?.takeUnless { it=="Не указано" })
                    if (facts.isNotEmpty()) Text(facts.joinToString(" · "),color=if(art) Color(0xffe4e6e9) else colors.onSurfaceVariant,fontSize=16.sp,lineHeight=23.sp)
                    FlowRow(Modifier.padding(top=5.dp),horizontalArrangement=Arrangement.spacedBy(if(wide) 8.dp else 6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        val episode = saved?.let { episodeNumber(it.path).episode }
                        val label = when {
                            offlinePosition != null -> "Продолжить скачанное с ${time(offlinePosition.positionMs)}"
                            offline != null -> "Смотреть скачанное"
                            saved == null -> "Выбрать раздачу"
                            series && episode != null -> "Продолжить · серия $episode"
                            else -> "Продолжить с ${time(saved.positionMs)}"
                        }
                        val primaryTag = when {
                            offline != null -> "detail-offline"
                            saved != null -> "detail-resume"
                            else -> "detail-sources"
                        }
                        val primaryModifier = Modifier.focusRequester(focus).testTag(primaryTag)
                        val loading = offline == null && state.busy && state.page == "detail"
                        val play = {
                            if (offline != null) {
                                offlineError = ""
                                scope.launch {
                                    try {
                                        val intent = offlinePlaybackIntent(context, state.config, offline, mobile = true)
                                        if (model.state.value.config == state.config) player.launch(intent)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        offlineError = safeError(e)
                                    }
                                }
                            } else if (saved == null) choose() else model.resume(saved)
                            Unit
                        }
                        if (tv) {
                            TvActionButton(label, primaryModifier, icon = Icons.Outlined.PlayArrow, loading = loading, onClick = play)
                        } else {
                            HeroAction(label, Icons.Outlined.PlayArrow, primaryModifier, primary = true, wide = wide, loading = loading, onClick = play)
                        }
                        val bookmarkLabel = if (bookmarked) "Убрать из «Буду смотреть»" else "В «Буду смотреть»"
                        val bookmarkIcon = if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder
                        HeroAction(if(wide || compact) { if(bookmarked) "В моём" else "В моё" } else null,bookmarkIcon,
                            Modifier.testTag("detail-bookmark").semantics {contentDescription=bookmarkLabel},
                            primary=false,wide=wide,onClick=model::bookmark)
                    }
                    if (offlineError.isNotEmpty()) OperationMessage(offlineError, error = true)
                }
            }
        }
    }
}

@Composable
private fun HeroAction(
    text:String?, icon:ImageVector, modifier:Modifier, primary:Boolean, wide:Boolean,
    loading:Boolean=false, onClick:()->Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val foreground = if(primary) colors.onPrimary else colors.onSurface
    val background = if(primary) colors.primary else if(colors.background.luminance()<.5f) Color(0xe628313a) else colors.surface
    Box(modifier.heightIn(min=48.dp).widthIn(min=48.dp).clip(shape).tvFocusRing(true,shape)
        .clickable(enabled=!loading,role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center) {
        Row(Modifier.heightIn(min=44.dp).widthIn(min=44.dp).background(background,shape)
            .padding(horizontal=if(text==null) 10.dp else if(wide) 16.dp else 12.dp, vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            if(loading) CircularProgressIndicator(Modifier.size(20.dp),color=foreground,strokeWidth=2.dp)
            else Icon(icon,null,Modifier.size(22.dp),tint=foreground)
            if(text!=null) Text(text,color=foreground,fontSize=16.sp,lineHeight=22.sp,
                fontWeight=if(primary) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

@Composable
private fun DetailTabs(labels:List<String>,current:String,focus:FocusRequester,tag:(String)->String,onSelect:(String)->Unit) {
    val colors=MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
        labels.forEach {label ->
            val active=label==current
            Box(Modifier.heightIn(min=48.dp).testTag(tag(label))
                .then(if(active)Modifier.focusRequester(focus) else Modifier)
                .tvFocusRing(true,RoundedCornerShape(6.dp)).selectable(active,role=Role.Tab,onClick={onSelect(label)})) {
                Text(label,Modifier.align(Alignment.Center).padding(horizontal=4.dp,vertical=10.dp),
                    color=if(active) colors.onSurface else colors.onSurfaceVariant,fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium)
                if(active) Box(Modifier.matchParentSize(),contentAlignment=Alignment.BottomCenter) {
                    HorizontalDivider(thickness=2.dp,color=colors.primary)
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
