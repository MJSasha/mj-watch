package dev.mjwatch.designsystem

import dev.mjwatch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class UsersState(val scope:String="",val users:List<ManagedUser> = emptyList(),val loaded:Boolean=false,
    val selectedId:String?=null,val devices:Map<String,List<ManagedDevice>> = emptyMap(),
    val invitations:Map<String,UserInvitation> = emptyMap(),val draftName:String="",
    val busy:Boolean=false,val message:String="",val error:Boolean=false,val messageTarget:String="") {
    val selected get()=users.firstOrNull {it.id==selectedId}
}

/** Administrative responses have their own data and message, independent of sync. */
class UsersController(private val scope:CoroutineScope,private val secrets:SecretStore,private val config:()->Config) {
    private val mutable=MutableStateFlow(UsersState())
    val state=mutable.asStateFlow()
    private var job:Job?=null
    private var generation=0
    fun reset(account:String) {
        if(mutable.value.scope==account)return
        generation++;job?.cancel();mutable.value=UsersState(scope=account)
    }
    fun select(id:String) {mutable.update {it.copy(selectedId=id,message="",error=false)}}
    fun editName(name:String) {mutable.update {it.copy(draftName=name,message="",error=false)}}
    fun clearMessage() {mutable.update {it.copy(message="",error=false)}}
    private suspend fun update(c:Config,block:(UsersState)->UsersState) {
        currentCoroutineContext().ensureActive()
        if(config().scope==c.scope&&mutable.value.scope==c.scope)mutable.update(block)
    }
    private fun request(target:String,action:suspend (ManagedApi,Config)->Unit) {
        if(state.value.busy)return
        val c=config();if(!c.historyEnabled)return
        reset(c.scope);val token=generation
        mutable.update {it.copy(busy=true,message="",error=false,messageTarget=target)}
        job=scope.launch {
            try {withContext(Dispatchers.IO){action(ManagedApi(c,secrets),c)}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){update(c){it.copy(message=safeError(e),error=true,messageTarget=target)}}
            finally {if(token==generation)update(c){it.copy(busy=false)}}
        }
    }
    fun load()=request("users") {api,c->val users=managedUsers(api.authorized("admin/users"));update(c){it.copy(users=users,loaded=true)}}
    fun create(onReady:()->Unit) {
        val name=state.value.draftName.trim()
        if(!validManagedUserName(name)){mutable.update {it.copy(message="Введите имя пользователя. Если имя слишком длинное, сократите его.",error=true,messageTarget="create")};return}
        val selectedBefore=state.value.selectedId
        request("create") {api,c->
            val result=api.authorized("admin/users","POST",JSONObject().put("name",name))
            val id=result.getString("userId")
            val invitation=UserInvitation(id,result.getString("invite"),System.currentTimeMillis()+result.getLong("expiresIn")*1000)
            update(c){it.copy(users=(it.users+ManagedUser(id,name,"user",false)).distinctBy {u->u.id}.sortedBy {u->u.name},
                selectedId=if(it.selectedId==selectedBefore)id else it.selectedId,invitations=it.invitations+(id to invitation),draftName="",message="Пользователь $name создан",messageTarget=id)}
            try {
                val fresh=managedUsers(api.authorized("admin/users"))
                update(c){it.copy(users=fresh,loaded=true)}
            } catch(e:CancellationException){throw e}
            catch(_:Exception){update(c){it.copy(message="Пользователь $name создан. Список не удалось обновить — обновите его позже.",error=true)}}
            withContext(Dispatchers.Main){if(config().scope==c.scope)onReady()}
        }
    }
    fun loadDevices(id:String)=request(id) {api,c->
        val devices=managedDevices(api.authorized("admin/users/$id/devices"))
        update(c){it.copy(devices=it.devices+(id to devices))}
    }
    fun invite(id:String)=request(id) {api,c->
        val result=api.authorized("admin/users/$id/invite","POST")
        val invitation=UserInvitation(id,result.getString("invite"),System.currentTimeMillis()+result.getLong("expiresIn")*1000)
        update(c){it.copy(invitations=it.invitations+(id to invitation),message="Новое приглашение готово")}
    }
    fun revokeInvite(id:String)=request(id) {api,c->
        api.authorized("admin/users/$id/invite","DELETE")
        update(c){it.copy(invitations=it.invitations-id,message="Приглашение отозвано")}
    }
    fun revokeDevices(id:String,device:String?=null)=request(id) {api,c->
        api.authorized("admin/users/$id/devices"+(device?.let {"/$it"} ?: ""),"DELETE")
        val devices=managedDevices(api.authorized("admin/users/$id/devices"))
        update(c){it.copy(devices=it.devices+(id to devices),message="Доступ устройств отозван")}
    }
    fun changeAccess(user:ManagedUser,delete:Boolean=false,onDeleted:()->Unit={}) {
        if(!state.value.loaded||isLastAdmin(user,state.value.users)) {
            mutable.update {it.copy(message=if(!it.loaded)"Сначала обновите список пользователей." else "Это последний администратор сервера. Его нельзя заблокировать или удалить.",error=true,messageTarget=user.id)};return
        }
        request(user.id) {api,c->
            api.authorized("admin/users/${user.id}",if(delete)"DELETE" else "PATCH",if(delete)null else JSONObject().put("blocked",!user.blocked))
            update(c){old->old.copy(users=if(delete)old.users.filter {it.id!=user.id} else old.users.map {if(it.id==user.id)it.copy(blocked=!user.blocked) else it},
                invitations=old.invitations-user.id,devices=old.devices-user.id,messageTarget=if(delete)"users" else user.id,message=if(delete)"Пользователь ${user.name} удалён" else "Доступ ${user.name} изменён")}
            if(delete)withContext(Dispatchers.Main){if(config().scope==c.scope)onDeleted()}
        }
    }
}
