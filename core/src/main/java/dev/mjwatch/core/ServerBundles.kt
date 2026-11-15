package dev.mjwatch.core
import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.zip.ZipInputStream

/** Release packages carry no credentials. The trust key always comes from the signed APK. */
class ServerBundles(private val context:Context) {
 private val installed=File(context.filesDir,"server-release")
 class Source(private val root:File?,private val context:Context) {
  fun open(path:String):InputStream {require(path.matches(Regex("[a-zA-Z0-9_./-]+"))&&!path.contains("..")&&!path.startsWith('/'));return root?.resolve(path)?.inputStream() ?: context.assets.open("server/$path")}
  fun manifest()=open("manifest.json").use {JSONObject(String(it.readBytes()))}
 }
 fun active()=Source(installed.takeIf {it.isDirectory},context)
 fun useBundled(){installed.deleteRecursively()}
 fun import(input:InputStream):JSONObject {
  require(context.filesDir.usableSpace>320L*1024*1024){"Недостаточно места для серверной поставки"}
  val stage=File(context.filesDir,"server-release-${UUID.randomUUID()}").apply {check(mkdir())}
  try {
   var total=0L;var count=0
   ZipInputStream(input).use {zip->while(true){val entry=zip.nextEntry ?: break;require(++count<=64);val path=entry.name;require(path.matches(Regex("[a-zA-Z0-9_./-]+"))&&!path.contains("..")&&!path.startsWith('/'))
    val file=File(stage,path);require(file.canonicalPath.startsWith(stage.canonicalPath+File.separator));if(entry.isDirectory){file.mkdirs();continue};require(!file.exists());file.parentFile?.mkdirs();file.outputStream().use {out->val bytes=ByteArray(32768);while(true){val n=zip.read(bytes);if(n<0)break;total+=n;require(total<=256L*1024*1024){"Поставка превышает лимит"};out.write(bytes,0,n)}}
   }}
   val source=Source(stage,context);verify(source);val manifest=source.manifest();val previous=File(context.filesDir,"server-release-previous");previous.deleteRecursively();if(installed.exists())check(installed.renameTo(previous));if(!stage.renameTo(installed)){previous.renameTo(installed);error("Не удалось сохранить поставку")};previous.deleteRecursively();return manifest
  }finally{stage.deleteRecursively()}
 }
 fun verify(source:Source=active()):JSONObject {
  val manifestBytes=source.open("manifest.json").use {it.readBytes().also {data->require(data.size<=1024*1024)}}
  val signature=source.open("manifest.sig").use {it.readBytes().also {data->require(data.size<=4096)}}
  val pem=context.assets.open("server/release-public.pem").bufferedReader().use {it.readText()}
  val imported=source.open("release-public.pem").bufferedReader().use {it.readText()};require(imported.trim()==pem.trim()){"Ключ поставки не совпадает с доверенным ключом приложения"}
  val encoded=pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replace(Regex("\\s"),"")
  val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(encoded,Base64.DEFAULT)))
  require(Signature.getInstance("SHA256withRSA").apply {initVerify(key);update(manifestBytes)}.verify(signature)){"Подпись серверной поставки не прошла проверку"}
  val manifest=JSONObject(String(manifestBytes));require(manifest.getString("version").matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")));require(manifest.optInt("apiVersion",1)==1&&manifest.getInt("schemaVersion")==1&&manifest.optInt("minClientApiVersion",1)<=1){"Обновите приложение для этой версии сервера"}
  val files=manifest.getJSONArray("files");require(files.length() in 1..60)
  for(index in 0 until files.length()){val entry=files.getJSONObject(index);val digest=MessageDigest.getInstance("SHA-256");var total=0L;source.open(entry.getString("path")).use {input->val bytes=ByteArray(32768);while(true){val n=input.read(bytes);if(n<0)break;total+=n;require(total<=128L*1024*1024);digest.update(bytes,0,n)}};val actual=digest.digest().joinToString(""){"%02x".format(it)};require(actual==entry.getString("sha256")&&total==entry.getLong("size")){"Нарушена целостность серверной поставки"}}
  return manifest
 }
}
