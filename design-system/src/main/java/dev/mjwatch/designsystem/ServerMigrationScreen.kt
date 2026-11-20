package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun ServerMigrationScreen(url:String,state:ManagedState,controller:ManagedController,tv:Boolean) {
    Text("Перенос сервера",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics { heading() })
    SettingsCard("Текущий сервер",Icons.Outlined.CloudSync,tv) {
        Text(url,style=MaterialTheme.typography.bodyMedium)
        ServerMoveStatusMessage(state.moveStatus,tv,state.busy,controller::syncNow)
        if(state.moveStatus==null||state.moveStatus==ServerMoveStatus.CONNECTED) {
            Text(state.syncMessage.ifBlank {"Проверяем синхронизацию"},style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
            Action("Проверить подключение",tv,secondary=true,enabled=!state.busy){controller.syncNow()}
        }
    }
    SettingsCard("Подготовка",Icons.Outlined.CloudSync,tv) {
        Text("Обновите приложение на всех устройствах. Установите MJWatch Server 0.1.9 на обоих серверах. Новый сервер должен быть пустым.")
        Text("Настройте вход по SSH под root к обоим серверам и сверьте их ключи хостов.")
    }
    SettingsCard("Перенос по SSH",Icons.Outlined.Terminal,tv) {
        Text("На компьютере с Python 3 распакуйте архив сервера из релиза и откройте терминал в этой папке. Подставьте SSH-имена старого и нового сервера.")
        SelectionContainerCompat("python3 migrate.py OLD_SSH_ALIAS NEW_SSH_ALIAS")
        Text("Если при установке на новом сервере уже создан начальный аккаунт владельца, добавьте --replace-bootstrap.")
        Text("Во время переноса просмотр может прерваться. Оставьте прежний HTTPS-адрес доступным, пока все устройства не получат новый адрес при синхронизации.")
    }
}

@Composable internal fun ServerMoveStatusMessage(status:ServerMoveStatus?,tv:Boolean,busy:Boolean,retry:()->Unit) {
    val message=when(status) {
        ServerMoveStatus.CHECKING->"Подключаемся к новому адресу…"
        ServerMoveStatus.RETRY->"Не удалось подключиться к новому адресу. Приложение попробует снова."
        ServerMoveStatus.CONNECTED->"Устройство подключено к новому адресу."
        null->return
    }
    if(status==ServerMoveStatus.CHECKING)LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(message,style=MaterialTheme.typography.bodySmall,
        color=if(status==ServerMoveStatus.RETRY)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
    if(status==ServerMoveStatus.RETRY) {
        Action("Повторить",tv,Modifier.heightIn(min=48.dp),secondary=true,enabled=!busy,onClick=retry)
    }
}
