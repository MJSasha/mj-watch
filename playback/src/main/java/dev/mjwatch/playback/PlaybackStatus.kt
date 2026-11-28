@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.mjwatch.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mjwatch.core.TorrentPlaybackStats
import dev.mjwatch.core.Diagnostics
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class PlaybackLoadingMetrics(
    val torrent:TorrentPlaybackStats?,
    val streamBytesPerSecond:Double?,
    val trackerSeeders:Int?,
    val streamBytesReceived:Long,
)

internal data class PlaybackStatus(val title:String,val message:String="",val loading:Boolean=false,val retry:Boolean=false,val compact:Boolean=false,val backLabel:String="К раздаче",val audioRecovery:Boolean=false)

internal fun bufferingStatus(firstFrame:Boolean,elapsedMs:Long=0):PlaybackStatus = when {
    elapsedMs>=30_000 -> PlaybackStatus(if(firstFrame)"Буферизация" else "Готовим видео","Загрузка занимает больше времени",loading=true,compact=firstFrame)
    firstFrame -> PlaybackStatus("Буферизация",loading=true,compact=true)
    else -> PlaybackStatus("Готовим видео",loading=true)
}

internal fun playbackFailureStatus(code:Int?,mime:String?=null,details:String="",offline:Boolean=false):PlaybackStatus {
    val audio=mime?.startsWith("audio/")==true
    val video=mime?.startsWith("video/")==true
    val audioRecovery=code in 5000..5999 || (audio && code in 4000..4999)
    val track=if(audio) "Аудиодорожка" else if(video) "Видеодорожка" else "Дорожка"
    val (title,message)=when(code) {
        3001,3003 -> "Не удалось прочитать контейнер" to "Попробуйте другую раздачу этого видео."
        4001,4002 -> "Не удалось запустить декодер" to "Доступные декодеры не удалось запустить. Попробуйте другую раздачу."
        4004,4005 -> "$track не поддерживается" to if(audio) "Выберите раздачу с другой аудиодорожкой." else "Выберите раздачу с другим кодеком или меньшим разрешением."
        in 4000..4999 -> "Ошибка декодирования" to "Попробуйте другую раздачу."
        in 5000..5999 -> "Не удалось вывести звук" to "Проверьте настройки вывода звука или выберите другую аудиодорожку."
        else -> "Видео недоступно" to "Не удалось восстановить поток. Повторите попытку или проверьте сервер."
    }
    val diagnostic=listOfNotNull(code?.let {"Код: $it"},mime,details.takeIf {it.isNotBlank()}).joinToString(" · ")
    val guidance=when {
        audioRecovery -> "Выберите другую аудиодорожку или продолжите без звука."
        offline -> "Попробуйте другую загрузку."
        else -> message
    }
    return PlaybackStatus(title,guidance+if(diagnostic.isBlank()) "" else "\n$diagnostic",
        retry=code==null||code !in 4000..5999,audioRecovery=audioRecovery)
}

@Composable
internal fun PlaybackStatusOverlay(
    status: PlaybackStatus,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    focusAction: Boolean = false,
    active: Boolean = true,
    metrics: PlaybackLoadingMetrics? = null,
    progress: StartupLoadingProgress? = null,
    onAudioRecovery: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focusAction, active, status.loading, status.retry, status.audioRecovery, windowFocused) {
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
        val torrent = metrics?.torrent
        val metricsAvailable = status.loading && metrics != null
        val showStatus = !metricsAvailable || status.compact || status.message.isNotBlank()
        val serverSpeed = torrent?.downloadBytesPerSecond
        val streamSpeed = metrics?.streamBytesPerSecond
        val receivedBytes = metrics?.streamBytesReceived?.takeIf { it > 0 }
        val cachedBytes = torrent?.cachedBytes?.takeIf { it > 0 }
        val context = LocalContext.current
        val transferred = when {
            receivedBytes != null -> "Получено ${android.text.format.Formatter.formatShortFileSize(context, receivedBytes)}"
            cachedBytes != null -> "В кэше раздачи ${android.text.format.Formatter.formatShortFileSize(context, cachedBytes)}"
            else -> null
        }
        val speed = when {
            serverSpeed != null -> "${mbitPerSecond(serverSpeed)} Мбит/с"
            streamSpeed != null -> "Поток ${mbitPerSecond(streamSpeed)} Мбит/с"
            else -> "— Мбит/с"
        }
        val seeders = when {
            torrent?.connectedSeeders != null -> "Сиды ${torrent.connectedSeeders}"
            metrics?.trackerSeeders != null -> "Сиды ${metrics.trackerSeeders} (трекер)"
            else -> "Сиды —"
        }
        val peers = torrent?.activePeers
        val totalPeers = torrent?.totalPeers
        FlowRow(
            Modifier.widthIn(max = 760.dp).testTag(tag).background(Color(0xDE202020), CircleShape)
                .padding(horizontal = if (status.loading) 16.dp else 10.dp, vertical = if (status.loading) 9.dp else 1.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (status.loading) {
                if (showStatus || progress != null || transferred == null) Row(
                    Modifier.heightIn(min = 18.dp).semantics {
                        if (!metricsAvailable && progress == null) liveRegion = LiveRegionMode.Polite
                        else stateDescription = status.title
                    },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showStatus) Text(status.title, color = foreground, style = MaterialTheme.typography.bodyMedium)
                    if (status.compact) LinearProgressIndicator(
                        Modifier.width(34.dp).height(3.dp), color = foreground, trackColor = Color.DarkGray,
                    )
                    if (progress == null) {
                        if (!showStatus && transferred == null) Text("Ждём данные", color = foreground,
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("≈${(progress.fraction * 100f).roundToInt()}%", color = foreground,
                            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        progress.remainingSeconds?.let { seconds ->
                            Text("≈ $seconds с", color = foreground,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (metricsAvailable) {
                    if (transferred != null) Text(transferred, color = foreground, style = MaterialTheme.typography.bodyMedium)
                    Text(speed, color = foreground, style = MaterialTheme.typography.bodyMedium)
                    Text(seeders, color = muted, style = MaterialTheme.typography.bodyMedium)
                    if (peers != null && totalPeers != null && available >= 405.dp) {
                        Text("$peers / $totalPeers", color = muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (status.message.isNotBlank() && available >= 570.dp) {
                    Text(status.message, color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            } else {
                Row(
                    Modifier.heightIn(min = 36.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(17.dp), tint = muted)
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
                if (status.audioRecovery) TextButton(onClick = onAudioRecovery, colors = actionColors,
                    modifier = Modifier.focusRequester(focus).playerFocus()) { Text("Аудиодорожки") }
                TextButton(onClick = onBack, colors = actionColors,
                    modifier = (if (status.retry || status.audioRecovery) Modifier else Modifier.focusRequester(focus)).playerFocus()) { Text(status.backLabel) }
                val diagnostics = remember(context) { Diagnostics.get(context) }
                val enabled by diagnostics.enabled.collectAsState()
                if (enabled) {
                    val scope = rememberCoroutineScope()
                    var label by remember(status) { mutableStateOf("Скопировать диагностику") }
                    TextButton(onClick = {
                        scope.launch {
                            label = try {
                                if (diagnostics.copyReport()) "Скопировано" else "Журнал пуст"
                            } catch (_: java.io.IOException) {
                                "Не удалось скопировать"
                            }
                        }
                    }, colors = actionColors, modifier = Modifier.playerFocus()) { Text(label) }
                }
            }
        }
    }
}

private fun mbitPerSecond(bytesPerSecond:Double):String {
    val mbit = bytesPerSecond * 8.0 / 1_000_000
    return if (mbit >= 10 || mbit == 0.0) mbit.roundToInt().toString()
    else String.format(java.util.Locale.forLanguageTag("ru"), "%.1f", mbit)
}
