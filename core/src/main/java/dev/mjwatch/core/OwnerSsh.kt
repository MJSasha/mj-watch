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
 suspend fun apply(target:SshTarget,body:JSONObject)=session(target){session->
  val id=body.getString("operationId");val key="torrApply:${target.host}:${target.port}";secrets.put(key,body.toString())
  fun result()=JSONObject(exec(session,"/usr/local/sbin/mjwatchctl torrserver operation",JSONObject().put("operationId",id).toString()))
  val previous=result();if(previous.optString("state")=="complete"){secrets.put(key,"");return@session previous.getJSONObject("result")}
  startOperation(session,"torr",body)
  val deadline=System.currentTimeMillis()+90000;var state:JSONObject
  do{Thread.sleep(500);state=result();if(state.optString("state")=="failed"){secrets.put(key,"");error(if(state.optString("code")=="unsupported_field")"Сервер отклонил неподдерживаемое поле. Прочитайте настройки заново и повторите применение." else "Настройки не применены: ${state.optString("code")}")};check(System.currentTimeMillis()<deadline){"Результат пока неизвестен. Проверьте состояние операции."}}while(state.optString("state")!="complete")
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
  do{Thread.sleep(1000);state=result();if(state.optString("state")=="failed")error("Операция обслуживания: ${state.optString("code")}");check(System.currentTimeMillis()<deadline){"Операция продолжается. Проверьте состояние после подключения."}}while(state.optString("state")!="complete")
  check(state.optBoolean("verified")){"Операция обслуживания не подтверждена"};return state
 }
 suspend fun restart(target:SshTarget)=session(target){exec(it,"/usr/local/sbin/mjwatchctl restart")}
 suspend fun install(target:SshTarget)=session(target){session->
  val state=exec(session,"systemctl is-active --quiet mjwatch-install.service && echo running || echo stopped")
  if(state.trim()!="running"){exec(session,"rm -f /var/lib/mjwatch-install/cancel");upload(session,target);exec(session,"systemd-run --unit=mjwatch-install --collect /bin/sh /var/lib/mjwatch-install/upload/install.sh")}
 }
 suspend fun update(target:SshTarget,passphrase:String,operation:String)=session(target){session->upload(session,target);maintenanceJob(session,JSONObject().put("action","update").put("operationId",operation).put("confirmed",true).put("passphrase",passphrase))}
 suspend fun ownerPassword(target:SshTarget,user:String,password:String)=session(target){exec(it,"/usr/local/sbin/mjwatchctl owner password",JSONObject().put("User",user).put("Password",password).toString())}
 private fun upload(session:Session,target:SshTarget) {
  val bundles=ServerBundles(context);val source=bundles.active();bundles.verify(source)
  val entries=source.manifest().getJSONArray("files")
  val paths=mutableListOf("manifest.json","manifest.sig","release-public.pem")
  for(index in 0 until entries.length())paths+=entries.getJSONObject(index).getString("path")
  exec(session,"install -d -m 700 /var/lib/mjwatch-install /var/lib/mjwatch-install/upload")
  val sftp=session.openChannel("sftp") as ChannelSftp
  try {sftp.connect(10000);for(path in paths){var dir="/var/lib/mjwatch-install/upload";for(part in path.split('/').dropLast(1)){dir+="/$part";runCatching {sftp.mkdir(dir)}};source.open(path).use {sftp.put(it,"/var/lib/mjwatch-install/upload/$path")};sftp.chmod(384,"/var/lib/mjwatch-install/upload/$path")};sftp.put(JSONObject().put("host",target.host).toString().byteInputStream(),"/var/lib/mjwatch-install/request.json");sftp.chmod(384,"/var/lib/mjwatch-install/request.json")}finally{sftp.disconnect()}
 }
}
