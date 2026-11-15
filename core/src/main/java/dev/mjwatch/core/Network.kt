package dev.mjwatch.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun endpoint(raw: String): HttpUrl {
    val u=raw.trim().toHttpUrl()
    require(u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null) { "URL: без логина, query и fragment" }
    return u
}
fun validateTorznabPath(path:String) {
    require(path.startsWith('/')&&!path.startsWith("//")&&path.none {it in "?\\#%"}&&path.split('/').none {it==".."||it=="."}) {"Некорректный относительный Torznab path"}
}

fun HttpUrl.child(path: String): HttpUrl = newBuilder().addPathSegments(path.trimStart('/')).build()
fun HttpUrl.origin() = "$scheme://$host:$port"
private fun localAddress(a: InetAddress) = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress
/** Applied to control requests, redirects and Media3 via the same OkHttp client. No cross-origin redirects. */
fun guardedClient(config: Config): OkHttpClient {
    val allowed = (listOf(config.jackett, config.torrServer) + if(config.metadataProvider=="tmdb") listOf("https://api.themoviedb.org", "https://image.tmdb.org") else emptyList()).filter { it.isNotBlank() }.map { endpoint(it).origin() }.toSet()
    return OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .dns(object : Dns { override fun lookup(host: String): List<InetAddress> = Dns.SYSTEM.lookup(host).also { addresses ->
            if (allowed.any { it.startsWith("http://$host:") }) if(!config.localHttp || !addresses.all(::localAddress)) throw IOException("HTTP разрешён только в локальной сети")
        } })
        .addNetworkInterceptor { chain ->
            if(chain.request().url.scheme=="http") if(chain.connection()?.route()?.socketAddress?.address?.let(::localAddress)!=true) throw IOException("Публичный HTTP запрещён")
            chain.proceed(chain.request())
        }
        .addInterceptor { chain ->
            val r=chain.request(); if(r.url.origin() !in allowed) throw IOException("Не разрешённый адрес")
            if(r.url.scheme != "https" && !config.localHttp) throw IOException("Разрешите локальный HTTP в настройках")
            chain.proceed(r)
        }.build()
}
suspend fun OkHttpClient.bytes(request: Request, limit: Long = 10L*1024*1024, allowMagnetRedirect:Boolean=false): ByteArray = suspendCancellableCoroutine { c ->
    val call=newCall(request); c.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if(c.isActive) c.resumeWithException(IOException(when(e) { is java.net.UnknownHostException -> "Имя сервера не найдено. Для .local попробуйте IP-адрес"; is javax.net.ssl.SSLException -> "Не удалось проверить TLS-сертификат сервера"; is java.net.SocketTimeoutException -> "Время ожидания сервера истекло"; else -> "Сеть недоступна или соединение прервано" })) }
        override fun onResponse(call: Call, response: Response) {
            try { response.use { r ->
                val location=r.header("Location")
                if(allowMagnetRedirect&&r.code in 300..399&&location?.startsWith("magnet:?")==true) {
                    require(location.length<=65536);validateMagnet(location)
                    if(c.isActive) c.resume(location.toByteArray());return
                }
                if(!r.isSuccessful) throw IOException(when(r.code) { 401,403 -> "Доступ отклонён: проверьте ключ или пароль"; in 300..399 -> "Перенаправление запрещено; укажите конечный адрес"; else -> "Сервер ответил HTTP ${r.code}" })
                val b=r.body ?: throw IOException("Пустой ответ")
                require(b.contentLength() <= limit) { "Ответ превышает лимит" }
                val output=java.io.ByteArrayOutputStream(); b.byteStream().use { input ->
                    val buffer=ByteArray(8192); var total=0L
                    while(true) { val n=input.read(buffer); if(n<0) break; total+=n; require(total<=limit) { "Ответ превышает лимит" }; output.write(buffer,0,n) }
                }
                if(c.isActive) c.resume(output.toByteArray())
            } } catch(e: Exception) { if(c.isActive) c.resumeWithException(e) }
        }
    })
}
data class Caps(val max: Int = 50, val offset: Boolean = false, val categories: List<String> = emptyList())
object TorznabXml {
    private fun parser(xml: String): XmlPullParser {
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "DTD запрещён" }
        return XmlPullParserFactory.newInstance().apply { isNamespaceAware=true }.newPullParser().apply { setInput(StringReader(xml)) }
    }
    fun caps(xml: String): Caps {
        val p=parser(xml); var max=50; var offset=false; val categories=mutableListOf<String>()
        while(p.eventType!=XmlPullParser.END_DOCUMENT) {
            if(p.eventType==XmlPullParser.START_TAG) when(p.name) {
                "error" -> error("Torznab: проверьте API-ключ")
                "search" -> offset=p.getAttributeValue(null,"supportedParams")?.split(',')?.contains("offset")==true
                "limits" -> max=p.getAttributeValue(null,"max")?.toIntOrNull()?.coerceIn(1,50) ?: 50
                "category", "subcat" -> p.getAttributeValue(null,"name")?.let(categories::add)
            }; p.next()
        }; return Caps(max, offset, categories) // Only explicitly advertised pagination is used.
    }
    fun releases(xml: String, source: String): List<Release> {
        val p=parser(xml); val result=mutableListOf<Release>(); var item: MutableMap<String,String>?=null
        while(p.eventType!=XmlPullParser.END_DOCUMENT) {
            if(p.eventType==XmlPullParser.START_TAG) when(p.name) {
                "error" -> error("Torznab: проверьте API-ключ или доступ к источнику")
                "item" -> { require(result.size<500) { "Слишком много результатов" }; item=mutableMapOf() }
                "attr" -> { val name=p.getAttributeValue(null,"name"); val value=p.getAttributeValue(null,"value"); if(name!=null&&value!=null) item?.set(name.lowercase(), value) }
                "enclosure" -> p.getAttributeValue(null,"url")?.let { item?.set("download",it) }
                "title","guid","link","size","pubDate","category" -> if(item!=null) { val name=p.name; item[name]=p.nextText() }
            }
            if(p.eventType==XmlPullParser.END_TAG && p.name=="item") {
                val m=item!!; val title=m["title"] ?: "Без названия"; val hash=m["infohash"]?.lowercase()
                val link=m["download"] ?: m["link"]
                val identity=m["guid"] ?: hash ?: link ?: m.toSortedMap().toString()
                val id=java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") {"%02x".format(it)}
                result+=Release("$source:$id", title,source,hash,
                    m["magneturl"] ?: link?.takeIf { it.startsWith("magnet:?") },link?.takeUnless { it.startsWith("magnet:") }, m["size"]?.toLongOrNull(),m["seeders"]?.toIntOrNull())
                item=null
            }; p.next()
        }; return result.distinctBy { it.hash ?: it.id }
    }
}
class TorznabRepository(private val config: Config, private val apiKey: String, private val client: OkHttpClient) : ReleaseRepository {
    private val permits=Semaphore(3)
    private val caps=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,Caps>>()
    private val sources get()=config.indexers.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private fun url(source: String, type: String, query: String? = null): HttpUrl {
        require(Regex("[A-Za-z0-9_-]+").matches(source)) { "Некорректный ID индексатора" }
        val path=config.torznabPaths[source] ?: "/api/v2.0/indexers/$source/results/torznab/api";validateTorznabPath(path)
        return endpoint(config.jackett).child(path).newBuilder()
            .addQueryParameter("t",type).addQueryParameter("apikey",apiKey).apply { if(query!=null) addQueryParameter("q",query) }.build()
    }
    private suspend fun capabilities(source: String): Caps {
        caps[source]?.takeIf { System.currentTimeMillis()-it.first < 86_400_000 }?.let { return it.second }
        return TorznabXml.caps(String(client.bytes(Request.Builder().url(url(source,"caps")).build()))).also { caps[source]=System.currentTimeMillis() to it }
    }
    override suspend fun check(): String { require(sources.isNotEmpty()) { "Укажите ID индексаторов через запятую" }; sources.forEach { capabilities(it) }; return "Проверено источников: ${sources.size}" }
    override suspend fun search(query: String): SearchResult = searchPage(query)
    suspend fun searchPage(query:String,page:Int=0,onlySources:Set<String>?=null):SearchResult = supervisorScope {
        val selected=sources.filter {onlySources==null||it in onlySources}
        require(selected.isNotEmpty()) {"Добавьте индексаторы в настройках"}
        val more=java.util.concurrent.atomic.AtomicBoolean(false)
        val responses=java.util.concurrent.ConcurrentHashMap<String,Pair<List<Release>,String?>>()
        val jobs=selected.map {source -> async {permits.withPermit {
            val result=try {withTimeout(20_000) {
                val cap=capabilities(source)
                if(page>0&&!cap.offset) return@withTimeout Pair(emptyList<Release>(),null)
                val u=url(source,"search",query).newBuilder().addQueryParameter("limit",cap.max.toString())
                    .apply {if(page>0) addQueryParameter("offset",(page*cap.max).toString())}.build()
                val items=TorznabXml.releases(String(client.bytes(Request.Builder().url(u).build())),source)
                if(cap.offset&&items.size>=cap.max) more.set(true)
                Pair(items,null)
            }} catch(e:TimeoutCancellationException) {Pair(emptyList<Release>(),"$source: Время ожидания истекло")}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {Pair(emptyList<Release>(),"$source: ${safeError(e)}")}
            responses[source]=result
        }}}
        val complete=withTimeoutOrNull(25_000) {jobs.awaitAll();true}==true
        if(!complete) jobs.forEach {it.cancel()}
        jobs.forEach {it.join()}
        val results=selected.map {responses[it] ?: Pair(emptyList<Release>(),"$it: Общий срок поиска истёк")}
        SearchResult(results.flatMap {it.first}.distinctBy {it.hash ?: it.id},results.mapNotNull {it.second},more.get())
    }
    suspend fun resolve(release: Release): TorrentInput {
        val raw=release.download ?: error("Нет magnet или torrent")
        val u=raw.toHttpUrl(); require(u.origin()==endpoint(config.jackett).origin()) { "Torrent должен загружаться с настроенного Jackett" }
        val body=client.bytes(Request.Builder().url(u).build(),allowMagnetRedirect=true)
        if(body.firstOrNull()?.toInt()==109) {val magnet=String(body);validateMagnet(magnet);return TorrentInput.Magnet(magnet)}
        validateTorrent(body); return TorrentInput.File(body)
    }
}
fun validateMagnet(raw:String) {
    require(raw.startsWith("magnet:?")) {"Некорректный magnet"}
    val hashes=raw.substringAfter('?').split('&').map {java.net.URLDecoder.decode(it,"UTF-8")}.filter {it.startsWith("xt=urn:btih:")}.map {it.substringAfterLast(':')}
    require(hashes.any {it.matches(Regex("(?i)([0-9a-f]{40}|[a-z2-7]{32})"))}) {"Некорректный infoHash"}
}
fun validateTorrent(bytes: ByteArray) {
    require(bytes.size<=10*1024*1024&&bytes.firstOrNull()?.toInt()==100) {"Ответ не является .torrent"}
    var i=0;var info=false
    fun string():ByteArray {
        val start=i
        while(i<bytes.size&&bytes[i].toInt() in 48..57) i++
        require(i<bytes.size&&bytes[i].toInt()==58&&i>start) {"Некорректный bencode"}
        val length=String(bytes,start,i-start);require(length=="0"||!length.startsWith('0'))
        val n=length.toInt();i++;require(n>=0&&n<=bytes.size-i)
        return bytes.copyOfRange(i,i+n).also {i+=n}
    }
    fun parse(depth:Int) {
        require(depth<64&&i<bytes.size) {"Некорректный bencode"}
        when(bytes[i].toInt().toChar()) {
            'd' -> {
                i++;val keys=mutableSetOf<String>()
                while(i<bytes.size&&bytes[i].toInt()!=101) {
                    val key=string();require(key.size<=1024&&keys.add(String(key)))
                    if(depth==0&&String(key)=="info") {require(i<bytes.size&&bytes[i].toInt()==100);info=true}
                    parse(depth+1)
                }
                require(i<bytes.size);i++
            }
            'l' -> {i++;while(i<bytes.size&&bytes[i].toInt()!=101) parse(depth+1);require(i<bytes.size);i++}
            'i' -> {
                i++;val start=i;while(i<bytes.size&&bytes[i].toInt()!=101) i++
                require(i<bytes.size);val value=String(bytes,start,i-start)
                require(value.matches(Regex("0|-?[1-9][0-9]*"))&&value.toLongOrNull()!=null);i++
            }
            in '0'..'9' -> string()
            else -> error("Некорректный bencode")
        }
    }
    parse(0);require(info&&i==bytes.size) {"В torrent нет info или есть лишние данные"}
}

class TorrServerGateway(private val config: Config, private val auth: String, client: OkHttpClient,
    private val torrents: TorznabRepository) : StreamGateway {
    private val client=client.newBuilder().readTimeout(15,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()
    private fun request(path: String) = Request.Builder().url(endpoint(config.torrServer).child(path)).apply { if(auth.isNotEmpty()) header("Authorization",auth) }
    override suspend fun check() = String(client.bytes(request("echo").build(),1024)).also { require(it.startsWith("MatriX.")) { "Неизвестная версия TorrServer" } }
    private suspend fun action(json: JSONObject): TorrentHandle = parse(String(client.bytes(request("torrents").post(json.toString().toRequestBody("application/json".toMediaType())).build())))
    override suspend fun add(release: Release): TorrentHandle {
        try {
            val input=release.magnet?.let {validateMagnet(it);TorrentInput.Magnet(it)} ?: torrents.resolve(release)
            return when(input) {
                is TorrentInput.Magnet -> action(JSONObject().put("action","add").put("link",input.link).put("title",release.title).put("save_to_db",false))
                is TorrentInput.File -> {
                    val body=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file","input.torrent",input.bytes.toRequestBody("application/x-bittorrent".toMediaType())).build()
                    parse(String(client.bytes(request("torrent/upload").post(body).build())))
                }
            }
        } catch(e: CancellationException) { throw e } catch(e: Exception) {
            // An uncertain POST is never blindly retried. Resolve only a known hash.
            release.hash?.let { try { return status(it) } catch(c: CancellationException) { throw c } catch(_:Exception) {} }
            throw IOException("Добавление не подтверждено. Проверьте сервер перед повтором.")
        }
    }
    override suspend fun status(hash: String) = action(JSONObject().put("action","get").put("hash",hash))
    override fun stream(handle: TorrentHandle, file: TorrentFile): StreamRequest {
        require(file.serverFileId>0 && handle.files.any { it.serverFileId==file.serverFileId })
        val url=endpoint(config.torrServer).child("stream").newBuilder().addPathSegment(file.path.substringAfterLast('/'))
            .addQueryParameter("link",handle.hash).addQueryParameter("index",file.serverFileId.toString()).addQueryParameter("play","").build()
        return StreamRequest(url.toString(),if(auth.isEmpty()) emptyMap() else mapOf("Authorization" to auth))
    }
    companion object {
        fun parse(raw: String): TorrentHandle {
            val j=if(raw.trimStart().startsWith("[")) JSONArray(raw).also { require(it.length()==1) }.getJSONObject(0) else JSONObject(raw)
            val files=j.optJSONArray("file_stats") ?: JSONArray()
            return TorrentHandle(j.getString("hash"), (0 until files.length()).map { i -> val f=files.getJSONObject(i)
                TorrentFile(f.getInt("id"), f.getString("path"),f.optLong("length")) }.filter { it.serverFileId>0 })
        }
    }
}
fun safeError(e: Throwable): String = when(e) {
    is java.net.SocketTimeoutException, is TimeoutCancellationException -> "Время ожидания истекло"
    is IllegalArgumentException -> "Проверьте адрес, параметры и разрешение локального HTTP"
    else -> e.message?.takeIf { !it.contains(Regex("(?i)(https?://|magnet:|apikey|authorization)")) }?.take(180) ?: "Не удалось выполнить запрос"
}
