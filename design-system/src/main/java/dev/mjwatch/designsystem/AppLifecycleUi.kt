package dev.mjwatch.designsystem

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mjwatch.core.UpdateState

@Composable fun StartupBanner(modifier:Modifier=Modifier) {
    val transition=rememberInfiniteTransition(label="startup")
    val scale by transition.animateFloat(.94f,1.06f,infiniteRepeatable(tween(800),RepeatMode.Reverse),label="logo pulse")
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("startup-banner"),contentAlignment=Alignment.Center) {
        Column(modifier,horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Icon(painterResource(R.drawable.ic_launcher_foreground),null,Modifier.size(108.dp).scale(scale),tint=MaterialTheme.colorScheme.primary)
            Text("MJWatch",fontSize=36.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onBackground)
            Text("Ваше кино на любом экране",color=MaterialTheme.colorScheme.onSurfaceVariant)
            CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
            Text("Запускаем приложение…",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun ExitAppDialog(tv:Boolean,onDismiss:()->Unit,onExit:()->Unit) {
    val focus=remember {FocusRequester()}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Выйти из MJWatch?")},text={Text("Воспроизведение и загрузка обновления остановятся. Настройки и история сохранятся.")},
        confirmButton={Action("Выйти",tv,onClick=onExit)},
        dismissButton={
            val windowFocused=LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(windowFocused) {if(tv&&windowFocused)focus.requestFocus()}
            Action("Остаться",tv,Modifier.focusRequester(focus),secondary=true,onClick=onDismiss)
        })
}

/** Outside the settings list so progress stays visible after focus or page changes. */
@Composable fun UpdateDownloadDialog(state:UpdateState,tv:Boolean,onCancel:()->Unit,onDismiss:()->Unit={},installButton:@Composable (Modifier)->Unit={}) {
    val update=state.available ?: return
    val focus=remember {FocusRequester()}
    val fraction=(state.downloaded.toFloat()/update.size.coerceAtLeast(1)).coerceIn(0f,1f)
    AlertDialog(onDismissRequest={if(state.ready!=null)onDismiss()},title={Text(if(state.ready!=null) "Обновление готово" else "Загрузка MJWatch ${update.versionName}")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(state.ready==null) {
            Text("${(fraction*100).toInt()}%",fontSize=28.sp,fontWeight=FontWeight.Bold)
            LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth().height(8.dp).testTag("update-download-progress"))
            Text("Загружено ${state.downloaded/1024/1024} из ${update.size/1024/1024} МБ")
            Text(if(state.downloaded>=update.size) "Проверяем APK и подпись…" else "После загрузки проверим APK. Установку подтверждает Android.")
            } else Text("MJWatch ${update.versionName} загружен. APK и подпись проверены. Можно установить обновление.")
        }
    },confirmButton={
        val windowFocused=LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused,state.ready) {if(tv&&windowFocused)focus.requestFocus()}
        if(state.ready!=null) installButton(Modifier.focusRequester(focus))
        else Action("Отменить загрузку",tv,Modifier.focusRequester(focus),secondary=true,onClick=onCancel)
    },dismissButton={if(state.ready!=null)Action("Позже",tv,secondary=true,onClick=onDismiss)})
}
