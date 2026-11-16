package dev.mjwatch.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mjwatch.core.*

@Composable internal fun InvitationScreen(model:WatchModel,tv:Boolean) {
    val controller=model.invitation
    val state by controller.state.collectAsState()
    val link=state.link
    var password by remember(link){mutableStateOf("")}
    var confirmation by remember(link){mutableStateOf("")}
    var certificate by remember(link){mutableStateOf(false)}
    fun leave(){controller.dismiss();model.back()}
    BackHandler {if(!state.busy)leave()}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Вас пригласили в MJWatch",fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics{heading()})
        if(link!=null) {
            Text(endpoint(link.url).let {it.host+if(it.port!=443)":${it.port}" else ""},color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Придумайте пароль для своего аккаунта. История и избранное будут храниться отдельно.")
            if(model.state.value.config.historyEnabled)Text("После регистрации приложение переключится на новый аккаунт. Данные прежнего аккаунта сохранятся на устройстве.")
            key(link){ServerSecret(password,{password=it},"Придумайте пароль · от 10 символов")
            ServerSecret(confirmation,{confirmation=it},"Повторите пароль")}
            OperationMessage(state.message,error=true)
            Action(if(state.busy)"Регистрируем…" else "Создать аккаунт и войти",tv,Modifier.fillMaxWidth(),enabled=!state.busy&&password.length>=10&&password==confirmation){controller.register(password,confirmation){password="";confirmation="";model.page("home")}}
            Text("Продолжая, вы принимаете приглашение на указанный сервер. Пароль передаётся только ему по защищённому соединению.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick={certificate=!certificate},enabled=!state.busy){Text("Проверить сервер")}
            if(certificate){Text(link.url);Text(if(link.pin.isBlank())"Сертификат проверяет Android." else "Отпечаток сертификата из приглашения:\n"+link.pin.chunked(2).joinToString(":").uppercase())}
        } else OperationMessage(state.message,error=true)
        TextButton(onClick={leave()},enabled=!state.busy){Text("Отмена")}
    }
}
