package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mjwatch.core.*
import org.json.JSONObject

/** The production editor also accepts snapshots in native UI regression tests. */
@Composable fun TorrServerEditor(snapshot:JSONObject,draft:JSONObject,tv:Boolean,onChange:(JSONObject)->Unit,onDefaults:()->Unit){
 val fields=snapshot.getJSONArray("fields");val all=(0 until fields.length()).map{fields.getJSONObject(it)}
 fun change(name:String,value:Any)=onChange(JSONObject(draft.toString()).put(name,value))
 @Composable fun field(name:String){all.firstOrNull{it.optString("name")==name&&draft.has(name)}?.let {TorrInput(snapshot,draft,it,tv){value->change(name,value)}}}
 val settings=snapshot.getJSONObject("settings")
 val savedMode=torrCacheUsesDisk(settings)
 val selectedMode=torrCacheUsesDisk(draft)
 val budget=torrCacheBudget(snapshot,draft)
 SettingsCard("Кэш видео",Icons.Outlined.Memory,tv){
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
   Text("Режим по данным сервера",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Text(when(savedMode){true->"Диск";false->"ОЗУ";null->"Неизвестен"},style=MaterialTheme.typography.labelLarge)
  }
  Text("Где хранить загруженные части видео",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
   for((disk,label) in listOf(false to "В памяти",true to "На диске")){
    OutlinedButton(onClick={change("UseDisk",disk)},modifier=Modifier.weight(1f).tvFocusRing(tv).semantics{selected=selectedMode==disk},colors=ButtonDefaults.outlinedButtonColors(containerColor=if(selectedMode==disk)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)){
     Icon(if(disk)Icons.Outlined.Storage else Icons.Outlined.Memory,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(label)
    }
   }
  }
  if(selectedMode!=savedMode)Text("Режим изменится после сохранения",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(budget!=null){
   val free=budget.optLong("availableBytes")/1048576
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
    Text(if(selectedMode==true)"Свободно на диске" else "Свободно в ОЗУ",color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text(if(selectedMode==true)"$free МиБ" else "$free / ${budget.optLong("totalBytes")/1048576} МиБ",style=MaterialTheme.typography.labelLarge)
   }
   if(selectedMode==false)LinearProgressIndicator(progress={ (budget.optDouble("availableBytes")/budget.optDouble("totalBytes")).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
   budget.optLong("reserveBytes").takeIf {it>0}?.let {reserve->
    Text(if(selectedMode==true)"Резерв на диске — ${reserve/1048576} МиБ" else "Резерв ОЗУ для системы и сервисов — ${reserve/1048576} МиБ",
     style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }else if(selectedMode!=null){
   val pendingPath=selectedMode&&draft.optString("TorrentsSavePath")!=settings.optString("TorrentsSavePath")
   Text(when {
    pendingPath->"Доступное место для нового каталога будет проверено при сохранении"
    selectedMode->"Свободное место на диске неизвестно"
    else->"Свободная ОЗУ неизвестна"
   },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
  field("CacheSize")
  Text("Размер кэша — на одну раздачу. Разные раздачи используют отдельные кэши.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  snapshot.optJSONObject("resources")?.optInt("plannedTorrents",0)?.takeIf{budget!=null&&it>0}?.let{count->Text("Оценка максимума рассчитана на $count раздач с учётом лимита просмотров.",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
  field("PreloadCache")
 }
 var network by rememberSaveable{mutableStateOf(false)}
 SettingsGroup(tv){
  SettingsActionRow(Icons.Outlined.Lan,"Сеть",if(network)"Скрыть параметры" else "Соединения и скорость",tv){network=!network}
  if(network)Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){field("ConnectionsLimit");field("DownloadRateLimit");field("UploadRateLimit")}
 }
 var advanced by rememberSaveable{mutableStateOf(false)}
 val basic=setOf("UseDisk","CacheSize","PreloadCache","ConnectionsLimit","DownloadRateLimit","UploadRateLimit")
 SettingsGroup(tv){
  SettingsActionRow(Icons.Outlined.Tune,"Дополнительные параметры",if(advanced)"Скрыть параметры" else "Чтение вперёд, протоколы и хранение",tv){advanced=!advanced}
  if(advanced)Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
   all.filter{it.optString("name") !in basic&&draft.has(it.optString("name"))}.forEach{TorrInput(snapshot,draft,it,tv){value->change(it.getString("name"),value)}}
   TextButton(onClick=onDefaults,modifier=Modifier.tvFocusRing(tv)){Icon(Icons.Outlined.RestartAlt,null);Spacer(Modifier.width(8.dp));Text("Заводские настройки")}
  }
 }
}

@Composable private fun TorrInput(snapshot:JSONObject,draft:JSONObject,field:JSONObject,tv:Boolean,onChange:(Any)->Unit){
 val name=field.getString("name");val label=field.getString("label")
 if(field.optBoolean("boolean")){
  val negative=field.optBoolean("negative")
  SettingsToggle(label,if(negative)!draft.optBoolean(name) else draft.optBoolean(name),{onChange(if(negative)!it else it)})
  return
 }
 if(field.optString("unit")=="path"){ServerField(draft.optString(name),{onChange(it)},label);return}
 val advice=torrFieldAdvice(snapshot,draft,field);val scale=if(name=="CacheSize")1048576L else 1L
 val unit=if(name=="CacheSize")"МиБ" else if(field.optString("unit")=="KiB/s")"КиБ/с" else field.optString("unit")
 var text by remember(draft.opt(name)){mutableStateOf((draft.opt(name) as? Number)?.let{(it.toLong()/scale).toString()} ?: draft.optString(name))}
 val value=text.toLongOrNull()?.let{runCatching{Math.multiplyExact(it,scale)}.getOrNull()}
 val old=snapshot.getJSONObject("settings").optLong(name)
 val exceeds=value!=null&&value>advice.maximum&&(if(name=="CacheSize")value>old||torrCacheUsesDisk(snapshot.getJSONObject("settings"))!=torrCacheUsesDisk(draft) else value!=old)
 val invalid=value==null||value<field.optLong("min")||value>field.optLong("max")||exceeds
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  OutlinedTextField(text,{text=it;onChange(it.toLongOrNull()?.let{n->runCatching{Math.multiplyExact(n,scale)}.getOrNull()} ?: it)},label={Text(label)},suffix={Text(unit)},singleLine=true,isError=invalid,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().tvFieldNavigation().semantics{contentDescription=label},shape=MaterialTheme.shapes.small,
   supportingText={if(invalid)Text(if(exceeds)"Выше доступного бюджета сервера" else "Введите число от ${field.optLong("min")/scale} до ${advice.maximum/scale}")})
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)){
   advice.recommended?.let{n->AdviceTile("Рекомендуем",if(n==0L&&name.endsWith("RateLimit"))"Без лимита" else "${n/scale} $unit".trim(),tv,Modifier.weight(1f).fillMaxHeight()){onChange(n)}}
   Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.small,modifier=Modifier.weight(1f).fillMaxHeight()){
    Column(Modifier.padding(12.dp)){Text(if(advice.measured)"Максимум по серверу" else "Предел TorrServer",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("${advice.maximum/scale} $unit".trim(),style=MaterialTheme.typography.labelLarge)}
   }
  }
  if(name=="PreloadCache"&&value!=null&&value in 0..100){
   val cache=(draft.opt("CacheSize") as? Number)?.toLong()?.takeIf {it>=0}
   if(cache!=null)Text("$value% выбранного кэша — ${cache/100*value/1048576} МиБ до старта",
    style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

@Composable internal fun AdviceTile(title:String,value:String,tv:Boolean,modifier:Modifier=Modifier,onClick:()->Unit){
 val focus=androidx.compose.ui.platform.LocalFocusManager.current
 val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
 Surface(onClick={focus.clearFocus();keyboard?.hide();onClick()},color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.small,modifier=modifier.tvFocusRing(tv)){
  Column(Modifier.heightIn(min=56.dp).padding(12.dp)){Text(title,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onPrimaryContainer);Text(value,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onPrimaryContainer)}
 }
}
