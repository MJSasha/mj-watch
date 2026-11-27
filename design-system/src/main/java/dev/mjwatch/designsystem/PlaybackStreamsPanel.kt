package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

private data class PlaybackViewer(val id:String,val account:String,val device:String,val file:String,val startedAt:String,val download:Boolean) {
    val label get() = listOf(account,device).filter {it.isNotBlank()}.joinToString(" · ")
    val action get() = if(download) "Остановить загрузку" else "Остановить просмотр"
}

@Composable
internal fun PlaybackStreamsPanel(config:Config,secrets:SecretStore,tv:Boolean) {
    val api=remember(config.scope,config.managedUrl,secrets){lazy {ManagedApi(config,secrets)}}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val scope=rememberCoroutineScope()
    val timeFormat=remember{DateFormat.getTimeInstance(DateFormat.SHORT)}
    var viewers by remember(api){mutableStateOf<List<PlaybackViewer>?>(null)}
    var confirmation by remember(api){mutableStateOf<PlaybackViewer?>(null)}
    var stopping by remember(api){mutableStateOf(false)}
    var refresh by remember(api){mutableIntStateOf(0)}
    var loadError by remember(api){mutableStateOf("")}
    var stopError by remember(api){mutableStateOf("")}

    LaunchedEffect(api,lifecycle,refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) {
                try {
                    val result=withContext(Dispatchers.IO){api.value.authorized("admin/playback/streams").getJSONArray("streams")}
                    if(!stopping) {
                        viewers=List(result.length()){index->
                            val row=result.getJSONObject(index)
                            val start=runCatching {timeFormat.format(Date.from(java.time.Instant.parse(row.getString("startedAt"))))}.getOrDefault("")
                            PlaybackViewer(row.getString("id"),row.optString("account"),row.optString("device"),row.optString("file"),start,row.optBoolean("download"))
                        }
                        loadError=""
                    }
                } catch(e:CancellationException) {
                    throw e
                } catch(e:Exception) {
                    loadError=if(e is HttpFailure&&e.status==404)"Обновите сервер, чтобы видеть активные просмотры." else safeError(e)
                    if(e is HttpFailure&&e.status==404) break
                }
                delay(5_000)
            }
        }
    }

    confirmation?.let {viewer->
        AlertDialog(
            onDismissRequest={confirmation=null},
            title={Text("${viewer.action}?")},
            text={Text(listOf(viewer.label,viewer.file).filter {it.isNotBlank()}.joinToString("\n"))},
            confirmButton={
                TextButton(modifier=Modifier.tvFocusRing(tv),onClick={
                    confirmation=null
                    stopping=true
                    stopError=""
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO){api.value.authorized("admin/playback/streams/${viewer.id}","DELETE")}
                            viewers=viewers?.filter {it.id!=viewer.id}
                            refresh++
                        } catch(e:CancellationException) {
                            throw e
                        } catch(e:Exception) {
                            stopError=safeError(e)
                        } finally {
                            stopping=false
                        }
                    }
                }){Text("Остановить")}
            },
            dismissButton={TextButton(modifier=Modifier.tvFocusRing(tv),onClick={confirmation=null}){Text("Отмена")}}
        )
    }

    SettingsCard("Активные просмотры и загрузки",Icons.Outlined.People,tv) {
        if((viewers==null&&loadError.isBlank())||stopping) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(viewers?.isEmpty()==true&&loadError.isBlank()) Text("Активных просмотров и загрузок нет")
        viewers.orEmpty().forEach {viewer->
            key(viewer.id) {
                Text(viewer.label,style=MaterialTheme.typography.titleSmall)
                if(viewer.file.isNotBlank()) Text(viewer.file,style=MaterialTheme.typography.bodySmall)
                if(viewer.startedAt.isNotBlank()) Text("Начало в ${viewer.startedAt}",style=MaterialTheme.typography.bodySmall)
                TextButton(enabled=!stopping,modifier=Modifier.tvFocusRing(tv),onClick={confirmation=viewer}){Text(viewer.action)}
                HorizontalDivider()
            }
        }
        listOf(loadError,stopError).filter {it.isNotBlank()}.forEach {
            Text(it,style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
        }
        TextButton(enabled=!stopping,modifier=Modifier.tvFocusRing(tv),onClick={refresh++}){Text("Обновить список")}
    }
}
