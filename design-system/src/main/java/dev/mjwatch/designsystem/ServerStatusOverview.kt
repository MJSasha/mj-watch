package dev.mjwatch.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** One compact telemetry strip; management actions stay in the settings list below. */
@Composable fun ServerStatusOverview(url:String,role:String?,data:JSONObject?,loading:Boolean,failed:Boolean,checkedAt:Long,tv:Boolean,onRefresh:()->Unit) {
    val colors=MaterialTheme.colorScheme
    val status=when {failed->"Нет связи";data!=null->"Сервер доступен";loading->"Подключаемся…";else->"Нет данных"}
    val statusColor=when {failed->colors.error;data!=null->colors.primary;else->colors.onSurfaceVariant}
    Surface(shape=MaterialTheme.shapes.large,color=colors.surfaceContainer) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(statusColor.copy(alpha=.07f),colors.surfaceContainer))).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(36.dp).background(statusColor.copy(alpha=.12f),RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
                    Icon(if(failed)Icons.Outlined.CloudOff else Icons.Outlined.Dns,null,Modifier.size(20.dp),tint=statusColor)
                }
                Text(status,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,color=statusColor,modifier=Modifier.weight(1f).semantics {liveRegion=LiveRegionMode.Polite})
                if(loading) CircularProgressIndicator(Modifier.size(16.dp).semantics {contentDescription="Обновление статуса"},strokeWidth=2.dp)
                else IconButton(onClick=onRefresh,modifier=Modifier.size(48.dp).tvFocusRing(tv)) {Icon(Icons.Outlined.Refresh,"Обновить статус",Modifier.size(20.dp))}
            }
            Text(url,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            HorizontalDivider(color=colors.outlineVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                StatusMetric(Icons.Outlined.Dns,"TorrServer",data?.let {if(it.optBoolean("torrserverHealthy"))"Работает" else "Недоступен"} ?: "—",data?.optBoolean("torrserverHealthy"),Modifier.weight(1f))
                StatusMetric(Icons.Outlined.Storage,"База данных",data?.let {if(it.optBoolean("databaseHealthy"))"В норме" else "Ошибка"} ?: "—",data?.optBoolean("databaseHealthy"),Modifier.weight(1f))
                StatusMetric(Icons.Outlined.GraphicEq,"Потоки",data?.optInt("activeStreams")?.toString() ?: "—",null,Modifier.weight(1f))
            }
            val details=listOfNotNull(
                when(role){"admin"->"Администратор";"member"->"Участник";else->null},
                data?.optString("version")?.takeIf {it.isNotBlank()}?.let {"v$it"},
                if(failed&&data!=null)"Последние данные" else checkedAt.takeIf {it>0}?.let {"Обновлено ${java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.getDefault()).format(java.util.Date(it))}"})
            if(details.isNotEmpty()) Text(details.joinToString(" · "),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
        }
    }
}

@Composable private fun StatusMetric(icon:ImageVector,title:String,value:String,healthy:Boolean?,modifier:Modifier) {
    val colors=MaterialTheme.colorScheme
    val accent=when {healthy==false->colors.error;healthy==true->colors.primary;title=="Потоки"&&value!="—"->colors.tertiary;else->colors.onSurfaceVariant}
    Column(modifier.background(accent.copy(alpha=.06f),RoundedCornerShape(12.dp)).padding(horizontal=10.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Icon(icon,null,Modifier.size(20.dp),tint=accent)
        Text(title,style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,color=accent)
    }
}
