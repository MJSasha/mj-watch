package dev.mjwatch.mobile
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import dev.mjwatch.designsystem.WatchApp
import dev.mjwatch.designsystem.WatchModel
class MainActivity: ComponentActivity() {
 override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); val model=ViewModelProvider(this)[WatchModel::class.java]; setContent { WatchApp(model,false) } }
}
