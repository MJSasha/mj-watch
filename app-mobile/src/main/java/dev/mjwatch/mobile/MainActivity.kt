package dev.mjwatch.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.mjwatch.designsystem.WatchApp
import dev.mjwatch.designsystem.WatchModel

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model=ViewModelProvider(this)[WatchModel::class.java]
        if(savedInstanceState==null)handleInvitation(intent,model)
        val scanner=registerForActivityResult(ScanContract()) {result->
            val contents=result.contents
            if(contents!=null)model.managed.qr.scan(contents)
            else if(result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION,false)==true)model.managed.qr.cameraDenied()
        }
        setContent {
            WatchApp(model,false,scanTvQr={
                scanner.launch(ScanOptions()
                    .setCaptureActivity(QrCaptureActivity::class.java)
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Наведите камеру на QR телевизора")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG,false))
            })
        }
    }
    override fun onNewIntent(intent:android.content.Intent){super.onNewIntent(intent);setIntent(intent);handleInvitation(intent,ViewModelProvider(this)[WatchModel::class.java])}
    private fun handleInvitation(value:android.content.Intent,model:WatchModel){
        if(value.action==android.content.Intent.ACTION_VIEW){val raw=value.dataString;if(raw!=null)model.openInvitation(raw)}
    }
}
