@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.mjwatch.playback

import dev.mjwatch.core.SkipSegment
import androidx.compose.animation.core.updateTransition

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.common.Player
import kotlinx.coroutines.delay

internal val PlayerMint=Color(0xFF31F5C8)
@Composable internal fun PlayerControls(player:()->Player?,title:String,visible:Boolean,mobile:Boolean,keepVisible:Boolean,status:PlaybackStatus?,onRetry:()->Unit,onVisibility:(Boolean)->Unit,onBack:()->Unit,onTracks:()->Unit,onPip:()->Unit,episodeCount:Int=0,episodeIndex:Int=-1,episodeLabel:String="",onEpisode:(Int)->Unit={},episodes:List<PlaybackEpisode> = emptyList(),externalPanel:Boolean=false,onTvBackHandler:((()->Unit)?)->Unit={},onVideo:()->Unit={},mediaInteraction:Int=0,beforeFirstFrame:Boolean=false,artwork:PlaybackArtwork?=null,metrics:PlaybackLoadingMetrics?=null,progress:StartupLoadingProgress?=null,skipSegments:List<SkipSegment> = emptyList(),advanceAfterCredits:(Boolean)->Boolean={false}) {
    val startup=status.takeIf {beforeFirstFrame}
    if(!mobile) {
        TvPlayerControls(player,title,visible,keepVisible,status,onRetry,onVisibility,onBack,onTracks,onVideo,episodes,episodeIndex,onEpisode,externalPanel,onTvBackHandler,mediaInteraction,startup=startup,artwork=artwork,metrics=metrics,progress=progress,skipSegments=skipSegments,advanceAfterCredits=advanceAfterCredits)
        return
    }
    val splash=updateTransition(startup,label="Playback splash")
    val splashBlocking=startup!=null||splash.currentState!=null
    var position by remember {mutableLongStateOf(0)};var duration by remember {mutableLongStateOf(0)};var playing by remember {mutableStateOf(false)};var ready by remember {mutableStateOf(false)}
    var interaction by remember {mutableIntStateOf(0)};var scrubbing by remember {mutableStateOf(false)}
    var seekForward by remember {mutableStateOf(true)}
    val seekPulse=remember {Animatable(0f)}
    var seekFeedback by remember {mutableStateOf("")}
    var seekInteraction by remember {mutableIntStateOf(0)}
    LaunchedEffect(seekInteraction) {if(seekFeedback.isNotEmpty()) {seekPulse.snapTo(0f);seekPulse.animateTo(1f,tween(650));delay(250);seekFeedback=""}}
    LaunchedEffect(Unit) {while(true) {player()?.let {p->if(!scrubbing) position=p.currentPosition;duration=p.duration.coerceAtLeast(0);playing=p.playWhenReady;ready=p.playbackState==Player.STATE_READY||p.duration>0};delay(250)}}
    val preferences=LocalContext.current.getSharedPreferences("playback-preferences",0)
    val skip=remember(episodeIndex) {SegmentSkipState()}
    val segments=remember(episodeIndex,episodes,skipSegments) {
        skipSegments.ifEmpty {episodes.getOrNull(episodeIndex)?.legacySegments().orEmpty()}
    }
    val skipBlocked=splashBlocking||externalPanel||status!=null||scrubbing
    val latestBlocked by rememberUpdatedState(skipBlocked)
    val latestAdvanceAfterCredits by rememberUpdatedState(advanceAfterCredits)
    var footerHeight by remember { mutableIntStateOf(0) }
    DisposableEffect(player(),skip) {
        val p=player()
        val listener=object:Player.Listener {
            override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int) {
                skip.discontinuity()
            }
        }
        p?.addListener(listener)
        onDispose {p?.removeListener(listener)}
    }
    LaunchedEffect(skip,segments) {
        if(segments.isEmpty()) {
            skip.reset()
            return@LaunchedEffect
        }
        while(true) {
            player()?.let {p->
                skip.update(segments,p.currentPosition,p.duration,p.isPlaying,latestBlocked,
                    preferences.autoSkip(),android.os.SystemClock.uptimeMillis(),preferences::segmentEnabled)
                if(skip.offer?.remainingMs==0L&&p.isPlaying)skip.seek(p) { latestAdvanceAfterCredits(true) }
            }
            delay(200)
        }
    }
    LaunchedEffect(visible,playing,interaction,keepVisible,externalPanel,status) {if(visible&&playing&&status==null&&!keepVisible&&!externalPanel) {delay(4000);onVisibility(false)}}
    fun touch(block:()->Unit) {interaction++;block()}
    MaterialTheme(colorScheme=darkColorScheme(primary=PlayerMint,onPrimary=Color(0xFF161622),surface=Color(0xFF222232),onSurface=Color(0xFFF4F4FA))) {
        CompositionLocalProvider(LocalContentColor provides Color(0xFFF4F4FA)) {Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().testTag("player-gestures").then(if(!visible&&!splashBlocking)Modifier.semantics {onClick(label="Показать элементы управления"){onVisibility(true);true}} else Modifier).pointerInput(visible,externalPanel,keepVisible,splashBlocking) {
                if(splashBlocking)return@pointerInput
                detectTapGestures(onTap={touch {onVisibility(keepVisible||!visible)}},onDoubleTap={offset->
                    val p=player()
                    if(p!=null&&p.isCurrentMediaItemSeekable&&p.duration>0&&!externalPanel) touch {
                        val forward=offset.x>=size.width/2
                        p.seekTo((p.currentPosition+if(forward)10_000 else -10_000).coerceIn(0,p.duration))
                        seekForward=forward;seekFeedback=if(forward) "+10 секунд" else "−10 секунд";seekInteraction++
                    }
                })
            })
            if(!splashBlocking) PlayerChrome(visible,Modifier.align(Alignment.TopCenter)) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(.24f).background(Brush.verticalGradient(0f to Color(0xAA000000),.45f to Color(0x44000000),1f to Color.Transparent)))
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).statusBarsPadding().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    IconButton(modifier=Modifier.size(48.dp).playerFocus(),onClick=onBack) {Icon(Icons.Outlined.ArrowBack,"Назад")}
                    Column(Modifier.weight(1f)) {Text(title,fontSize=18.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(episodeLabel.ifBlank {"MJWatch"},maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp,color=Color(0xFFB3C6DE))}
                }
            }
            if(!splashBlocking) PlayerChrome(visible&&ready,Modifier.align(Alignment.BottomCenter)) {
                Box(contentAlignment=Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(.38f).background(Brush.verticalGradient(0f to Color.Transparent,.55f to Color(0x44000000),1f to Color(0xAA000000))))
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).navigationBarsPadding()
                        .onSizeChanged { footerHeight = it.height }.padding(horizontal=28.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth().testTag("player-time-row"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(playerTime(position,duration),fontSize=12.sp,modifier=Modifier.widthIn(min=40.dp).testTag("player-position"))
                            Slider(
                                value=position.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()),
                                onValueChange={scrubbing=true;position=it.toLong();interaction++},
                                onValueChangeFinished={player()?.seekTo(position);scrubbing=false;interaction++},
                                valueRange=0f..duration.coerceAtLeast(1).toFloat(),
                                enabled=duration>0&&player()?.isCurrentMediaItemSeekable==true,
                                modifier=Modifier.weight(1f).height(48.dp).testTag("player-timeline")
                                    .describeSkipSegments(segments,duration).playerFocus(),
                                thumb={},
                                track={PlayerTimelineTrack(position,duration,segments,PlayerMint)},
                            )
                            Text(playerTime(duration,position),fontSize=12.sp,color=Color(0xFFB6BACD),modifier=Modifier.widthIn(min=40.dp).testTag("player-duration"))
                        }
                        FlowRow(Modifier.fillMaxWidth().testTag("player-controls-actions"),horizontalArrangement=Arrangement.SpaceBetween,verticalArrangement=Arrangement.spacedBy(8.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                            if(episodeCount>1) IconButton(enabled=episodeIndex>0,modifier=Modifier.size(48.dp).testTag("player-previous-episode").playerFocus(),onClick={touch {onEpisode(episodeIndex-1)}}) {Icon(Icons.Outlined.SkipPrevious,"Предыдущая серия")}
                            IconButton(modifier=Modifier.size(48.dp).playerFocus(),onClick={touch {player()?.seekBack()}}) {Icon(Icons.Outlined.Replay10,"Назад на 10 секунд")}
                            IconButton(onClick={touch {player()?.let {if(it.playWhenReady) it.pause() else it.play()}}},modifier=Modifier.size(56.dp).testTag("player-play").playerFocus()) {Icon(if(playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(playing) "Пауза" else "Воспроизвести",Modifier.size(24.dp))}
                            IconButton(modifier=Modifier.size(48.dp).playerFocus(),onClick={touch {player()?.seekForward()}}) {Icon(Icons.Outlined.Forward10,"Вперёд на 10 секунд")}
                            if(episodeCount>1) IconButton(enabled=episodeIndex>=0&&episodeIndex<episodeCount-1,modifier=Modifier.size(48.dp).testTag("player-next-episode").playerFocus(),onClick={touch {onEpisode(episodeIndex+1)}}) {Icon(Icons.Outlined.SkipNext,"Следующая серия")}
                            }
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                            IconButton(modifier=Modifier.size(48.dp).testTag("player-tracks").playerFocus(),onClick={touch(onTracks)}) {Icon(Icons.Outlined.Subtitles,"Аудио и субтитры")}
                            IconButton(modifier=Modifier.size(48.dp).playerFocus(),onClick={touch(onVideo)}) {Icon(Icons.Outlined.Settings,"Параметры видео")}
                            IconButton(modifier=Modifier.size(48.dp).playerFocus(),onClick={touch(onPip)}) {Icon(Icons.Outlined.PictureInPictureAlt,"Картинка в картинке")}
                            }
                        }
                    }
                }
            }
            if(seekFeedback.isNotEmpty()) Box(Modifier.align(if(seekForward)Alignment.CenterEnd else Alignment.CenterStart).fillMaxWidth(.45f).fillMaxHeight().testTag(if(seekForward)"seek-forward-feedback" else "seek-back-feedback"),contentAlignment=Alignment.Center) {
                Box(Modifier.size(170.dp).scale(.75f+seekPulse.value*.5f).alpha((1f-seekPulse.value)*.32f).background(Color.White,CircleShape))
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row {repeat(3) {index->Icon(if(seekForward)Icons.Outlined.PlayArrow else Icons.Outlined.ArrowLeft,null,Modifier.size(28.dp).alpha(.35f+.65f*(1f-kotlin.math.abs(seekPulse.value*3f-index).coerceIn(0f,1f))),tint=Color.White)}}
                    Text(seekFeedback,fontSize=18.sp,fontWeight=FontWeight.Bold,color=Color.White)
                }
            }
            skip.offer?.takeUnless {skipBlocked}?.let {offer->
                val bottom = if (visible && ready) with(LocalDensity.current) { footerHeight.toDp() } else 16.dp
                SkipOverlay(
                    offer,
                    Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .navigationBarsPadding().padding(start=20.dp, end=20.dp, bottom=bottom),
                    skipModifier=Modifier.testTag("player-skip"),
                    onSkip={touch {player()?.let { skip.seek(it) { advanceAfterCredits(false) } }}},
                    onCancel={touch(skip::dismiss)},
                )
            }
            PlaybackSplash(splash,title,artwork,tv=false,metrics=metrics,progress=progress,onRetry=onRetry,onCancel=onBack,onAudioRecovery=onTracks)
            if(status!=null&&!splashBlocking)Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=if(visible&&ready) 120.dp else 20.dp)){PlaybackStatusOverlay(status,onRetry,onBack,metrics=metrics,onAudioRecovery=onTracks)}

        }
    }
}
}

@Composable
internal fun PlayerChrome(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    // Let the TV return focus to the video as the controls fade out.
    val exiting = if (visible) Modifier else Modifier
        .focusProperties { canFocus = false }
        .clearAndSetSemantics { hideFromAccessibility() }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.then(exiting),
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(160)),
    ) { content() }
}

internal fun playerTime(ms: Long, timelineMs: Long = 0L): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return if (maxOf(ms, timelineMs) >= 3_600_000L) {
        "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

@Composable internal fun Modifier.playerFocus():Modifier {
    var focused by remember {mutableStateOf(false)}
    return this.onFocusChanged {focused=it.isFocused}.drawBehind {
        if(focused) {
            val inset=2.dp.toPx()
            val origin=androidx.compose.ui.geometry.Offset(inset,inset)
            val bounds=androidx.compose.ui.geometry.Size(size.width-2*inset,size.height-2*inset)
            val radius=androidx.compose.ui.geometry.CornerRadius(11.dp.toPx())
            drawRoundRect(Color(0x662A303A),origin,bounds,radius)
            drawRoundRect(Color(0xFFF4F4FA),origin,bounds,radius,style=Stroke(1.5.dp.toPx()))
        }
    }
}
