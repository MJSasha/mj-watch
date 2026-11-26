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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
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
@Composable private fun UserAvatar(user:ManagedUser,size:androidx.compose.ui.unit.Dp){
    val initials=remember(user.name){user.name.trim().split(Regex("\\s+")).filter{it.isNotEmpty()}.take(2).joinToString(""){it.substring(0,it.offsetByCodePoints(0,1)).uppercase()}.ifBlank{"?"}}
    Surface(shape=CircleShape,color=if(user.blocked)MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.size(size)){
        Box(contentAlignment=Alignment.Center){Text(initials,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=if(user.blocked)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer)}
    }
}
@Composable private fun DangerAction(text:String,tv:Boolean,enabled:Boolean=true,description:String="",displayText:String=text,icon:ImageVector=Icons.Outlined.WarningAmber,danger:Boolean=true,action:()->Unit) {
    val color=if(danger)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(onClick=action,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).tvFocusRing(tv).semantics{contentDescription=text},shape=MaterialTheme.shapes.small,color=androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.padding(horizontal=12.dp,vertical=if(tv)12.dp else 10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(icon,null,tint=color.copy(alpha=if(enabled)1f else .38f))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(displayText,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium,color=color.copy(alpha=if(enabled)1f else .38f))
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
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Box(Modifier.weight(1f)){UserHeading("Пользователи")}
                if(current.loaded)Badge(current.users.size.toString(),accent=true)
            }
            Text("Доступ, приглашения и устройства",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                Action("Добавить пользователя",tv,Modifier.weight(1f),enabled=!current.busy,icon=Icons.Outlined.PersonAdd){controller.clearMessage();model.page("server-user-create")}
                FilledTonalIconButton(onClick={controller.load()},enabled=!current.busy,modifier=Modifier.size(48.dp).tvFocusRing(tv)){Icon(Icons.Outlined.Refresh,"Обновить список")}
            }
            if(current.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(s.page=="server-users")OperationMessage(message,current.error)
            LazyColumn(Modifier.weight(1f).testTag("users-list"),state=scroll,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(current.users,key={it.id}) {row->
                    NativeCard(tv,Modifier.fillMaxWidth(),selected=row.id==current.selectedId,onClick={controller.select(row.id);model.page("server-user")}) {
                        Row(Modifier.fillMaxWidth().heightIn(min=72.dp).padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            UserAvatar(row,40.dp)
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                Text(row.name,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(row.roleLabel+if(row.id==s.config.userId)" · Это вы" else "",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(row.blocked)Badge("Заблокирован",warning=true)
                            }
                            Icon(Icons.Outlined.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
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
                SettingsCard("Аккаунт пользователя",Icons.Outlined.PersonAdd,tv){
                Text("У каждого пользователя будут отдельные история и избранное.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(current.draftName,controller::editName,label={Text("Имя пользователя")},singleLine=true,modifier=Modifier.fillMaxWidth().tvFieldNavigation())
                OperationMessage(message,current.error)
                Action(if(current.busy)"Создаём пользователя…" else "Создать и получить приглашение",tv,enabled=validManagedUserName(current.draftName)&&!current.busy){controller.create {if(model.state.value.page=="server-user-create")model.page("server-user-invite")}}
                }
            }
            "server-user-devices"->{
                UserHeading("Устройства ${user?.name ?: "пользователя"}")
                OperationMessage(message,current.error)
                if(user!=null) {
                    LaunchedEffect(user.id) {snapshotFlow {controller.state.value.busy}.first {!it};controller.loadDevices(user.id)}
                    val devices=current.devices[user.id]
                    if(devices?.isEmpty()==true)SettingsCard("Устройств пока нет",Icons.Outlined.Devices,tv){Text("Устройства появятся после первого входа.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    devices?.forEach {device->SettingsCard(device.name,if(device.type=="tv")Icons.Outlined.Tv else Icons.Outlined.Devices,tv){
                        Text(device.typeLabel,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(device.type != "tv") {
                            val version = if(device.appVersionCode > 0 && device.appVersionName.isNotBlank())
                                "MJ Watch ${device.appVersionName} · сборка ${device.appVersionCode}"
                            else "Версия MJ Watch неизвестна"
                            Text(version, style=MaterialTheme.typography.bodySmall)
                        }
                        Text(if(device.revoked)"Доступ отозван" else "Последняя активность: ${activityTime(device.lastSeen)}",style=MaterialTheme.typography.bodySmall)
                        DangerAction("Удалить устройство ${device.name}",tv,enabled=!current.busy,displayText="Удалить устройство",icon=Icons.Outlined.DeleteOutline){confirm("Удалить ${device.name}?","Устройство исчезнет из списка, доступ будет отозван. Пользователю ${user.name} потребуется снова войти.","Удалить устройство"){controller.revokeDevices(user.id,device.id)}}
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
                        SettingsCard("Ссылка для входа",Icons.Outlined.Link,tv){
                        Text("Действует до ${activityTime(invitation.expiresAt/1000)}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                        Text("Ссылка одноразовая. Она откроет экран регистрации — получателю останется придумать пароль.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        var copied by remember(invitation.token){mutableStateOf(false)}
                        val context=LocalContext.current
                        val link=remember(invitation.token,s.config.managedUrl){invitationLink(s.config.managedUrl,invitation.token,model.secrets.get("ca:${endpoint(s.config.managedUrl).origin()}"))}
                        Action("Поделиться ссылкой",tv,Modifier.fillMaxWidth(),icon=Icons.Outlined.Share,enabled=invitation.expiresAt>System.currentTimeMillis()&&!current.busy){
                            val send=android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_SUBJECT,"Приглашение в MJWatch").putExtra(android.content.Intent.EXTRA_TEXT,"Приглашаю вас в MJWatch. Откройте ссылку и придумайте пароль:\n$link")
                            context.startActivity(android.content.Intent.createChooser(send,"Отправить приглашение ${user.name}"))
                        }
                        Action(if(copied)"Ссылка скопирована" else "Скопировать ссылку",tv,Modifier.fillMaxWidth(),secondary=true,icon=Icons.Outlined.ContentCopy,enabled=invitation.expiresAt>System.currentTimeMillis()&&!current.busy){
                            val clip=ClipData.newPlainText("Приглашение для ${user.name}",link)
                            clip.description.extras=PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)}
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip);copied=true
                        }
                        }
                    } else SettingsHint("Приглашение недоступно на этом устройстве. Создайте новое — прежнее перестанет работать.")
                    SettingsCard("Управление приглашением",Icons.Outlined.MailOutline,tv){
                    Action("Создать новое приглашение",tv,secondary=true,enabled=!current.busy&&!user.blocked){confirm("Создать новое приглашение для ${user.name}?","Прежнее приглашение перестанет работать.","Создать новое"){controller.invite(user.id)}}
                    DangerAction("Отозвать приглашение для ${user.name}",tv,enabled=!current.busy,displayText="Отозвать приглашение",icon=Icons.Outlined.LinkOff){confirm("Отозвать приглашение для ${user.name}?","По этому приглашению больше нельзя будет войти.","Отозвать"){controller.revokeInvite(user.id)}}
                    }
                    SettingsDisclosure("Проверка сервера","Адрес и отпечаток сертификата",Icons.Outlined.VerifiedUser,tv){
                        Text(s.config.managedUrl,style=MaterialTheme.typography.bodyMedium)
                        Text("Получатель сверяет этот отпечаток с вами по доверенному каналу.",style=MaterialTheme.typography.bodySmall)
                        runCatching{managedCaFingerprint(model.secrets.get("ca:${endpoint(s.config.managedUrl).origin()}"))}.getOrNull()?.let{SelectionContainerCompat(it)}
                    }
                }
            }
            else->if(user==null) {Text("Выберите пользователя из списка.");if(!current.loaded)Text("Загружаем пользователей…")}
            else {
                SettingsGroup(tv){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
                        UserAvatar(user,56.dp)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text(user.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.semantics{heading()})
                            Text(user.roleLabel,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Badge(if(user.blocked)"Доступ заблокирован" else "Доступ разрешён",accent=!user.blocked,warning=user.blocked)
                        if(user.id==s.config.userId)Badge("Это вы")
                    }
                }}
                OperationMessage(message,current.error)
                SettingsCard("Вход и устройства",Icons.Outlined.Login,tv){
                    SettingsGroup(tv){
                        if(user.role=="user"){
                            SettingsActionRow(Icons.Outlined.Link,"Приглашение","Одноразовая ссылка для входа",tv){controller.clearMessage();model.page("server-user-invite")}
                            SettingsDivider()
                        }
                        SettingsActionRow(Icons.Outlined.Devices,"Устройства","Подключения и управление сеансами",tv){controller.clearMessage();model.page("server-user-devices")}
                    }
                }
                SettingsCard("Управление доступом",Icons.Outlined.ManageAccounts,tv){
                val protected=isLastAdmin(user,current.users)
                if(protected)SettingsHint("Это последний администратор сервера. Его нельзя заблокировать или удалить.")
                SettingsGroup(tv){
                DangerAction("Отозвать все устройства ${user.name}",tv,enabled=!current.busy,description="Завершить все сеансы. История сохранится.",displayText="Отключить все устройства",icon=Icons.Outlined.Logout,danger=false){confirm("Отключить все устройства ${user.name}?","Все сеансы будут отозваны. Для просмотра потребуется повторный вход.","Отключить все"){controller.revokeDevices(user.id)}}
                SettingsDivider()
                DangerAction(if(user.blocked)"Разблокировать ${user.name}" else "Заблокировать ${user.name}",tv,enabled=current.loaded&&!current.busy&&!protected,description=if(user.blocked)"Снова разрешить вход на сервер." else "Временно запретить вход. История сохранится.",displayText=if(user.blocked)"Разблокировать" else "Заблокировать",icon=if(user.blocked)Icons.Outlined.LockOpen else Icons.Outlined.Block,danger=!user.blocked){
                    confirm(if(user.blocked)"Разблокировать ${user.name}?" else "Заблокировать ${user.name}?",
                        if(user.blocked)"Пользователь снова сможет войти. Прежние сеансы не восстанавливаются." else "${user.name} не сможет входить на сервер. Доступ устройств будет отозван. История сохранится. После разблокировки понадобится повторный вход.",
                        if(user.blocked)"Разблокировать" else "Заблокировать"){controller.changeAccess(user)}
                }
                SettingsDivider()
                DangerAction("Удалить ${user.name}",tv,enabled=current.loaded&&!current.busy&&!protected,description="Аккаунт и история будут удалены безвозвратно.",displayText="Удалить пользователя",icon=Icons.Outlined.DeleteOutline){confirm("Удалить ${user.name}?","Будут удалены аккаунт и его история, доступ устройств будет отозван. Сохранённые на устройствах офлайн-данные удалённо не стираются. Отменить удаление нельзя.","Удалить ${user.name}"){controller.changeAccess(user,delete=true){model.page("server-users")}}}
                }
                }
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
