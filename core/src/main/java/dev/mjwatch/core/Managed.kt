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
 @Query("SELECT * FROM syncEntity WHERE scope=:scope AND kind='progress' AND deleted=0") suspend fun activeProgress(scope:String):List<SyncEntity>
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
 @Transaction suspend fun clearProfile(scope:String) {
  clearEntities(scope)
  clearOutbox(scope)
  clearCursor(scope)
 }
 @Transaction suspend fun pull(scope:String,entities:List<SyncEntity>,cursor:SyncCursor,snapshot:Boolean) {
  val pending=pending(scope).associateBy {it.kind to it.entityKey}
  if(snapshot) {
   val retained=pending.values.map {p->entity(scope,p.kind,p.entityKey)
    ?: SyncEntity(scope,p.kind,p.entityKey,p.json,p.baseRevision,p.operation=="delete")}
   clearEntities(scope)
   retained.forEach {put(it)}
  }
  entities.forEach {e->if(e.kind to e.entityKey !in pending) put(e)}
  put(cursor)
 }
 @Transaction suspend fun applySnapshot(scope:String,entities:List<SyncEntity>,cursor:SyncCursor) {
  val server=entities.associateBy {it.kind to it.entityKey}
  for ((key,events) in pending(scope).groupBy {it.kind to it.entityKey}) {
   val newest=events.last()
   val remote=server[key]
   when {
    // Changes made before the first snapshot belong to this account, but have no known revision.
    newest.serverEpoch.isEmpty() -> {
     events.forEach {acknowledge(it.id)}
     if(newest.operation=="delete"&&(remote==null||remote.deleted)) continue
     if(remote?.let {remoteHistoryIsNewer(it,newest)}==true) continue
     put(newest.copy(baseRevision=remote?.revision ?: 0,serverEpoch=cursor.serverEpoch,historyEpoch=cursor.historyEpoch,
      conflict=if(remote?.deleted==true&&newest.operation!="restore") resyncConflict(remote) else ""))
    }
    newest.serverEpoch!=cursor.serverEpoch||newest.historyEpoch!=cursor.historyEpoch -> {
     if(newest.serverEpoch==cursor.serverEpoch&&historyTimestampField(newest.kind).isEmpty()) {
      events.forEach {put(it.copy(historyEpoch=cursor.historyEpoch))}
     } else {
      events.dropLast(1).forEach {acknowledge(it.id)}
      put(newest.copy(conflict=resyncConflict(remote)))
     }
    }
   }
  }
  pull(scope,entities,cursor,true)
 }
 @Transaction suspend fun stage(events:List<OutboxEntry>,entities:List<SyncEntity>) {
  events.forEach {put(it)}
  entities.forEach {put(it)}
 }
 @Transaction suspend fun resumeProgress(scope:String,releaseId:String,key:String):Progress? {
  // The opening may still be deleted if playback starts before the library has reopened it.
  return if(entity(scope,"opening",releaseId)?.deleted==true) null
  else entity(scope,"progress",key)?.takeUnless {it.deleted}?.progress()
 }

 @Transaction suspend fun acknowledged(event:OutboxEntry,server:SyncEntity) {
  acknowledge(event.id)
  val newest=pending(server.scope).lastOrNull {it.kind==server.kind && it.entityKey==server.entityKey}
  put(if(newest==null) server else server.copy(json=newest.json,deleted=newest.operation=="delete"))
 }

 @Transaction suspend fun resolveConflict(events:List<OutboxEntry>,body:String):OutboxEntry? {
  val latest=events.last()
  val server=latest.historyConflictServer(body)
  events.dropLast(1).forEach {acknowledge(it.id)}
  if(server==null) {
   put(latest.copy(conflict=body))
   return null
  }
  acknowledge(latest.id)
  if(remoteHistoryIsNewer(server,latest)) {
   put(server)
   return null
  }
  val retry=latest.copy(id=UUID.randomUUID().toString(),baseRevision=server.revision,conflict="")
  put(retry)
  put(server.copy(json=latest.json))
  return retry
 }

 @Transaction suspend fun resolveSnapshot(request:OutboxEntry,entities:List<SyncEntity>,cursor:SyncCursor,useLocal:Boolean) {
  val related=pending(cursor.scope).filter {it.kind==request.kind&&it.entityKey==request.entityKey}
  if(request.scope!=cursor.scope||related.none {it.id==request.id}) return
  val latest=related.last()
  val remote=entities.firstOrNull {it.kind==latest.kind&&it.entityKey==latest.entityKey}
  related.forEach {acknowledge(it.id)}
  val restore=useLocal&&(latest.operation!="delete"||remote?.deleted==false)
  if(restore) {
   val operation=if(remote?.deleted==true) "restore" else latest.operation
   val revision=remote?.revision ?: 0
   put(latest.copy(id=UUID.randomUUID().toString(),baseRevision=revision,serverEpoch=cursor.serverEpoch,
    historyEpoch=cursor.historyEpoch,operation=operation,conflict=""))
   put(SyncEntity(cursor.scope,latest.kind,latest.entityKey,latest.json,revision,latest.operation=="delete"))
  }
  applySnapshot(cursor.scope,entities,cursor)
  if(!restore&&remote==null) put(SyncEntity(cursor.scope,latest.kind,latest.entityKey,"{}",0,true))
 }
}

private fun resyncConflict(server:SyncEntity?)=JSONObject().put("code","resync_required").apply {
 if(server!=null) put("entity",JSONObject().put("value",JSONObject(server.json)))
}.toString()

private fun remoteHistoryIsNewer(server:SyncEntity,local:OutboxEntry):Boolean {
 val field=historyTimestampField(local.kind)
 if(field.isEmpty()||server.deleted||local.operation!="put") return false
 val localTime=JSONObject(local.json).optLong(field)
 return localTime>0&&JSONObject(server.json).optLong(field)>localTime
}

private fun historyTimestampField(kind:String)=when(kind) {
 "opening" -> "openedAt"
 "progress" -> "updatedAt"
 else -> ""
}

private fun OutboxEntry.historyConflictServer(body:String):SyncEntity? = runCatching {
 val timestamp=historyTimestampField(kind)
 if(timestamp.isEmpty()||operation!="put"||JSONObject(json).optLong(timestamp)<=0) return null
 val conflict=JSONObject(body)
 if(conflict.optString("code")!="conflict") return null
 val server=conflict.getJSONObject("entity").syncEntity(scope)
 server.takeIf {
  it.kind==kind&&it.entityKey==entityKey&&!it.deleted&&it.revision>0&&JSONObject(it.json).optLong(timestamp)>0
 }
}.getOrNull()

private fun JSONObject.syncEntity(scope:String)=SyncEntity(scope,getString("kind"),getString("entityKey"),
 getJSONObject("value").toString(),getLong("revision"),optBoolean("deleted"))

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

internal const val MANAGED_IMAGE_PREFIX = "/api/v1/metadata/images/"
private val managedImagePath = Regex("${MANAGED_IMAGE_PREFIX}(?:w92|w154|w185|w342|w500|w780|w1280|original)/[A-Za-z0-9_-]{1,128}\\.(?:jpg|png|webp)$")
internal fun managedArtworkPath(url:HttpUrl):String? =
    if(url.isHttps&&url.encodedQuery==null)managedImagePath.find(url.encodedPath)?.value else null

fun managedImageClient(config: Config, secrets: SecretStore): OkHttpClient {
    val base = endpoint(config.managedUrl)
    val builder = managedClient(config, secrets).newBuilder()
    // Cached artwork stores absolute URLs. Rebase recognized image paths before the origin check.
    builder.interceptors().add(0, Interceptor { chain ->
        val request = chain.request()
        val url = request.url
        val path = if (request.method == "GET" && url.origin() != base.origin()) {
            managedArtworkPath(url)
        } else null
        if (path == null) chain.proceed(request)
        else chain.proceed(request.newBuilder().url(base.child(path)).removeHeader("Authorization").build())
    })
    return builder.build()
}

class ManagedApi(val config:Config,val secrets:SecretStore,caOverride:String?=null) {
 companion object {private val refreshMutex=Mutex()}
 val client=managedClient(config,secrets,caOverride)
 private val releaseSearchClient by lazy {
  client.newBuilder().readTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
   .callTimeout(38, java.util.concurrent.TimeUnit.SECONDS).build()
 }
 val base=endpoint(config.managedUrl)
 suspend fun request(path:String,method:String="GET",body:JSONObject?=null,revision:String?=null,headers:Map<String,String> = emptyMap()):JSONObject {
  require(!path.startsWith('/')&&!path.contains(".."))
  val url=base.newBuilder().addPathSegments("api/v1/"+path.substringBefore('?')).apply {path.substringAfter('?',"").takeIf {it.isNotEmpty()}?.let {encodedQuery(it)}}.build()
  val builder=Request.Builder().url(url);if(revision!=null) builder.header("If-Match",revision);headers.forEach {builder.header(it.key,it.value)}
  builder.method(method,if(method in setOf("GET","HEAD")) null else (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
  val requestClient=if(method=="GET"&&path.substringBefore('?')=="search/releases") releaseSearchClient else client
  return try {JSONObject(String(requestClient.bytes(builder.build(),4L*1024*1024))).also {response->if(path=="me"){require(response.optInt("apiVersion",1)==1&&response.optInt("minClientApiVersion",1)<=1){"Обновите приложение для этой версии сервера"};if(config.scope.isNotBlank())require(response.getString("serverId")==config.serverId&&response.getString("userId")==config.userId){"Аккаунт изменился. Выполните вход заново."}}}} catch(e:HttpFailure) {
   throw serverMove(config,e) ?: e
  }
 }
 suspend fun followMove(move:ServerMove,settings:Settings):Boolean {
  val next=config.copy(managedUrl=move.baseUrl)
  val probe=ManagedApi(next.copy(userId=""),secrets,move.ca)
  move.verify(probe.request("server/identity"))
  return refreshMutex.withLock {
   settings.moveServer(config,move.baseUrl) {
    val oldKey="${base.origin()}:${config.scope}"
    val newKey="${probe.base.origin()}:${config.scope}"
    secrets.putAll(mapOf(
     "ca:${probe.base.origin()}" to move.ca,
     "refresh:$newKey" to secrets.get("refresh:$oldKey"),
     "access:$newKey" to secrets.get("access:$oldKey"),
     "expires:$newKey" to secrets.get("expires:$oldKey"),
     downloadSourceKey(probe.base,config.scope) to secrets.get(downloadSourceKey(base,config.scope)).ifBlank {base.toString()},
     "ssh:${config.scope}" to "",
     "sshPassword:${config.scope}" to "",
     "serverUpdate:${config.scope}" to "",
    ),removePrefix="maintenance:${config.scope}:")
   }
  }
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
 suspend fun authorized(path:String,method:String="GET",body:JSONObject?=null,revision:String?=null):JSONObject =
  withAuthorization {token->request(path,method,body,revision,headers=mapOf("Authorization" to "Bearer $token"))}

 internal suspend fun poster(path:String):ByteArray {
  val image=MANAGED_IMAGE_PREFIX+path
  require(managedImagePath.matches(image))
  return withAuthorization {token->
   client.bytes(Request.Builder().url(base.child(image)).header("Authorization","Bearer $token").build(),2L*1024*1024)
  }
 }

 internal fun searchAdminSocket(service:String,id:String?,listener:WebSocketListener):WebSocket {
  require(service in setOf("jacred","prowlarr"))
  val url=base.child("api/v1/admin/host/$service/websocket").newBuilder()
   .apply {if(id!=null)addQueryParameter("id",id)}.build()
  return client.newWebSocket(Request.Builder().url(url).header("Authorization","Bearer ${access()}").build(),listener)
 }

 internal suspend fun prowlarrTorrent(token:String):ByteArray {
  require(token.length<=8192&&token.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))) {"Некорректная ссылка на раздачу"}
  val url=base.child("api/v1/search/prowlarr-download").newBuilder().addQueryParameter("token",token).build()
  return withAuthorization {access->
   client.bytes(Request.Builder().url(url).header("Authorization","Bearer $access").build())
  }
 }

 private suspend fun <T> withAuthorization(send:suspend (String)->T):T {
  val expiry=secrets.get("expires:${base.origin()}:${config.scope}").toLongOrNull()
  if(expiry!=null&&System.currentTimeMillis()+60000>=expiry) refresh()
  val previous=access()
  return try {send(previous)} catch(e:HttpFailure) {if(e.status!=401)throw e;refreshIfCurrent(previous);send(access())}
 }

}
class ManagedGateway(private val api:ManagedApi,private val releases:TorznabRepository):StreamGateway {
 override suspend fun check():String {api.authorized("me");return "MJWatch: подключение проверено"}
 private fun parse(j:JSONObject):TorrentHandle {val torrent=j.getJSONObject("torrent");val files=torrent.optJSONArray("file_stats") ?: JSONArray();return TorrentHandle(j.getString("id"),(0 until files.length()).map {val f=files.getJSONObject(it);TorrentFile(f.getInt("id"),f.getString("path"),f.optLong("length"))},j.getString("hash"))}
 override suspend fun add(release:Release):TorrentHandle {val input=release.magnet?.let {validateMagnet(it);TorrentInput.Magnet(it)} ?: releases.resolve(release,api);val json=when(input){is TorrentInput.Magnet->JSONObject().put("magnet",input.link);is TorrentInput.File->JSONObject().put("torrent",android.util.Base64.encodeToString(input.bytes,android.util.Base64.NO_WRAP))};val bytes=when(input){is TorrentInput.Magnet->input.link.toByteArray();is TorrentInput.File->input.bytes};val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)};val key="playbackRequest:${api.config.scope}:${release.id}:$digest";val id=api.secrets.get(key).ifBlank {java.util.UUID.randomUUID().toString().also {api.secrets.put(key,it)}};json.put("requestId",id);val response=try {api.authorized("playback","POST",json)}catch(e:HttpFailure){if(e.status!=404)throw e;val next=UUID.randomUUID().toString();api.secrets.put(key,next);api.authorized("playback","POST",json.put("requestId",next))};return parse(response).also {api.secrets.put("playbackHandle:${api.config.scope}:${it.hash}",key)}}
 override suspend fun status(hash:String):TorrentHandle=try {parse(api.authorized("playback/$hash"))}catch(e:HttpFailure){if(e.status==404){val release=api.secrets.get("playbackHandle:${api.config.scope}:$hash");if(release.startsWith("playbackRequest:${api.config.scope}:"))api.secrets.put(release,"")};throw e}
 override fun stream(handle:TorrentHandle,file:TorrentFile)=StreamRequest(api.base.child("api/v1/playback/${handle.hash}/stream").newBuilder().addQueryParameter("file",file.serverFileId.toString()).build().toString())
}
class ManagedMetadata(private val api:ManagedApi):MetadataAdapter {
 private fun parse(j: JSONObject): List<Metadata> = parseItems(j.optJSONArray("results") ?: JSONArray())
 private fun parseItems(a:JSONArray):List<Metadata> {return (0 until a.length()).mapNotNull {index->val v=a.getJSONObject(index);val kind=v.optString("media_type");if(kind !in setOf("movie","tv")) null else {
  fun image(k:String)=v.optString(k).takeIf {it.startsWith("/api/v1/metadata/images/")}?.let {api.base.child(it.removePrefix("/api/v1/").let {p->"api/v1/$p"}).toString()}
  val genres=v.optJSONArray("genre_ids") ?: JSONArray()
  Metadata("tmdb:$kind:${v.getInt("id")}",v.optString(if(kind=="movie") "title" else "name"),image("poster_path"),v.optString("overview"),kind,v.optString(if(kind=="movie") "release_date" else "first_air_date").take(4).toIntOrNull(),image("backdrop_path"),(0 until genres.length()).any {genres.optInt(it)==16},v.optString("original_language"),v.primaryGenre(),v.runtimeMinutes(),tmdbLogo(v) { file ->
      file.takeIf {it.startsWith("/api/v1/metadata/images/")}?.let {api.base.child(it.removePrefix("/")).toString()}
  })
 }}}
 override suspend fun cast(id: String): List<CastMember> =
  parseCast(api.authorized("metadata/${titleCreditsPath(id)}")) { path ->
   val prefix = "/api/v1/metadata/images/w500"
   path.takeIf { it.startsWith("$prefix/") && tmdbProfilePath.matches(it.removePrefix(prefix)) }
    ?.let { api.base.child(it).toString() }
  }
 override suspend fun actorFilmography(personId: Long): List<Metadata> =
  parseItems(filmographyResults(api.authorized("metadata/${personCreditsPath(personId)}")))
 override suspend fun search(query:String)=parse(api.authorized("metadata/search?query="+java.net.URLEncoder.encode(query,"UTF-8")))
 override suspend fun home()=parse(api.authorized("metadata/home"))
 override suspend fun browse(section:CatalogSection):List<Metadata> {
  val response=api.authorized("metadata/home?category=${section.key}")
  check(section==CatalogSection.FOR_YOU||response.optString("category")==section.key){"Для разделов каталога обновите сервер MJWatch в разделе «Управление сервером»."}
  return parse(response)
 }
 override suspend fun details(id:String)=details(id,withArtwork=false)
 override suspend fun artworkDetails(id:String)=details(id,withArtwork=true)
 private suspend fun details(id:String,withArtwork:Boolean):Metadata? {
  require(id.matches(Regex("tmdb:(movie|tv):[0-9]+")))
  val (_,kind,number)=id.split(':')
  val path="metadata/$kind/$number"
  val item=if(!withArtwork) api.authorized(path) else try {
   api.authorized("$path?artwork=logos")
  } catch(e:HttpFailure) {
   if(e.status!=400&&e.status!=404) throw e
   api.authorized(path)
  }
  item.put("media_type",kind)
  return parse(JSONObject().put("results",JSONArray().put(item))).singleOrNull()
 }
}
class SyncEngine internal constructor(
 private val scope:String,private val dao:SyncDao,
 private val request:suspend (String,String,JSONObject?)->JSONObject,
) {
 constructor(api:ManagedApi,dao:SyncDao):this(api.config.scope,dao,{path,method,body->api.authorized(path,method,body)})
 companion object {
  val mutex=Mutex()
  private val networkMutex=Mutex()
  private val lastPush=mutableMapOf<String,Long>()
 }
 private suspend fun authorized(path:String,method:String="GET",body:JSONObject?=null)=request(path,method,body)
 suspend fun enqueue(kind:String,key:String,json:JSONObject,operation:String="put",playSession:String="",sequence:Long=0)=mutex.withLock {
  val old=dao.entity(scope,kind,key)
  val row=SyncEntity(scope,kind,key,json.toString(),old?.revision ?: 0)
  // Older clients deleted the opening alone. Remove any remaining positions before reopening it.
  val orphaned=if(kind=="opening"&&operation=="put"&&old?.deleted==true) progressFor(setOf(key)) else emptyList()
  stage(orphaned.map {it.copy(deleted=true)}+row.copy(deleted=operation=="delete"),operation,playSession,sequence)
 }

 suspend fun recordPlayback(opening:LibraryEntry,progress:Progress,playSession:String,sequence:Long)=mutex.withLock {
  val rows=buildList {
   if(opening.id.isNotBlank()&&dao.entity(scope,"opening",opening.id)==null) {
    add(SyncEntity(scope,"opening",opening.id,opening.syncJson().toString(),0))
   }
   add(SyncEntity(scope,"progress",progress.key,progress.syncJson().toString(),0))
  }
  stage(rows,"put",playSession,sequence)
 }

 suspend fun removeHistory(releaseIds:Set<String>):List<SyncEntity> = mutex.withLock {
  val openings=releaseIds.mapNotNull {dao.entity(scope,"opening",it)?.takeUnless {row->row.deleted}}
  val removed=openings+progressFor(releaseIds)
  stage(removed.map {it.copy(deleted=true)},"delete")
  removed
 }

 suspend fun restoreHistory(removed:List<SyncEntity>)=mutex.withLock {
  val restore=removed.filter {it.scope==scope&&dao.entity(scope,it.kind,it.entityKey)?.deleted==true}
  stage(restore,"restore")
 }

 private suspend fun progressFor(releaseIds:Set<String>)=dao.activeProgress(scope).filter {
  runCatching {it.progress().releaseId in releaseIds}.getOrDefault(false)
 }

 private suspend fun stage(rows:List<SyncEntity>,operation:String,playSession:String="",sequence:Long=0) {
  require(scope.isNotBlank())
  val cursor=dao.cursor(scope) ?: SyncCursor(scope,"",0)
  val pending=dao.pending(scope).associateBy {it.kind to it.entityKey}
  val local=rows.map {row->
   val old=dao.entity(scope,row.kind,row.entityKey)
   row.copy(revision=old?.revision ?: 0, json=if(row.deleted) "{}" else row.json) to old
  }
  val events=local.map {(row,old)->
   val base=pending[row.kind to row.entityKey]?.baseRevision?.plus(1) ?: row.revision
   val effectiveOperation=when {
    row.deleted -> "delete"
    operation=="put"&&old?.deleted==true -> "restore"
    else -> operation
   }
   OutboxEntry(UUID.randomUUID().toString(),scope,row.kind,row.entityKey,row.json,base,effectiveOperation,cursor.serverEpoch,cursor.historyEpoch,playSession,sequence)
  }
  dao.stage(events,local.map {it.first})
 }
 suspend fun sync(knownMe:JSONObject?=null)=networkMutex.withLock {
  require(scope.isNotBlank())
  val cursor=dao.cursor(scope)
  if(cursor==null) snapshot()
  else {
   val me=knownMe ?: authorized("me")
   if(cursor.serverEpoch!=me.getString("serverEpoch")||cursor.historyEpoch!=me.getLong("historyEpoch")) snapshot(fresh=true)
  }
  val canPush=android.os.SystemClock.elapsedRealtime()-(lastPush[scope] ?: -15000)>=15000
  val conflicted=mutableSetOf<Pair<String,String>>()
  for(p in if(canPush)dao.pending(scope) else emptyList()) {
   val key=p.kind to p.entityKey
   if(key in conflicted) continue
   if(p.conflict.isNotBlank()&&p.historyConflictServer(p.conflict)==null) continue
   val rejection=pushRejection(p) ?: continue
   if(rejection.requiresSnapshot()) {
    snapshot(fresh=true)
    return@withLock
   }
   conflicted.add(key)
   val retry=resolveRejection(p,rejection)
   if(retry!=null) {
    val next=pushRejection(retry) ?: continue
    if(next.requiresSnapshot()) {
     snapshot(fresh=true)
     return@withLock
    }
    resolveRejection(retry,next)
   }
  }
  var current=dao.cursor(scope)!!
  do {
   val result=try {
    authorized("sync?cursor=${current.cursor}&serverEpoch=${current.serverEpoch}")
   } catch(e:HttpFailure) {
    if(e.status!=409||e.responseCode()!="resync_required") throw e
    snapshot(fresh=true)
    return@withLock
   }
   val next=result.syncCursor()
   if(current.serverEpoch!=next.serverEpoch||current.historyEpoch!=next.historyEpoch||next.cursor<current.cursor) {
    snapshot(fresh=true)
    return@withLock
   }
   current=next
   val rows=parse(result)
   mutex.withLock {dao.pull(scope,rows,current,false)}
  } while(result.optBoolean("more"))
 }
 private suspend fun pushRejection(event:OutboxEntry):HttpFailure? = try {
  push(event)
  null
 } catch(e:HttpFailure) {
  if(e.status!=409&&e.responseCode() !in setOf("invalid_mutation","invalid_entity","entity_missing")) throw e
  e
 }
 private suspend fun resolveRejection(event:OutboxEntry,rejection:HttpFailure)=mutex.withLock {
  val related=dao.pending(scope).filter {it.kind==event.kind&&it.entityKey==event.entityKey}
  dao.resolveConflict(related,rejection.responseBody ?: "{}")
 }
 private suspend fun push(p:OutboxEntry) {
  val json=JSONObject().put("mutationId",p.id).put("kind",p.kind).put("entityKey",p.entityKey).put("baseRevision",p.baseRevision).put("operation",p.operation).put("value",JSONObject(p.json)).put("serverEpoch",p.serverEpoch).put("historyEpoch",p.historyEpoch).put("playSession",p.playSession).put("sequence",p.sequence)
  val result=authorized("mutations","POST",json)
  val entity=result.getJSONObject("entity").syncEntity(scope)
  mutex.withLock {dao.acknowledged(p,entity)}
  lastPush[scope]=android.os.SystemClock.elapsedRealtime()
 }
 private fun parse(j:JSONObject):List<SyncEntity> {val a=j.getJSONArray("entities");return (0 until a.length()).map {a.getJSONObject(it).syncEntity(scope)}}
 suspend fun connect()=sync()
 suspend fun clearCache()=networkMutex.withLock {mutex.withLock {dao.clearProfile(scope)}}
 suspend fun clearHistory():JSONObject=networkMutex.withLock {
  val result=authorized("history","DELETE")
  mutex.withLock {dao.clearHistoryOutbox(scope)}
  snapshot()
  result
 }
 private suspend fun completeSnapshot(fresh:Boolean=false):JSONObject {var result=authorized(if(fresh)"snapshot?fresh=1" else "snapshot");val all=JSONArray();while(true){val rows=result.getJSONArray("entities");for(i in 0 until rows.length())all.put(rows.getJSONObject(i));if(!result.optBoolean("more"))return result.put("entities",all);result=authorized("snapshot?snapshotId=${result.getString("snapshotId")}&offset=${result.getInt("nextOffset")}")}}
 private fun JSONObject.syncCursor()=SyncCursor(scope,getString("serverEpoch"),getLong("historyEpoch"),getLong("cursor"))
 private suspend fun snapshot(fresh:Boolean=false) {
  val result=completeSnapshot(fresh)
  val rows=parse(result)
  mutex.withLock {dao.applySnapshot(scope,rows,result.syncCursor())}
 }
 suspend fun resolve(p:OutboxEntry,useLocal:Boolean)=networkMutex.withLock {
  val result=completeSnapshot(true)
  val rows=parse(result)
  val cursor=result.syncCursor()
  mutex.withLock {dao.resolveSnapshot(p,rows,cursor,useLocal)}
 }
}
private fun HttpFailure.responseCode()=runCatching {JSONObject(responseBody ?: "{}").optString("code")}.getOrDefault("")
private fun HttpFailure.requiresSnapshot()=status==409&&responseCode() in setOf("history_epoch_changed","resync_required")
fun LibraryEntry.syncJson()=JSONObject().put("id",id).put("title",title).put("source",source).put("openedAt",openedAt).put("bookmarked",bookmarked).put("metadataId",metadataId)
fun Progress.syncJson()=JSONObject().put("key",key).put("releaseId",releaseId).put("fileId",fileId).put("path",path).put("positionMs",positionMs).put("durationMs",durationMs).put("updatedAt",updatedAt).put("completed",completed)
fun SyncEntity.libraryEntry():LibraryEntry {val j=JSONObject(json);return LibraryEntry(j.getString("id"),j.getString("title"),j.getString("source"),j.optLong("openedAt"),j.optBoolean("bookmarked"),metadataId=j.optString("metadataId").takeIf {it!="null"&&it.isNotBlank()})}
fun SyncEntity.progress():Progress {val j=JSONObject(json);return Progress(j.getString("key"),j.getString("releaseId"),j.getInt("fileId"),j.getString("path"),j.getLong("positionMs"),if(j.isNull("durationMs")) null else j.getLong("durationMs"),j.optLong("updatedAt"),j.optBoolean("completed"))}

/** Healthy polling stays at 30s; repeated failures back off to five minutes. */
fun syncPauseMillis(failures:Int):Long=if(failures<=0)30000L else (30000L shl failures.coerceAtMost(4)).coerceAtMost(300000L)
