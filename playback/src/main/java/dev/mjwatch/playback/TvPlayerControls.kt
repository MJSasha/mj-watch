@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.mjwatch.playback

import androidx.compose.animation.core.updateTransition
import dev.mjwatch.core.TorrentPlaybackStats

import android.os.SystemClock
import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.media3.common.Player
import kotlinx.coroutines.delay

@Composable internal fun TvPlayerControls(
    player:()->Player?, title:String, visible:Boolean, keepVisible:Boolean, status:PlaybackStatus?,
    onRetry:()->Unit, onVisibility:(Boolean)->Unit, onBack:()->Unit, onTracks:()->Unit, onVideo:()->Unit,
    episodes:List<PlaybackEpisode>, episodeIndex:Int, onEpisode:(Int)->Unit, externalPanel:Boolean,
    onBackHandler:((()->Unit)?)->Unit, mediaInteraction:Int=0, now:()->Long={SystemClock.uptimeMillis()},
    startup:PlaybackStatus?=null, artwork:PlaybackArtwork?=null, stats:TorrentPlaybackStats?=null, progress:Float?=null,
) {
    val statusRipple=LocalRippleConfiguration.current
    val windowFocused=LocalWindowInfo.current.isWindowFocused
    val inputMode=LocalInputModeManager.current
    val remote=remember {TvPlaybackState()}
    val rootFocus=remember {FocusRequester()}; val backFocus=remember {FocusRequester()}
    val playFocus=remember {FocusRequester()}; val timelineFocus=remember {FocusRequester()}
    val topTracksFocus=remember {FocusRequester()}; val tracksFocus=remember {FocusRequester()}; val videoFocus=remember {FocusRequester()}
    val episodesFocus=remember {FocusRequester()}; val promptFocus=remember {FocusRequester()}; val stayFocus=remember {FocusRequester()}
    val preferences=LocalContext.current.getSharedPreferences("playback-preferences",0)
    var position by remember {mutableLongStateOf(0)}; var duration by remember {mutableLongStateOf(0)}
    var playing by remember {mutableStateOf(false)}; var ready by remember {mutableStateOf(false)}
    var seekable by remember {mutableStateOf(false)}
    var episodePanel by remember {mutableStateOf(false)}
    var requestedFocus by remember {mutableStateOf(playFocus)}
    var modalReturn by remember {mutableStateOf(tracksFocus)}
    var lastFocused by remember {mutableStateOf(rootFocus)}
    var navigating by remember {mutableStateOf(false)}
    var navigationVersion by remember {mutableIntStateOf(0)}
    var modalNavigationVersion by remember {mutableIntStateOf(0)}
    @Composable fun controlFocus(target:FocusRequester=remember {FocusRequester()})=Modifier.focusRequester(target).onFocusChanged {
        if(it.isFocused) {
            lastFocused=target
            if(navigating) {requestedFocus=target;navigating=false}
        }
    }
    var centerHandled by remember {mutableStateOf(false)}
    var repeats by remember {mutableIntStateOf(0)}
    var timelineFocused by remember {mutableStateOf(false)}
    val modal=externalPanel||episodePanel
    val splash=updateTransition(startup,label="Playback splash")
    val splashBlocking=startup!=null||splash.currentState!=null
    val blockingStatus=splashBlocking||status!=null&&!status.compact
    val blocked=modal||remote.preview!=null||status!=null
    val currentBlocked by rememberUpdatedState(blocked)
    val currentIndex by rememberUpdatedState(episodeIndex)
    val currentStatus by rememberUpdatedState(status)
    val currentVisible by rememberUpdatedState(visible)
    val currentModal by rememberUpdatedState(modal)
    val currentKeepVisible by rememberUpdatedState(keepVisible)
    fun interact() {remote.interact(now())}
    fun openPanel(target:FocusRequester,action:()->Unit) {modalReturn=target;modalNavigationVersion=navigationVersion;action()}
    fun focusPlayback() {requestedFocus=playFocus;onVisibility(true);interact();playFocus.requestFocus()}
    fun dismissPrompt() {remote.dismissPrompt();focusPlayback()}
    fun back() {
        interact()
        when {
            episodePanel -> episodePanel=false
            remote.preview!=null -> remote.cancelSeek()
            remote.prompt!=null && status==null -> dismissPrompt()
            else -> onBack()
        }
    }
    SideEffect {onBackHandler {back()}}
    DisposableEffect(Unit) {onDispose {onBackHandler(null)}}
    LaunchedEffect(mediaInteraction) {interact()}
    LaunchedEffect(episodeIndex) {remote.reset();interact();requestedFocus=playFocus}
    DisposableEffect(player()) {
        val p=player()
        val listener=object:Player.Listener {
            override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int) {remote.discontinuity()}
        }
        p?.addListener(listener)
        onDispose {p?.removeListener(listener)}
    }
    LaunchedEffect(Unit) {
        while(true) {
            player()?.let {p ->
                position=p.currentPosition.coerceAtLeast(0);duration=p.duration.coerceAtLeast(0)
                playing=p.isPlaying;ready=p.playbackState==Player.STATE_READY||p.duration>0
                seekable=p.isCurrentMediaItemSeekable
                val index=currentIndex
                val episode=episodes.getOrNull(index)
                val autoSkip=preferences.getBoolean("auto-skip",false)
                if(autoSkip&&!currentBlocked) {
                    val target=episode?.openingTarget(position,duration)
                        ?: if(index==episodes.lastIndex) markerTarget(episode?.endingStartMs,episode?.endingEndMs,position,duration) else null
                    if(target!=null) {p.seekTo(target);position=target}
                }
                if(remote.update(position,duration,playing,episode,index>=0&&index<episodes.lastIndex,currentBlocked,
                        preferences.getBoolean("auto-next",true))) onEpisode(index+1)
            }
            if(currentVisible&&!currentKeepVisible&&currentStatus==null&&remote.shouldHide(now(),currentModal)) onVisibility(false)
            delay(250)
        }
    }
    val prompt=remote.prompt.takeIf {!blocked}
    var offeredPrompt by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(windowFocused) {if(windowFocused)inputMode.requestInputMode(InputMode.Keyboard)}
    LaunchedEffect(visible,ready,requestedFocus,blockingStatus,modal,windowFocused,inputMode.inputMode,startup?.loading) {
        if(!modal&&windowFocused&&startup?.loading==true)rootFocus.requestFocus()
        if(!modal&&!blockingStatus&&windowFocused) {
            // Projector firmware may start in touch mode even when controlled by a D-pad.
            inputMode.requestInputMode(InputMode.Keyboard)
            val target=when {
                requestedFocus==promptFocus||requestedFocus==stayFocus -> requestedFocus.takeIf {prompt!=null}
                !visible -> rootFocus
                !ready -> backFocus
                requestedFocus==rootFocus -> playFocus
                else -> requestedFocus
            }
            target?.requestFocus()
        }
    }
    var wasModal by remember {mutableStateOf(false)}
    LaunchedEffect(modal) {
        interact()
        val closing=wasModal&&!modal
        wasModal=modal
        if(closing&&visible&&status==null&&navigationVersion==modalNavigationVersion) requestedFocus=if(prompt!=null&&prompt!=offeredPrompt)promptFocus else modalReturn
    }
    LaunchedEffect(prompt,remote.prompt) {
        if(remote.prompt==null)offeredPrompt=null
        if(prompt!=null&&prompt!=offeredPrompt) {
            offeredPrompt=prompt;requestedFocus=promptFocus;promptFocus.requestFocus()
        }
        else if(prompt==null&&(requestedFocus==promptFocus||requestedFocus==stayFocus)&&!modal&&status==null&&remote.preview==null) {
            requestedFocus=if(!visible)rootFocus else if(ready)playFocus else backFocus
        }
    }
    fun seek(direction:Int) {player()?.let {p -> p.seekTo((p.currentPosition+direction*10_000L).coerceIn(0,duration))}}
    CompositionLocalProvider(LocalContentColor provides Color(0xFFF0F2EC),LocalRippleConfiguration provides null) {
    MaterialTheme(colorScheme=darkColorScheme(primary=PlayerLime,onPrimary=Color(0xFF0C0E15),surface=Color(0xFF191E29),onSurface=Color(0xFFF0F2EC))) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent {event ->
            val code=event.nativeKeyEvent.keyCode
            val down=event.type==KeyEventType.KeyDown
            if(startup?.loading==true && code in listOf(AndroidKey.KEYCODE_DPAD_CENTER,AndroidKey.KEYCODE_ENTER,AndroidKey.KEYCODE_NUMPAD_ENTER,
                    AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN,AndroidKey.KEYCODE_DPAD_LEFT,AndroidKey.KEYCODE_DPAD_RIGHT)) return@onPreviewKeyEvent true

            if(down) {interact();requestedFocus=lastFocused}
            if(code in listOf(AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN,AndroidKey.KEYCODE_DPAD_LEFT,AndroidKey.KEYCODE_DPAD_RIGHT)) {
                navigating=down
                if(down)navigationVersion++
            }
            if(code in listOf(AndroidKey.KEYCODE_DPAD_CENTER,AndroidKey.KEYCODE_ENTER,AndroidKey.KEYCODE_NUMPAD_ENTER)) {
                if(centerHandled) {if(!down)centerHandled=false;return@onPreviewKeyEvent true}
                if(down&&event.nativeKeyEvent.repeatCount>0)return@onPreviewKeyEvent true
                if(!splashBlocking&&!visible&&prompt==null&&down) {
                    player()?.let {if(it.playWhenReady)it.pause() else it.play()}
                    requestedFocus=playFocus;onVisibility(true);centerHandled=true;return@onPreviewKeyEvent true
                }
            }
            if(!splashBlocking&&!visible&&code in listOf(AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN,AndroidKey.KEYCODE_DPAD_LEFT,AndroidKey.KEYCODE_DPAD_RIGHT)) {
                if(down) when(code) {
                    AndroidKey.KEYCODE_DPAD_UP -> {requestedFocus=backFocus;onVisibility(true)}
                    AndroidKey.KEYCODE_DPAD_DOWN -> {requestedFocus=playFocus;onVisibility(true)}
                    AndroidKey.KEYCODE_DPAD_LEFT -> seek(-1)
                    AndroidKey.KEYCODE_DPAD_RIGHT -> seek(1)
                }
                true
            } else false
        }.then(controlFocus(rootFocus)).focusable()) {
            if(!splashBlocking) PlayerChrome(visible,Modifier.align(Alignment.TopCenter)) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(.24f).background(Brush.verticalGradient(0f to Color(0xAA000000),.45f to Color(0x44000000),1f to Color.Transparent)))
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=28.dp,vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(0.dp)) {
                    IconButton(modifier=Modifier.size(48.dp).testTag("player-back").then(controlFocus(backFocus)).focusProperties {up=FocusRequester.Cancel;down=if(ready)playFocus else FocusRequester.Cancel}.tvPlayerFocus(!modal),onClick=onBack) {Icon(Icons.Outlined.ArrowBack,"Назад")}
                    Column(Modifier.weight(1f)) {Text(title,fontSize=18.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(episodes.getOrNull(episodeIndex)?.path ?: "MJWatch",maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp,color=Color(0xFFB3C6DE))}
                    IconButton(modifier=Modifier.size(48.dp).then(controlFocus(topTracksFocus)).focusProperties {up=FocusRequester.Cancel;down=if(ready)playFocus else backFocus}.tvPlayerFocus(!modal),onClick={interact();openPanel(topTracksFocus,onTracks)}) {Icon(Icons.Outlined.Tune,"Дорожки")}
                }
            }
            if(!splashBlocking) PlayerChrome(visible&&ready,Modifier.align(Alignment.BottomCenter)) {
                Box(contentAlignment=Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(.38f).background(Brush.verticalGradient(0f to Color.Transparent,.55f to Color(0x44000000),1f to Color(0xAA000000))))
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=44.dp,vertical=34.dp),verticalArrangement=Arrangement.spacedBy(0.dp)) {
                    val shown=remote.preview ?: position
                    Row(Modifier.fillMaxWidth().testTag("player-time-row"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(playerTime(shown),fontSize=12.sp,modifier=Modifier.widthIn(min=40.dp).testTag("player-position"))
                    Slider(value=shown.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={remote.previewSeek(it.toLong(),duration,0,0,0);interact()},onValueChangeFinished={remote.confirmSeek()?.let {player()?.seekTo(it)};interact()},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=seekable&&duration>0,
                        modifier=Modifier.weight(1f).height(48.dp).testTag("player-timeline").then(controlFocus(timelineFocus)).onFocusChanged {timelineFocused=it.isFocused;if(!it.hasFocus)remote.cancelSeek()}.onPreviewKeyEvent {event ->
                            val down=event.type==KeyEventType.KeyDown
                            when(event.nativeKeyEvent.keyCode) {
                                AndroidKey.KEYCODE_DPAD_LEFT,AndroidKey.KEYCODE_DPAD_RIGHT -> {
                                    if(down) {repeats=event.nativeKeyEvent.repeatCount;remote.previewSeek(position,duration,if(event.nativeKeyEvent.keyCode==AndroidKey.KEYCODE_DPAD_LEFT)-1 else 1,repeats,10_000);interact()} else repeats=0
                                    true
                                }
                                AndroidKey.KEYCODE_DPAD_CENTER,AndroidKey.KEYCODE_ENTER,AndroidKey.KEYCODE_NUMPAD_ENTER -> {if(down&&event.nativeKeyEvent.repeatCount==0)remote.confirmSeek()?.let {player()?.seekTo(it)};true}
                                AndroidKey.KEYCODE_DPAD_UP -> {if(down) {remote.cancelSeek();onVisibility(false)};true}
                                AndroidKey.KEYCODE_DPAD_DOWN -> {if(down) {remote.cancelSeek();playFocus.requestFocus()};true}
                                else -> false
                            }
                        },thumb={},track={
                            Box(Modifier.fillMaxWidth().height(7.dp).drawBehind {
                                val y=size.height/2
                                val x=size.width*(shown.toFloat()/duration.coerceAtLeast(1)).coerceIn(0f,1f)
                                val focused=timelineFocused&&!modal
                                val thickness=(if(focused)3.dp else 2.dp).toPx()
                                drawLine(Color(0xFF78818A),Offset(0f,y),Offset(size.width,y),thickness,StrokeCap.Round)
                                drawLine(TvPlayerLime,Offset(0f,y),Offset(x,y),thickness,StrokeCap.Round)
                                if(focused) {
                                    drawCircle(Color(0xFF101722),7.5.dp.toPx(),Offset(x,y))
                                    drawCircle(Color(0xFFF0F2EC),7.5.dp.toPx(),Offset(x,y),style=Stroke(1.5.dp.toPx()))
                                }
                                drawCircle(TvPlayerLime,(if(focused)4.5.dp else 3.5.dp).toPx(),Offset(x,y))
                            })
                        })
                        Text(playerTime(duration),fontSize=12.sp,color=Color(0xFFF0F2EC),modifier=Modifier.widthIn(min=40.dp).testTag("player-duration"))
                    }
                    Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        @Composable fun buttonModifier(target:FocusRequester=remember {FocusRequester()})=Modifier.size(48.dp).then(controlFocus(target)).focusProperties {up=if(seekable)timelineFocus else backFocus;down=FocusRequester.Cancel}.tvPlayerFocus(!modal)
                        if(episodes.size>1) IconButton(enabled=episodeIndex>0,modifier=Modifier.testTag("player-previous-episode").focusProperties {left=FocusRequester.Cancel}.then(buttonModifier()),onClick={interact();onEpisode(episodeIndex-1)}) {Icon(Icons.Outlined.SkipPrevious,"Предыдущая серия")}
                        IconButton(modifier=Modifier.focusProperties {if(episodeIndex<=0)left=FocusRequester.Cancel}.then(buttonModifier()),enabled=seekable,onClick={interact();seek(-1)}) {BasicText("−10",Modifier.clearAndSetSemantics {contentDescription="Назад на 10 секунд"},style=TextStyle(color=LocalContentColor.current,fontSize=16.sp),maxLines=1,autoSize=TextAutoSize.StepBased(minFontSize=10.sp,maxFontSize=16.sp))}
                        IconButton(onClick={interact();player()?.let {if(it.playWhenReady)it.pause() else it.play()}},modifier=Modifier.size(width=52.dp,height=48.dp).testTag("player-play").then(buttonModifier(playFocus))) {Icon(if(playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(playing) "Пауза" else "Воспроизвести",Modifier.size(24.dp))}
                        IconButton(modifier=buttonModifier(),enabled=seekable,onClick={interact();seek(1)}) {BasicText("+10",Modifier.clearAndSetSemantics {contentDescription="Вперёд на 10 секунд"},style=TextStyle(color=LocalContentColor.current,fontSize=16.sp),maxLines=1,autoSize=TextAutoSize.StepBased(minFontSize=10.sp,maxFontSize=16.sp))}
                        if(episodes.size>1) IconButton(enabled=episodeIndex>=0&&episodeIndex<episodes.lastIndex,modifier=Modifier.testTag("player-next-episode").then(buttonModifier()),onClick={interact();onEpisode(episodeIndex+1)}) {Icon(Icons.Outlined.SkipNext,"Следующая серия")}
                        Spacer(Modifier.weight(1f))
                        if(episodes.isNotEmpty()) IconButton(modifier=Modifier.testTag("player-episodes").then(buttonModifier(episodesFocus)),onClick={interact();openPanel(episodesFocus){episodePanel=true}}) {Icon(Icons.Outlined.List,"Выбор серии")}
                        IconButton(modifier=Modifier.testTag("player-tracks").then(buttonModifier(tracksFocus)),onClick={interact();openPanel(tracksFocus,onTracks)}) {Icon(Icons.Outlined.Subtitles,"Аудио и субтитры")}
                        IconButton(modifier=Modifier.testTag("player-video").focusProperties {right=FocusRequester.Cancel}.then(buttonModifier(videoFocus)),onClick={interact();openPanel(videoFocus,onVideo)}) {Icon(Icons.Outlined.Fullscreen,"Параметры видео")}
                    }
                    }
                }
            }
            if(prompt!=null) Row(Modifier.align(Alignment.CenterEnd).padding(28.dp).background(Color(0xF0191E29),MaterialTheme.shapes.medium).padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Button(modifier=Modifier.testTag("player-prompt").then(controlFocus(promptFocus)).tvPlayerFocus(!modal),onClick={
                    interact()
                    if(prompt=="opening") {episodes.getOrNull(episodeIndex)?.openingTarget(position,duration)?.let {player()?.seekTo(it)};dismissPrompt()}
                    else onEpisode(episodeIndex+1)
                }) {Text(if(prompt=="opening")"Пропустить опенинг" else if(preferences.getBoolean("auto-next",true))"Следующая серия · ${((10_000-remote.nextElapsed+999)/1000).coerceAtLeast(0)}" else "Следующая серия")}
                TextButton(modifier=Modifier.then(controlFocus(stayFocus)).tvPlayerFocus(!modal),onClick={dismissPrompt()}) {Text(if(prompt=="next")"Остаться" else "Скрыть")}
            }
            PlaybackSplash(splash,title,artwork,tv=true,stats=stats,progress=progress,onRetry=onRetry,onCancel=onBack)
            if(status!=null&&!splashBlocking) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=if(visible&&ready) 110.dp else 20.dp)) {
                CompositionLocalProvider(LocalRippleConfiguration provides statusRipple) {
                    PlaybackStatusOverlay(status,onRetry,onBack,focusAction=true,stats=stats)
                }
            }
            if(episodePanel) AlertDialog(onDismissRequest={episodePanel=false},title={Text("Выбор серии")},text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState())) {
                val selectedFocus=remember {FocusRequester()}
                LaunchedEffect(Unit) {selectedFocus.requestFocus()}
                episodes.forEachIndexed {index,episode ->
                    TextButton(modifier=(if(index==episodeIndex)Modifier.focusRequester(selectedFocus) else Modifier).fillMaxWidth().tvPlayerFocus(true),onClick={episodePanel=false;interact();onEpisode(index)}) {Text("${if(index==episodeIndex)"▶ " else ""}${episode.path}")}
                }
            }},confirmButton={TextButton(modifier=Modifier.tvPlayerFocus(true),onClick={episodePanel=false}) {Text("Закрыть")}})
        }
    }
}
}

private val TvPlayerLime=Color(0xFFB4F45B)

/** Draw inside the 48 dp target so the single focus outline cannot be clipped. */
@Composable private fun Modifier.tvPlayerFocus(showFocus:Boolean):Modifier {
    var focused by remember {mutableStateOf(false)}
    return this.onFocusChanged {focused=it.isFocused}.drawBehind {
        if(focused&&showFocus) {
            val inset=2.dp.toPx()
            val origin=Offset(inset,inset)
            val bounds=Size(size.width-2*inset,size.height-2*inset)
            val radius=CornerRadius(11.dp.toPx())
            drawRoundRect(Color(0x662A303A),origin,bounds,radius)
            drawRoundRect(Color(0xFFF0F2EC),origin,bounds,radius,style=Stroke(1.5.dp.toPx()))
        }
    }
}
