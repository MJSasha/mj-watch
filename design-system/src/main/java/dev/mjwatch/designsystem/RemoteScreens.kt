package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.mjwatch.core.*
import dev.mjwatch.playback.*
import kotlinx.coroutines.*

@Composable internal fun RemoteCastButton(s:WatchState,model:WatchModel) {
    val remote by model.remote.collectAsState()
    var show by remember {mutableStateOf(false)}
    val focus=remember {FocusRequester()}
    IconButton(onClick={show=true},modifier=Modifier.size(48.dp).focusRequester(focus).testTag("detail-cast")) {
        Icon(if(remote.connected)Icons.Outlined.CastConnected else Icons.Outlined.Cast,"Где смотреть?"+(remote.target?.let {" · ${it.name}"} ?: ""),tint=if(remote.connected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
    if(show)TargetSheet(s,model,onDismiss={show=false;focus.requestFocus()})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TargetSheet(s:WatchState,model:WatchModel,onDismiss:()->Unit) {
    val context=LocalContext.current
    var devices by remember {mutableStateOf<List<ManagedDevice>?>(null)}
    var message by remember {mutableStateOf("")}
    val remote by model.remote.collectAsState()
    val scope=rememberCoroutineScope()
    var connecting by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(s.config.scope) {
        if(s.config.historyEnabled)try {devices=withContext(Dispatchers.IO){managedDevices(ManagedApi(s.config,model.secrets).authorized("devices"))}}
        catch(e:CancellationException){throw e}catch(e:Exception){message="Не удалось получить устройства. Повторите попытку."}
    }
    ModalBottomSheet(onDismissRequest=onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Где смотреть?",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics{heading()})
            if(!s.config.historyEnabled)Text("Для подключения ТВ войдите в аккаунт MJWatch. В direct удалённое управление пока недоступно.")
            if(message.isNotBlank())Text(message,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
            if(connecting!=null)LinearProgressIndicator(Modifier.fillMaxWidth())
            val targets=devices.orEmpty().filter {it.type=="tv"&&it.remoteVideo&&!it.revoked}
            if(devices!=null&&targets.isEmpty())Text("Нет доступных ТВ. Откройте MJWatch на подключённом телевизоре.")
            targets.forEach {device->
                TextButton(enabled=device.available&&connecting==null,onClick={
                    connecting=device.id;message=""
                    scope.launch {try {
                        withContext(Dispatchers.IO){ManagedApi(s.config,model.secrets).authorized("remote/${device.id}/connect","POST")}
                        ensureActive();RemotePlaybackService.connect(context,device.id,device.name);onDismiss()
                    }catch(e:CancellationException){throw e}catch(e:Exception){message="ТВ недоступен. Проверьте соединение.";connecting=null}}
                },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("target-${device.id}")) {
                    Icon(Icons.Outlined.Tv,null);Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {Text("${device.name} · ${device.shortId}");Text(if(!device.available)"Нет связи" else if(remote.connected&&remote.target?.id==device.id)"Подключён для просмотра" else "Доступен",style=MaterialTheme.typography.bodySmall)}
                }
            }
            TextButton(onClick={onDismiss();model.page(if(s.config.historyEnabled)"managed-devices" else "settings")},modifier=Modifier.heightIn(min=48.dp)){Text("Все устройства")}
            if(remote.target!=null)TextButton(onClick={RemotePlaybackService.disconnect(context);onDismiss()},modifier=Modifier.heightIn(min=48.dp)){Text("Отключить телефон")}
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable internal fun RemoteStatus(model:WatchModel,contentId:String,metadataId:String?,onStart:()->Unit,modifier:Modifier=Modifier) {
    val remote by model.remote.collectAsState()
    val context=LocalContext.current
    val target=remote.target
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        when {
            !remote.connected&&remote.busy->{Text("Подключаемся к ТВ…",modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})}
            !remote.connected&&target!=null->{
                Text("Нет связи с ТВ · ${target.name}",modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                Text("Видео могло продолжиться на телевизоре.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={RemotePlaybackService.connect(context,target.id)},modifier=Modifier.heightIn(min=48.dp)){Text("Повторить")}
            }
            remote.connected&&target!=null&&target.video.active&&(target.video.releaseId==contentId||metadataId!=null&&target.video.metadataId==metadataId)->{
                Text((if(target.video.status=="loading")"Готовим на ТВ" else if(target.video.status=="paused")"Пауза на ТВ" else "Идёт на ТВ")+" · ${target.name}",modifier=Modifier.testTag("remote-status").semantics{liveRegion=LiveRegionMode.Polite})
            }
            remote.connected&&target!=null->{
                Action("Смотреть на ТВ · ${target.name}",false,Modifier.fillMaxWidth().testTag("watch-on-tv"),enabled=remote.canLaunch,loading=remote.busy,onClick=onStart)
            }
        }
        if(remote.message.isNotBlank()&&remote.connected)Text(remote.message,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
    }
}

@Composable internal fun RemoteReplacementDialog(model:WatchModel) {
    val remote by model.remote.collectAsState()
    remote.conflict?.let {replacement->
        AlertDialog(onDismissRequest={RemotePlaybackService.dismissConflict()},title={Text("Заменить видео?")},text={Text("На ТВ «${replacement.name}» сейчас «${replacement.videoTitle}». Запустить «${replacement.media.title}»?")},confirmButton={TextButton(onClick={RemotePlaybackService.confirm(replacement)},modifier=Modifier.heightIn(min=48.dp)){Text("Заменить")}},dismissButton={TextButton(onClick={RemotePlaybackService.dismissConflict()},modifier=Modifier.heightIn(min=48.dp)){Text("Отмена")}})
    }
}
