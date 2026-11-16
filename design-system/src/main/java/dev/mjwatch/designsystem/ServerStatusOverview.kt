package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ServerStatusOverview(url:String,role:String?,data:JSONObject?,loading:Boolean) {
    val unknown=if(loading)"Проверяем…" else "Нет данных"
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.Dns,null,tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(url,style=MaterialTheme.typography.bodyMedium)
                    Text(when(role){"admin"->"Администратор";"member"->"Участник";else->if(loading)"Проверяем права…" else "Права не получены"},style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),maxItemsInEachRow=3) {
            ServerStatusTile("TorrServer",data?.let {if(it.optBoolean("torrserverHealthy"))"Работает" else "Недоступен"} ?: unknown,Icons.Outlined.Dns,data?.optBoolean("torrserverHealthy"))
            ServerStatusTile("База данных",data?.let {if(it.optBoolean("databaseHealthy"))"Проверена" else "Ошибка"} ?: unknown,Icons.Outlined.Storage,data?.optBoolean("databaseHealthy"))
            ServerStatusTile("Потоки",data?.let {it.optInt("activeStreams").toString()} ?: "—",Icons.Outlined.PlayCircle,null)
        }
    }
}

@Composable private fun ServerStatusTile(title:String,value:String,icon:ImageVector,healthy:Boolean?) {
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.widthIn(min=140.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Icon(icon,null,tint=if(healthy==false)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(title,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,color=if(healthy==false)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}
