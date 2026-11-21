@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.mjwatch.playback

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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.common.Player
import kotlinx.coroutines.delay

internal val PlayerLime=Color(0xFFC7FF68)
@Composable internal fun PlayerControls(player:()->Player?,title:String,visible:Boolean,mobile:Boolean,keepVisible:Boolean,status:PlaybackStatus?,onRetry:()->Unit,onVisibility:(Boolean)->Unit,onBack:()->Unit,onTracks:()->Unit,onFit:()->Unit,onPip:()->Unit,episodeCount:Int=0,episodeIndex:Int=-1,episodeLabel:String="",onEpisode:(Int)->Unit={},episodes:List<PlaybackEpisode> = emptyList(),externalPanel:Boolean=false,onTvBackHandler:((()->Unit)?)->Unit={},onVideo:()->Unit={},mediaInteraction:Int=0) {
    if(!mobile) {
        TvPlayerControls(player,title,visible,keepVisible,status,onRetry,onVisibility,onBack,onTracks,onVideo,episodes,episodeIndex,onEpisode,externalPanel,onTvBackHandler,mediaInteraction)
        return
    }
    val playFocus=remember {FocusRequester()};val timelineFocus=remember {FocusRequester()};val tracksFocus=remember {FocusRequester()}
    var position by remember {mutableLongStateOf(0)};var duration by remember {mutableLongStateOf(0)};var playing by remember {mutableStateOf(false)};var ready by remember {mutableStateOf(false)}
    val windowFocused=LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(visible,ready,windowFocused) {if(visible&&ready&&windowFocused&&!mobile) {delay(100);playFocus.requestFocus()}}
    var interaction by remember {mutableIntStateOf(0)};var scrubbing by remember {mutableStateOf(false)}
    var seekForward by remember {mutableStateOf(true)}
    val seekPulse=remember {Animatable(0f)}
    var seekFeedback by remember {mutableStateOf("")}
    var seekInteraction by remember {mutableIntStateOf(0)}
    LaunchedEffect(seekInteraction) {if(seekFeedback.isNotEmpty()) {seekPulse.snapTo(0f);seekPulse.animateTo(1f,tween(650));delay(250);seekFeedback=""}}
    LaunchedEffect(Unit) {while(true) {player()?.let {p->if(!scrubbing) position=p.currentPosition;duration=p.duration.coerceAtLeast(0);playing=p.isPlaying;ready=p.playbackState==Player.STATE_READY||p.duration>0};delay(250)}}
    LaunchedEffect(visible,playing,interaction,keepVisible) {if(visible&&playing&&!keepVisible) {delay(4000);onVisibility(false)}}
    fun touch(block:()->Unit) {interaction++;block()}
    MaterialTheme(colorScheme=darkColorScheme(primary=PlayerLime,onPrimary=Color(0xFF0C0E15),surface=Color(0xFF191E29),onSurface=Color(0xFFF0F2EC))) {
        CompositionLocalProvider(LocalContentColor provides Color(0xFFF0F2EC)) {Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().testTag("player-gestures").then(if(!visible)Modifier.semantics {onClick(label="Показать элементы управления"){onVisibility(true);true}} else Modifier).pointerInput(visible,externalPanel,keepVisible) {
                detectTapGestures(onTap={touch {onVisibility(keepVisible||!visible)}},onDoubleTap={offset->
                    val p=player()
                    if(p!=null&&p.isCurrentMediaItemSeekable&&p.duration>0&&!externalPanel) touch {
                        val forward=offset.x>=size.width/2
                        p.seekTo((p.currentPosition+if(forward)10_000 else -10_000).coerceIn(0,p.duration))
                        seekForward=forward;seekFeedback=if(forward) "+10 секунд" else "−10 секунд";seekInteraction++
                    }
                })
            })
            PlayerChrome(visible,Modifier.align(Alignment.TopCenter)) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(.24f).background(Brush.verticalGradient(0f to Color(0xAA000000),.45f to Color(0x44000000),1f to Color.Transparent)))
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).statusBarsPadding().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    IconButton(modifier=Modifier.size(48.dp).focusProperties {if(!mobile)down=timelineFocus}.playerFocus(),onClick=onBack) {Icon(Icons.Outlined.ArrowBack,"Назад")}
                    Column(Modifier.weight(1f)) {Text(title,fontSize=18.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(episodeLabel.ifBlank {"MJWatch"},maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp,color=Color(0xFFB3C6DE))}
                    IconButton(modifier=Modifier.size(48.dp).testTag("player-tracks").focusRequester(tracksFocus).focusProperties {if(!mobile)down=timelineFocus}.playerFocus(),onClick={touch(onTracks)}) {Icon(Icons.Outlined.Tune,"Дорожки")}
                }
            }
            PlayerChrome(visible&&ready,Modifier.align(Alignment.BottomCenter)) {
                Box(contentAlignment=Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(.38f).background(Brush.verticalGradient(0f to Color.Transparent,.55f to Color(0x44000000),1f to Color(0xAA000000))))
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).navigationBarsPadding().padding(horizontal=28.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth().testTag("player-time-row"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(playerTime(position),fontSize=12.sp,modifier=Modifier.widthIn(min=40.dp).testTag("player-position"))
                        Slider(value=position.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={scrubbing=true;position=it.toLong();interaction++},onValueChangeFinished={player()?.seekTo(position);scrubbing=false;interaction++},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=duration>0&&player()?.isCurrentMediaItemSeekable==true,modifier=Modifier.weight(1f).height(48.dp).testTag("player-timeline").focusRequester(timelineFocus).focusProperties {if(!mobile){up=tracksFocus;down=playFocus}}.onPreviewKeyEvent {event->
                            if(mobile||event.key !in listOf(Key.DirectionUp,Key.DirectionDown,Key.DirectionLeft,Key.DirectionRight))false else {
                                if(event.type==KeyEventType.KeyDown)touch {
                                    when(event.key) {
                                        Key.DirectionUp->tracksFocus.requestFocus()
                                        Key.DirectionDown->playFocus.requestFocus()
                                        Key.DirectionLeft->player()?.seekTo((position-10_000).coerceAtLeast(0))
                                        Key.DirectionRight->player()?.seekTo((position+10_000).coerceAtMost(duration))
                                    }
                                }
                                true
                            }
                        }.playerFocus(),thumb={},track={Box(Modifier.fillMaxWidth().height(7.dp).drawBehind {
                            val y=size.height/2
                            val x=size.width*(position.toFloat()/duration.coerceAtLeast(1)).coerceIn(0f,1f)
                            drawLine(Color(0xFF78818A),androidx.compose.ui.geometry.Offset(0f,y),androidx.compose.ui.geometry.Offset(size.width,y),2.dp.toPx(),androidx.compose.ui.graphics.StrokeCap.Round)
                            drawLine(PlayerLime,androidx.compose.ui.geometry.Offset(0f,y),androidx.compose.ui.geometry.Offset(x,y),2.dp.toPx(),androidx.compose.ui.graphics.StrokeCap.Round)
                            drawCircle(PlayerLime,3.5.dp.toPx(),androidx.compose.ui.geometry.Offset(x,y))
                        })})
                            Text(playerTime(duration),fontSize=12.sp,color=Color(0xFFABB5C5),modifier=Modifier.widthIn(min=40.dp).testTag("player-duration"))
                        }
                        FlowRow(Modifier.fillMaxWidth().testTag("player-controls-actions"),horizontalArrangement=Arrangement.SpaceBetween,verticalArrangement=Arrangement.spacedBy(8.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                            if(episodeCount>1) IconButton(enabled=episodeIndex>0,modifier=Modifier.size(48.dp).testTag("player-previous-episode").focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch {onEpisode(episodeIndex-1)}}) {Icon(Icons.Outlined.SkipPrevious,"Предыдущая серия")}
                            IconButton(modifier=Modifier.size(48.dp).focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch {player()?.seekBack()}}) {BasicText("−10",Modifier.clearAndSetSemantics {contentDescription="Назад на 10 секунд"},style=TextStyle(color=LocalContentColor.current,fontSize=16.sp),maxLines=1,autoSize=TextAutoSize.StepBased(minFontSize=10.sp,maxFontSize=16.sp))}
                            IconButton(onClick={touch {player()?.let {if(it.isPlaying) it.pause() else it.play()}}},modifier=Modifier.size(56.dp).testTag("player-play").focusRequester(playFocus).focusProperties {if(!mobile)up=timelineFocus}.playerFocus()) {Icon(if(playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(playing) "Пауза" else "Воспроизвести",Modifier.size(24.dp))}
                            IconButton(modifier=Modifier.size(48.dp).focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch {player()?.seekForward()}}) {BasicText("+10",Modifier.clearAndSetSemantics {contentDescription="Вперёд на 10 секунд"},style=TextStyle(color=LocalContentColor.current,fontSize=16.sp),maxLines=1,autoSize=TextAutoSize.StepBased(minFontSize=10.sp,maxFontSize=16.sp))}
                            if(episodeCount>1) IconButton(enabled=episodeIndex>=0&&episodeIndex<episodeCount-1,modifier=Modifier.size(48.dp).testTag("player-next-episode").focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch {onEpisode(episodeIndex+1)}}) {Icon(Icons.Outlined.SkipNext,"Следующая серия")}
                            }
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                            IconButton(modifier=Modifier.size(48.dp).focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch(onTracks)}) {Icon(Icons.Outlined.Subtitles,"Аудио и субтитры")}
                            IconButton(modifier=Modifier.size(48.dp).focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch(onFit)}) {Icon(Icons.Outlined.Fullscreen,"Масштаб")}
                            if(mobile) IconButton(modifier=Modifier.size(48.dp).focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch(onPip)}) {Icon(Icons.Outlined.PictureInPictureAlt,"PiP")}
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
            if(status!=null)Box(Modifier.align(Alignment.Center)){PlaybackStatusOverlay(status,onRetry,onBack)}
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

internal fun playerTime(ms:Long)="%d:%02d".format(ms/60000,ms/1000%60)

@Composable internal fun Modifier.playerFocus():Modifier {
    var focused by remember {mutableStateOf(false)}
    return this.onFocusChanged {focused=it.isFocused}.drawBehind {
        if(focused) {
            val inset=2.dp.toPx()
            val origin=androidx.compose.ui.geometry.Offset(inset,inset)
            val bounds=androidx.compose.ui.geometry.Size(size.width-2*inset,size.height-2*inset)
            val radius=androidx.compose.ui.geometry.CornerRadius(11.dp.toPx())
            drawRoundRect(Color(0x662A303A),origin,bounds,radius)
            drawRoundRect(Color(0xFFF0F2EC),origin,bounds,radius,style=Stroke(1.5.dp.toPx()))
        }
    }
}
