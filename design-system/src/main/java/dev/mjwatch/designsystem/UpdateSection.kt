package dev.mjwatch.designsystem

import android.app.NotificationManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.mjwatch.core.BackgroundUpdateJob
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.*

@Composable internal fun UpdateSection(manager:UpdateManager,tv:Boolean,compact:Boolean=false) {
    val state by manager.state.collectAsState()
    val context=LocalContext.current
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
            SettingsCard("Проверка обновлений",Icons.Outlined.Update,tv) {
            SettingsToggle("Автопроверка",state.automatic,{manager.automatic(it)},modifier=Modifier.focusRequester(entryFocus),
                description=if(tv) "При открытии приложения" else "Проверка и фоновая загрузка")
            if(tv) SettingsHint("Проверяем GitHub не чаще раза в 15 минут. Загрузка начинается по нажатию, установку подтверждает Android.")
            else {
                SettingsHint("APK загружается в фоне через безлимитную сеть. Когда он готов, MJ Watch присылает уведомление. Установку подтверждает Android.")
                if(state.automatic) UpdateNotificationPermission()
            }
            UpdateButton(if(state.checking) "Проверка…" else "Проверить сейчас",!state.checking&&!state.downloading,loading=state.checking) {manager.check()}
            }
            SettingsGroup(tv) {SettingsActionRow(Icons.Outlined.OpenInNew,"GitHub Releases","Все версии и APK",tv) {try {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/MJSasha/mj-watch/releases")))} catch(_:Exception) {error="На устройстве нет браузера"}}}
        }
        state.available?.let {update->
            if(!compact) Text("Доступна ${update.versionName} · сборка ${update.versionCode} · ${update.size/1024/1024} МБ",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
            if(state.downloading) {
                Text("Обновление загружается…")
            } else if(state.ready!=null) {
                UpdateInstallAction(manager,tv,Modifier.fillMaxWidth())
                UpdateButton("Загрузить заново") {manager.download()}
            } else UpdateButton("Скачать обновление",!state.checking) {manager.download()}
        }
        if(state.message.isNotBlank()) Text(state.message,Modifier.semantics {liveRegion=LiveRegionMode.Polite},color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun UpdateInstallAction(manager:UpdateManager,tv:Boolean,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val scope=rememberCoroutineScope()
    var installing by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        val allowed=remember(lifecycleState) {context.packageManager.canRequestPackageInstalls()}
        Action(if(allowed) "Установить обновление" else "Разрешить установку",tv,modifier,enabled=!installing) {
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
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }
}

@Composable private fun UpdateNotificationPermission() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val manager = context.getSystemService(NotificationManager::class.java)
    fun enabled() = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(BackgroundUpdateJob.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    var allowed by remember { mutableStateOf(enabled()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = enabled() }
    DisposableEffect(owner, manager) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = enabled() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (!allowed) Action("Разрешить уведомления", false, Modifier.fillMaxWidth(), secondary=true) {
        val prefs = context.getSharedPreferences("update-notifications", 0)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("requested", false)) {
            prefs.edit().putBoolean("requested", true).apply()
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val channel = manager.getNotificationChannel(BackgroundUpdateJob.CHANNEL)
            val settings = if (channel?.importance == NotificationManager.IMPORTANCE_NONE) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channel.id)
            } else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            context.startActivity(settings.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
}

@Composable internal fun AutomaticUpdates(manager:UpdateManager) {
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,manager) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) manager.onResume()}
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer)}
    }
}
