package dev.mjwatch.designsystem

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mjwatch.core.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

@Composable internal fun AccountLinks(s:WatchState,model:WatchModel,tv:Boolean) {
    val state by model.managed.state.collectAsState()
    val me=state.takeIf {it.scope==s.config.scope}?.me
    SettingsGroup(tv) {
        SettingsActionRow(Icons.Outlined.ManageAccounts,"Аккаунт и синхронизация",me?.optString("name") ?: "Проверяем подключение",tv){model.page("managed-account")}
        if(me?.optString("role")=="admin") {
            SettingsDivider()
            SettingsActionRow(Icons.Outlined.AdminPanelSettings,"Мой сервер",s.config.managedUrl,tv){model.page("server-overview")}
        }
    }
}

@Composable internal fun AccountScreen(s:WatchState,model:WatchModel,tv:Boolean,scanQr:(()->Unit)?=null) {
    val controller=model.managed
    val qr by controller.qr.phone.collectAsState()
    val raw by controller.state.collectAsState()
    val managed=raw.takeIf {it.scope==s.config.scope} ?: ManagedState()
    var confirm by remember(s.page) {mutableStateOf<Pair<String,()->Unit>?>(null)}
    var logout by remember {mutableStateOf(false)}
    var keepData by remember {mutableStateOf(true)}
    LaunchedEffect(s.page,s.config.scope,s.config.managedUrl) {if(s.page=="managed-devices"){snapshotFlow {controller.state.value.busy}.first {!it};controller.load("devices")}}
    confirm?.let {(text,action)->AlertDialog(onDismissRequest={confirm=null},title={Text(text)},confirmButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={confirm=null;action()}){Text("Подтвердить")}},dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={confirm=null}){Text("Отмена")}})}
    if(logout)AlertDialog(onDismissRequest={logout=false},title={Text("Выйти из аккаунта?")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Изменений, ещё не отправленных на сервер: ${managed.pending.size}.")
        PreferenceChoice("Сохранить данные на этом устройстве",keepData,tv){keepData=true}
        Text("Данные и неотправленные изменения останутся доступны только этому аккаунту после повторного входа.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        PreferenceChoice("Удалить историю и очередь с устройства",!keepData,tv){keepData=false}
        Text("Локальные история, избранное и неотправленные изменения аккаунта будут удалены. Данные на сервере сохранятся.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    }},confirmButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={logout=false;controller.logout(!keepData);model.page("connection-choice")}){Text("Выйти")}},dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={logout=false}){Text("Отмена")}})
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(when(s.page){"managed-devices"->"Устройства";"managed-pairing"->"Подключить устройство";else->managed.me?.optString("name") ?: "Аккаунт"},fontSize=26.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f).semantics {heading()})
            if(scanQr!=null&&s.page in setOf("managed-account","managed-pairing")) FilledTonalIconButton(
                onClick={model.page("managed-pairing");scanQr()},
                enabled=!managed.busy&&qr.stage !in setOf("sending","waiting","approving"),
                modifier=Modifier.size(48.dp)
            ) {Icon(Icons.Outlined.QrCodeScanner,"Сканировать QR")}
        }
        if(managed.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        OperationMessage(managed.message.takeUnless {s.page=="managed-account"&&it==managed.syncMessage}.orEmpty())
        when(s.page) {
            "managed-devices"->{
                val devices=managed.data?.takeIf {it.has("devices")}?.let {managedDevices(it)}
                if(devices?.isEmpty()==true){Text("Устройств пока нет");Text("Устройства появятся после первого входа.")}
                devices?.forEach {device->key(device.id){DeviceRow(device,device.id==managed.me?.optString("deviceId"),s,model,tv){confirm="Удалить ${device.name} из списка? Доступ будет отозван. Для просмотра потребуется повторный вход." to {controller.action("devices/${device.id}","DELETE",reload="devices")}}}}
                Action("Обновить устройства",tv,secondary=true,enabled=!managed.busy){controller.load("devices")}
            }
            "managed-pairing"->{
                DisposableEffect(Unit){onDispose{controller.qr.cancelPhone()}}
                SettingsCard("Подключение по QR",Icons.Outlined.QrCodeScanner,tv){SettingsHint("Откройте QR входа на новом устройстве и отсканируйте его кнопкой вверху. Подходит для телевизора, телефона и планшета.")}
                if(qr.stage in setOf("sending","waiting","approving")) {
                    CircularProgressIndicator()
                    Text(when(qr.stage){"sending"->"Передаём настройки на устройство…";"approving"->"Подтверждаем устройство…";else->"На новом устройстве выберите «Подключить»."},modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                }
                if(qr.stage=="preview") {
                    SettingsCard("Подтвердите устройство",Icons.Outlined.Devices,tv){
                    Text("Устройство: ${qr.device}",fontWeight=FontWeight.Medium)
                    Text("Аккаунт: ${qr.account}")
                    val shown=qr
                    Action("Подключить устройство",tv){controller.qr.approvePhone(shown)}
                    }
                }
                OperationMessage(qr.message,error=qr.stage=="error")
                if(qr.stage.isNotBlank()&&qr.stage!="approved")Action("Отменить подключение",tv,secondary=true){controller.qr.cancelPhone()}
                SettingsDisclosure("Ввести код вручную","Перенос файла настроек и шестизначный код",Icons.Outlined.Password,tv) {
                var code by rememberSaveable {mutableStateOf("")}
                val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)controller.exportTvSetup(uri)}
                Text("Первое подключение: сохраните настройки и перенесите файл на TV. На телевизоре выберите «Импортировать настройки» и сверьте отпечаток.")
                Action("Сохранить настройки TV",tv,secondary=true,enabled=!managed.busy){exporter.launch("mjwatch-tv.json")}
                val ca=model.secrets.get("ca:${endpoint(s.config.managedUrl).origin()}")
                if(ca.isNotBlank()) {
                    Text("Отпечаток сертификата сервера",fontWeight=FontWeight.Medium)
                    Text(managedCaFingerprint(ca),fontSize=16.sp)
                }
                Text("Когда телевизор покажет код, введите шесть цифр ниже.")
                OutlinedTextField(code,{code=it},label={Text("Код телевизора")},singleLine=true,modifier=Modifier.fillMaxWidth().tvFieldNavigation())
                Action("Проверить телевизор",tv,enabled=code.matches(Regex("[0-9]{6}"))&&!managed.busy){controller.action("pairing/lookup","POST",JSONObject().put("code",code))}
                managed.data?.optJSONObject("pairing")?.takeIf {it.optString("code")==code}?.let {pair->
                    Text("Устройство: ${pair.getString("device")} · Пользователь: ${managed.me?.optString("name")}")
                    Action("Добавить телевизор",tv,enabled=!managed.busy){confirm="Добавить ${pair.getString("device")} в аккаунт ${managed.me?.optString("name")}?" to {controller.action("pairing/${pair.getString("code")}/approve","POST",JSONObject().put("code",pair.getString("code")),reload="devices");code=""}}
                }
                }
            }
            else->{
                SettingsCard("Аккаунт и синхронизация",Icons.Outlined.ManageAccounts,tv) {
                    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Icon(if(managed.syncedAt>0)Icons.Outlined.CloudDone else Icons.Outlined.CloudSync,null,tint=if(managed.syncedAt>0)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text("Синхронизация",style=MaterialTheme.typography.titleMedium)
                                if(managed.moveStatus!=ServerMoveStatus.CHECKING&&managed.moveStatus!=ServerMoveStatus.RETRY)Text(managed.syncMessage.ifBlank {"Проверяем синхронизацию"},style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                            }
                            IconButton(enabled=!managed.busy,onClick={controller.syncNow()},modifier=Modifier.size(48.dp).tvFocusRing(tv)){Icon(Icons.Outlined.Sync,"Синхронизировать")}
                        }
                        Text(s.config.managedUrl,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        ServerMoveStatusMessage(managed.moveStatus,tv,managed.busy,controller::syncNow)
                    }
                }
                SettingsGroup(tv) {
                    SettingsActionRow(Icons.Outlined.Devices,"Устройства","Просмотр и отключение устройств",tv){model.page("managed-devices")}
                    if(scanQr==null) {SettingsDivider();SettingsActionRow(Icons.Outlined.DevicesOther,"Подключить устройство","По коду подключения",tv){model.page("managed-pairing")}}
                }
                val history by model.scopedLibrary.collectAsState()
                managed.pending.filter {it.conflict.isNotBlank()}.forEach {pending->
                    val local=JSONObject(pending.json)
                    val title=history.entries.firstOrNull {it.id==local.optString("releaseId")}?.title ?: local.optString("title",local.optString("path",pending.entityKey))
                    SettingsCard("Конфликт · $title",Icons.Outlined.SyncProblem,tv){
                    if(pending.kind=="progress") {
                        val remote=runCatching{JSONObject(pending.conflict).optJSONObject("entity")?.optJSONObject("value")}.getOrNull()
                        Text("На этом устройстве: ${local.optLong("positionMs")/1000} с. На сервере: ${remote?.optLong("positionMs")?.div(1000) ?: "нет позиции"}")
                    }
                    Action("Использовать серверную",tv,secondary=true){controller.resolve(pending,false)}
                    Action("Использовать эту",tv){confirm="Отправить изменения этого устройства вместо актуальной серверной версии?" to {controller.resolve(pending,true)}}
                    }
                }
                var advanced by rememberSaveable {mutableStateOf(false)}
                SettingsGroup(tv) {SettingsActionRow(Icons.Outlined.MoreHoriz,"Дополнительные действия","Импорт прежней истории",tv){advanced=!advanced}}
                if(advanced) {
                    var count by remember {mutableIntStateOf(0)}
                    LaunchedEffect(s.config.scope){count=model.dao.library().first().count {it.openedAt>0}+model.dao.progress().first().size}
                    if(count==0)Text("Нет прежней истории для импорта")
                    Action("Импорт прежней истории · $count",tv,secondary=true,enabled=count>0&&!managed.busy){confirm="Импортировать $count записей в аккаунт ${managed.me?.optString("name")} на ${s.config.managedUrl}?" to {controller.importLegacy()}}
                }
                TextButton(modifier=Modifier.heightIn(min=48.dp).tvFocusRing(tv),onClick={logout=true}){Text("Выйти из аккаунта",color=MaterialTheme.colorScheme.error)}
            }
        }
    }
}
