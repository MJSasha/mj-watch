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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mjwatch.core.*
import kotlinx.coroutines.flow.first

private data class UserConfirmation(val title:String,val body:String,val button:String,val action:()->Unit)

@Composable internal fun OperationMessage(text:String,error:Boolean=false) {
    if(text.isBlank())return
    val location=remember {BringIntoViewRequester()}
    LaunchedEffect(text,error){if(error){withFrameNanos {};location.bringIntoView()}}
    Text(text,color=if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier=Modifier.bringIntoViewRequester(location).testTag("operation-message").semantics {liveRegion=if(error)LiveRegionMode.Assertive else LiveRegionMode.Polite})
}
@Composable private fun UserHeading(text:String) {Text(text,fontSize=26.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics {heading()})}
@Composable private fun DangerAction(text:String,tv:Boolean,enabled:Boolean=true,description:String="",action:()->Unit) {
    Surface(onClick=action,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).tvFocusRing(tv),color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.WarningAmber,null,tint=MaterialTheme.colorScheme.error.copy(alpha=if(enabled)1f else .38f))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(text,color=MaterialTheme.colorScheme.error.copy(alpha=if(enabled)1f else .38f))
                if(description.isNotBlank())Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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
            Text("Выберите человека, чтобы пригласить его, посмотреть устройства или изменить доступ.",color=MaterialTheme.colorScheme.onSurfaceVariant)
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
                OutlinedTextField(current.draftName,controller::editName,label={Text("Имя пользователя")},singleLine=true,modifier=Modifier.fillMaxWidth().tvFieldNavigation())
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
                            DangerAction("Удалить устройство ${device.name}",tv,enabled=!current.busy){confirm("Удалить ${device.name}?","Устройство исчезнет из списка, доступ будет отозван. Пользователю ${user.name} потребуется снова войти.","Удалить устройство"){controller.revokeDevices(user.id,device.id)}}
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
                        Text("Отправьте ссылку ${user.name}. Она откроет MJWatch сразу на экране регистрации — останется придумать пароль.")
                        var copied by remember(invitation.token){mutableStateOf(false)}
                        val context=LocalContext.current
                        val link=remember(invitation.token,s.config.managedUrl){invitationLink(s.config.managedUrl,invitation.token,model.secrets.get("ca:${endpoint(s.config.managedUrl).origin()}"))}
                        Action("Поделиться ссылкой",tv,enabled=invitation.expiresAt>System.currentTimeMillis()&&!current.busy){
                            val send=android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_SUBJECT,"Приглашение в MJWatch").putExtra(android.content.Intent.EXTRA_TEXT,"Приглашаю вас в MJWatch. Откройте ссылку и придумайте пароль:\n$link")
                            context.startActivity(android.content.Intent.createChooser(send,"Отправить приглашение ${user.name}"))
                        }
                        Action(if(copied)"Ссылка для ${user.name} скопирована" else "Скопировать ссылку",tv,secondary=true,enabled=invitation.expiresAt>System.currentTimeMillis()&&!current.busy){
                            val clip=ClipData.newPlainText("Приглашение для ${user.name}",link)
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
                SettingsGroup(tv) {
                    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Person,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary)
                        UserHeading(user.name)
                        Text(user.roleLabel+if(user.id==s.config.userId)" · Это вы" else "",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if(user.blocked)"Доступ заблокирован" else "Доступ разрешён",fontWeight=FontWeight.Medium)
                    }
                }
                OperationMessage(message,current.error)
                Text("Вход и устройства",fontWeight=FontWeight.Bold,modifier=Modifier.semantics {heading()})
                SettingsGroup(tv) {
                    if(user.role=="user") {
                        SettingsActionRow(Icons.Outlined.MailOutline,"Приглашение для ${user.name}","Получить и отправить одноразовое приглашение для входа",tv){controller.clearMessage();model.page("server-user-invite")}
                        SettingsDivider()
                    }
                    SettingsActionRow(Icons.Outlined.Devices,"Устройства ${user.name}","Посмотреть подключения и отключить отдельное устройство",tv){controller.clearMessage();model.page("server-user-devices")}
                }
                Spacer(Modifier.height(8.dp));Text("Управление доступом",fontWeight=FontWeight.Bold,modifier=Modifier.semantics {heading()})
                Text("Эти действия влияют на вход пользователя. Перед выполнением появится подтверждение.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                val protected=isLastAdmin(user,current.users)
                if(protected)Text("Это последний администратор сервера. Его нельзя заблокировать или удалить.")
                DangerAction("Отозвать все устройства ${user.name}",tv,enabled=!current.busy,description="Выйти из аккаунта на всех устройствах. История сохранится."){confirm("Отключить все устройства ${user.name}?","Все сеансы будут отозваны. Для просмотра потребуется повторный вход.","Отключить все"){controller.revokeDevices(user.id)}}
                DangerAction(if(user.blocked)"Разблокировать ${user.name}" else "Заблокировать ${user.name}",tv,enabled=current.loaded&&!current.busy&&!protected,description=if(user.blocked)"Снова разрешить вход на сервер." else "Временно запретить вход. История сохранится."){
                    confirm(if(user.blocked)"Разблокировать ${user.name}?" else "Заблокировать ${user.name}?",
                        if(user.blocked)"Пользователь снова сможет войти. Прежние сеансы не восстанавливаются." else "${user.name} не сможет входить на сервер. Доступ устройств будет отозван. История сохранится. После разблокировки понадобится повторный вход.",
                        if(user.blocked)"Разблокировать" else "Заблокировать"){controller.changeAccess(user)}
                }
                DangerAction("Удалить ${user.name}",tv,enabled=current.loaded&&!current.busy&&!protected,description="Удалить аккаунт и его историю без возможности восстановления."){confirm("Удалить ${user.name}?","Будут удалены аккаунт и его история, доступ устройств будет отозван. Сохранённые на устройствах офлайн-данные удалённо не стираются. Отменить удаление нельзя.","Удалить ${user.name}"){controller.changeAccess(user,delete=true){model.page("server-users")}}}
            }
        }
    }}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if(tv||maxWidth>=840.dp)Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
            Box(Modifier.width(if(tv)tvUnit(340f) else 300.dp)){list()}
            Box(Modifier.weight(1f)){BoundedPane(!tv){detail()}}
        } else if(s.page=="server-users")list() else BoundedPane(!tv){detail()}
    }
}

internal fun activityTime(seconds:Long):String=if(seconds<=0)"Неизвестно" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(java.util.Date(seconds*1000))
