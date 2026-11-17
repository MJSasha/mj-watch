@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.mjwatch.playback

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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
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
    var seekFeedback by remember {mutableStateOf("")}
    var seekInteraction by remember {mutableIntStateOf(0)}
    LaunchedEffect(seekInteraction) {if(seekFeedback.isNotEmpty()) {delay(900);seekFeedback=""}}
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
                        seekFeedback=if(forward) "+10 секунд" else "−10 секунд";seekInteraction++
                    }
                })
            })
            if(visible) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC0C0E15),Color.Transparent))).statusBarsPadding().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    IconButton(modifier=Modifier.focusProperties {if(!mobile)down=timelineFocus}.playerFocus(),onClick=onBack) {Icon(Icons.Outlined.ArrowBack,"Назад")}
                    Column(Modifier.weight(1f)) {Text(title,fontSize=20.sp,fontWeight=FontWeight.Bold,maxLines=1);Text(episodeLabel.ifBlank {"MJWatch"},maxLines=1,fontSize=12.sp,color=Color(0xFFABB5C5))}
                    IconButton(modifier=Modifier.testTag("player-tracks").focusRequester(tracksFocus).focusProperties {if(!mobile)down=timelineFocus}.playerFocus(),onClick={touch(onTracks)}) {Icon(Icons.Outlined.Tune,"Дорожки")}
                }
                if(ready)Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xF00C0E15)))).navigationBarsPadding().padding(horizontal=28.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Slider(value=position.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={scrubbing=true;position=it.toLong();interaction++},onValueChangeFinished={player()?.seekTo(position);scrubbing=false;interaction++},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=duration>0&&player()?.isCurrentMediaItemSeekable==true,modifier=Modifier.testTag("player-timeline").focusRequester(timelineFocus).focusProperties {if(!mobile){up=tracksFocus;down=playFocus}}.onPreviewKeyEvent {event->
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
                    }.playerFocus(),thumb={Box(Modifier.size(12.dp).background(PlayerLime,CircleShape))},track={Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF53616E),CircleShape)) {Box(Modifier.fillMaxWidth((position.toFloat()/duration.coerceAtLeast(1)).coerceIn(0f,1f)).fillMaxHeight().background(PlayerLime,CircleShape))}})
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(playerTime(position),fontSize=12.sp);Text(playerTime(duration),fontSize=12.sp,color=Color(0xFFABB5C5))}
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(episodeCount>1) IconButton(enabled=episodeIndex>0,modifier=Modifier.testTag("player-previous-episode").focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch {onEpisode(episodeIndex-1)}}) {Icon(Icons.Outlined.SkipPrevious,"Предыдущая серия")}
                        IconButton(modifier=Modifier.focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch {player()?.seekBack()}}) {Icon(Icons.Outlined.Replay10,"Назад на 10 секунд")}
                        FilledIconButton(onClick={touch {player()?.let {if(it.isPlaying) it.pause() else it.play()}}},modifier=Modifier.size(56.dp).testTag("player-play").focusRequester(playFocus).focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),shape=CircleShape) {Icon(if(playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(playing) "Пауза" else "Воспроизвести",Modifier.size(32.dp))}
                        IconButton(modifier=Modifier.focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch {player()?.seekForward()}}) {Icon(Icons.Outlined.Forward10,"Вперёд на 10 секунд")}
                        if(episodeCount>1) IconButton(enabled=episodeIndex>=0&&episodeIndex<episodeCount-1,modifier=Modifier.testTag("player-next-episode").focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch {onEpisode(episodeIndex+1)}}) {Icon(Icons.Outlined.SkipNext,"Следующая серия")}
                        Spacer(Modifier.weight(1f))
                        IconButton(modifier=Modifier.focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch(onTracks)}) {Icon(Icons.Outlined.Subtitles,"Аудио и субтитры")}
                        IconButton(modifier=Modifier.focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch(onFit)}) {Icon(Icons.Outlined.AspectRatio,"Масштаб")}
                        if(mobile) IconButton(modifier=Modifier.focusProperties {if(!mobile)up=timelineFocus}.playerFocus(),onClick={touch(onPip)}) {Icon(Icons.Outlined.PictureInPictureAlt,"PiP")}
                    }
                }
            }
            if(seekFeedback.isNotEmpty()) Text(seekFeedback,Modifier.align(Alignment.Center).background(Color(0xCC0C0E15),CircleShape).padding(20.dp),fontSize=24.sp,fontWeight=FontWeight.Bold)
            if(status!=null)Box(Modifier.align(Alignment.Center)){PlaybackStatusOverlay(status,onRetry,onBack)}
        }
    }
}
}

internal fun playerTime(ms:Long)="%d:%02d".format(ms/60000,ms/1000%60)

@Composable internal fun Modifier.playerFocus():Modifier {
    var focused by remember {mutableStateOf(false)}
    return this.onFocusChanged {focused=it.isFocused}.drawBehind {
        if(focused) {
            val outset=5.dp.toPx()
            drawRoundRect(PlayerLime,topLeft=androidx.compose.ui.geometry.Offset(-outset,-outset),size=androidx.compose.ui.geometry.Size(size.width+2*outset,size.height+2*outset),cornerRadius=androidx.compose.ui.geometry.CornerRadius(size.minDimension/2+outset),style=Stroke(2.dp.toPx()))
        }
    }
}
