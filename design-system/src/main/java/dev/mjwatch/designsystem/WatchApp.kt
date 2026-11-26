@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.mjwatch.designsystem

import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.withSaveLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import coil.ImageLoader
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import dev.mjwatch.playback.PlaybackEpisode
import dev.mjwatch.playback.PlaybackQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Destination(val id:String,val title:String,val icon:ImageVector)
internal val LocalImages=staticCompositionLocalOf<ImageLoader> {error("Image loader unavailable")}
private val destinations=listOf(Destination("home","Главная",Icons.Filled.Home),Destination("search","Поиск",Icons.Outlined.Search),Destination("library","Моё",Icons.Filled.Bookmarks),Destination("settings","Настройки",Icons.Filled.Settings))

@Composable fun WatchApp(model:WatchModel,tv:Boolean,scanQr:(()->Unit)?=null,animateStartup:Boolean=false) {
    val state by model.state.collectAsStateWithLifecycle()
    val online = tv || rememberNetworkAvailable()
    val onlineState = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
        if (state.initializing) BackHandler {}
        else Box(Modifier.fillMaxSize().testTag("app-content")) {
            if (online) onlineState.SaveableStateProvider("online") { WatchAppContent(model, tv, scanQr) }
            else OfflineHome(state.config)
        }
        if(state.initializing || animateStartup) {
            StartupBanner(waiting=state.initializing,animateMark=animateStartup)
        }
    }
}

@Composable private fun WatchAppContent(model:WatchModel,tv:Boolean,scanQr:(()->Unit)?) {
    val state by model.state.collectAsStateWithLifecycle()
    val remoteState by model.remote.collectAsStateWithLifecycle()
    if(!tv)RemoteReplacementDialog(model)
    val receiverActivity=androidx.activity.compose.LocalActivity.current
    LaunchedEffect(tv,state.config.profileKey,state.config.managedUrl) {if(tv&&receiverActivity!=null){dev.mjwatch.playback.RemoteReceiver.attach(receiverActivity);dev.mjwatch.playback.RemoteReceiver.start(receiverActivity.application,state.config)}}
    AutomaticUpdates(model.updates)
    if(state.legacyHistoryRecords>0) AlertDialog(onDismissRequest={},title={Text("Прежняя локальная история")},text={Text("Найдено ${state.legacyHistoryRecords} записей открытий и позиций. В direct история скрыта и больше не обновляется. Импорт на сервер доступен отдельным действием.")},confirmButton={TextButton(onClick={model.managed.direct(false);model.legacyHandled()}){Text("Сохранить отдельно")}},dismissButton={TextButton(onClick={model.managed.direct(true);model.legacyHandled()}){Text("Удалить локальную историю")}})
    val snackbar=remember {SnackbarHostState()}
    LaunchedEffect(state.page,state.message) {
        if(state.page in setOf("detail","home","library","history")&&state.message.isNotBlank()) snackbar.showSnackbar(state.message,duration=SnackbarDuration.Long)
    }
    LaunchedEffect(state.removed) {state.removed?.let {removal->
        if(snackbar.showSnackbar(if(removal.bookmarks) "Убрано из подборки" else "Удалено из истории","Отменить",duration=SnackbarDuration.Long)==SnackbarResult.ActionPerformed) model.undo(removal) else model.clearUndo()
    }}
    val snapshot by model.scopedLibrary.collectAsStateWithLifecycle()
    val currentLibrary=snapshot.takeIf {it.context==state.config.profileKey}
    val library=currentLibrary?.entries.orEmpty()
    val progress=currentLibrary?.positions.orEmpty()
    var editorBack by remember {mutableStateOf<(() -> Unit)?>(null)}
    val saved=rememberSaveableStateHolder();val searchScroll=rememberLazyListState()
    val context=LocalContext.current.applicationContext
    val imageConfig=state.config
    val images=remember(imageConfig) {ImageLoader.Builder(context).okHttpClient {
        if(imageConfig.managed) managedImageClient(imageConfig,model.secrets) else guardedClient(imageConfig)
    }.build()}
    DisposableEffect(images) {onDispose {images.shutdown()}}
    val focusClearance=with(LocalDensity.current) {8.dp.toPx()}
    val tvScrollSpec=remember(focusClearance) {TVBringIntoView(focusClearance)}
    CompositionLocalProvider(LocalTvNavigation provides tv,LocalImages provides images,androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides if(tv) tvScrollSpec else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current) {WatchTheme(state.config.theme) {
        val colors=MaterialTheme.colorScheme
        val activity=androidx.activity.compose.LocalActivity.current
        var exitRequested by remember {mutableStateOf(false)}
        val updateState by model.updates.state.collectAsStateWithLifecycle()
        var updateDialogDismissed by rememberSaveable {mutableStateOf(false)}
        LaunchedEffect(updateState.downloading) {if(updateState.downloading)updateDialogDismissed=false}
        if(updateState.downloading||updateState.promptInstall&&updateState.ready!=null&&!updateDialogDismissed) UpdateDownloadDialog(
            updateState,tv,onCancel={model.updates.cancel()},onDismiss={updateDialogDismissed=true},
            installButton={modifier->UpdateInstallAction(model.updates,tv,modifier)}
        )
        if(exitRequested) ExitAppDialog(tv,onDismiss={exitRequested=false},onExit={
            model.cancel();model.updates.cancel();PlaybackActivity.stopActivePlayback();activity?.finishAndRemoveTask()
        })
        SideEffect {activity?.let {a->WindowCompat.getInsetsController(a.window,a.window.decorView).apply {isAppearanceLightStatusBars=colors.background.luminance()>.5;isAppearanceLightNavigationBars=colors.background.luminance()>.5};if(android.os.Build.VERSION.SDK_INT>=29) a.window.isNavigationBarContrastEnforced=false}}
        androidx.tv.material3.MaterialTheme(colorScheme=androidx.tv.material3.darkColorScheme(primary=colors.primary,onPrimary=colors.onPrimary,surface=colors.surface,onSurface=colors.onSurface,surfaceVariant=colors.surfaceVariant,onSurfaceVariant=colors.onSurfaceVariant)) {
            val width=LocalConfiguration.current.screenWidthDp
            val imeVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
            val rail=tv||(width>=600&&LocalConfiguration.current.screenHeightDp>=480&&!imeVisible)
            val railItems=if(rail) listOf(destinations[0],destinations[1],Destination("history","История",Icons.Outlined.History),destinations[2],destinations[3]) else destinations
            val releaseSheet=!tv&&width<840&&state.page=="releases"
            val page=if(releaseSheet) "detail" else state.page
            val top=page in (destinations.map {it.id}+"history")
            val cinematic=page in setOf("home","detail")
            val releaseSelection=state.query+":${state.releaseSeason}:${state.releaseEpisode}"
            val releaseKey="releases"+releaseSelection
            val screenKey=page+when(page) {
                "detail"->state.selectedMetadata?.id ?: state.selected?.id.orEmpty()
                "releases"->releaseSelection
                "files"->state.selected?.id.orEmpty()+state.config.torrServer+":${state.releaseSeason}:${state.releaseEpisode}"
                "library","history"->if(tv) "" else state.config.profileKey
                else->""
            }
            val railFocus=remember {FocusRequester()};var railFocused by remember {mutableStateOf(false)}
            BackHandler(state.page=="home"&&!imeVisible) {exitRequested=true}
            BackHandler(tv&&state.page=="home"&&!railFocused&&state.configured) {railFocus.requestFocus()}
            BackHandler(state.page!="home"&&!imeVisible) {model.back()}
            val detailNav=page=="detail" && width<600 && LocalConfiguration.current.screenHeightDp>=480
            val showBottomBar=!rail&&(top||detailNav)&&state.configured
            val activeDestination=if(detailNav) state.originPage else page
            Scaffold(containerColor=colors.background,snackbarHost={SnackbarHost(snackbar)},bottomBar={
                if(!imeVisible) Box {
                    val bottomInset = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                    if(!showBottomBar) Spacer(Modifier.windowInsetsBottomHeight(bottomInset))
                    AnimatedVisibility(
                        visible=showBottomBar,
                        enter=fadeIn(tween(180))+expandVertically(tween(240)),
                        exit=fadeOut(tween(120))+shrinkVertically(tween(200)),
                    ) {
                        Column(Modifier.fillMaxWidth().windowInsetsPadding(bottomInset)
                            .padding(horizontal=8.dp).clip(RoundedCornerShape(22.dp))) {
                            NavigationBar(
                                modifier=Modifier.fillMaxWidth().height(52.dp).testTag("bottom-navigation"),
                                containerColor=colors.surface,
                                tonalElevation=0.dp,
                                windowInsets=WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal),
                            ) {
                                val selectionBrush=watchSelectionGradient()
                                destinations.forEach { destination ->
                                    val selected=destination.id==activeDestination||destination.id=="library"&&activeDestination=="history"
                                    NavigationBarItem(
                                        selected=selected,
                                        enabled=showBottomBar,
                                        onClick={model.page(destination.id)},
                                        colors=NavigationBarItemDefaults.colors(
                                            selectedIconColor=colors.primary,
                                            unselectedIconColor=colors.onSurfaceVariant,
                                            indicatorColor=Color.Transparent,
                                        ),
                                        icon={
                                            Box(
                                                Modifier.size(60.dp,32.dp)
                                                    .then(if(selected) Modifier.background(selectionBrush,CircleShape) else Modifier),
                                                contentAlignment=Alignment.Center,
                                            ) {
                                                Icon(destination.icon,destination.title,Modifier.size(24.dp))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }) {padding->
                Row(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                    if(rail&&state.configured) Column(Modifier.width(if(tv) tvUnit(112f) else 88.dp).fillMaxHeight().background(colors.background).drawBehind {val x=size.width-1f;drawLine(colors.onSurface.copy(alpha=.06f),androidx.compose.ui.geometry.Offset(x,0f),androidx.compose.ui.geometry.Offset(x,size.height),1f)}.padding(top=if(tv) tvUnit(28f) else 24.dp,bottom=24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("MJ",color=colors.primary,fontSize=if(tv) 23.sp else 26.sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(if(tv) 24.dp else 28.dp))
                        railItems.forEach {d->
                            if(d.id=="settings") Spacer(Modifier.weight(1f))
                            val selected = when {
                                state.page.startsWith("settings-") || state.page == "connections" -> d.id == "settings"
                                else -> state.page == d.id || (!top && state.originPage == d.id)
                            }
                            RailButton(tv,d.icon,d.title,selected,Modifier.padding(vertical=6.dp).then(if(tv) Modifier.size(width=tvUnit(96f),height=72.dp) else Modifier.size(48.dp)).then(if(d.id=="home") Modifier.focusRequester(railFocus) else Modifier).onFocusChanged {railFocused=it.isFocused}) {model.page(d.id)}
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().imePadding().clipToBounds().softEntrance(screenKey)) {
                        if(!(tv&&(cinematic||page=="search"))&&page!="detail"&&!(tv&&page=="settings"&&!state.configured)) {
                            Row(Modifier.fillMaxWidth().heightIn(min=if(tv) 76.dp else 64.dp).padding(horizontal=if(tv) tvUnit(40f) else 20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                if(!top) IconButton(onClick={editorBack?.invoke() ?: model.back()},modifier=Modifier.tvFocusRing(tv).background(colors.surface,RoundedCornerShape(24.dp))) {Icon(Icons.Outlined.ArrowBack,"Назад")}
                                val title=when(page) {"search"->"Поиск";"library"->"Моё";"history"->"История";"settings"->if(state.configured) "Настройки" else "Подключения";"connections"->"Подключения";"settings-search"->if(state.config.managed) "Поиск раздач" else "Jackett / JacRed";"settings-server"->"TorrServer";"settings-catalog"->"Каталог";"settings-updates"->"Обновление MJWatch";"files"->"Выберите файл";"preparing"->"Подготовка";"releases"->"Выберите раздачу";else->""}
                                if(page=="home") Box(Modifier.weight(1f)) {Wordmark()} else Text(title,fontSize=if(tv) 30.sp else 24.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                                if(page=="home") HomeServerButton(state,model,tv)
                                else if(state.config.demo&&page!="settings"&&page!="connections"&&!page.startsWith("settings-")) Badge("ДЕМО",accent=true)
                            }
                        }
                        val inset=if(tv) tvUnit(40f) else 20.dp
                        val edge=page in setOf("home","detail")||page=="releases"&&!tv||page=="settings"&&!state.configured&&!tv
                        val screenFocus=remember {FocusRequester()}
                        LaunchedEffect(page,tv) {
                            if(tv&&page in setOf("connection-choice","managed-login","connections","settings-search","settings-server","settings-catalog")) {withFrameNanos {};screenFocus.requestFocus()}
                        }
                        Box(
                            Modifier.weight(1f).fillMaxWidth().focusRequester(screenFocus).focusGroup()
                                .then(if(edge) Modifier else Modifier.padding(horizontal=inset))
                                .then(if(releaseSheet) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier),
                        ) {
                            val bounded=if(tv) page=="settings-search"||page=="settings-server"&&!state.config.managed
                                else page in setOf("settings","connections","settings-search","settings-server","settings-catalog","settings-updates","invitation-register","managed-account","managed-devices","managed-pairing","preparing","files","releases")&&!(page=="settings-server"&&state.config.managed)
                            BoundedPane(bounded,maxWidth=if(tv) tvUnit(720f) else if(page=="releases"||page=="files") 840.dp else 640.dp) {
                            saved.SaveableStateProvider(screenKey) {
                                when(page) {
                                    "invitation-register"->InvitationScreen(model,tv)
                                    "home"->HomeScreen(state,currentLibrary,model,tv)
                                    "settings"->if(state.configured) SettingsOverview(state,model,tv,onExit={exitRequested=true}) else ServerScreen(state.copy(page="connection-choice"),model,tv,registerBack={editorBack=it})
                                    "managed-account","managed-devices","managed-pairing"->AccountScreen(state,model,tv,scanQr)
                                    "server-users","server-user","server-user-create","server-user-invite","server-user-devices"->UsersScreen(state,model,tv)
                                    "connection-choice","managed-login","server-overview","server-resources","server-tmdb","server-history","server-retention","server-torr","server-jacred","server-install","server-maintenance","server-update","server-migration"->ServerScreen(state,model,tv,registerBack={editorBack=it})
                                    "connections"->SettingsScreen(state,model,tv,registerBack={editorBack=it})
                                    "settings-search"->SettingsScreen(state,model,tv,showIntro=false,section="search",registerBack={editorBack=it})
                                    "settings-server"->if(state.config.managed) ServerScreen(state.copy(page="server-overview"),model,tv,registerBack={editorBack=it}) else SettingsScreen(state,model,tv,showIntro=false,section="server",registerBack={editorBack=it})
                                    "settings-catalog"->if(state.config.managed) Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)){SettingsCard("Каталог и обложки",Icons.Outlined.Movie,tv){SettingsHint("Каталог TMDB работает через сервер MJWatch. Ключ хранится у владельца сервера.");Action("Проверить каталог",tv,Modifier.fillMaxWidth(),loading=state.catalogBusy){model.refreshCatalog(true)};OperationMessage(state.catalogMessage);SettingsActionRow(Icons.Outlined.ManageAccounts,"Аккаунт и сервер","Управление подключением",tv){model.page("managed-account")}}} else SettingsScreen(state,model,tv,showIntro=false,section="catalog",registerBack={editorBack=it})
                                    "settings-updates"->LazyColumn(Modifier.testTag("settings-list"),contentPadding=PaddingValues(vertical=16.dp)) {item {UpdateSection(model.updates,tv)}}
                                    "search"->BoundedPane(!tv,maxWidth=840.dp) {SearchScreen(state,model,tv,searchScroll)}
                                    "library","history"->LibraryScreen(state,currentLibrary,model,tv)
                                    "detail"->if(!tv&&width>=840&&state.originPage=="search") Row(Modifier.fillMaxSize()) {
                                        Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {Heading("Поиск");Spacer(Modifier.height(16.dp));SearchScreen(state,model,false,searchScroll)}
                                        VerticalDivider()
                                        Box(Modifier.weight(1f).fillMaxHeight().background(colors.surface)) {DetailScreen(state,library,progress,model,false,compact=true)}
                                    } else DetailScreen(state.copy(page=page),library,progress,model,tv)
                                    "releases"->ReleasesScreen(state,model,tv)
                                    "files"->FilesScreen(state,model,tv)
                                    "preparing"->PreparingScreen(state,model,tv)
                                }
                            }
                            }
                            if(state.busy&&page in setOf("home","library","history")) {
                                Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp),shape=MaterialTheme.shapes.medium) {
                                    LoadingIndicator("Открываем карточку…",Modifier.widthIn(max=280.dp))
                                }
                            }
                        }
                        if(releaseSheet) saved.SaveableStateProvider(releaseKey) {
                            ModalBottomSheet(
                                onDismissRequest={model.back()},
                                sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
                                containerColor=colors.surface,
                                dragHandle={BottomSheetDefaults.DragHandle()},
                            ) {
                                Box(Modifier.fillMaxWidth().fillMaxHeight(if(LocalConfiguration.current.fontScale>=1.5f) 1f else .88f).navigationBarsPadding().padding(horizontal=20.dp)) {
                                    ReleasesScreen(state,model,false)
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
@Composable internal fun Action(text:String,tv:Boolean,modifier:Modifier=Modifier,enabled:Boolean=true,secondary:Boolean=false,icon:ImageVector?=null,loading:Boolean=false,pill:Boolean=false,gradient:Brush?=null,onClick:()->Unit) {
    if(tv) {TvActionButton(text,modifier,enabled,secondary,icon,loading,onClick=onClick);return}
    val colors=MaterialTheme.colorScheme
    val shape=if(pill)RoundedCornerShape(50) else MaterialTheme.shapes.medium
    val content:@Composable ()->Unit={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        val contentColor=LocalContentColor.current
        if(loading) CircularProgressIndicator(Modifier.size(20.dp).clearAndSetSemantics {},color=contentColor,strokeWidth=2.dp) else if(icon!=null) Icon(icon,null,Modifier.size(20.dp),tint=contentColor)
        Text(text,fontSize=16.sp,lineHeight=24.sp,fontWeight=FontWeight.Medium,color=contentColor,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.weight(1f,fill=false))
    }}
    Button(
        onClick=onClick,enabled=enabled&&!loading,shape=shape,
        modifier=(if(LocalConfiguration.current.screenWidthDp>=600) Modifier.widthIn(max=320.dp) else Modifier)
            .then(modifier).semantics {
                if(loading) {stateDescription="Выполняется";progressBarRangeInfo=ProgressBarRangeInfo.Indeterminate}
            }.heightIn(min=if(secondary) 48.dp else 52.dp).tvFocusRing(true,shape)
            .then(if(gradient==null) Modifier else Modifier.background(gradient,shape)),
        colors=ButtonDefaults.buttonColors(
            containerColor=when {gradient!=null -> Color.Transparent; secondary -> colors.surface; else -> colors.primary},
            contentColor=when {gradient!=null -> Color.White; secondary -> colors.onSurface; else -> colors.onPrimary},
        ),
        contentPadding=PaddingValues(horizontal=if(secondary) 20.dp else 24.dp,vertical=12.dp),
    ) {content()}
}
@Composable internal fun Empty(title:String,body:String,tv:Boolean,action:String="Открыть поиск",onClick:()->Unit) {
    val short=!tv&&LocalConfiguration.current.screenHeightDp<480
    Column(Modifier.fillMaxWidth().padding(vertical=if(short) 8.dp else 36.dp),verticalArrangement=Arrangement.spacedBy(if(short) 12.dp else 20.dp)) {
        if(!short) Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.surface,RoundedCornerShape(22.dp)),contentAlignment=Alignment.Center) {Icon(Icons.Outlined.SearchOff,null,Modifier.size(36.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
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
            val codec = Regex("(?i)(HEVC|H[ .]?265|x265|H[ .]?264|x264|AV1)").find(release.title)?.value
            val hdr = Regex("(?i)(HDR10\\+?|HDR|SDR)").find(release.title)?.value
            val quality = release.resolution.takeUnless { it == "Не указано" }
            if (tv || quality != null || codec != null || hdr != null || release.size != null || release.seeds != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    quality?.let { Badge(it) }
                    codec?.let { Badge(it.uppercase()) }
                    hdr?.let { Badge(it.uppercase()) }
                    if (tv || release.size != null) Badge(sizeLabel(release.size))
                    if (tv || release.seeds != null) {
                        Badge(release.seeds?.let {"$it сидов"} ?: "Сиды не указаны",accent=(release.seeds ?: 0)>0,warning=release.seeds==0)
                    }
                }
            }
            Text(release.source,fontSize=if(tv) 16.sp else 13.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun TvHomeScreen(s:WatchState,library:WatchModel.LibrarySnapshot?,model:WatchModel) {
    val entries=library?.titles.orEmpty()
    var featured by rememberSaveable(s.catalogSection) {mutableStateOf<String?>(null)}
    var lastCard by rememberSaveable(s.catalogSection) {mutableStateOf<String?>(null)}
    val restoreCard=remember(s.catalogSection) {lastCard}
    val hero=s.catalog.firstOrNull {it.id==featured} ?: entries.firstOrNull {it.metadata?.id==featured}?.metadata ?: s.catalog.firstOrNull()
    val continuing=remember(library) {entries.filter {it.canContinue(library?.unfinishedReleaseIds.orEmpty())}}
    val inset=tvUnit(40f)
    val hasCategories=s.config.metadataProvider=="tmdb"||s.config.demo
    val tabsFocus=remember {FocusRequester()}
    val shelfFocus=remember {FocusRequester()}
    var lastShelfFocus by remember(s.catalogSection) {mutableStateOf<FocusRequester?>(null)}
    val trackShelfFocus:(FocusRequester,Boolean)->Unit=remember(s.catalogSection) {{target,focused->
        if(focused)lastShelfFocus=target else if(lastShelfFocus===target)lastShelfFocus=null
    }}
    var started by remember {mutableStateOf(false)}
    val hasCards=continuing.isNotEmpty()||s.catalog.isNotEmpty()||s.config.demo
    LaunchedEffect(hasCards) {if(hasCards&&!started) {started=true;delay(100);if(lastCard==null)shelfFocus.requestFocus()}}
    val rows=buildList {
        if(s.catalog.isEmpty()&&!s.config.demo)add("status")
        if(continuing.isNotEmpty())add("continue")
        if(s.catalog.isNotEmpty())add("catalog")
        if(s.config.demo)add("demo")
        if(s.catalogMessage.isNotBlank()&&s.catalog.isNotEmpty()&&!s.config.demo)add("message")
        add("updates")
    }
    WatchTheme(if(hero!=null||s.config.demo) "dark" else s.config.theme) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if(hero!=null||s.config.demo) {
                Crossfade(targetState=hero,animationSpec=tween(220),label="Featured backdrop") {featuredTitle->
                    Artwork(featuredTitle,s.config,Modifier.fillMaxSize(),backdrop=true,demo=featuredTitle==null&&s.config.demo)
                }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha=.5f)))
                ArtScrim(Modifier.fillMaxSize(),true);ArtScrim(Modifier.fillMaxSize())
            }
            Column(Modifier.fillMaxSize()) {
                if(s.config.metadataProvider=="tmdb"||s.config.demo) Row(
                    Modifier.fillMaxWidth().testTag("home-categories").background(MaterialTheme.colorScheme.background.copy(alpha=.94f))
                        .padding(horizontal=inset,vertical=8.dp)
                        .focusProperties {down=lastShelfFocus ?: shelfFocus}.focusRestorer().focusGroup(),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.weight(1f)) {WatchTabs(CatalogSection.entries.map {it.label},s.catalogSection.label,true,selectedFocus=tabsFocus) {label->model.catalogSection(CatalogSection.entries.first {it.label==label})}}
                    if(s.config.demo)Badge("ДЕМО",true)
                }
                CompositionLocalProvider(LocalTvShelfFocus provides trackShelfFocus) {
                    key(s.catalogSection) {TvHomeShelves(rows,shelfFocus,Modifier.weight(1f)) {row->when(row) {
                        "continue"->{
                            Box(Modifier.padding(horizontal=inset)) {SectionTitle("Продолжить просмотр")}
                            TvCarousel(inset,14.dp,up=if(hasCategories)tabsFocus else null) {items(continuing,key={it.key}) {e->
                                Box(Modifier.width(tvUnit(512f))) {LibraryCard(e,library?.latestProgress.orEmpty(),s,model,true,
                                    restoreFocus=restoreCard=="library:${e.key}",onFocus={lastCard="library:${e.key}";e.metadata?.let {featured=it.id}})}
                            }}
                        }
                        "catalog"->{
                            Box(Modifier.padding(horizontal=inset)) {SectionTitle("Ваши подборки")}
                            val cardWidth = tvUnit(200f)
                            TvCarousel(inset,up=if(continuing.isEmpty()&&hasCategories)tabsFocus else null) {items(s.catalog,key={it.id}) {m->
                                Box(Modifier.width(cardWidth)) {
                                    MetadataCard(m,s.config,true,restoreCard=="metadata:${m.id}",onFocus={lastCard="metadata:${m.id}";featured=m.id}) {model.metadata(m)}
                                }
                            }}
                        }
                        "demo"->{
                            Box(Modifier.padding(horizontal=inset)) {SectionTitle("Тестовые раздачи")}
                            TvCarousel(inset) {items(DemoReleases.items,key={it.id}) {r->Box(Modifier.width(tvUnit(360f))) {ReleaseCard(r,true,s.focusKey==r.id) {model.open(r)}}}}
                        }
                        "status"->Box(Modifier.padding(horizontal=inset)) {
                            when {
                                s.catalogBusy->LoadingIndicator("Загрузка каталога")
                                s.config.metadataProvider!="tmdb"->Empty("Кино. На вашем экране.","Найдите раздачу по названию. Каталог и постеры можно подключить в настройках.",true,"Поиск раздач") {model.page("search")}
                                else->Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Heading(if(s.catalogMessage.isNotBlank())"Не удалось загрузить раздел" else "В разделе пока нет карточек")
                                    Text("Раздел «${s.catalogSection.label}». Попробуйте обновить каталог или найдите название через поиск.")
                                    Action("Обновить раздел",true){model.refreshCatalog(true)}
                                    Action("Поиск",true,secondary=true){model.page("search")}
                                }
                            }
                        }
                        "message"->Text(s.catalogMessage,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=inset))
                        "updates"->Box(Modifier.padding(horizontal=inset)) {UpdateSection(model.updates,true,compact=true)}
                    }}}
                }
            }
        }}
    }
}

private fun LibraryTitle.canContinue(unfinished:Set<String>) = entry.openedAt>0&&releaseIds.any {it in unfinished}

@Composable private fun HomeScreen(s:WatchState,library:WatchModel.LibrarySnapshot?,model:WatchModel,tv:Boolean) {
    if(tv) {TvHomeScreen(s,library,model);return}
    val entries=library?.titles.orEmpty()
    val continuing=remember(library) {entries.filter {it.canContinue(library?.unfinishedReleaseIds.orEmpty())}}
    val category=s.catalogSection.label;var featured by rememberSaveable(s.catalogSection) {mutableStateOf<String?>(null)}
    val catalogue=s.catalog
    val hero=catalogue.firstOrNull {it.id==featured} ?: entries.firstOrNull {it.metadata?.id==featured}?.metadata ?: catalogue.firstOrNull()
    val cinematic=hero!=null||s.config.demo
    val inset=20.dp
    WatchTheme(s.config.theme) {CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val columns=if(maxWidth>=680.dp) 2 else 1
        val catalogueCardWidth = ((maxWidth - inset - 28.dp) / 2).coerceIn(120.dp, 200.dp)
        Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).testTag("home-list"),state=rememberLazyListState(),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=24.dp)) {
            if(s.config.metadataProvider=="tmdb"||s.config.demo) item {
                    Column(Modifier.padding(horizontal=16.dp).animateContentSize(tween(240)),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {CatalogSection.entries.forEach {section->Pill(section.label,s.catalogSection==section,gradient=true) {model.catalogSection(section)}}}
                        if (cinematic) {
                            val compact=LocalConfiguration.current.screenHeightDp<480
                            val background=MaterialTheme.colorScheme.background
                            var captionHeight by remember {mutableIntStateOf(0)}
                            var actionHeight by remember {mutableIntStateOf(0)}
                            Box(
                                Modifier.fillMaxWidth().heightIn(min=if(compact) 226.dp else 296.dp)
                                    .clip(RoundedCornerShape(topStart=20.dp,topEnd=20.dp)),
                            ) {
                                Artwork(hero,s.config,Modifier.matchParentSize().blurPosterCaption(captionHeight),
                                    backdrop=true,demo=hero==null&&s.config.demo)
                                Box(Modifier.matchParentSize().drawWithCache {
                                    val shadeEnd=(size.height-captionHeight+20.dp.toPx()).coerceIn(1f,size.height)
                                    val shade=Brush.verticalGradient(
                                        colors=listOf(Color.Transparent,Color(0xE610141E)),
                                        startY=(shadeEnd-60.dp.toPx()).coerceAtLeast(0f),endY=shadeEnd,
                                    )
                                    val fade=Brush.verticalGradient(
                                        0f to Color.Transparent,.46f to background.copy(alpha=.30f),1f to background,
                                        startY=(size.height-actionHeight-22.dp.toPx()).coerceIn(0f,size.height-1f),
                                        endY=size.height,
                                    )
                                    onDrawBehind {drawRect(shade);drawRect(fade)}
                                })
                                Column(
                                    Modifier.align(Alignment.BottomStart).widthIn(max=640.dp).fillMaxWidth()
                                        .onSizeChanged {captionHeight=it.height}
                                        .padding(start=16.dp,end=16.dp,top=14.dp,bottom=8.dp),
                                    verticalArrangement=Arrangement.spacedBy(4.dp),
                                ) {
                                    val metadataInk=Color(0xFFE5E8EE)
                                    Text(if(s.config.demo) "ДЕМОНСТРАЦИЯ" else "НА ВЕЧЕР",
                                        fontSize=11.sp,letterSpacing=1.sp,color=metadataInk)
                                    Text(hero?.title ?: "Тихая орбита",
                                        fontSize=30.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold,color=Color.White)
                                    Text(hero?.let {"${it.year ?: "Год не указан"} · ${titleKind(it)}"} ?: "Локальное тестовое видео",
                                        fontSize=13.sp,color=metadataInk)
                                    Action(
                                        if(hero?.kind=="tv") "О сериале" else "О фильме",false,
                                        Modifier.fillMaxWidth().padding(top=6.dp).onSizeChanged {actionHeight=it.height}
                                            .testTag("hero-open"),
                                        icon=Icons.Outlined.PlayArrow,pill=true,
                                        gradient=Brush.horizontalGradient(listOf(Color(0xFF006E48),Color(0xFF006D7B))),
                                    ) {
                                        if(hero!=null) model.metadata(hero,"hero:${hero.id}")
                                        else model.open(DemoReleases.items.first(),"hero:${DemoReleases.items.first().id}")
                                    }
                                }
                            }
                        }
                    }
            }
            if(!cinematic&&s.config.metadataProvider!="tmdb") item {Box(Modifier.padding(horizontal=inset)) {Empty("Кино. На вашем экране.","Найдите раздачу по названию. Каталог и постеры можно подключить в настройках.",tv,"Поиск раздач") {model.page("search")}}}
            if(s.catalogBusy&&s.catalog.isEmpty()&&!s.config.demo) item {LoadingIndicator("Загрузка каталога",Modifier.padding(horizontal=inset))}
            if(!s.catalogBusy&&catalogue.isEmpty()&&s.config.metadataProvider=="tmdb"&&!s.config.demo) item {
                Column(Modifier.padding(horizontal=inset),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Heading(if(s.catalogMessage.isNotBlank())"Не удалось загрузить раздел" else "В разделе пока нет карточек")
                    Text("Раздел «$category». Попробуйте обновить каталог или найдите название через поиск.")
                    Action("Обновить раздел",tv){model.refreshCatalog(true)}
                    Action("Поиск",tv,secondary=true){model.page("search")}
                }
            }
            if(continuing.isNotEmpty()) {
                item {Box(Modifier.padding(horizontal=inset)) {SectionTitle("Продолжить","Вся история") {model.page("history")}}}
                items(continuing.take(3).chunked(columns),key={"continue:${it.first().entry.id}"}) {row->
                    Row(Modifier.padding(horizontal=inset),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        row.forEach {e->Box(Modifier.weight(1f)) {LibraryCard(e,library?.latestProgress.orEmpty(),s,model,false)}}
                        repeat(columns-row.size) {Spacer(Modifier.weight(1f))}
                    }
                }
            }
            if(catalogue.isNotEmpty()) {
                item {Box(Modifier.padding(horizontal=inset)) {SectionTitle("Ваши подборки")}}
                item {
                    LazyRow(contentPadding=PaddingValues(horizontal=inset),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        items(catalogue,key={it.id}) {m ->
                            Box(Modifier.width(catalogueCardWidth)) {
                                MetadataCard(m,s.config,false,onFocus={featured=m.id}) {model.metadata(m)}
                            }
                        }
                    }
                }
            }
            if(s.config.demo) {
                item {Box(Modifier.padding(horizontal=inset)) {SectionTitle("Тестовые раздачи")}}
                items(DemoReleases.items.chunked(columns),key={it.first().id}) {row->
                    Row(Modifier.padding(horizontal=inset),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        row.forEach {r->Box(Modifier.weight(1f)) {ReleaseCard(r,tv,s.focusKey==r.id) {model.open(r)}}}
                        repeat(columns-row.size) {Spacer(Modifier.weight(1f))}
                    }
                }
            }
            if(s.catalogMessage.isNotBlank()&&!s.config.demo) item {Text(s.catalogMessage,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=inset))}
            item {Box(Modifier.padding(horizontal=inset)) {UpdateSection(model.updates,tv,compact=true)}}
        }
        }
    }
}}}
private fun releasePageFooter(cursor:String?) = "release-page-footer/${cursor.orEmpty()}"

@Composable private fun LoadReleasePageOnScroll(
    listState:LazyListState,cursor:String?,releases:List<Release>,enabled:Boolean,load:()->Unit
) {
    val action by rememberUpdatedState(load)
    val nearEnd=remember(releases,cursor) {
        releases.takeLast(5).mapTo(mutableSetOf<Any>()) {it.id}.apply {add(releasePageFooter(cursor))}
    }
    LaunchedEffect(listState,cursor,enabled,nearEnd) {
        if(cursor!=null&&enabled) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any {it.key in nearEnd} }.first {it}
            action()
        }
    }
}

@Composable private fun ReleasePageAction(s:WatchState,tv:Boolean,load:()->Unit,retry:()->Unit) {
    when {
        s.nextReleaseCursor!=null&&s.failures.isNotEmpty() -> Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(s.failures.joinToString("\n"),color=MaterialTheme.colorScheme.error)
            Action("Повторить поиск",tv,secondary=true,onClick=retry)
        }
        s.nextReleaseCursor!=null -> Box(Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            if(s.loadingMore) LoadingIndicator("Загружаем раздачи…")
        }
        s.loadingMore -> LoadingIndicator("Загружаем раздачи…")
        else -> Action("Загрузить ещё",tv,secondary=true,onClick=load)
    }
}

@Composable private fun SearchScreen(s:WatchState,model:WatchModel,tv:Boolean,listState:LazyListState) {
    val keyboard=LocalSoftwareKeyboardController.current
    val fieldFocus=remember {FocusRequester()}
    val focusManager=LocalFocusManager.current
    var focusResultsOnReady by remember {mutableStateOf(false)}
    LaunchedEffect(tv,s.busy,focusResultsOnReady) {
        if(tv&&focusResultsOnReady&&!s.busy) {withFrameNanos {};focusManager.moveFocus(FocusDirection.Down);focusResultsOnReady=false}
    }
    var resultTab by rememberSaveable {mutableStateOf("Каталог")}
    val showReleases=s.metadata.isEmpty()||resultTab=="Раздачи"
    LoadReleasePageOnScroll(listState,s.nextReleaseCursor,s.results,
        showReleases&&!s.busy&&!s.loadingMore&&s.failures.isEmpty()) {model.loadMore()}
    val context=LocalContext.current
    val voice=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result -> if(result.resultCode==android.app.Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(model::search)}
    val voiceAvailable=remember {context.packageManager.resolveActivity(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),0)!=null}
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val posterColumns=if(tv) 4 else (maxWidth/180.dp).toInt().coerceIn(2,6)
    val posterRows=remember(s.metadata,posterColumns) {s.metadata.chunked(posterColumns)}
    Column(Modifier.fillMaxSize().padding(top=if(tv) 24.dp else 0.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    OutlinedTextField(value=s.query,onValueChange=model::search,label={Text("Название фильма или раздачи")},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={Row {if(s.query.isNotEmpty()) IconButton(onClick={model.search("")}) {Icon(Icons.Outlined.Close,"Очистить поиск")};if(voiceAvailable) IconButton(onClick={voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU"))}) {Icon(Icons.Outlined.Mic,"Голосовой поиск")}}},singleLine=true,
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide();if(tv) focusResultsOnReady=true}),modifier=Modifier.fillMaxWidth().requiredHeightIn(min=64.dp).tvFieldNavigation().focusRequester(fieldFocus).testTag("search-field"),shape=RoundedCornerShape(28.dp))
    Spacer(Modifier.height(4.dp))
    if(tv) {
        if(s.busy) LoadingIndicator("Поиск…")
    } else AnimatedVisibility(
        visible=s.busy,
        enter=fadeIn(tween(150))+expandVertically(tween(200),expandFrom=Alignment.Top),
        exit=fadeOut(tween(100))+shrinkVertically(tween(180),shrinkTowards=Alignment.Top),
    ) {LoadingIndicator("Поиск…")}
    if(s.metadata.isNotEmpty()) {
        Box(if(tv) Modifier else Modifier.softEntrance("search-tabs",offset=0.dp)) {
            WatchTabs(listOf("Каталог","Раздачи"),resultTab,tv) {resultTab=it}
        }
    }
    LazyColumn(Modifier.weight(1f),state=listState,verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=16.dp)) {
        if(s.failures.isNotEmpty()&&s.nextReleaseCursor==null) item { Text("Не ответили источники: ${s.failures.joinToString("; ")}",color=MaterialTheme.colorScheme.error); Action("Повторить недоступные источники",tv) {model.loadMore(true)} }
        if(!s.busy&&s.query.length>=2&&s.results.isEmpty()&&s.metadata.isEmpty()) item {Empty(if(s.message.startsWith("Ничего")) "Ничего не найдено" else "Поиск недоступен",s.message.ifBlank {"Попробуйте другое название или проверьте подключение поиска."},tv,"Изменить запрос") {model.search("");fieldFocus.requestFocus();keyboard?.show()}}
        else if(s.message.isNotEmpty()&&s.metadata.isEmpty()) item {Text(s.message,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
        if(s.query.length<2) item { Text("Введите не менее двух символов. Поиск раздач работает без каталога.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(s.metadata.isNotEmpty()) item {SectionTitle("Результаты поиска");if(s.metadataCached) Text("Данные из кеша")}
        if(resultTab=="Каталог") items(posterRows,key={it.joinToString {m->m.id}}) {row -> Row(if(tv) Modifier else Modifier.animateItem(placementSpec=tween(220)),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            row.forEach {m -> Column(Modifier.weight(1f)) {MetadataCard(m,s.config,tv,s.focusKey==m.id) {keyboard?.hide();model.metadata(m)}}}
            repeat(posterColumns-row.size) {Spacer(Modifier.weight(1f))}
        }}
        if(showReleases) items(s.results,key={it.id}) {r->
            ReleaseCard(r,tv,s.focusKey==r.id) {keyboard?.hide();model.open(r)}
        }
        if(showReleases&&s.more&&!s.busy) item(key=releasePageFooter(s.nextReleaseCursor)) {
            ReleasePageAction(s,tv,{model.loadMore()},model::retryReleaseSearch)
        }
    }
}
}
}
@Composable internal fun MetadataCard(
    m:Metadata,c:Config,tv:Boolean,restoreFocus:Boolean=false,onFocus:(()->Unit)?=null,
    modifier:Modifier=Modifier,onClick:()->Unit
) {
    val requester=remember {FocusRequester()};var focused by remember {mutableStateOf(false)}
    LaunchedEffect(restoreFocus) {if(tv&&restoreFocus) {delay(100);requester.requestFocus()}}
    LaunchedEffect(focused,m.id) {if(focused) {delay(180);onFocus?.invoke()}}
    NativeCard(tv,modifier.fillMaxWidth().focusRequester(requester).onFocusChanged {focused=it.hasFocus},
        onClick=onClick,outlined=!tv) {
        val frame = if(tv) Modifier.padding(TvPosterClearance).tvPosterFocus(focused) else Modifier
        Box(frame) {
            CinematicTitleTile(m,c,m.title,titleSubtitle(m),artworkTag="poster-${m.id}")
        }
    }
}

private fun titleSubtitle(metadata:Metadata) = listOfNotNull(metadata.year?.toString(),titleKind(metadata)).joinToString(" · ")

private const val PosterAspectRatio = 2f / 3f

@Composable private fun CinematicTitleTile(
    metadata:Metadata?,
    config:Config,
    title:String,
    subtitle:String,
    artworkTag:String,
    demo:Boolean=false,
    action:(@Composable BoxScope.()->Unit)?=null,
    progress:(@Composable ()->Unit)?=null
) {
    CinematicArtworkTile(title,subtitle,artworkTag,PosterAspectRatio,action,progress) { modifier ->
        Artwork(metadata,config,modifier,backdrop=true,demo=demo,alignment=Alignment.Center,posterFirst=true)
    }
}

@Composable internal fun CinematicArtworkTile(
    title:String,
    subtitle:String,
    artworkTag:String,
    aspectRatio:Float,
    action:(@Composable BoxScope.()->Unit)?=null,
    progress:(@Composable ()->Unit)?=null,
    artwork:@Composable (Modifier)->Unit,
) {
    val captionBackground = Color(0xFF161B24)
    var captionHeight by remember {mutableIntStateOf(0)}
    Box(Modifier.fillMaxWidth().aspectRatio(aspectRatio).clip(RoundedCornerShape(16.dp))) {
        artwork(Modifier.matchParentSize().testTag(artworkTag).blurPosterCaption(captionHeight))
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().onSizeChanged {captionHeight=it.height}
                .background(Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to captionBackground.copy(alpha=0.66f),
                    1f to captionBackground.copy(alpha=0.82f)
                )).padding(start=12.dp,end=12.dp,top=24.dp,bottom=12.dp),
            verticalArrangement=Arrangement.spacedBy(4.dp)
        ) {
            Text(
                title,color=Color.White,fontSize=16.sp,lineHeight=20.sp,fontWeight=FontWeight.Bold,
                maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if(subtitle.isNotBlank()) Text(subtitle,color=Color(0xFFBDC7D3),fontSize=13.sp,lineHeight=16.sp,
                maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            progress?.invoke()
        }
        action?.invoke(this)
    }
}

@Composable private fun Modifier.blurPosterCaption(captionHeight:Int):Modifier {
    if(Build.VERSION.SDK_INT<31||captionHeight==0) return this
    val layer=rememberGraphicsLayer()
    return drawWithCache {
        val radius=12.dp.toPx()
        val captionTop=(size.height-captionHeight).coerceAtLeast(0f)
        val captionBounds=Rect(0f,captionTop,size.width,size.height)
        val blend=Brush.verticalGradient(listOf(Color.Transparent,Color.Black),
            startY=captionTop,endY=captionTop+24.dp.toPx())
        val paint=Paint()
        layer.renderEffect=BlurEffect(radius,radius,TileMode.Clamp)
        onDrawWithContent {
            drawContent()
            layer.record {this@onDrawWithContent.drawContent()}
            clipRect(top=captionTop) {
                drawContext.canvas.withSaveLayer(captionBounds,paint) {
                    drawLayer(layer)
                    drawRect(blend,topLeft=Offset(0f,captionTop),size=captionBounds.size,blendMode=BlendMode.DstIn)
                }
            }
        }
    }
}
@Composable private fun <T:Any> backgroundValue(vararg inputs:Any?,calculate:suspend CoroutineScope.()->T):T? {
    var value by remember(*inputs) {mutableStateOf<T?>(null)}
    LaunchedEffect(*inputs) {value=withContext(Dispatchers.Default,calculate)}
    return value
}

private val russianRelease=Regex("(?i)(rus|russian|рус|дубляж)")
private data class IndexedRelease(val release:Release,val quality:String,val seasons:Set<Int>,val voices:Set<String>,val russian:Boolean)
private class ReleaseOptions(
    val rows:List<IndexedRelease>,
    val seasons:List<Int>,
    val voices:List<String>,
    val qualities:List<String>,
    val sources:List<String>,
)
private data class ReleaseListFilters(val criteria:ReleaseFilter,val source:String,val seeds:String,val language:String)
private data class VisibleReleaseList(val options:ReleaseOptions,val filters:ReleaseListFilters,val releases:List<Release>)

@Composable internal fun ReleasesScreen(
    s:WatchState,model:WatchModel,tv:Boolean,
    detailHeader:(@Composable () -> Unit)?=null,
    detailInset:Dp=0.dp,
    sourceStatus:(@Composable () -> Unit)?=null,
    onLoadMore:()->Unit={model.loadMore()},
    onRetrySearch:()->Unit=model::retryReleaseSearch,
) {
    val remote by model.remote.collectAsStateWithLifecycle()
    val preferences=LocalContext.current.getSharedPreferences("playback-preferences",0)
    var filter by rememberSaveable {mutableStateOf(preferences.getString("quality","Все") ?: "Все")}
    var selectedId by rememberSaveable {mutableStateOf(s.selected?.id)}
    var source by rememberSaveable {mutableStateOf("Все источники")}
    var seeds by rememberSaveable {mutableStateOf("Любые сиды")}
    var language by rememberSaveable {mutableStateOf("Любой язык")}
    var season by rememberSaveable {mutableStateOf(s.releaseSeason)}
    var voice by rememberSaveable {mutableStateOf<String?>(null)}
    var filters by remember {mutableStateOf(false)}
    var focusInitialRelease by rememberSaveable {mutableStateOf(tv&&detailHeader==null)}
    val releaseScroll=rememberLazyListState()
    if(s.busy) {
        Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            LoadingIndicator("Ищем раздачи…")
            Action("Отменить",tv,secondary=true) {model.back()}
        }
        return
    }
    val selectedFallback=s.selected.takeIf {s.nextReleaseCursor==null}
    val indexedOptions=backgroundValue(s.results,selectedFallback) {
        val candidates=(s.results+listOfNotNull(selectedFallback)).distinctBy {it.id}
        val rows=candidates.map {
            ensureActive()
            IndexedRelease(it,it.resolution,seasonNumbers(it.title),releaseVoices(it.title),russianRelease.containsMatchIn(it.title))
        }
        ReleaseOptions(rows,rows.flatMap {it.seasons}.distinct().sorted(),rows.flatMap {it.voices}.distinct().sorted(),
            rows.map {it.quality}.distinct().sorted(),candidates.map {it.source}.distinct())
    }
    val criteria=ReleaseFilter(filter,season,voice)
    val selectedSource=source
    val selectedSeeds=seeds
    val selectedLanguage=language
    val currentFilters=ReleaseListFilters(criteria,selectedSource,selectedSeeds,selectedLanguage)
    var displayed by remember(s.query,s.selectedMetadata?.id) {mutableStateOf<VisibleReleaseList?>(null)}
    LaunchedEffect(indexedOptions,currentFilters,s.query,s.selectedMetadata?.id) {
        val options=indexedOptions ?: return@LaunchedEffect
        val filtered=withContext(Dispatchers.Default) {options.rows.filter {row->
            ensureActive()
            val r=row.release
            (criteria.quality=="Все"||row.quality==criteria.quality)&&
                (criteria.season==null||criteria.season in row.seasons)&&
                (criteria.voice==null||criteria.voice in row.voices)&&
                (selectedSource=="Все источники"||r.source==selectedSource)&&
                when(selectedSeeds) {"Есть сиды" -> (r.seeds ?: 0)>0;"0 сидов" -> r.seeds==0;"Сиды не указаны" -> r.seeds==null;else -> true}&&
                when(selectedLanguage) {"Русский (название)" -> row.russian;"Русский не указан" -> !row.russian;else -> true}
        }.map {it.release}}
        displayed=VisibleReleaseList(options,currentFilters,filtered)
    }
    val options=displayed?.options
    val filtering=displayed?.filters!=currentFilters
    val releases=displayed?.releases.orEmpty()
    LoadReleasePageOnScroll(releaseScroll,s.nextReleaseCursor,releases,
        !filtering&&options===indexedOptions&&!s.loadingMore&&s.failures.isEmpty(),onLoadMore)
    val seasons=options?.seasons.orEmpty()
    val voices=options?.voices.orEmpty()
    val activeFilters=listOf(filter!="Все",season!=null,voice!=null,source!="Все источники",seeds!="Любые сиды",language!="Любой язык").count {it}
    fun resetFilters() {
        focusInitialRelease=false
        filter="Все";season=null;voice=null;source="Все источники";seeds="Любые сиды";language="Любой язык"
        releaseScroll.requestScrollToItem(if(detailHeader==null) 0 else 1)
    }
    if(filters) ReleaseFiltersDialog(tv,releases.size.takeUnless {filtering},onReset={resetFilters()},onDismiss={filters=false}) {
        ReleaseFilterChoices("Качество",Icons.Outlined.HighQuality,(listOf("Все",filter)+options?.qualities.orEmpty()).distinct(),filter,tv){filter=it}
        if(seasons.isNotEmpty())ReleaseFilterChoices("Сезон",Icons.Outlined.Layers,listOf("Все сезоны")+seasons.map {"Сезон $it"},season?.let {"Сезон $it"} ?: "Все сезоны",tv){label->season=seasons.firstOrNull {"Сезон $it"==label}}
        if(voices.isNotEmpty())ReleaseFilterChoices("Озвучка",Icons.Outlined.RecordVoiceOver,listOf("Любая озвучка")+voices,voice ?: "Любая озвучка",tv,hint="Определена по названию раздачи. Точные дорожки доступны в плеере."){voice=it.takeUnless {it=="Любая озвучка"}}
        ReleaseFilterChoices("Источник",Icons.Outlined.Storage,listOf("Все источники")+options?.sources.orEmpty(),source,tv){source=it}
        ReleaseFilterChoices("Сиды",Icons.Outlined.PeopleOutline,listOf("Любые сиды","Есть сиды","0 сидов","Сиды не указаны"),seeds,tv){seeds=it}
        ReleaseFilterChoices("Язык",Icons.Outlined.Translate,listOf("Любой язык","Русский (название)","Русский не указан"),language,tv,hint="Язык указан по названию раздачи. Проверьте аудиодорожки в плеере."){language=it}
    }
    val header:@Composable ()->Unit={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(!tv&&detailHeader==null&&LocalConfiguration.current.screenWidthDp<840) Heading("Выберите раздачу")
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            val quickQualities=listOf("Все","1080p","2160p","720p",if(tv)"Не указано" else filter).distinct()
            quickQualities.forEach {label->
                Pill(if(label=="2160p") "4K" else label,filter==label,tv) {focusInitialRelease=false;filter=label}
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Action(if(activeFilters==0)"Фильтры" else "Фильтры · $activeFilters",tv,secondary=true,icon=Icons.Outlined.Tune){focusInitialRelease=false;filters=true}
            if(activeFilters>0)TextButton(onClick={resetFilters()},modifier=Modifier.tvFocusRing(tv)){Text("Сбросить всё")}
        }
        if(seasons.isNotEmpty()) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            item {Pill("Все сезоны",season==null,tv){focusInitialRelease=false;season=null}}
            items(seasons,key={it}) {number->Pill("Сезон $number",season==number,tv){focusInitialRelease=false;season=number}}
        }
        if(voice!=null||source!="Все источники"||seeds!="Любые сиды"||language!="Любой язык")FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            listOfNotNull(voice,source.takeUnless {it=="Все источники"},seeds.takeUnless {it=="Любые сиды"},language.takeUnless {it=="Любой язык"}).forEach {Badge(it,accent=true)}
        }
        if(filtering) LoadingIndicator("Применяем фильтры…")
        else {
            val resourceContext=LocalContext.current
            val countText=remember(resourceContext,releases.size) {releaseCountText(resourceContext,releases.size)}
            Text(if(tv) "$countText · свойства указаны источником" else countText,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }}
    val scrollingHeader=!tv||detailHeader!=null
    val entrance=if(!tv&&detailHeader==null) Modifier.softEntrance("releases-loaded",offset=4.dp) else Modifier
    Column(entrance,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(!scrollingHeader) header()
        if(displayed==null&&detailHeader==null) {
            if(scrollingHeader) Box(Modifier.padding(horizontal=detailInset)) {header()}
            Spacer(Modifier.weight(1f))
        } else LazyColumn(Modifier.weight(1f).testTag("release-list"),state=releaseScroll,verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=12.dp)) {
            if(detailHeader!=null) item(key="detail-header") {detailHeader()}
            if(scrollingHeader && sourceStatus==null) item(key="release-filters-header") {Box(Modifier.padding(horizontal=detailInset)) {header()}}
            items(releases,key={it.id}) {r->Box(Modifier.padding(horizontal=detailInset)) {
                ReleaseCard(r,tv,focus=focusInitialRelease&&r.id==(s.selected?.id ?: releases.firstOrNull()?.id),selected=selectedId==r.id) {
                    if(!filtering) {
                        selectedId=r.id
                        if(tv) model.playRelease(r,season,fromDetailTab=detailHeader!=null)
                    }
                }
            }}
            if(sourceStatus!=null) item {Box(Modifier.padding(horizontal=detailInset)) {sourceStatus()}}
            else if(s.nextReleaseCursor!=null||s.loadingMore) item(key=releasePageFooter(s.nextReleaseCursor)) {
                Box(Modifier.padding(horizontal=detailInset)) {ReleasePageAction(s,tv,onLoadMore,onRetrySearch)}
            }
            else if(!filtering&&releases.isEmpty()) item {Box(Modifier.padding(horizontal=detailInset)) {
                if(options?.rows.isNullOrEmpty()&&s.message.isNotBlank()&&s.selectedMetadata!=null) {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(s.message,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                        if(s.failures.isNotEmpty()) Text(s.failures.joinToString("\n"),color=MaterialTheme.colorScheme.error)
                        Action("Повторить поиск",tv,secondary=true) {
                            val seasonNumber=s.releaseSeason
                            val episodeNumber=s.releaseEpisode
                            if(seasonNumber!=null) model.findSeriesReleases(s.selectedMetadata,seasonNumber,episodeNumber,s.episodeTargetId,s.episodeDownload)
                            else model.findReleases()
                        }
                        if(s.failures.isNotEmpty()) Action("Настроить источник",tv,secondary=true) {model.page("settings-search")}
                    }
                } else Empty("Нет раздач с этими фильтрами","Попробуйте другой источник или качество.",tv,"Сбросить фильтры") {resetFilters()}
            }}
        }
        val selected=if(filtering) null else releases.firstOrNull {it.id==selectedId}
        if(!tv&&releases.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=detailInset).padding(bottom=12.dp)) {
            val gap=8.dp
            val density = LocalDensity.current
            val buttonWidth = if (maxWidth >= 320.dp * density.fontScale.coerceAtLeast(1f)) {
                with(density) { ((constraints.maxWidth - gap.roundToPx()) / 2).toDp().coerceAtMost(240.dp) }
            } else maxWidth
            val target=remote.target?.takeIf {remote.connected&&remote.profileKey==s.config.profileKey}
            if (s.episodeDownload || s.episodeTargetId != null) {
                val download = s.episodeDownload
                val available = if (download) !s.config.demo else target?.id == s.episodeTargetId && remote.canLaunch
                Action(if (download && s.releaseEpisode == null) "Скачать сезон" else if (download) "Скачать" else "Смотреть на ТВ", false, Modifier.fillMaxWidth().testTag("source-continue"),
                    enabled=selected!=null && available, icon=if (download) Icons.Outlined.FileDownload else Icons.Outlined.Cast) {
                    val current = model.remote.value
                    if (selected!=null && (download || current.canLaunch && current.profileKey==s.config.profileKey && current.target?.id==s.episodeTargetId))
                        model.playRelease(selected,season,onTv=!download,download=download)
                }
                return@BoxWithConstraints
            }
            var chooseDevice by remember(selected?.id,season,target?.id,target?.instance) {mutableStateOf(false)}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(gap),verticalArrangement=Arrangement.spacedBy(gap)) {
                Box {
                    val watchButton=Modifier.width(buttonWidth).testTag("source-continue").semantics {
                        if(target!=null)stateDescription="Выбор экрана"
                    }
                    Action("Смотреть",false,watchButton,enabled=selected!=null,
                        icon=if(target!=null) Icons.Outlined.ArrowDropDown else Icons.Outlined.PlayArrow) {
                        if(selected!=null) {
                            if(target==null)model.playRelease(selected,season,fromDetailTab=detailHeader!=null) else chooseDevice=true
                        }
                    }
                    if(target!=null&&selected!=null) DropdownMenu(expanded=chooseDevice,onDismissRequest={chooseDevice=false}) {
                        DropdownMenuItem(text={Text("На этом устройстве")},leadingIcon={Icon(Icons.Outlined.Devices,null)},
                            modifier=Modifier.heightIn(min=48.dp).testTag("source-continue-local"),onClick={
                                chooseDevice=false
                                model.playRelease(selected,season,fromDetailTab=detailHeader!=null)
                            })
                        DropdownMenuItem(text={Text("На ТВ · ${target.name}")},leadingIcon={Icon(Icons.Outlined.Tv,null)},
                            modifier=Modifier.heightIn(min=48.dp).testTag("source-continue-tv"),enabled=remote.canLaunch,onClick={
                                chooseDevice=false
                                val current=model.remote.value.target
                                if(current?.id==target.id&&current.instance==target.instance)model.playRelease(selected,season,onTv=true,fromDetailTab=detailHeader!=null)
                            })
                    }
                }
                Action("Скачать",false,Modifier.width(buttonWidth).heightIn(min=52.dp).testTag("source-download"),enabled=selected!=null&&!s.config.demo,secondary=true,icon=Icons.Outlined.FileDownload) {
                    selected?.let {model.playRelease(it,season,download=true,fromDetailTab=detailHeader!=null)}
                }
            }
        }
    }
}

@Composable private fun FilesScreen(s:WatchState,model:WatchModel,tv:Boolean) {
    val remote by model.remote.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var downloadError by remember {mutableStateOf("")}
    var queueing by remember { mutableStateOf(false) }
    var confirmSeason by remember { mutableStateOf(false) }
    val handle=s.handle
    val release=s.selected
    var launching by remember(handle?.hash) {mutableStateOf(false)}
    var launchError by remember(handle?.hash) {mutableStateOf("")}
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val playerLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {model.playerClosed()}
    var showAll by rememberSaveable(s.handle?.hash) { mutableStateOf(false) }
    var autoLaunched by remember(s.handle?.hash) {mutableStateOf(false)}
    val scoped by model.scopedLibrary.collectAsStateWithLifecycle()
    val progress=if(scoped.context==s.config.profileKey)scoped.positions else emptyList()
    val allFiles=handle?.files.orEmpty()
    val indexed=backgroundValue(allFiles) {sortedVideoFiles(allFiles).map {it to episodeNumber(it.path)}}
    val seasons = remember(indexed, release?.title) {
        (seasonNumbers(release?.title.orEmpty()) + indexed.orEmpty().mapNotNull { (file, number) ->
            number.season.takeIf { file.video && !file.sample }
        }).sorted()
    }
    val implicitSeason = seasons.singleOrNull()
    var season by rememberSaveable(s.handle?.hash) {mutableStateOf(s.releaseSeason)}
    var episode by rememberSaveable(s.handle?.hash) {mutableStateOf(s.releaseEpisode)}
    val visibleFiles=remember(indexed,showAll,season,episode,implicitSeason) {
        indexed.orEmpty().filter {(file,number)->
            (showAll||file.video&&!file.sample)&&(season==null||(number.season ?: implicitSeason.takeIf { number.episode != null })==season)&&
                (episode==null||number.episode==episode)
        }.map {it.first}
    }
    val videos=remember(visibleFiles) {visibleFiles.filter {it.video&&!it.sample}}
    val positionByPath=remember(progress,release?.id) {
        progress.asReversed().filter {it.releaseId==release?.id}.associateBy {it.path}
    }
    var selection by rememberSaveable(s.handle?.hash,s.downloadSelection,s.releaseSeason,s.releaseEpisode) {
        mutableStateOf<Int?>(null)
    }
    var downloadSelection by rememberSaveable(handle?.hash,release?.id,s.releaseSeason,s.releaseEpisode) {
        mutableStateOf<List<Int>?>(null)
    }
    fun launch(file:TorrentFile) {
        if(s.downloadSelection||launching||handle==null||release==null||!model.isFileSelectionActive(handle.hash,release.id)) return
        if(!tv&&s.remotePlayback) {
            val target=remote.target ?: return
            launching=true
            scope.launch {
                try {
                    val position=if(s.config.historyEnabled) {
                        val key=handle.contentHash?.let {contentKey(it,file.path,file.size)} ?: "${release.id}|${file.path}"
                        model.playbackPosition(s.config,release.id,key)
                    } else 0L
                    val current=model.remote.value.target
                    if(current?.id!=target.id||current.instance!=target.instance||!model.isFileSelectionActive(handle.hash,release.id))return@launch
                    dev.mjwatch.playback.RemotePlaybackService.load(dev.mjwatch.playback.RemoteMedia(handle.hash,file.serverFileId,s.selectedMetadata?.title ?: release.displayTitle(),release.id,"${handle.contentHash}:${file.serverFileId}",s.selectedMetadata?.id.orEmpty(),position))
                    model.playerClosed()
                } catch(e:kotlinx.coroutines.CancellationException) {
                    throw e
                } catch(e:Exception) {
                    launchError="Не удалось открыть видео. ${safeError(e)}"
                } finally {
                    launching=false
                }
            }
            return
        }
        if(!tv) dev.mjwatch.playback.RemotePlaybackService.disconnect(context)
        launching=true
        launchError=""
        scope.launch {
            val queue=PlaybackQueue.create(context.cacheDir)
            var launched=false
            try {
                val intent=withContext(Dispatchers.IO) {
                    val gateway=model.streamGateway(s.config)
                    fun identity(f:TorrentFile)=if(s.config.managed) contentKey(requireNotNull(handle.contentHash),f.path,f.size) else ""
                    val request=gateway.stream(handle,file)
                    val episodes=indexed.orEmpty().asSequence().map {it.first}.filter {it.video&&!it.sample}.map {f->
                        PlaybackEpisode(gateway.stream(handle,f).uri,f.path,f.serverFileId,identity(f))
                    }.toList()
                    queue.write(episodes)
                    val recovery=org.json.JSONObject().put("id",release.id).put("title",release.title).put("source",release.source)
                        .put("magnet",release.magnet).put("download",release.download).put("hash",handle.contentHash ?: handle.hash)
                    val metadata=s.selectedMetadata
                    Intent(context,PlaybackActivity::class.java)
                        .putExtra("recoveryRelease",recovery.toString()).putExtra("torrentHandle",handle.hash)
                        .putExtra(PlaybackActivity.EXTRA_FILE_BYTES,file.size)
                        .putExtra(PlaybackActivity.EXTRA_RUNTIME_MS,(metadata?.runtimeMinutes ?: 0) * 60_000L)
                        .putExtra("remoteContentId","${handle.contentHash}:${file.serverFileId}")
                        .putExtra("metadataId",metadata?.id).putExtra("artMetadataId",metadata?.id)
                        .putExtra("segmentSeason",season ?: -1).putExtra("segmentEpisode",episode ?: -1)
                        .putExtra(PlaybackActivity.EXTRA_SEGMENT_QUEUE_SEASON,implicitSeason ?: -1)
                        .putExtra("artBackdrop",metadata?.backdrop).putExtra("artPoster",metadata?.poster).putExtra("artLogo",metadata?.logo)
                        .putExtra("trackerSeeders",release.seeds ?: -1).putExtra(PlaybackQueue.EXTRA,queue.token)
                        .putExtra("contentKey",identity(file)).putExtra("uri",request.uri).putExtra("releaseId",release.id)
                        .putExtra("fileId",file.serverFileId).putExtra("path",file.path).putExtra("mobile",!tv)
                        .putExtra("title",metadata?.title ?: release.displayTitle())
                }
                if(model.isFileSelectionActive(handle.hash,release.id)) {
                    android.util.Log.d("MJWatch","Dispatching playback activity")
                    playerLauncher.launch(intent)
                    launched=true
                }
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {launchError="Не удалось открыть видео. ${safeError(e)}"}
            finally {
                if(!launched) withContext(NonCancellable+Dispatchers.IO) {queue.delete()}
                launching=false
            }
        }
    }
    val awaitingRemote=!tv&&s.remotePlayback&&!remote.canLaunch
    LaunchedEffect(handle?.hash,s.resumePath,indexed,s.downloadSelection,awaitingRemote) {
        if(indexed==null) return@LaunchedEffect
        if(s.downloadSelection) {
            if(downloadSelection==null) downloadSelection=listOfNotNull(videos.singleOrNull {it.canDownload}?.serverFileId)
            return@LaunchedEffect
        }
        if(autoLaunched||awaitingRemote) return@LaunchedEffect
        val target=if(s.resumePath!=null) allFiles.singleOrNull {it.video&&it.path==s.resumePath} else videos.singleOrNull()
        android.util.Log.d("MJWatch","Automatic file selection: resume=${s.resumePath!=null}, target=${target!=null}")
        if(target!=null) {autoLaunched=true;selection=target.serverFileId;launch(target)}
        if(s.resumePath!=null) {if(target==null) selection=null;model.consumeResume(target==null)}
    }
    val file=visibleFiles.firstOrNull {it.serverFileId==selection&&it.video}
    val downloadFiles=remember(indexed,downloadSelection) {
        val ids=downloadSelection.orEmpty().toSet()
        indexed.orEmpty().map {it.first}.filter {it.serverFileId in ids&&it.canDownload}
    }
    val seasonToDownload = (season ?: implicitSeason).takeIf { episode == null }
    val seasonFiles = remember(indexed, release, seasonToDownload, s.downloadSelection, tv) {
        if (!tv && s.downloadSelection && seasonToDownload != null && release != null && indexed != null)
            seasonDownloadFiles(allFiles, release.title, seasonToDownload)
        else emptyList()
    }
    fun enqueue(download: suspend (OfflineDownloads) -> Unit) {
        if (queueing || !model.isFileSelectionActive(handle?.hash, release?.id)) return
        queueing = true
        downloadError = ""
        scope.launch {
            try {
                download(OfflineDownloads.get(context))
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val permission = android.Manifest.permission.POST_NOTIFICATIONS
                    val prefs = context.getSharedPreferences("offline-notifications", 0)
                    if (context.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                        !prefs.getBoolean("requested", false)) {
                        prefs.edit().putBoolean("requested", true).apply()
                        notificationPermission.launch(permission)
                    }
                }
                model.openDownloads()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                downloadError = safeError(e)
            } finally {
                queueing = false
            }
        }
    }
    if (confirmSeason && seasonToDownload != null && handle != null && release != null) {
        val expected = s.selectedMetadata?.let { model.episodeCatalog(it.id, s.config).episodes.get(seasonToDownload) }
        val found = seasonFiles.mapNotNull { episodeNumber(it.path).episode }.toSet()
        val missing = expected.orEmpty().count { it.number !in found }
        AlertDialog(
            onDismissRequest = { confirmSeason = false },
            title = { Text(if (seasonToDownload == 0) "Скачать спецвыпуски?" else "Скачать сезон $seasonToDownload?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Файлов из выбранной раздачи: ${seasonFiles.size} · ${sizeLabel(seasonFiles.sumOf { it.size })}")
                    if (missing > 0) Text("Серий из каталога, которые не удалось сопоставить: $missing")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSeason = false
                    enqueue { it.enqueueSeason(s.config, release, handle, seasonToDownload, s.selectedMetadata) }
                }, modifier = Modifier.testTag("confirm-season-download")) { Text("Скачать") }
            },
            dismissButton = { TextButton(onClick = { confirmSeason = false }) { Text("Отмена") } }
        )
    }
    val controls:@Composable ()->Unit={Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(tv) {
            Artwork(s.selectedMetadata,s.config,Modifier.fillMaxWidth().aspectRatio(1.3f).clip(RoundedCornerShape(18.dp)),backdrop=true,demo=s.selected?.id?.startsWith("demo:")==true)
            Text(file?.let {fileLabel(it.path)} ?: "Выберите видеофайл",fontSize=20.sp,fontWeight=FontWeight.Bold)
        }
        if(!tv&&s.downloadSelection&&release!=null&&handle!=null) {
            val count=downloadFiles.size
            Action(if(count>1)"Скачать ($count)" else "Скачать",false,Modifier.fillMaxWidth().testTag("file-download"),
                enabled=!s.config.demo&&!queueing&&count>0,icon=Icons.Outlined.FileDownload) {
                enqueue { it.enqueueFiles(s.config,release,handle,downloadFiles,s.selectedMetadata,implicitSeason,
                    episode.takeIf {count==1}) }
            }
        } else if(file!=null) {
            val p=positionByPath[file.path]
            if(p!=null&&tv) Text("Сохранено ${time(p.positionMs)}${if(p.completed) " · завершено" else ""}")
            if(!s.downloadSelection) {
                val onTv=!tv&&s.remotePlayback
                val label=if(onTv) remote.target?.name?.let {"Смотреть на ТВ · $it"} ?: "Смотреть на ТВ" else "Смотреть"
                Action(if(launching) "Открываем видео…" else label+if(p!=null&&!p.completed) " с ${time(p.positionMs)}" else "",
                    tv,Modifier.fillMaxWidth().testTag("file-continue"),enabled=!onTv||remote.canLaunch&&remote.profileKey==s.config.profileKey,
                    icon=Icons.Outlined.PlayArrow,loading=launching) {launch(file)}
            }
        } else Text(if(s.downloadSelection)"Выберите файл для загрузки" else "Выберите файл для просмотра",color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
    val header:@Composable ()->Unit={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        val error=launchError.ifBlank {downloadError.ifBlank {s.message}}
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
        if(s.downloadSelection&&s.config.demo) Text("Загрузки недоступны в деморежиме",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(s.selectedMetadata?.title ?: s.selected?.displayTitle().orEmpty(),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=if(tv)2 else Int.MAX_VALUE,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if(seasons.isNotEmpty()||season!=null||episode!=null) {
            val seasonChoices:@Composable ()->Unit={
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    item {Pill("Все сезоны",season==null,tv){season=null;episode=null;selection=null}}
                    items(seasons,key={it}) {number->Pill("Сезон $number",season==number,tv){season=number;episode=null;selection=null}}
                }
                if(episode!=null)Pill("Серия $episode · показать все серии",true,tv){episode=null;selection=null}
            }
            if(tv)SettingsGroup(true){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){seasonChoices()}}
            else SettingsCard("Сезоны и серии",Icons.Outlined.Layers,false){seasonChoices()}
        }
        if (seasonFiles.isNotEmpty() && seasonToDownload != null && !s.config.demo) {
            Action(if (seasonToDownload == 0) "Скачать спецвыпуски" else "Скачать сезон $seasonToDownload",
                false, Modifier.fillMaxWidth().testTag("file-download-season"),
                enabled = !queueing, icon = Icons.Outlined.FileDownload) { confirmSeason = true }
        } else if (!tv && s.downloadSelection && indexed != null && seasonToDownload != null && !s.config.demo) {
            Text("В раздаче не удалось определить файлы сезона. Отметьте нужные файлы в списке.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(tv)SettingsGroup(true){SettingsCheckbox("Все файлы и sample",showAll){showAll=it}}
        else SettingsDisclosure("Дополнительные файлы","Sample и служебные файлы",Icons.Outlined.FolderOpen,false) {
            SettingsToggle("Все файлы и sample",showAll,{showAll=it},description="Показать также образцы видео и файлы без видео.")
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(seasons.isNotEmpty())"Серии" else "Видеофайлы",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f).semantics{heading()})
            if(indexed!=null) Badge(videos.size.toString())
        }
        if(tv)Text("OK — выбрать серию · повторный OK — смотреть",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
    Row(Modifier.fillMaxSize().padding(bottom=20.dp),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(tv)header()
            LazyColumn(Modifier.weight(1f).testTag("file-list"),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=12.dp)) {
                if(!tv)item {header()}
                if(indexed==null) item {LoadingIndicator("Готовим список файлов…")}
                else if(visibleFiles.isEmpty())item {Empty("Нет файлов для выбранного сезона или серии","Попробуйте другой сезон или покажите все серии.",tv,"Показать все серии"){season=null;episode=null;selection=null}}
                items(visibleFiles,key={it.serverFileId}) {f->
                    Box(if(tv) Modifier else Modifier.animateItem(placementSpec=tween(220))) {
                        val selected=if(s.downloadSelection)f.serverFileId in downloadSelection.orEmpty() else selection==f.serverFileId
                        FileCard(f,tv,selected,progress=positionByPath[f.path],multiSelect=s.downloadSelection,
                            focus=tv&&f.serverFileId==(selection ?: videos.firstOrNull()?.serverFileId)) {
                            if(s.downloadSelection) {
                                if(f.canDownload&&!queueing) {
                                    val ids=downloadSelection.orEmpty()
                                    downloadSelection=if(selected)ids-f.serverFileId else ids+f.serverFileId
                                }
                            } else if(f.video&&!launching) {if(tv&&selection==f.serverFileId)launch(f) else selection=f.serverFileId}
                        }
                    }
                }
            }
        if(!tv&&(file!=null||s.downloadSelection)) controls()
        }
        if(tv) Box(Modifier.width(tvUnit(320f)).fillMaxHeight().verticalScroll(rememberScrollState())) {controls()}
    }
}

@Composable private fun LibraryCard(item:LibraryTitle,progress:Map<String,Progress>,state:WatchState,model:WatchModel,tv:Boolean,showMenu:Boolean=false,bookmarkOnly:Boolean=false,restoreFocus:Boolean=false,onOpen:()->Unit={},onFocus:()->Unit={}) {
    val e=item.entry
    var menu by remember {mutableStateOf(false)}
    if(menu) {
        val confirmFocus=remember {FocusRequester()}
        AlertDialog(onDismissRequest={menu=false},title={Text(e.title)},text={Text(if(bookmarkOnly) "Убрать из подборки?" else "Позиция просмотра будет удалена. Фильм останется доступен в каталоге.")},confirmButton={LaunchedEffect(Unit) {if(tv) {delay(150);confirmFocus.requestFocus()}};TextButton(modifier=Modifier.heightIn(min=48.dp).focusRequester(confirmFocus).tvFocusRing(tv),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),onClick={model.remove(item.records,bookmarkOnly);menu=false}) {Icon(Icons.Outlined.DeleteOutline,null);Spacer(Modifier.width(8.dp));Text("Удалить")}},dismissButton={TextButton(modifier=Modifier.heightIn(min=48.dp).tvFocusRing(tv),onClick={menu=false}) {Text("Отмена")}})
    }
    val p=remember(item,progress) {item.releaseIds.mapNotNull(progress::get).maxByOrNull {it.updatedAt}}
    val config=state.config
    val metadata=remember(item,state.catalog) {item.metadata ?: state.catalog.firstOrNull {it.id==e.metadataId}}
    val requester=remember {FocusRequester()}
    LaunchedEffect(Unit) {if(tv&&restoreFocus) {withFrameNanos {};requester.requestFocus()}}
    val duration=p?.durationMs?.takeIf {it>0}
    val position=p?.positionMs?.coerceAtLeast(0L)
    val elapsed=position?.let {time(it.coerceAtMost(duration ?: it))}
    val number=p?.let {episodeNumber(it.path)}
    val episode=number?.episode?.let {index->listOfNotNull(number.season?.let {"С$it"},"Серия $index").joinToString(" · ")}
    val subtitle=listOfNotNull(episode,elapsed).joinToString(" · ").ifEmpty {
        metadata?.let(::titleSubtitle) ?: e.source
    }
    val colors=MaterialTheme.colorScheme
    val surface=colors.surface
    val secondary=colors.onSurfaceVariant
    val track=colors.outline.copy(alpha=.48f)
    val artHeight=if(tv) tvUnit(176f) else 96.dp
    val demoArt=e.id.startsWith("demo:")&&metadata?.backdrop==null&&metadata?.poster==null
    val playbackProgress:(@Composable ()->Unit)? = if(position!=null&&duration!=null) {{
        val fraction by animateFloatAsState(
            (position.toFloat() / duration).coerceIn(0f, 1f), tween(200), label = "history-progress"
        )
        if (tv) {
            LinearProgressIndicator(
                progress={fraction},modifier=Modifier.padding(top=tvUnit(5f)).fillMaxWidth().height(tvUnit(5f)),
                trackColor=track,gapSize=0.dp,drawStopIndicator={},
            )
        } else {
            Box(
                Modifier.progressSemantics(fraction).padding(top=4.dp).fillMaxWidth().height(4.dp)
                    .clip(CircleShape).background(track),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(watchProgressGradient(),CircleShape))
            }
        }
    }} else null
    var focused by remember {mutableStateOf(false)}
    val cardModifier=Modifier.fillMaxWidth().focusRequester(requester)
        .onFocusChanged {focused=it.isFocused;if(it.isFocused)onFocus()}.testTag("library-${e.id}")
    NativeCard(tv,cardModifier,outlined=!bookmarkOnly,onLongClick={menu=true},onClick={onOpen();model.restore(e)}) {
        if(bookmarkOnly) {
            val frame = if(tv) Modifier.padding(TvPosterClearance).tvPosterFocus(focused) else Modifier
            Box(frame) {
                CinematicTitleTile(metadata,config,e.title,subtitle,"library-poster-${e.id}",demo=demoArt,
                    progress=playbackProgress,action=if(showMenu&&!tv) {{
                        IconButton(onClick={menu=true},modifier=Modifier.align(Alignment.TopEnd)) {
                            Icon(Icons.Outlined.MoreVert,"Действия с «${e.title}»",
                                Modifier.background(Color(0xCC161B24),CircleShape).padding(4.dp),tint=Color.White)
                        }
                    }} else null)
            }
        } else Row(Modifier.fillMaxWidth().background(surface),
            verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(width=artHeight*PosterAspectRatio,height=artHeight).testTag("library-poster-${e.id}")) {
                Artwork(metadata,config,Modifier.matchParentSize(),backdrop=true,demo=demoArt,posterFirst=true)
            }
            Column(Modifier.weight(1f).padding(
                start=if(tv) tvUnit(24f) else 12.dp,end=if(tv) tvUnit(24f) else 12.dp,
                top=if(tv) tvUnit(20f) else 8.dp,bottom=if(tv) tvUnit(20f) else 8.dp
            ), verticalArrangement=Arrangement.spacedBy(if(tv) tvUnit(8f) else 4.dp)) {
                Text(e.title,fontWeight=FontWeight.Bold,fontSize=if(tv) tvUnit(25.6f).value.sp else 18.sp,
                    lineHeight=if(tv) tvUnit(32f).value.sp else 22.sp,color=colors.onSurface,
                    maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if(subtitle.isNotEmpty()) Text(subtitle,fontSize=if(tv) tvUnit(18f).value.sp else 14.sp,
                    lineHeight=if(tv) tvUnit(24f).value.sp else 18.sp,color=secondary)
                playbackProgress?.invoke()
            }
            if(showMenu&&!tv) IconButton(onClick={menu=true}) {Icon(Icons.Outlined.MoreVert,"Действия с «${e.title}»")}
            else Icon(Icons.Outlined.PlayCircle,null,
                modifier=Modifier.padding(end=if(tv) tvUnit(24f) else 18.dp).size(if(tv) tvUnit(32f) else 24.dp),
                tint=colors.primary)
        }
    }
}

@Composable private fun LibraryScreen(libraryState:WatchState,library:WatchModel.LibrarySnapshot?,model:WatchModel,tv:Boolean) {
    var tab by rememberSaveable { mutableStateOf(if(model.state.value.page=="history") "История" else "Продолжить") }
    LaunchedEffect(libraryState.openDownloadsRequested) {
        if(!tv&&libraryState.page=="library"&&libraryState.openDownloadsRequested) {
            tab="Загрузки"
            model.consumeOpenDownloads()
        }
    }
    var returnFocusId by rememberSaveable {mutableStateOf<String?>(null)}
    val filtered=remember(library,tab) {
        library?.titles.orEmpty().filter {entry->
            when(tab) {
                "Буду смотреть" -> entry.entry.bookmarked
                "Продолжить" -> entry.canContinue(library?.unfinishedReleaseIds.orEmpty())
                else -> entry.entry.openedAt>0
            }
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        WatchTabs(if(tv) listOf("Продолжить","Буду смотреть","История") else listOf("Продолжить","Загрузки","Буду смотреть","История"),tab,tv) {if(it=="Загрузки"&&libraryState.page=="history") model.openDownloads() else tab=it}
        val bookmarkOnly=tab=="Буду смотреть"
        val columns=if(tv) 4 else 2
        val cards=remember(filtered,bookmarkOnly,columns) {filtered.chunked(if(bookmarkOnly) columns else 1)}
        if(tab=="Загрузки"&&!tv) OfflineScreen(libraryState.config) else LazyColumn(contentPadding=PaddingValues(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(library==null) item {LoadingIndicator("Загружаем библиотеку…")}
            else if(filtered.isEmpty()) item { Empty("Пока пусто",if(tab=="Буду смотреть") "Сохраните фильм или сериал на потом" else if(!libraryState.config.managed) "В direct история и позиции не сохраняются. Избранное доступно отдельно." else "Здесь появится то, что вы смотрели",tv,onClick={model.page("search")}) }
            items(cards,key={it.first().key}) {row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    row.forEach {e ->
                        Box(Modifier.weight(1f)) {
                            LibraryCard(e,library?.latestProgress.orEmpty(),libraryState,model,tv,
                                showMenu=tab!="Продолжить",bookmarkOnly=bookmarkOnly,
                                restoreFocus=returnFocusId==e.key,onOpen={returnFocusId=e.key})
                        }
                    }
                    if(bookmarkOnly) repeat(columns-row.size) {Spacer(Modifier.weight(1f))}
                }
            }
        }
    }
}
@Composable private fun SettingsScreen(s:WatchState,model:WatchModel,tv:Boolean,showIntro:Boolean=true,section:String?=null,registerBack: (((() -> Unit)?) -> Unit)?=null) {
    val context=LocalContext.current
    val serverSearch=section=="search"&&s.config.managed
    var c by remember { mutableStateOf(if(serverSearch) s.config else model.draftConfig ?: s.config) }
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
    if(importError.isNotBlank()) {
        val errorFocus=remember {FocusRequester()}
        LaunchedEffect(importError) {if(tv) {withFrameNanos {};errorFocus.requestFocus()}}
        AlertDialog(onDismissRequest={importError=""},title={Text("Импорт и экспорт")},text={Text(importError)},confirmButton={TextButton(modifier=Modifier.focusRequester(errorFocus),onClick={importError=""}) {Text("Понятно")}})
    }
    LaunchedEffect(Unit) { if(model.draftConfig==null) { metadataToken=model.secrets.get("tmdb"); key=model.secrets.get("jackett:${s.config.jackett}");user=model.secrets.get("torrUser:${s.config.torrServer}");password=model.secrets.get("torrPassword:${s.config.torrServer}") }; baseline=listOf(model.secrets.get("jackett:${s.config.jackett}"),model.secrets.get("torrUser:${s.config.torrServer}"),model.secrets.get("torrPassword:${s.config.torrServer}"),model.secrets.get("tmdb")) }
    if(!serverSearch) LaunchedEffect(c,key,user,password,metadataToken) {model.draftConfig=c;model.draftKey=key;model.draftUser=user;model.draftPassword=password;model.draftMetadata=metadataToken}
    val editorImeVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    val dirty=!serverSearch&&(c!=s.config||listOf(key,user,password,metadataToken)!=baseline)
    val requestBack=rememberUpdatedState<()->Unit>({if(dirty) leave=true else {model.discardDraft();model.back()}})
    DisposableEffect(Unit) {registerBack?.invoke {requestBack.value()};onDispose {registerBack?.invoke(null)}}
    BackHandler(dirty&&!editorImeVisible) {requestBack.value()}
    if(leave) AlertDialog(onDismissRequest={leave=false},title={Text("Сохранить изменения?")},text={Text("Введённые параметры ещё не применены")},confirmButton={TextButton(onClick={leave=false;applySettings()}) {Text("Сохранить")}},dismissButton={Row {TextButton(onClick={leave=false;model.discardDraft();model.back()}) {Text("Не сохранять")};TextButton(onClick={leave=false}) {Text("Отмена")}}})
    val export=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> val uri=result.data?.data?.takeIf {result.resultCode==android.app.Activity.RESULT_OK}; if(uri!=null) context.contentResolver.openOutputStream(uri)?.use { it.write(encodeConfig(c.copy(torrServer=normalizeDirectAddress(c.torrServer)),includeAccount=false).toByteArray()) } }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> val uri=result.data?.data?.takeIf {result.resultCode==android.app.Activity.RESULT_OK}; if(uri!=null) try {
        val raw=context.contentResolver.openInputStream(uri)!!.use { String(it.readBytes().also { b -> require(b.size<65536) }) }; preview=decodeConfig(raw)
    } catch(_:Exception) {importError="Не удалось импортировать конфигурацию"} }
    fun urlError(raw:String)=raw.isNotBlank()&&runCatching {endpoint(raw)}.isFailure
    fun changed(new:Config) { c=new; model.invalidateChecks() }
    var setupStep by rememberSaveable {mutableIntStateOf(1)}
    val setup=!tv&&!s.configured
    BackHandler(setup&&setupStep==2&&!editorImeVisible) {setupStep=1}
    Row(Modifier.fillMaxSize().then(if(tv&&!s.configured) Modifier.padding(horizontal=tvUnit(80f)) else Modifier),horizontalArrangement=Arrangement.spacedBy(32.dp)) {
    if(tv&&showIntro) Column(Modifier.weight(.8f).padding(top=32.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        Wordmark();Spacer(Modifier.height(24.dp));Text(if(s.configured) "Настройте\nсвои источники." else "Ваше кино.\nВаши источники.",fontSize=36.sp,lineHeight=44.sp,fontWeight=FontWeight.Bold)
        Text("Jackett найдёт раздачи.\nTorrServer подготовит поток.\nMJWatch начнёт просмотр.",fontSize=18.sp,lineHeight=28.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Badge("Подключения",true)
    }
    LazyColumn(Modifier.weight(1f).testTag("settings-list"),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(horizontal=if(!tv&&!s.configured)20.dp else 0.dp,vertical=24.dp)) {
        if(setup)item {Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("ШАГ $setupStep ИЗ 2",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
            Heading(if(setupStep==1)"Добавьте источник раздач" else "Подключите TorrServer")
        }} else if(section==null)item{Heading("Подключения")}
        if((!setup||setupStep==1)&&(section==null||section=="search"))item {SettingsCard("Источник раздач",Icons.Outlined.Search,tv){
            if(c.managed) {
                val account by model.managed.state.collectAsStateWithLifecycle()
                SettingsHint("Источник поиска задаёт администратор сервера MJWatch.")
                if(account.scope==c.scope&&account.me?.optString("role")=="admin") {
                    Action("Управление поиском",tv,secondary=true,icon=Icons.Outlined.Tune){model.page("server-jacred")}
                }
            } else {
            Text("Jackett / JacRed находит раздачи для просмотра.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(c.jackett,{key="";changed(c.copy(jackett=it))},label={Text("Адрес Jackett / JacRed")},isError=urlError(c.jackett),supportingText={Text(if(urlError(c.jackett))"Введите http/https URL без логина, query и fragment" else "Например https://jac.red")},modifier=Modifier.fillMaxWidth().tvFieldNavigation(),singleLine=true,shape=MaterialTheme.shapes.small)
            SecretField(key,{key=it;model.invalidateChecks()},"API-ключ (если требуется)")
            }
            Action("Проверить поиск",tv,Modifier.fillMaxWidth(),secondary=true,icon=Icons.Outlined.Refresh,loading=s.checkingJackett){model.check(true,c,key,user,password)}
            OperationMessage(s.checkJackett)
        }}
        if((!setup||setupStep==2)&&(section==null||section=="server"))item {SettingsCard("Сервер видео",Icons.Outlined.Dns,tv){
            OutlinedTextField(c.torrServer,{user="";password="";changed(c.copy(torrServer=it))},label={Text("Адрес TorrServer")},isError=runCatching{endpoint(normalizeDirectAddress(c.torrServer))}.isFailure&&c.torrServer.isNotBlank(),supportingText={Text(if(runCatching{endpoint(normalizeDirectAddress(c.torrServer))}.isFailure&&c.torrServer.isNotBlank())"Введите http/https URL без логина, query и fragment" else runCatching{"Итоговый адрес: ${normalizeDirectAddress(c.torrServer)}"}.getOrDefault("Введите IP или полный URL"))},modifier=Modifier.fillMaxWidth().tvFieldNavigation(),singleLine=true,shape=MaterialTheme.shapes.small)
            SettingsHint("Для IP без порта используется 8090. В прямом подключении открытия и позиции просмотра не сохраняются.")
            SettingsToggle("Разрешить локальный HTTP",c.localHttp,{changed(c.copy(localHttp=it))},description="Для сервера в вашей локальной сети")
            Action("Проверить TorrServer",tv,Modifier.fillMaxWidth(),secondary=true,icon=Icons.Outlined.Refresh,loading=s.checkingTorr){model.check(false,c,key,user,password)}
            OperationMessage(s.checkTorr)
        }}
        if(section=="search"&&!c.managed)item {SettingsDisclosure("Параметры поиска","Протокол, индексаторы и локальный HTTP",Icons.Outlined.Tune,tv){
            Text("Протокол поиска",style=MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("auto" to "Авто","torznab" to "Torznab","jacred" to "JacRed JSON").forEach{(id,label)->Pill(label,c.releaseApi==id,tv){changed(c.copy(releaseApi=id))}}}
            SettingsHint("Авто подходит для большинства серверов. Выберите протокол вручную, если автоматическая проверка не распознала источник.")
            OutlinedTextField(c.indexers,{changed(c.copy(indexers=it))},label={Text("Индексаторы (необязательно)")},supportingText={Text("Пусто — все источники")},modifier=Modifier.fillMaxWidth().tvFieldNavigation(),shape=MaterialTheme.shapes.small)
            SettingsToggle("Разрешить локальный HTTP",c.localHttp,{changed(c.copy(localHttp=it))},description="Для источника в вашей локальной сети")
        }}
        if(section=="server")item {SettingsDisclosure("Авторизация сервера",if(user.isNotBlank()||password.isNotBlank())"Авторизация настроена" else "Логин и пароль Basic Auth",Icons.Outlined.Lock,tv,initiallyOpen=user.isNotBlank()||password.isNotBlank()){
            OutlinedTextField(user,{user=it;model.invalidateChecks()},label={Text("Basic Auth: логин (необязательно)")},modifier=Modifier.fillMaxWidth().tvFieldNavigation(),singleLine=true,shape=MaterialTheme.shapes.small)
            SecretField(password,{password=it;model.invalidateChecks()},"Пароль TorrServer")
            SettingsHint("Оставьте поля пустыми, если TorrServer не запрашивает логин и пароль.")
        }}
        if(section=="catalog")item {SettingsCard("Каталог и обложки",Icons.Outlined.Movie,tv){
            SettingsToggle("Каталог TMDB",c.metadataProvider=="tmdb",{changed(c.copy(metadataProvider=if(it)"tmdb" else "none"))},description="Фильмы, сериалы, описания и постеры")
            if(c.metadataProvider=="tmdb")SecretField(metadataToken,{metadataToken=it},"TMDB Read Access Token")
            SettingsHint("Поиск раздач работает и без каталога. Токен хранится в зашифрованном хранилище устройства.")
        }}
        if(section==null)item {SettingsCard("Режим просмотра",Icons.Outlined.PlayCircleOutline,tv){
            SettingsToggle("Демонстрация с локальным видео",c.demo,{changed(c.copy(demo=it))},description="Проверить интерфейс и плеер без подключения источников")
        }}
        if(setup&&setupStep==1&&!c.demo)item {Action("Далее · TorrServer",false,Modifier.fillMaxWidth(),enabled=!urlError(c.jackett),icon=Icons.Outlined.ArrowForward){setupStep=2}}
        if(section==null)item {SettingsDisclosure("Дополнительные настройки","Протокол, авторизация, каталог и тема",Icons.Outlined.Tune,tv){
            Text("Протокол поиска",style=MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("auto" to "Авто","torznab" to "Torznab","jacred" to "JacRed JSON").forEach{(id,label)->Pill(label,c.releaseApi==id,tv){changed(c.copy(releaseApi=id))}}}
            OutlinedTextField(c.indexers,{changed(c.copy(indexers=it))},label={Text("Индексаторы (необязательно)")},supportingText={Text("Пусто — все источники")},modifier=Modifier.fillMaxWidth().tvFieldNavigation(),shape=MaterialTheme.shapes.small)
            OutlinedTextField(user,{user=it;model.invalidateChecks()},label={Text("Basic Auth: логин (необязательно)")},modifier=Modifier.fillMaxWidth().tvFieldNavigation(),singleLine=true,shape=MaterialTheme.shapes.small)
            SecretField(password,{password=it;model.invalidateChecks()},"Пароль TorrServer")
            SettingsToggle("Каталог TMDB",c.metadataProvider=="tmdb",{changed(c.copy(metadataProvider=if(it)"tmdb" else "none"))})
            if(c.metadataProvider=="tmdb")SecretField(metadataToken,{metadataToken=it},"TMDB Read Access Token")
            Text("Оформление",style=MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("system" to "Системная","dark" to "Тёмная","light" to "Светлая").forEach{(id,label)->Pill(label,c.theme==id,tv){c=c.copy(theme=id)}}}
        }}
        if(!serverSearch&&(section!=null||!setup||setupStep==2||c.demo))item {Action(if(s.busy)"Сохранение…" else if(section==null)"Сохранить и продолжить" else "Сохранить",tv,Modifier.fillMaxWidth(),enabled=!s.busy,icon=Icons.Outlined.Check,loading=s.busy){applySettings()}}
        if(s.message.isNotBlank())item {OperationMessage(s.message,error=true)}
        if(section==null)item {SettingsCard("Перенос настроек",Icons.Outlined.ImportExport,tv){
            Action("Импорт конфигурации",tv,secondary=true,icon=Icons.Outlined.FileDownload) {try {val request=filePickerIntent(context,ActivityResultContracts.OpenDocument().createIntent(context,arrayOf("application/json")));if(request==null) importError="На устройстве нет приложения для выбора файлов. Подключитесь по QR или введите настройки вручную." else importer.launch(request)} catch(_:android.content.ActivityNotFoundException) {importError="На устройстве нет приложения для выбора файлов. Подключитесь по QR или введите настройки вручную."}}
            TextButton(onClick={if(!urlError(c.jackett)&&(c.torrServer.isBlank()||runCatching {endpoint(normalizeDirectAddress(c.torrServer))}.isSuccess)) try {val request=filePickerIntent(context,ActivityResultContracts.CreateDocument("application/json").createIntent(context,"mjwatch-config.json"));if(request==null) importError="На устройстве нет приложения для сохранения файлов. Используйте QR или настройте подключение вручную." else export.launch(request)} catch(_:android.content.ActivityNotFoundException) {importError="На устройстве нет приложения для сохранения файлов. Используйте QR или настройте подключение вручную."} else importError="Исправьте адреса перед экспортом"}) {Text("Экспорт конфигурации")}
            SettingsHint("Экспорт не содержит ключей и паролей. Подключения можно сохранить без проверки.")
        }}
        if(section==null)item {Text("MJWatch ${model.updates.versionName} · TMDB: необязательный каталог. This product uses the TMDB API but is not endorsed or certified by TMDB.\nТестовое видео: Android Emulator virtual scene, Apache 2.0",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    }
    preview?.let { imported -> AlertDialog(onDismissRequest={preview=null},title={Text("Применить конфигурацию?")},text={Text("Jackett: ${imported.jackett}\nИндексаторы: ${imported.indexers}\nTorrServer: ${imported.torrServer}\nКлючи потребуется ввести заново. Сетевые запросы не выполнялись.")},confirmButton={TextButton(onClick={c=imported.copy(serverId="",userId="");key="";user="";password="";preview=null;model.invalidateChecks()}) {Text("Применить к форме")}},dismissButton={TextButton(onClick={preview=null}) {Text("Отмена")}}) }
}
@Composable private fun SecretField(value:String,onChange:(String)->Unit,label:String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value,onChange,label={Text(label)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),visualTransformation=if(visible) VisualTransformation.None else PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth().tvFieldNavigation(),shape=MaterialTheme.shapes.small,trailingIcon={IconButton(onClick={visible=!visible}) {Icon(if(visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(visible) "Скрыть пароль" else "Показать пароль")}})
}
internal fun time(ms:Long):String = "%d:%02d".format(ms/60000,ms/1000%60)

@Composable private fun SettingsOverview(s:WatchState,model:WatchModel,tv:Boolean,onExit:()->Unit) {
    var section by rememberSaveable {mutableStateOf("Подключения")}
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var themes by remember {mutableStateOf(false)}
    if(themes) AlertDialog(onDismissRequest={themes=false},title={Text("Тема")},text={Column {listOf("system" to "Как в системе","dark" to "Тёмная","light" to "Светлая").forEach {(id,label)->PreferenceChoice(label,s.config.theme==id) {scope.launch {Settings(context).save(s.config.copy(theme=id))};themes=false}}}},confirmButton={TextButton(onClick={themes=false}) {Text("Закрыть")}})
    val appearance:@Composable ()->Unit={SettingsCard("Тема приложения",Icons.Outlined.Palette,tv) {
        Text("Выберите оформление для этого устройства.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("system" to "Как в системе","dark" to "Тёмная","light" to "Светлая").forEach {(id,label)->PreferenceChoice(label,s.config.theme==id,tv) {scope.launch {Settings(context).save(s.config.copy(theme=id))}}}
    }}
    if(tv) {
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
            Column(Modifier.width(tvUnit(320f)).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                ((listOf("Подключения")+if(s.config.managed)listOf("Аккаунт") else emptyList())+listOf("Воспроизведение","Оформление","О приложении")).forEach {label->Action(label,true,Modifier.fillMaxWidth(),secondary=section!=label) {section=label}}
                Action("Выйти из приложения",true,Modifier.fillMaxWidth(),secondary=true,icon=Icons.Outlined.ExitToApp,onClick=onExit)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical=4.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {when(section) {
                "Подключения"->SettingsCard("Источники и сервер",Icons.Outlined.Hub,true){ConnectionLinks(s,model,true)}
                "Аккаунт"->AccountLinks(s,model,true)
                "Воспроизведение"->PlaybackPreferences(true,model)
                "Оформление"->appearance()
                else->Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {Wordmark();UpdateLink(model,true);Text("TMDB: необязательный каталог. This product uses the TMDB API but is not endorsed or certified by TMDB.")}
            }}
        }
        return
    }
    LazyColumn(Modifier.testTag("settings-list"),verticalArrangement=Arrangement.spacedBy(20.dp),contentPadding=PaddingValues(vertical=16.dp)) {
        item {SettingsCard("Источники и сервер",Icons.Outlined.Hub,false){ConnectionLinks(s,model,false)}}
        if(s.config.managed)item {SettingsCard("Аккаунт",Icons.Outlined.PersonOutline,false){AccountLinks(s,model,false)}}
        item {PlaybackPreferences(false,model)}
        item {SettingsCard("Оформление",Icons.Outlined.Contrast,false) {
            SettingsActionRow(Icons.Outlined.Palette,"Тема",when(s.config.theme) {"dark"->"Тёмная";"light"->"Светлая";else->"Как в системе"},false) {themes=true}
        }}
        item {SettingsCard("Приложение",Icons.Outlined.Info,false){UpdateLink(model,false)}}
        item {Action("Выйти из приложения",false,Modifier.fillMaxWidth(),secondary=true,icon=Icons.Outlined.ExitToApp,onClick=onExit)}
        item {Text("MJWatch ${model.updates.versionName}\nTMDB: необязательный каталог. This product uses the TMDB API but is not endorsed or certified by TMDB.\nТестовое видео: Android Emulator virtual scene, Apache 2.0",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)}
    }
}
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
    SettingsCard("Воспроизведение",Icons.Outlined.PlayCircleOutline,tv) {
        Text("Предпочтения для следующих просмотров. Дорожки можно менять и в самом плеере.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsGroup(tv) {
        SettingsActionRow(Icons.Outlined.HighQuality,"Предпочтительное качество",if(quality=="Все") "Любое" else quality,tv) {qualityDialog=true}
        SettingsDivider()
        SettingsActionRow(Icons.Outlined.Translate,"Язык аудио",when(audio) {"ru"->"Русский";"en"->"Английский";"ja"->"Японский";else->"Авто"},tv) {dialog=true}
        SettingsDivider()
        SettingsActionRow(Icons.Outlined.Subtitles,"Субтитры",subtitleLabel,tv) {subtitleDialog=true}
        }
    }
}

@Composable internal fun SettingsRow(icon:ImageVector,title:String,value:String,modifier:Modifier=Modifier,chevron:Boolean=true,expanded:Boolean?=null) {
    Row(modifier.fillMaxWidth().heightIn(min=48.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(title,fontWeight=FontWeight.Medium,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurface);Text(value.ifBlank {"Не настроен"},fontSize=13.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)}
        if(chevron) Icon(if(expanded==true)Icons.Outlined.ExpandLess else if(expanded==false)Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
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
            Column(Modifier.fillMaxWidth().background(featureSurfaceGradient(),MaterialTheme.shapes.large).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                PreparationStep(Icons.Outlined.CheckCircle,"Раздача выбрана",s.selected?.resolution.orEmpty(),true)
                HorizontalDivider(color=MaterialTheme.colorScheme.outline.copy(alpha=.15f))
                Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp);Column {Text("Получаем информацию о файлах",fontWeight=FontWeight.Medium);Text("Ожидаем ответ TorrServer",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                HorizontalDivider(color=MaterialTheme.colorScheme.outline.copy(alpha=.15f))
                PreparationStep(Icons.Outlined.PlayCircle,"Затем — выбор файла","Видео откроется в плеере",false)
            }
            Text("Прошло $elapsed с. Подготовку можно отменить.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(!s.busy&&s.message.isNotBlank()) Text(s.message,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
        if(!s.busy) {Icon(Icons.Outlined.CloudOff,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.error);if(!missingInput&&model.canStartPlayback)Action("Повторить",tv,Modifier.focusRequester(recoveryFocus),icon=Icons.Outlined.Refresh) {model.prepare()};Action("Подключения",tv,secondary=true,icon=Icons.Outlined.Settings) {model.page("connections")}}
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

@Composable internal fun SettingsGroup(tv:Boolean,featured:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    val shape=MaterialTheme.shapes.large
    val background=if(featured) Modifier.background(featureSurfaceGradient(),shape) else Modifier.background(MaterialTheme.colorScheme.surface,shape)
    Column(Modifier.fillMaxWidth().then(background).then(if(tv) Modifier else Modifier.clip(shape)),content=content)
}
@Composable internal fun SettingsDivider() {HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.outline.copy(alpha=.18f))}
@Composable internal fun SettingsActionRow(icon:ImageVector,title:String,value:String,tv:Boolean,onClick:()->Unit) =
    SettingsActionRow(icon,title,value,tv,expanded=null,onClick=onClick)
@Composable internal fun SettingsActionRow(icon:ImageVector,title:String,value:String,tv:Boolean,expanded:Boolean?,onClick:()->Unit) {
    if(tv) NativeCard(true,Modifier.fillMaxWidth(),onClick=onClick) {SettingsRow(icon,title,value,Modifier.padding(16.dp),expanded=expanded)}
    else Surface(onClick=onClick,color=androidx.compose.ui.graphics.Color.Transparent,modifier=Modifier.fillMaxWidth()) {SettingsRow(icon,title,value,Modifier.padding(horizontal=16.dp,vertical=8.dp),expanded=expanded)}
}
@Composable private fun ConnectionLinks(s:WatchState,model:WatchModel,tv:Boolean) {
    val account by model.managed.state.collectAsState()
    val managed=account.takeIf {it.scope==s.config.scope}
    val admin=managed?.me?.optString("role")=="admin"
    SettingsGroup(tv) {
        SettingsActionRow(Icons.Outlined.Search,if(s.config.managed) "Поиск раздач" else "Jackett / JacRed",when {
            s.config.demo -> "Демонстрационный режим"
            s.config.managed -> "Сервер MJWatch"
            else -> s.config.jackett
        },tv) {model.page("settings-search")}
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
    val requester=remember {FocusRequester()}
    var restoreFocus by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(Unit) {if(tv&&restoreFocus) {withFrameNanos {};requester.requestFocus()}}
    SettingsGroup(tv) {Box(Modifier.focusRequester(requester).focusGroup()) {SettingsActionRow(Icons.Outlined.SystemUpdate,"Обновления",update.available?.let {"Доступна ${it.versionName} · сборка ${it.versionCode}"} ?: "Версия ${model.updates.versionName} · сборка ${model.updates.versionCode}",tv) {restoreFocus=true;model.page("settings-updates")}}}
}
@Composable private fun RailButton(tv:Boolean,icon:ImageVector,label:String,selected:Boolean,modifier:Modifier,onClick:()->Unit) {
    val colors=MaterialTheme.colorScheme;val shape=RoundedCornerShape(12.dp)
    if(tv) androidx.tv.material3.IconButton(onClick=onClick,modifier=modifier.semantics {this.selected=selected;role=Role.Tab},shape=androidx.tv.material3.IconButtonDefaults.shape(shape=shape),scale=androidx.tv.material3.IconButtonDefaults.scale(focusedScale=1f,pressedScale=1f),colors=androidx.tv.material3.IconButtonDefaults.colors(containerColor=if(selected) colors.primaryContainer else colors.background,contentColor=if(selected) colors.primary else colors.onSurface,focusedContainerColor=colors.primaryContainer,focusedContentColor=colors.primary),border=androidx.tv.material3.IconButtonDefaults.border(focusedBorder=tvFocusBorder(12.dp))) {Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {Icon(icon,label,Modifier.size(24.dp),tint=androidx.tv.material3.LocalContentColor.current);Text(label,fontSize=11.sp,color=androidx.tv.material3.LocalContentColor.current,maxLines=1)}}
    else Surface(onClick=onClick,modifier=modifier.tvFocusRing(true,shape).semantics {this.selected=selected;role=Role.Tab},shape=shape,color=if(selected) colors.primaryContainer else colors.background,contentColor=if(selected) colors.primary else colors.onSurface) {Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {Icon(icon,label,Modifier.size(24.dp))}}
}
