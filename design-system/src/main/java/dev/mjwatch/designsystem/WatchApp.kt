@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.mjwatch.designsystem

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
private val destinations=listOf(Destination("home","Главная",Icons.Outlined.Home),Destination("search","Поиск",Icons.Outlined.Search),Destination("library","Моё",Icons.Outlined.Bookmarks),Destination("settings","Настройки",Icons.Outlined.Settings))

@Composable fun WatchApp(model:WatchModel,tv:Boolean) {
    val state by model.state.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val screenStates=rememberSaveableStateHolder()
    val searchScroll=rememberLazyListState()
    WatchTheme(state.config.theme) {
        val scheme=MaterialTheme.colorScheme
        val activity=LocalContext.current as? android.app.Activity
        SideEffect {activity?.let {a ->
            val dark=scheme.background.luminance()<0.5f
            WindowCompat.getInsetsController(a.window,a.window.decorView).apply {isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}
            if(android.os.Build.VERSION.SDK_INT>=29) a.window.isNavigationBarContrastEnforced=false
        }}
        androidx.tv.material3.MaterialTheme(colorScheme=androidx.tv.material3.darkColorScheme(primary=scheme.primary,onPrimary=scheme.onPrimary,surface=scheme.surface,onSurface=scheme.onSurface,surfaceVariant=scheme.surfaceVariant,onSurfaceVariant=scheme.onSurfaceVariant)) {
            val width=LocalConfiguration.current.screenWidthDp
            val rail=tv||width>=600
            val opacity=remember {Animatable(1f)}
            LaunchedEffect(state.page) {opacity.snapTo(0.85f);opacity.animateTo(1f,tween(180))}
            val top=state.page in destinations.map { it.id }
            val railFocus=remember {FocusRequester()}; var railFocused by remember {mutableStateOf(false)}
            BackHandler(tv&&state.page=="home"&&!railFocused&&state.configured) {railFocus.requestFocus()}
            val imeVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
            BackHandler(state.page!="home" && !imeVisible) { model.back() }
            Scaffold(containerColor=scheme.background, bottomBar={ if(!rail&&top&&state.configured) NavigationBar {
                destinations.forEach { d -> NavigationBarItem(selected=state.page==d.id,onClick={model.page(d.id)},icon={Icon(d.icon,null)},label={Text(d.title)}) }
            } }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding()) {
                    if(rail&&state.configured) Column(Modifier.width(if(tv) 88.dp else 80.dp).fillMaxHeight().background(scheme.background).padding(top=24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("MJ",color=scheme.primary,fontSize=26.sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(28.dp))
                        destinations.forEach { d ->
                            if(tv) androidx.tv.material3.IconButton(onClick={model.page(d.id)},colors=androidx.tv.material3.IconButtonDefaults.colors(containerColor=if(state.page==d.id||!top&&state.originPage==d.id) scheme.primaryContainer else scheme.background,contentColor=if(state.page==d.id||!top&&state.originPage==d.id) scheme.primary else scheme.onSurface,focusedContainerColor=scheme.primary,focusedContentColor=scheme.onPrimary),modifier=Modifier.padding(vertical=8.dp).size(52.dp).then(if(d.id=="home") Modifier.focusRequester(railFocus) else Modifier).onFocusChanged {railFocused=it.isFocused}) { Icon(d.icon,d.title,tint=androidx.tv.material3.LocalContentColor.current) }
                            else NavigationRailItem(selected=state.page==d.id,onClick={model.page(d.id)},icon={Icon(d.icon,d.title)},label={Text(d.title)})
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().graphicsLayer {alpha=opacity.value}.padding(horizontal=if(tv) 32.dp else 20.dp)) {
                        if(state.page!="settings"||state.configured) Row(Modifier.fillMaxWidth().padding(vertical=16.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(!top) IconButton(onClick={model.back()}) { Icon(Icons.Outlined.ArrowBack,"Назад") }
                            Text(if(top) "MJWatch" else when(state.page){"detail"->"О раздаче";"releases"->"Выберите раздачу";"files"->"Выберите файл";else->"Подготовка потока"},style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                            if(state.config.demo) Text("ДЕМО",color=scheme.primary,style=MaterialTheme.typography.labelLarge)
                        }
                        screenStates.SaveableStateProvider(state.page+(if(state.page in listOf("detail","releases","files")) state.selected?.id.orEmpty() else "")+(if(state.page=="files") state.config.torrServer else "")) {
                        when(state.page) {
                            "settings" -> SettingsScreen(state,model,tv)
                            "home" -> HomeScreen(state,library,progress,model,tv)
                            "search" -> SearchScreen(state,model,tv,searchScroll)
                            "library" -> LibraryScreen(library,progress,model,tv)
                            "detail" -> if(!tv&&width>=840&&state.originPage=="search") Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                                Column(Modifier.weight(1f).fillMaxHeight()) {SearchScreen(state,model,false,searchScroll)}
                                VerticalDivider()
                                Column(Modifier.weight(1f).fillMaxHeight()) {DetailScreen(state,library,progress,model,false,compact=true)}
                            } else DetailScreen(state,library,progress,model,tv)
                            "releases" -> ReleasesScreen(state,model,tv)
                            "files" -> FilesScreen(state,model,tv)
                            "preparing" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical=32.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                                Icon(Icons.Outlined.CloudDownload,null,modifier=Modifier.size(64.dp),tint=scheme.primary)
                                Heading("Готовим просмотр")
                                Text(state.message)
                                if(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); var longWait by remember { mutableStateOf(false) }; LaunchedEffect(Unit) { delay(30000); longWait=true }; if(longWait) Text("Подготовка занимает больше времени") }
                                Action("Отменить",tv) { model.back() }
                                if(!state.busy) Action("Повторить получение файлов",tv) { model.prepare() }
                            }
                        }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun Heading(text:String) { Text(text,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,modifier=Modifier.semantics { heading() }) }
@Composable private fun Action(text:String,tv:Boolean,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit) {
    if(tv) androidx.tv.material3.Button(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=52.dp),
        colors=androidx.tv.material3.ButtonDefaults.colors(containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary,focusedContainerColor=MaterialTheme.colorScheme.primary,focusedContentColor=MaterialTheme.colorScheme.onPrimary),
        scale=androidx.tv.material3.ButtonDefaults.scale(focusedScale=1f),border=androidx.tv.material3.ButtonDefaults.border(focusedBorder=androidx.tv.material3.Border(BorderStroke(3.dp,MaterialTheme.colorScheme.onSurface)))) { androidx.tv.material3.Text(text,fontSize=18.sp) }
    else Button(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=52.dp)) { Text(text) }
}
@Composable private fun Empty(title:String,body:String,tv:Boolean,action:String="Открыть поиск",onClick:()->Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical=32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Icon(Icons.Outlined.Movie,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
        Heading(title); Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant); Action(action,tv,onClick=onClick)
    }
}
@Composable private fun ReleaseCard(release:Release,tv:Boolean,focus:Boolean=false,onLongClick:(()->Unit)?=null,onClick:()->Unit) {
    val requester=remember { FocusRequester() }
    LaunchedEffect(focus) { if(tv&&focus) { delay(100); requester.requestFocus() } }
    val content:@Composable ()->Unit={
        Row(Modifier.fillMaxWidth().padding(20.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.Movie,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(release.title,fontSize=if(tv) 20.sp else 17.sp,fontWeight=FontWeight.Bold)
                Text("${release.source} · ${release.resolution}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${release.size?.let { "%.2f ГБ".format(it/1e9) } ?: "Размер не указан"} · ${release.seeds?.let { "$it сидов" } ?: "Сиды не указаны"}",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if(tv) androidx.tv.material3.Card(onClick=onClick,onLongClick=onLongClick,colors=androidx.tv.material3.CardDefaults.colors(containerColor=MaterialTheme.colorScheme.surface,focusedContainerColor=MaterialTheme.colorScheme.surfaceVariant),modifier=Modifier.fillMaxWidth().focusRequester(requester),scale=androidx.tv.material3.CardDefaults.scale(focusedScale=1f),border=androidx.tv.material3.CardDefaults.border(focusedBorder=androidx.tv.material3.Border(BorderStroke(3.dp,MaterialTheme.colorScheme.primary))),content={content()})
    else Card(onClick=onClick,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),content={content()})
}
@Composable private fun HomeScreen(s:WatchState,entries:List<LibraryEntry>,progress:List<Progress>,model:WatchModel,tv:Boolean) {
    LazyColumn(Modifier.testTag("home-list"),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        if(s.config.demo) item {
            val heroFocus=remember {FocusRequester()}
            LaunchedEffect(s.focusKey) {if(tv&&s.focusKey.startsWith("hero:")) {delay(100);heroFocus.requestFocus()}}
            Box(Modifier.fillMaxWidth().height(if(tv) 300.dp else 360.dp).clip(RoundedCornerShape(24.dp))) {
                Image(painterResource(dev.mjwatch.designsystem.R.drawable.demo_orbit),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent,androidx.compose.ui.graphics.Color(0xF00C0E15)))))
                Column(Modifier.align(Alignment.BottomStart).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("ДЕМОНСТРАЦИЯ",color=Lime); Text("Тихая орбита",fontSize=34.sp,color=androidx.compose.ui.graphics.Color.White,fontWeight=FontWeight.Bold)
                    Action("О тестовой раздаче",tv,Modifier.focusRequester(heroFocus)) {model.open(DemoReleases.items.first(),"hero:${DemoReleases.items.first().id}")}
                }
            }
        }
        if(!s.config.demo) item { Empty("Найдите свою раздачу","Прямой поиск через Jackett. Без метаданных постеры и описания скрыты.",tv,onClick={model.page("search")}) }
        val continuing=entries.filter { e -> e.openedAt>0&&progress.any { it.releaseId==e.id&&!it.completed&&it.positionMs>0 } }
        if(continuing.isNotEmpty()) item { Heading("Продолжить просмотр") }
        items(continuing,key={it.id}) { e -> LibraryCard(e,progress,model,tv) }
        if(s.config.demo) {
            item { Heading("Тестовые раздачи") }
            items(DemoReleases.items,key={it.id}) { r -> ReleaseCard(r,tv,s.focusKey==r.id) { model.open(r) } }
        }
    }
}
@Composable private fun SearchScreen(s:WatchState,model:WatchModel,tv:Boolean,listState:LazyListState) {
    val keyboard=LocalSoftwareKeyboardController.current
    val context=LocalContext.current
    val voice=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result -> if(result.resultCode==android.app.Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(model::search)}
    val voiceAvailable=remember {context.packageManager.resolveActivity(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),0)!=null}
    OutlinedTextField(value=s.query,onValueChange=model::search,label={Text("Название фильма или раздачи")},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(voiceAvailable) IconButton(onClick={voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU"))}) {Icon(Icons.Outlined.Mic,"Голосовой поиск")}},singleLine=true,
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide(); model.search(s.query)}),modifier=Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    if(s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    LazyColumn(state=listState,verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=16.dp)) {
        if(s.failures.isNotEmpty()) item { Text("Не ответили источники: ${s.failures.joinToString("; ")}",color=MaterialTheme.colorScheme.error); Action("Повторить недоступные источники",tv) {model.loadMore(true)} }
        if(s.message.isNotEmpty()) item { Text(s.message,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite }) }
        if(s.query.length<2) item { Text("Введите не менее двух символов. Поиск раздач работает без каталога.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(s.metadata.isNotEmpty()) item { Heading("Каталог TMDB · выберите произведение") }
        items(s.metadata,key={it.id}) { m ->
            Card(onClick={model.metadata(m)},modifier=Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    val context=LocalContext.current
                    val loader=remember(s.config) {ImageLoader.Builder(context).okHttpClient(guardedClient(s.config)).build()}
                    AsyncImage(model=m.poster,contentDescription=null,imageLoader=loader,modifier=Modifier.width(80.dp).height(120.dp),contentScale=ContentScale.Crop)
                    Column(Modifier.weight(1f)) {Text(m.title,fontWeight=FontWeight.Bold);Text(m.overview.orEmpty(),maxLines=4)}
                }
            }
        }
        items(s.results,key={it.id}) { r -> ReleaseCard(r,tv,s.focusKey==r.id) { keyboard?.hide(); model.open(r) } }
        if(s.more&&!s.busy) item {Action("Загрузить ещё",tv) {model.loadMore()} }
    }
}
@Composable private fun DetailScreen(s:WatchState,entries:List<LibraryEntry>,progress:List<Progress>,model:WatchModel,tv:Boolean,compact:Boolean=false) {
    val r=s.selected ?: return
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) {
            if(!compact&&(tv||LocalConfiguration.current.screenWidthDp>=840)) Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.width(180.dp).height(260.dp)) { Box(contentAlignment=Alignment.Center) { Icon(Icons.Outlined.Movie,null,Modifier.size(64.dp)) } }
            Column(verticalArrangement=Arrangement.spacedBy(16.dp),modifier=Modifier.weight(1f)) {
                Heading(r.title)
                Text("${r.source} · ${r.resolution}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Свойства устройства и дорожек ещё не проверены. Технические признаки в названии — подсказки источника.")
                Action("Выбрать раздачу",tv) { model.page("releases") }
                Action(if(entries.any { it.id==r.id&&it.bookmarked }) "Убрать из «Буду смотреть»" else "В «Буду смотреть»",tv) { model.bookmark() }
            }
        }
        progress.filter { it.releaseId==r.id }.forEach { p -> Text("Сохранено ${p.path} · ${time(p.positionMs)}${p.durationMs?.let { " / ${time(it)}" } ?: ""}${if(p.completed) " · Завершено" else ""}") }
        Text("Исходное название",style=MaterialTheme.typography.titleMedium)
        Text(r.title,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun ReleasesScreen(s:WatchState,model:WatchModel,tv:Boolean) {
    var filter by rememberSaveable {mutableStateOf("Все")}
    var selectedId by rememberSaveable {mutableStateOf(s.selected?.id)}
    var source by rememberSaveable {mutableStateOf("Все источники")}
    var seeds by rememberSaveable {mutableStateOf("Любые сиды")}
    var language by rememberSaveable {mutableStateOf("Любой язык")}
    val candidates=(s.results+listOfNotNull(s.selected)).distinctBy {it.id}
    val releases=candidates.filter {r ->
        val russian=Regex("(?i)(rus|russian|рус|дубляж)").containsMatchIn(r.title)
        (filter=="Все"||r.resolution.equals(filter,true))&&(source=="Все источники"||r.source==source)&&
            when(seeds) {"Есть сиды" -> r.seeds?.let {it>0}==true;"0 сидов" -> r.seeds==0;"Сиды не указаны" -> r.seeds==null;else -> true}&&
            when(language) {"Русский (название)" -> russian;"Русский не указан" -> !russian;else -> true}
    }
    LazyColumn(Modifier.testTag("release-list"),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Все","1080p","2160p","720p","Не указано").forEach {label -> FilterChip(selected=filter==label,onClick={filter=label},label={Text(label)})}
        }}
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            (listOf("Все источники")+candidates.map {it.source}.distinct()).forEach {label -> FilterChip(selected=source==label,onClick={source=label},label={Text(label)})}
        }}
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Любые сиды","Есть сиды","0 сидов","Сиды не указаны").forEach {label -> FilterChip(selected=seeds==label,onClick={seeds=label},label={Text(label)})}
        }}
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Любой язык","Русский (название)","Русский не указан").forEach {label -> FilterChip(selected=language==label,onClick={language=label},label={Text(label)})}
        }}
        items(releases,key={it.id}) {r -> Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            ReleaseCard(r,tv) {selectedId=r.id;if(tv) {if(s.selected?.id!=r.id) model.open(r);model.prepare()}}
            if(!tv&&selectedId==r.id) Action("Продолжить с выбранной раздачей",false,Modifier.fillMaxWidth()) {if(s.selected?.id!=r.id) model.open(r);model.prepare()}
        }}
        if(releases.isEmpty()) item {Empty("Нет раздач с этими фильтрами","Неизвестные свойства доступны в «Не указано».",tv,"Сбросить фильтры") {filter="Все";source="Все источники";seeds="Любые сиды";language="Любой язык"}}
    }
}
@Composable private fun FilesScreen(s:WatchState,model:WatchModel,tv:Boolean) {
    val context=LocalContext.current
    val playerLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {model.playerClosed()}
    var showAll by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable(s.handle?.hash) { mutableStateOf<Int?>(null) }
    val progress by model.progress.collectAsStateWithLifecycle()
    fun launch(file:TorrentFile) {
        val request=model.stream(file)
        playerLauncher.launch(Intent(context,PlaybackActivity::class.java).putExtra("uri",request.uri).putExtra("releaseId",s.selected!!.id).putExtra("fileId",file.serverFileId).putExtra("path",file.path).putExtra("mobile",!tv))
    }
    LaunchedEffect(s.handle?.hash) {
        val videos=s.handle?.files.orEmpty().filter {it.video&&!it.sample}
        if(videos.size==1) {selection=videos.single().serverFileId;launch(videos.single())}
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Выберите серию или видеофайл для просмотра.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        FilterChip(selected=showAll,onClick={showAll=!showAll},label={Text("Показывать все файлы и sample")})
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(s.handle?.files.orEmpty().filter { showAll||it.video&&!it.sample }.sortedBy { it.path },key={it.serverFileId}) { f ->
                FileCard(f,tv,selection==f.serverFileId) {if(f.video) selection=f.serverFileId}
            }
        }
        val file=s.handle?.files?.firstOrNull { it.serverFileId==selection }
        if(file!=null&&file.video) {
            val p=progress.firstOrNull { it.key=="${s.selected?.id}|${file.path}" }
            if(p!=null) Text("Сохранено ${time(p.positionMs)}${if(p.completed) " · завершено" else ""}")
            Action("Смотреть${if(p!=null&&!p.completed) " с ${time(p.positionMs)}" else ""}",tv,Modifier.fillMaxWidth()) {
                launch(file)
            }
        } else Text("Выберите файл для просмотра",Modifier.padding(bottom=24.dp))
    }
}
@Composable private fun FileCard(file:TorrentFile,tv:Boolean,selected:Boolean,onClick:()->Unit) {
    val modifier=Modifier.fillMaxWidth().semantics {this.selected=selected;role=Role.RadioButton}
    val content:@Composable ()->Unit={
        Row(Modifier.padding(20.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(if(selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,null,tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(file.path,fontWeight=FontWeight.Bold,fontSize=if(tv) 20.sp else 17.sp)
                Text("%.1f МБ".format(file.size/1e6)+if(file.sample) " · sample" else if(file.video) " · Видео" else " · Дополнительный файл",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if(tv) androidx.tv.material3.Card(onClick=onClick,modifier=modifier,scale=androidx.tv.material3.CardDefaults.scale(focusedScale=1f),
        colors=androidx.tv.material3.CardDefaults.colors(containerColor=MaterialTheme.colorScheme.surface,focusedContainerColor=MaterialTheme.colorScheme.surfaceVariant),
        border=androidx.tv.material3.CardDefaults.border(focusedBorder=androidx.tv.material3.Border(BorderStroke(3.dp,MaterialTheme.colorScheme.primary)))) {content()}
    else Card(onClick=onClick,modifier=modifier) {content()}
}
@Composable private fun LibraryCard(e:LibraryEntry,progress:List<Progress>,model:WatchModel,tv:Boolean) {
    var menu by remember {mutableStateOf(false)}
    if(menu) AlertDialog(onDismissRequest={menu=false},title={Text(e.title)},text={Text("Локальная библиотека")},confirmButton={TextButton(onClick={model.remove(e);menu=false}) {Text("Удалить из истории")}},dismissButton={TextButton(onClick={menu=false}) {Text("Отмена")}})
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        ReleaseCard(Release(e.id,e.title,e.source),tv,focus=model.state.value.focusKey==e.id,onLongClick={menu=true}) {model.restore(e)}
        progress.firstOrNull { it.releaseId==e.id }?.let { p ->
            Text("${time(p.positionMs)}${p.durationMs?.let { " / ${time(it)}" } ?: ""}${if(p.completed) " · Завершено" else ""}")
            p.durationMs?.takeIf { it>0 }?.let { LinearProgressIndicator(progress={ (p.positionMs.toFloat()/it).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth()) }
        }
    }
}
@Composable private fun LibraryScreen(entries:List<LibraryEntry>,progress:List<Progress>,model:WatchModel,tv:Boolean) {
    var tab by rememberSaveable { mutableStateOf("Продолжить") }; var removed by remember { mutableStateOf<LibraryEntry?>(null) }
    val filtered=entries.filter { e -> when(tab) { "Буду смотреть" -> e.bookmarked; "Продолжить" -> e.openedAt>0&&progress.any { it.releaseId==e.id&&!it.completed&&it.positionMs>0 }; else -> e.openedAt>0 } }
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("Продолжить","Буду смотреть","История").forEach { t -> FilterChip(selected=t==tab,onClick={tab=t},label={Text(t)}) } }
        removed?.let { e -> Row(verticalAlignment=Alignment.CenterVertically) { Text("Удалено локально",Modifier.weight(1f)); Action("Отменить",tv) {model.undo(e);removed=null} } }
        LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(filtered.isEmpty()) item { Empty("Пока пусто",if(tab=="Буду смотреть") "Сохраните раздачу на потом" else "Здесь появится то, что вы смотрели",tv,onClick={model.page("search")}) }
            items(filtered,key={it.id}) { e -> Column { LibraryCard(e,progress,model,tv); TextButton(onClick={model.remove(e,tab=="Буду смотреть");removed=e}) { Text("Удалить локально") } } }
        }
    }
}
@Composable private fun SettingsScreen(s:WatchState,model:WatchModel,tv:Boolean) {
    val context=LocalContext.current
    var c by remember { mutableStateOf(model.draftConfig ?: s.config) }
    var metadataToken by remember { mutableStateOf(model.draftMetadata) }; var key by remember { mutableStateOf(model.draftKey) }; var user by remember { mutableStateOf(model.draftUser) }; var password by remember { mutableStateOf(model.draftPassword) }
    var stopConfirmation by remember {mutableStateOf(false)}
    fun applySettings() {
        if(c.torrServer!=s.config.torrServer&&PlaybackActivity.hasActivePlayback()) stopConfirmation=true
        else model.save(c,key,user,password,metadataToken)
    }
    if(stopConfirmation) AlertDialog(onDismissRequest={stopConfirmation=false},title={Text("Остановить просмотр и применить?")},text={Text("Адрес TorrServer меняется. Позиция текущего просмотра будет сохранена.")},confirmButton={TextButton(onClick={stopConfirmation=false;PlaybackActivity.stopActivePlayback();model.save(c,key,user,password,metadataToken)}) {Text("Остановить и применить")}},dismissButton={TextButton(onClick={stopConfirmation=false}) {Text("Отмена")}})
    var leave by remember {mutableStateOf(false)}
    var baseline by remember {mutableStateOf(listOf("","","",""))}
    var preview by remember { mutableStateOf<Config?>(null) }; var importError by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if(model.draftConfig==null) { metadataToken=model.secrets.get("tmdb"); key=model.secrets.get("jackett:${s.config.jackett}");user=model.secrets.get("torrUser:${s.config.torrServer}");password=model.secrets.get("torrPassword:${s.config.torrServer}") }; baseline=listOf(model.secrets.get("jackett:${s.config.jackett}"),model.secrets.get("torrUser:${s.config.torrServer}"),model.secrets.get("torrPassword:${s.config.torrServer}"),model.secrets.get("tmdb")) }
    LaunchedEffect(c,key,user,password,metadataToken) {model.draftConfig=c;model.draftKey=key;model.draftUser=user;model.draftPassword=password;model.draftMetadata=metadataToken}
    BackHandler(c!=s.config||listOf(key,user,password,metadataToken)!=baseline) {leave=true}
    if(leave) AlertDialog(onDismissRequest={leave=false},title={Text("Сохранить изменения?")},text={Text("Введённые параметры ещё не применены")},confirmButton={TextButton(onClick={leave=false;applySettings()}) {Text("Сохранить")}},dismissButton={Row {TextButton(onClick={leave=false;model.discardDraft();model.page("home")}) {Text("Не сохранять")};TextButton(onClick={leave=false}) {Text("Отмена")}}})
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) context.contentResolver.openOutputStream(uri)?.use { it.write(encodeConfig(c).toByteArray()) } }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) try {
        val raw=context.contentResolver.openInputStream(uri)!!.use { String(it.readBytes().also { b -> require(b.size<65536) }) }; preview=decodeConfig(raw)
    } catch(_:Exception) {importError="Не удалось импортировать конфигурацию"} }
    fun urlError(raw:String)=raw.isNotBlank()&&runCatching {endpoint(raw)}.isFailure
    fun changed(new:Config) { c=new; model.invalidateChecks() }
    LazyColumn(Modifier.testTag("settings-list"),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(vertical=24.dp)) {
        item { Heading("Подключения") }
        item { Text("Jackett ищет раздачи. TorrServer готовит поток. Каталог необязателен.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(verticalAlignment=Alignment.CenterVertically) { Switch(modifier=Modifier.semantics {contentDescription="Демонстрация с локальным видео"},checked=c.demo,onCheckedChange={changed(c.copy(demo=it))}); Text("Демонстрация с локальным видео",Modifier.padding(start=12.dp)) } }
        item { OutlinedTextField(c.jackett,{key=""; changed(c.copy(jackett=it))},label={Text("Адрес Jackett")},isError=urlError(c.jackett),supportingText={Text(if(urlError(c.jackett)) "Введите http/https URL без логина, query и fragment" else "https://host или локальный http://host:port")},modifier=Modifier.fillMaxWidth(),singleLine=true) }
        item { SecretField(key,{key=it;model.invalidateChecks()},"API-ключ Jackett") }
        item { OutlinedTextField(c.indexers,{changed(c.copy(indexers=it))},label={Text("ID индексаторов через запятую")},modifier=Modifier.fillMaxWidth()) }
        item { Action("Проверить Jackett",tv) {model.check(true,c,key,user,password)}; Text(s.checkJackett,Modifier.padding(top=8.dp)) }
        item { HorizontalDivider(); Heading("TorrServer") }
        item { OutlinedTextField(c.torrServer,{user="";password="";changed(c.copy(torrServer=it))},label={Text("Адрес TorrServer")},isError=urlError(c.torrServer),supportingText={if(urlError(c.torrServer)) Text("Введите http/https URL без логина, query и fragment")},modifier=Modifier.fillMaxWidth(),singleLine=true) }
        item { OutlinedTextField(user,{user=it;model.invalidateChecks()},label={Text("Basic Auth: логин (необязательно)")},modifier=Modifier.fillMaxWidth(),singleLine=true) }
        item { SecretField(password,{password=it;model.invalidateChecks()},"Пароль TorrServer") }
        item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(modifier=Modifier.semantics {contentDescription="Разрешить локальный HTTP"},checked=c.localHttp,onCheckedChange={changed(c.copy(localHttp=it))}); Text("Разрешить HTTP только для этих локальных серверов",Modifier.weight(1f)) } }
        item { Action("Проверить TorrServer",tv) {model.check(false,c,key,user,password)}; Text(s.checkTorr,Modifier.padding(top=8.dp)) }
        item { Heading("Каталог · необязательно") }
        item { Row(verticalAlignment=Alignment.CenterVertically) { Switch(modifier=Modifier.semantics {contentDescription="Каталог TMDB"},checked=c.metadataProvider=="tmdb",onCheckedChange={c=c.copy(metadataProvider=if(it) "tmdb" else "none")}); Text("TMDB API v3",Modifier.padding(start=12.dp)) } }
        if(c.metadataProvider=="tmdb") item {SecretField(metadataToken,{metadataToken=it},"TMDB Read Access Token")}
        item { Heading("Оформление") }
        item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("system" to "Системная","dark" to "Тёмная","light" to "Светлая").forEach { (id,label) -> FilterChip(selected=c.theme==id,onClick={c=c.copy(theme=id)},label={Text(label)}) } } }
        item { Text("Секреты шифруются Android Keystore. Экспорт не содержит ключей и паролей. Подключения можно сохранить без проверки.") }
        if(s.message.isNotBlank()) item { Text(s.message,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite }) }
        item { Action(if(s.busy) "Сохранение…" else "Сохранить и продолжить",tv,Modifier.fillMaxWidth(),enabled=!s.busy) {applySettings()} }
        item { Action("Экспорт конфигурации",tv) {if(!urlError(c.jackett)&&!urlError(c.torrServer)) export.launch("mjwatch-config.json") else importError="Исправьте адреса перед экспортом"} }
        item { Action("Импорт конфигурации",tv) {importer.launch(arrayOf("application/json"))} }
        if(importError.isNotBlank()) item {Text(importError,color=MaterialTheme.colorScheme.error)}
        item { AsyncImage(model=coil.request.ImageRequest.Builder(LocalContext.current).data("file:///android_asset/tmdb.svg").decoderFactory(coil.decode.SvgDecoder.Factory()).build(),contentDescription="TMDB",modifier=Modifier.width(120.dp).height(32.dp)); HorizontalDivider(); Text("MJWatch 0.1.0 · Native Kotlin / Media3\nTMDB: необязательный каталог. This product uses the TMDB API but is not endorsed or certified by TMDB.\nТестовое видео: Android Emulator virtual scene, Apache 2.0\nНет внешней аналитики",color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    preview?.let { imported -> AlertDialog(onDismissRequest={preview=null},title={Text("Применить конфигурацию?")},text={Text("Jackett: ${imported.jackett}\nИндексаторы: ${imported.indexers}\nTorrServer: ${imported.torrServer}\nКлючи потребуется ввести заново. Сетевые запросы не выполнялись.")},confirmButton={TextButton(onClick={c=imported;key="";user="";password="";preview=null;model.invalidateChecks()}) {Text("Применить к форме")}},dismissButton={TextButton(onClick={preview=null}) {Text("Отмена")}}) }
}
@Composable private fun SecretField(value:String,onChange:(String)->Unit,label:String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value,onChange,label={Text(label)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),visualTransformation=if(visible) VisualTransformation.None else PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth(),trailingIcon={IconButton(onClick={visible=!visible}) {Icon(if(visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(visible) "Скрыть пароль" else "Показать пароль")}})
}
private fun time(ms:Long):String = "%d:%02d".format(ms/60000,ms/1000%60)
