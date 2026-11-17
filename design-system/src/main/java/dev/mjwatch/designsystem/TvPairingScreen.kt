package dev.mjwatch.designsystem

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.mjwatch.core.*

@Composable internal fun TvPairingScreen(s:WatchState,model:WatchModel,manualLogin:()->Unit) {
    val controller=model.managed
    val state by controller.state.collectAsState()
    var url by rememberSaveable {mutableStateOf(s.config.managedUrl)}
    var address by rememberSaveable {mutableStateOf(false)}
    var pickerError by remember {mutableStateOf("")}
    val focus=remember {FocusRequester()}
    LaunchedEffect(state.pairing?.optString("id"),state.busy,state.pairingSetup){if(!state.busy&&state.pairingSetup==null){withFrameNanos {};focus.requestFocus()}}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null)controller.importTvSetup(uri)}
    DisposableEffect(Unit){onDispose{controller.cancelPairing();controller.dismissTvSetup()}}
    state.pairingSetup?.let {setup->
        val origin=endpoint(setup.url).origin()
        AlertDialog(onDismissRequest={controller.dismissTvSetup()},title={Text("Подтвердите сервер")},text={Column {
            Text(origin)
            Text(if(setup.ca.isBlank())"Сертификат проверит Android." else "Сверьте этот отпечаток с экраном подключения устройства на телефоне:")
            if(setup.ca.isNotBlank())Text(managedCaFingerprint(setup.ca),fontSize=16.sp)
        }},confirmButton={Action(if(setup.ca.isBlank())"Подключиться" else "Отпечаток совпадает",true,enabled=!state.busy){url=setup.url;controller.confirmTvSetup()}},dismissButton={Action("Отмена",true,secondary=true){controller.dismissTvSetup()}})
    }
    Text("Подключить телевизор",fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics{heading()})
    OperationMessage(pickerError.ifBlank{state.message},error=true)
    if(state.busy)Text("Подключаемся к серверу…",modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
    val pair=state.pairing
    if(pair!=null) {
        Text(pair.getString("code"),fontSize=56.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
        Text("На телефоне: Аккаунт и синхронизация → QR-сканер → Ввести код вручную. Введите этот код и подтвердите устройство.")
        Text("Ждём подтверждения. Код действует ${pair.getInt("expiresIn")/60} мин.")
        Action("Отменить вход по коду",true,Modifier.focusRequester(focus),secondary=true){controller.cancelPairing()}
    } else {
        Text("На вошедшем телефоне откройте «Аккаунт и синхронизация → QR-сканер → Ввести код вручную» и сохраните настройки TV. Перенесите файл на телевизор, например через USB или облачное хранилище.")
        Action("Импортировать настройки",true,Modifier.fillMaxWidth().focusRequester(focus),enabled=!state.busy){
            pickerError=""
            try{importer.launch(arrayOf("application/json","text/plain","application/octet-stream"))}
            catch(e:android.content.ActivityNotFoundException){pickerError="На TV нет приложения для выбора файлов. Установите файловый менеджер с поддержкой системного выбора документов или укажите адрес сервера."}
        }
        if(url.isNotBlank())Action(if(state.message.isBlank())"Получить код" else "Повторить получение кода",true,enabled=!state.busy){controller.startPairing(url)}
        Action("Указать адрес сервера",true,secondary=true,enabled=!state.busy){address=!address}
        if(address) {
            ServerField(url,{url=it},"HTTPS-адрес MJWatch")
            Text("Для частного сертификата импортируйте настройки с телефона.")
        }
        if(state.busy)Action("Отменить подключение",true,secondary=true){controller.cancelPairing()}
        Action("Войти с паролем или приглашением",true,secondary=true,enabled=!state.busy,onClick=manualLogin)
    }
}
