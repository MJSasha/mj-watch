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

@Composable internal fun TvQrScreen(model:WatchModel,manual:()->Unit) {
    val controller=model.managed
    val qr by controller.qr.tv.collectAsState()
    val managed by controller.state.collectAsState()
    LaunchedEffect(Unit){controller.qr.startTv()}
    DisposableEffect(Unit){onDispose{controller.qr.stopTv()}}
    Text("Подключить телевизор",fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics{heading()})
    OperationMessage(qr.message.ifBlank {managed.message},error=true)
    val proposal=qr.proposal
    val connectFocus=remember {FocusRequester()}
    LaunchedEffect(proposal){if(proposal!=null){withFrameNanos {};connectFocus.requestFocus()}}
    if(proposal!=null) {
        Text("Подключиться к аккаунту ${proposal.account}?",fontSize=24.sp,fontWeight=FontWeight.Medium)
        Text(endpoint(proposal.setup.url).origin())
        Text("Настройки и сертификат переданы телефоном, который отсканировал QR на этом TV.")
        Action("Подключить",true,Modifier.focusRequester(connectFocus),enabled=!managed.busy){controller.qr.confirmTv()}
        Action("Отклонить",true,secondary=true){controller.qr.startTv()}
    } else if(qr.accepted) {
        if(managed.message.isBlank()) {
            CircularProgressIndicator()
            Text(if(managed.pairing==null)"Проверяем сервер…" else "Подтвердите телевизор на телефоне",fontSize=24.sp,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
        }
        Action("Начать заново",true,secondary=true){controller.qr.startTv()}
    } else {
        val link=qr.link
        if(link!=null) {
            val bitmap=remember(link) {
                val matrix=QRCodeWriter().encode(link.encode(),BarcodeFormat.QR_CODE,768,768)
                Bitmap.createBitmap(768,768,Bitmap.Config.ARGB_8888).apply {setPixels(IntArray(768*768){i->if(matrix[i%768,i/768])android.graphics.Color.BLACK else android.graphics.Color.WHITE},0,768,0,0,768,768)}.asImageBitmap()
            }
            val refreshFocus=remember {FocusRequester()}
            LaunchedEffect(Unit) {withFrameNanos {};refreshFocus.requestFocus()}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(32.dp),verticalAlignment=Alignment.CenterVertically) {
                Image(bitmap,"QR для подключения телевизора",Modifier.size((LocalConfiguration.current.screenHeightDp*.4f).coerceIn(160f,280f).dp).background(Color.White).testTag("tv-connect-qr"))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    Text("На телефоне: MJWatch → Аккаунт → Добавить телевизор → Сканировать QR.")
                    Text("Телефон и TV должны быть в одной сети. QR действует 5 минут.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Action("Обновить QR",true,Modifier.focusRequester(refreshFocus).testTag("tv-refresh-qr"),secondary=true){controller.qr.startTv()}
                    Action("Другие способы входа",true,secondary=true,onClick=manual)
                }
            }
        } else {
            if(qr.message.isBlank())CircularProgressIndicator()
            Action("Обновить QR",true,secondary=true){controller.qr.startTv()}
            Action("Другие способы входа",true,secondary=true,onClick=manual)
        }
    }
}
