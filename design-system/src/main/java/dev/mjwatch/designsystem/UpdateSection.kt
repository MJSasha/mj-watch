package dev.mjwatch.designsystem

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    @Composable fun UpdateButton(text:String,enabled:Boolean=true,action:()->Unit) {
        if(tv) androidx.tv.material3.Button(onClick=action,enabled=enabled) {Text(text)}
        else Button(onClick=action,enabled=enabled) {Text(text)}
    }
    if(!compact||state.available!=null) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(if(compact) "Доступно обновление ${state.available?.versionName}" else "Обновления · ${manager.versionName}",style=MaterialTheme.typography.titleLarge)
        if(!compact) {
            Row {Switch(checked=state.automatic,onCheckedChange={manager.automatic(it)},modifier=Modifier.semantics {contentDescription="Автоматически проверять обновления"});Text("Проверять GitHub при запуске",Modifier.padding(start=12.dp,top=12.dp))}
            Text("Тестовый канал. Установку подтверждает системное окно Android.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            UpdateButton(if(state.checking) "Проверка…" else "Проверить сейчас",!state.checking&&!state.downloading) {manager.check()}
            UpdateButton("GitHub Releases") {try {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/MJSasha/mj-watch/releases")))} catch(_:Exception) {error="На устройстве нет браузера"}}
        }
        state.available?.let {update->
            if(!compact) Text("Доступна ${update.versionName} · ${update.size/1024/1024} МБ")
            if(state.downloading) {
                LinearProgressIndicator(progress={ (state.downloaded.toFloat()/update.size).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
                Text("Загружено ${state.downloaded/1024/1024} из ${update.size/1024/1024} МБ")
                UpdateButton("Отменить загрузку") {manager.cancel()}
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
        if(state.message.isNotBlank()) Text(state.message,Modifier.semantics {liveRegion=LiveRegionMode.Polite})
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
