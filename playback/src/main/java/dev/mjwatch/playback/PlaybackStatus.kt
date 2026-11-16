package dev.mjwatch.playback

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal data class PlaybackStatus(val title:String,val message:String="",val loading:Boolean=false,val retry:Boolean=false)

@Composable internal fun PlaybackStatusCard(status:PlaybackStatus,onRetry:()->Unit,onBack:()->Unit,focusAction:Boolean=false) {
    val actionFocus=remember {FocusRequester()}
    LaunchedEffect(status.title,focusAction) {if(focusAction)actionFocus.requestFocus()}
    Surface(Modifier.widthIn(max=420.dp).fillMaxWidth().padding(24.dp).testTag("playback-status")
        .semantics(mergeDescendants=true){liveRegion=LiveRegionMode.Polite},shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.95f)) {
        Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(status.loading)CircularProgressIndicator(Modifier.size(36.dp),strokeWidth=3.dp)
            Text(status.title,style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center)
            if(status.message.isNotBlank())Text(status.message,style=MaterialTheme.typography.bodyMedium,textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                if(status.retry)Button(modifier=Modifier.focusRequester(actionFocus),onClick=onRetry){Text("Повторить")}
                TextButton(modifier=if(!status.retry)Modifier.focusRequester(actionFocus) else Modifier,onClick=onBack){Text(if(status.loading)"Отменить" else "К раздаче")}
            }
        }
    }
}
