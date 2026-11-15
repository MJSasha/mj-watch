package dev.mjwatch.designsystem

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mjwatch.core.InstallationProgress

@Suppress("DEPRECATION")
@Composable internal fun InstallationScreen(progress:InstallationProgress,busy:Boolean,message:String,tv:Boolean,onRetry:()->Unit,onRestart:()->Unit,onCancel:()->Unit,onEdit:()->Unit) {
 var details by rememberSaveable {mutableStateOf(false)}
 val clipboard=LocalClipboardManager.current
 val colors=MaterialTheme.colorScheme
 val heading=remember {BringIntoViewRequester()}
 LaunchedEffect(busy){heading.bringIntoView()}
 val labels=listOf("Проверяем сервер","Устанавливаем TorrServer","Устанавливаем сервис MJWatch","Настраиваем защиту и автозапуск","Проверяем и подключаем приложение")
 Text(if(progress.state=="unreachable") "Связь с сервером прервана" else if(progress.terminal) "Установка приостановлена" else if(!busy) "Проверим состояние сервера" else "Всё настраиваем за вас",modifier=Modifier.bringIntoViewRequester(heading),fontSize=26.sp,fontWeight=FontWeight.Bold)
 SettingsGroup(tv) {
  labels.forEachIndexed {index,label->
   val active=index==progress.index
   val done=progress.index>index
   Row(Modifier.fillMaxWidth().background(if(active) colors.onSurface.copy(alpha=.04f) else androidx.compose.ui.graphics.Color.Transparent).padding(16.dp).semantics(mergeDescendants=true){
    stateDescription=when {done->"Завершено";active&&progress.state=="unreachable"->"Связь прервана";active&&progress.terminal->"Остановлено";active&&busy->"Выполняется";else->"Ожидает проверки"}
   },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
    when {
     done->Icon(Icons.Outlined.Check,null,Modifier.size(28.dp).background(colors.primary,CircleShape).padding(4.dp),tint=colors.onPrimary)
     active&&progress.attention->Icon(Icons.Outlined.ErrorOutline,null,Modifier.size(28.dp),tint=colors.error)
     active&&busy->CircularProgressIndicator(Modifier.size(28.dp),strokeWidth=3.dp)
     else->Icon(Icons.Outlined.CheckBoxOutlineBlank,null,Modifier.size(28.dp),tint=colors.onSurfaceVariant)
    }
    Text(label,Modifier.weight(1f),color=if(done||active)colors.onSurface else colors.onSurfaceVariant)
   }
   if(index<labels.lastIndex)SettingsDivider()
  }
 }
 Text(message.ifBlank{"Ожидаем подтверждение текущего этапа."},color=if(progress.attention)colors.error else colors.onSurfaceVariant,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
 Text("Можно закрыть приложение. Если серверная задача уже запущена, установка продолжится. После возврата проверьте состояние.",color=colors.onSurfaceVariant)
 Action(if(details)"Скрыть подробности" else "Подробности",tv,secondary=true,icon=Icons.Outlined.ReceiptLong){details=!details}
 if(details) {
  Column(Modifier.fillMaxWidth().background(colors.surface,RoundedCornerShape(16.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   Text("Диагностика установки",fontWeight=FontWeight.Bold)
   Text(progress.diagnostic)
   Text("Отчёт содержит только этап, состояние, код и время ответа сервера.",color=colors.onSurfaceVariant)
   Action("Скопировать диагностику",tv,secondary=true){clipboard.setText(AnnotatedString(progress.diagnostic))}
  }
 }
 if(busy)Action("Остановить на безопасной границе",tv,secondary=true,onClick=onCancel)
 else {
  Action("Проверить состояние и подключиться",tv,Modifier.fillMaxWidth(),onClick=onRetry)
  Action("Повторить установку",tv,secondary=true,onClick=onRestart)
  Action("Вернуться к SSH-параметрам",tv,secondary=true,onClick=onEdit)
 }
}

@Composable internal fun InstallationReady(tv:Boolean,onOpen:()->Unit,onTmdb:()->Unit,onUsers:()->Unit) {
 val colors=MaterialTheme.colorScheme
 Icon(Icons.Outlined.Check,null,Modifier.size(64.dp).background(colors.primary,CircleShape).padding(12.dp),tint=colors.onPrimary)
 Text("Сервер готов",fontSize=28.sp,fontWeight=FontWeight.Bold)
 Text("Сервер готов. Приложение уже подключено.",color=colors.onSurfaceVariant)
 SettingsGroup(tv) {
  listOf("TorrServer установлен","История синхронизируется","Вы — администратор").forEachIndexed {index,label->
   Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically){
    Icon(Icons.Outlined.CheckBox,null,tint=colors.primary);Text(label)
   }
   if(index<2)SettingsDivider()
  }
 }
 Text("Можно настроить позже",fontWeight=FontWeight.Bold)
 SettingsGroup(tv) {
  SettingsActionRow(Icons.Outlined.Layers,"TMDB и каталог","Добавить API-ключ",tv,onTmdb)
  SettingsDivider()
  SettingsActionRow(Icons.Outlined.People,"Пользователи","Пригласить семью",tv,onUsers)
 }
 Action("Открыть приложение",tv,Modifier.fillMaxWidth(),icon=Icons.Outlined.PlayArrow,onClick=onOpen)
 Text("Источник раздач настраивается отдельно",color=colors.onSurfaceVariant)
}
