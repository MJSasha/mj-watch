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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable internal fun DeviceRow(device:ManagedDevice,current:Boolean,s:WatchState,model:WatchModel,tv:Boolean,onRevoke:()->Unit) {
    var menu by remember {mutableStateOf(false)}
    var renaming by remember {mutableStateOf(false)}
    var restoreFocus by remember {mutableStateOf(false)}
    val focus=remember {FocusRequester()}
    val host=LocalView.current
    LaunchedEffect(renaming,restoreFocus){if(!renaming&&restoreFocus){
        withTimeoutOrNull(1500){while(!host.hasWindowFocus())delay(16)}
        delay(100);focus.requestFocus();restoreFocus=false
    }}
    val remote by model.remote.collectAsState()
    val connected=remote.connected&&remote.target?.id==device.id
    SettingsGroup(tv) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(when(device.type){"tv"->Icons.Outlined.Tv;"tablet"->Icons.Outlined.TabletAndroid;"phone"->Icons.Outlined.PhoneAndroid;else->Icons.Outlined.Devices},device.typeLabel)
                Column(Modifier.weight(1f)) {
                    Text(device.name)
                    Text("${device.typeLabel} · ${device.shortId}"+(if(current)" · Это устройство" else ""),style=MaterialTheme.typography.bodySmall)
                }
                Box {
                    IconButton(onClick={menu=true},enabled=!device.revoked,modifier=Modifier.size(48.dp).focusRequester(focus).focusProperties {canFocus=true}.tvFocusRing(tv).testTag("device-menu-${device.id}")){Icon(Icons.Outlined.MoreVert,"Действия · ${device.name}")}
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                        DropdownMenuItem(text={Text("Переименовать")},onClick={menu=false;renaming=true},modifier=Modifier.heightIn(min=48.dp))
                        if(!current)DropdownMenuItem(text={Text("Отключить ${device.name}")},onClick={menu=false;onRevoke()},enabled=!device.revoked,modifier=Modifier.heightIn(min=48.dp))
                    }
                }
            }
            Text(when {device.revoked->"Доступ отозван";connected->"Подключён для просмотра";device.type=="tv"->if(!device.remoteVideo)"Удалённый просмотр недоступен. Обновите MJWatch на ТВ." else if(device.available)"Доступен" else "Нет связи";else->"Последняя активность: ${activityTime(device.lastSeen)}"})
        }
    }
    if(renaming)RenameDeviceDialog(device,s,model,tv){renaming=false;restoreFocus=true}
}

@Composable private fun RenameDeviceDialog(device:ManagedDevice,s:WatchState,model:WatchModel,tv:Boolean,onDismiss:()->Unit) {
    var name by remember(device.id){mutableStateOf(device.name)}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    val scope=rememberCoroutineScope()
    val valid=validDeviceName(name)
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("Переименовать устройство")},text={
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=name,onValueChange={name=it;message=""},enabled=!busy,label={Text("Название")},singleLine=true,isError=!valid||message.isNotBlank(),supportingText={Text(message.ifBlank {"${name.trim().codePointCount(0,name.trim().length)} / 32"})},modifier=Modifier.fillMaxWidth().tvFieldNavigation().testTag("device-name"))
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    },confirmButton={TextButton(enabled=valid&&!busy,modifier=Modifier.heightIn(min=48.dp).tvFocusRing(tv),onClick={
        busy=true;message=""
        scope.launch {try {
            withContext(Dispatchers.IO){ManagedApi(s.config,model.secrets).authorized("devices/${device.id}","PATCH",JSONObject().put("name",name.trim()))}
            model.managed.load("devices");onDismiss()
        }catch(e:CancellationException){throw e}catch(e:Exception){message="Не удалось сохранить на сервере. Проверьте соединение."}finally{busy=false}}
    }){Text("Сохранить")}},dismissButton={TextButton(enabled=!busy,onClick=onDismiss,modifier=Modifier.heightIn(min=48.dp).tvFocusRing(tv)){Text("Отмена")}})
}
