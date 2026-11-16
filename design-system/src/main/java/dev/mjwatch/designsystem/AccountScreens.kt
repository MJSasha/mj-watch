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
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SettingsGroup(tv) {SettingsActionRow(Icons.Outlined.ManageAccounts,"Аккаунт и синхронизация",me?.optString("name") ?: "Проверяем подключение",tv){model.page("managed-account")}}
        if(me?.optString("role")=="admin")SettingsGroup(tv) {SettingsActionRow(Icons.Outlined.AdminPanelSettings,"Мой сервер",s.config.managedUrl,tv){model.page("server-overview")}}
    }
}

@Composable internal fun AccountScreen(s:WatchState,model:WatchModel,tv:Boolean,scanTvQr:(()->Unit)?=null) {
    val controller=model.managed
    val raw by controller.state.collectAsState()
    val managed=raw.takeIf {it.scope==s.config.scope} ?: ManagedState()
    var confirm by remember(s.page) {mutableStateOf<Pair<String,()->Unit>?>(null)}
    var logout by remember {mutableStateOf(false)}
    var keepData by remember {mutableStateOf(true)}
    LaunchedEffect(s.page,s.config.scope) {if(s.page=="managed-devices"){snapshotFlow {controller.state.value.busy}.first {!it};controller.load("devices")}}
    confirm?.let {(text,action)->AlertDialog(onDismissRequest={confirm=null},title={Text(text)},confirmButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={confirm=null;action()}){Text("Подтвердить")}},dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={confirm=null}){Text("Отмена")}})}
    if(logout)AlertDialog(onDismissRequest={logout=false},title={Text("Выйти из аккаунта?")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Изменений, ещё не отправленных на сервер: ${managed.pending.size}.")
        PreferenceChoice("Сохранить данные на этом устройстве",keepData,tv){keepData=true}
        Text("Данные и неотправленные изменения останутся доступны только этому аккаунту после повторного входа.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        PreferenceChoice("Удалить историю и очередь с устройства",!keepData,tv){keepData=false}
        Text("Локальные история, избранное и неотправленные изменения аккаунта будут удалены. Данные на сервере сохранятся.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    }},confirmButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={logout=false;controller.logout(!keepData);model.page("connection-choice")}){Text("Выйти")}},dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={logout=false}){Text("Отмена")}})
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(when(s.page){"managed-devices"->"Мои устройства";"managed-tv"->"Добавить телевизор";else->managed.me?.optString("name") ?: "Аккаунт"},fontSize=26.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics {heading()})
        if(managed.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        OperationMessage(managed.message)
        when(s.page) {
            "managed-devices"->{
                val devices=managed.data?.takeIf {it.has("devices")}?.let {managedDevices(it)}
                if(devices?.isEmpty()==true){Text("Устройств пока нет");Text("Устройства появятся после первого входа.")}
                devices?.forEach {device->SettingsGroup(tv){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    val current=device.id==managed.me?.optString("deviceId")
                    Text(device.name,fontWeight=FontWeight.Medium)
                    if(current)Text("Это устройство")
                    Text(if(device.revoked)"Доступ отозван" else "Последняя активность: ${activityTime(device.lastSeen)}")
                    if(!current)Action("Отключить ${device.name}",tv,secondary=true,enabled=!device.revoked&&!managed.busy){confirm="Отключить ${device.name}? Для просмотра на нём потребуется повторный вход." to {controller.action("devices/${device.id}","DELETE",reload="devices")}}
                }}}
                Action("Обновить устройства",tv,secondary=true,enabled=!managed.busy){controller.load("devices")}
            }
            "managed-tv"->{
                val qr by controller.qr.phone.collectAsState()
                DisposableEffect(Unit){onDispose{controller.qr.cancelPhone()}}
                Text("Откройте подключение MJWatch на TV и отсканируйте QR. Адрес и сертификат передадутся автоматически.")
                if(scanTvQr!=null)Action("Сканировать QR телевизора",tv,enabled=qr.stage !in setOf("sending","waiting","approving"),onClick=scanTvQr)
                if(qr.stage in setOf("sending","waiting","approving")) {
                    CircularProgressIndicator()
                    Text(when(qr.stage){"sending"->"Передаём настройки на TV…";"approving"->"Подтверждаем телевизор…";else->"На TV выберите «Подключить»."},modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                }
                if(qr.stage=="preview") {
                    Text("Телевизор: ${qr.device}",fontWeight=FontWeight.Medium)
                    Text("Аккаунт: ${qr.account}")
                    val shown=qr
                    Action("Подключить телевизор",tv){controller.qr.approvePhone(shown)}
                }
                OperationMessage(qr.message,error=qr.stage=="error")
                if(qr.stage.isNotBlank()&&qr.stage!="approved")Action("Отменить подключение TV",tv,secondary=true){controller.qr.cancelPhone()}
                var legacy by rememberSaveable {mutableStateOf(false)}
                Action("Ввести код вручную",tv,secondary=true){legacy=!legacy}
                if(legacy) {
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
                Text(s.config.managedUrl)
                Text(managed.syncMessage.ifBlank {"Проверяем синхронизацию"},modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                Action("Синхронизировать",tv,enabled=!managed.busy){controller.syncNow()}
                SettingsGroup(tv) {
                    SettingsActionRow(Icons.Outlined.Devices,"Мои устройства","Просмотр и отключение устройств",tv){model.page("managed-devices")}
                    SettingsDivider()
                    SettingsActionRow(Icons.Outlined.Tv,"Добавить телевизор","Сканировать QR с экрана TV",tv){model.page("managed-tv")}
                }
                val history by model.scopedLibrary.collectAsState()
                managed.pending.filter {it.conflict.isNotBlank()}.forEach {pending->
                    val local=JSONObject(pending.json)
                    val title=history.entries.firstOrNull {it.id==local.optString("releaseId")}?.title ?: local.optString("title",local.optString("path",pending.entityKey))
                    Text("Конфликт · $title",fontWeight=FontWeight.Bold)
                    if(pending.kind=="progress") {
                        val remote=runCatching{JSONObject(pending.conflict).optJSONObject("entity")?.optJSONObject("value")}.getOrNull()
                        Text("На этом устройстве: ${local.optLong("positionMs")/1000} с. На сервере: ${remote?.optLong("positionMs")?.div(1000) ?: "нет позиции"}")
                    }
                    Action("Использовать серверную",tv,secondary=true){controller.resolve(pending,false)}
                    Action("Использовать эту",tv){confirm="Отправить изменения этого устройства вместо актуальной серверной версии?" to {controller.resolve(pending,true)}}
                }
                var advanced by rememberSaveable {mutableStateOf(false)}
                Action("Дополнительные действия",tv,secondary=true){advanced=!advanced}
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
