package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable internal fun SettingsCard(title:String,icon:ImageVector,tv:Boolean,content:@Composable ColumnScope.()->Unit){
 SettingsGroup(tv){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
   Icon(icon,null,tint=MaterialTheme.colorScheme.primary)
   Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f).semantics{heading()})
  }
  content()
 }}
}

@Composable internal fun SettingsDisclosure(title:String,subtitle:String,icon:ImageVector,tv:Boolean,initiallyOpen:Boolean=false,content:@Composable ColumnScope.()->Unit){
 var open by rememberSaveable{mutableStateOf(initiallyOpen)}
 SettingsGroup(tv){
  Box(Modifier.semantics{stateDescription=if(open)"Развёрнуто" else "Свёрнуто"}){
   SettingsActionRow(icon,title,if(open)"Скрыть параметры" else subtitle,tv,expanded=open){open=!open}
  }
  if(open)Column(Modifier.padding(start=20.dp,end=20.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
 }
}

@Composable internal fun SettingsHint(text:String){
 Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.small){
  Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
   Icon(Icons.Outlined.Info,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
   Text(text,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

/** Bounds are the server API contract; resource estimates are shown only when supplied by the server. */
@Composable internal fun SettingsNumber(value:String,onChange:(String)->Unit,label:String,unit:String,min:Long,max:Long,tv:Boolean,recommended:Long?=null,hint:String=""){
 val n=value.toLongOrNull();val invalid=n==null||n !in min..max
 fun formatted(number:Long):String=when{
  unit=="МиБ"&&number>=1048576&&number%1048576==0L->"${number/1048576} ТиБ"
  unit=="МиБ"&&number>=1024&&number%1024==0L->"${number/1024} ГиБ"
  else->"${java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("ru")).format(number)} $unit".trim()
 }
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  OutlinedTextField(value,onChange,label={Text(label)},suffix={Text(unit)},singleLine=true,isError=invalid,
   keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),shape=MaterialTheme.shapes.small,
   modifier=Modifier.fillMaxWidth().tvFieldNavigation().semantics{contentDescription=label},
   supportingText={if(invalid)Text("Введите число от $min до $max")})
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)){
   recommended?.let{n->AdviceTile("Рекомендуем","$n $unit".trim(),tv,Modifier.weight(1f).fillMaxHeight()){onChange(n.toString())}}
   Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.small,modifier=Modifier.weight(1f).fillMaxHeight()){
    Column(Modifier.padding(12.dp)){Text("Предел настройки",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("${formatted(min)} – ${formatted(max)}",style=MaterialTheme.typography.labelLarge)}
   }
  }
  if(hint.isNotBlank())Text(hint,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 }
}
