package dev.mjwatch.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import dev.mjwatch.playback.RemotePlaybackService
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.mjwatch.core.OfflineDownloads
import dev.mjwatch.core.BackgroundUpdateJob
import kotlinx.coroutines.flow.first
import dev.mjwatch.designsystem.WatchApp
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.designsystem.coldStartupWindow

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val startupWindow=coldStartupWindow(restored = savedInstanceState != null)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemotePlaybackService.state.collect {mediaController=RemotePlaybackService.mediaController()}
            }
        }
        val model=ViewModelProvider(this)[WatchModel::class.java]
        if(savedInstanceState==null)handleInvitation(intent,model)
        val scanner=registerForActivityResult(ScanContract()) {result->
            val contents=result.contents
            if(contents!=null)model.managed.qr.scan(contents)
            else if(result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION,false)==true)model.managed.qr.cameraDenied()
        }
        setContent {
            WatchApp(model,false,scanQr={
                scanner.launch(ScanOptions()
                    .setCaptureActivity(QrCaptureActivity::class.java)
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Наведите камеру на QR нового устройства")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG,false))
            },animateStartup=startupWindow!=null,startupWindowReady=startupWindow?.value ?: true)
        }
    }
    override fun onPostResume() {
        super.onPostResume()
        lifecycleScope.launch { OfflineDownloads.get(this@MainActivity).resumePending() }
    }

    override fun onNewIntent(intent:android.content.Intent){super.onNewIntent(intent);setIntent(intent);handleInvitation(intent,ViewModelProvider(this)[WatchModel::class.java])}
    private fun handleInvitation(value:android.content.Intent,model:WatchModel){
        if (value.action == BackgroundUpdateJob.OPEN_UPDATES) {
            lifecycleScope.launch {
                model.state.first { !it.initializing }
                model.updates.refreshCached()
                model.page("settings-updates")
            }
        }
        if (value.action == OfflineDownloads.OPEN_DOWNLOADS) {
            lifecycleScope.launch {
                model.state.first { !it.initializing }
                model.openDownloads()
            }
        }
        if(value.hasExtra("remoteReleaseId"))model.openRemoteContent(value.getStringExtra("remoteReleaseId").orEmpty(),value.getStringExtra("remoteTitle").orEmpty(),value.getStringExtra("remoteMetadataId").orEmpty())
        if(value.action==android.content.Intent.ACTION_VIEW){val raw=value.dataString;if(raw!=null)model.openInvitation(raw)}
    }
}
