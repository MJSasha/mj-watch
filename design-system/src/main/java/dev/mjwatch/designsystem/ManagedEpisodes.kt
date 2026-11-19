package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable internal fun ManagedEpisodes(item:Metadata,config:Config,model:WatchModel,tv:Boolean) {
 var data by remember(item.id){mutableStateOf<JSONObject?>(null)}
 var season by remember(item.id){mutableStateOf<Int?>(null)}
 var busy by remember(item.id){mutableStateOf(false)}
 var message by remember(item.id){mutableStateOf("")}
 val scope=rememberCoroutineScope()
 fun load(number:Int?){if(busy)return;busy=true;message="";scope.launch {try {val result=withContext(Dispatchers.IO){val id=item.id.substringAfterLast(':');require(id.matches(Regex("[0-9]+")));ManagedApi(config,model.secrets).authorized("metadata/tv/$id"+(number?.let{"/seasons/$it"} ?: ""))};data=result;season=number}catch(e:CancellationException){throw e}catch(e:Exception){message=safeError(e)}finally{busy=false}}}
 SettingsCard("Сезоны и эпизоды",Icons.Outlined.VideoLibrary,tv) {
  if(data==null) {
   Text("Выберите эпизод, чтобы найти подходящие раздачи.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Action("Выбрать сезон",tv,Modifier.fillMaxWidth(),secondary=true,enabled=!busy,icon=Icons.Outlined.Layers){load(null)}
  } else {
   Action("Выбрать сезон",tv,secondary=true,enabled=!busy,icon=Icons.Outlined.Layers){load(null)}
   if(season!=null)Badge("Сезон $season",accent=true)
  }
  if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
  if(message.isNotBlank())OperationMessage(message,error=true)
  val rows=data?.optJSONArray(if(season==null)"seasons" else "episodes")
  if(rows!=null) {
   if(rows.length()==0)SettingsHint(if(season==null)"Сведения о сезонах пока недоступны. Вы можете выбрать раздачу целиком." else "Сведения о сериях пока недоступны. Вы можете выбрать раздачу сезона.")
   else SettingsGroup(tv) {
     for(index in 0 until rows.length()) {
      val row=rows.getJSONObject(index)
      if(index>0)SettingsDivider()
      if(season==null) {
       val number=row.getInt("season_number")
       SettingsActionRow(Icons.Outlined.Layers,row.optString("name").ifBlank{"Сезон $number"},"Эпизодов: ${row.optInt("episode_count")}",tv){if(!busy)load(number)}
      } else {
       val episode=row.getInt("episode_number")
       SettingsActionRow(Icons.Outlined.PlayCircleOutline,"Серия $episode",row.optString("name").ifBlank{"Найти раздачи"},tv){if(!busy)model.findEpisodeReleases(item,season!!,episode)}
      }
     }
   }
   Text("Поиск раздач для выбранного эпизода. Файл для просмотра выбирается отдельно.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}
