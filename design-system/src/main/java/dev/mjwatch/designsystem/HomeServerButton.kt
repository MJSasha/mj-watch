package dev.mjwatch.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

@Composable internal fun HomeServerButton(s:WatchState,model:WatchModel,tv:Boolean) {
    var online by remember(s.config) {mutableStateOf<Boolean?>(null)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(s.config,lifecycle) {
        if(s.config.demo)return@LaunchedEffect
        val api by lazy {ManagedApi(s.config,model.secrets)}
        val gateway by lazy {
            val client=guardedClient(s.config)
            TorrServerGateway(s.config,model.secrets.get("torrAuth:${s.config.torrServer}"),client,TorznabRepository(s.config,model.secrets.get("jackett:${s.config.jackett}"),client))
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) {
                online=try {withContext(Dispatchers.IO) {
                    if(s.config.managed)api.authorized("me") else gateway.check()
                };true} catch(e:CancellationException){throw e} catch(e:Exception){false}
                delay(30000)
            }
        }
    }
    val status=when(online){true->"Сервер доступен";false->"Нет связи с сервером";null->if(s.config.demo)"Демонстрация" else "Проверяем сервер"}
    IconButton(onClick={model.page("settings-server")},modifier=Modifier.size(48.dp).tvFocusRing(tv).background(MaterialTheme.colorScheme.surface,CircleShape)) {
        Box {
            Icon(Icons.Outlined.Dns,"Мой сервер · $status")
            Box(Modifier.align(Alignment.BottomEnd).size(7.dp).background(when(online){true->MaterialTheme.colorScheme.primary;false->MaterialTheme.colorScheme.error;null->MaterialTheme.colorScheme.onSurfaceVariant},CircleShape))
        }
    }
}
