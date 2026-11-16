package dev.mjwatch.designsystem

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mjwatch.core.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private data class UserConfirmation(val title:String,val body:String,val button:String,val action:()->Unit)

@Composable internal fun OperationMessage(text:String,error:Boolean=false) {
    if(text.isBlank())return
    val location=remember {BringIntoViewRequester()}
    LaunchedEffect(text,error){if(error){withFrameNanos {};location.bringIntoView()}}
    Text(text,color=if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier=Modifier.bringIntoViewRequester(location).testTag("operation-message").semantics {liveRegion=if(error)LiveRegionMode.Assertive else LiveRegionMode.Polite})
}
@Composable private fun UserHeading(text:String) {Text(text,fontSize=26.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics {heading()})}
@Composable private fun DangerAction(text:String,tv:Boolean,enabled:Boolean=true,action:()->Unit) {
    TextButton(onClick=action,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).tvFocusRing(tv),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text(text)}
}

@Composable internal fun UsersScreen(s:WatchState,model:WatchModel,tv:Boolean) {
    val controller=model.managed.users
    val state by controller.state.collectAsState()
    val current=state.takeIf {it.scope==s.config.scope} ?: UsersState()
    val user=current.selected
    val message=current.message.takeIf {current.messageTarget==when(s.page){"server-user-create"->"create";"server-users"->"users";else->current.selectedId}}.orEmpty()
    var confirmation by remember(s.page,current.selectedId) {mutableStateOf<UserConfirmation?>(null)}
    LaunchedEffect(s.config.scope) {
        if(!controller.state.value.loaded) {
            snapshotFlow {controller.state.value.busy}.first {!it}
            controller.load()
        }
    }
    fun confirm(title:String,body:String,button:String,action:()->Unit) {confirmation=UserConfirmation(title,body,button,action)}
    confirmation?.let {dialog->AlertDialog(onDismissRequest={confirmation=null},title={Text(dialog.title)},text={Text(dialog.body)},
        confirmButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={confirmation=null;dialog.action()}){Text(dialog.button)}},
        dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={confirmation=null}){Text("Отмена")}})}
    val list:@Composable ()->Unit={
        val scroll=rememberLazyListState()
        Column(Modifier.fillMaxHeight().padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            UserHeading("Пользователи"+(if(current.loaded)" · ${current.users.size}" else ""))
            Action("Добавить пользователя",tv,Modifier.fillMaxWidth(),enabled=!current.busy,icon=Icons.Outlined.PersonAdd){controller.clearMessage();model.page("server-user-create")}
            Action("Обновить список",tv,secondary=true,enabled=!current.busy){controller.load()}
            if(current.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(s.page=="server-users")OperationMessage(message,current.error)
            LazyColumn(Modifier.weight(1f).testTag("users-list"),state=scroll,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(current.users,key={it.id}) {row->
                    NativeCard(tv,Modifier.fillMaxWidth(),selected=row.id==current.selectedId,onClick={controller.select(row.id);model.page("server-user")}) {
                        Column(Modifier.fillMaxWidth().heightIn(min=72.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(row.name,fontSize=18.sp,fontWeight=FontWeight.Medium)
                            Text(listOfNotNull(row.roleLabel,"Это вы".takeIf {row.id==s.config.userId},"Заблокирован".takeIf {row.blocked}).joinToString(" · "),color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if(current.loaded&&current.users.isEmpty())item {Text("Пользователей пока нет")}
            }
        }
    }
    val detail:@Composable ()->Unit={Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical=16.dp).testTag("user-detail"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(current.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        when(s.page) {
            "server-user-create"->{
                UserHeading("Новый пользователь")
                Text("У каждого пользователя будут отдельные история и избранное.")
                OutlinedTextField(current.draftName,controller::editName,label={Text("Имя пользователя")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OperationMessage(message,current.error)
                Action(if(current.busy)"Создаём пользователя…" else "Создать и получить приглашение",tv,enabled=validManagedUserName(current.draftName)&&!current.busy){controller.create {if(model.state.value.page=="server-user-create")model.page("server-user-invite")}}
            }
            "server-user-devices"->{
                UserHeading("Устройства ${user?.name ?: "пользователя"}")
                OperationMessage(message,current.error)
                if(user!=null) {
                    LaunchedEffect(user.id) {snapshotFlow {controller.state.value.busy}.first {!it};controller.loadDevices(user.id)}
                    val devices=current.devices[user.id]
                    if(devices?.isEmpty()==true){Text("Устройств пока нет",fontWeight=FontWeight.Bold);Text("Устройства появятся после первого входа.")}
                    devices?.forEach {device->SettingsGroup(tv) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(device.name,fontWeight=FontWeight.Medium)
                            Text(if(device.revoked)"Доступ отозван" else "Последняя активность: ${activityTime(device.lastSeen)}")
                            DangerAction("Отключить ${device.name}",tv,enabled=!current.busy&&!device.revoked){confirm("Отключить ${device.name}?","Пользователю ${user.name} потребуется снова войти на этом устройстве.","Отключить"){controller.revokeDevices(user.id,device.id)}}
                        }
                    }}
                    Action("Обновить устройства",tv,secondary=true,enabled=!current.busy){controller.loadDevices(user.id)}
                    Action("К пользователю ${user.name}",tv,secondary=true){model.back()}
                }
            }
            "server-user-invite"->{
                UserHeading("Приглашение для ${user?.name ?: "пользователя"}")
                OperationMessage(message,current.error)
                if(user!=null) {
                    val invitation=current.invitations[user.id]
                    if(invitation!=null) {
                        Text("Одноразовое. Действует до ${activityTime(invitation.expiresAt/1000)}.")
                        Text("Отправьте приглашение ${user.name}. На её или его устройстве: Настройки → Способ подключения → Войти на сервер MJWatch → Вставить приглашение.")
                        var copied by remember(invitation.token){mutableStateOf(false)}
                        val context=LocalContext.current
                        Action(if(copied)"Приглашение для ${user.name} скопировано" else "Скопировать приглашение",tv,enabled=invitation.expiresAt>System.currentTimeMillis()&&!current.busy){
                            val packet=JSONObject().put("url",s.config.managedUrl).put("invite",invitation.token).put("ca",model.secrets.get("ca:${endpoint(s.config.managedUrl).origin()}"))
                            val clip=ClipData.newPlainText("Приглашение для ${user.name}",packet.toString())
                            clip.description.extras=PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)}
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip);copied=true
                        }
                    } else Text("Приглашение недоступно на этом устройстве. Можно создать новое — прежнее перестанет работать.")
                    var trust by rememberSaveable {mutableStateOf(false)}
                    Action("Проверка сервера",tv,secondary=true){trust=!trust}
                    if(trust) {
                        Text(s.config.managedUrl)
                        Text("Получатель сверяет этот отпечаток с вами по доверенному каналу.")
                        runCatching {managedCaFingerprint(model.secrets.get("ca:${endpoint(s.config.managedUrl).origin()}"))}.getOrNull()?.let {SelectionContainerCompat(it)}
                    }
                    Action("Создать новое приглашение",tv,secondary=true,enabled=!current.busy&&!user.blocked){confirm("Создать новое приглашение для ${user.name}?","Прежнее приглашение перестанет работать.","Создать новое"){controller.invite(user.id)}}
                    DangerAction("Отозвать приглашение для ${user.name}",tv,enabled=!current.busy){confirm("Отозвать приглашение для ${user.name}?","По этому приглашению больше нельзя будет войти.","Отозвать"){controller.revokeInvite(user.id)}}
                }
            }
            else->if(user==null) {Text("Выберите пользователя из списка.");if(!current.loaded)Text("Загружаем пользователей…")}
            else {
                UserHeading(user.name)
                Text(user.roleLabel+if(user.id==s.config.userId)" · Это вы" else "")
                Text(if(user.blocked)"Доступ заблокирован" else "Доступ разрешён")
                OperationMessage(message,current.error)
                if(user.role=="user")Action("Приглашение для ${user.name}",tv,secondary=true){controller.clearMessage();model.page("server-user-invite")}
                Action("Устройства ${user.name}",tv,secondary=true){controller.clearMessage();model.page("server-user-devices")}
                Spacer(Modifier.height(8.dp));Text("Управление доступом",fontWeight=FontWeight.Bold,modifier=Modifier.semantics {heading()})
                val protected=isLastAdmin(user,current.users)
                if(protected)Text("Это последний администратор сервера. Его нельзя заблокировать или удалить.")
                DangerAction("Отозвать все устройства ${user.name}",tv,enabled=!current.busy){confirm("Отключить все устройства ${user.name}?","Все сеансы будут отозваны. Для просмотра потребуется повторный вход.","Отключить все"){controller.revokeDevices(user.id)}}
                DangerAction(if(user.blocked)"Разблокировать ${user.name}" else "Заблокировать ${user.name}",tv,enabled=current.loaded&&!current.busy&&!protected){
                    confirm(if(user.blocked)"Разблокировать ${user.name}?" else "Заблокировать ${user.name}?",
                        if(user.blocked)"Пользователь снова сможет войти. Прежние сеансы не восстанавливаются." else "${user.name} не сможет входить на сервер. Доступ устройств будет отозван. История сохранится. После разблокировки понадобится повторный вход.",
                        if(user.blocked)"Разблокировать" else "Заблокировать"){controller.changeAccess(user)}
                }
                DangerAction("Удалить ${user.name}",tv,enabled=current.loaded&&!current.busy&&!protected){confirm("Удалить ${user.name}?","Будут удалены аккаунт и его история, доступ устройств будет отозван. Сохранённые на устройствах офлайн-данные удалённо не стираются. Отменить удаление нельзя.","Удалить ${user.name}"){controller.changeAccess(user,delete=true){model.page("server-users")}}}
            }
        }
    }}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if(tv||maxWidth>=840.dp)Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
            Box(Modifier.width(if(tv)tvUnit(340f) else 300.dp)){list()}
            Box(Modifier.weight(1f)){detail()}
        } else if(s.page=="server-users")list() else detail()
    }
}

internal fun activityTime(seconds:Long):String=if(seconds<=0)"Неизвестно" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(java.util.Date(seconds*1000))
