@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.mjwatch.playback

import android.os.SystemClock
import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
) {
    val remote=remember {TvPlaybackState()}
    val rootFocus=remember {FocusRequester()}; val backFocus=remember {FocusRequester()}
    val playFocus=remember {FocusRequester()}; val timelineFocus=remember {FocusRequester()}
    val topTracksFocus=remember {FocusRequester()}; val tracksFocus=remember {FocusRequester()}; val videoFocus=remember {FocusRequester()}
    val episodesFocus=remember {FocusRequester()}; val promptFocus=remember {FocusRequester()}
    val preferences=LocalContext.current.getSharedPreferences("playback-preferences",0)
    var position by remember {mutableLongStateOf(0)}; var duration by remember {mutableLongStateOf(0)}
    var playing by remember {mutableStateOf(false)}; var ready by remember {mutableStateOf(false)}
    var seekable by remember {mutableStateOf(false)}
    var episodePanel by remember {mutableStateOf(false)}
    var focusedArea by remember {mutableStateOf("root")}
    var requestedFocus by remember {mutableStateOf("play")}
    var modalReturn by remember {mutableStateOf("tracks")}
    var centerHandled by remember {mutableStateOf(false)}
    var repeats by remember {mutableIntStateOf(0)}
    val modal=externalPanel||episodePanel
    val blockingStatus=status!=null&&!status.compact
    val blocked=modal||remote.preview!=null||status!=null
    val currentBlocked by rememberUpdatedState(blocked)
    val currentIndex by rememberUpdatedState(episodeIndex)
    val currentStatus by rememberUpdatedState(status)
    val currentVisible by rememberUpdatedState(visible)
    val currentModal by rememberUpdatedState(modal)
    val currentKeepVisible by rememberUpdatedState(keepVisible)
    fun interact() {remote.interact(now())}
    fun focusPlayback() {requestedFocus="play";onVisibility(true);interact();playFocus.requestFocus()}
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
    LaunchedEffect(episodeIndex) {remote.reset();interact();requestedFocus="play"}
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
    LaunchedEffect(visible,ready,requestedFocus,blockingStatus) {
        if(!modal&&!blockingStatus) {
            if(!visible) rootFocus.requestFocus()
            else if(requestedFocus=="back"||!ready) backFocus.requestFocus()
            else playFocus.requestFocus()
        }
    }
    var wasModal by remember {mutableStateOf(false)}
    LaunchedEffect(modal) {
        interact()
        val closing=wasModal&&!modal
        wasModal=modal
        if(closing&&visible&&status==null) when(modalReturn) {
            "topTracks" -> topTracksFocus.requestFocus()
            "episodes" -> episodesFocus.requestFocus()
            "video" -> videoFocus.requestFocus()
            else -> tracksFocus.requestFocus()
        }
    }
    val prompt=remote.prompt.takeIf {!blocked}
    LaunchedEffect(prompt) {
        if(prompt!=null) promptFocus.requestFocus()
        else if(!modal&&status==null&&focusedArea=="prompt") {
            if(visible&&ready) playFocus.requestFocus() else if(!visible) rootFocus.requestFocus()
        }
    }
    fun seek(direction:Int) {player()?.let {p -> p.seekTo((p.currentPosition+direction*10_000L).coerceIn(0,duration))}}
    CompositionLocalProvider(LocalContentColor provides Color(0xFFF0F2EC),LocalRippleConfiguration provides null) {
    MaterialTheme(colorScheme=darkColorScheme(primary=PlayerLime,onPrimary=Color(0xFF0C0E15),surface=Color(0xFF191E29),onSurface=Color(0xFFF0F2EC))) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent {event ->
            val code=event.nativeKeyEvent.keyCode
            val down=event.type==KeyEventType.KeyDown
            if(down) interact()
            if(code in listOf(AndroidKey.KEYCODE_DPAD_CENTER,AndroidKey.KEYCODE_ENTER,AndroidKey.KEYCODE_NUMPAD_ENTER)) {
                if(centerHandled) {if(!down)centerHandled=false;return@onPreviewKeyEvent true}
                if(down&&event.nativeKeyEvent.repeatCount>0)return@onPreviewKeyEvent true
                if(!visible&&prompt==null&&down) {
                    player()?.let {if(it.playWhenReady)it.pause() else it.play()}
                    requestedFocus="play";onVisibility(true);centerHandled=true;return@onPreviewKeyEvent true
                }
            }
            if(!visible&&code in listOf(AndroidKey.KEYCODE_DPAD_UP,AndroidKey.KEYCODE_DPAD_DOWN,AndroidKey.KEYCODE_DPAD_LEFT,AndroidKey.KEYCODE_DPAD_RIGHT)) {
                if(down) when(code) {
                    AndroidKey.KEYCODE_DPAD_UP -> {requestedFocus="back";onVisibility(true)}
                    AndroidKey.KEYCODE_DPAD_DOWN -> {requestedFocus="play";onVisibility(true)}
                    AndroidKey.KEYCODE_DPAD_LEFT -> seek(-1)
                    AndroidKey.KEYCODE_DPAD_RIGHT -> seek(1)
                }
                true
            } else false
        }.focusRequester(rootFocus).focusable()) {
            if(visible) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color(0xD9101722),Color.Transparent))))
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal=28.dp,vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(0.dp)) {
                    IconButton(modifier=Modifier.size(48.dp).testTag("player-back").onFocusChanged {if(it.isFocused)focusedArea="controls"}.focusRequester(backFocus).focusProperties {up=FocusRequester.Cancel;down=if(ready)playFocus else FocusRequester.Cancel}.tvPlayerFocus(!modal&&!blockingStatus),onClick=onBack) {Icon(Icons.Outlined.ArrowBack,"Назад")}
                    Column(Modifier.weight(1f)) {Text(title,fontSize=18.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(episodes.getOrNull(episodeIndex)?.path ?: "MJWatch",maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp,color=Color(0xFFB3C6DE))}
                    IconButton(modifier=Modifier.size(48.dp).focusRequester(topTracksFocus).focusProperties {up=FocusRequester.Cancel;down=if(ready)playFocus else backFocus}.tvPlayerFocus(!modal&&!blockingStatus),onClick={interact();modalReturn="topTracks";onTracks()}) {Icon(Icons.Outlined.Tune,"Дорожки")}
                }
                if(ready) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(224.dp).background(Brush.verticalGradient(listOf(Color.Transparent,Color(0x99101722),Color(0xF5101722)))))
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal=44.dp,vertical=34.dp),verticalArrangement=Arrangement.spacedBy(0.dp)) {
                    val shown=remote.preview ?: position
                    Slider(value=shown.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={remote.previewSeek(it.toLong(),duration,0,0,0);interact()},onValueChangeFinished={remote.confirmSeek()?.let {player()?.seekTo(it)};interact()},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=seekable&&duration>0,
                        modifier=Modifier.testTag("player-timeline").focusRequester(timelineFocus).onFocusChanged {if(it.isFocused)focusedArea="timeline";if(!it.hasFocus)remote.cancelSeek()}.onPreviewKeyEvent {event ->
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
                        }.tvPlayerFocus(!modal&&!blockingStatus),thumb={},track={
                            Box(Modifier.fillMaxWidth().height(7.dp).offset(y=8.dp).drawBehind {
                                val y=size.height/2
                                val x=size.width*(shown.toFloat()/duration.coerceAtLeast(1)).coerceIn(0f,1f)
                                drawLine(Color(0xFF78818A),Offset(0f,y),Offset(size.width,y),2.dp.toPx(),StrokeCap.Round)
                                drawLine(TvPlayerLime,Offset(0f,y),Offset(x,y),2.dp.toPx(),StrokeCap.Round)
                                drawCircle(TvPlayerLime,3.5.dp.toPx(),Offset(x,y))
                            })
                        })
                    Row(Modifier.fillMaxWidth().offset(y=(-8).dp),horizontalArrangement=Arrangement.SpaceBetween) {Text(playerTime(shown),fontSize=12.sp);Text(playerTime(duration),fontSize=12.sp,color=Color(0xFFF0F2EC))}
                    Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        @Composable fun buttonModifier()=Modifier.size(48.dp).onFocusChanged {if(it.isFocused)focusedArea="controls"}.focusProperties {up=if(seekable)timelineFocus else backFocus;down=FocusRequester.Cancel}.tvPlayerFocus(!modal&&!blockingStatus)
                        if(episodes.size>1) IconButton(enabled=episodeIndex>0,modifier=Modifier.testTag("player-previous-episode").focusProperties {left=FocusRequester.Cancel}.then(buttonModifier()),onClick={interact();onEpisode(episodeIndex-1)}) {Icon(Icons.Outlined.SkipPrevious,"Предыдущая серия")}
                        IconButton(modifier=Modifier.focusProperties {if(episodeIndex<=0)left=FocusRequester.Cancel}.then(buttonModifier()),enabled=seekable,onClick={interact();seek(-1)}) {Text("−10",Modifier.clearAndSetSemantics {contentDescription="Назад на 10 секунд"},fontSize=16.sp)}
                        IconButton(onClick={interact();player()?.let {if(it.playWhenReady)it.pause() else it.play()}},modifier=Modifier.size(width=52.dp,height=48.dp).testTag("player-play").focusRequester(playFocus).then(buttonModifier())) {Icon(if(playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(playing) "Пауза" else "Воспроизвести",Modifier.size(24.dp))}
                        IconButton(modifier=buttonModifier(),enabled=seekable,onClick={interact();seek(1)}) {Text("+10",Modifier.clearAndSetSemantics {contentDescription="Вперёд на 10 секунд"},fontSize=16.sp)}
                        if(episodes.size>1) IconButton(enabled=episodeIndex>=0&&episodeIndex<episodes.lastIndex,modifier=Modifier.testTag("player-next-episode").then(buttonModifier()),onClick={interact();onEpisode(episodeIndex+1)}) {Icon(Icons.Outlined.SkipNext,"Следующая серия")}
                        Spacer(Modifier.weight(1f))
                        if(episodes.isNotEmpty()) IconButton(modifier=Modifier.testTag("player-episodes").focusRequester(episodesFocus).then(buttonModifier()),onClick={interact();modalReturn="episodes";episodePanel=true}) {Icon(Icons.Outlined.List,"Выбор серии")}
                        IconButton(modifier=Modifier.testTag("player-tracks").focusRequester(tracksFocus).then(buttonModifier()),onClick={interact();modalReturn="tracks";onTracks()}) {Icon(Icons.Outlined.Subtitles,"Аудио и субтитры")}
                        IconButton(modifier=Modifier.testTag("player-video").focusRequester(videoFocus).focusProperties {right=FocusRequester.Cancel}.then(buttonModifier()),onClick={interact();modalReturn="video";onVideo()}) {Icon(Icons.Outlined.Fullscreen,"Параметры видео")}
                    }
                    }
                }
            }
            if(prompt!=null) Row(Modifier.align(Alignment.CenterEnd).padding(28.dp).background(Color(0xF0191E29),MaterialTheme.shapes.medium).padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Button(modifier=Modifier.testTag("player-prompt").onFocusChanged {if(it.isFocused)focusedArea="prompt"}.focusRequester(promptFocus).tvPlayerFocus(!modal&&!blockingStatus),onClick={
                    interact()
                    if(prompt=="opening") {episodes.getOrNull(episodeIndex)?.openingTarget(position,duration)?.let {player()?.seekTo(it)};dismissPrompt()}
                    else onEpisode(episodeIndex+1)
                }) {Text(if(prompt=="opening")"Пропустить опенинг" else if(preferences.getBoolean("auto-next",true))"Следующая серия · ${((10_000-remote.nextElapsed+999)/1000).coerceAtLeast(0)}" else "Следующая серия")}
                TextButton(modifier=Modifier.onFocusChanged {if(it.isFocused)focusedArea="prompt"}.tvPlayerFocus(!modal&&!blockingStatus),onClick={dismissPrompt()}) {Text(if(prompt=="next")"Остаться" else "Скрыть")}
            }
            if(status!=null) Box(Modifier.align(Alignment.Center)) {PlaybackStatusOverlay(status,onRetry,onBack,focusAction=true)}
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
