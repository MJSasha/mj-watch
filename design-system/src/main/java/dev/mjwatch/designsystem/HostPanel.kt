package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

internal enum class HostPanelKind { Maintenance, TorrServer, Jacred, Prowlarr }

@Composable internal fun HostPanel(
    s: WatchState,
    model: WatchModel,
    tv: Boolean,
    kind: HostPanelKind,
    limitsRevision: Int = 0,
    onDirty: (Boolean) -> Unit = {},
    onSearchAdmin: () -> Unit = {},
) {
    val api = remember(s.config, model.secrets) { ManagedApi(s.config, model.secrets) }
    val host = remember(api) { ManagedHost(api) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val pendingKey = "httpsHost:${s.config.scope}:$kind"
    var pending by remember(pendingKey) { mutableStateOf(model.secrets.get(pendingKey)) }
    var snapshot by remember(api, kind) { mutableStateOf<JSONObject?>(null) }
    var draft by remember(api, kind) { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var jacredDirty by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val fresh = host.read(when (kind) {
            HostPanelKind.TorrServer -> "torr/read"
            HostPanelKind.Jacred -> "jacred/status"
            HostPanelKind.Prowlarr -> "prowlarr/status"
            HostPanelKind.Maintenance -> "status"
        })
        if (kind == HostPanelKind.TorrServer) {
            val previous = snapshot
            val current = draft
            val next = if (previous != null && current != null) refreshTorrSettingsDraft(previous, current, fresh)
                else JSONObject(fresh.getJSONObject("settings").toString())
            draft = next
            onDirty(torrSettingsChanges(fresh, next).length() > 0)
        }
        snapshot = fresh
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        keyboard?.hide()
        busy = true
        message = ""
        scope.launch {
            try { action() }
            catch (failure: TimeoutCancellationException) { message = "Операция ещё не подтверждена. Проверьте её результат." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: MaintenanceFailure) {
                if (kind == HostPanelKind.Jacred) message = jacredError(failure.code)
                else message = safeError(failure)
            }
            catch (failure: Exception) { message = safeError(failure) }
            finally { busy = false }
        }
    }
    fun rememberOperation(id: String) {
        model.secrets.put(pendingKey, id)
        pending = id
    }
    suspend fun completeOperation(operationKind: String, id: String, request: JSONObject? = null): JSONObject {
        return try {
            val result = host.operation(operationKind, id, request)
            rememberOperation("")
            result
        } catch (failure: MaintenanceFailure) {
            rememberOperation("")
            throw failure
        } catch (failure: HttpFailure) {
            if (failure.status in setOf(400, 403, 404, 409)) rememberOperation("")
            throw failure
        }
    }
    fun applyTorr(current: JSONObject, changes: JSONObject, defaults: Boolean) {
        confirmation = "Изменение настроек TorrServer может повлиять на текущий просмотр." to {
            perform {
                val id = UUID.randomUUID().toString()
                val request = JSONObject().put("operationId", id).put("revision", current.getString("revision"))
                    .put("changes", changes).put("defaults", defaults).put("confirmed", true)
                rememberOperation(id)
                val result = completeOperation("torr", id, request)
                snapshot = result
                draft = JSONObject(result.getJSONObject("settings").toString())
                onDirty(false)
                message = "Настройки сохранены и проверены."
            }
        }
    }

    LaunchedEffect(api, kind, limitsRevision) { perform { refresh() } }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (message.isNotBlank()) OperationMessage(message, error = false)
    confirmation?.let { (text, action) ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("Подтвердите действие") },
            text = { Text(text) }, confirmButton = {
                TextButton(onClick = { confirmation = null; action() }, modifier = Modifier.tvFocusRing(tv)) { Text("Подтвердить") }
            }, dismissButton = {
                TextButton(onClick = { confirmation = null }, modifier = Modifier.tvFocusRing(tv)) { Text("Отмена") }
            })
    }
    val current = snapshot
    if (pending.isNotBlank()) {
        Action("Проверить результат операции", tv, enabled = !busy) {
            perform {
                completeOperation(if (kind == HostPanelKind.TorrServer) "torr" else "maintenance", pending)
                refresh()
                message = "Операция завершена."
            }
        }
    }
    if (current == null) {
        if (!busy) Action("Загрузить настройки", tv, secondary = true) { perform { refresh() } }
        return
    }
    when (kind) {
        HostPanelKind.TorrServer -> {
            val settings = draft ?: return
            val changes = torrSettingsChanges(current, settings)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(current.optString("version"), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { perform { refresh() } }, enabled = !busy, modifier = Modifier.tvFocusRing(tv)) {
                    Text("Обновить состояние")
                }
            }
            TorrServerEditor(current, settings, tv, onChange = { next ->
                draft = next
                onDirty(torrSettingsChanges(current, next).length() > 0)
            }, onDefaults = { if (!busy && pending.isBlank()) applyTorr(current, JSONObject(), true) })
            Action("Сохранить настройки", tv, Modifier.fillMaxWidth(),
                enabled = !busy && pending.isBlank() && current.optBoolean("editable") &&
                    changes.length() > 0 && torrSettingsValid(current, settings)) { applyTorr(current, changes, false) }
            if (changes.length() > 0) TextButton(onClick = {
                draft = JSONObject(current.getJSONObject("settings").toString())
                onDirty(false)
            }, enabled = !busy, modifier = Modifier.tvFocusRing(tv)) { Text("Отменить изменения") }
        }
        HostPanelKind.Jacred -> {
            JacredEditor(current, tv, busy, { jacredDirty = it; onDirty(it) }, onRefresh = { perform { refresh() } }) { next ->
                perform {
                    val result = host.searchOperation("jacred", "start", next)
                    val operation = result.optJSONObject("operation")
                    if (operation?.optString("state") == "failed") throw MaintenanceFailure(operation.optString("code"))
                    snapshot = result
                    jacredDirty = false
                    onDirty(false)
                    model.managed.load("admin/jacred?view=overview")
                }
            }
            if (current.optBoolean("running")) Action("Открыть админку", tv,
                enabled = !busy && !jacredDirty, secondary = true, onClick = onSearchAdmin)
        }
        HostPanelKind.Prowlarr -> {
            val operation = current.optJSONObject("operation")
            val installing = operation?.optString("state") == "running"
            val installed = current.optBoolean("installed")
            val running = current.optBoolean("ready")
            val configured = current.optBoolean("configured")
            val selected = current.optString("provider") == "prowlarr"
            SettingsCard("Prowlarr", Icons.Outlined.Search, tv) {
                Text(if (selected) "Внутренний поиск через Prowlarr" else "Внутренний поиск через JacRed")
                Text(when {
                    installing -> "Идёт установка Prowlarr…"
                    configured -> "Торрент-трекеры настроены"
                    running -> "Добавьте торрент-трекер в веб-админке"
                    installed -> "Prowlarr установлен. Служба пока недоступна."
                    else -> "Prowlarr ещё не установлен"
                })
                if (operation?.optString("state") == "failed") SettingsHint(prowlarrInstallError(operation.optString("code")))
                Action("Обновить состояние", tv, secondary = true, enabled = !busy) { perform { refresh() } }
                if (!running && !installing) Action(if (installed) "Запустить Prowlarr" else "Установить Prowlarr", tv,
                    enabled = !busy) { perform { snapshot = host.searchOperation("prowlarr", "install") } }
                if (running) Action("Открыть админку Prowlarr", tv, secondary = true, enabled = !busy, onClick = onSearchAdmin)
                if (selected || configured) Action(if (selected) "Переключить поиск на JacRed" else "Использовать Prowlarr для поиска",
                    tv, enabled = !busy) {
                    perform { snapshot = host.write("prowlarr/" + if (selected) "jacred" else "prowlarr") }
                }
            }
        }
        HostPanelKind.Maintenance -> {
            var password by remember { mutableStateOf("") }
            var newPassword by remember { mutableStateOf("") }
            var archive by remember { mutableStateOf("") }
            var passphrase by remember { mutableStateOf("") }
            fun submit(action: String, parameters: JSONObject = JSONObject()) {
                confirmation = "Операция может прервать активный просмотр." to {
                    perform {
                        val id = UUID.randomUUID().toString()
                        val request = parameters.put("operationId", id).put("action", action)
                            .put("confirmed", true).put("adminPassword", password)
                        password = ""
                        passphrase = ""
                        rememberOperation(id)
                        completeOperation("maintenance", id, request)
                        message = "Операция завершена."
                    }
                }
            }
            SettingsCard("Сервисы", Icons.Outlined.Build, tv) {
                ServerSecret(password, { password = it }, "Пароль владельца")
                val available = !busy && pending.isBlank() && password.isNotBlank()
                Action("Перезапустить сервисы", tv, enabled = available) { submit("restart") }
                Action("Очистить кэш TorrServer", tv, secondary = true, enabled = available) { submit("clear-cache") }
                SettingsDisclosure("Восстановление из копии", "Восстанавливает данные и требует повторного входа", Icons.Outlined.Restore, tv) {
                    ServerField(archive, { archive = it }, "Имя архива .mjwb")
                    ServerSecret(passphrase, { passphrase = it }, "Парольная фраза архива")
                    Action("Восстановить из копии", tv, secondary = true,
                        enabled = available && archive.matches(Regex("[A-Za-z0-9_-]+\\.mjwb")) && passphrase.length >= 16) {
                        submit("restore", JSONObject().put("archive", archive).put("passphrase", passphrase))
                    }
                }
            }
            SettingsCard("Пароль владельца", Icons.Outlined.Lock, tv) {
                ServerSecret(newPassword, { newPassword = it }, "Новый пароль владельца")
                SettingsHint("Если пароль уже задан, введите его в поле «Пароль владельца».")
                Action("Сохранить пароль владельца", tv, enabled = !busy && newPassword.length >= 10) {
                    perform {
                        api.authorized("admin/owner/password", "POST", JSONObject().put("current", password).put("password", newPassword))
                        password = ""
                        newPassword = ""
                        message = "Пароль владельца сохранён."
                    }
                }
            }
        }
    }
}
