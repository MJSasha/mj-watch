@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mjwatch.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val activateManualCollection = snapshot.has("manualIndex") && !snapshot.optBoolean("manualIndex")
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
        if (!installed) "Установить JacRed" else if (!running && !changed) "Запустить JacRed"
        else if (activateManualCollection && !changed) "Применить настройки" else "Сохранить настройки",
        tv, Modifier.fillMaxWidth(), enabled = !busy && !installing && valid && (!running || changed || activateManualCollection),
        icon = if (installed) Icons.Outlined.Check else Icons.Outlined.CloudDownload, loading = busy,
    ) {
        onApply(JSONObject().put("syncUrl", syncUrl.trim()).put("trackers", JSONArray(selected.sorted()))
            .put("collect", collect).put("interval", refreshMinutes))
    }
}

@Composable
internal fun JacredDiagnosticsPanel(
    data: JSONObject?, tv: Boolean, busy: Boolean, catalog: JSONObject?, catalogLoading: Boolean,
    catalogError: String, onCatalogRefresh: () -> Unit, onSettings: () -> Unit,
    onCollect: () -> Unit, onRefresh: (String) -> Unit,
) {
    val installed = data?.optBoolean("installed") == true
    val searchReady = data?.optBoolean("searchReady") == true
    val index = data?.optJSONObject("index")
    val state = index?.optString("state")
    val queued = data?.optBoolean("collectQueued") == true
    val active = state == "running"
    val manual = data?.optBoolean("manualCollect") == true
    val collection = data?.opt("collect") as? Boolean
    val sync = data?.opt("syncConfigured") as? Boolean
    val number = NumberFormat.getIntegerInstance()
    SettingsCard("Сводка", Icons.Outlined.Dns, tv) {
        Text(
            when {
                data == null -> "Ожидаем данные сервера"
                !installed -> "JacRed ещё не установлен"
                searchReady -> "Поиск отвечает"
                else -> "Поиск недоступен"
            },
            style = MaterialTheme.typography.titleLarge,
            color = when {
                searchReady -> MaterialTheme.colorScheme.primary
                installed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        if (installed) {
            if (collection == true) Text("Сбор по расписанию · каждые ${data?.optInt("interval")} мин")
            if (sync == true) Text("Синхронизация настроена")
            if (collection == false && sync == false) SettingsHint("Включите сбор или настройте синхронизацию.")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (installed && manual && collection == true) {
                Action(
                    if (active) "Сбор идёт" else if (queued) "В очереди" else "Запустить сбор",
                    tv, enabled = !busy && !active && !queued && searchReady, icon = Icons.Outlined.PlayArrow, onClick = onCollect,
                )
            } else if (data != null) {
                Action(if (installed) "Настроить сбор" else "Установить JacRed", tv, onClick = onSettings)
            }
            Action("Обновить статус", tv, secondary = true, enabled = !busy, icon = Icons.Outlined.Refresh) { onRefresh("") }
        }
    }
    if (!installed || data == null) return

    val results = index?.optJSONArray("results")
    val rows = (0 until (results?.length() ?: 0)).mapNotNull { results?.optJSONObject(it) }
    val unfinished = setOf("pending", "running")
    val failed = setOf("http_error", "parser_error", "timeout", "connection_error")
    val completed = rows.count { it.optString("state") !in unfinished }
    SettingsCard("Сбор раздач", Icons.Outlined.Schedule, tv) {
        Text(
            when {
                active -> "Сбор идёт · $completed из ${rows.size}"
                queued -> "Запуск в очереди"
                state == "complete" -> "Последний сбор завершён"
                state == "interrupted" -> "Сбор прервался"
                else -> "Ожидаем первый сбор"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (active && rows.isNotEmpty()) {
            val progress by animateFloatAsState(completed.toFloat() / rows.size, label = "JacRed collection")
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        if (queued && !active) LinearProgressIndicator(Modifier.fillMaxWidth())
        index?.optLong("updatedAt")?.takeIf { it > 0 }?.let { Text("Последнее событие · ${jacredTime(it)}") }
        rows.forEach { row ->
            val rowState = row.optString("state")
            val status = when (rowState) {
                "pending" -> if (state == "interrupted") "Не начато" else "В очереди"
                "running" -> if (state == "interrupted") "Прервано" else "Запрос выполняется"
                "responded" -> "Парсер ответил"
                "busy" -> "Парсер уже занят"
                "disabled" -> "Парсер отключён"
                "http_error" -> "HTTP ${row.optInt("httpCode")}"
                "parser_error" -> "Парсер сообщил об ошибке"
                "timeout" -> "Истекло время ожидания"
                "connection_error" -> "Ошибка подключения"
                else -> "Статус неизвестен"
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.optString("tracker"), Modifier.weight(1f))
                Text(status, color = when {
                    rowState in failed -> MaterialTheme.colorScheme.error
                    rowState == "running" && active -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
            }
        }
    }

    JacredCatalogPanel(catalog, catalogLoading, catalogError, tv, onCatalogRefresh)

    val history = index?.optJSONArray("history")
    if (history != null && history.length() > 0) SettingsDisclosure(
        "История запусков", "Запусков: ${history.length()}", Icons.Outlined.Schedule, tv,
    ) {
        for (position in 0 until history.length()) {
            val run = history.optJSONObject(position) ?: continue
            val trigger = if (run.optString("trigger") == "manual") "Вручную" else "По расписанию"
            val interrupted = run.optString("state") == "interrupted"
            val result = if (interrupted) "Прерван" else "${run.optInt("total")} трекеров · ошибок ${run.optInt("failed")}"
            Column {
                Text("$trigger · ${jacredTime(run.optLong("startedAt"))}", style = MaterialTheme.typography.titleSmall)
                Text(result, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    SettingsDisclosure("Проверка поиска", "Посчитать результаты по названию", Icons.Outlined.Search, tv) {
        var query by rememberSaveable { mutableStateOf("") }
        var submitted by rememberSaveable { mutableStateOf("") }
        var found by rememberSaveable { mutableStateOf<Int?>(null) }
        LaunchedEffect(data) {
            if (data.has("probeCount")) found = data.optInt("probeCount")
        }
        ServerField(query, { query = it.take(120) }, "Название фильма или сериала")
        Action("Проверить запрос", tv, secondary = true, enabled = !busy && query.isNotBlank()) {
            submitted = query.trim()
            found = null
            onRefresh(submitted)
        }
        if (query.trim() == submitted) found?.let {
            Text("Найдено: ${number.format(it)}", style = MaterialTheme.typography.titleMedium)
        }
    }

}

@Composable
private fun JacredCatalogPanel(data: JSONObject?, loading: Boolean, error: String, tv: Boolean, onRefresh: () -> Unit) {
    val number = NumberFormat.getIntegerInstance()
    val count = data?.optLong("catalogCount", -1) ?: -1
    val trackers = data?.optJSONArray("trackers")
    val updated = data?.optLong("statsUpdatedAt", 0) ?: 0
    SettingsCard("Статистика каталога", Icons.Outlined.BarChart, tv) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JacredMetric(Modifier.weight(1f), "Раздачи", if (count >= 0) number.format(count) else "—")
            JacredMetric(Modifier.weight(1f), "Трекеры", trackers?.length()?.let(number::format) ?: "—")
        }
        Text(
            when {
                error.isNotBlank() -> error
                updated > 0 -> "Статистика на ${jacredTime(updated)}"
                count >= 0 -> "Получены данные каталога"
                loading -> "Загружаем статистику…"
                else -> "Первая сводка ещё не готова"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count == 0L) SettingsHint("Каталог пока пуст. Запустите сбор или проверьте источник синхронизации.")
        Action("Обновить статистику", tv, secondary = true, enabled = !loading, loading = loading,
            icon = Icons.Outlined.Refresh, onClick = onRefresh)
    }
    if (trackers == null || trackers.length() == 0) return
    SettingsDisclosure("Каталог по трекерам", "Источников: ${trackers.length()}", Icons.Outlined.Search, tv) {
        val rows = (0 until trackers.length()).mapNotNull { trackers.optJSONObject(it) }
        for (row in rows.sortedByDescending { it.optLong("alltorrents") }) {
            val entries = row.optLong("alltorrents")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.optString("trackerName"))
                    Text(number.format(entries), style = MaterialTheme.typography.titleSmall)
                }
                if (count > 0) {
                    val fraction = (entries.toDouble() / count).toFloat().coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}

@Composable
private fun JacredMetric(modifier: Modifier, label: String, value: String) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun jacredTime(seconds: Long): String = if (seconds > 0) {
    val time = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
    DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").format(time)
} else "Время неизвестно"
