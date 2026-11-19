package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.mjwatch.core.*
import dev.mjwatch.playback.*
import kotlinx.coroutines.*

@Composable internal fun RemoteCastButton(s:WatchState,model:WatchModel,hasArt:Boolean=false) {
    val remote by model.remote.collectAsState()
    var show by remember {mutableStateOf(false)}
    var restoreFocus by remember {mutableStateOf(false)}
    var button by remember {mutableStateOf<android.widget.ImageButton?>(null)}
    val host=LocalView.current
    val tint=if(remote.connected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val background=if(hasArt)MaterialTheme.colorScheme.background.copy(alpha=.7f) else MaterialTheme.colorScheme.surface
    val description="Где смотреть?"+(remote.target?.let {" · ${it.name} · "+if(remote.connected)"Подключён" else "Нет связи"} ?: "")
    LaunchedEffect(show,restoreFocus){if(!show&&restoreFocus){
        withTimeoutOrNull(1500){while(!host.hasWindowFocus())delay(16)}
        delay(100)
        button?.apply {
            requestFocusFromTouch()
            if(context.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled)
                performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null)
        }
        restoreFocus=false
    }}
    // A native focus target supports restoring TalkBack focus as well as keyboard
    // focus. Compose's FocusRequester only restores the latter for virtual nodes.
    androidx.compose.ui.viewinterop.AndroidView(modifier=Modifier.size(48.dp).testTag("detail-cast"),factory={context->
        android.widget.ImageButton(context).apply {
            isFocusable=true
            val padding=(12*resources.displayMetrics.density).toInt();setPadding(padding,padding,padding,padding)
            button=this
        }
    },update={view->
        view.contentDescription=description
        view.setImageResource(if(remote.connected)R.drawable.ic_cast_connected else R.drawable.ic_cast)
        view.imageTintList=android.content.res.ColorStateList.valueOf(tint.toArgb())
        view.background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(tint.copy(alpha=.16f).toArgb()),android.graphics.drawable.GradientDrawable().apply {shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(background.toArgb())},null)
        view.setOnClickListener {show=true}
    })
    if(show)TargetSheet(s,model,onDismiss={show=false;restoreFocus=true})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TargetSheet(s:WatchState,model:WatchModel,onDismiss:()->Unit,onConnected:((String)->Unit)?=null) {
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
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
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
                        ensureActive();RemotePlaybackService.connect(context,device.id,device.name);if(onConnected!=null)onConnected(device.id) else onDismiss()
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

@Composable internal fun RemoteStatus(model:WatchModel,contentId:String,metadataId:String?,onStart:(()->Unit)?,modifier:Modifier=Modifier) {
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
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Tv,null)
                Text((if(target.video.status=="loading")"Готовим на ТВ" else if(target.video.status=="paused")"Пауза на ТВ" else "Идёт на ТВ")+" · ${target.name}",modifier=Modifier.weight(1f).testTag("remote-status").semantics{liveRegion=LiveRegionMode.Polite})
                }
            }
            remote.connected&&target!=null->{
                if (onStart != null) {
                    Action("Продолжить на ТВ · ${target.name}",false,Modifier.testTag("watch-on-tv"),enabled=remote.canLaunch,loading=remote.busy,secondary=true,icon=Icons.Outlined.Tv,onClick=onStart)
                } else {
                    Text("Подключён ТВ · ${target.name}",style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("connected-tv"))
                }
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
