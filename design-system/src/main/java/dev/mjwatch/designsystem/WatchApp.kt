@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.mjwatch.designsystem

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.ImageLoader
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Destination(val id:String,val title:String,val icon:ImageVector)
internal val LocalImages=staticCompositionLocalOf<ImageLoader> {error("Image loader unavailable")}
private val destinations=listOf(Destination("home","Главная",Icons.Filled.Home),Destination("search","Поиск",Icons.Outlined.Search),Destination("library","Моё",Icons.Filled.Bookmarks),Destination("settings","Настройки",Icons.Filled.Settings))

@Composable fun WatchApp(model:WatchModel,tv:Boolean,scanTvQr:(()->Unit)?=null) {
    AutomaticUpdates(model.updates)
    val state by model.state.collectAsStateWithLifecycle()
    if(state.legacyHistoryRecords>0) AlertDialog(onDismissRequest={},title={Text("Прежняя локальная история")},text={Text("Найдено ${state.legacyHistoryRecords} записей открытий и позиций. В direct история скрыта и больше не обновляется. Импорт на сервер доступен отдельным действием.")},confirmButton={TextButton(onClick={model.managed.direct(false);model.legacyHandled()}){Text("Сохранить отдельно")}},dismissButton={TextButton(onClick={model.managed.direct(true);model.legacyHandled()}){Text("Удалить локальную историю")}})
    val snackbar=remember {SnackbarHostState()}
    LaunchedEffect(state.removed) {state.removed?.let {entry->
        if(snackbar.showSnackbar("Удалено из локальной библиотеки","Отменить",duration=SnackbarDuration.Long)==SnackbarResult.ActionPerformed) model.undo(entry,state.removedBookmarks) else model.clearUndo()
    }}
    val snapshot by model.scopedLibrary.collectAsStateWithLifecycle()
    val library=if(snapshot.context==state.config.profileKey)snapshot.entries else emptyList()
    val progress=if(snapshot.context==state.config.profileKey)snapshot.positions else emptyList()
    val titles=if(snapshot.context==state.config.profileKey)snapshot.titles else emptyList()
    var editorBack by remember {mutableStateOf<(() -> Unit)?>(null)}
    val saved=rememberSaveableStateHolder();val searchScroll=rememberLazyListState()
    val context=LocalContext.current.applicationContext
    val images=remember(state.config) {ImageLoader.Builder(context).okHttpClient(if(state.config.managed) managedClient(state.config,model.secrets) else guardedClient(state.config)).build()}
    DisposableEffect(images) {onDispose {images.shutdown()}}
    CompositionLocalProvider(LocalImages provides images,androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides if(tv) TVBringIntoView else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current) {WatchTheme(state.config.theme) {
        val colors=MaterialTheme.colorScheme
        val activity=androidx.activity.compose.LocalActivity.current
        SideEffect {activity?.let {a->WindowCompat.getInsetsController(a.window,a.window.decorView).apply {isAppearanceLightStatusBars=colors.background.luminance()>.5;isAppearanceLightNavigationBars=colors.background.luminance()>.5};if(android.os.Build.VERSION.SDK_INT>=29) a.window.isNavigationBarContrastEnforced=false}}
        androidx.tv.material3.MaterialTheme(colorScheme=androidx.tv.material3.darkColorScheme(primary=colors.primary,onPrimary=colors.onPrimary,surface=colors.surface,onSurface=colors.onSurface,surfaceVariant=colors.surfaceVariant,onSurfaceVariant=colors.onSurfaceVariant)) {
            val width=LocalConfiguration.current.screenWidthDp
            val rail=tv||width>=600
            val railItems=if(rail) listOf(destinations[0],destinations[1],Destination("history","История",Icons.Outlined.History),destinations[2],destinations[3]) else destinations
            val top=state.page in (destinations.map {it.id}+"history")
            val cinematic=state.page in setOf("home","detail")
            val railFocus=remember {FocusRequester()};var railFocused by remember {mutableStateOf(false)}
            BackHandler(tv&&state.page=="home"&&!railFocused&&state.configured) {railFocus.requestFocus()}
            val imeVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
            BackHandler(state.page!="home"&&!imeVisible) {model.back()}
            Scaffold(containerColor=colors.background,snackbarHost={SnackbarHost(snackbar)},bottomBar={if(!rail&&top&&state.configured) {
                Column(Modifier.background(colors.surface).windowInsetsPadding(WindowInsets.navigationBars)) {
                    NavigationBar(Modifier.height(if(LocalConfiguration.current.fontScale>=1.5f) 96.dp else 74.dp),containerColor=colors.surface,tonalElevation=0.dp,windowInsets=WindowInsets(0)) {
                        destinations.forEach {d->NavigationBarItem(selected=state.page==d.id,onClick={model.page(d.id)},colors=NavigationBarItemDefaults.colors(selectedIconColor=colors.primary,selectedTextColor=colors.onSurface,unselectedIconColor=colors.onSurfaceVariant,unselectedTextColor=colors.onSurfaceVariant,indicatorColor=colors.primaryContainer),icon={Icon(d.icon,null,Modifier.size(24.dp))},label={Text(d.title,fontSize=11.sp)})}
                    }
                }
            }}) {padding->
                Row(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding()) {
                    if(rail&&state.configured) Column(Modifier.width(if(tv) tvUnit(112f) else 88.dp).fillMaxHeight().background(colors.background).drawBehind {val x=size.width-1f;drawLine(colors.onSurface.copy(alpha=.06f),androidx.compose.ui.geometry.Offset(x,0f),androidx.compose.ui.geometry.Offset(x,size.height),1f)}.padding(top=if(tv) tvUnit(28f) else 24.dp,bottom=24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("MJ",color=colors.primary,fontSize=if(tv) 23.sp else 26.sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(if(tv) 24.dp else 28.dp))
                        railItems.forEach {d->
                            if(d.id=="settings") Spacer(Modifier.weight(1f))
                            val selected=if(state.page.startsWith("settings-")||state.page=="connections") d.id=="settings" else state.page==d.id||(!top&&state.originPage==d.id)
                            RailButton(tv,d.icon,d.title,selected,Modifier.padding(vertical=6.dp).then(if(tv) Modifier.size(width=tvUnit(96f),height=72.dp) else Modifier.size(48.dp)).then(if(d.id=="home") Modifier.focusRequester(railFocus) else Modifier).onFocusChanged {railFocused=it.isFocused}) {model.page(d.id)}
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if(!(tv&&(cinematic||state.page=="search"))&&state.page!="detail"&&!(state.page=="releases"&&!tv)&&!(tv&&state.page=="settings"&&!state.configured)) {
                            Row(Modifier.fillMaxWidth().heightIn(min=if(tv) 76.dp else 64.dp).padding(horizontal=if(tv) tvUnit(40f) else 20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                if(!top) IconButton(onClick={editorBack?.invoke() ?: model.back()},modifier=Modifier.tvFocusRing(tv).background(colors.surface,RoundedCornerShape(24.dp))) {Icon(Icons.Outlined.ArrowBack,"Назад")}
                                val title=when(state.page) {"search"->"Поиск";"library"->"Моё";"history"->"История";"settings"->if(state.configured) "Настройки" else "Подключения";"connections"->"Подключения";"settings-search"->"Jackett / JacRed";"settings-server"->"TorrServer";"settings-catalog"->"Каталог";"settings-updates"->"Обновление MJWatch";"files"->"Выберите файл";"preparing"->"Подготовка";"releases"->"Выберите раздачу";else->""}
                                if(state.page=="home") Box(Modifier.weight(1f)) {Wordmark()} else Text(title,fontSize=if(tv) 30.sp else 24.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                                if(state.page=="home") IconButton(onClick={model.page("settings")},modifier=Modifier.tvFocusRing(tv).background(colors.surface,RoundedCornerShape(24.dp))) {Icon(Icons.Outlined.Dns,"Подключения")}
                                else if(state.config.demo&&state.page!="settings"&&state.page!="connections"&&!state.page.startsWith("settings-")) Badge("ДЕМО",accent=true)
                            }
                        }
                        val inset=if(tv) tvUnit(40f) else 20.dp
                        val edge=state.page in setOf("home","detail")||state.page=="releases"&&!tv||state.page=="settings"&&!state.configured
                        val screenFocus=remember {FocusRequester()}
                        LaunchedEffect(state.page,tv) {
                            if(tv&&state.page in setOf("connection-choice","managed-login","connections","settings-search","settings-server","settings-catalog")) {withFrameNanos {};screenFocus.requestFocus()}
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().focusRequester(screenFocus).focusGroup().then(if(edge) Modifier else Modifier.padding(horizontal=inset))) {
                            saved.SaveableStateProvider(state.page+when(state.page) {"detail"->state.selectedMetadata?.id ?: state.selected?.id.orEmpty();"releases"->state.query+":${state.releaseSeason}:${state.releaseEpisode}";"files"->state.selected?.id.orEmpty()+state.config.torrServer+":${state.releaseSeason}:${state.releaseEpisode}";else->""}) {
                                when(state.page) {
                                    "invitation-register"->InvitationScreen(model,tv)
                                    "home"->HomeScreen(state,titles,progress,model,tv)
                                    "settings"->if(state.configured) SettingsOverview(state,model,tv) else ServerScreen(state.copy(page="connection-choice"),model,tv,registerBack={editorBack=it})
                                    "managed-account","managed-devices","managed-tv"->AccountScreen(state,model,tv,scanTvQr)
                                    "server-users","server-user","server-user-create","server-user-invite","server-user-devices"->UsersScreen(state,model,tv)
                                    "connection-choice","managed-login","server-overview","server-tmdb","server-history","server-retention","server-torr","server-install","server-maintenance","server-update"->ServerScreen(state,model,tv,registerBack={editorBack=it})
                                    "connections"->SettingsScreen(state,model,tv,registerBack={editorBack=it})
                                    "settings-search"->SettingsScreen(state,model,tv,showIntro=false,section="search",registerBack={editorBack=it})
                                    "settings-server"->if(state.config.managed) ServerScreen(state.copy(page="server-overview"),model,tv,registerBack={editorBack=it}) else SettingsScreen(state,model,tv,showIntro=false,section="server",registerBack={editorBack=it})
                                    "settings-catalog"->if(state.config.managed) Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)){Text("Каталог TMDB через сервер MJWatch. Ключ хранится у владельца сервера.");Action("Аккаунт и сервер",tv){model.page("managed-account")};Action("Проверить каталог",tv){model.refreshCatalog(true)};Text(state.catalogMessage)} else SettingsScreen(state,model,tv,showIntro=false,section="catalog",registerBack={editorBack=it})
                                    "settings-updates"->LazyColumn(Modifier.testTag("settings-list"),contentPadding=PaddingValues(vertical=16.dp)) {item {UpdateSection(model.updates,tv)}}
                                    "search"->SearchScreen(state,model,tv,searchScroll)
                                    "library","history"->LibraryScreen(titles,progress,model,tv)
                                    "detail"->if(!tv&&width>=840&&state.originPage=="search") Row(Modifier.fillMaxSize()) {
                                        Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {Heading("Поиск");Spacer(Modifier.height(16.dp));SearchScreen(state,model,false,searchScroll)}
                                        VerticalDivider()
                                        Box(Modifier.weight(1f).fillMaxHeight().background(colors.surface)) {DetailScreen(state,library,progress,model,false,compact=true)}
                                    } else DetailScreen(state,library,progress,model,tv)
                                    "releases"->if(!tv&&width<840) {
                                        Box(Modifier.clearAndSetSemantics {}) {DetailScreen(state,library,progress,model,false)}
                                        ModalBottomSheet(onDismissRequest={model.back()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=colors.surface,dragHandle={BottomSheetDefaults.DragHandle()}) {
                                            Box(Modifier.fillMaxWidth().fillMaxHeight(if(LocalConfiguration.current.fontScale>=1.5f) 1f else .88f).navigationBarsPadding().padding(horizontal=20.dp)) {ReleasesScreen(state,model,false)}
                                        }
                                    } else ReleasesScreen(state,model,tv)
                                    "files"->FilesScreen(state,model,tv)
                                    "preparing"->PreparingScreen(state,model,tv)
                                }
                            }
                        }
                    }
                }
            }
        }
    }}
}
@Composable private fun Heading(text:String) {Text(text,fontSize=26.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics {heading()})}
@Composable internal fun Action(text:String,tv:Boolean,modifier:Modifier=Modifier,enabled:Boolean=true,secondary:Boolean=false,icon:ImageVector?=null,onClick:()->Unit) {
    val colors=MaterialTheme.colorScheme
    val content:@Composable ()->Unit={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {if(icon!=null) Icon(icon,null,Modifier.size(20.dp),tint=if(!enabled) colors.onSurface.copy(alpha=.38f) else if(secondary) colors.onSurface else colors.onPrimary);Text(text,fontSize=16.sp,fontWeight=FontWeight.Medium,color=if(!enabled) colors.onSurface.copy(alpha=.38f) else if(secondary) colors.onSurface else colors.onPrimary)}}
    if(tv) androidx.tv.material3.Button(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=48.dp),shape=androidx.tv.material3.ButtonDefaults.shape(shape=RoundedCornerShape(26.dp)),scale=androidx.tv.material3.ButtonDefaults.scale(focusedScale=1f,pressedScale=1f),colors=androidx.tv.material3.ButtonDefaults.colors(containerColor=if(secondary) colors.surfaceContainerHigh else colors.primary,contentColor=if(secondary) colors.onSurface else colors.onPrimary,focusedContainerColor=if(secondary) colors.primaryContainer else colors.primary,focusedContentColor=if(secondary) colors.primary else colors.onPrimary),border=androidx.tv.material3.ButtonDefaults.border(focusedBorder=androidx.tv.material3.Border(BorderStroke(3.dp,colors.onSurface),inset=(-6).dp,shape=RoundedCornerShape(26.dp)))) {content()}
    else Button(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=52.dp),shape=RoundedCornerShape(26.dp),colors=ButtonDefaults.buttonColors(containerColor=if(secondary) colors.surfaceContainerHigh else colors.primary,contentColor=if(secondary) colors.onSurface else colors.onPrimary),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp)) {content()}
}
@Composable private fun Empty(title:String,body:String,tv:Boolean,action:String="Открыть поиск",onClick:()->Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical=36.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.surface,RoundedCornerShape(22.dp)),contentAlignment=Alignment.Center) {Icon(Icons.Outlined.SearchOff,null,Modifier.size(36.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
        Heading(title);Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=16.sp,lineHeight=24.sp)
        Action(action,tv,if(tv) Modifier else Modifier.fillMaxWidth(),icon=Icons.Outlined.Search,onClick=onClick)
    }
}
@Composable private fun ReleaseCard(release:Release,tv:Boolean,focus:Boolean=false,selected:Boolean=false,onLongClick:(()->Unit)?=null,onClick:()->Unit) {
    val requester=remember {FocusRequester()}
    LaunchedEffect(focus) {if(tv&&focus) {delay(100);requester.requestFocus()}}
    NativeCard(tv,Modifier.fillMaxWidth().focusRequester(requester),selected,onLongClick,onClick) {
        Column(Modifier.fillMaxWidth().padding(if(tv) 18.dp else 16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(release.title,fontSize=if(tv) 17.sp else 16.sp,lineHeight=22.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(release.resolution!="Не указано") Badge(release.resolution)
                Regex("(?i)(HEVC|H[ .]?265|x265|H[ .]?264|x264|AV1)").find(release.title)?.value?.let {Badge(it.uppercase())}
                Regex("(?i)(HDR10\\+?|HDR|SDR)").find(release.title)?.value?.let {Badge(it.uppercase())}
                Badge(sizeLabel(release.size))
                Badge(release.seeds?.let {"$it сидов"} ?: "Сиды не указаны",accent=(release.seeds ?: 0)>0,warning=release.seeds==0)
            }
            Text(release.source,fontSize=if(tv) 16.sp else 13.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun HomeScreen(s:WatchState,entries:List<LibraryTitle>,progress:List<Progress>,model:WatchModel,tv:Boolean) {
    val category=s.catalogSection.label;var featured by remember(s.catalogSection) {mutableStateOf<Metadata?>(null)}
    val catalogue=s.catalog
    val hero=catalogue.firstOrNull {it.id==featured?.id} ?: catalogue.firstOrNull()
    val cinematic=hero!=null||s.config.demo
    val inset=if(tv) tvUnit(40f) else 20.dp
    val heroFocus=remember {FocusRequester()}
    LaunchedEffect(s.focusKey,cinematic) {if(tv&&cinematic&&(s.focusKey.isEmpty()||s.focusKey.startsWith("hero:"))) {delay(100);heroFocus.requestFocus()}}
    WatchTheme(if(tv&&cinematic) "dark" else s.config.theme) {CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if(tv&&cinematic) Box(Modifier.fillMaxWidth().height(tvUnit(475f))) {Artwork(hero,s.config,Modifier.fillMaxSize(),backdrop=true,demo=hero==null&&s.config.demo);ArtScrim(Modifier.fillMaxSize(),true);ArtScrim(Modifier.fillMaxSize())}
        LazyColumn(Modifier.testTag("home-list"),verticalArrangement=Arrangement.spacedBy(if(tv) 12.dp else 16.dp),contentPadding=PaddingValues(bottom=24.dp)) {
            if(s.config.metadataProvider=="tmdb"||s.config.demo) item {
                if(tv) {
                    Box(Modifier.fillMaxWidth().heightIn(min=tvUnit(if(cinematic)390f else 100f)).padding(horizontal=inset)) {
                        Row(Modifier.fillMaxWidth().padding(top=tvUnit(30f)),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            WatchTabs(CatalogSection.entries.map {it.label},category,true) {label->model.catalogSection(CatalogSection.entries.first {it.label==label})}
                            if(s.config.demo) Badge("ДЕМО",true)
                        }
                        if(cinematic)Column(Modifier.align(Alignment.BottomStart).padding(bottom=tvUnit(26f)),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(if(s.config.demo) "ДЕМОНСТРАЦИЯ" else "ВАШ СЛЕДУЮЩИЙ ВЕЧЕР",fontSize=11.sp,letterSpacing=1.4.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(hero?.title ?: "Тихая орбита",fontSize=40.sp,lineHeight=48.sp,fontWeight=FontWeight.Bold)
                            Text(hero?.let {"${it.year ?: "Год не указан"} · ${titleKind(it)}"} ?: "Локальное тестовое видео",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                Action(if(hero?.kind=="tv") "О сериале" else "О фильме",true,Modifier.testTag("hero-open").focusRequester(heroFocus),icon=Icons.Outlined.PlayArrow) {if(hero!=null) model.metadata(hero,"hero:${hero.id}") else model.open(DemoReleases.items.first(),"hero:${DemoReleases.items.first().id}")}
                                Action("В моё",true,secondary=true,icon=Icons.Outlined.BookmarkAdd) {if(hero!=null) model.metadata(hero,"hero:${hero.id}") else model.open(DemoReleases.items.first());model.bookmark()}
                            }
                        }
                    }
                } else {
                    Column(Modifier.padding(horizontal=inset),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {CatalogSection.entries.forEach {section->Pill(section.label,s.catalogSection==section) {model.catalogSection(section)}}}
                        if(cinematic)Box(Modifier.fillMaxWidth().heightIn(min=374.dp).clip(RoundedCornerShape(24.dp))) {
                            Artwork(hero,s.config,Modifier.matchParentSize(),backdrop=true,demo=hero==null&&s.config.demo);ArtScrim(Modifier.matchParentSize())
                            Column(Modifier.align(Alignment.BottomStart).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(if(s.config.demo) "ДЕМОНСТРАЦИЯ" else "НА ВЕЧЕР",fontSize=11.sp,letterSpacing=1.5.sp,color=androidx.compose.ui.graphics.Color(0xFFDCE1DD))
                                Text(hero?.title ?: "Тихая орбита",fontSize=35.sp,lineHeight=40.sp,fontWeight=FontWeight.Bold,color=androidx.compose.ui.graphics.Color.White)
                                Text(hero?.let {"${it.year ?: "Год не указан"} · ${titleKind(it)}"} ?: "Локальное тестовое видео",fontSize=13.sp,color=androidx.compose.ui.graphics.Color(0xFFDCE1DD))
                                Action(if(hero?.kind=="tv") "О сериале" else "О фильме",false,Modifier.fillMaxWidth().padding(top=10.dp).testTag("hero-open"),icon=Icons.Outlined.PlayArrow) {if(hero!=null) model.metadata(hero,"hero:${hero.id}") else model.open(DemoReleases.items.first(),"hero:${DemoReleases.items.first().id}")}
                            }
                        }
                    }
                }
            }
            if(!cinematic&&s.config.metadataProvider!="tmdb") item {Box(Modifier.padding(horizontal=inset)) {Empty("Кино. На вашем экране.","Найдите раздачу по названию. Каталог и постеры можно подключить в настройках.",tv,"Поиск раздач") {model.page("search")}}}
            if(s.catalogBusy&&s.catalog.isEmpty()&&!s.config.demo) item {Box(Modifier.padding(horizontal=inset).fillMaxWidth().height(240.dp).background(MaterialTheme.colorScheme.surface,RoundedCornerShape(24.dp)),contentAlignment=Alignment.Center) {CircularProgressIndicator()}}
            if(!s.catalogBusy&&catalogue.isEmpty()&&s.config.metadataProvider=="tmdb"&&!s.config.demo) item {
                Column(Modifier.padding(horizontal=inset),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Heading(if(s.catalogMessage.isNotBlank())"Не удалось загрузить раздел" else "В разделе пока нет карточек")
                    Text("Раздел «$category». Попробуйте обновить каталог или найдите название через поиск.")
                    Action("Обновить раздел",tv){model.refreshCatalog(true)}
                    Action("Поиск",tv,secondary=true){model.page("search")}
                }
            }
            val continuing=entries.filter {e->e.entry.openedAt>0&&progress.any {it.releaseId in e.releaseIds&&!it.completed&&it.positionMs>0}}
            if(continuing.isNotEmpty()) {
                item {Box(Modifier.padding(horizontal=inset)) {SectionTitle(if(tv) "Продолжить просмотр" else "Продолжить",if(tv) "Вся история" else "") {model.page("history")}}}
                if(tv) item {LazyRow(contentPadding=PaddingValues(horizontal=inset),horizontalArrangement=Arrangement.spacedBy(14.dp)) {items(continuing,key={it.entry.id}) {e->Box(Modifier.width(tvUnit(254f))) {LibraryCard(e,progress,model,true,wide=true)}}}}
                else items(continuing.take(3),key={it.entry.id}) {e->Box(Modifier.padding(horizontal=inset)) {LibraryCard(e,progress,model,false)}}
            }
            if(catalogue.isNotEmpty()) {
                item {Box(Modifier.padding(horizontal=inset)) {SectionTitle("Ваши подборки")}}
                item {LazyRow(contentPadding=PaddingValues(horizontal=inset),horizontalArrangement=Arrangement.spacedBy(16.dp)) {items(catalogue,key={it.id}) {m->Box(Modifier.width(if(tv) tvUnit(180f) else 156.dp)) {MetadataCard(m,s.config,tv,s.focusKey==m.id,onFocus={featured=m}) {model.metadata(m)}}}}}
            }
            if(s.config.demo) {
                item {Box(Modifier.padding(horizontal=inset)) {SectionTitle("Тестовые раздачи")}}
                items(DemoReleases.items,key={it.id}) {r->Box(Modifier.padding(horizontal=inset)) {ReleaseCard(r,tv,s.focusKey==r.id) {model.open(r)}}}
            }
            if(s.catalogMessage.isNotBlank()&&!s.config.demo) item {Text(s.catalogMessage,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=inset))}
            item {Box(Modifier.padding(horizontal=inset)) {UpdateSection(model.updates,tv,compact=true)}}
            if(tv) item {Text("OK · Открыть     Удерживать OK · Действия",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.fillMaxWidth().padding(horizontal=inset),textAlign=androidx.compose.ui.text.style.TextAlign.End)}
        }
    }
}}}
@Composable private fun SearchScreen(s:WatchState,model:WatchModel,tv:Boolean,listState:LazyListState) {
    val keyboard=LocalSoftwareKeyboardController.current
    val fieldFocus=remember {FocusRequester()}
    var resultTab by rememberSaveable {mutableStateOf("Каталог")}
    val context=LocalContext.current
    val voice=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result -> if(result.resultCode==android.app.Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(model::search)}
    val voiceAvailable=remember {context.packageManager.resolveActivity(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),0)!=null}
    Column(Modifier.fillMaxSize().padding(top=if(tv) 24.dp else 0.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    OutlinedTextField(value=s.query,onValueChange=model::search,label={Text("Название фильма или раздачи")},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={Row {if(s.query.isNotEmpty()) IconButton(onClick={model.search("")}) {Icon(Icons.Outlined.Close,"Очистить поиск")};if(voiceAvailable) IconButton(onClick={voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU"))}) {Icon(Icons.Outlined.Mic,"Голосовой поиск")}}},singleLine=true,
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide(); model.search(s.query)}),modifier=Modifier.fillMaxWidth().focusRequester(fieldFocus),shape=RoundedCornerShape(28.dp))
    Spacer(Modifier.height(4.dp))
    if(s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if(s.metadata.isNotEmpty()) WatchTabs(listOf("Каталог","Раздачи"),resultTab,tv) {resultTab=it}
    LazyColumn(Modifier.weight(1f),state=listState,verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=16.dp)) {
        if(s.failures.isNotEmpty()) item { Text("Не ответили источники: ${s.failures.joinToString("; ")}",color=MaterialTheme.colorScheme.error); Action("Повторить недоступные источники",tv) {model.loadMore(true)} }
        if(!s.busy&&s.query.length>=2&&s.results.isEmpty()&&s.metadata.isEmpty()) item {Empty(if(s.message.startsWith("Ничего")) "Ничего не найдено" else "Поиск недоступен",s.message.ifBlank {"Попробуйте другое название или проверьте подключение поиска."},tv,"Изменить запрос") {model.search("");fieldFocus.requestFocus();keyboard?.show()}}
        else if(s.message.isNotEmpty()&&s.metadata.isEmpty()) item {Text(s.message,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
        if(s.query.length<2) item { Text("Введите не менее двух символов. Поиск раздач работает без каталога.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(s.metadata.isNotEmpty()) item {SectionTitle("Результаты поиска");if(s.metadataCached) Text("Данные из кеша")}
        if(resultTab=="Каталог") items(s.metadata.chunked(if(tv) 4 else 2),key={it.joinToString {m->m.id}}) {row -> Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            row.forEach {m -> Column(Modifier.weight(1f)) {MetadataCard(m,s.config,tv,s.focusKey==m.id) {keyboard?.hide();model.metadata(m)}}}
            repeat((if(tv) 4 else 2)-row.size) {Spacer(Modifier.weight(1f))}
        }}
        if(s.metadata.isEmpty()||resultTab=="Раздачи") items(s.results,key={it.id}) { r -> ReleaseCard(r,tv,s.focusKey==r.id) { keyboard?.hide(); model.open(r) } }
        if(s.more&&!s.busy) item {Action("Загрузить ещё",tv) {model.loadMore()} }
    }
}
}
@Composable private fun MetadataImage(m:Metadata,c:Config,modifier:Modifier=Modifier,backdrop:Boolean=false) {
    val loader=LocalImages.current
    val uri=if(c.metadataProvider=="tmdb") if(backdrop) m.backdrop ?: m.poster else m.poster else null
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) {
        Icon(Icons.Outlined.Movie,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        if(uri!=null) key(m.id,uri) {AsyncImage(model=uri,imageLoader=loader,contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
    }
}
@Composable private fun MetadataCard(m:Metadata,c:Config,tv:Boolean,restoreFocus:Boolean=false,onFocus:(()->Unit)?=null,onClick:()->Unit) {
    val requester=remember {FocusRequester()};var focused by remember {mutableStateOf(false)}
    LaunchedEffect(restoreFocus) {if(tv&&restoreFocus) {delay(100);requester.requestFocus()}}
    LaunchedEffect(focused,m.id) {if(focused) {delay(180);onFocus?.invoke()}}
    val modifier=Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged {focused=it.hasFocus}
    val content:@Composable ()->Unit={Column {
        MetadataImage(m,c,Modifier.fillMaxWidth().aspectRatio(2f/3).clip(RoundedCornerShape(16.dp)))
        Column(Modifier.padding(vertical=10.dp,horizontal=4.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {Text(m.title,fontWeight=FontWeight.Bold,maxLines=2);Text("${m.year ?: "Год не указан"} · ${if(m.kind=="tv") "Сериал" else "Фильм"}",color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }}
    if(tv) androidx.tv.material3.Card(onClick=onClick,modifier=modifier,scale=androidx.tv.material3.CardDefaults.scale(focusedScale=1f),colors=androidx.tv.material3.CardDefaults.colors(containerColor=MaterialTheme.colorScheme.background,focusedContainerColor=MaterialTheme.colorScheme.background),border=androidx.tv.material3.CardDefaults.border(focusedBorder=androidx.tv.material3.Border(BorderStroke(2.dp,MaterialTheme.colorScheme.primary),inset=(-4).dp))) {content()}
    else Card(onClick=onClick,modifier=modifier,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.background)) {content()}
}
@Composable private fun DetailScreen(s:WatchState,entries:List<LibraryEntry>,progress:List<Progress>,model:WatchModel,tv:Boolean,compact:Boolean=false) {
    val r=s.selected;val m=s.selectedMetadata
    if(r==null&&m==null) return
    val title=m?.title ?: r!!.displayTitle()
    val id=m?.id ?: r!!.id
    val linked=entries.filter {m!=null&&it.metadataId==m.id}.map {it.id}.toSet()
    val saved=progress.filter {it.positionMs>0&&!it.completed&&(it.releaseId==r?.id||it.releaseId in linked)}.maxByOrNull {it.updatedAt}
    val bookmarked=entries.any {it.id==id&&it.bookmarked}
    var tab by rememberSaveable {mutableStateOf("О фильме")}
    val inset=if(tv) tvUnit(40f) else 20.dp
    val primaryFocus=remember {FocusRequester()}
    LaunchedEffect(id) {if(tv) {delay(100);primaryFocus.requestFocus()}}
    var bottomHeight by remember {mutableIntStateOf(0)}
    val bottomSpace=with(LocalDensity.current) {bottomHeight.toDp()}
    val hasArt=(m!=null&&s.config.metadataProvider=="tmdb"&&(m.backdrop!=null||m.poster!=null))||r?.id?.startsWith("demo:")==true
    val choose:()->Unit={if(r==null) model.findReleases() else model.page("releases")}
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom=if(tv||compact) 24.dp else bottomSpace+16.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min=if(!hasArt) 0.dp else if(tv) tvUnit(420f) else if(compact) 330.dp else 390.dp)) {
                if(hasArt) {Artwork(m,s.config,Modifier.matchParentSize(),backdrop=true,demo=r?.id?.startsWith("demo:")==true);ArtScrim(Modifier.matchParentSize());if(tv) ArtScrim(Modifier.matchParentSize(),true)}
                Column(Modifier.fillMaxWidth().heightIn(min=if(!hasArt) 0.dp else if(tv) tvUnit(420f) else if(compact) 330.dp else 390.dp)) {
                Row(Modifier.fillMaxWidth().padding(inset).testTag("detail-toolbar"),horizontalArrangement=Arrangement.SpaceBetween) {
                    IconButton(onClick={model.back()},modifier=Modifier.tvFocusRing(tv).background(if(hasArt) MaterialTheme.colorScheme.background.copy(alpha=.7f) else MaterialTheme.colorScheme.surface,RoundedCornerShape(24.dp))) {Icon(Icons.Outlined.ArrowBack,"Назад")}
                    IconButton(onClick={model.bookmark()},modifier=Modifier.tvFocusRing(tv).background(if(hasArt) MaterialTheme.colorScheme.background.copy(alpha=.7f) else MaterialTheme.colorScheme.surface,RoundedCornerShape(24.dp))) {Icon(if(bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,if(bookmarked) "Убрать из «Буду смотреть»" else "В «Буду смотреть»",tint=if(bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)}
                }
                Spacer(Modifier.weight(1f))
                if(!compact) Column(Modifier.padding(inset),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(title,modifier=Modifier.testTag("detail-title"),fontSize=if(tv) 40.sp else if(m!=null) 32.sp else 24.sp,lineHeight=if(tv) 48.sp else if(m!=null) 38.sp else 30.sp,fontWeight=FontWeight.Bold,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=if(hasArt) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface)
                    Text(m?.let {listOfNotNull(it.year?.toString(),titleKind(it)).joinToString(" · ")} ?: "${r!!.source} · ${r.resolution}",color=if(hasArt) androidx.compose.ui.graphics.Color(0xFFDCE1DD) else MaterialTheme.colorScheme.onSurfaceVariant,fontSize=14.sp)
                    if(tv) WatchTheme(if(hasArt)"dark" else s.config.theme) {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Action(saved?.let {"Продолжить с ${time(it.positionMs)}"} ?: "Выбрать раздачу",true,Modifier.focusRequester(primaryFocus).testTag(if(saved!=null) "detail-resume" else "detail-sources"),icon=Icons.Outlined.PlayArrow) {if(saved!=null) model.resume(saved) else choose()}
                        if(saved!=null) Action("Раздачи",true,Modifier.testTag("detail-sources"),secondary=true,onClick=choose)
                    }}
                }
            }
            }
            if(compact) Column(Modifier.padding(horizontal=32.dp,vertical=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(title,fontSize=28.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold)
                Text(m?.let {listOfNotNull(it.year?.toString(),titleKind(it)).joinToString(" · ")} ?: r!!.resolution,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Action(saved?.let {"Продолжить · ${time(it.positionMs)}"} ?: "Выбрать раздачу",false,Modifier.testTag(if(saved!=null) "detail-resume" else "detail-sources"),icon=Icons.Outlined.PlayArrow) {if(saved!=null) model.resume(saved) else choose()}
                    if(saved!=null) IconButton(onClick=choose,modifier=Modifier.testTag("detail-sources")) {Icon(Icons.Outlined.Tune,"Выбрать раздачу")}
                }
            }
            Column(Modifier.padding(horizontal=if(compact) 32.dp else inset),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                WatchTabs(listOf("О фильме","Раздачи","Похожие"),tab,tv) {if(it=="Раздачи") choose() else tab=it}
                if(tab=="Похожие") {
                    val related=s.catalog.filter {it.id!=m?.id&&it.kind==m?.kind&&it.animation==m?.animation}
                    if(related.isEmpty()) Empty("Похожих пока нет","Попробуйте поиск по названию",tv,onClick={model.page("search")})
                    else {Text("Из того же раздела",color=MaterialTheme.colorScheme.onSurfaceVariant);LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp)) {items(related,key={it.id}) {item->Box(Modifier.width(156.dp)) {MetadataCard(item,s.config,tv) {model.metadata(item)}}}}}
                } else {
                Text(m?.overview?.takeIf {it.isNotBlank()} ?: if(r?.id?.startsWith("demo:")==true) "Демонстрация MJWatch с локальным тестовым видео. Проверьте выбор раздачи, файлов и сохранение позиции." else "Описание недоступно. Вы можете перейти к выбору раздачи.",fontSize=16.sp,lineHeight=25.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(m?.kind=="tv"&&s.config.managed) ManagedEpisodes(m,s.config,model,tv)
                if(r!=null) {Text("Исходное название",fontWeight=FontWeight.Bold);Text(r.title,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("Свойства дорожек и устройства проверяются при воспроизведении.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                progress.filter {it.releaseId==r?.id||it.releaseId in linked}.forEach {p->Text("Сохранено ${p.path} · ${time(p.positionMs)}${if(p.completed) " · Завершено" else ""}",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
            }
        }
        if(!tv&&!compact) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged {bottomHeight=it.height}.background(MaterialTheme.colorScheme.background).padding(20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Action(saved?.let {"Продолжить с ${time(it.positionMs)}"} ?: "Выбрать раздачу",false,Modifier.weight(1f).testTag(if(saved!=null) "detail-resume" else "detail-sources"),enabled=!s.busy,icon=Icons.Outlined.PlayArrow) {if(saved!=null) model.resume(saved) else choose()}
            if(saved!=null) IconButton(onClick=choose,modifier=Modifier.testTag("detail-sources").background(MaterialTheme.colorScheme.surface,RoundedCornerShape(24.dp))) {Icon(Icons.Outlined.Tune,"Выбрать раздачу")}
        }
    }
}
@Composable private fun ReleaseFilterChoices(title:String,options:List<String>,selected:String,tv:Boolean,onSelect:(String)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,fontWeight=FontWeight.Bold,modifier=Modifier.semantics {heading()})
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            options.forEach {label->Pill(if(label=="2160p")"4K" else label,selected==label,tv){onSelect(label)}}
        }
    }
}

@Composable private fun ReleasesScreen(s:WatchState,model:WatchModel,tv:Boolean) {
    if(s.busy) {Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Ищем раздачи…");Action("Отменить",tv) {model.back()}};return}
    val preferences=LocalContext.current.getSharedPreferences("playback-preferences",0)
    var filter by rememberSaveable {mutableStateOf(preferences.getString("quality","Все") ?: "Все")}
    var selectedId by rememberSaveable {mutableStateOf(s.selected?.id)}
    var source by rememberSaveable {mutableStateOf("Все источники")}
    var seeds by rememberSaveable {mutableStateOf("Любые сиды")}
    var language by rememberSaveable {mutableStateOf("Любой язык")}
    var season by rememberSaveable {mutableStateOf(s.releaseSeason)}
    var voice by rememberSaveable {mutableStateOf<String?>(null)}
    var filters by remember {mutableStateOf(false)}
    val candidates=(s.results+listOfNotNull(s.selected)).distinctBy {it.id}
    val seasons=candidates.flatMap {seasonNumbers(it.title)}.distinct().sorted()
    val voices=candidates.flatMap {releaseVoices(it.title)}.distinct().sorted()
    val releases=candidates.filter {r ->
        val russian=Regex("(?i)(rus|russian|рус|дубляж)").containsMatchIn(r.title)
        ReleaseFilter(filter,season,voice).matches(r)&&(source=="Все источники"||r.source==source)&&
            when(seeds) {"Есть сиды" -> r.seeds?.let {it>0}==true;"0 сидов" -> r.seeds==0;"Сиды не указаны" -> r.seeds==null;else -> true}&&
            when(language) {"Русский (название)" -> russian;"Русский не указан" -> !russian;else -> true}
    }
    val activeFilters=listOf(filter!="Все",season!=null,voice!=null,source!="Все источники",seeds!="Любые сиды",language!="Любой язык").count {it}
    fun resetFilters() {filter="Все";season=null;voice=null;source="Все источники";seeds="Любые сиды";language="Любой язык"}
    if(filters) AlertDialog(onDismissRequest={filters=false},title={Text("Фильтры раздач")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()).testTag("release-filters"),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            ReleaseFilterChoices("Качество",listOf("Все")+candidates.map {it.resolution}.distinct().sorted(),filter,tv){filter=it}
            if(seasons.isNotEmpty())ReleaseFilterChoices("Сезон",listOf("Все сезоны")+seasons.map {"Сезон $it"},season?.let {"Сезон $it"} ?: "Все сезоны",tv){label->season=seasons.firstOrNull {"Сезон $it"==label}}
            if(voices.isNotEmpty())ReleaseFilterChoices("Озвучка",listOf("Любая озвучка")+voices,voice ?: "Любая озвучка",tv){voice=it.takeUnless {it=="Любая озвучка"}}
            ReleaseFilterChoices("Источник",listOf("Все источники")+candidates.map {it.source}.distinct(),source,tv){source=it}
            ReleaseFilterChoices("Сиды",listOf("Любые сиды","Есть сиды","0 сидов","Сиды не указаны"),seeds,tv){seeds=it}
            ReleaseFilterChoices("Язык",listOf("Любой язык","Русский (название)","Русский не указан"),language,tv){language=it}
            Text("Озвучка и язык определены по названию раздачи. Точные дорожки доступны в плеере.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },confirmButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={filters=false}) {Text("Показать результаты (${releases.size})")}},dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={resetFilters()}) {Text("Сбросить всё")}})
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(!tv) Heading("Выберите раздачу")
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            listOf("Все","1080p","2160p","720p","Не указано").forEach {label->Pill(if(label=="2160p") "4K" else label,filter==label,tv) {filter=label}}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Action(if(activeFilters==0)"Фильтры" else "Фильтры · $activeFilters",tv,secondary=true,icon=Icons.Outlined.Tune){filters=true}
            if(activeFilters>0)TextButton(onClick={resetFilters()},modifier=Modifier.tvFocusRing(tv)){Text("Сбросить всё")}
        }
        if(seasons.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Pill("Все сезоны",season==null,tv){season=null}
            seasons.forEach {number->Pill("Сезон $number",season==number,tv){season=number}}
        }
        if(voice!=null||source!="Все источники"||seeds!="Любые сиды"||language!="Любой язык")Text(listOfNotNull(voice,source.takeUnless {it=="Все источники"},seeds.takeUnless {it=="Любые сиды"},language.takeUnless {it=="Любой язык"}).joinToString(" · "),style=MaterialTheme.typography.bodySmall)
        Text("${releases.size} раздач · свойства указаны источником",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f).testTag("release-list"),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=12.dp)) {
            items(releases,key={it.id}) {r->ReleaseCard(r,tv,focus=tv&&r.id==(s.selected?.id ?: releases.firstOrNull()?.id),selected=selectedId==r.id) {selectedId=r.id;if(tv) model.playRelease(r,season)}}
            if(releases.isEmpty()) item {Empty("Нет раздач с этими фильтрами","Попробуйте другой источник или качество.",tv,"Сбросить фильтры") {resetFilters()}}
        }
        val selected=releases.firstOrNull {it.id==selectedId}
        if(!tv&&selected!=null) Action("Смотреть · ${selected.resolution}",false,Modifier.fillMaxWidth().padding(bottom=16.dp).testTag("source-continue"),icon=Icons.Outlined.PlayArrow) {model.playRelease(selected,season)}
    }
}

@Composable private fun FilesScreen(s:WatchState,model:WatchModel,tv:Boolean) {
    val context=LocalContext.current
    val playerLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {model.playerClosed()}
    var showAll by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable(s.handle?.hash) { mutableStateOf<Int?>(null) }
    var autoLaunched by remember(s.handle?.hash) {mutableStateOf(false)}
    val scoped by model.scopedLibrary.collectAsStateWithLifecycle()
    val progress=if(scoped.context==s.config.profileKey)scoped.positions else emptyList()
    val allFiles=s.handle?.files.orEmpty()
    val seasons=allFiles.filter {it.video&&!it.sample}.mapNotNull {episodeNumber(it.path).season}.distinct().sorted()
    var season by rememberSaveable(s.handle?.hash) {mutableStateOf(s.releaseSeason)}
    var episode by rememberSaveable(s.handle?.hash) {mutableStateOf(s.releaseEpisode)}
    val visibleFiles=sortedVideoFiles(allFiles.filter {f->
        (showAll||f.video&&!f.sample)&&(season==null||episodeNumber(f.path).season==season)&&
            (episode==null||episodeNumber(f.path).episode==episode)
    })
    fun launch(file:TorrentFile) {
        if(!model.isFileSelectionActive(s.handle?.hash,s.selected?.id)) return
        val request=model.stream(file)
        android.util.Log.d("MJWatch","Dispatching playback activity")
        playerLauncher.launch(Intent(context,PlaybackActivity::class.java).putExtra("contentKey",if(s.config.managed) contentKey(s.handle!!.contentHash!!,file.path,file.size) else "").putExtra("uri",request.uri).putExtra("releaseId",s.selected!!.id).putExtra("fileId",file.serverFileId).putExtra("path",file.path).putExtra("mobile",!tv).putExtra("title",s.selectedMetadata?.title ?: s.selected?.displayTitle()))
    }
    LaunchedEffect(s.handle?.hash,s.resumePath) {
        if(autoLaunched) return@LaunchedEffect
        val videos=visibleFiles.filter {it.video&&!it.sample}
        val target=if(s.resumePath!=null) s.handle?.files.orEmpty().singleOrNull {it.video&&it.path==s.resumePath} else videos.singleOrNull()
        android.util.Log.d("MJWatch","Automatic file selection: resume=${s.resumePath!=null}, target=${target!=null}")
        if(target!=null) {autoLaunched=true;selection=target.serverFileId;launch(target)}
        if(s.resumePath!=null) {if(target==null) selection=null;model.consumeResume(target==null)}
    }
    val file=visibleFiles.firstOrNull {it.serverFileId==selection&&it.video}
    val controls:@Composable ()->Unit={Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(tv) {
            Artwork(s.selectedMetadata,s.config,Modifier.fillMaxWidth().aspectRatio(1.3f).clip(RoundedCornerShape(18.dp)),backdrop=true,demo=s.selected?.id?.startsWith("demo:")==true)
            Text(file?.let {fileLabel(it.path)} ?: "Выберите видеофайл",fontSize=20.sp,fontWeight=FontWeight.Bold)
        }
        if(file!=null) {
            val p=progress.firstOrNull {it.releaseId==s.selected?.id&&it.path==file.path}
            if(p!=null) Text("Сохранено ${time(p.positionMs)}${if(p.completed) " · завершено" else ""}")
            Action("Смотреть${if(p!=null&&!p.completed) " с ${time(p.positionMs)}" else ""}",tv,Modifier.fillMaxWidth(),icon=Icons.Outlined.PlayArrow) {launch(file)}
        } else Text("Выберите файл для просмотра",color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
    Row(Modifier.fillMaxSize().padding(bottom=20.dp),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(s.message.isNotBlank()) Text(s.message,color=MaterialTheme.colorScheme.error)
            Text(s.selectedMetadata?.title ?: s.selected?.displayTitle().orEmpty(),fontSize=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(seasons.isNotEmpty()||season!=null) Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Pill("Все сезоны",season==null,tv){season=null;episode=null;selection=null}
                seasons.forEach {number->Pill("Сезон $number",season==number,tv){season=number;episode=null;selection=null}}
            }
            if(episode!=null)Pill("Серия $episode · показать все серии",true,tv){episode=null;selection=null}
            if(tv)Text("OK — выбрать серию · повторный OK — смотреть",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Pill("Все файлы и sample",showAll,tv) {showAll=!showAll}
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=12.dp)) {
                if(visibleFiles.isEmpty())item {Text("Нет файлов для выбранного сезона или серии. Покажите все сезоны и серии.")}
                items(visibleFiles,key={it.serverFileId}) {f->FileCard(f,tv,selection==f.serverFileId,focus=tv&&f.serverFileId==(selection ?: visibleFiles.firstOrNull {it.video&&!it.sample}?.serverFileId)) {if(f.video) {if(tv&&selection==f.serverFileId)launch(f) else selection=f.serverFileId}}}
            }
            if(!tv) controls()
        }
        if(tv) Box(Modifier.width(tvUnit(320f))) {controls()}
    }
}

@Composable private fun FileCard(file:TorrentFile,tv:Boolean,selected:Boolean,focus:Boolean=false,onClick:()->Unit) {
    val requester=remember {FocusRequester()}
    LaunchedEffect(focus) {if(focus) {delay(100);requester.requestFocus()}}
    val modifier=Modifier.fillMaxWidth().focusRequester(requester).semantics {this.selected=selected;role=Role.RadioButton}
    val content:@Composable ()->Unit={
        Row(Modifier.padding(horizontal=16.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(if(selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,null,tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(episodeNumber(file.path).let {number->number.episode?.let {"${number.season?.let {s->"Сезон $s · "} ?: ""}Серия $it"} ?: fileLabel(file.path)},fontWeight=FontWeight.Bold,fontSize=17.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {Text(file.path,fontSize=13.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f));Text(sizeLabel(file.size)+if(file.sample) " · sample" else "",fontSize=13.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
    }
    NativeCard(tv,modifier,selected,onClick=onClick) {content()}
}
@Composable private fun LibraryCard(item:LibraryTitle,progress:List<Progress>,model:WatchModel,tv:Boolean,wide:Boolean=false,bookmarkOnly:Boolean=false) {
    val e=item.entry
    var menu by remember {mutableStateOf(false)}
    if(menu) AlertDialog(onDismissRequest={menu=false},title={Text(e.title)},text={Text("Действие с записью библиотеки")},confirmButton={TextButton(onClick={model.remove(item.records,bookmarkOnly);menu=false}) {Text(if(bookmarkOnly) "Убрать из избранного" else "Удалить из истории")}},dismissButton={TextButton(onClick={menu=false}) {Text("Отмена")}})
    val p=item.progress(progress)
    val cardState by model.state.collectAsState()
    val config=cardState.config
    val metadata=item.metadata ?: cardState.catalog.firstOrNull {it.id==e.metadataId}
    NativeCard(tv,Modifier.fillMaxWidth(),onLongClick={menu=true},onClick={model.restore(e)}) {
        if(wide) Column {
            Artwork(metadata,config,Modifier.fillMaxWidth().aspectRatio(2.3f),backdrop=true,demo=e.id.startsWith("demo:"))
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text(e.title,maxLines=1,fontWeight=FontWeight.Bold,fontSize=16.sp);if(p!=null) {Text(time(p.positionMs),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);p.durationMs?.takeIf {it>0}?.let {LinearProgressIndicator(progress={(p.positionMs.toFloat()/it).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(3.dp))}}}
        } else Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
            Artwork(metadata,config,Modifier.width(68.dp).height(88.dp).clip(RoundedCornerShape(10.dp)),demo=e.id.startsWith("demo:"))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text(e.title,maxLines=2,fontWeight=FontWeight.Bold,fontSize=16.sp);Text(metadata?.let {listOfNotNull(it.year?.toString(),titleKind(it)).joinToString(" · ")} ?: e.source,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);if(p!=null) {
                Text("${time(p.positionMs)}${p.durationMs?.let {" / ${time(it)}"} ?: ""}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                p.durationMs?.takeIf {it>0}?.let {LinearProgressIndicator(progress={(p.positionMs.toFloat()/it).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(3.dp))}
            }}
            if(!tv) IconButton(onClick={menu=true}) {Icon(Icons.Outlined.MoreVert,"Действия с «${e.title}»")} else Icon(Icons.Outlined.PlayCircle,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(32.dp))
        }
    }
}

@Composable private fun LibraryScreen(entries:List<LibraryTitle>,progress:List<Progress>,model:WatchModel,tv:Boolean) {
    val libraryState by model.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(if(model.state.value.page=="history") "История" else "Продолжить") }
    val filtered=entries.filter { e -> when(tab) { "Буду смотреть" -> e.entry.bookmarked; "Продолжить" -> e.entry.openedAt>0&&progress.any { it.releaseId in e.releaseIds&&!it.completed&&it.positionMs>0 }; else -> e.entry.openedAt>0 } }
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        WatchTabs(listOf("Продолжить","Буду смотреть","История"),tab,tv) {tab=it}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(filtered.isEmpty()) item { Empty("Пока пусто",if(tab=="Буду смотреть") "Сохраните фильм или сериал на потом" else if(!libraryState.config.managed) "В direct история и позиции не сохраняются. Избранное доступно отдельно." else "Здесь появится то, что вы смотрели",tv,onClick={model.page("search")}) }
            items(filtered,key={it.entry.id}) {e->LibraryCard(e,progress,model,tv,bookmarkOnly=tab=="Буду смотреть")}
        }
    }
}
@Composable private fun SettingsScreen(s:WatchState,model:WatchModel,tv:Boolean,showIntro:Boolean=true,section:String?=null,registerBack: (((() -> Unit)?) -> Unit)?=null) {
    val context=LocalContext.current
    var c by remember { mutableStateOf(model.draftConfig ?: s.config) }
    var metadataToken by remember { mutableStateOf(model.draftMetadata) }; var key by remember { mutableStateOf(model.draftKey) }; var user by remember { mutableStateOf(model.draftUser) }; var password by remember { mutableStateOf(model.draftPassword) }
    var stopConfirmation by remember {mutableStateOf(false)}
    fun applySettings() {
        if(c.torrServer!=s.config.torrServer&&PlaybackActivity.hasActivePlayback()) stopConfirmation=true
        else model.save(c,key,user,password,metadataToken,section)
    }
    if(stopConfirmation) AlertDialog(onDismissRequest={stopConfirmation=false},title={Text("Остановить просмотр и применить?")},text={Text("Адрес TorrServer меняется. Позиция текущего просмотра будет сохранена.")},confirmButton={TextButton(onClick={stopConfirmation=false;PlaybackActivity.stopActivePlayback();model.save(c,key,user,password,metadataToken,section)}) {Text("Остановить и применить")}},dismissButton={TextButton(onClick={stopConfirmation=false}) {Text("Отмена")}})
    var leave by remember {mutableStateOf(false)}
    var baseline by remember {mutableStateOf(listOf("","","",""))}
    var preview by remember { mutableStateOf<Config?>(null) }; var importError by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if(model.draftConfig==null) { metadataToken=model.secrets.get("tmdb"); key=model.secrets.get("jackett:${s.config.jackett}");user=model.secrets.get("torrUser:${s.config.torrServer}");password=model.secrets.get("torrPassword:${s.config.torrServer}") }; baseline=listOf(model.secrets.get("jackett:${s.config.jackett}"),model.secrets.get("torrUser:${s.config.torrServer}"),model.secrets.get("torrPassword:${s.config.torrServer}"),model.secrets.get("tmdb")) }
    LaunchedEffect(c,key,user,password,metadataToken) {model.draftConfig=c;model.draftKey=key;model.draftUser=user;model.draftPassword=password;model.draftMetadata=metadataToken}
    val editorImeVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    val dirty=c!=s.config||listOf(key,user,password,metadataToken)!=baseline
    val requestBack=rememberUpdatedState<()->Unit>({if(dirty) leave=true else {model.discardDraft();model.back()}})
    DisposableEffect(Unit) {registerBack?.invoke {requestBack.value()};onDispose {registerBack?.invoke(null)}}
    BackHandler(dirty&&!editorImeVisible) {requestBack.value()}
    if(leave) AlertDialog(onDismissRequest={leave=false},title={Text("Сохранить изменения?")},text={Text("Введённые параметры ещё не применены")},confirmButton={TextButton(onClick={leave=false;applySettings()}) {Text("Сохранить")}},dismissButton={Row {TextButton(onClick={leave=false;model.discardDraft();model.back()}) {Text("Не сохранять")};TextButton(onClick={leave=false}) {Text("Отмена")}}})
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) context.contentResolver.openOutputStream(uri)?.use { it.write(encodeConfig(c.copy(torrServer=normalizeDirectAddress(c.torrServer)),includeAccount=false).toByteArray()) } }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) try {
        val raw=context.contentResolver.openInputStream(uri)!!.use { String(it.readBytes().also { b -> require(b.size<65536) }) }; preview=decodeConfig(raw)
    } catch(_:Exception) {importError="Не удалось импортировать конфигурацию"} }
    fun urlError(raw:String)=raw.isNotBlank()&&runCatching {endpoint(raw)}.isFailure
    fun changed(new:Config) { c=new; model.invalidateChecks() }
    var advanced by rememberSaveable {mutableStateOf(section!=null)}
    var setupStep by rememberSaveable {mutableIntStateOf(1)}
    val setup=!tv&&!s.configured
    BackHandler(setup&&setupStep==2&&!editorImeVisible) {setupStep=1}
    Row(Modifier.fillMaxSize().then(if(tv&&!s.configured) Modifier.padding(horizontal=tvUnit(80f)) else Modifier),horizontalArrangement=Arrangement.spacedBy(32.dp)) {
    if(tv&&showIntro) Column(Modifier.weight(.8f).padding(top=32.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        Wordmark();Spacer(Modifier.height(24.dp));Text(if(s.configured) "Настройте\nсвои источники." else "Ваше кино.\nВаши источники.",fontSize=36.sp,lineHeight=44.sp,fontWeight=FontWeight.Bold)
        Text("Jackett найдёт раздачи.\nTorrServer подготовит поток.\nMJWatch начнёт просмотр.",fontSize=18.sp,lineHeight=28.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Badge("Подключения",true)
    }
    LazyColumn(Modifier.weight(1f).testTag("settings-list"),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(horizontal=if(!tv&&!s.configured) 20.dp else 0.dp,vertical=24.dp)) {
        if(setup) item {Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {Text("ШАГ $setupStep ИЗ 2",fontSize=12.sp,letterSpacing=1.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Heading(if(setupStep==1) "Добавьте\nисточник раздач" else "Подключите\nTorrServer");Text(if(setupStep==1) "Используйте адрес Jackett или JacRed. API-ключ нужен только если требует сервер." else "Укажите адрес сервера в вашей сети",color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        else if(section==null) item {Heading("Подключения")}
        if((!setup||setupStep==1)&&(section==null||section=="search")) item {Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface,RoundedCornerShape(20.dp)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("Jackett / JacRed",fontSize=20.sp,fontWeight=FontWeight.Bold)
            OutlinedTextField(c.jackett,{key="";changed(c.copy(jackett=it))},label={Text("Адрес Jackett / JacRed")},isError=urlError(c.jackett),supportingText={Text(if(urlError(c.jackett)) "Введите http/https URL без логина, query и fragment" else "Например https://jac.red")},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(14.dp))
            SecretField(key,{key=it;model.invalidateChecks()},"API-ключ (если требуется)")
            Action("Проверить поиск",tv,secondary=true,icon=Icons.Outlined.Refresh) {model.check(true,c,key,user,password)}
            if(s.checkJackett.isNotBlank()) Text(s.checkJackett,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        if((!setup||setupStep==2)&&(section==null||section=="server")) item {Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface,RoundedCornerShape(20.dp)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("TorrServer",fontSize=20.sp,fontWeight=FontWeight.Bold)
            OutlinedTextField(c.torrServer,{user="";password="";changed(c.copy(torrServer=it))},label={Text("Адрес TorrServer")},isError=runCatching {endpoint(normalizeDirectAddress(c.torrServer))}.isFailure&&c.torrServer.isNotBlank(),supportingText={Text(if(runCatching {endpoint(normalizeDirectAddress(c.torrServer))}.isFailure&&c.torrServer.isNotBlank()) "Введите http/https URL без логина, query и fragment" else runCatching {"Итоговый адрес: ${normalizeDirectAddress(c.torrServer)} · IP без порта использует 8090"}.getOrDefault("Введите IP или полный URL"))},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(14.dp))
            Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(modifier=Modifier.semantics {contentDescription="Разрешить локальный HTTP"},checked=c.localHttp,onCheckedChange={changed(c.copy(localHttp=it))});Text("Разрешить локальный HTTP",fontSize=14.sp)}
            Action("Проверить TorrServer",tv,secondary=true,icon=Icons.Outlined.Refresh) {model.check(false,c,key,user,password)}
            if(s.checkTorr.isNotBlank()) Text(s.checkTorr,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        if(section=="search") item {Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(checked=c.localHttp,onCheckedChange={changed(c.copy(localHttp=it))});Text("Разрешить локальный HTTP")}}
        if(section=="server") item {Text("БЕЗ ИСТОРИИ · Позиция и открытия не сохраняются",color=MaterialTheme.colorScheme.primary)}
        if(section=="catalog") item {Text("Метаданные и обложки из TMDB. Поиск раздач работает и без каталога.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(section==null) item {Row(verticalAlignment=Alignment.CenterVertically) {Switch(modifier=Modifier.semantics {contentDescription="Демонстрация с локальным видео"},checked=c.demo,onCheckedChange={changed(c.copy(demo=it))});Text("Демонстрация с локальным видео",Modifier.padding(start=12.dp))}}
        if(setup&&setupStep==1&&!c.demo) item {Action("Далее · TorrServer",false,Modifier.fillMaxWidth(),enabled=!urlError(c.jackett),icon=Icons.Outlined.ArrowForward) {setupStep=2}}
        if(section==null&&(!setup||setupStep==2||c.demo)) item {Action(if(s.busy) "Сохранение…" else "Сохранить и продолжить",tv,Modifier.fillMaxWidth(),enabled=!s.busy,icon=Icons.Outlined.Check) {applySettings()}}
        if(s.message.isNotBlank()) item {Text(s.message,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
        if(section==null) item {TextButton(onClick={advanced=!advanced}) {Text(if(advanced) "Скрыть дополнительные настройки" else "Дополнительные настройки");Icon(if(advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)}}
        if(advanced) {
            if(section==null||section=="search") item {Text("Протокол поиска",fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("auto" to "Авто","torznab" to "Torznab","jacred" to "JacRed JSON").forEach {(id,label)->Pill(label,c.releaseApi==id,tv) {changed(c.copy(releaseApi=id))}}}}
            if(section==null||section=="search") item {OutlinedTextField(c.indexers,{changed(c.copy(indexers=it))},label={Text("Индексаторы (необязательно)")},supportingText={Text("Пусто — все источники")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp))}
            if(section==null||section=="server") item {OutlinedTextField(user,{user=it;model.invalidateChecks()},label={Text("Basic Auth: логин (необязательно)")},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(14.dp))}
            if(section==null||section=="server") item {SecretField(password,{password=it;model.invalidateChecks()},"Пароль TorrServer")}
            if(section==null||section=="catalog") item {Row(verticalAlignment=Alignment.CenterVertically) {Switch(modifier=Modifier.semantics {contentDescription="Каталог TMDB"},checked=c.metadataProvider=="tmdb",onCheckedChange={c=c.copy(metadataProvider=if(it) "tmdb" else "none")});Text("Каталог TMDB",Modifier.padding(start=12.dp))}}
            if((section==null||section=="catalog")&&c.metadataProvider=="tmdb") item {SecretField(metadataToken,{metadataToken=it},"TMDB Read Access Token")}
            if(section==null) item {Text("Оформление",fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("system" to "Системная","dark" to "Тёмная","light" to "Светлая").forEach {(id,label)->Pill(label,c.theme==id,tv) {c=c.copy(theme=id)}}}}
        }
        if(section!=null) item {Action(if(s.busy) "Сохранение…" else "Сохранить",tv,Modifier.fillMaxWidth(),enabled=!s.busy,icon=Icons.Outlined.Check) {applySettings()}}
        if(section==null) item {Action("Импорт конфигурации",tv,secondary=true,icon=Icons.Outlined.FileDownload) {importer.launch(arrayOf("application/json"))}}
        if(section==null) item {TextButton(onClick={if(!urlError(c.jackett)&&(c.torrServer.isBlank()||runCatching {endpoint(normalizeDirectAddress(c.torrServer))}.isSuccess)) export.launch("mjwatch-config.json") else importError="Исправьте адреса перед экспортом"}) {Text("Экспорт конфигурации")}}
        if(importError.isNotBlank()) item {Text(importError,color=MaterialTheme.colorScheme.error)}
        if(section==null) item {Text("Секреты шифруются Android Keystore. Экспорт не содержит ключей и паролей. Подключения можно сохранить без проверки.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(section==null) item {Text("MJWatch ${model.updates.versionName} · TMDB: необязательный каталог. This product uses the TMDB API but is not endorsed or certified by TMDB.\nТестовое видео: Android Emulator virtual scene, Apache 2.0",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    }
    preview?.let { imported -> AlertDialog(onDismissRequest={preview=null},title={Text("Применить конфигурацию?")},text={Text("Jackett: ${imported.jackett}\nИндексаторы: ${imported.indexers}\nTorrServer: ${imported.torrServer}\nКлючи потребуется ввести заново. Сетевые запросы не выполнялись.")},confirmButton={TextButton(onClick={c=imported.copy(serverId="",userId="");key="";user="";password="";preview=null;model.invalidateChecks()}) {Text("Применить к форме")}},dismissButton={TextButton(onClick={preview=null}) {Text("Отмена")}}) }
}
@Composable private fun SecretField(value:String,onChange:(String)->Unit,label:String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value,onChange,label={Text(label)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),visualTransformation=if(visible) VisualTransformation.None else PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),trailingIcon={IconButton(onClick={visible=!visible}) {Icon(if(visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(visible) "Скрыть пароль" else "Показать пароль")}})
}
private fun time(ms:Long):String = "%d:%02d".format(ms/60000,ms/1000%60)

@Composable private fun SettingsOverview(s:WatchState,model:WatchModel,tv:Boolean) {
    var section by rememberSaveable {mutableStateOf("Подключения")}
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var themes by remember {mutableStateOf(false)}
    if(themes) AlertDialog(onDismissRequest={themes=false},title={Text("Тема")},text={Column {listOf("system" to "Как в системе","dark" to "Тёмная","light" to "Светлая").forEach {(id,label)->PreferenceChoice(label,s.config.theme==id) {scope.launch {Settings(context).save(s.config.copy(theme=id))};themes=false}}}},confirmButton={TextButton(onClick={themes=false}) {Text("Закрыть")}})
    val appearance:@Composable ()->Unit={Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Тема",fontSize=20.sp,fontWeight=FontWeight.Bold)
        listOf("system" to "Как в системе","dark" to "Тёмная","light" to "Светлая").forEach {(id,label)->PreferenceChoice(label,s.config.theme==id,tv) {scope.launch {Settings(context).save(s.config.copy(theme=id))}}}
    }}
    if(tv) {
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
            Column(Modifier.width(tvUnit(320f)),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                ((listOf("Подключения")+if(s.config.managed)listOf("Аккаунт") else emptyList())+listOf("Воспроизведение","Оформление","О приложении")).forEach {label->Action(label,true,Modifier.fillMaxWidth(),secondary=section!=label) {section=label}}
            }
            Box(Modifier.weight(1f)) {when(section) {
                "Подключения"->ConnectionLinks(s,model,true)
                "Аккаунт"->AccountLinks(s,model,true)
                "Воспроизведение"->PlaybackPreferences(true,model)
                "Оформление"->appearance()
                else->Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {Wordmark();UpdateLink(model,true);Text("TMDB: необязательный каталог. This product uses the TMDB API but is not endorsed or certified by TMDB.")}
            }}
        }
        return
    }
    LazyColumn(Modifier.testTag("settings-list"),verticalArrangement=Arrangement.spacedBy(20.dp),contentPadding=PaddingValues(vertical=16.dp)) {
        item {SettingsLabel("Подключения")}
        item {ConnectionLinks(s,model,false)}
        if(s.config.managed) {item {SettingsLabel("Аккаунт")};item {AccountLinks(s,model,false)}}
        item {SettingsLabel("Воспроизведение")}
        item {PlaybackPreferences(false,model)}
        item {SettingsLabel("Оформление")}
        item {SettingsGroup(false) {
            SettingsActionRow(Icons.Outlined.Palette,"Тема",when(s.config.theme) {"dark"->"Тёмная";"light"->"Светлая";else->"Как в системе"},false) {themes=true}
        }}
        item {SettingsLabel("Приложение")}
        item {UpdateLink(model,false)}
        item {Text("MJWatch ${model.updates.versionName}\nTMDB: необязательный каталог. This product uses the TMDB API but is not endorsed or certified by TMDB.\nТестовое видео: Android Emulator virtual scene, Apache 2.0",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)}
    }
}
@Composable private fun SettingsLabel(text:String) {Text(text.uppercase(),fontSize=12.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)}
@Composable private fun PlaybackPreferences(tv:Boolean,model:WatchModel) {
    val context=LocalContext.current
    val prefs=remember {context.getSharedPreferences("playback-preferences",0)}
    var audio by remember {mutableStateOf(prefs.getString("audio-language",null))}
    var subtitlesOff by remember {mutableStateOf(prefs.getBoolean("subtitles-off",false))}
    var quality by remember {mutableStateOf(prefs.getString("quality","Все") ?: "Все")}
    var qualityDialog by remember {mutableStateOf(false)}
    if(qualityDialog) AlertDialog(onDismissRequest={qualityDialog=false},title={Text("Качество раздач")},text={Column {listOf("Все","720p","1080p","2160p").forEach {label->PreferenceChoice(label,quality==label,tv) {quality=label;prefs.edit().putString("quality",label).apply();model.preferencesChanged();qualityDialog=false}}}},confirmButton={TextButton(onClick={qualityDialog=false}) {Text("Закрыть")}})
    var dialog by remember {mutableStateOf(false)}
    if(dialog) AlertDialog(onDismissRequest={dialog=false},title={Text("Язык аудио")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {listOf(null to "Авто","ru" to "Русский","en" to "Английский","ja" to "Японский").forEach {(id,label)->PreferenceChoice(label,audio==id,tv) {audio=id;prefs.edit().putString("audio-language",id).apply();model.preferencesChanged();dialog=false}}}},confirmButton={TextButton(onClick={dialog=false}) {Text("Закрыть")}})
    var subtitles by remember {mutableStateOf(prefs.getString("subtitle-language",null))}
    var subtitleDialog by remember {mutableStateOf(false)}
    val subtitleLabel=if(subtitlesOff) "Выключены" else when(subtitles) {"ru"->"Русский";"en"->"Английский";else->"Автоматически"}
    if(subtitleDialog) AlertDialog(onDismissRequest={subtitleDialog=false},title={Text("Субтитры")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("auto" to "Автоматически","off" to "Выключены","ru" to "Русский","en" to "Английский").forEach {(id,label)->PreferenceChoice(label,if(id=="off") subtitlesOff else !subtitlesOff&&(subtitles ?: "auto")==id,tv) {
            subtitlesOff=id=="off";subtitles=id.takeIf {it!="auto"&&it!="off"};prefs.edit().putBoolean("subtitles-off",subtitlesOff).putString("subtitle-language",subtitles).apply();model.preferencesChanged();subtitleDialog=false
        }}
    }},confirmButton={TextButton(onClick={subtitleDialog=false}) {Text("Закрыть")}})
    SettingsGroup(tv) {
        SettingsActionRow(Icons.Outlined.HighQuality,"Предпочтительное качество",if(quality=="Все") "Любое" else quality,tv) {qualityDialog=true}
        SettingsDivider()
        SettingsActionRow(Icons.Outlined.Translate,"Язык аудио",when(audio) {"ru"->"Русский";"en"->"Английский";"ja"->"Японский";else->"Авто"},tv) {dialog=true}
        SettingsDivider()
        SettingsActionRow(Icons.Outlined.Subtitles,"Субтитры",subtitleLabel,tv) {subtitleDialog=true}
    }
}

@Composable internal fun SettingsRow(icon:ImageVector,title:String,value:String,modifier:Modifier=Modifier,chevron:Boolean=true) {
    Row(modifier.fillMaxWidth().heightIn(min=42.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(title,fontWeight=FontWeight.Medium,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurface);Text(value.ifBlank {"Не настроен"},fontSize=13.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)}
        if(chevron) Icon(Icons.Outlined.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun PreparingScreen(s:WatchState,model:WatchModel,tv:Boolean) {
    val recoveryFocus=remember {FocusRequester()}
    val missingInput=s.message.contains("Нет magnet",ignoreCase=true)
    LaunchedEffect(s.busy) {if(tv&&!s.busy){delay(150);recoveryFocus.requestFocus()}}
    var elapsed by remember {mutableIntStateOf(0)}
    LaunchedEffect(s.busy) {while(s.busy) {delay(1000);elapsed++}}
    val artwork:@Composable ()->Unit={Artwork(s.selectedMetadata,s.config,Modifier.fillMaxWidth().aspectRatio(if(tv) 1.4f else 2f).clip(RoundedCornerShape(20.dp)),backdrop=true,demo=s.selected?.id?.startsWith("demo:")==true)}
    val content:@Composable ()->Unit={Column(verticalArrangement=Arrangement.spacedBy(if(tv) 12.dp else 20.dp)) {
        Text(s.selectedMetadata?.title ?: s.selected?.displayTitle().orEmpty(),fontSize=12.sp,letterSpacing=1.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Heading(if(s.busy) "Готовим к просмотру" else "Не удалось подготовить поток")
        if(s.busy) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface,RoundedCornerShape(18.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                PreparationStep(Icons.Outlined.CheckCircle,"Раздача выбрана",s.selected?.resolution.orEmpty(),true)
                HorizontalDivider(color=MaterialTheme.colorScheme.outline.copy(alpha=.15f))
                Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp);Column {Text("Получаем информацию о файлах",fontWeight=FontWeight.Medium);Text("Ожидаем ответ TorrServer",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                HorizontalDivider(color=MaterialTheme.colorScheme.outline.copy(alpha=.15f))
                PreparationStep(Icons.Outlined.PlayCircle,"Затем — выбор файла","Видео откроется в плеере",false)
            }
            Text("Прошло $elapsed с. Подготовку можно отменить.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(!s.busy&&s.message.isNotBlank()) Text(s.message,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
        if(!s.busy) {Icon(Icons.Outlined.CloudOff,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.error);if(!missingInput)Action("Повторить",tv,Modifier.focusRequester(recoveryFocus),icon=Icons.Outlined.Refresh) {model.prepare()};Action("Подключения",tv,secondary=true,icon=Icons.Outlined.Settings) {model.page("connections")}}
        Action(if(s.busy) "Отменить" else "К раздачам",tv,(if(tv&&missingInput) Modifier.focusRequester(recoveryFocus) else Modifier).then(if(tv) Modifier else Modifier.fillMaxWidth()),secondary=true,icon=Icons.Outlined.Close) {model.back()}
    }}
    if(tv) Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top=24.dp),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
        if(s.busy) {Box(Modifier.weight(1.1f)) {content()};Box(Modifier.weight(.9f)) {artwork()}} else {Box(Modifier.weight(.8f)) {artwork()};Box(Modifier.weight(1.2f)) {content()}}
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {artwork();content()}
}
@Composable private fun PreparationStep(icon:ImageVector,title:String,subtitle:String,complete:Boolean) {
    Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=if(complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column {Text(title,fontWeight=FontWeight.Medium,lineHeight=22.sp,color=if(complete) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant);Text(subtitle,fontSize=13.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable internal fun SettingsGroup(tv:Boolean,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface,RoundedCornerShape(16.dp)).then(if(tv) Modifier else Modifier.clip(RoundedCornerShape(16.dp))),content=content)
}
@Composable internal fun SettingsDivider() {HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.outline.copy(alpha=.18f))}
@Composable internal fun SettingsActionRow(icon:ImageVector,title:String,value:String,tv:Boolean,onClick:()->Unit) {
    if(tv) NativeCard(true,Modifier.fillMaxWidth(),onClick=onClick) {SettingsRow(icon,title,value,Modifier.padding(16.dp))}
    else Surface(onClick=onClick,color=androidx.compose.ui.graphics.Color.Transparent,modifier=Modifier.fillMaxWidth()) {SettingsRow(icon,title,value,Modifier.padding(16.dp))}
}
@Composable private fun ConnectionLinks(s:WatchState,model:WatchModel,tv:Boolean) {
    val account by model.managed.state.collectAsState()
    val managed=account.takeIf {it.scope==s.config.scope}
    val admin=managed?.me?.optString("role")=="admin"
    SettingsGroup(tv) {
        SettingsActionRow(Icons.Outlined.Dns,"Jackett / JacRed",if(s.config.demo) "Демонстрационный режим" else s.config.jackett,tv) {model.page("settings-search")}
        if(!s.config.managed) {
            SettingsDivider()
            SettingsActionRow(Icons.Outlined.Dns,"TorrServer",if(s.config.demo) "Локальное тестовое видео" else s.config.torrServer,tv) {model.page("settings-server")}
            SettingsDivider()
            SettingsActionRow(Icons.Outlined.Movie,"Каталог",if(s.config.metadataProvider=="tmdb") "TMDB" else "Метаданные и обложки",tv) {model.page("settings-catalog")}
        }
        SettingsDivider()
        SettingsActionRow(Icons.Outlined.Cloud,"Способ подключения",if(s.config.managed) "Сервер MJWatch" else "TorrServer · Без истории",tv){model.page("connection-choice")}

    }
}
@Composable private fun UpdateLink(model:WatchModel,tv:Boolean) {
    val update by model.updates.state.collectAsStateWithLifecycle()
    SettingsGroup(tv) {SettingsActionRow(Icons.Outlined.SystemUpdate,"Обновления",update.available?.let {"Доступна ${it.versionName}"} ?: "Версия ${model.updates.versionName}",tv) {model.page("settings-updates")}}
}
@Composable private fun RailButton(tv:Boolean,icon:ImageVector,label:String,selected:Boolean,modifier:Modifier,onClick:()->Unit) {
    val colors=MaterialTheme.colorScheme;val shape=RoundedCornerShape(12.dp)
    if(tv) androidx.tv.material3.IconButton(onClick=onClick,modifier=modifier.semantics {this.selected=selected;role=Role.Tab},shape=androidx.tv.material3.IconButtonDefaults.shape(shape=shape),scale=androidx.tv.material3.IconButtonDefaults.scale(focusedScale=1f,pressedScale=1f),colors=androidx.tv.material3.IconButtonDefaults.colors(containerColor=if(selected) colors.primaryContainer else colors.background,contentColor=if(selected) colors.primary else colors.onSurface,focusedContainerColor=colors.primaryContainer,focusedContentColor=colors.primary),border=androidx.tv.material3.IconButtonDefaults.border(focusedBorder=androidx.tv.material3.Border(BorderStroke(2.dp,colors.primary)))) {Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {Icon(icon,label,Modifier.size(24.dp),tint=androidx.tv.material3.LocalContentColor.current);Text(label,fontSize=11.sp,color=androidx.tv.material3.LocalContentColor.current,maxLines=1)}}
    else Surface(onClick=onClick,modifier=modifier.tvFocusRing(true,shape).semantics {this.selected=selected;role=Role.Tab},shape=shape,color=if(selected) colors.primaryContainer else colors.background,contentColor=if(selected) colors.primary else colors.onSurface) {Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {Icon(icon,label,Modifier.size(24.dp))}}
}
