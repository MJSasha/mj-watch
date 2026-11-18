@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.mjwatch.designsystem
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import kotlinx.coroutines.channels.Channel
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

data class ManagedState(val statusCheckedAt:Long=0,val scope:String="",val busy:Boolean=false,val message:String="",val syncMessage:String="",val me:JSONObject?=null,val data:JSONObject?=null,val pairing:JSONObject?=null,val pairingSetup:ManagedTvSetup?=null,val pending:List<OutboxEntry> = emptyList(),val syncedAt:Long=0)
class ManagedController(private val context:android.content.Context,private val scope:CoroutineScope,private val settings:Settings,private val secrets:SecretStore,private val db:WatchDatabase,private val config:()->Config) {
 private val mutable=MutableStateFlow(ManagedState());val state=mutable.asStateFlow()
 val users=UsersController(scope,secrets,config)
 val qr=TvQrController(scope,secrets,config,this)
 fun storePreferences(c:Config){val prefs=context.getSharedPreferences("playback-preferences",0);val row=JSONObject().put("metadataProvider",if(c.managed)"none" else c.metadataProvider).put("quality",prefs.getString("quality","Все")).put("audioLanguage",prefs.getString("audio-language",null)).put("subtitleLanguage",if(prefs.getBoolean("subtitles-off",false))"off" else prefs.getString("subtitle-language",null));context.getSharedPreferences("profile-playback-preferences",0).edit().putString(if(c.managed)c.scope else "direct",row.toString()).apply()}
 private suspend fun restorePreferences(c:Config){val server=if(c.historyEnabled)db.syncDao().entity(c.scope,"preferences","profile")?.takeUnless{it.deleted}?.json else null;val stored=context.getSharedPreferences("profile-playback-preferences",0).getString(if(c.managed)c.scope else "direct",null);if(!c.managed&&server==null&&stored==null)return;val value=JSONObject(server ?: stored ?: "{}");context.getSharedPreferences("playback-preferences",0).edit().putString("quality",value.optString("quality","Все")).putString("audio-language",value.optString("audioLanguage").takeIf{it.isNotBlank()&&it!="null"}).putString("subtitle-language",value.optString("subtitleLanguage").takeIf{it.isNotBlank()&&it!="null"&&it!="off"}).putBoolean("subtitles-off",value.optString("subtitleLanguage","off")=="off").apply()}
 private var syncJob:Job?=null;private var observer:Job?=null
 init {scope.launch {var previous:Config?=null;settings.config.collectLatest {c->if(previous?.scope!=c.scope||previous?.connectionMode!=c.connectionMode){previous?.let{storePreferences(it)};restorePreferences(c)};if(previous?.scope!=c.scope||previous?.managedUrl!=c.managedUrl)qr.cancelPhone();previous=c;users.reset(c.scope);syncJob?.cancel();observer?.cancel();mutable.value=ManagedState(scope=c.scope);if(c.historyEnabled) {
   observer=launch {db.syncDao().observePending(c.scope).collect {rows->if(config().scope==c.scope)mutable.update {it.copy(pending=rows)}}}
   syncJob=launch {
    val wake=Channel<Unit>(Channel.CONFLATED)
    val connectivity=context.getSystemService(ConnectivityManager::class.java)
    val callback=object:ConnectivityManager.NetworkCallback(){
     private var ready:Network?=null
     override fun onLost(network:Network){if(ready==network)ready=null}
     override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities){
      // Reachability is only a wake-up hint: local/VPN servers need no public-internet validation.
      if(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&ready!=network){ready=network;wake.trySend(Unit)}
     }
    }
    var registered=false
    try {
     try {connectivity.registerDefaultNetworkCallback(callback);registered=true}catch(_:RuntimeException){}
     var failures=0
     while(isActive) {
      val success=sync(c)
      failures=if(success)0 else (failures+1).coerceAtMost(4)
      val pause=syncPauseMillis(failures)
      withTimeoutOrNull(pause+kotlin.random.Random.nextLong(0,3001)){wake.receive()}
     }
    }finally {if(registered)runCatching{connectivity.unregisterNetworkCallback(callback)};wake.close()}
   }
 }}}}
 private fun api(c:Config=config())=ManagedApi(c,secrets)
 fun run(action:suspend ()->Unit) {if(state.value.busy)return;mutable.update {it.copy(busy=true,message="")};scope.launch {try{withContext(Dispatchers.IO){action()}}catch(e:CancellationException){throw e}catch(e:Exception){mutable.update {it.copy(message=safeError(e))}}finally{mutable.update {it.copy(busy=false)}}}}
 fun login(url:String,name:String,password:String,invite:String="")=run {
  check(!PlaybackActivity.hasActivePlayback()) {"Остановите воспроизведение перед сменой аккаунта"}
  val c=config().copy(connectionMode="managed",managedUrl=url.trim(),demo=false,serverId="",userId="")
  val api=api(c);val result=if(invite.isBlank()) api.request("auth/login","POST",JSONObject().put("name",name).put("password",password).put("device",android.os.Build.MODEL)) else api.request("auth/accept-invite","POST",JSONObject().put("invite",invite).put("password",password).put("device",android.os.Build.MODEL))
  connected(c,result)
 }
 suspend fun connected(c:Config,result:JSONObject,expectedServerId:String="",expectedUserId:String="",expectedName:String="",caOverride:String?=null) {
  check(!PlaybackActivity.hasActivePlayback()){ "Остановите воспроизведение перед сменой подключения" }
  val api=ManagedApi(c,secrets,caOverride)
  val me=api.request("me",headers=mapOf("Authorization" to "Bearer ${result.getString("accessToken")}"))
  if((expectedServerId.isNotBlank()&&me.getString("serverId")!=expectedServerId)||(expectedUserId.isNotBlank()&&me.getString("userId")!=expectedUserId)||(expectedName.isNotBlank()&&me.getString("name")!=expectedName))throw java.io.IOException("Телефон подтвердил другой аккаунт. Обновите QR и повторите вход.")
  val saved=c.copy(serverId=me.getString("serverId"),userId=me.getString("userId"),metadataProvider="tmdb")
  // Snapshot establishes epochs before any local writes; old local history is imported only explicitly.
  val savedApi=ManagedApi(saved,secrets,caOverride);savedApi.saveTokens(result);secrets.put("access:${api.base.origin()}:","");secrets.put("refresh:${api.base.origin()}:","");SyncEngine(savedApi,db.syncDao()).connect();if(caOverride!=null)secrets.put("ca:${api.base.origin()}",caOverride);settings.save(saved);settings.destination("home");mutable.update {it.copy(scope=saved.scope,me=me,data=null,message="Подключение проверено")}
 }
 fun load(path:String)=run {val api=api();val me=api.authorized("me");val data=api.authorized(path);if(config().scope!=api.config.scope)throw CancellationException("Account changed");mutable.update {it.copy(me=me,data=data,statusCheckedAt=if(path=="admin/status")System.currentTimeMillis() else it.statusCheckedAt)}}
 fun action(path:String,method:String,body:JSONObject=JSONObject(),revision:String?=null,reload:String?=null)=run {val api=api();val data=api.authorized(path,method,body,revision);if(config().scope!=api.config.scope)throw CancellationException("Account changed");if(path=="history"&&method=="DELETE"){db.syncDao().clearHistoryOutbox(config().scope);SyncEngine(api,db.syncDao()).connect()};mutable.update {it.copy(data=data,message="Операция подтверждена сервером")};if(reload!=null){val fresh=api.authorized(reload);mutable.update {it.copy(data=fresh)}}}
 fun logout(clearCache:Boolean)=run {
  check(!PlaybackActivity.hasActivePlayback()) {"Остановите воспроизведение перед выходом"}
  val c=config();syncJob?.cancel();observer?.cancel();runCatching {api(c).authorized("auth/logout","POST")};val origin=endpoint(c.managedUrl).origin();secrets.put("access:$origin:${c.scope}","");secrets.put("refresh:$origin:${c.scope}","")
  if(clearCache) {SyncEngine.mutex.lock();try{db.syncDao().clearEntities(c.scope);db.syncDao().clearOutbox(c.scope);db.syncDao().clearCursor(c.scope)}finally{SyncEngine.mutex.unlock()}}
  settings.save(c.copy(connectionMode="direct",serverId="",userId=""));mutable.value=ManagedState(message="Вы вышли из аккаунта")
 }
 fun direct(deleteHistory:Boolean)=run {check(!PlaybackActivity.hasActivePlayback()) {"Остановите воспроизведение перед сменой режима"};val c=config();if(c.managed){syncJob?.cancel();val origin=endpoint(c.managedUrl).origin();runCatching {api(c).authorized("auth/logout","POST")};secrets.put("access:$origin:${c.scope}","");secrets.put("refresh:$origin:${c.scope}","")};if(deleteHistory)db.dao().clearLegacyHistory();settings.handleLegacyHistory();val provider=if(c.managed)JSONObject(context.getSharedPreferences("profile-playback-preferences",0).getString("direct","{}") ?: "{}").optString("metadataProvider","none") else c.metadataProvider;settings.save(c.copy(connectionMode="direct",serverId="",userId="",metadataProvider=provider));mutable.value=ManagedState()}
 suspend fun record(c:Config,kind:String,key:String,json:JSONObject,operation:String="put") {if(!c.historyEnabled||config().scope!=c.scope)return;try {SyncEngine(api(c),db.syncDao()).enqueue(kind,key,json,operation)}catch(e:Exception){mutable.update {it.copy(message=safeError(e))}}}
 private fun installationId():String {val prefs=context.getSharedPreferences("device-installation",0);return prefs.getString("id",null) ?: java.util.UUID.randomUUID().toString().also {prefs.edit().putString("id",it).commit()}}
 private suspend fun sync(c:Config):Boolean {try {val api=api(c);val me=api.authorized("me");runCatching {api.authorized("devices/self/profile","PUT",JSONObject().apply {if(me.optBoolean("deviceInstallationIdentity"))put("installationId",installationId())}.put("type",if(context.packageName.endsWith(".tv"))"tv" else if(context.resources.configuration.smallestScreenWidthDp>=600)"tablet" else "phone"))};SyncEngine(api,db.syncDao()).sync(me);if(config().scope!=c.scope)return false;restorePreferences(c);mutable.update {it.copy(me=me,syncedAt=System.currentTimeMillis(),syncMessage=if(it.pending.any {p->p.conflict.isNotBlank()}) "Есть конфликт" else if(it.pending.isEmpty()) "Синхронизировано" else "Сохранено на устройстве")};return true}catch(e:CancellationException){throw e}catch(e:Exception){if(config().scope==c.scope)mutable.update {it.copy(syncMessage=safeError(e))};return false}}
 fun downloadBackup(id:String,password:String,out:java.io.OutputStream)=run {out.use {api().downloadBackup(id,password,it)};mutable.update {it.copy(message="Зашифрованный архив скачан")}}
 fun syncNow()=run {sync(config())}
 fun resolve(p:OutboxEntry,useLocal:Boolean)=run {SyncEngine(api(),db.syncDao()).resolve(p,useLocal);sync(config())}
 fun importLegacy()=run {val c=config();require(c.historyEnabled);val engine=SyncEngine(api(c),db.syncDao());db.dao().library().first().filter {it.openedAt>0}.forEach {engine.enqueue("opening",it.id,it.syncJson())};db.dao().library().first().filter {it.bookmarked}.forEach {engine.enqueue("favorite",it.id,it.syncJson())};db.dao().progress().first().forEach {engine.enqueue("progress",it.key,it.syncJson())};engine.sync();mutable.update {it.copy(message="Импорт завершён")}}
 private var pairingJob:Job?=null
 private var pairingGeneration=0
 fun importTvSetup(uri:android.net.Uri) {
  if(state.value.busy)return
  cancelPairing()
  val generation=pairingGeneration
  mutable.update {it.copy(busy=true,message="",pairingSetup=null)}
  pairingJob=scope.launch(Dispatchers.IO) {try {
  val raw=context.contentResolver.openInputStream(uri)?.use {input->
   val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
   while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=65536){"Файл слишком большой"};out.write(buffer,0,n)}
   out.toString("UTF-8")
  } ?: error("Не удалось открыть файл настроек")
  val setup=try{decodeManagedTvSetup(raw)}catch(e:Exception){throw java.io.IOException("Файл настроек TV не распознан. Сохраните его заново на телефоне.")}
  ensureActive()
  mutable.update {it.copy(pairingSetup=setup)}
  }catch(e:CancellationException){throw e}
  catch(e:Exception){if(generation==pairingGeneration)mutable.update {it.copy(message=safeError(e))}}
  finally {if(generation==pairingGeneration)mutable.update {it.copy(busy=false)}}}
 }
 fun dismissTvSetup(){mutable.update {it.copy(pairingSetup=null)}}
 fun confirmTvSetup() {
  val setup=state.value.pairingSetup ?: return
  secrets.put("ca:${endpoint(setup.url).origin()}",setup.ca)
  dismissTvSetup();startPairing(setup.url)
 }
 fun exportTvSetup(uri:android.net.Uri)=run {
  val c=config();require(c.managed)
  val raw=encodeManagedTvSetup(c.managedUrl,secrets.get("ca:${endpoint(c.managedUrl).origin()}"))
  context.contentResolver.openOutputStream(uri)?.use {it.write(raw.toByteArray(Charsets.UTF_8))} ?: error("Не удалось сохранить файл")
  mutable.update {it.copy(message="Настройки TV сохранены. Перенесите файл на телевизор и выберите «Импортировать настройки».")}
 }
 fun startPairing(url:String,expectedServerId:String="",expectedUserId:String="",expectedName:String="") {
  if(state.value.busy)return
  cancelPairing()
  val generation=pairingGeneration
  mutable.update {it.copy(busy=true,message="",pairing=null)}
  pairingJob=scope.launch(Dispatchers.IO) {
   try {
    check(!PlaybackActivity.hasActivePlayback()) {"Остановите воспроизведение перед сменой подключения"}
    val c=config().copy(connectionMode="managed",managedUrl=url.trim(),demo=false,serverId="",userId="")
    val api=api(c)
    val result=api.request("pairing","POST",JSONObject().put("device",android.os.Build.MODEL))
    mutable.update {it.copy(pairing=result,busy=false)}
    val expires=android.os.SystemClock.elapsedRealtime()+result.getInt("expiresIn")*1000L
    while(isActive&&android.os.SystemClock.elapsedRealtime()<expires) {
     delay(minOf(3000L,(expires-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1)))
     if(android.os.SystemClock.elapsedRealtime()>=expires)break
     val response=api.request("pairing/${result.getString("id")}",headers=mapOf("X-Pairing-Secret" to result.getString("pollSecret")))
     if(response.has("accessToken")) {
      mutable.update {it.copy(busy=true)}
      connected(c,response,expectedServerId,expectedUserId,expectedName)
      return@launch
     }
    }
    mutable.update {it.copy(message="Код истёк. Получите новый код и введите его на телефоне.")}
   }catch(e:CancellationException){throw e}
   catch(e:Exception){if(generation==pairingGeneration)mutable.update {it.copy(message=if(e.message?.contains("TLS")==true) "Не удалось проверить сертификат сервера. Проверьте адрес сервера на телефоне и обновите QR на TV." else safeError(e))}}
   finally {if(generation==pairingGeneration)mutable.update {it.copy(pairing=null,busy=false)}}
  }
 }
 fun cancelPairing(clearMessage:Boolean=false){pairingGeneration++;pairingJob?.cancel();pairingJob=null;mutable.update {it.copy(pairing=null,busy=false,message=if(clearMessage)"" else it.message)}}
}
