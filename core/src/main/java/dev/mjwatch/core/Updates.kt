package dev.mjwatch.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val REPOSITORY="MJSasha/mj-watch"
private const val MAX_APK=150L*1024*1024
data class AppUpdate(val versionCode:Long,val versionName:String,val packageName:String,val apkUrl:String,val sha256:String,val size:Long,val releaseUrl:String,val manifest:String)

fun parseUpdate(raw:String,packageName:String,tag:String):AppUpdate {
    require(tag.matches(Regex("test-[1-9][0-9]*")))
    val j=JSONObject(raw)
    require(j.getInt("schemaVersion")==1&&j.getString("channel")=="testing")
    val platform=when(packageName) {"dev.mjwatch.mobile"->"mobile";"dev.mjwatch.tv"->"tv";else->error("Неизвестный пакет")}
    val a=j.getJSONObject("packages").getJSONObject(platform)
    val release="https://github.com/$REPOSITORY/releases/tag/$tag"
    val apk="https://github.com/$REPOSITORY/releases/download/$tag/MJWatch-$platform.apk"
    require(j.getString("releaseUrl")==release&&a.getString("url")==apk&&a.getString("packageName")==packageName)
    val size=a.getLong("size");val sha=a.getString("sha256")
    val code=j.getLong("versionCode");val name=j.getString("versionName")
    require(code in 1..Int.MAX_VALUE.toLong()&&name.length in 1..80&&size in 1..MAX_APK&&sha.matches(Regex("[0-9a-f]{64}")))
    return AppUpdate(code,name,packageName,apk,sha,size,release,raw)
}
fun verifyUpdateBytes(file:File,update:AppUpdate) {
    require(file.length()==update.size) {"Неполный файл обновления"}
    val digest=MessageDigest.getInstance("SHA-256")
    file.inputStream().use {input->val buffer=ByteArray(32768);while(true) {val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)}}
    val actual=digest.digest().joinToString("") {"%02x".format(it)}
    require(MessageDigest.isEqual(actual.toByteArray(),update.sha256.toByteArray())) {"Контрольная сумма APK не совпала"}
}

/** Independent client: never uses Jackett/TorrServer credentials, cookies or their HTTP policy. */
class GitHubUpdates(client:OkHttpClient=OkHttpClient()) {
    private val http=client.newBuilder().followRedirects(false).followSslRedirects(false).connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(0,TimeUnit.SECONDS).build()
    private fun safeUrl(raw:String):HttpUrl=raw.toHttpUrl().also {
        require(it.scheme=="https"&&it.port==443&&it.username.isEmpty()&&it.password.isEmpty()&&it.fragment==null&&it.host in setOf("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")) {"Небезопасный адрес обновления"}
    }
    private suspend fun fetch(raw:String,limit:Long,file:File?=null,progress:(Long)->Unit={}):ByteArray {
        var url=safeUrl(raw)
        repeat(5) {
            val result=suspendCancellableCoroutine<Pair<String?,ByteArray>> {c->
                val call=http.newCall(Request.Builder().url(url).header("User-Agent","MJWatch-Updater/1").header("Accept","application/vnd.github+json, application/octet-stream").build())
                c.invokeOnCancellation {call.cancel()}
                call.enqueue(object:Callback {
                    override fun onFailure(call:Call,e:IOException) {if(c.isActive) c.resumeWithException(IOException("Не удалось загрузить обновление. Проверьте интернет и повторите"))}
                    override fun onResponse(call:Call,response:Response) {
                        try {response.use {r->
                            if(r.code in 300..399) {
                                val location=r.header("Location") ?: error("Пустой redirect")
                                val next=r.request.url.resolve(location) ?: error("Некорректный redirect")
                                safeUrl(next.toString())
                                if(c.isActive) c.resume(next.toString() to ByteArray(0));return
                            }
                            if(!r.isSuccessful) throw HttpFailure(r.code)
                            val body=r.body ?: error("Пустой ответ обновления")
                            require(body.contentLength()<=limit) {"Обновление превышает лимит"}
                            val bytes=if(file==null) java.io.ByteArrayOutputStream() else null
                            (file?.outputStream() ?: bytes!!).use {out->body.byteStream().use {input->
                                val buffer=ByteArray(32768);var total=0L
                                while(c.isActive) {val n=input.read(buffer);if(n<0) break;total+=n;require(total<=limit);out.write(buffer,0,n);progress(total)}
                            }}
                            if(c.isActive) c.resume(null to (bytes?.toByteArray() ?: ByteArray(0)))
                        }} catch(e:Exception) {if(c.isActive) c.resumeWithException(e)}
                    }
                })
            }
            if(result.first==null) return result.second
            url=safeUrl(result.first!!)
        }
        error("Слишком много перенаправлений")
    }
    suspend fun latest(packageName:String):AppUpdate?=withTimeout(60_000) {
        val raw=String(fetch("https://github.com/$REPOSITORY/releases/latest/download/update.json",65536))
        val tag=JSONObject(raw).getString("releaseUrl").substringAfterLast('/')
        parseUpdate(raw,packageName,tag)
    }
    suspend fun download(update:AppUpdate,file:File,progress:(Long)->Unit) = withTimeout(300_000) {
        fetch(update.apkUrl,update.size,file,progress)
        withContext(Dispatchers.IO) {verifyUpdateBytes(file,update)}
    }
}

@Suppress("DEPRECATION")
fun verifyUpdatePackage(context:Context,file:File,update:AppUpdate) {
    val pm=context.packageManager
    val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val installed=pm.getPackageInfo(context.packageName,flags)
    val candidate=pm.getPackageArchiveInfo(file.absolutePath,flags) ?: error("Не удалось прочитать APK")
    fun version(p:android.content.pm.PackageInfo)=if(Build.VERSION.SDK_INT>=28) p.longVersionCode else p.versionCode.toLong()
    fun signatures(p:android.content.pm.PackageInfo):Set<String> {
        val certs=if(Build.VERSION.SDK_INT>=28) p.signingInfo?.apkContentsSigners else p.signatures
        return certs.orEmpty().map {MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") {b->"%02x".format(b)}}.toSet()
    }
    require(candidate.packageName==context.packageName&&candidate.packageName==update.packageName) {"APK предназначен для другого приложения"}
    require(version(candidate)==update.versionCode&&version(candidate)>version(installed)&&candidate.versionName==update.versionName) {"Версия APK не соответствует обновлению"}
    val certs=signatures(installed)
    require(certs.isNotEmpty()&&certs==signatures(candidate)) {"Подпись APK отличается от установленного приложения"}
}
data class UpdateState(val automatic:Boolean=true,val checking:Boolean=false,val available:AppUpdate?=null,val downloading:Boolean=false,val downloaded:Long=0,val ready:File?=null,val message:String="")
class UpdateManager(private val context:Context,private val scope:CoroutineScope) {
    private val prefs=context.getSharedPreferences("updates",Context.MODE_PRIVATE)
    private val source=GitHubUpdates()
    private var job:Job?=null
    private val mutable=MutableStateFlow(UpdateState(automatic=prefs.getBoolean("automatic",true)))
    val state=mutable.asStateFlow()
    @Suppress("DEPRECATION") val installed=context.packageManager.getPackageInfo(context.packageName,0)
    @Suppress("DEPRECATION") val versionCode=if(Build.VERSION.SDK_INT>=28) installed.longVersionCode else installed.versionCode.toLong()
    val versionName=installed.versionName ?: versionCode.toString()
    init {
        runCatching {prefs.getString("manifest",null)?.let {raw->val release=JSONObject(raw).getString("releaseUrl");parseUpdate(raw,context.packageName,release.substringAfterLast('/'))}}.getOrNull()?.takeIf {it.versionCode>versionCode}?.let {cached->mutable.update {it.copy(available=cached)}}
    }
    fun automatic(enabled:Boolean) {prefs.edit().putBoolean("automatic",enabled).apply();mutable.update {it.copy(automatic=enabled)}}
    fun check(automatic:Boolean=false) {
        if(state.value.checking||state.value.downloading) return
        if(automatic&&(!state.value.automatic||System.currentTimeMillis()-prefs.getLong("lastAttempt",0)<900_000)) return
        prefs.edit().putLong("lastAttempt",System.currentTimeMillis()).apply()
        mutable.update {it.copy(checking=true,message="")}
        job=scope.launch {try {
            val found=source.latest(context.packageName)?.takeIf {it.versionCode>versionCode}
            prefs.edit().putString("manifest",found?.manifest).apply()
            mutable.update {it.copy(checking=false,available=found,ready=if(it.available==found) it.ready else null,message=if(found==null) "Обновлений нет" else "Проверка завершена")}
        } catch(_:TimeoutCancellationException) {mutable.update {it.copy(checking=false,message="Проверка обновления заняла слишком много времени. Проверьте интернет и повторите")}}
        catch(e:CancellationException) {mutable.update {it.copy(checking=false)};throw e}
        catch(e:Exception) {mutable.update {it.copy(checking=false,message=if(e is HttpFailure) "Не удалось проверить обновление: HTTP ${e.status}. Откройте GitHub Releases или повторите позже" else "Не удалось проверить обновление. Проверьте интернет или откройте GitHub Releases")}}}
    }
    fun cancel() {job?.cancel();mutable.update {it.copy(message="Загрузка отменена")}}
    fun download() {
        val update=state.value.available ?: return
        if(state.value.downloading||state.value.checking) return
        mutable.update {it.copy(downloading=true,downloaded=0,ready=null,message="")}
        job=scope.launch {
            val directory=File(context.cacheDir,"updates").apply {mkdirs()};val part=File.createTempFile("download-",".part",directory);val apk=File(directory,"update.apk")
            try {
                source.download(update,part) {bytes->mutable.update {it.copy(downloaded=bytes)}}
                withContext(Dispatchers.IO) {verifyUpdatePackage(context,part,update);check(part.renameTo(apk)) {"Не удалось сохранить APK"}}
                mutable.update {it.copy(downloading=false,ready=apk,message="APK проверен. Подтвердите установку в Android")}
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {mutable.update {it.copy(downloading=false,message=if(e is IllegalArgumentException||e is IllegalStateException) e.message?.take(150) ?: "APK не прошёл проверку" else "Загрузка не удалась. Повторите позже")}}
            finally {withContext(NonCancellable+Dispatchers.IO) {part.delete()};mutable.update {it.copy(downloading=false)}}
        }
    }
    fun readyFile():File? {
        val update=state.value.available ?: return null
        val file=state.value.ready ?: return null
        return runCatching {verifyUpdateBytes(file,update);verifyUpdatePackage(context,file,update);file}.getOrNull()
    }
}
