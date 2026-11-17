package dev.mjwatch.designsystem

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.mjwatch.core.*

@Composable internal fun TvQrScreen(model:WatchModel,tv:Boolean=true,manual:()->Unit) {
    val controller=model.managed
    val qr by controller.qr.tv.collectAsState()
    val managed by controller.state.collectAsState()
    LaunchedEffect(Unit){controller.qr.startTv()}
    DisposableEffect(Unit){onDispose{controller.qr.stopTv()}}
    Text(if(tv) "Подключить телевизор" else "Войти по QR",fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics{heading()})
    OperationMessage(qr.message.ifBlank {managed.message},error=true)
    val proposal=qr.proposal
    val connectFocus=remember {FocusRequester()}
    LaunchedEffect(proposal){if(tv&&proposal!=null){withFrameNanos {};connectFocus.requestFocus()}}
    if(proposal!=null) {
        Text("Подключиться к аккаунту ${proposal.account}?",fontSize=24.sp,fontWeight=FontWeight.Medium)
        Text(endpoint(proposal.setup.url).origin())
        Text("Настройки и сертификат переданы устройством, которое отсканировало этот QR.")
        Action("Подключить",tv,Modifier.focusRequester(connectFocus),enabled=!managed.busy){controller.qr.confirmTv()}
        Action("Отклонить",tv,secondary=true){controller.qr.startTv()}
    } else if(qr.accepted) {
        if(managed.message.isBlank()) {
            CircularProgressIndicator()
            Text(if(managed.pairing==null)"Проверяем сервер…" else "Подтвердите новое устройство на авторизованном телефоне",fontSize=24.sp,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
        }
        Action("Начать заново",tv,secondary=true){controller.qr.startTv()}
    } else {
        val link=qr.link
        if(link!=null) {
            val bitmap=remember(link) {
                val matrix=QRCodeWriter().encode(link.encode(),BarcodeFormat.QR_CODE,768,768)
                Bitmap.createBitmap(768,768,Bitmap.Config.ARGB_8888).apply {setPixels(IntArray(768*768){i->if(matrix[i%768,i/768])android.graphics.Color.BLACK else android.graphics.Color.WHITE},0,768,0,0,768,768)}.asImageBitmap()
            }
            val refreshFocus=remember {FocusRequester()}
            LaunchedEffect(Unit) {if(tv) {withFrameNanos {};refreshFocus.requestFocus()}}
            val image:@Composable ()->Unit={
                Image(bitmap,if(tv) "QR для подключения телевизора" else "QR для входа на новом устройстве",Modifier.size((LocalConfiguration.current.screenHeightDp*.4f).coerceIn(160f,280f).dp).background(Color.White).testTag("tv-connect-qr"))
            }
            val help:@Composable ()->Unit={Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {
                Text(if(tv) "На телефоне: MJWatch → Аккаунт → Добавить телевизор → Сканировать QR." else "На телефоне, где уже выполнен вход: MJWatch → Аккаунт → Добавить телефон или планшет → Сканировать QR нового устройства.")
                Text(if(tv) "Телефон и TV должны быть в одной сети. QR действует 5 минут." else "Оба устройства должны быть в одной сети Wi-Fi. QR действует 5 минут. Подтвердите вход на обоих устройствах.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Action("Обновить QR",tv,Modifier.focusRequester(refreshFocus).testTag("tv-refresh-qr"),secondary=true){controller.qr.startTv()}
                Action("Другие способы входа",tv,secondary=true,onClick=manual)
            }}
            if(tv) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(32.dp),verticalAlignment=Alignment.CenterVertically) {
                image();Box(Modifier.weight(1f)) {help()}
            } else Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {image();help()}

        } else {
            if(qr.message.isBlank())CircularProgressIndicator()
            Action("Обновить QR",tv,secondary=true){controller.qr.startTv()}
            Action("Другие способы входа",tv,secondary=true,onClick=manual)
        }
    }
}
