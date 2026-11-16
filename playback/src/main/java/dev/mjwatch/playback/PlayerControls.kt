@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.mjwatch.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.focus.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.media3.common.Player
import kotlinx.coroutines.delay

private val PlayerLime=Color(0xFFC7FF68)
@Composable internal fun PlayerControls(player:()->Player?,title:String,visible:Boolean,mobile:Boolean,keepVisible:Boolean,status:PlaybackStatus?,onRetry:()->Unit,onVisibility:(Boolean)->Unit,onBack:()->Unit,onTracks:()->Unit,onFit:()->Unit,onPip:()->Unit) {
    val playFocus=remember {FocusRequester()}
    var position by remember {mutableLongStateOf(0)};var duration by remember {mutableLongStateOf(0)};var playing by remember {mutableStateOf(false)};var ready by remember {mutableStateOf(false)}
    LaunchedEffect(visible,ready) {if(visible&&ready&&!mobile) {delay(100);playFocus.requestFocus()}}
    var interaction by remember {mutableIntStateOf(0)};var scrubbing by remember {mutableStateOf(false)}
    LaunchedEffect(Unit) {while(true) {player()?.let {p->if(!scrubbing) position=p.currentPosition;duration=p.duration.coerceAtLeast(0);playing=p.isPlaying;ready=p.playbackState==Player.STATE_READY||p.duration>0};delay(250)}}
    LaunchedEffect(visible,playing,interaction) {if(visible&&playing&&!keepVisible) {delay(4000);onVisibility(false)}}
    fun touch(block:()->Unit) {interaction++;block()}
    MaterialTheme(colorScheme=darkColorScheme(primary=PlayerLime,onPrimary=Color(0xFF0C0E15),surface=Color(0xFF191E29),onSurface=Color(0xFFF0F2EC))) {
        CompositionLocalProvider(LocalContentColor provides Color(0xFFF0F2EC)) {Box(Modifier.fillMaxSize()) {
            if(!visible) Box(Modifier.fillMaxSize().clickable {onVisibility(true)})
            if(visible) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC0C0E15),Color.Transparent))).statusBarsPadding().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    IconButton(modifier=Modifier.playerFocus(),onClick=onBack) {Icon(Icons.Outlined.ArrowBack,"Назад")}
                    Column(Modifier.weight(1f)) {Text(title,fontSize=20.sp,fontWeight=FontWeight.Bold,maxLines=1);Text("MJWatch",fontSize=12.sp,color=Color(0xFFABB5C5))}
                    IconButton(modifier=Modifier.playerFocus(),onClick={touch(onTracks)}) {Icon(Icons.Outlined.Tune,"Дорожки")}
                }
                if(ready)Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xF00C0E15)))).navigationBarsPadding().padding(horizontal=28.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Slider(value=position.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={scrubbing=true;position=it.toLong();interaction++},onValueChangeFinished={player()?.seekTo(position);scrubbing=false;interaction++},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=duration>0&&player()?.isCurrentMediaItemSeekable==true,modifier=Modifier.playerFocus(),thumb={Box(Modifier.size(12.dp).background(PlayerLime,CircleShape))},track={Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF53616E),CircleShape)) {Box(Modifier.fillMaxWidth((position.toFloat()/duration.coerceAtLeast(1)).coerceIn(0f,1f)).fillMaxHeight().background(PlayerLime,CircleShape))}})
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(playerTime(position),fontSize=12.sp);Text(playerTime(duration),fontSize=12.sp,color=Color(0xFFABB5C5))}
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        IconButton(modifier=Modifier.playerFocus(),onClick={touch {player()?.seekBack()}}) {Icon(Icons.Outlined.Replay10,"Назад на 10 секунд")}
                        FilledIconButton(onClick={touch {player()?.let {if(it.isPlaying) it.pause() else it.play()}}},modifier=Modifier.size(56.dp).focusRequester(playFocus).playerFocus(),shape=CircleShape) {Icon(if(playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(playing) "Пауза" else "Воспроизвести",Modifier.size(32.dp))}
                        IconButton(modifier=Modifier.playerFocus(),onClick={touch {player()?.seekForward()}}) {Icon(Icons.Outlined.Forward10,"Вперёд на 10 секунд")}
                        Spacer(Modifier.weight(1f))
                        IconButton(modifier=Modifier.playerFocus(),onClick={touch(onTracks)}) {Icon(Icons.Outlined.Subtitles,"Аудио и субтитры")}
                        IconButton(modifier=Modifier.playerFocus(),onClick={touch(onFit)}) {Icon(Icons.Outlined.AspectRatio,"Масштаб")}
                        if(mobile) IconButton(modifier=Modifier.playerFocus(),onClick={touch(onPip)}) {Icon(Icons.Outlined.PictureInPictureAlt,"PiP")}
                    }
                }
            }
            if(status!=null)Box(Modifier.align(Alignment.Center)){PlaybackStatusCard(status,onRetry,onBack)}
        }
    }
}
}

private fun playerTime(ms:Long)="%d:%02d".format(ms/60000,ms/1000%60)

@Composable private fun Modifier.playerFocus():Modifier {
    var focused by remember {mutableStateOf(false)}
    return this.onFocusChanged {focused=it.isFocused}.border(if(focused) 2.dp else 0.dp,if(focused) PlayerLime else Color.Transparent,CircleShape)
}
