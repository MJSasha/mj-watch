package dev.mjwatch.tv
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import dev.mjwatch.designsystem.WatchApp
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.designsystem.startupWindowReady
class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val startupWindow=if(savedInstanceState==null) startupWindowReady() else null
        enableEdgeToEdge()
        val model=ViewModelProvider(this)[WatchModel::class.java]
        if(savedInstanceState==null) handleInvitation(intent,model)
        setContent { WatchApp(model,true,animateStartup=startupWindow!=null,startupWindowReady=startupWindow?.value ?: true) }
    }
    override fun onNewIntent(intent:android.content.Intent){super.onNewIntent(intent);setIntent(intent);handleInvitation(intent,ViewModelProvider(this)[WatchModel::class.java])}
    private fun handleInvitation(value:android.content.Intent,model:WatchModel){
        if(value.action==android.content.Intent.ACTION_VIEW){val raw=value.dataString;if(raw!=null)model.openInvitation(raw)}
    }
}
