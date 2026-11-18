package dev.mjwatch.core

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.*
import java.io.IOException
import java.util.UUID
import javax.net.ssl.*
import java.security.KeyStore
import java.security.cert.CertificateFactory

@Entity(tableName="syncEntity",primaryKeys=["scope","kind","entityKey"])
data class SyncEntity(val scope:String,val kind:String,val entityKey:String,val json:String,val revision:Long,val deleted:Boolean=false)
@Entity(tableName="outbox")
data class OutboxEntry(@PrimaryKey val id:String,val scope:String,val kind:String,val entityKey:String,val json:String,val baseRevision:Long,val operation:String,val serverEpoch:String,val historyEpoch:Long,val playSession:String="",val sequence:Long=0,val created:Long=System.currentTimeMillis(),val conflict:String="")
@Entity(tableName="syncCursor")
data class SyncCursor(@PrimaryKey val scope:String,val serverEpoch:String,val historyEpoch:Long,val cursor:Long=0)
@Dao interface SyncDao {
 @Query("SELECT * FROM syncEntity WHERE scope=:scope AND deleted=0") fun entities(scope:String):Flow<List<SyncEntity>>
 @Query("SELECT * FROM syncEntity WHERE scope=:scope AND kind=:kind AND entityKey=:key") suspend fun entity(scope:String,kind:String,key:String):SyncEntity?
 @Query("SELECT * FROM outbox WHERE scope=:scope ORDER BY rowid") suspend fun pending(scope:String):List<OutboxEntry>
 @Query("SELECT * FROM outbox WHERE scope=:scope ORDER BY rowid") fun observePending(scope:String):Flow<List<OutboxEntry>>
 @Query("SELECT * FROM syncCursor WHERE scope=:scope") suspend fun cursor(scope:String):SyncCursor?
 @Upsert suspend fun put(v:SyncEntity)
 @Upsert suspend fun put(v:OutboxEntry)
 @Upsert suspend fun put(v:SyncCursor)
 @Query("DELETE FROM outbox WHERE id=:id") suspend fun acknowledge(id:String)
 @Query("DELETE FROM syncEntity WHERE scope=:scope") suspend fun clearEntities(scope:String)
 @Query("DELETE FROM syncCursor WHERE scope=:scope") suspend fun clearCursor(scope:String)
 @Query("DELETE FROM outbox WHERE scope=:scope") suspend fun clearOutbox(scope:String)
 @Query("DELETE FROM outbox WHERE scope=:scope AND kind IN ('opening','progress')") suspend fun clearHistoryOutbox(scope:String)
 @Transaction suspend fun pull(scope:String,entities:List<SyncEntity>,cursor:SyncCursor,snapshot:Boolean) {
  val pending=pending(scope)
  if(snapshot) {val retained=pending.map {p->entity(scope,p.kind,p.entityKey) ?: SyncEntity(scope,p.kind,p.entityKey,p.json,p.baseRevision,p.operation=="delete")};clearEntities(scope);retained.forEach {put(it)}}
  entities.forEach {e->if(pending.none {it.kind==e.kind&&it.entityKey==e.entityKey}) put(e)}
  put(cursor)
 }
 @Transaction suspend fun acknowledged(pending:OutboxEntry,entity:SyncEntity) {acknowledge(pending.id);put(entity)}
}
/** The private CA is scoped to the managed origin; other integrations keep system trust. */
fun managedClient(c:Config,secrets:SecretStore,caOverride:String?=null):OkHttpClient {
 require(c.managed&&endpoint(c.managedUrl).scheme=="https") {"MJWatch требует HTTPS"}
 val origin=endpoint(c.managedUrl).origin()
 val builder=guardedClient(c.copy(jackett="",torrServer="",metadataProvider="none")).newBuilder()
 val ca=caOverride ?: secrets.get("ca:$origin")
 if(ca.isNotBlank()) {
  val cert=CertificateFactory.getInstance("X.509").generateCertificate(ca.byteInputStream())
  val store=KeyStore.getInstance(KeyStore.getDefaultType()).apply {load(null);setCertificateEntry("mjwatch",cert)}
  val factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(store)}
  val manager=factory.trustManagers.filterIsInstance<X509TrustManager>().single()
  val ssl=SSLContext.getInstance("TLS").apply {init(null,arrayOf(manager),null)}
  builder.sslSocketFactory(ssl.socketFactory,manager)
 }
 return builder.addInterceptor {chain -> val request=chain.request();val token=secrets.get("access:$origin:${c.scope}")
  chain.proceed(if(request.url.origin()==origin&&c.scope.isNotBlank()&&request.header("Authorization")==null&&token.isNotBlank()) request.newBuilder().header("Authorization","Bearer $token").build() else request)
 }.build()
}
class ManagedApi(val config:Config,val secrets:SecretStore,caOverride:String?=null) {
 companion object {private val refreshMutex=Mutex()}
 val client=managedClient(config,secrets,caOverride)
 val base=endpoint(config.managedUrl)
 suspend fun request(path:String,method:String="GET",body:JSONObject?=null,revision:String?=null,headers:Map<String,String> = emptyMap()):JSONObject {
  require(!path.startsWith('/')&&!path.contains(".."))
  val url=base.newBuilder().addPathSegments("api/v1/"+path.substringBefore('?')).apply {path.substringAfter('?',"").takeIf {it.isNotEmpty()}?.let {encodedQuery(it)}}.build()
  val builder=Request.Builder().url(url);if(revision!=null) builder.header("If-Match",revision);headers.forEach {builder.header(it.key,it.value)}
  builder.method(method,if(method in setOf("GET","HEAD")) null else (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
  return try {JSONObject(String(client.bytes(builder.build(),4L*1024*1024))).also {response->if(path=="me"){require(response.optInt("apiVersion",1)==1&&response.optInt("minClientApiVersion",1)<=1){"Обновите приложение для этой версии сервера"};if(config.scope.isNotBlank())require(response.getString("serverId")==config.serverId&&response.getString("userId")==config.userId){"Аккаунт изменился. Выполните вход заново."}}}} catch(e:HttpFailure) {throw e}
 }
 suspend fun downloadBackup(id:String,password:String,out:java.io.OutputStream) {
  require(id.matches(Regex("[A-Za-z0-9_-]+\\.mjwb")));authorized("me")
  val request=Request.Builder().url(base.child("api/v1/admin/backups/$id/download")).post(JSONObject().put("password",password).toString().toRequestBody("application/json".toMediaType())).build()
  client.newBuilder().callTimeout(0,java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use {response->if(!response.isSuccessful)throw HttpFailure(response.code);val input=response.body ?: throw IOException("Пустой архив");require(input.contentLength()<=2L*1024*1024*1024);input.byteStream().use {source->val buffer=ByteArray(32768);var total=0L;while(true){val n=source.read(buffer);if(n<0)break;total+=n;require(total<=2L*1024*1024*1024);out.write(buffer,0,n)}}}
 }
 private fun access()=secrets.get("access:${base.origin()}:${config.scope}")
 private suspend fun refreshIfCurrent(previous:String)=refreshMutex.withLock {
  if(access()!=previous) return@withLock
  val result=request("auth/refresh","POST",JSONObject().put("refreshToken",secrets.get("refresh:${base.origin()}:${config.scope}")));saveTokens(result)
 }
 suspend fun refresh()=refreshIfCurrent(access())
 fun saveTokens(result:JSONObject) {val key="${base.origin()}:${config.scope}";secrets.put("refresh:$key",result.getString("refreshToken"));secrets.put("access:$key",result.getString("accessToken"));secrets.put("expires:$key",(System.currentTimeMillis()+result.optLong("expiresIn",900)*1000).toString())}
 /** Preserve rotated credentials when handing a verified bootstrap session to the account scope. */
 fun sessionTokens():JSONObject {
  val key="${base.origin()}:${config.scope}"
  val remaining=((secrets.get("expires:$key").toLongOrNull() ?: 0)-System.currentTimeMillis())/1000
  return JSONObject().put("accessToken",access()).put("refreshToken",secrets.get("refresh:$key")).put("expiresIn",remaining.coerceAtLeast(0))
 }
 suspend fun authorized(path:String,method:String="GET",body:JSONObject?=null,revision:String?=null):JSONObject {
  val expiry=secrets.get("expires:${base.origin()}:${config.scope}").toLongOrNull()
  if(expiry!=null&&System.currentTimeMillis()+60000>=expiry) refresh()
  val previous=access()
  // Explicit authorization also works before /me establishes the account scope.
  suspend fun send()=request(path,method,body,revision,headers=mapOf("Authorization" to "Bearer ${access()}"))
  return try {send()} catch(e:HttpFailure) {if(e.status!=401)throw e;refreshIfCurrent(previous);send()}
 }

}
class ManagedGateway(private val api:ManagedApi,private val releases:TorznabRepository):StreamGateway {
 override suspend fun check():String {api.authorized("me");return "MJWatch: подключение проверено"}
 private fun parse(j:JSONObject):TorrentHandle {val torrent=j.getJSONObject("torrent");val files=torrent.optJSONArray("file_stats") ?: JSONArray();return TorrentHandle(j.getString("id"),(0 until files.length()).map {val f=files.getJSONObject(it);TorrentFile(f.getInt("id"),f.getString("path"),f.optLong("length"))},j.getString("hash"))}
 override suspend fun add(release:Release):TorrentHandle {val input=release.magnet?.let {validateMagnet(it);TorrentInput.Magnet(it)} ?: releases.resolve(release);val json=when(input){is TorrentInput.Magnet->JSONObject().put("magnet",input.link);is TorrentInput.File->JSONObject().put("torrent",android.util.Base64.encodeToString(input.bytes,android.util.Base64.NO_WRAP))};val bytes=when(input){is TorrentInput.Magnet->input.link.toByteArray();is TorrentInput.File->input.bytes};val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)};val key="playbackRequest:${api.config.scope}:${release.id}:$digest";val id=api.secrets.get(key).ifBlank {java.util.UUID.randomUUID().toString().also {api.secrets.put(key,it)}};json.put("requestId",id);val response=try {api.authorized("playback","POST",json)}catch(e:HttpFailure){if(e.status!=404)throw e;val next=UUID.randomUUID().toString();api.secrets.put(key,next);api.authorized("playback","POST",json.put("requestId",next))};return parse(response).also {api.secrets.put("playbackHandle:${api.config.scope}:${it.hash}",key)}}
 override suspend fun status(hash:String):TorrentHandle=try {parse(api.authorized("playback/$hash"))}catch(e:HttpFailure){if(e.status==404){val release=api.secrets.get("playbackHandle:${api.config.scope}:$hash");if(release.startsWith("playbackRequest:${api.config.scope}:"))api.secrets.put(release,"")};throw e}
 override fun stream(handle:TorrentHandle,file:TorrentFile)=StreamRequest(api.base.child("api/v1/playback/${handle.hash}/stream").newBuilder().addQueryParameter("file",file.serverFileId.toString()).build().toString())
}
class ManagedMetadata(private val api:ManagedApi):MetadataAdapter {
 private fun parse(j:JSONObject):List<Metadata> {val a=j.optJSONArray("results") ?: JSONArray();return (0 until a.length()).mapNotNull {index->val v=a.getJSONObject(index);val kind=v.optString("media_type");if(kind !in setOf("movie","tv")) null else {
  fun image(k:String)=v.optString(k).takeIf {it.startsWith("/api/v1/metadata/images/")}?.let {api.base.child(it.removePrefix("/api/v1/").let {p->"api/v1/$p"}).toString()}
  val genres=v.optJSONArray("genre_ids") ?: JSONArray()
  Metadata("tmdb:$kind:${v.getInt("id")}",v.optString(if(kind=="movie") "title" else "name"),image("poster_path"),v.optString("overview"),kind,v.optString(if(kind=="movie") "release_date" else "first_air_date").take(4).toIntOrNull(),image("backdrop_path"),(0 until genres.length()).any {genres.optInt(it)==16},v.optString("original_language"))
 }}}
 override suspend fun search(query:String)=parse(api.authorized("metadata/search?query="+java.net.URLEncoder.encode(query,"UTF-8")))
 override suspend fun home()=parse(api.authorized("metadata/home"))
 override suspend fun browse(section:CatalogSection):List<Metadata> {
  val response=api.authorized("metadata/home?category=${section.key}")
  check(section==CatalogSection.FOR_YOU||response.optString("category")==section.key){"Для разделов каталога обновите сервер MJWatch в разделе «Управление сервером»."}
  return parse(response)
 }
 override suspend fun details(id:String):Metadata? {
  require(id.matches(Regex("tmdb:(movie|tv):[0-9]+")))
  val (_,kind,number)=id.split(':')
  val item=api.authorized("metadata/$kind/$number").put("media_type",kind)
  return parse(JSONObject().put("results",JSONArray().put(item))).singleOrNull()
 }
}
class SyncEngine(private val api:ManagedApi,private val dao:SyncDao) {
 companion object {val mutex=Mutex();private val lastPush=mutableMapOf<String,Long>()}
 private val scope=api.config.scope
 suspend fun enqueue(kind:String,key:String,json:JSONObject,operation:String="put",playSession:String="",sequence:Long=0)=mutex.withLock {
  require(scope.isNotBlank());val cursor=dao.cursor(scope) ?: return@withLock
  val old=dao.entity(scope,kind,key);val pending=dao.pending(scope).filter {it.kind==kind&&it.entityKey==key}
  val base=(pending.lastOrNull()?.baseRevision?.plus(1)) ?: old?.revision ?: 0
  val effectiveOperation=if(operation=="put"&&old?.deleted==true) "restore" else operation
  val event=OutboxEntry(UUID.randomUUID().toString(),scope,kind,key,json.toString(),base,effectiveOperation,cursor.serverEpoch,cursor.historyEpoch,playSession,sequence)
  dao.put(event);dao.put(SyncEntity(scope,kind,key,json.toString(),old?.revision ?: 0,operation=="delete"))
 }
 suspend fun sync(knownMe:JSONObject?=null)=mutex.withLock {
  require(scope.isNotBlank());val cursor=dao.cursor(scope)
  if(cursor==null) {snapshot();return@withLock}
  val me=knownMe ?: api.authorized("me");if(cursor.serverEpoch!=me.getString("serverEpoch")||cursor.historyEpoch!=me.getLong("historyEpoch")){snapshot();return@withLock}
  val canPush=android.os.SystemClock.elapsedRealtime()-(lastPush[scope] ?: -15000)>=15000
  for(p in if(canPush)dao.pending(scope) else emptyList()) {
   if(p.conflict.isNotBlank()) continue
   val json=JSONObject().put("mutationId",p.id).put("kind",p.kind).put("entityKey",p.entityKey).put("baseRevision",p.baseRevision).put("operation",p.operation).put("value",JSONObject(p.json)).put("serverEpoch",p.serverEpoch).put("historyEpoch",p.historyEpoch).put("playSession",p.playSession).put("sequence",p.sequence)
   try {val result=api.authorized("mutations","POST",json);dao.acknowledged(p,entity(result.getJSONObject("entity")));lastPush[scope]=android.os.SystemClock.elapsedRealtime()}catch(e:HttpFailure){if(e.status==409){val related=dao.pending(scope).filter {it.kind==p.kind&&it.entityKey==p.entityKey};val latest=related.lastOrNull() ?: p;related.filter {it.id!=latest.id}.forEach {dao.acknowledge(it.id)};dao.put(latest.copy(conflict=e.responseBody ?: "{}"));break}else throw e}
  }
  var current=dao.cursor(scope)!!
  do {val result=api.authorized("sync?cursor=${current.cursor}&serverEpoch=${current.serverEpoch}");current=SyncCursor(scope,result.getString("serverEpoch"),result.getLong("historyEpoch"),result.getLong("cursor"));dao.pull(scope,parse(result),current,false)}while(result.optBoolean("more"))
 }
 private fun entity(j:JSONObject)=SyncEntity(scope,j.getString("kind"),j.getString("entityKey"),j.getJSONObject("value").toString(),j.getLong("revision"),j.optBoolean("deleted"))
 private fun parse(j:JSONObject):List<SyncEntity> {val a=j.getJSONArray("entities");return (0 until a.length()).map {entity(a.getJSONObject(it))}}
 suspend fun connect(){mutex.withLock {snapshot()};sync()}
 private suspend fun completeSnapshot(fresh:Boolean=false):JSONObject {var result=api.authorized(if(fresh)"snapshot?fresh=1" else "snapshot");val all=JSONArray();while(true){val rows=result.getJSONArray("entities");for(i in 0 until rows.length())all.put(rows.getJSONObject(i));if(!result.optBoolean("more"))return result.put("entities",all);result=api.authorized("snapshot?snapshotId=${result.getString("snapshotId")}&offset=${result.getInt("nextOffset")}")}}
 private suspend fun snapshot() {val result=completeSnapshot();val old=dao.cursor(scope);if(old!=null&&(old.serverEpoch!=result.getString("serverEpoch")||old.historyEpoch!=result.getLong("historyEpoch"))){val server=parse(result);dao.pending(scope).groupBy {it.kind to it.entityKey}.values.forEach {related->val pending=related.last();if(old.serverEpoch==result.getString("serverEpoch")&&pending.kind !in setOf("opening","progress")){related.forEach {dao.put(it.copy(historyEpoch=result.getLong("historyEpoch")))};return@forEach};related.dropLast(1).forEach {dao.acknowledge(it.id)};val current=server.firstOrNull {it.kind==pending.kind&&it.entityKey==pending.entityKey};val conflict=JSONObject().put("code","resync_required");if(current!=null)conflict.put("entity",JSONObject().put("value",JSONObject(current.json)));dao.put(pending.copy(conflict=conflict.toString()))}};dao.pull(scope,parse(result),SyncCursor(scope,result.getString("serverEpoch"),result.getLong("historyEpoch"),result.getLong("cursor")),true)}
 suspend fun resolve(p:OutboxEntry,useLocal:Boolean)=mutex.withLock {
  val snap=completeSnapshot(true);val server=parse(snap).firstOrNull {it.kind==p.kind&&it.entityKey==p.entityKey}
  dao.pending(scope).filter {it.kind==p.kind&&it.entityKey==p.entityKey}.forEach {dao.acknowledge(it.id)}
  val epoch=snap.getString("serverEpoch");val history=snap.getLong("historyEpoch");dao.put(SyncCursor(scope,epoch,history,snap.getLong("cursor")))
  if(useLocal) dao.put(p.copy(id=UUID.randomUUID().toString(),baseRevision=server?.revision ?: 0,serverEpoch=epoch,historyEpoch=history,operation=if(server?.deleted==true) "restore" else p.operation,conflict="")) else if(server!=null) dao.put(server) else dao.put(SyncEntity(scope,p.kind,p.entityKey,"{}",0,true))
 }
}
fun LibraryEntry.syncJson()=JSONObject().put("id",id).put("title",title).put("source",source).put("openedAt",openedAt).put("bookmarked",bookmarked).put("metadataId",metadataId)
fun Progress.syncJson()=JSONObject().put("key",key).put("releaseId",releaseId).put("fileId",fileId).put("path",path).put("positionMs",positionMs).put("durationMs",durationMs).put("updatedAt",updatedAt).put("completed",completed)
fun SyncEntity.libraryEntry():LibraryEntry {val j=JSONObject(json);return LibraryEntry(j.getString("id"),j.getString("title"),j.getString("source"),j.optLong("openedAt"),j.optBoolean("bookmarked"),metadataId=j.optString("metadataId").takeIf {it!="null"&&it.isNotBlank()})}
fun SyncEntity.progress():Progress {val j=JSONObject(json);return Progress(j.getString("key"),j.getString("releaseId"),j.getInt("fileId"),j.getString("path"),j.getLong("positionMs"),if(j.isNull("durationMs")) null else j.getLong("durationMs"),j.optLong("updatedAt"),j.optBoolean("completed"))}

/** Healthy polling stays at 30s; repeated failures back off to five minutes. */
fun syncPauseMillis(failures:Int):Long=if(failures<=0)30000L else (30000L shl failures.coerceAtMost(4)).coerceAtMost(300000L)
