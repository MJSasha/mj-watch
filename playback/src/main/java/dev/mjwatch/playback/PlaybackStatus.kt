@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.mjwatch.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.mjwatch.core.TorrentPlaybackStats
import kotlin.math.roundToInt

internal data class PlaybackStatus(val title:String,val message:String="",val loading:Boolean=false,val retry:Boolean=false,val compact:Boolean=false,val backLabel:String="К раздаче")

internal fun bufferingStatus(firstFrame:Boolean,elapsedMs:Long=0):PlaybackStatus = when {
    elapsedMs>=30_000 -> PlaybackStatus(if(firstFrame)"Буферизация" else "Готовим видео","Загрузка занимает больше времени",loading=true,compact=firstFrame)
    firstFrame -> PlaybackStatus("Буферизация",loading=true,compact=true)
    else -> PlaybackStatus("Готовим видео",loading=true)
}

internal fun playbackFailureStatus(code:Int?,mime:String?=null,details:String="",offline:Boolean=false):PlaybackStatus {
    val audio=mime?.startsWith("audio/")==true
    val video=mime?.startsWith("video/")==true
    val track=if(audio) "Аудиодорожка" else if(video) "Видеодорожка" else "Дорожка"
    val (title,message)=when(code) {
        3001,3003 -> "Не удалось прочитать контейнер" to "Попробуйте другую раздачу этого видео."
        4001,4002 -> "Не удалось запустить декодер" to "Доступные декодеры не удалось запустить. Попробуйте другую дорожку или раздачу."
        4004,4005 -> "$track не поддерживается" to if(audio) "Выберите раздачу с другой аудиодорожкой." else "Выберите раздачу с другим кодеком или меньшим разрешением."
        in 4000..4999 -> "Ошибка декодирования" to "Попробуйте другую дорожку или раздачу."
        in 5000..5999 -> "Не удалось вывести звук" to "Проверьте настройки вывода звука или выберите другую аудиодорожку."
        else -> "Видео недоступно" to "Не удалось восстановить поток. Повторите попытку или проверьте сервер."
    }
    val diagnostic=listOfNotNull(code?.let {"Код: $it"},mime,details.takeIf {it.isNotBlank()}).joinToString(" · ")
    val guidance=if(offline) "Проверьте дорожки или выберите другую загрузку." else message
    return PlaybackStatus(title,guidance+if(diagnostic.isBlank()) "" else "\n$diagnostic",retry=code==null||code !in 4000..5999)
}

@Composable
internal fun PlaybackStatusOverlay(
    status: PlaybackStatus,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    focusAction: Boolean = false,
    active: Boolean = true,
    stats: TorrentPlaybackStats? = null,
    progress: Float? = null,
) {
    val focus = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focusAction, active, status.loading, status.retry, windowFocused) {
        if (focusAction && active && !status.loading && windowFocused) {
            withFrameNanos { }
            focus.requestFocus()
        }
    }

    val foreground = Color(0xFFF4F4F3)
    val muted = Color(0xFFC3C3C5)
    val tag = if (status.compact) "playback-buffering" else "playback-status"
    BoxWithConstraints(modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        val available = maxWidth / LocalDensity.current.fontScale
        val metrics = status.loading && status.message.isBlank()
        val labels = buildList {
            val peers = stats?.activePeers
            val total = stats?.totalPeers
            if (metrics && peers != null && available >= 305.dp) add((if (total == null) "$peers пир." else "$peers / $total") to false)
            if (metrics) {
                stats?.downloadBytesPerSecond?.let { add("${(it * 8.0 / 1_000_000).roundToInt()} Мбит/с" to false) }
                progress?.let { add("${(it * 100f).roundToInt()}%" to true) }
                if (available >= 235.dp) stats?.connectedSeeders?.let { add("Сиды $it" to false) }
            }
            if (isEmpty() || status.compact || !metrics) add(0, status.title to true)
            if (status.loading && status.message.isNotBlank() && available >= 570.dp) add(status.message to false)
        }
        val line = buildAnnotatedString {
            labels.forEachIndexed { index, (label, emphasis) ->
                if (index > 0) withStyle(SpanStyle(color = muted)) { append("  ·  ") }
                withStyle(SpanStyle(color = if (emphasis) foreground else muted,
                    fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal)) { append(label) }
            }
        }
        FlowRow(
            Modifier.widthIn(max = 760.dp).testTag(tag).background(Color(0xDE202020), CircleShape)
                .padding(horizontal = if (status.loading) 16.dp else 10.dp, vertical = if (status.loading) 9.dp else 1.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.heightIn(min = if (status.loading) 18.dp else 36.dp).semantics {
                    if (stats == null && progress == null) liveRegion = LiveRegionMode.Polite
                    else stateDescription = status.title
                    if (status.loading && status.message.isNotBlank() && available < 570.dp) contentDescription = status.message
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (status.loading) Icons.Outlined.FileDownload else Icons.Outlined.ErrorOutline,
                    contentDescription = null, modifier = Modifier.size(17.dp), tint = muted)
                if (status.loading) {
                    Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Column {
                        Text(status.title, color = foreground, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (status.message.isNotBlank()) Text(status.message, color = muted,
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (!status.loading && active) {
                val actionColors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = foreground)
                if (status.retry) TextButton(onClick = onRetry, colors = actionColors,
                    modifier = Modifier.focusRequester(focus).playerFocus()) { Text("Повторить") }
                TextButton(onClick = onBack, colors = actionColors,
                    modifier = (if (status.retry) Modifier else Modifier.focusRequester(focus)).playerFocus()) { Text(status.backLabel) }
            }
        }
    }
}
