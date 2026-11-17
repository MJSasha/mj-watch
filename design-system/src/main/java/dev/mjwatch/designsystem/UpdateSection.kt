package dev.mjwatch.designsystem

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mjwatch.core.UpdateManager
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.*

@Composable internal fun UpdateSection(manager:UpdateManager,tv:Boolean,compact:Boolean=false) {
    val state by manager.state.collectAsState()
    val context=LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val scope=rememberCoroutineScope()
    var installing by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    val entryFocus=remember {FocusRequester()}
    LaunchedEffect(tv,compact) {if(tv&&!compact) {withFrameNanos {};entryFocus.requestFocus()}}
    @Composable fun UpdateButton(text:String,enabled:Boolean=true,loading:Boolean=false,action:()->Unit) {
        Action(text,tv,Modifier.fillMaxWidth(),enabled=enabled,secondary=text in setOf("GitHub Releases","Загрузить заново","Отменить загрузку"),loading=loading,onClick=action)
    }
    if(!compact||state.available!=null) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        SettingsGroup(tv,featured=compact) {Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center) {Icon(Icons.Outlined.SystemUpdate,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(28.dp))}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(if(compact) "Доступно обновление" else "MJWatch",fontSize=21.sp,fontWeight=FontWeight.Bold)
                Text(if(compact) state.available?.let {"${it.versionName} · сборка ${it.versionCode}"}.orEmpty() else "Установлена ${manager.versionName} · сборка ${manager.versionCode}",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }}
        if(!compact) {
            SettingsGroup(tv,featured=true) {SettingsToggle("Автопроверка",state.automatic,{manager.automatic(it)},modifier=Modifier.focusRequester(entryFocus),description="При открытии приложения",icon=Icons.Outlined.Update)}
            Text("Проверяем GitHub не чаще раза в 15 минут. Загрузка начинается по нажатию, установку подтверждает Android.",fontSize=13.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            UpdateButton(if(state.checking) "Проверка…" else "Проверить сейчас",!state.checking&&!state.downloading,loading=state.checking) {manager.check()}
            SettingsGroup(tv) {SettingsActionRow(Icons.Outlined.OpenInNew,"GitHub Releases","Все версии и APK",tv) {try {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/MJSasha/mj-watch/releases")))} catch(_:Exception) {error="На устройстве нет браузера"}}}
        }
        state.available?.let {update->
            if(!compact) Text("Доступна ${update.versionName} · сборка ${update.versionCode} · ${update.size/1024/1024} МБ",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
            if(state.downloading) {
                Text("Обновление загружается…")
            } else if(state.ready!=null) {
                val allowed=remember(lifecycleState) {context.packageManager.canRequestPackageInstalls()}
                UpdateButton(if(allowed) "Установить обновление" else "Разрешить установку",!installing) {
                    if(!allowed) {
                        try {context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))} catch(_:Exception) {error="Откройте настройки Android и разрешите установку из MJWatch"}
                    } else {
                        installing=true;error=""
                        scope.launch {try {
                            val apk=withContext(Dispatchers.IO) {manager.readyFile()} ?: throw IllegalStateException("APK не прошёл проверку")
                            val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",apk)
                            PlaybackActivity.stopActivePlayback()
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch(_:Exception) {error="Не удалось открыть установщик. Загрузите APK снова или откройте GitHub Releases"} finally {installing=false}}
                    }
                }
                UpdateButton("Загрузить заново",!installing) {manager.download()}
            } else UpdateButton("Скачать обновление",!state.checking) {manager.download()}
        }
        if(state.message.isNotBlank()) Text(state.message,Modifier.semantics {liveRegion=LiveRegionMode.Polite},color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun AutomaticUpdates(manager:UpdateManager) {
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,manager) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) manager.check(automatic=true)}
        owner.lifecycle.addObserver(observer)
        manager.check(automatic=true)
        onDispose {owner.lifecycle.removeObserver(observer)}
    }
}
