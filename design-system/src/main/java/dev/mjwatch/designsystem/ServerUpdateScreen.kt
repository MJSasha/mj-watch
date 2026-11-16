package dev.mjwatch.designsystem

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URI
import java.util.UUID

@Suppress("DEPRECATION")
@Composable internal fun ServerUpdateScreen(s:WatchState,model:WatchModel,tv:Boolean) {
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 val keyboard=LocalSoftwareKeyboardController.current
 val clipboard=LocalClipboardManager.current
 val managed by model.managed.state.collectAsState()
 val ssh=remember {OwnerSsh(context,model.secrets)}
 val bundles=remember {ServerBundles(context)}
 val pendingKey="serverUpdate:${s.config.scope}"
 val saved=remember {runCatching {JSONObject(model.secrets.get("ssh:${s.config.scope}"))}.getOrNull()}
 val pending=remember {
  runCatching {JSONObject(model.secrets.get(pendingKey))}.getOrNull() ?: runCatching {
   val selected=bundles.active().manifest().getString("version")
   val legacy=model.secrets.get("maintenance:${s.config.scope}:update:$selected")
   if(!legacy.matches(Regex("[A-Za-z0-9_-]{16,128}")))null else JSONObject().put("operationId",legacy).put("version",selected).put("host",saved?.optString("host") ?: URI(s.config.managedUrl).host).put("port",saved?.optInt("port",22) ?: 22)
  }.getOrNull()
 }
 var step by rememberSaveable {mutableIntStateOf(0)}
 var phase by rememberSaveable {mutableStateOf(if(pending==null) "" else "unknown")}
 var operation by rememberSaveable {mutableStateOf(pending?.optString("operationId").orEmpty())}
 var version by rememberSaveable {mutableStateOf(pending?.optString("version").orEmpty())}
 var release by remember {mutableStateOf(runCatching {bundles.active().manifest()}.getOrNull())}
 var host by rememberSaveable {mutableStateOf(pending?.optString("host") ?: saved?.optString("host") ?: runCatching {URI(s.config.managedUrl).host}.getOrNull().orEmpty())}
 var port by rememberSaveable {mutableStateOf(pending?.optInt("port",22)?.toString() ?: saved?.optInt("port",22)?.toString() ?: "22")}
 var password by remember {mutableStateOf("")}
 var passphrase by remember {mutableStateOf("")}
 var repeated by remember {mutableStateOf("")}
 var secured by remember {mutableStateOf(false)}
 var busy by remember {mutableStateOf(false)}
 var message by rememberSaveable {mutableStateOf("")}
 var code by rememberSaveable {mutableStateOf("")}
 var details by rememberSaveable {mutableStateOf(false)}
 var trust by remember {mutableStateOf<HostKeyRequired?>(null)}
 var fingerprint by remember {mutableStateOf("")}
 var confirm by remember {mutableStateOf(false)}
 fun target()=SshTarget(host.trim(),port.toIntOrNull() ?: 0,password=password)
 fun recoverPhase() {
  if(model.secrets.get(pendingKey).isBlank()){operation="";phase=""}else phase="unknown"
 }
 LaunchedEffect(Unit) {
  if(pending!=null&&model.secrets.get(pendingKey).isBlank())model.secrets.put(pendingKey,pending.toString())
  if(phase in listOf("checking","uploading","applying"))recoverPhase()
  if(phase.isBlank()&&step>1&&password.isBlank())step=1
 }
 fun perform(stage:String,verifyAccount:Boolean=true,block:suspend ()->Unit) {
  if(busy)return
  keyboard?.hide();busy=true;message="";code=""
  scope.launch {
   try {withContext(Dispatchers.IO){
    phase=stage
    if(verifyAccount)check(ManagedApi(s.config,model.secrets).authorized("me").getString("role")=="admin"){"Требуется аккаунт владельца сервера"}
    check(ssh.identity(target()).getString("serverId")==s.config.serverId){"SSH относится к другому серверу"}
    block()
   }}catch(e:CancellationException){throw e}
   catch(e:HostKeyRequired){trust=e;recoverPhase()}
   catch(e:MaintenanceFailure){phase="failed";code=e.code.takeIf {it.matches(Regex("[A-Za-z0-9_]{1,80}"))} ?: "unknown";message=serverUpdateFailure(code);passphrase="";repeated=""}
   catch(e:Exception){recoverPhase();message=safeError(e)}
   finally {busy=false}
  }
 }
 fun clearPending() {
  model.secrets.put(pendingKey,"")
  model.secrets.put("maintenance:${s.config.scope}:update:$version","")
 }
 fun finish(result:JSONObject) {
  check(result.optBoolean("verified")){"Результат обновления пока не подтверждён"}
  phase="complete";clearPending();passphrase="";repeated="";password=""
 }
 fun start() {
  operation=UUID.randomUUID().toString();version=release!!.getString("version")
  // No credentials or backup passphrase are persisted with the operation.
  perform("checking") {finish(ssh.update(target(),passphrase,operation){stage->
   if(stage=="applying")model.secrets.put(pendingKey,JSONObject().put("operationId",operation).put("version",version).put("host",host.trim()).put("port",port.toIntOrNull() ?: 22).toString())
   phase=stage
  })}
 }
 fun checkResult()=perform("checking",verifyAccount=false) {
  val deadline=System.currentTimeMillis()+300000
  do {
   val result=ssh.maintenanceStatus(target(),operation)
   when(result.optString("state")) {
    "complete" -> {finish(result);return@perform}
    "failed" -> throw MaintenanceFailure(result.optString("code"))
   }
   phase="applying";delay(1500)
  }while(System.currentTimeMillis()<deadline)
  phase="unknown";message="Результат ещё не получен. Проверьте эту же операцию позже. Новое обновление не запускается."
 }
 val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){busy=true;scope.launch {
  try {release=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)!!.use {bundles.import(it)}};message="Пакет ${release!!.getString("version")} выбран. Подпись и целостность проверены."}
  catch(e:CancellationException){throw e}catch(e:Exception){message=safeError(e)}finally{busy=false}
 }}}
 val unlock=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){if(it.resultCode==Activity.RESULT_OK){password=model.secrets.get("sshPassword:${s.config.scope}");message="SSH-доступ разблокирован"}}
 val heading=remember {BringIntoViewRequester()}
 LaunchedEffect(step,phase) {heading.bringIntoView()}
 Text("Обновление сервера",modifier=Modifier.bringIntoViewRequester(heading),fontSize=28.sp,fontWeight=FontWeight.Bold)
 if(message.isNotBlank())Text(message,Modifier.semantics{liveRegion=LiveRegionMode.Polite},color=if(phase=="failed")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
 if(phase=="complete") {
  Text("Сервер обновлён до $version",fontSize=24.sp,fontWeight=FontWeight.Bold)
  Text("Защищённое подключение, база данных и TorrServer проверены. Можно продолжать просмотр и отправлять приглашения.")
  Action("Готово",tv,Modifier.fillMaxWidth()){model.page("server-maintenance")}
 } else if(phase.isNotBlank()) {
  Text(when(phase){"checking"->"Проверяем доступ к серверу";"uploading"->"Передаём подписанный пакет";"applying"->"Сервер выполняет обновление";"failed"->"Обновление не подтверждено";else->"Проверим результат обновления"},fontSize=24.sp,fontWeight=FontWeight.Bold)
  if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
  Text("Выбранная версия: $version")
  Text("1. Проверка SSH и сервера\n2. Передача пакета\n3. Резервная копия, установка и проверка работоспособности")
  if(busy)Text("Сервер не сообщает отдельный прогресс копирования и установки. Дождитесь итоговой проверки; операция может занять несколько минут. Просмотр может прерваться.")
  if(!busy&&phase=="unknown") {
   Text("Связь могла прерваться до получения результата. Сначала проверим ту же операцию, без повторного запуска обновления.")
   ServerSecret(password,{password=it},"Пароль SSH")
   Action("Проверить результат обновления",tv,Modifier.fillMaxWidth(),enabled=password.isNotBlank()){checkResult()}
   if(password.isBlank())Text("Введите root-пароль SSH для проверки результата.")
  }
  if(!busy)Action("Вернуться в обслуживание",tv,secondary=true){model.page("server-maintenance")}
  if(!busy&&phase=="failed"&&code in listOf("update_failed_binary_rollback_confirmed","not_newer_release","already_installed","incompatible_release","supply_integrity"))Action("Выбрать другой пакет",tv,secondary=true){clearPending();operation="";phase="";step=0;message="";code=""}
  if(code.isNotBlank()) {
   Action(if(details) "Скрыть подробности" else "Подробности ошибки",tv,secondary=true){details=!details}
   if(details){Text("Код: $code\nОперация: $operation\nВерсия: $version");Action("Скопировать диагностику",tv,secondary=true){clipboard.setText(AnnotatedString("code=$code\noperation=$operation\nversion=$version"))}}
  }
 } else {
  Text("Шаг ${step+1} из 4",color=MaterialTheme.colorScheme.onSurfaceVariant)
  when(step) {
   0 -> {
    Text("Выберите версию",fontSize=24.sp,fontWeight=FontWeight.Bold)
    Text("Установлена на сервере: ${managed.takeIf {it.scope==s.config.scope}?.data?.optString("version") ?: "не проверена"}")
    Text("Пакет обновления: ${release?.optString("version") ?: "не найден"}",fontWeight=FontWeight.Bold)
    Text("Обычно достаточно встроенного пакета из свежей версии MJWatch. Выбор пакета ещё не запускает обновление сервера.")
    Action("Выбрать встроенный пакет",tv,secondary=true,enabled=!busy){
     try {bundles.useBundled();release=bundles.active().manifest();message="Встроенный пакет ${release!!.getString("version")} выбран. Нажмите «Далее»."}catch(e:Exception){message=safeError(e)}
    }
    Action("Выбрать пакет .zip",tv,secondary=true,enabled=!busy){importer.launch(arrayOf("application/zip","application/octet-stream"))}
   }
   1 -> {
    Text("Доступ к вашему серверу",fontSize=24.sp,fontWeight=FontWeight.Bold)
    Text("Нужен root-пароль SSH, который вы получили у хостинга при создании сервера. Это не пароль аккаунта MJWatch.")
    ServerField(host,{host=it},"IP или домен сервера")
    ServerSecret(password,{password=it},"Пароль SSH")
    if(saved!=null)Action("Разблокировать сохранённый SSH-доступ",tv,secondary=true){
     val manager=context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
     val intent=manager.createConfirmDeviceCredentialIntent("MJWatch","Разблокируйте SSH-доступ владельца")
     if(intent!=null)unlock.launch(intent)else message="Введите root-пароль SSH: блокировка устройства не настроена."
    }
    var advanced by rememberSaveable {mutableStateOf(false)}
    Action("SSH-порт: $port",tv,secondary=true){advanced=!advanced}
    if(advanced)ServerField(port,{port=it},"SSH-порт")
   }
   2 -> {
    Text("Защитите резервную копию",fontSize=24.sp,fontWeight=FontWeight.Bold)
    Text("Перед обновлением сервер автоматически сохранит аккаунты, историю и настройки в зашифрованный архив. Эта фраза нужна только для восстановления копии; она не меняет пароль входа или SSH.")
    ServerSecret(passphrase,{passphrase=it;secured=false},"Придумайте фразу от 16 символов")
    ServerSecret(repeated,{repeated=it},"Повторите фразу")
    if(repeated.isNotBlank()&&repeated!=passphrase)Text("Фразы не совпадают",color=MaterialTheme.colorScheme.error)
    if(tv)Action("Я сохранил парольную фразу",true,Modifier.semantics{stateDescription=if(secured)"Подтверждено" else "Не подтверждено"},secondary=true,icon=if(secured)Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked){secured=!secured}
    else Row {Checkbox(secured,{secured=it},Modifier.semantics {contentDescription="Я сохранил парольную фразу"});Text("Я сохранил парольную фразу. Без неё восстановить копию не получится.",Modifier.padding(top=12.dp))}
   }
   3 -> {
    Text("Всё готово к обновлению",fontSize=24.sp,fontWeight=FontWeight.Bold)
    Text("Сервер: ${host.trim()}\nНовая версия: ${release?.optString("version")}\nРезервная копия: будет создана автоматически")
    Text("Сначала проверим SSH и принадлежность сервера, затем передадим пакет. Сервер создаст копию, установит версию и проверит HTTPS, базу данных и TorrServer. При неудачной проверке он попытается вернуть прежнюю версию.")
    Text("Просмотр может прерваться. Обновление начнётся только после подтверждения.")
   }
  }
  val canContinue=when(step){0->release!=null;1->host.isNotBlank()&&password.isNotBlank()&&(port.toIntOrNull() ?: 0) in 1..65535;2->passphrase.length in 16..1024&&repeated==passphrase&&secured;else->password.isNotBlank()&&passphrase.length in 16..1024&&repeated==passphrase&&secured}
  if(!canContinue)Text(when(step){0->"Выберите пакет обновления.";1->"Введите адрес, root-пароль и корректный SSH-порт.";else->"Введите одинаковую фразу от 16 до 1024 символов в оба поля и подтвердите, что сохранили её."})
  Action(if(step==3) "Обновить до ${release?.optString("version")}" else "Далее",tv,Modifier.fillMaxWidth(),enabled=canContinue&&!busy){message="";if(step==3)confirm=true else step++}
  if(step>0)Action("Назад",tv,secondary=true,enabled=!busy){step--;message=""}
 }
 if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Начать обновление?")},text={Text("Будет создана зашифрованная резервная копия и установлена версия ${release?.optString("version")}. Активный просмотр может прерваться.")},confirmButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={confirm=false;start()}){Text("Начать обновление")}},dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={confirm=false}){Text("Отмена")}})
 trust?.let {key->AlertDialog(onDismissRequest={trust=null},title={Text(if(key.changed)"Ключ SSH изменился" else "Проверьте ключ SSH")},text={Column {Text("Сверьте отпечаток с консолью вашего хостинга. После подтверждения повторите проверку или запуск обновления.");Text(key.fingerprint);if(key.changed)ServerField(fingerprint,{fingerprint=it},"Подтверждённый отпечаток")}},confirmButton={TextButton(modifier=Modifier.tvFocusRing(tv),enabled=!key.changed||fingerprint==key.fingerprint,onClick={ssh.trust(target(),key.fingerprint);trust=null;if(operation.isNotBlank()){phase="unknown"}else phase="";message="Ключ SSH сохранён"}){Text("Доверять этому ключу")}},dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={trust=null}){Text("Отмена")}})}
}
