package dev.mjwatch.designsystem

import android.content.Context
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class InvitationState(val link:InvitationLink?=null,val busy:Boolean=false,val message:String="")
class InvitationController(private val context:Context,private val scope:CoroutineScope,private val secrets:SecretStore,private val managed:ManagedController,private val config:()->Config) {
    private val mutable=MutableStateFlow(InvitationState())
    val state=mutable.asStateFlow()
    private var job:Job?=null
    fun open(raw:String) {
        if(state.value.busy)return
        if(state.value.busy)return
        if(state.value.busy)return
        mutable.value=try {InvitationState(link=decodeInvitationLink(raw))}catch(_:Exception){InvitationState(message="Приглашение не распознано. Попросите владельца отправить новую ссылку.")}
    }
    fun dismiss(){if(!state.value.busy){job?.cancel();mutable.value=InvitationState()}}
    fun register(password:String,confirmation:String,onConnected:()->Unit) {
        val link=state.value.link ?: return
        if(state.value.busy)return
        if(password.length<10||password!=confirmation){mutable.update {it.copy(message=if(password!=confirmation)"Пароли не совпадают" else "Придумайте пароль от 10 символов")};return}
        if(PlaybackActivity.hasActivePlayback()){mutable.update {it.copy(message="Остановите воспроизведение перед входом по приглашению")};return}
        val previous=config().profileKey
        mutable.update {it.copy(busy=true,message="")}
        job=scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val ca=invitationCa(link,secrets.get("ca:${endpoint(link.url).origin()}"))
                    ensureActive();check(config().profileKey==previous){"Аккаунт изменился. Откройте приглашение заново."}
                    val c=config().copy(connectionMode="managed",managedUrl=link.url,demo=false,serverId="",userId="")
                    val api=ManagedApi(c,secrets,ca)
                    val result=api.request("auth/accept-invite","POST",JSONObject().put("invite",link.token).put("password",password).put("device",android.os.Build.MODEL))
                    ensureActive();check(config().profileKey==previous){"Аккаунт изменился. Откройте приглашение заново."}
                    managed.connected(c,result,caOverride=ca)
                }
                mutable.value=InvitationState();onConnected()
            }catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.update {it.copy(message=if(e is javax.net.ssl.SSLException)"Не удалось подтвердить сервер приглашения. Попросите владельца обновить сервер MJWatch и отправить новую ссылку." else safeError(e))}}
            finally {mutable.update {it.copy(busy=false)}}
        }
    }
}
