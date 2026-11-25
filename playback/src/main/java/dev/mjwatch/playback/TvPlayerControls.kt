@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.mjwatch.playback

import dev.mjwatch.core.SegmentKind
import dev.mjwatch.core.SkipSegment
import androidx.compose.animation.core.updateTransition

import android.os.SystemClock
import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
    startup:PlaybackStatus?=null, artwork:PlaybackArtwork?=null, metrics:PlaybackLoadingMetrics?=null, progress:StartupLoadingProgress?=null,
    skipSegments:List<SkipSegment> = emptyList(), advanceAfterCredits:(Boolean)->Boolean={false},
) {
    val statusRipple=LocalRippleConfiguration.current
    val windowFocused=LocalWindowInfo.current.isWindowFocused
    val inputMode=LocalInputModeManager.current
    val remote=remember {TvPlaybackState()}
    val rootFocus=remember {FocusRequester()}; val backFocus=remember {FocusRequester()}
    val playFocus=remember {FocusRequester()}; val timelineFocus=remember {FocusRequester()}
    val tracksFocus=remember {FocusRequester()}; val videoFocus=remember {FocusRequester()}
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
    var modalEpisodeIndex by remember {mutableIntStateOf(episodeIndex)}
    var restoreModalFocus by remember {mutableStateOf(false)}
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
    val blocked=splashBlocking||modal||remote.preview!=null||status!=null
    val currentBlocked by rememberUpdatedState(blocked)
    val currentIndex by rememberUpdatedState(episodeIndex)
    val currentStatus by rememberUpdatedState(status)
    val currentVisible by rememberUpdatedState(visible)
    val currentModal by rememberUpdatedState(modal)
    val currentKeepVisible by rememberUpdatedState(keepVisible)
    val currentEpisodes by rememberUpdatedState(episodes)
    val episodeSegments=remember(episodes,episodeIndex,skipSegments) {
        skipSegments.ifEmpty {episodes.getOrNull(episodeIndex)?.legacySegments().orEmpty()}
    }
    val currentSegments by rememberUpdatedState(episodeSegments)
    val markedTail by rememberUpdatedState(skipSegments.any {it.kind==SegmentKind.OUTRO||it.kind==SegmentKind.PREVIEW})
    val selectEpisode by rememberUpdatedState(onEpisode)
    val latestAdvanceAfterCredits by rememberUpdatedState(advanceAfterCredits)
    var footerHeight by remember { mutableIntStateOf(0) }
    fun interact() {remote.interact(now())}
    fun openPanel(target:FocusRequester,action:()->Unit) {
        modalReturn=target;modalNavigationVersion=navigationVersion;modalEpisodeIndex=episodeIndex;action()
    }
    fun focusPlayback() {requestedFocus=playFocus;onVisibility(true);interact()}
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
                playing=p.playWhenReady;ready=p.playbackState==Player.STATE_READY||p.duration>0
                seekable=p.isCurrentMediaItemSeekable
                val index=currentIndex
                val queue=currentEpisodes
                val episode=queue.getOrNull(index)
                if(remote.update(position,duration,p.isPlaying,episode,index>=0&&index<queue.lastIndex,currentBlocked,
                        preferences.getBoolean("auto-next",true),currentSegments,preferences.autoSkip(),
                        now(),markedTail,preferences::segmentEnabled)) selectEpisode(index+1)
                if(remote.skip.offer?.remainingMs==0L&&p.isPlaying) remote.skip.seek(p) { latestAdvanceAfterCredits(true) }

            }
            if(currentVisible&&!currentKeepVisible&&currentStatus==null&&remote.shouldHide(now(),currentModal)) onVisibility(false)
            delay(250)
        }
    }
    val prompt=remote.prompt.takeIf {!blocked&&(it=="next"||remote.skip.offer!=null)}
    var offeredPrompt by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(windowFocused) {if(windowFocused)inputMode.requestInputMode(InputMode.Keyboard)}
    LaunchedEffect(visible,ready,requestedFocus,blockingStatus,modal,windowFocused,inputMode.inputMode,startup?.loading) {
        if(modal) {
            restoreModalFocus=true
            return@LaunchedEffect
        }
        if(!windowFocused)return@LaunchedEffect
        if(startup?.loading==true)rootFocus.requestFocus()
        if(!blockingStatus) {
            // Projector firmware may start in touch mode even when controlled by a D-pad.
            inputMode.requestInputMode(InputMode.Keyboard)
            if(restoreModalFocus) {
                withFrameNanos {}
                restoreModalFocus=false
                if(visible&&ready&&currentIndex==modalEpisodeIndex&&navigationVersion==modalNavigationVersion) {
                    if(prompt==null||requestedFocus!=promptFocus)requestedFocus=modalReturn
                }
            }
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
    LaunchedEffect(modal) {interact()}
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
                if (prompt != null && prompt != "next" &&
                    (lastFocused == promptFocus || lastFocused == stayFocus) &&
                    (code == AndroidKey.KEYCODE_DPAD_LEFT || code == AndroidKey.KEYCODE_DPAD_RIGHT)
                ) return@onPreviewKeyEvent false
                if(down) when(code) {
                    AndroidKey.KEYCODE_DPAD_UP -> {requestedFocus=backFocus;onVisibility(true)}
                    AndroidKey.KEYCODE_DPAD_DOWN -> {requestedFocus=playFocus;onVisibility(true)}
                    AndroidKey.KEYCODE_DPAD_LEFT -> seek(-1)
                    AndroidKey.KEYCODE_DPAD_RIGHT -> seek(1)
                }
                true
            } else false
        }.then(controlFocus(rootFocus)).focusable()) {
            if(!splashBlocking) PlayerChrome(visible&&!blockingStatus,Modifier.align(Alignment.TopCenter)) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(.24f).background(Brush.verticalGradient(0f to Color(0xAA000000),.45f to Color(0x44000000),1f to Color.Transparent)))
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=28.dp,vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(0.dp)) {
                    IconButton(modifier=Modifier.size(48.dp).testTag("player-back").then(controlFocus(backFocus)).focusProperties {up=FocusRequester.Cancel;down=if(ready)playFocus else FocusRequester.Cancel}.tvPlayerFocus(!modal),onClick=onBack) {Icon(Icons.Outlined.ArrowBack,"Назад")}
                    Column(Modifier.weight(1f)) {Text(title,fontSize=18.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(episodes.getOrNull(episodeIndex)?.path ?: "MJWatch",maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp,color=Color(0xFFB3C6DE))}
                    if(ready) {
                        @Composable fun headerButton(target:FocusRequester)=Modifier.size(48.dp).then(controlFocus(target))
                            .focusProperties {down=playFocus}
                            .onPreviewKeyEvent {event->
                                if(event.nativeKeyEvent.keyCode!=AndroidKey.KEYCODE_DPAD_UP) false else {
                                    if(event.type==KeyEventType.KeyDown)onVisibility(false)
                                    true
                                }
                            }.tvPlayerFocus(!modal)
                        if(episodes.isNotEmpty()) IconButton(modifier=Modifier.testTag("player-episodes").then(headerButton(episodesFocus)),onClick={interact();openPanel(episodesFocus){episodePanel=true}}) {Icon(Icons.Outlined.List,"Выбор серии")}
                        IconButton(modifier=Modifier.testTag("player-tracks").then(headerButton(tracksFocus)),onClick={interact();openPanel(tracksFocus,onTracks)}) {Icon(Icons.Outlined.Subtitles,"Аудио и субтитры")}
                        IconButton(modifier=Modifier.testTag("player-video").focusProperties {right=FocusRequester.Cancel}.then(headerButton(videoFocus)),onClick={interact();openPanel(videoFocus,onVideo)}) {Icon(Icons.Outlined.Settings,"Параметры видео")}
                    }
                }
            }
            if(!splashBlocking) PlayerChrome(visible&&ready&&!blockingStatus,Modifier.align(Alignment.BottomCenter)) {
                Box(contentAlignment=Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(.38f).background(Brush.verticalGradient(0f to Color.Transparent,.55f to Color(0x44000000),1f to Color(0xAA000000))))
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()
                        .onSizeChanged { footerHeight = it.height }.padding(horizontal=44.dp,vertical=34.dp),verticalArrangement=Arrangement.spacedBy(0.dp)) {
                    val shown=remote.preview ?: position
                    Row(Modifier.fillMaxWidth().testTag("player-time-row"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(playerTime(shown),fontSize=12.sp,modifier=Modifier.widthIn(min=40.dp).testTag("player-position"))
                    Slider(value=shown.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={remote.previewSeek(it.toLong(),duration,0,0,0);interact()},onValueChangeFinished={remote.confirmSeek()?.let {player()?.seekTo(it)};interact()},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=seekable&&duration>0,
                        modifier=Modifier.weight(1f).height(48.dp).testTag("player-timeline").describeSkipSegments(episodeSegments,duration).then(controlFocus(timelineFocus)).onFocusChanged {timelineFocused=it.isFocused;if(!it.hasFocus)remote.cancelSeek()}.onPreviewKeyEvent {event ->
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
                            PlayerTimelineTrack(shown,duration,episodeSegments,TvPlayerLime,focused=timelineFocused&&!modal)
                        })
                        Text(playerTime(duration),fontSize=12.sp,color=Color(0xFFF0F2EC),modifier=Modifier.widthIn(min=40.dp).testTag("player-duration"))
                    }
                    Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        @Composable fun buttonModifier(target:FocusRequester=remember {FocusRequester()})=Modifier.size(48.dp).then(controlFocus(target)).focusProperties {up=if(seekable)timelineFocus else backFocus;down=FocusRequester.Cancel}.tvPlayerFocus(!modal)
                        if(episodes.size>1) IconButton(enabled=episodeIndex>0,modifier=Modifier.testTag("player-previous-episode").focusProperties {left=FocusRequester.Cancel}.then(buttonModifier()),onClick={interact();onEpisode(episodeIndex-1)}) {Icon(Icons.Outlined.SkipPrevious,"Предыдущая серия")}
                        IconButton(modifier=Modifier.focusProperties {if(episodeIndex<=0)left=FocusRequester.Cancel}.then(buttonModifier()),enabled=seekable,onClick={interact();seek(-1)}) {BasicText("−10",Modifier.clearAndSetSemantics {contentDescription="Назад на 10 секунд"},style=TextStyle(color=LocalContentColor.current,fontSize=16.sp),maxLines=1,autoSize=TextAutoSize.StepBased(minFontSize=10.sp,maxFontSize=16.sp))}
                        IconButton(onClick={interact();player()?.let {if(it.playWhenReady)it.pause() else it.play()}},modifier=Modifier.size(width=52.dp,height=48.dp).testTag("player-play").then(buttonModifier(playFocus))) {Icon(if(playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(playing) "Пауза" else "Воспроизвести",Modifier.size(24.dp))}
                        IconButton(modifier=buttonModifier(),enabled=seekable,onClick={interact();seek(1)}) {BasicText("+10",Modifier.clearAndSetSemantics {contentDescription="Вперёд на 10 секунд"},style=TextStyle(color=LocalContentColor.current,fontSize=16.sp),maxLines=1,autoSize=TextAutoSize.StepBased(minFontSize=10.sp,maxFontSize=16.sp))}
                        if(episodes.size>1) IconButton(enabled=episodeIndex>=0&&episodeIndex<episodes.lastIndex,modifier=Modifier.testTag("player-next-episode").then(buttonModifier()),onClick={interact();onEpisode(episodeIndex+1)}) {Icon(Icons.Outlined.SkipNext,"Следующая серия")}
                    }
                    }
                }
            }
            if (prompt == "next") {
                val actionInteraction = remember { MutableInteractionSource() }
                val dismissInteraction = remember { MutableInteractionSource() }
                Row(Modifier.align(Alignment.CenterEnd).padding(28.dp)
                    .background(Color(0xF0191E29),MaterialTheme.shapes.medium).padding(16.dp),
                    horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                    Button(modifier=Modifier.testTag("player-prompt").then(controlFocus(promptFocus)).heightIn(min=48.dp),
                        shape=CircleShape, border=tvPromptBorder(actionInteraction), interactionSource=actionInteraction, onClick={
                        interact()
                        onEpisode(episodeIndex+1)
                    }) {
                        val label=if(preferences.getBoolean("auto-next",true))"Следующая серия · ${((10_000-remote.nextElapsed+999)/1000).coerceAtLeast(0)}"
                            else "Следующая серия"
                        Text(label)
                    }
                    TextButton(modifier=controlFocus(stayFocus).heightIn(min=48.dp),
                        shape=CircleShape, border=tvPromptBorder(dismissInteraction),
                        interactionSource=dismissInteraction, onClick={dismissPrompt()}) {Text("Остаться")}
                }
            } else if (prompt != null) {
                remote.skip.offer?.let { offer ->
                    val bottom = if (visible && ready) with(LocalDensity.current) { footerHeight.toDp() } else 28.dp
                    SkipOverlay(
                        offer,
                        Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                            .padding(start=28.dp, end=28.dp, bottom=bottom),
                        skipModifier=Modifier.testTag("player-prompt").then(controlFocus(promptFocus))
                            .focusProperties { right=stayFocus; left=FocusRequester.Cancel },
                        cancelModifier=Modifier.testTag("player-skip-cancel").then(controlFocus(stayFocus))
                            .focusProperties { left=promptFocus; right=FocusRequester.Cancel },
                        tv=true,
                        onSkip={
                            interact()
                            player()?.let { remote.skip.seek(it) { advanceAfterCredits(false) } }
                            focusPlayback()
                        },
                        onCancel={dismissPrompt()},
                    )
                }
            }
            PlaybackSplash(splash,title,artwork,tv=true,metrics=metrics,progress=progress,onRetry=onRetry,onCancel=onBack,onAudioRecovery=onTracks)
            if(status!=null&&!splashBlocking) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=if(visible&&ready&&!blockingStatus) 110.dp else 20.dp)) {
                CompositionLocalProvider(LocalRippleConfiguration provides statusRipple) {
                    PlaybackStatusOverlay(status,onRetry,onBack,focusAction=true,metrics=metrics,onAudioRecovery=onTracks)
                }
            }
            if(episodePanel&&episodes.isNotEmpty()) AlertDialog(onDismissRequest={episodePanel=false},title={Text("Выбор серии")},text={
                val initialIndex=remember {episodeIndex.coerceIn(0,episodes.lastIndex)}
                val listState=rememberLazyListState(initialFirstVisibleItemIndex=initialIndex)
                val selectedFocus=remember {FocusRequester()}
                LaunchedEffect(Unit) {withFrameNanos {};selectedFocus.requestFocus()}
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=320.dp).testTag("player-episode-list"),state=listState) {
                    itemsIndexed(episodes) {index,episode ->
                        TextButton(modifier=(if(index==initialIndex)Modifier.focusRequester(selectedFocus) else Modifier).fillMaxWidth().tvPlayerFocus(true),
                            onClick={episodePanel=false;interact();onEpisode(index)}) {
                            val path=episode.path.replace('\\','/')
                            Column(Modifier.fillMaxWidth()) {
                                Text("${if(index==episodeIndex)"▶ " else ""}${path.substringAfterLast('/')}",maxLines=2,overflow=TextOverflow.Ellipsis)
                                val directory=path.substringBeforeLast('/',"")
                                if(directory.isNotEmpty()) Text(directory,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },confirmButton={TextButton(modifier=Modifier.tvPlayerFocus(true),onClick={episodePanel=false}) {Text("Закрыть")}})
        }
    }
}
}

private val TvPlayerLime=Color(0xFFB4F45B)

/** Let the button draw its state border on the same shape as its surface. */
@Composable internal fun tvPromptBorder(interaction:MutableInteractionSource, icon:Boolean=false):BorderStroke {
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val color = when {
        focused && icon -> TvPlayerLime
        focused -> Color(0xFFF0F2EC)
        hovered -> Color(0x99F0F2EC)
        else -> Color.Transparent
    }
    return BorderStroke(if(icon) 1.dp else 1.5.dp, color)
}

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
