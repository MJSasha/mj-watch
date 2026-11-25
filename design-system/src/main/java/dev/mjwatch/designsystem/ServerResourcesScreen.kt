package dev.mjwatch.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mjwatch.core.Config
import dev.mjwatch.core.HttpFailure
import dev.mjwatch.core.ManagedApi
import dev.mjwatch.core.SecretStore
import dev.mjwatch.core.safeError
import dev.mjwatch.core.scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun ServerResourcesScreen(config: Config, secrets: SecretStore, tv: Boolean) {
    var data by remember(config.scope, config.managedUrl) { mutableStateOf<JSONObject?>(null) }
    var history by remember(config.scope, config.managedUrl) { mutableStateOf(emptyList<Float>()) }
    var error by remember(config.scope, config.managedUrl) { mutableStateOf("") }
    var loading by remember(config.scope, config.managedUrl) { mutableStateOf(false) }
    var refresh by remember(config.scope, config.managedUrl) { mutableIntStateOf(0) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(config.scope, config.managedUrl, refresh, visible) {
        if (!visible) return@LaunchedEffect
        val api by lazy { ManagedApi(config, secrets) }
        while (true) {
            loading = true
            try {
                val sample = withContext(Dispatchers.IO) {
                    api.authorized("admin/resources")
                }
                data = sample
                sample.optJSONObject("cpu").percentage()?.let { history = (history + it).takeLast(18) }
                error = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val status = (failure as? HttpFailure)?.status
                if (status in setOf(401, 403)) {
                    data = null
                    history = emptyList()
                }
                error = when (status) {
                    404 -> "Для мониторинга обновите сервер MJWatch до 0.1.16."
                    403 -> "Ресурсы сервера доступны администратору."
                    else -> safeError(failure)
                }
                if (status in setOf(401, 403, 404)) break
            } finally {
                loading = false
            }
            delay(5_000)
        }
    }
    ServerResourcesPanel(data, history, error, loading, tv) { refresh++ }
}

@Composable
internal fun ServerResourcesPanel(
    data: JSONObject?, history: List<Float>, error: String, loading: Boolean, tv: Boolean, onRefresh: () -> Unit,
) {
    SettingsCard("Состояние сервера", Icons.Outlined.Dns, tv) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when {
                        error.isNotBlank() -> "Не удалось обновить"
                        data != null -> "Сервер отвечает"
                        else -> "Подключаемся к серверу"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        error.isNotBlank() -> MaterialTheme.colorScheme.error
                        data != null -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                val updated = data?.optLong("checkedAt")?.takeIf { it > 0 }?.let { seconds ->
                    val time = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
                    DateTimeFormatter.ofPattern("HH:mm:ss").format(time)
                }
                Text(updated?.let { "Последнее обновление · $it" } ?: "Ожидаем первый замер",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (loading && data == null && error.isBlank()) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh, enabled = !loading || data != null, modifier = Modifier.tvFocusRing(tv)) {
                        Icon(Icons.Outlined.Refresh, "Обновить ресурсы")
                    }
                }
            }
        }
        if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }

    val cpu = data?.optJSONObject("cpu")
    val cpuPercent = cpu.percentage()
    val cores = cpu?.optInt("cores")?.takeIf { it > 0 }
    val memory = data?.optJSONObject("memory")
    val ramTotal = memory?.optLong("totalBytes")?.takeIf { it > 0 }
    val ramAvailable = memory?.optLong("availableBytes", -1)?.takeIf { ramTotal != null && it in 0..ramTotal }
    val ramUsed = if (ramTotal != null && ramAvailable != null) ramTotal - ramAvailable else null
    val cpuCard: @Composable (Modifier) -> Unit = { modifier ->
        ResourceCard(modifier, "Процессор", Icons.Outlined.Speed, cpuPercent, cpuPercent?.asPercent() ?: "—",
            cores?.let { "Логических ядер: $it" } ?: "Данные процессора пока недоступны") {
            Box(Modifier.fillMaxWidth().height(38.dp)) {
                if (history.size > 1) CpuTrend(history)
            }
        }
    }
    val ramCard: @Composable (Modifier) -> Unit = { modifier ->
        val value = if (ramUsed != null) "${sizeLabel(ramUsed)} / ${sizeLabel(ramTotal)}" else "—"
        ResourceCard(modifier, "Оперативная память", Icons.Outlined.Memory, usedPercent(ramUsed, ramTotal), value,
            ramAvailable?.let { "Доступно ${sizeLabel(it)}" } ?: "Данные памяти пока недоступны")
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 570.dp) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                cpuCard(Modifier.weight(1f).fillMaxHeight())
                ramCard(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cpuCard(Modifier.fillMaxWidth())
                ramCard(Modifier.fillMaxWidth())
            }
        }
    }

    val disks = data?.optJSONArray("disks")
    if (disks == null || disks.length() == 0) {
        ResourceCard(Modifier.fillMaxWidth(), "Диск", Icons.Outlined.Storage, null, "—", "Данные диска пока недоступны")
    } else {
        for (position in 0 until disks.length()) {
            val disk = disks.optJSONObject(position) ?: continue
            val total = disk.optLong("totalBytes")
            val used = disk.optLong("usedBytes")
            val available = disk.optLong("availableBytes")
            ResourceCard(
                Modifier.fillMaxWidth(),
                if (disk.optString("id") == "data") "Диск данных MJWatch" else "Системный диск",
                Icons.Outlined.Storage, usedPercent(used, total),
                "${sizeLabel(used)} / ${sizeLabel(total)}", "Доступно ${sizeLabel(available)}",
            )
        }
    }
}

@Composable
private fun ResourceCard(
    modifier: Modifier, title: String, icon: ImageVector, percent: Float?, value: String, caption: String,
    detail: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val accent = if (percent != null && percent >= 90f) colors.error else colors.primary
    val fill by animateFloatAsState((percent ?: 0f) / 100f, label = title)
    Surface(modifier, shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).clip(MaterialTheme.shapes.medium).background(accent.copy(alpha = .11f)),
                    contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(20.dp), tint = accent)
                }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                percent?.asPercent()?.takeIf { it != value }?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = accent)
                }
            }
            Text(value, style = MaterialTheme.typography.titleLarge)
            LinearProgressIndicator(progress = { fill }, modifier = Modifier.fillMaxWidth(), color = accent)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            detail()
        }
    }
}

@Composable
private fun CpuTrend(values: List<Float>) {
    val accent = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(38.dp)) {
        val line = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1)
            val y = size.height * (1f - value / 100f)
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        drawPath(line, color = accent, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun JSONObject?.percentage(): Float? =
    this?.optDouble("percent")?.toFloat()?.takeIf { it in 0f..100f }

private fun usedPercent(used: Long?, total: Long?): Float? =
    if (used != null && total != null && total > 0) (used.toDouble() * 100 / total).toFloat().coerceIn(0f, 100f) else null

private fun Float.asPercent() = "${roundToInt()}%"
