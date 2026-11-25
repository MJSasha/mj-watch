@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.mjwatch.designsystem
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.UUID

private const val JACRED_OVERVIEW = "admin/jacred?view=overview"

@Composable internal fun ServerScreen(s:WatchState,model:WatchModel,tv:Boolean,registerBack:((()->Unit)?)->Unit) {
 val keyboard=LocalSoftwareKeyboardController.current
 val controller=model.managed;val account by controller.state.collectAsState();val managed=if(account.scope==s.config.scope)account else account.copy(me=null,data=null,pending=emptyList());val page=s.page
 var confirm by remember {mutableStateOf<Pair<String,()->Unit>?>(null)}
 var mobileQr by rememberSaveable(page) {mutableStateOf(false)}
 val screenScroll=rememberScrollState()
 LaunchedEffect(page,mobileQr) {screenScroll.scrollTo(0)}
 var dirty by remember(page) {mutableStateOf(false)}
 var leave by remember {mutableStateOf(false)}
 val back:()->Unit={if(page=="managed-login"&&mobileQr)mobileQr=false else if(dirty)leave=true else model.back()}
 DisposableEffect(page,dirty){registerBack(back);onDispose{registerBack(null)}}
 BackHandler {back()}
 if(leave) AlertDialog(onDismissRequest={leave=false},title={Text("Несохранённые изменения")},text={Text("Примените изменения на странице или отбросьте их.")},confirmButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={leave=false;dirty=false;model.back()}){Text("Отбросить")}},dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={leave=false}){Text("Продолжить редактирование")}})
 confirm?.let {(text,action)->AlertDialog(onDismissRequest={confirm=null},title={Text("Подтвердите действие")},text={Text(text)},confirmButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={confirm=null;action()}){Text("Подтвердить")}},dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={confirm=null}){Text("Отмена")}})}
 LaunchedEffect(page,s.config.scope,managed.me){if(page=="managed-login"&&s.config.historyEnabled&&managed.me?.optString("userId")==s.config.userId){controller.cancelPairing();model.page("home")}}
 val load=when(page){"server-overview"->"admin/status";"server-users"->"admin/users";"server-tmdb"->"admin/tmdb";"server-jacred"->JACRED_OVERVIEW;"server-history"->"admin/backups";"server-retention"->"admin/settings";"server-maintenance","server-update"->"admin/status";"managed-account"->"devices";else->null}
 LaunchedEffect(page,s.config.scope,s.config.managedUrl){if(load!=null&&s.config.historyEnabled) {
  do {controller.load(load);if(page=="server-overview")delay(15000)} while(page=="server-overview")
 }}
 val content:@Composable ()->Unit={Column(Modifier.fillMaxSize().verticalScroll(screenScroll).imePadding().padding(vertical=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  if(managed.busy&&page!="server-overview"&&page!="server-jacred"&&page!="server-resources") LinearProgressIndicator(Modifier.fillMaxWidth())
  when(page) {
   "connection-choice"->{
    Text("Как подключиться",fontSize=28.sp,fontWeight=FontWeight.Bold)
    Text("Выберите способ просмотра. Источник раздач настраивается отдельно.")
    SettingsGroup(tv) {
     SettingsActionRow(Icons.Outlined.Dns,"Готовый TorrServer","По IP или URL. Без истории.",tv) {confirm="Сохранить прежнюю локальную историю отдельно? В direct она скрыта и не обновляется." to {controller.direct(false);model.page("settings-server")}}
     SettingsDivider();SettingsActionRow(Icons.Outlined.Cloud,"Войти на сервер MJWatch","История и каталог на ваших устройствах",tv) {model.page("managed-login")}
     SettingsDivider();SettingsActionRow(Icons.Outlined.Terminal,"Установить сервер","Нужны IP, root и SSH-пароль",tv) {model.page("server-install")}
    }
    Action("Демонстрация, импорт и экспорт",tv,secondary=true){model.page("connections")}
    Action("Удалить прежнюю локальную историю",tv,secondary=true){confirm="Будут удалены прежние открытия и позиции. Избранное сохранится." to {controller.direct(true);model.page("settings-server")}}
   }
   "managed-login"->{
    var manualLogin by rememberSaveable {mutableStateOf(false)}
    var tvLegacy by rememberSaveable {mutableStateOf(false)}
    var url by rememberSaveable {mutableStateOf(s.config.managedUrl)};var name by rememberSaveable {mutableStateOf("")};var password by remember {mutableStateOf("")};var invite by remember {mutableStateOf("")};var ca by remember {mutableStateOf("")};var advanced by remember {mutableStateOf(false)}
    if(tv&&(tvLegacy||manualLogin))Action("Подключиться по QR",true,secondary=true){tvLegacy=false;manualLogin=false}
    if(!tv&&mobileQr) TvQrScreen(model,tv=false){mobileQr=false}
    else if(tv&&!manualLogin) {if(tvLegacy)TvPairingScreen(s,model){manualLogin=true} else TvQrScreen(model){tvLegacy=true}} else {
    DisposableEffect(Unit){onDispose{controller.cancelPairing()}}
    val loginContext=LocalContext.current
    var importOpen by remember {mutableStateOf(false)};var packet by remember {mutableStateOf("")};var importError by remember {mutableStateOf("")}
    fun fillInvitation(raw:String):Boolean {
     return try {
      if(raw.trim().startsWith("https://mjsasha.github.io/")||raw.trim().startsWith("mjwatch://")){decodeInvitationLink(raw.trim());model.openInvitation(raw.trim());return true}
      val parsed=decodeManagedInvitation(raw)
      url=parsed.url;invite=parsed.invite;ca=parsed.ca;password="";name="";advanced=true;dirty=true;packet="";importOpen=false
      val origin=endpoint(parsed.url).origin();val fingerprint=managedCaFingerprint(parsed.ca)
      confirm="Подключиться к $origin? Сверьте отпечаток сертификата с владельцем: $fingerprint" to {model.secrets.put("ca:$origin",parsed.ca);ca="";advanced=false}
      true
     } catch(e:Exception){importError="Приглашение не распознано. Скопируйте его целиком у владельца сервера.";false}
    }
    Text("Войти на сервер MJWatch",fontSize=26.sp,fontWeight=FontWeight.Bold)
    if(!tv)Action("Войти по QR с другого устройства",false,secondary=true){keyboard?.hide();mobileQr=true}
    OperationMessage(managed.message,error=true)
    Text("Получили приглашение? Скопируйте его у владельца и нажмите кнопку ниже.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    Action("Вставить приглашение",tv,secondary=true,enabled=!managed.busy){
     val clipboard=loginContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
     val copied=clipboard.primaryClip?.let {if(it.itemCount>0)it.getItemAt(0).coerceToText(loginContext)?.toString() else null}.orEmpty()
     if(copied.isBlank()){packet="";importError="";importOpen=true}
     else if(!fillInvitation(copied)){packet="";importOpen=true}
    }
    if(importOpen)AlertDialog(onDismissRequest={importOpen=false;packet=""},title={Text("Вставьте приглашение")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
     Text("Скопируйте приглашение в приложении владельца и вставьте сюда целиком. Адрес и настройки подключения заполнятся сами.")
     ServerSecret(packet,{packet=it;importError=""},"Приглашение")
     if(importError.isNotBlank())Text(importError,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(modifier=Modifier.tvFocusRing(tv),enabled=packet.isNotBlank(),onClick={fillInvitation(packet)}){Text("Продолжить")}},dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={importOpen=false;packet=""}){Text("Отмена")}})

    if(invite.isBlank())ServerField(url,{url=it;dirty=true},"HTTPS-адрес MJWatch")
    if(invite.isBlank())ServerField(name,{name=it;dirty=true},"Имя пользователя")
    if(invite.isNotBlank()){
     val origin=runCatching{endpoint(url).origin()}.getOrDefault("")
     SettingsGroup(tv){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Приглашение добавлено",fontWeight=FontWeight.Bold);Text(origin);Text(if(ca.isBlank())"Сертификат сохранён. Придумайте пароль от 10 символов." else "Подтвердите сервер по отпечатку сертификата.")}}
    }
    ServerSecret(password,{password=it;dirty=true},if(invite.isBlank())"Пароль" else "Придумайте пароль · от 10 символов")
    if(invite.isBlank())Action("Войти",tv,Modifier.fillMaxWidth(),enabled=!managed.busy&&url.isNotBlank()&&name.isNotBlank()&&password.isNotBlank()&&ca.isBlank()&&invite.isBlank()){dirty=false;controller.login(url,name,password);password=""}
    if(invite.isBlank())Action("Войти по коду TV",tv,secondary=true,enabled=!managed.busy&&url.isNotBlank()&&ca.isBlank()){controller.startPairing(url)}
    managed.pairing?.let {pair->Text(pair.getString("code"),fontSize=48.sp,fontWeight=FontWeight.Bold);Text("Код действует 5 минут. На вошедшем устройстве откройте «Аккаунт и синхронизация → QR-сканер → Ввести код вручную».");Action("Отменить вход по коду",tv,secondary=true){controller.cancelPairing()}}
    if(invite.isNotBlank())Action("Создать аккаунт и войти",tv,Modifier.fillMaxWidth(),enabled=!managed.busy&&password.length>=10&&ca.isBlank()){dirty=false;controller.login(url,name,password,invite);password=""}
    TextButton(modifier=Modifier.tvFocusRing(tv),onClick={advanced=!advanced}){Text("Параметры подключения вручную")}
    if(advanced){ServerSecret(invite,{invite=it;dirty=true},"Одноразовое приглашение");Action("Принять приглашение",tv,enabled=!managed.busy&&invite.isNotBlank()&&password.length>=10&&ca.isBlank()){dirty=false;controller.login(url,name,password,invite);password="";invite=""};ServerField(ca,{ca=it},"Публичный CA из доверенного приглашения",singleLine=false)
     if(ca.isNotBlank()){
      val normalizedCa=normalizeManagedCa(ca)
      val fingerprint=runCatching {managedCaFingerprint(normalizedCa)}.getOrNull()
      val origin=runCatching {endpoint(url).takeIf {it.scheme=="https"}?.origin()}.getOrNull()
      Text(fingerprint ?: "Некорректный CA: импортируйте приглашение целиком")
      if(origin==null)Text("Укажите корректный HTTPS-адрес сервера",color=MaterialTheme.colorScheme.error)
      Action("Доверять этому CA для сервера",tv,secondary=true,enabled=fingerprint!=null&&origin!=null){confirm="Сервер: $origin. Сверьте отпечаток CA с владельцем через доверенный канал: $fingerprint" to {model.secrets.put("ca:$origin",normalizedCa);ca=""}}
     }
     val origin=runCatching {endpoint(url).origin()}.getOrNull()
     if(origin!=null&&model.secrets.get("ca:$origin").isNotBlank())Text("CA сохранён для $origin. Адрес и порт должны совпадать с приглашением.",color=MaterialTheme.colorScheme.primary)

    }
    }
   }
   "server-overview"->{
    Text("Мой сервер",fontSize=28.sp,fontWeight=FontWeight.Bold)
    ServerStatusOverview(s.config.managedUrl,managed.me?.optString("role"),managed.data?.takeIf {it.has("databaseHealthy")&&it.has("torrserverHealthy")},managed.busy,managed.message.isNotBlank(),managed.statusCheckedAt,tv){controller.load("admin/status")}
    if(managed.me?.optString("role")=="admin") SettingsGroup(tv){listOf(Triple("Ресурсы сервера","server-resources",Icons.Outlined.Speed),Triple("Пользователи","server-users",Icons.Outlined.People),Triple("TorrServer","server-torr",Icons.Outlined.Dns),Triple("JacRed","server-jacred",Icons.Outlined.Search),Triple("TMDB и каталог","server-tmdb",Icons.Outlined.Movie),Triple("История и копии","server-history",Icons.Outlined.History),Triple("Обслуживание","server-maintenance",Icons.Outlined.Build)).forEach {(title,destination,icon)->SettingsActionRow(icon,title,when(destination){"server-resources"->"Процессор, память и диск";"server-users"->managed.data?.takeIf {it.has("users")}?.let {"Пользователей: ${it.getInt("users")}"} ?: "Не загружено";"server-tmdb"->managed.data?.optJSONObject("tmdb")?.let {if(it.optBoolean("credentialConfigured"))"Ключ настроен" else "Ключ не настроен"} ?: "Не загружено";else->"Настройки и состояние"},tv){model.page(destination)};SettingsDivider()}}
   }
   "server-tmdb"->{
    var enabled by remember {mutableStateOf(false)};var language by rememberSaveable {mutableStateOf("ru-RU")};var cache by rememberSaveable {mutableStateOf("256")};var credential by remember {mutableStateOf("")};var revision by remember {mutableStateOf("")}
    LaunchedEffect(managed.data){managed.data?.optJSONObject("settings")?.let {data->enabled=data.optBoolean("enabled");language=data.optString("language","ru-RU");cache=data.optLong("cacheMiB",256).toString();revision=managed.data!!.optString("revision");dirty=false}}
    Text("TMDB и каталог",fontSize=26.sp,fontWeight=FontWeight.Bold)
    SettingsCard("Метаданные и обложки",Icons.Outlined.Movie,tv){
     ServerToggle("Использовать TMDB",enabled){enabled=it;dirty=true}
     Text(if(managed.data?.optJSONObject("settings")?.optBoolean("credentialConfigured")==true)"Ключ настроен" else "Ключ не настроен",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
     managed.data?.optString("lastCheckedAt")?.toLongOrNull()?.takeIf{it>0}?.let{Text("Последняя успешная проверка: ${java.time.Instant.ofEpochSecond(it)}",style=MaterialTheme.typography.bodySmall)}
     ServerField(language,{language=it;dirty=true},"Язык каталога, например ru-RU")
     ServerSecret(credential,{credential=it;dirty=true},"Новый Read Access Token или API key v3")
     SettingsHint("Ключ хранится только на сервере и не возвращается приложению. Оставьте поле пустым, чтобы сохранить действующий ключ.")
    }
    SettingsCard("Кэш каталога",Icons.Outlined.Storage,tv){
     SettingsNumber(cache,{cache=it;dirty=true},"Лимит кэша","МиБ",1,1024,tv,recommended=256,hint="Метаданные и изображения. Предел настройки не является оценкой свободного места сервера.")
    }
    fun body()=JSONObject().put("enabled",enabled).put("language",language).put("cacheMiB",cache.toLongOrNull() ?: 0).put("credential",credential)
    Action("Проверить и применить",tv,Modifier.fillMaxWidth(),enabled=!managed.busy&&dirty&&revision.isNotBlank()&&(cache.toLongOrNull() ?: 0) in 1L..1024L){val input=body();credential="";controller.action("admin/tmdb","PATCH",input,revision,"admin/tmdb")}
    Action("Проверить подключение",tv,secondary=true,enabled=!managed.busy){val input=body();credential="";controller.action("admin/tmdb/check","POST",input,reload="admin/tmdb")}
    SettingsDisclosure("Управление данными","Очистка кэша и удаление ключа",Icons.Outlined.ManageAccounts,tv){
     Action("Очистить кэш",tv,secondary=true){confirm="Очистить кэш метаданных и изображений?" to {controller.action("admin/tmdb/cache","DELETE",reload="admin/tmdb")}}
     TextButton(modifier=Modifier.heightIn(min=48.dp).tvFocusRing(tv),onClick={confirm="Удалить серверный ключ TMDB и отключить каталог?" to {credential="";controller.action("admin/tmdb/credential","DELETE",reload="admin/tmdb")}}){Text("Удалить ключ и отключить",color=MaterialTheme.colorScheme.error)}
    }
   }
   "server-retention"->{
    var days by remember{mutableStateOf("0")};var limit by remember{mutableStateOf("1024")};var keep by remember{mutableStateOf("7")};var hour by remember{mutableStateOf("3")};var enabled by remember{mutableStateOf(false)};var phrase by remember{mutableStateOf("")};var revision by remember{mutableStateOf("")}
    LaunchedEffect(managed.data){managed.data?.optJSONObject("settings")?.let {v->days=v.optInt("historyDays").toString();limit=v.optLong("databaseLimitMiB",1024).toString();keep=v.optInt("backupKeep",7).toString();hour=v.optInt("backupHourUTC",3).toString();enabled=v.optBoolean("backupEnabled");revision=managed.data!!.getString("revision");dirty=false}}
    Text("Хранение и расписание копий",fontSize=26.sp,fontWeight=FontWeight.Bold)
    SettingsCard("История просмотра",Icons.Outlined.History,tv){
     SettingsNumber(days,{days=it;dirty=true},"Хранить историю","дней",0,36500,tv,hint="0 — без ограничения срока. Изменение действует на сервере для всех аккаунтов.")
     SettingsNumber(limit,{limit=it;dirty=true},"Лимит базы","МиБ",32,1048576,tv,recommended=1024,hint="Это предел настройки, а не оценка свободного диска. Учитывайте место для резервных копий и других сервисов.")
    }
    SettingsCard("Ежедневные резервные копии",Icons.Outlined.Backup,tv){
     ServerToggle("Ежедневная резервная копия",enabled){enabled=it;dirty=true}
     SettingsNumber(hour,{hour=it;dirty=true},"Час запуска, UTC","ч",0,23,tv,recommended=3,hint="Время сервера указано в UTC, без перехода на летнее время.")
     SettingsNumber(keep,{keep=it;dirty=true},"Хранить последних плановых копий","копий",1,365,tv,recommended=7)
     ServerSecret(phrase,{phrase=it;dirty=true},"Новая парольная фраза плановых копий")
     SettingsHint("Парольная фраза не включается в архив. Сохраните её отдельно. Пустое поле сохраняет уже установленную фразу.")
    }
    val valid=(days.toIntOrNull() ?: -1) in 0..36500&&(limit.toLongOrNull() ?: -1) in 32L..1048576L&&(keep.toIntOrNull() ?: -1) in 1..365&&(hour.toIntOrNull() ?: -1) in 0..23
    Action("Сохранить",tv,Modifier.fillMaxWidth(),enabled=dirty&&!managed.busy&&revision.isNotBlank()&&valid){val body=JSONObject().put("historyDays",days.toIntOrNull() ?: -1).put("databaseLimitMiB",limit.toLongOrNull() ?: -1).put("backupKeep",keep.toIntOrNull() ?: -1).put("backupHourUTC",hour.toIntOrNull() ?: -1).put("backupEnabled",enabled).put("backupPassphrase",phrase);phrase="";controller.action("admin/settings","PATCH",body,revision,"admin/settings")}
   }
   "server-history"->{
    Text("История и резервные копии",fontSize=26.sp,fontWeight=FontWeight.Bold)
    SettingsGroup(tv){SettingsActionRow(Icons.Outlined.Schedule,"Хранение и расписание копий","Срок истории, размер базы и ежедневные копии",tv){model.page("server-retention")}}
    var downloadId by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};val context=LocalContext.current
    val downloader=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null){val out=context.contentResolver.openOutputStream(uri);if(out!=null)controller.downloadBackup(downloadId,password,out)};password=""}
    var passphrase by remember{mutableStateOf("")}
    SettingsCard("Создать резервную копию",Icons.Outlined.Backup,tv){
     ServerSecret(passphrase,{passphrase=it},"Парольная фраза архива · от 16 символов")
     SettingsHint("Архив шифруется этой фразой. Сохраните её отдельно и скачайте копию: архив на сервере не защищает от потери его диска.")
     Action("Создать копию",tv,Modifier.fillMaxWidth(),enabled=passphrase.length>=16&&!managed.busy){val pass=passphrase;passphrase="";controller.action("admin/backups","POST",JSONObject().put("passphrase",pass),reload="admin/backups")}
    }
    SettingsCard("Скачать копию",Icons.Outlined.FileDownload,tv){
     ServerSecret(password,{password=it},"Пароль владельца для скачивания · повторная проверка")
     Text("Если пароль владельца ещё не задан, установите его по SSH в обслуживании.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     managed.data?.optJSONArray("backups")?.let{rows->
      if(rows.length()==0)Text("Резервных копий пока нет",style=MaterialTheme.typography.bodyMedium)
      for(index in 0 until rows.length()){val backup=rows.getJSONObject(index)
       HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
       Text(backup.getString("id"),style=MaterialTheme.typography.labelLarge)
       Text(sizeLabel(backup.getLong("size")),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
       Action("Скачать зашифрованную копию",tv,secondary=true,enabled=password.isNotBlank()&&!managed.busy){downloadId=backup.getString("id");downloader.launch(downloadId)}
      }
     }
    }
    SettingsDisclosure("Очистка истории","Только открытия и позиции текущего аккаунта",Icons.Outlined.DeleteOutline,tv){
     Text("Избранное сохраняется. Другие аккаунты не затрагиваются.",style=MaterialTheme.typography.bodySmall)
     TextButton(modifier=Modifier.heightIn(min=48.dp).tvFocusRing(tv),onClick={confirm="Очистить открытия и позиции только текущего аккаунта? Избранное сохранится, старая очередь будет отклонена." to {controller.action("history","DELETE");controller.syncNow()}}){Text("Очистить мою историю",color=MaterialTheme.colorScheme.error)}
    }
   }
   "server-maintenance"->{
    Text("Обслуживание",fontSize=26.sp,fontWeight=FontWeight.Bold)
    SettingsCard("Обновление сервера",Icons.Outlined.SystemUpdate,tv){
     Text("Версия сервера: ${managed.data?.optString("version") ?: "не проверена"}",style=MaterialTheme.typography.labelLarge)
     Text("Проверим доступ, сохраним резервную копию и установим выбранную версию.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
     Action("Обновить сервер",tv,Modifier.fillMaxWidth()){model.page("server-update")}
    }
    if(managed.me?.optString("role")=="admin")SettingsCard("Перенос сервера",Icons.Outlined.CloudSync,tv){
     Text("Аккаунты, история и вход на устройствах сохранятся.",style=MaterialTheme.typography.bodyMedium)
     Action("Подготовить перенос",tv,Modifier.fillMaxWidth(),secondary=true){model.page("server-migration")}
    }
    SettingsDisclosure("Другие операции","Перезапуск, очистка кэша, пароль и восстановление",Icons.Outlined.Build,tv){key(s.config.managedUrl){SshPanel(s,model,tv,false)}}
   }
   "server-update"->key(s.config.managedUrl){ServerUpdateScreen(s,model,tv)}
   "server-migration"->ServerMigrationScreen(s.config.managedUrl,managed,controller,tv)

   "server-resources"->{
    Text("Ресурсы сервера",fontSize=28.sp,fontWeight=FontWeight.Bold)
    if(managed.me?.optString("role")=="admin") ServerResourcesScreen(s.config,model.secrets,tv)
    else SettingsHint("Ресурсы сервера доступны администратору.")
   }
   "server-jacred"->{
    Text("JacRed",fontSize=28.sp,fontWeight=FontWeight.Bold)
    val admin=managed.me?.optString("role")=="admin"
    if(admin) {
     var editing by rememberSaveable(s.config.scope){mutableStateOf(false)}
     SettingsChoiceTabs("Диагностика",Icons.Outlined.MonitorHeart,"Настройки",Icons.Outlined.Tune,editing,tv) {next->
      if(dirty&&!next)confirm="Отбросить несохранённые изменения JacRed?" to {dirty=false;editing=false}
      else editing=next
     }
     val diagnostics=managed.data?.takeIf {it.optBoolean("jacredDiagnostics")}
     var catalog by remember(s.config.scope,s.config.managedUrl) {mutableStateOf<JSONObject?>(null)}
     var catalogLoading by remember(s.config.scope,s.config.managedUrl) {mutableStateOf(false)}
     var catalogError by remember(s.config.scope,s.config.managedUrl) {mutableStateOf("")}
     var catalogRefresh by remember(s.config.scope,s.config.managedUrl) {mutableIntStateOf(0)}
     if(editing) {
      key(s.config.managedUrl){SshPanel(s,model,tv,false,jacred=true,onDirty={dirty=it})}
     } else {
      val separateCatalog=diagnostics?.optBoolean("separateCatalogStats")==true
      val installed=diagnostics?.optBoolean("installed")==true
      val lastRun=diagnostics?.optJSONObject("index")?.optJSONArray("history")?.optJSONObject(0)?.optLong("updatedAt")
      LaunchedEffect(s.config.scope,s.config.managedUrl,separateCatalog,installed,lastRun,catalogRefresh) {
       if(separateCatalog&&installed) {
        catalogLoading=true
        catalogError=""
        try {
         val fresh=withContext(Dispatchers.IO){ManagedApi(s.config,model.secrets).authorized("admin/jacred/stats")}
         if(fresh.has("catalogCount")||catalog==null)catalog=fresh
        } catch(e:CancellationException) {
         throw e
        } catch(e:Exception) {
         if(e is HttpFailure&&e.status in setOf(401,403))catalog=null
         catalogError=safeError(e)
        } finally {
         catalogLoading=false
        }
       }
      }
      JacredDiagnosticsPanel(diagnostics,tv,managed.busy,
       catalog=if(separateCatalog) catalog else diagnostics,
       catalogLoading=if(separateCatalog) catalogLoading||(installed&&catalog==null&&catalogError.isBlank()) else managed.busy,
       catalogError=if(separateCatalog) catalogError else "",
       onCatalogRefresh={if(separateCatalog) catalogRefresh++ else controller.load(JACRED_OVERVIEW)},
       onSettings={editing=true},onCollect={controller.action("admin/jacred/collect","POST",reload=JACRED_OVERVIEW)}) { query ->
       controller.load(if(query.isBlank()) JACRED_OVERVIEW else "$JACRED_OVERVIEW&query="+java.net.URLEncoder.encode(query,"UTF-8").replace(".","%2E"))
      }
      val indexing=diagnostics?.optBoolean("collectQueued")==true||diagnostics?.optJSONObject("index")?.optString("state")=="running"
      LaunchedEffect(indexing) {
       if(indexing) {
        while(true){delay(6_000);controller.load(JACRED_OVERVIEW)}
       }
      }
     }
    } else if(managed.me!=null) SettingsHint("Панель JacRed доступна администратору сервера.")
   }
   "server-install"->{Text("Установка сервера",fontSize=28.sp,fontWeight=FontWeight.Bold);SshPanel(s,model,tv,true)}
   "server-torr"->{
    var limitsDirty by remember{mutableStateOf(false)};var torrDirty by remember{mutableStateOf(false)};var limitsRevision by remember{mutableIntStateOf(0)}
    Text("Настройки TorrServer",fontSize=26.sp,fontWeight=FontWeight.Bold)
    PlaybackLimitsPanel(s,model,tv,onDirty={limitsDirty=it;dirty=it||torrDirty},onSaved={limitsRevision++})
    key(s.config.managedUrl){SshPanel(s,model,tv,false,settings=true,limitsRevision=limitsRevision,onDirty={torrDirty=it;dirty=it||limitsDirty})}
   }
  }
  if(page!="managed-login"&&managed.message.isNotBlank()&&!(page=="server-torr"&&managed.message=="Синхронизировано")) Text(managed.message,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
 }
 }
 BoxWithConstraints(Modifier.fillMaxSize()) {
  if((tv||maxWidth>=840.dp)&&page.startsWith("server-")&&page!="server-install") Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
   Column(Modifier.width(if(tv)240.dp else 260.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    listOf("Обзор" to "server-overview","Ресурсы" to "server-resources","Пользователи" to "server-users","TorrServer" to "server-torr","JacRed" to "server-jacred","TMDB и каталог" to "server-tmdb","История и копии" to "server-history","Обслуживание" to "server-maintenance").filter {it.second !in setOf("server-jacred","server-resources")||managed.me?.optString("role")=="admin"}.forEach {(label,destination)->Action(label,tv,Modifier.fillMaxWidth(),secondary=page!=destination){if(dirty)confirm="Отбросить несохранённые изменения?" to {dirty=false;model.page(destination)} else model.page(destination)}}
   }
   Box(Modifier.weight(1f)){BoundedPane(!tv){content()}}
  }else BoundedPane(!tv){content()}
 }
}
@Composable internal fun SelectionContainerCompat(text:String){androidx.compose.foundation.text.selection.SelectionContainer {Text(text)}}
@Composable internal fun ServerField(value:String,onChange:(String)->Unit,label:String,singleLine:Boolean=true){
 OutlinedTextField(value,onChange,label={Text(label)},singleLine=singleLine,maxLines=if(singleLine)1 else 6,modifier=Modifier.fillMaxWidth().tvFieldNavigation().semantics{contentDescription=label},shape=MaterialTheme.shapes.small)
}
@Composable internal fun ServerSecret(value:String,onChange:(String)->Unit,label:String){
 var visible by remember{mutableStateOf(false)}
 OutlinedTextField(value,onChange,label={Text(label)},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Password,autoCorrectEnabled=false),visualTransformation=if(visible)VisualTransformation.None else PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().tvFieldNavigation().semantics{contentDescription=label},shape=MaterialTheme.shapes.small,trailingIcon={IconButton(onClick={visible=!visible}){Icon(if(visible)Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(visible)"Скрыть пароль" else "Показать пароль")}})
}
@Composable private fun ServerToggle(label:String,value:Boolean,onChange:(Boolean)->Unit){SettingsToggle(label,value,onChange)}

@Composable private fun SshPanel(s:WatchState,model:WatchModel,tv:Boolean,install:Boolean,settings:Boolean=false,limitsRevision:Int=0,jacred:Boolean=false,onDirty:(Boolean)->Unit={}) {
 val keyboard=LocalSoftwareKeyboardController.current
 val context=LocalContext.current;val scope=rememberCoroutineScope();val ssh=remember{OwnerSsh(context,model.secrets)}
 val pendingKey=remember{"install.pending:${s.config.scope.ifBlank{"direct"}}"}
 var connectionOpen by rememberSaveable{mutableStateOf(false)}
 var refreshedLimits by remember{mutableIntStateOf(limitsRevision)}
 val saved=remember(s.config.scope){runCatching {JSONObject(model.secrets.get("ssh:${s.config.scope}"))}.getOrNull()}
 var host by rememberSaveable{mutableStateOf(saved?.optString("host") ?: if(settings||jacred)runCatching{endpoint(s.config.managedUrl).host}.getOrDefault("") else "")};var port by rememberSaveable{mutableStateOf(saved?.optInt("port",22)?.toString() ?: "22")};var password by remember{mutableStateOf("")};var save by remember{mutableStateOf(true)};var advanced by remember{mutableStateOf(false)}
 var ready by remember{mutableStateOf(false)}
 var jacredAdmin by remember{mutableStateOf<SshTarget?>(null)}
 var jacredDirty by remember{mutableStateOf(false)}
 var installationJson by rememberSaveable {mutableStateOf<String?>(null)}
 val progress=installationJson?.let {InstallationProgress.from(JSONObject(it))}
 fun showProgress(value:InstallationProgress){installationJson=JSONObject().put("stage",value.stage).put("state",value.state).put("code",value.code).put("updated",value.updated).toString()}
 var activeCount by remember{mutableStateOf<Int?>(null)};var restoreWarning by remember{mutableStateOf(false)}
 var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")};var trust by remember{mutableStateOf<HostKeyRequired?>(null)};var verifiedFingerprint by remember{mutableStateOf("")};var data by remember{mutableStateOf<JSONObject?>(null)};var draft by remember{mutableStateOf<JSONObject?>(null)};var confirmation by remember{mutableStateOf<(() -> Unit)?>(null)}
 fun target()=SshTarget(host.trim(),port.toIntOrNull() ?: 0,"root",password)
 fun perform(block:suspend (SshTarget)->Unit){if(busy)return;keyboard?.hide();busy=true;message="Проверяем SSH…";scope.launch {try{withContext(Dispatchers.IO){if(!install){require(ManagedApi(s.config,model.secrets).authorized("me").getString("role")=="admin");require(ssh.identity(target()).getString("serverId")==s.config.serverId){"SSH относится к другому серверу"}};block(target())}}catch(e:CancellationException){throw e}catch(e:HostKeyRequired){trust=e;message=e.message.orEmpty()}catch(e:Exception){message=when {install->installationError(e);jacred&&e is MaintenanceFailure->jacredError(e.code);else->safeError(e)};if(install&&installationJson!=null)showProgress(InstallationProgress.from(JSONObject(installationJson!!)).copy(state=if(e is IllegalArgumentException)"failed" else "unreachable",code=if(e is IllegalArgumentException)"client_validation" else "connection_error"))}finally{busy=false}}}
 suspend fun refreshTorr(target:SshTarget){
  val fresh=ssh.read(target)
  val previous=data
  val current=draft
  val updated=if(previous!=null&&current!=null)refreshTorrSettingsDraft(previous,current,fresh)
   else JSONObject(fresh.getJSONObject("settings").toString())
  val changed=torrSettingsChanges(fresh,updated).length()>0
  data=fresh;draft=updated;onDirty(changed)
  message=if(changed)"Данные сервера обновлены. Проверьте несохранённые изменения." else "Данные сервера обновлены"
 }
 LaunchedEffect(limitsRevision,busy){
  if(settings&&data!=null&&!busy&&refreshedLimits!=limitsRevision){
   refreshedLimits=limitsRevision
   data=data?.let{JSONObject(it.toString()).also{copy->copy.remove("resources");copy.remove("storage")}}
   perform{target->refreshTorr(target)}
  }
 }
 fun askConfirmation(action:()->Unit){if(busy)return;busy=true;scope.launch{try{activeCount=if(install)null else withContext(Dispatchers.IO){ManagedApi(s.config,model.secrets).authorized("admin/status").getInt("activeStreams")};confirmation=action}catch(e:Exception){message=safeError(e)}finally{busy=false}}}
 fun operation(action:String,target:String=""):String {val key="maintenance:${s.config.scope}:$action:$target";return model.secrets.get(key).ifBlank{UUID.randomUUID().toString().also{model.secrets.put(key,it)}}}
 suspend fun finishInstallation(target:SshTarget){
  var result:InstallationProgress
  do {
   result=InstallationProgress.from(ssh.status(target));showProgress(result)
   message=if(result.terminal)installationFailure(result.code) else installationLabel(JSONObject().put("stage",result.stage))
   if(result.state=="running")delay(3000)
  }while(result.state=="running")
  if(result.state!="installed")return
  showProgress(result.copy(stage="verifying",state="running"));message="Проверяем HTTPS, базу данных и TorrServer…"
  connectInstalled(target,ssh,model,save){message=it}
  model.secrets.put(pendingKey,"");ready=true;password=""
 }
 fun checkInstallation(){showProgress((progress ?: InstallationProgress()).copy(state="running",code=""));perform{target->finishInstallation(target)}}
 fun startInstallation(){
  showProgress(InstallationProgress());perform{target->
   message="Проверяем SSH и передаём подписанную поставку…"
   ssh.install(target)
   if(save)model.secrets.put(pendingKey,JSONObject().put("host",target.host).put("port",target.port).put("password",target.password).toString())
   message="Серверная задача запущена. Ожидаем первый ответ…";delay(3000);finishInstallation(target)
  }
 }
 val unlock=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){if(it.resultCode==Activity.RESULT_OK){password=if(install)runCatching{JSONObject(model.secrets.get(pendingKey)).getString("password")}.getOrDefault("") else model.secrets.get("sshPassword:${s.config.scope}");message="SSH-доступ разблокирован на этой странице"}}
 if(install&&ready){InstallationReady(tv,onOpen={model.page("home")},onTmdb={model.page("server-tmdb")},onUsers={model.page("server-users")});return}
 if(install&&progress!=null)InstallationScreen(progress,busy,message,tv,onRetry={checkInstallation()},onRestart={startInstallation()},onCancel={scope.launch{try{ssh.cancel(target());message="Остановка запрошена. Ожидаем подтверждение сервера."}catch(e:CancellationException){throw e}catch(e:Exception){message=installationError(e)}}},onEdit={installationJson=null})
 if(!install||progress==null){
 if(install){Text("Дайте SSH-доступ",fontSize=26.sp,fontWeight=FontWeight.Bold);Text("Остальное MJWatch сделает сам")}
 if(install&&model.secrets.get(pendingKey).isNotBlank())Action("Продолжить последнюю установку",tv,secondary=true,enabled=!busy){val pending=JSONObject(model.secrets.get(pendingKey));host=pending.getString("host");port=pending.getInt("port").toString();val manager=context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager;val intent=manager.createConfirmDeviceCredentialIntent("MJWatch","Разблокируйте сохранённый SSH-доступ");if(intent!=null)unlock.launch(intent)else message="Введите SSH-пароль для продолжения"}
 if(settings||jacred)SettingsGroup(tv){SettingsActionRow(Icons.Outlined.Lock,"Доступ администратора",if(data!=null)"Подключено · ${host.trim()}" else "Подключение по SSH",tv){connectionOpen=!connectionOpen}}
 if((!settings&&!jacred)||connectionOpen){
 ServerField(host,{host=it},"IP или домен сервера");if(!settings)ServerField("root",{},"Пользователь SSH");ServerSecret(password,{password=it},"Пароль SSH")
 if(!install&&saved!=null) Action("Разблокировать сохранённый SSH-доступ",tv,secondary=true){val manager=context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager;val intent=manager.createConfirmDeviceCredentialIntent("MJWatch","Разблокируйте SSH-доступ владельца");if(intent!=null)unlock.launch(intent)else message="Настройте блокировку устройства или введите SSH-пароль"}
 TextButton(modifier=Modifier.tvFocusRing(tv),onClick={advanced=!advanced}){Text("Дополнительно · SSH-порт $port")};if(advanced)ServerField(port,{port=it},"SSH-порт")
 if(!jacred)ServerToggle("Сохранить SSH-доступ на устройстве",save){save=it}
 }
 if(busy&&!jacred)LinearProgressIndicator(Modifier.fillMaxWidth())
 if(install){
  if(busy)Action("Остановить на безопасной границе",tv,secondary=true){scope.launch{try{ssh.cancel(target());message="Остановка запрошена. Проверяем фактическую стадию."}catch(e:Exception){message=safeError(e)}}}
  if(advanced)Action("Восстановить запуск по SSH",tv,secondary=true,enabled=!busy){askConfirmation{perform{target->ssh.maintenance(target,JSONObject().put("action","restart").put("operationId",operation("recovery")).put("confirmed",true));connectInstalled(target,ssh,model,save){message=it};model.secrets.put(pendingKey,"");ready=true;password=""}}}
  Action("Установить автоматически",tv,Modifier.fillMaxWidth(),enabled=!busy&&host.isNotBlank()&&password.isNotBlank()){startInstallation()}
  Action("Проверить состояние и подключиться",tv,secondary=true,enabled=!busy){showProgress(InstallationProgress());checkInstallation()}

 }else if(jacred){
  fun refresh()=perform{target->data=ssh.readJacred(target);connectionOpen=false;message=""}
  val snapshot=data
  if(connectionOpen) Action("Загрузить состояние JacRed",tv,Modifier.fillMaxWidth(),enabled=!busy&&host.isNotBlank()&&password.isNotBlank(),loading=busy){refresh()}
  if(snapshot==null&&!connectionOpen) SettingsCard("Установка и настройки",Icons.Outlined.Build,tv){
   SettingsHint("Подключите доступ администратора для установки и настройки JacRed.")
   Action("Подключить SSH",tv,Modifier.fillMaxWidth(),enabled=!busy){connectionOpen=true}
  }
  if(snapshot?.optBoolean("running")==true) {
   Action("Открыть админку",tv,Modifier.fillMaxWidth(),secondary=true,
    enabled=!busy&&!jacredDirty&&host.isNotBlank()&&password.isNotBlank(),icon=Icons.Outlined.AdminPanelSettings) {
    jacredAdmin=target()
   }
  }
  jacredAdmin?.let { adminTarget ->
   JacredAdminDialog(s.config,model.secrets,ssh,adminTarget,tv,onClose={jacredAdmin=null})
  }
  if(snapshot!=null) JacredEditor(snapshot,tv,busy,{jacredDirty=it;onDirty(it)},onRefresh={refresh()}) { next ->
   perform{target->
    data=ssh.applyJacred(target,next){stage->message=jacredStage(stage)}
    jacredDirty=false
    onDirty(false)
    message="JacRed запущен. Можно проверить поиск в настройках источника."
    model.managed.load(JACRED_OVERVIEW)
   }
  }
 }else if(settings){
  if(model.secrets.get("torrApply:${host.trim()}:${port.toIntOrNull() ?: 0}").isNotBlank())Action("Проверить результат применения",tv,secondary=true,enabled=!busy){perform{target->val pending=JSONObject(model.secrets.get("torrApply:${target.host}:${target.port}"));ssh.apply(target,pending);val current=ssh.read(target);data=current;draft=JSONObject(current.getJSONObject("settings").toString());onDirty(false);message="Результат подтверждён. Данные сервера обновлены."}}
  if(data==null||connectionOpen){
   if(connectionOpen)Action("Загрузить настройки",tv,Modifier.fillMaxWidth(),enabled=!busy&&host.isNotBlank()&&password.isNotBlank()){
    perform{target->refreshTorr(target);connectionOpen=false}
   }else SettingsCard("Кэш и сеть",Icons.Outlined.Memory,tv){
    Text("Подключите доступ администратора, чтобы загрузить настройки и рекомендации вашего сервера.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Action("Загрузить настройки",tv,Modifier.fillMaxWidth(),enabled=!busy){connectionOpen=true}
   }
  }
  data?.let{snapshot->
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(snapshot.optString("version"),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);TextButton(onClick={perform{target->refreshTorr(target)}},enabled=!busy,modifier=Modifier.tvFocusRing(tv)){Text("Обновить состояние")}}
   draft?.let{current->
    TorrServerEditor(snapshot,current,tv,onChange={next->draft=next;onDirty(torrSettingsChanges(snapshot,next).length()>0)},onDefaults={askConfirmation{perform{target->val result=ssh.apply(target,JSONObject().put("operationId",UUID.randomUUID().toString()).put("revision",snapshot.getString("revision")).put("changes",JSONObject()).put("defaults",true).put("confirmed",true));data=result;draft=JSONObject(result.getJSONObject("settings").toString());onDirty(false);message="Заводские настройки применены"}}})
    val changed=torrSettingsChanges(snapshot,current).length()>0
    Action("Сохранить настройки",tv,Modifier.fillMaxWidth(),enabled=!busy&&snapshot.optBoolean("editable")&&changed&&torrSettingsValid(snapshot,current),icon=Icons.Outlined.Check){askConfirmation{perform{target->val result=ssh.apply(target,JSONObject().put("operationId",UUID.randomUUID().toString()).put("revision",snapshot.getString("revision")).put("changes",torrSettingsChanges(snapshot,current)).put("confirmed",true));data=result;draft=JSONObject(result.getJSONObject("settings").toString());onDirty(false);message="Настройки сохранены и проверены"}}}
    if(changed)TextButton(onClick={draft=JSONObject(snapshot.getJSONObject("settings").toString());onDirty(false)},enabled=!busy,modifier=Modifier.tvFocusRing(tv)){Text("Отменить изменения")}
   }
  }

 }else{
  var archive by remember{mutableStateOf("")};var passphrase by remember{mutableStateOf("")};var ownerPassword by remember{mutableStateOf("")}
  SettingsCard("Сервисы",Icons.Outlined.Build,tv){
  Action("Перезапустить сервисы по SSH",tv,enabled=!busy){askConfirmation {perform{target->val result=ssh.maintenance(target,JSONObject().put("action","restart").put("operationId",operation("restart")).put("confirmed",true));model.secrets.put("maintenance:${s.config.scope}:restart:","");message=if(result.optBoolean("verified")) "Перезапуск и проверка подтверждены" else "Требуется проверка"}}}
  Action("Очистить кэш TorrServer",tv,secondary=true,enabled=!busy){askConfirmation {perform{target->ssh.maintenance(target,JSONObject().put("action","clear-cache").put("operationId",operation("clear-cache")).put("confirmed",true));model.secrets.put("maintenance:${s.config.scope}:clear-cache:","");message="Кэш очищен, сервисы проверены"}}}
  }
  SettingsCard("Пароль владельца",Icons.Outlined.Lock,tv){
  ServerSecret(ownerPassword,{ownerPassword=it},"Новый пароль владельца · от 10 символов")
  Action("Установить пароль владельца по SSH",tv,secondary=true,enabled=ownerPassword.length>=10&&!busy){perform{target->ssh.ownerPassword(target,s.config.userId,ownerPassword);ownerPassword="";message="Пароль владельца установлен"}}
  }
  SettingsDisclosure("Восстановление из копии","Заменяет данные сервера содержимым архива",Icons.Outlined.Restore,tv){
  SettingsHint("Потребуется парольная фраза архива. После восстановления устройствам понадобится повторный вход.")
  ServerSecret(passphrase,{passphrase=it},"Парольная фраза восстанавливаемой копии")

  ServerField(archive,{archive=it},"Имя архива на сервере, .mjwb")
  Action("Восстановить по SSH",tv,secondary=true,enabled=!busy&&passphrase.length>=16&&archive.endsWith(".mjwb")){restoreWarning=true;askConfirmation {perform{target->ssh.maintenance(target,JSONObject().put("action","restore").put("operationId",operation("restore",archive)).put("confirmed",true).put("archive",archive).put("passphrase",passphrase));passphrase="";model.secrets.put("installOperation:${target.host}:${target.port}",UUID.randomUUID().toString());connectInstalled(target,ssh,model,save){message=it};model.secrets.put(pendingKey,"");ready=true;password=""}}}
  }
 }
 }
 if(message.isNotBlank()&&(!install||progress==null))Text(message,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
 if(trust!=null)AlertDialog(onDismissRequest={trust=null},title={Text(if(trust!!.changed)"SSH host key изменился" else "Проверьте SSH host key")},text={Column{Text(trust!!.fingerprint);Text("Сверьте отпечаток с консолью провайдера. Изменение ключа может означать подмену сервера.");if(trust!!.changed)ServerField(verifiedFingerprint,{verifiedFingerprint=it},"Отпечаток из доверенной консоли")}},confirmButton={TextButton(modifier=Modifier.tvFocusRing(tv),enabled=!trust!!.changed||verifiedFingerprint==trust!!.fingerprint,onClick={ssh.trust(target(),trust!!.fingerprint);trust=null;message="Доверие сохранено. Повторите операцию."}){Text("Доверять проверенному ключу")}},dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={trust=null}){Text("Отмена")}})
 if(confirmation!=null)AlertDialog(onDismissRequest={confirmation=null},title={Text("Влияние на просмотр")},text={Text((if(restoreWarning) "Восстановление заменит данные состоянием из архива и отзовёт все сессии и приглашения. Устройствам потребуется повторный вход. " else "")+"Активных потоков по последней проверке: ${activeCount ?: "неизвестно"}. Операция может прервать видео. Продолжить?")},confirmButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={val action=confirmation;confirmation=null;restoreWarning=false;action?.invoke()}){Text("Продолжить")}},dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={confirmation=null;restoreWarning=false}){Text("Отмена")}})
}
private suspend fun connectInstalled(target:SshTarget,ssh:OwnerSsh,model:WatchModel,save:Boolean,message:(String)->Unit){
 val key="installOperation:${target.host}:${target.port}";var operation=model.secrets.get(key);if(operation.isBlank()){operation=UUID.randomUUID().toString();model.secrets.put(key,operation)}
 val bootstrap=ssh.bootstrap(target,operation);val host=if(target.host.contains(':'))"[${target.host}]" else target.host;val url="https://$host${bootstrap.getString("listen")}";val c=model.state.value.config.copy(connectionMode="managed",managedUrl=url,serverId="",userId="",demo=false)
 model.secrets.put("ca:${endpoint(url).origin()}",bootstrap.getString("ca"));val api=ManagedApi(c,model.secrets);api.saveTokens(bootstrap);val me=api.authorized("me");check(me.getString("role")=="admin"){"Сервер не подтвердил права администратора"};val status=api.authorized("admin/status");check(status.getBoolean("databaseHealthy")&&status.getBoolean("torrserverHealthy")){"HTTPS доступен, но база данных или TorrServer не прошли проверку"};api.authorized("admin/probe","POST")
 model.managed.connected(c,api.sessionTokens())
 if(save){val id=me.getString("serverId")+":"+me.getString("userId");model.secrets.put("ssh:$id",JSONObject().put("host",target.host).put("port",target.port).toString());model.secrets.put("sshPassword:$id",target.password)}
 message("Сервер готов. Приложение уже подключено.")
}

private fun installationLabel(result:JSONObject):String {val stage=when(result.optString("stage")){"checking"->"Проверяем сервер";"installing_torrserver"->"Устанавливаем TorrServer";"installing_mjwatch"->"Устанавливаем MJWatch";"securing"->"Настраиваем защиту и автозапуск";"verifying","awaiting_app_verification"->"Проверяем и подключаем приложение";"failed"->"Установка прервана";else->"Проверяем состояние"};return stage+result.optString("code").takeIf{it.isNotBlank()}?.let{" · $it"}.orEmpty()}
