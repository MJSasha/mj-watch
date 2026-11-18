package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable internal fun PlaybackLimitsPanel(s:WatchState,model:WatchModel,tv:Boolean,onDirty:(Boolean)->Unit,onSaved:()->Unit){
 val scope=rememberCoroutineScope()
 var snapshot by remember(s.config.scope){mutableStateOf<JSONObject?>(null)}
 var draft by remember(s.config.scope){mutableStateOf(JSONObject())}
 var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")};var unsupported by remember{mutableStateOf(false)}
 val dirty=snapshot?.getJSONObject("settings")?.toString()?.let{it!=draft.toString()} ?: false
 LaunchedEffect(dirty){onDirty(dirty)}
 fun load(){if(busy)return;busy=true;scope.launch{try{
  val data=withContext(Dispatchers.IO){ManagedApi(s.config,model.secrets).authorized("admin/playback/settings")}
  snapshot=data;draft=JSONObject(data.getJSONObject("settings").toString());unsupported=false;message=""
 }catch(e:CancellationException){throw e}catch(e:Exception){unsupported=e is HttpFailure&&e.status==404;message=if(unsupported)"Обновите сервер, чтобы настраивать одновременные просмотры." else safeError(e)}finally{busy=false}}}
 LaunchedEffect(s.config.scope){load()}
 if(snapshot==null)TorrCard("Одновременные просмотры",Icons.Outlined.PlayCircleOutline,tv){
  if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
  if(message.isNotBlank())Text(message,style=MaterialTheme.typography.bodyMedium)
  if(unsupported)TextButton(onClick={model.page("server-update")},modifier=Modifier.tvFocusRing(tv)){Text("Обновить сервер")}
  else if(!busy)TextButton(onClick={load()},modifier=Modifier.tvFocusRing(tv)){Text("Повторить загрузку")}
 }else{
  PlaybackLimitsEditor(snapshot!!,draft,tv,busy,{draft=it}){
   if(!busy){busy=true;scope.launch{try{
    val result=withContext(Dispatchers.IO){ManagedApi(s.config,model.secrets).authorized("admin/playback/settings","PATCH",draft,snapshot!!.getString("revision"))}
    snapshot=result;draft=JSONObject(result.getJSONObject("settings").toString());message="Лимиты сохранены. Текущие просмотры продолжаются.";onSaved()
   }catch(e:CancellationException){throw e}catch(e:Exception){message=safeError(e)}finally{busy=false}}}
  }
  if(message.isNotBlank())Text(message,style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
  if(dirty||message.isNotBlank())TextButton(onClick={load()},enabled=!busy,modifier=Modifier.tvFocusRing(tv)){Text(if(dirty)"Перечитать лимиты и сбросить изменения" else "Обновить лимиты")}
 }
}

@Composable fun PlaybackLimitsEditor(snapshot:JSONObject,draft:JSONObject,tv:Boolean,busy:Boolean=false,onChange:(JSONObject)->Unit,onSave:()->Unit){
 val recommended=snapshot.optInt("recommendedMaxStreams",1)
 val maximum=snapshot.optInt("maximumStreams",8)
 val total=draft.optInt("maxStreams").takeIf{it>0} ?: recommended
 val dirty=draft.toString()!=snapshot.getJSONObject("settings").toString()
 fun change(name:String,n:Int){
  val next=JSONObject(draft.toString()).put(name,n)
  val nextTotal=if(n==0)recommended else n
  if(name=="maxStreams"&&next.optInt("maxStreamsPerUser")>nextTotal)next.put("maxStreamsPerUser",0)
  onChange(next)
 }

 TorrCard("Одновременные просмотры",Icons.Outlined.PlayCircleOutline,tv){
  Text("Лимиты действуют на весь сервер и на каждый аккаунт.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  LimitChoice("На сервере",draft.optInt("maxStreams"),recommended,maximum,tv,busy){change("maxStreams",it)}
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)){
   AdviceTile("Рекомендуем по RAM","Авто · $recommended",tv,Modifier.weight(1f).fillMaxHeight()){if(!busy)change("maxStreams",0)}
   Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.small,modifier=Modifier.weight(1f).fillMaxHeight()){Column(Modifier.padding(12.dp)){Text("Поддерживаемый максимум",style=MaterialTheme.typography.labelSmall);Text("$maximum просмотров",style=MaterialTheme.typography.labelLarge)}}
  }
  if(total>recommended)Text("Выше рекомендации по памяти. При нескольких просмотрах возможна буферизация.",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.tertiary)
  LimitChoice("На один аккаунт",draft.optInt("maxStreamsPerUser").coerceAtMost(total),minOf(2,total),total,tv,busy){change("maxStreamsPerUser",it)}
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)){
   AdviceTile("Рекомендуем","Авто · ${minOf(2,total)}",tv,Modifier.weight(1f).fillMaxHeight()){if(!busy)change("maxStreamsPerUser",0)}
   Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.small,modifier=Modifier.weight(1f).fillMaxHeight()){Column(Modifier.padding(12.dp)){Text("Максимум по серверу",style=MaterialTheme.typography.labelSmall);Text("$total просмотров",style=MaterialTheme.typography.labelLarge)}}
  }
  Text("Уменьшение лимита не прерывает уже открытое видео.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Action("Сохранить лимиты",tv,Modifier.fillMaxWidth(),enabled=dirty&&!busy,icon=Icons.Outlined.Check,onClick=onSave)
 }
}

@Composable private fun LimitChoice(label:String,value:Int,automatic:Int,maximum:Int,tv:Boolean,busy:Boolean,onChange:(Int)->Unit){
 var expanded by remember{mutableStateOf(false)}
 Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
  Text(label,style=MaterialTheme.typography.labelLarge)
  Box{
   OutlinedButton(onClick={expanded=true},enabled=!busy,modifier=Modifier.fillMaxWidth().tvFocusRing(tv).semantics{contentDescription=label}){
    Text(if(value==0)"Авто · $automatic" else "$value",modifier=Modifier.weight(1f));Icon(Icons.Outlined.ExpandMore,null)
   }
   DropdownMenu(expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=360.dp)){
    for(n in 0..maximum)DropdownMenuItem(text={Text(if(n==0)"Авто · $automatic" else "$n")},onClick={expanded=false;onChange(n)})
   }
  }
 }
}
