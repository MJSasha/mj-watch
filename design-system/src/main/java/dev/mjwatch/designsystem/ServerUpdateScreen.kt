package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

@Composable internal fun ServerUpdateScreen(s: WatchState, model: WatchModel, tv: Boolean) {
    val context = LocalContext.current
    val host = remember(s.config, model.secrets) { ManagedHost(ManagedApi(s.config, model.secrets)) }
    val bundledVersion = remember(context) {
        runCatching { ServerBundles(context).bundledVersion() }.getOrNull()
    }
    val account by model.managed.state.collectAsState()
    val installed = account.data?.takeIf { account.scope == s.config.scope }?.optString("version").orEmpty()
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val pendingKey = "httpsUpdate:${s.config.scope}"
    var pending by remember(pendingKey) {
        mutableStateOf(runCatching { JSONObject(model.secrets.get(pendingKey)) }.getOrNull())
    }
    var candidate by remember(host) { mutableStateOf(bundledVersion) }
    var busy by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var completed by remember { mutableStateOf("") }

    LaunchedEffect(host) {
        try {
            available = host.read("status").optBoolean("available")
            if (available) {
                try { candidate = host.read("update/available").getString("version") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The bundled version can still be considered. */ }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: HttpFailure) {
            message = if (failure.status == 404) "Для первого перехода на управление по HTTPS обновите старый сервер с его хоста." else safeError(failure)
        } catch (failure: Exception) {
            message = safeError(failure)
        }
    }

    fun update(version: String, id: String, request: JSONObject?) {
        if (busy) return
        busy = true
        keyboard?.hide()
        message = "Сервер проверяет пакет, создаёт резервную копию и устанавливает обновление…"
        scope.launch {
            try {
                val result = host.operation("maintenance", id, request)
                check(result.optBoolean("verified")) { "Сервер не подтвердил результат обновления." }
                completed = version
                message = ""
                pending = null
                model.secrets.put(pendingKey, "")
                model.managed.load("admin/status")
            } catch (_: TimeoutCancellationException) {
                message = "Проверка ещё не завершилась. Повторите проверку этой операции."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MaintenanceFailure) {
                message = serverUpdateFailure(failure.code)
                pending = null
                model.secrets.put(pendingKey, "")
            } catch (failure: HttpFailure) {
                message = safeError(failure)
                if (failure.status in setOf(400, 403, 404, 409)) {
                    pending = null
                    model.secrets.put(pendingKey, "")
                }
            } catch (failure: Exception) {
                message = safeError(failure)
            } finally {
                busy = false
            }
        }
    }

    Text("Обновление сервера", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (message.isNotBlank()) OperationMessage(message, error = !busy)
    if (completed.isNotBlank()) {
        Text("Сервер обновлён до $completed. HTTPS, база и TorrServer проверены.")
        Action("Готово", tv) { model.page("server-maintenance") }
        return
    }
    val previous = pending
    if (previous != null) {
        Action("Проверить результат обновления", tv, enabled = !busy) {
            update(previous.getString("version"), previous.getString("operationId"), null)
        }
        return
    }
    if (!available) return

    ServerUpdateForm(installed, candidate, tv, busy) { version, passphrase, ownerPassword ->
        val id = UUID.randomUUID().toString()
        val job = JSONObject().put("operationId", id).put("version", version)
        model.secrets.put(pendingKey, job.toString())
        pending = job
        val request = JSONObject(job.toString()).put("action", "update").put("confirmed", true)
            .put("passphrase", passphrase).put("adminPassword", ownerPassword)
        update(version, id, request)
    }
}

@Composable fun ServerUpdateForm(
    installed: String,
    candidate: String?,
    tv: Boolean,
    busy: Boolean,
    onStart: (version: String, backupPassphrase: String, ownerPassword: String) -> Unit,
) {
    var ownerPassword by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    SettingsCard("Подписанная поставка", Icons.Outlined.SystemUpdate, tv) {
        Text("Установлена версия ${installed.ifBlank { "не проверена" }}")
        Text("Доступная версия: ${candidate ?: "не найдена"}")
        if (candidate != null && newerServerVersion(candidate, installed)) {
            ServerSecret(ownerPassword, { ownerPassword = it }, "Пароль владельца")
            ServerSecret(passphrase, { passphrase = it }, "Парольная фраза резервной копии")
            ServerSecret(repeated, { repeated = it }, "Повторите парольную фразу")
            SettingsHint("Сохраните фразу для восстановления копии. Во время установки просмотр может прерваться.")
            Action("Начать обновление", tv, Modifier.fillMaxWidth(), enabled = !busy && ownerPassword.isNotBlank() &&
                passphrase.length in 16..1024 && passphrase == repeated) { confirmed = true }
        }
    }
    if (confirmed && candidate != null) AlertDialog(
        onDismissRequest = { confirmed = false },
        title = { Text("Установить версию $candidate?") },
        text = { Text("Сервер создаст зашифрованную резервную копию перед установкой.") },
        confirmButton = {
            TextButton(onClick = {
                confirmed = false
                onStart(candidate, passphrase, ownerPassword)
                passphrase = ""
                repeated = ""
                ownerPassword = ""
            }, modifier = Modifier.tvFocusRing(tv)) { Text("Установить") }
        },
        dismissButton = { TextButton(onClick = { confirmed = false }, modifier = Modifier.tvFocusRing(tv)) { Text("Отмена") } },
    )
}

private fun newerServerVersion(candidate: String, current: String): Boolean {
    val newer = candidate.split('.').mapNotNull(String::toIntOrNull)
    val older = current.split('.').mapNotNull(String::toIntOrNull)
    if (newer.size != 3 || older.size != 3) return false
    return newer.zip(older).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } == true
}
