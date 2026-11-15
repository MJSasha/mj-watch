package dev.mjwatch.designsystem
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable internal fun ManagedEpisodes(item:Metadata,config:Config,model:WatchModel,tv:Boolean) {
 var data by remember(item.id){mutableStateOf<JSONObject?>(null)}
 var season by remember(item.id){mutableStateOf<Int?>(null)}
 var busy by remember{mutableStateOf(false)}
 var message by remember{mutableStateOf("")}
 val scope=rememberCoroutineScope()
 fun load(number:Int?){if(busy)return;busy=true;message="";scope.launch {try {val result=withContext(Dispatchers.IO){val id=item.id.substringAfterLast(':');require(id.matches(Regex("[0-9]+")));ManagedApi(config,model.secrets).authorized("metadata/tv/$id"+(number?.let{"/seasons/$it"} ?: ""))};data=result;season=number}catch(e:CancellationException){throw e}catch(e:Exception){message=safeError(e)}finally{busy=false}}}
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Action(if(data==null)"Сезоны и эпизоды" else "Выбрать сезон",tv,secondary=true,enabled=!busy){load(null)}
  if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
  if(message.isNotBlank())Text(message,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
  val rows=data?.optJSONArray(if(season==null)"seasons" else "episodes")
  if(rows!=null)for(index in 0 until rows.length()) {
   val row=rows.getJSONObject(index)
   if(season==null){val number=row.getInt("season_number");Action(row.optString("name","Сезон $number")+" · ${row.optInt("episode_count")} эпизодов",tv,secondary=true){load(number)}}
   else {val episode=row.getInt("episode_number");Action("${episode}. ${row.optString("name")}",tv,secondary=true){model.search("${item.title} S%02dE%02d".format(season,episode))}}
  }
  if(data!=null)Text("Поиск раздач для выбранного эпизода. Файл для просмотра выбирается отдельно.",color=MaterialTheme.colorScheme.onSurfaceVariant)
 }
}
