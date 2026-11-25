package dev.mjwatch.core
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import com.jcraft.jsch.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import android.util.Base64

class HostKeyRequired(val fingerprint:String,val changed:Boolean):java.io.IOException(if(changed) "SSH host key изменился. Сверьте отпечаток с консолью сервера." else "Подтвердите SSH host key после сверки с консолью провайдера.")
class MaintenanceFailure(val code:String):java.io.IOException("Операция обслуживания: $code")
class JacredAdminAddress(val port: Int, val cookie: String, val token: String) {
 val origin get() = "http://127.0.0.1:$port"
}
data class SshTarget(val host:String,val port:Int=22,val user:String="root",val password:String) {
 override fun toString()="SshTarget(password=redacted)"
 init {require(host.length in 1..253&&host.all {it.isLetterOrDigit()||it in ".:-"}){"Укажите IP или домен без протокола и пути"};require(port in 1..65535){"SSH-порт должен быть от 1 до 65535"};require(user=="root"){"Для установки нужен пользователь root"}}
}
class OwnerSsh(private val context:Context,private val secrets:SecretStore) {
 fun trust(target:SshTarget,fingerprint:String) {require(fingerprint.startsWith("SHA256:")&&fingerprint.length<128);secrets.put("sshHostKey:${target.host}:${target.port}",fingerprint)}
 private suspend fun <T> session(target:SshTarget,block:(Session)->T):T = runInterruptible(Dispatchers.IO) {
  var observed="";var changed=false
  val expected=secrets.get("sshHostKey:${target.host}:${target.port}")
  val jsch=JSch();jsch.hostKeyRepository=object:HostKeyRepository {
   override fun check(host:String,key:ByteArray):Int {observed="SHA256:"+Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key),Base64.NO_WRAP or Base64.NO_PADDING);changed=expected.isNotBlank()&&observed!=expected;return if(observed==expected) HostKeyRepository.OK else HostKeyRepository.CHANGED}
   override fun add(key:HostKey,ui:UserInfo?)=Unit
   override fun remove(host:String,type:String?)=Unit
   override fun remove(host:String,type:String?,key:ByteArray?)=Unit
   override fun getKnownHostsRepositoryID()="MJWatch encrypted pins"
   override fun getHostKey():Array<HostKey> = emptyArray()
   override fun getHostKey(host:String?,type:String?):Array<HostKey> = emptyArray()
  }
  val session=jsch.getSession(target.user,target.host,target.port);session.setConfig("StrictHostKeyChecking","yes");session.setConfig("PreferredAuthentications","password,keyboard-interactive");session.setPassword(target.password);session.timeout=30000
  try {session.connect(15000);return@runInterruptible block(session)}catch(e:JSchException){if(observed.isNotBlank()&&observed!=expected)throw HostKeyRequired(observed,changed);throw java.io.IOException("SSH недоступен. Проверьте адрес, пароль и разрешение root-входа.")}finally{session.disconnect()}
 }
 private fun exec(session:Session,command:String,input:String=""):String {
  val channel=session.openChannel("exec") as ChannelExec;channel.setCommand(command);channel.setInputStream(input.byteInputStream());channel.setErrStream(ByteArrayOutputStream());val stream=channel.inputStream;val output=ByteArrayOutputStream()
  try {channel.connect(10000);val b=ByteArray(8192);while(true){val n=stream.read(b);if(n<0)break;require(output.size()+n<=4*1024*1024);output.write(b,0,n)};while(!channel.isClosed)Thread.sleep(10);check(channel.exitStatus==0) {"SSH-операция не подтверждена. Перечитайте состояние сервера."};return output.toString("UTF-8")}finally{channel.disconnect()}
 }
 suspend fun identity(target:SshTarget)=session(target){JSONObject(exec(it,"/usr/local/sbin/mjwatchctl identity"))}
 suspend fun cancel(target:SshTarget)=session(target){exec(it,"touch /var/lib/mjwatch-install/cancel")}
 suspend fun read(target:SshTarget)=session(target){JSONObject(exec(it,"/usr/local/sbin/mjwatchctl torrserver settings read"))}
 private fun jacredStatus(session:Session):JSONObject {
  val path="/opt/mjwatch/current/jacred.py"
  if(exec(session,"if [ -x $path ]; then echo ready; else echo missing; fi").trim()!="ready") {
   val script=context.assets.open("jacred-repair.py").bufferedReader().use {it.readText()}
   val helper=try {
    ServerBundles(context).bundled().open("jacred.py").bufferedReader().use {it.readText()}
   }catch(_:java.io.FileNotFoundException){""}
   val quotedScript="'"+script.replace("'","'\\''")+"'"
   when(exec(session,"/usr/bin/python3 -c $quotedScript",helper).trim()) {
    "ready" -> Unit
    "update_required" -> error("Установите сервер MJWatch версии 0.1.14 или новее в разделе «Обслуживание → Обновление сервера».")
    "helper_unavailable" -> error("В поставке для установленной версии не найден проверенный файл JacRed. Проверьте серверную поставку в разделе «Обслуживание».")
    "release_changed" -> error("Версия сервера изменилась во время проверки. Загрузите состояние JacRed ещё раз.")
    else -> error("Не удалось проверить установленную поставку MJWatch. Загрузите состояние JacRed ещё раз.")
   }
  }
  return JSONObject(exec(session,"/usr/bin/python3 $path status"))
 }
 suspend fun readJacred(target:SshTarget)=session(target,::jacredStatus)
 suspend fun browseJacred(target: SshTarget, serverId: String, onReady: (JacredAdminAddress) -> Unit) =
  session(target) { connection ->
   check(serverId.isNotBlank() && JSONObject(exec(connection, "/usr/local/sbin/mjwatchctl identity"))
    .getString("serverId") == serverId) { "SSH относится к другому серверу" }
   connection.setServerAliveInterval(15_000)
   connection.serverAliveCountMax = 2
   val channel = connection.openChannel("exec") as ChannelExec
   channel.setCommand("/usr/bin/python3 /opt/mjwatch/current/jacred.py admin-session")
   val input = channel.outputStream
   val output = channel.inputStream
   try {
    channel.connect(10_000)
    val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
    while (output.available() == 0 && !channel.isClosed &&
     android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
    check(output.available() > 0) {
     if (channel.exitStatus == 64) "Обновите сервер MJ Watch для доступа к админке JacRed."
     else "Сервер не открыл админку JacRed. Проверьте состояние службы."
    }
    val ready = JSONObject(output.bufferedReader().readLine())
    check(!ready.has("error")) { "Проверьте, что серверный JacRed установлен и запущен." }
    val remotePort = ready.getInt("port")
    val cookie = ready.getString("cookie")
    val token = ready.getString("token")
    check(remotePort in 1..65535 && cookie == "mjwatch_jacred_$remotePort" &&
     token.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Сервер вернул неверные параметры админки." }
    val port = connection.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", remotePort)
    onReady(JacredAdminAddress(port, cookie, token))
    while (connection.isConnected && channel.isConnected && !channel.isClosed) Thread.sleep(250)
   } finally {
    try { runCatching { input.close() } } finally { channel.disconnect() }
   }
  }
 suspend fun applyJacred(target:SshTarget,settings:JSONObject,onStage:(String)->Unit)=session(target){connection->
  val before=jacredStatus(connection).optJSONObject("operation")
  check(before?.optString("state")!="running") {"JacRed уже настраивается. Обновите состояние после завершения."}
  val started=JSONObject(exec(connection,"/usr/bin/python3 /opt/mjwatch/current/jacred.py start",settings.toString()))
  check(started.optString("state")!="busy") {"JacRed уже настраивается. Обновите состояние после завершения."}
  check(started.optString("state")!="failed") {"Проверьте адрес синхронизации, трекеры и интервал."}
  val deadline=android.os.SystemClock.elapsedRealtime()+310_000
  var snapshot:JSONObject
  do {
   Thread.sleep(900)
   snapshot=jacredStatus(connection)
   val operation=snapshot.optJSONObject("operation")
   onStage(operation?.optString("stage").orEmpty())
   if(operation?.optString("state")=="failed") throw MaintenanceFailure(operation.optString("code"))
   check(android.os.SystemClock.elapsedRealtime()<deadline) {"Установка продолжается. Обновите состояние JacRed."}
  } while(operation?.optString("state")=="running")
  check(snapshot.optBoolean("running")) {"JacRed не запущен. Проверьте состояние сервера."}
  snapshot
 }
 suspend fun apply(target:SshTarget,body:JSONObject)=session(target){session->
  val id=body.getString("operationId");val key="torrApply:${target.host}:${target.port}";secrets.put(key,body.toString())
  fun result()=JSONObject(exec(session,"/usr/local/sbin/mjwatchctl torrserver operation",JSONObject().put("operationId",id).toString()))
  val previous=result();if(previous.optString("state")=="complete"){secrets.put(key,"");return@session previous.getJSONObject("result")}
  startOperation(session,"torr",body)
  val deadline=System.currentTimeMillis()+90000;var state:JSONObject
  do{Thread.sleep(500);state=result();if(state.optString("state")=="failed"){secrets.put(key,"");error(if(state.optString("code")=="cache_exceeds_available_memory")"Для такого RAM-кэша сейчас недостаточно свободной памяти. Перечитайте состояние и выберите рекомендованный размер." else if(state.optString("code")=="unsupported_field")"Сервер отклонил неподдерживаемое поле. Прочитайте настройки заново и повторите применение." else "Настройки не применены: ${state.optString("code")}")};check(System.currentTimeMillis()<deadline){"Результат пока неизвестен. Проверьте состояние операции."}}while(state.optString("state")!="complete")
  secrets.put(key,"");state.getJSONObject("result")
 }
 private fun startOperation(session:Session,kind:String,body:JSONObject){
  val id=body.getString("operationId");require(id.matches(Regex("[A-Za-z0-9_-]{16,128}")));exec(session,"install -d -m 700 /var/lib/mjwatch-install/requests")
  val sftp=session.openChannel("sftp") as ChannelSftp;try{sftp.connect(10000);val path="/var/lib/mjwatch-install/requests/$id.json";sftp.put(body.toString().byteInputStream(),path);sftp.chmod(384,path)}finally{sftp.disconnect()}
  exec(session,"/usr/local/sbin/mjwatchctl start operation",JSONObject().put("operationId",id).put("kind",kind).toString())
 }
 suspend fun status(target:SshTarget)=session(target){JSONObject(exec(it,"cat /var/lib/mjwatch-install/status.json"))}
 suspend fun bootstrap(target:SshTarget,operation:String)=session(target){JSONObject(exec(it,"/usr/local/sbin/mjwatchctl bootstrap",JSONObject().put("device",android.os.Build.MODEL).put("operationId",operation).toString()))}
 suspend fun maintenance(target:SshTarget,body:JSONObject)=session(target){maintenanceJob(it,body)}
 private fun maintenanceJob(session:Session,body:JSONObject):JSONObject {
  val id=body.getString("operationId")
  fun result()=JSONObject(exec(session,"/usr/local/sbin/mjwatchctl maintenance operation",JSONObject().put("operationId",id).toString()))
  val previous=result();if(previous.optBoolean("verified"))return previous
  startOperation(session,"maintenance",body)
  val deadline=System.currentTimeMillis()+300000;var state:JSONObject
  do{Thread.sleep(1000);state=result();if(state.optString("state")=="failed")throw MaintenanceFailure(state.optString("code"));check(System.currentTimeMillis()<deadline){"Операция продолжается. Проверьте состояние после подключения."}}while(state.optString("state")!="complete")
  check(state.optBoolean("verified")){"Операция обслуживания не подтверждена"};return state
 }
 suspend fun restart(target:SshTarget)=session(target){exec(it,"/usr/local/sbin/mjwatchctl restart")}
 suspend fun install(target:SshTarget)=session(target){session->
  val state=exec(session,"systemctl is-active --quiet mjwatch-install.service && echo running || echo stopped")
  if(state.trim()!="running"){exec(session,"rm -f /var/lib/mjwatch-install/cancel");upload(session,target);exec(session,"systemd-run --unit=mjwatch-install --collect /bin/sh /var/lib/mjwatch-install/upload/install.sh")}
 }
 suspend fun maintenanceStatus(target:SshTarget,operation:String)=session(target){session->
  require(operation.matches(Regex("[A-Za-z0-9_-]{16,128}")))
  JSONObject(exec(session,"/usr/local/sbin/mjwatchctl maintenance operation",JSONObject().put("operationId",operation).toString()))
 }
 suspend fun update(target:SshTarget,passphrase:String,operation:String,onProgress:(String)->Unit={})=session(target){session->
  onProgress("uploading");val manifest=upload(session,target);onProgress("applying")
  val result=maintenanceJob(session,JSONObject().put("action","update").put("operationId",operation).put("confirmed",true).put("passphrase",passphrase))
  if(manifest.optBoolean("jacredDiagnostics")) {
   try {exec(session,"/usr/bin/python3 /opt/mjwatch/current/jacred.py enable-observability")}
   catch(_:IllegalStateException){result.put("jacredDiagnosticsPending",true)}
  }
  result
 }
 suspend fun ownerPassword(target:SshTarget,user:String,password:String)=session(target){exec(it,"/usr/local/sbin/mjwatchctl owner password",JSONObject().put("User",user).put("Password",password).toString())}
 private fun upload(session:Session,target:SshTarget):JSONObject {
  val bundles=ServerBundles(context);val source=bundles.active();val manifest=bundles.verify(source)
  val entries=manifest.getJSONArray("files")
  val paths=mutableListOf("manifest.json","manifest.sig","release-public.pem")
  for(index in 0 until entries.length())paths+=entries.getJSONObject(index).getString("path")
  exec(session,"install -d -m 700 /var/lib/mjwatch-install /var/lib/mjwatch-install/upload")
  val sftp=session.openChannel("sftp") as ChannelSftp
  try {sftp.connect(10000);for(path in paths){var dir="/var/lib/mjwatch-install/upload";for(part in path.split('/').dropLast(1)){dir+="/$part";runCatching {sftp.mkdir(dir)}};source.open(path).use {sftp.put(it,"/var/lib/mjwatch-install/upload/$path")};sftp.chmod(384,"/var/lib/mjwatch-install/upload/$path")};sftp.put(JSONObject().put("host",target.host).toString().byteInputStream(),"/var/lib/mjwatch-install/request.json");sftp.chmod(384,"/var/lib/mjwatch-install/request.json")}finally{sftp.disconnect()}
  return manifest
 }
}
