package dev.mjwatch.designsystem

import dev.mjwatch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** Short-lived QR state is never written to Settings, saved state, or the clipboard. */
data class TvQrState(val link:TvQrLink?=null,val proposal:TvQrProposal?=null,val accepted:Boolean=false,val message:String="")
private class TvQrFailure(message:String):java.io.IOException(message)
data class PhoneQrState(val stage:String="",val device:String="",val account:String="",val code:String="",val scope:String="",val message:String="")
class TvQrController(
    private val scope:CoroutineScope,
    private val secrets:SecretStore,
    private val config:()->Config,
    private val managed:ManagedController
) {
    private val tvMutable=MutableStateFlow(TvQrState());val tv=tvMutable.asStateFlow()
    private val phoneMutable=MutableStateFlow(PhoneQrState());val phone=phoneMutable.asStateFlow()
    @Volatile private var server:TvQrServer?=null
    private var startJob:Job?=null
    private var phoneJob:Job?=null
    @Volatile private var activePhone:TvQrPhone?=null
    @Volatile private var tvGeneration=0
    @Volatile private var phoneGeneration=0
    init {scope.coroutineContext[Job]?.invokeOnCompletion {server?.close()}}
    fun startTv() {
        stopTv();val generation=tvGeneration
        startJob=scope.launch(Dispatchers.IO) {
            try {
                val fresh=TvQrServer(tvQrLocalAddress(),onSetup={proposal->
                    scope.launch {if(generation==tvGeneration)tvMutable.update {it.copy(proposal=proposal)}}
                },status={
                    val state=managed.state.value;val local=tv.value
                    when {
                        generation!=tvGeneration->JSONObject().put("stage","cancelled")
                        state.pairing!=null->JSONObject().put("stage","phone").put("code",state.pairing.getString("code")).put("device",android.os.Build.MODEL)
                        local.accepted&&state.message.isNotBlank()->JSONObject().put("stage","error").put("message",state.message)
                        local.accepted->JSONObject().put("stage","connecting")
                        else->JSONObject().put("stage","confirm")
                    }
                },expired={
                    scope.launch {if(generation==tvGeneration){managed.cancelPairing();tvMutable.value=TvQrState(message="QR истёк. Нажмите «Обновить QR» и отсканируйте новый код.")}}
                },cancelled={scope.launch {if(generation==tvGeneration)startTv()}})
                try {
                    withContext(Dispatchers.Main.immediate) {
                        if(generation==tvGeneration&&isActive){server=fresh;tvMutable.update {it.copy(link=fresh.link)}}
                    }
                }finally{if(server!==fresh)fresh.close()}
            }catch(e:CancellationException){throw e}
            catch(e:Exception){withContext(Dispatchers.Main.immediate){if(generation==tvGeneration)tvMutable.value=TvQrState(message="Не удалось открыть подключение по QR. Подключите TV к домашней сети и повторите.")}}
        }
    }
    fun stopTv() {
        tvGeneration++;startJob?.cancel();startJob=null;server?.close();server=null
        managed.cancelPairing(clearMessage=true);tvMutable.value=TvQrState()
    }
    fun confirmTv() {
        val proposal=tv.value.proposal ?: return
        if(server?.active!=true||managed.state.value.busy)return
        secrets.put("ca:${endpoint(proposal.setup.url).origin()}",proposal.setup.ca)
        tvMutable.update {it.copy(proposal=null,accepted=true)}
        managed.startPairing(proposal.setup.url,proposal.serverId,proposal.userId,proposal.account)
    }
    fun cameraDenied() {
        cancelPhone()
        phoneMutable.value=PhoneQrState(stage="error",message="Для сканирования QR разрешите MJWatch доступ к камере в настройках Android и повторите.")
    }
    fun scan(raw:String) {
        cancelPhone();val generation=phoneGeneration;val c=config()
        val link=try{TvQrLink.decode(raw)}catch(e:Exception){phoneMutable.value=PhoneQrState(stage="error",message="Это не QR подключения MJWatch. Отсканируйте QR с экрана телевизора.");return}
        if(!c.historyEnabled){phoneMutable.value=PhoneQrState(stage="error",message="Сначала войдите на сервер MJWatch на телефоне.");return}
        phoneMutable.value=PhoneQrState(stage="sending",scope=c.scope)
        phoneJob=scope.launch(Dispatchers.IO) {
            try {
                val api=ManagedApi(c,secrets);val me=api.authorized("me")
                ensureCurrent(c,generation)
                val client=TvQrPhone(link)
                withContext(Dispatchers.Main.immediate){ensureCurrent(c,generation);activePhone=client}
                val setup=encodeManagedTvSetup(c.managedUrl,secrets.get("ca:${endpoint(c.managedUrl).origin()}"))
                var result=client.send(setup,me.getString("name"),me.getString("serverId"),me.getString("userId"))
                val deadline=android.os.SystemClock.elapsedRealtime()+300000
                while(isActive&&android.os.SystemClock.elapsedRealtime()<deadline) {
                    ensureCurrent(c,generation)
                    when(result.getString("stage")) {
                        "phone"->{
                            val code=result.getString("code");require(code.matches(Regex("[0-9]{6}")))
                            val preview=api.authorized("pairing/lookup","POST",JSONObject().put("code",code)).getJSONObject("pairing")
                            ensureCurrent(c,generation)
                            publishPhone(c,generation,PhoneQrState(stage="preview",device=preview.getString("device"),account=me.getString("name"),code=code,scope=c.scope))
                            return@launch
                        }
                        "confirm","connecting"->publishPhone(c,generation,PhoneQrState(stage="waiting",account=me.getString("name"),scope=c.scope))
                        "error"->throw TvQrFailure("TV не смог проверить подключение к серверу. Проверьте адрес и сертификат на телефоне и обновите QR на TV.")
                        else->throw TvQrFailure("Подключение отменено. Обновите QR на телевизоре.")
                    }
                    delay(1000);result=client.status()
                }
                throw TvQrFailure("QR истёк. Обновите его на телевизоре и отсканируйте снова.")
            }catch(e:CancellationException){throw e}
            catch(e:Exception){publishPhone(c,generation,PhoneQrState(stage="error",scope=c.scope,message=when(e){is TvQrFailure->e.message.orEmpty();is HttpFailure->if(e.status in setOf(403,409,410))"QR истёк или уже используется. Обновите QR на TV." else safeError(e);else->"Не удалось связаться с TV. Подключите телефон и TV к одной сети Wi-Fi, отключите VPN и отсканируйте новый QR."}))}
        }
    }
    private fun ensureCurrent(c:Config,generation:Int){if(generation!=phoneGeneration||config().scope!=c.scope||config().managedUrl!=c.managedUrl||!config().historyEnabled)throw CancellationException("Account or QR changed")}
    private suspend fun publishPhone(c:Config,generation:Int,value:PhoneQrState)=withContext(Dispatchers.Main.immediate) {
        ensureCurrent(c,generation);if(value.stage=="approved")activePhone=null;phoneMutable.value=value
    }
    fun approvePhone(expected:PhoneQrState) {
        val preview=phone.value;val c=config()
        if(preview!=expected||preview.stage!="preview"||preview.scope!=c.scope)return
        val generation=phoneGeneration
        val client=activePhone ?: return
        phoneMutable.update {it.copy(stage="approving")}
        phoneJob=scope.launch(Dispatchers.IO) {
            try {
                ensureCurrent(c,generation)
                val status=client.status()
                require(status.optString("stage")=="phone"&&status.optString("code")==preview.code)
                val api=ManagedApi(c,secrets)
                val fresh=api.authorized("pairing/lookup","POST",JSONObject().put("code",preview.code)).getJSONObject("pairing")
                require(fresh.getString("device")==preview.device)
                ensureCurrent(c,generation)
                api.authorized("pairing/${preview.code}/approve","POST",JSONObject().put("code",preview.code))
                ensureCurrent(c,generation)
                publishPhone(c,generation,preview.copy(stage="approved",code="",message="Телевизор подтверждён. На TV автоматически откроется главный экран."))
            }catch(e:CancellationException){throw e}
            catch(e:Exception){publishPhone(c,generation,preview.copy(stage="error",code="",message=if(e is HttpFailure)safeError(e) else "TV недоступен или подключение отменено. Обновите QR на TV и отсканируйте снова."))}
        }
    }
    fun cancelPhone(){
        phoneGeneration++;phoneJob?.cancel();phoneJob=null
        val previous=activePhone;activePhone=null;phoneMutable.value=PhoneQrState()
        if(previous!=null)scope.launch(Dispatchers.IO){runCatching {previous.cancel()}}
    }
}
