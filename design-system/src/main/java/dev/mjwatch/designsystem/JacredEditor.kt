@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mjwatch.core.endpoint
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.NumberFormat

internal fun jacredStage(stage: String) = when (stage) {
    "download" -> "Скачиваем JacRed и сверяем контрольную сумму…"
    "configure" -> "Готовим сервис и каталог…"
    "start" -> "Запускаем JacRed и проверяем поиск…"
    "ready" -> "JacRed отвечает на запросы."
    else -> "Ждём ответа от задачи на сервере…"
}

internal fun jacredError(code: String) = when (code) {
    "archive_digest", "archive_size", "archive_layout" -> "Архив JacRed не прошёл проверку."
    "unsupported_arch" -> "Для установки нужен Linux на x86-64 или ARM64."
    "existing_unit" -> "На сервере уже есть служба с таким именем."
    "libicu_unavailable" -> "Пакет ICU для JacRed недоступен в репозиториях сервера."
    "startup_failed" -> "JacRed не ответил после запуска. Проверьте свободную память и журнал сервиса."
    "URLError", "HTTPError", "TimeoutExpired" -> "Серверу не удалось скачать или запустить JacRed. Проверьте доступ к GitHub и повторите."
    "interrupted" -> "Сервер прервал установку. Проверьте его состояние и повторите."
    else -> "Сервер не подтвердил установку JacRed. Проверьте состояние и повторите."
}

private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { array ->
    List(array.length()) { array.getString(it) }
}.orEmpty()

@Composable
internal fun JacredEditor(snapshot: JSONObject, tv: Boolean, busy: Boolean, onDirty: (Boolean) -> Unit,
                          onRefresh: () -> Unit, onApply: (JSONObject) -> Unit) {
    val settings = snapshot.getJSONObject("settings")
    val initialSync = settings.getString("syncUrl")
    val initialTrackers = settings.strings("trackers").toSet()
    val initialCollect = settings.getBoolean("collect")
    val initialInterval = settings.getInt("interval").toString()
    val revision = settings.toString()
    var syncUrl by remember(revision) { mutableStateOf(initialSync) }
    var selected by remember(revision) { mutableStateOf(initialTrackers) }
    var collect by remember(revision) { mutableStateOf(initialCollect) }
    var interval by remember(revision) { mutableStateOf(initialInterval) }
    val installed = snapshot.optBoolean("installed")
    val running = snapshot.optBoolean("running")
    val operation = snapshot.optJSONObject("operation")
    val installing = operation?.optString("state") == "running"
    val changed = syncUrl.trim() != initialSync || selected != initialTrackers || collect != initialCollect || interval != initialInterval
    val refreshMinutes = interval.toIntOrNull()
    val syncValid = syncUrl.isBlank() || runCatching { endpoint(syncUrl).scheme == "https" }.getOrDefault(false)
    val configured = collect || syncUrl.isNotBlank()
    val valid = syncValid && refreshMinutes in 30..1440 && (!configured || selected.isNotEmpty())
    LaunchedEffect(changed) { onDirty(changed) }

    SettingsCard("Состояние сервиса", Icons.Outlined.Dns, tv) {
        Text(
            when {
                installing -> jacredStage(operation?.optString("stage").orEmpty())
                running -> "Запущен · ${snapshot.optString("version")}"
                installed -> "Установлен · служба остановлена"
                else -> "Готов к установке"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (operation?.optString("state") == "failed") {
            Text(jacredError(operation.optString("code")), color = MaterialTheme.colorScheme.error)
        }
        snapshot.optLong("catalogCount", -1).takeIf { it >= 0 }?.let { count ->
            Text("Раздач в каталоге: ${NumberFormat.getIntegerInstance().format(count)}",
                style = MaterialTheme.typography.bodyMedium)
        }
        val indexing = snapshot.optJSONObject("indexing")
        indexing?.optLong("updated")?.takeIf { it > 0 }?.let { seconds ->
            val updated = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
            Text("Сбор проверен ${DateTimeFormatter.ofPattern("dd.MM HH:mm").format(updated)}",
                style = MaterialTheme.typography.bodyMedium)
            val failures = indexing.strings("failed")
            if (failures.isNotEmpty()) Text("Ошибки запросов: ${failures.joinToString()}", color = MaterialTheme.colorScheme.error)
        }
        if (running && indexing == null && initialCollect) {
            SettingsHint("Первый сбор запланирован. Раздачи появятся по мере наполнения каталога.")
        }
        Action("Обновить состояние", tv, secondary = true, enabled = !busy, icon = Icons.Outlined.Refresh, onClick = onRefresh)
    }

    SettingsCard("Наполнение каталога", Icons.Outlined.CloudDownload, tv) {
        SettingsToggle("Собирать с трекеров", collect, { collect = it }, description = "JacRed обновляет каталог на сервере по расписанию.")
        ServerField(syncUrl, { syncUrl = it }, "HTTPS-адрес сервера синхронизации")
        SettingsHint("Оставьте адрес пустым, если наполняете каталог только с трекеров.")
        if (!syncValid) Text("Введите HTTPS-адрес без ключа или параметров запроса.", color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30 to "30 мин", 60 to "1 ч", 120 to "2 ч", 360 to "6 ч", 1440 to "Раз в день").forEach { (minutes, title) ->
                Pill(title, refreshMinutes == minutes, tv) { interval = minutes.toString() }
            }
        }
        if (!configured) SettingsHint("Включите сбор или укажите источник синхронизации, чтобы каталог начал заполняться.")
    }

    SettingsDisclosure("Трекеры", "Выбрано ${selected.size}", Icons.Outlined.Search, tv, initiallyOpen = !installed) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            snapshot.strings("trackers").forEach { tracker ->
                Pill(tracker, tracker in selected, tv) { selected = if (tracker in selected) selected - tracker else selected + tracker }
            }
        }
        if (selected.isEmpty() && configured) SettingsHint("Выберите хотя бы один трекер.")
    }
    Action(
        if (!installed) "Установить JacRed" else if (!running && !changed) "Запустить JacRed" else "Сохранить настройки",
        tv, Modifier.fillMaxWidth(), enabled = !busy && !installing && valid && (!running || changed),
        icon = if (installed) Icons.Outlined.Check else Icons.Outlined.CloudDownload, loading = busy,
    ) {
        onApply(JSONObject().put("syncUrl", syncUrl.trim()).put("trackers", JSONArray(selected.sorted()))
            .put("collect", collect).put("interval", refreshMinutes))
    }
}
