package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.Config
import dev.mjwatch.core.Settings
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.junit.*

/** Same visible version, different builds: cached metadata must explain the upgrade. */
class UpdateLabelsUiTest {
    private var installedCode=0L
    private lateinit var version:String
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {
        @Suppress("DEPRECATION") override fun before()=runBlocking {
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            val info=c.packageManager.getPackageInfo(c.packageName,0)
            installedCode=if(android.os.Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong();version=info.versionName!!
            val raw="""{"schemaVersion":1,"channel":"testing","versionCode":${installedCode+1},"versionName":"$version","releaseUrl":"https://github.com/MJSasha/mj-watch/releases/tag/test-9999","packages":{"mobile":{"packageName":"${c.packageName}","url":"https://github.com/MJSasha/mj-watch/releases/download/test-9999/MJWatch-mobile.apk","size":1048576,"sha256":"${"0".repeat(64)}"}}}"""
            c.getSharedPreferences("updates",0).edit().clear().putBoolean("automatic",false).putString("manifest",raw).commit()
            Settings(c).handleLegacyHistory();Settings(c).save(Config(demo=true));Settings(c).destination("settings")
        }
        override fun after() {InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("updates",0).edit().remove("manifest").commit()}
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    @Test fun equalVersionNamesShowBothBuildNumbers() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {model.state.value.config.demo&&model.state.value.configured}
        compose.runOnIdle {model.page("settings-updates")}
        compose.onNodeWithText("Установлена $version · сборка $installedCode").assertIsDisplayed()
        compose.onNodeWithText("Доступна $version · сборка ${installedCode+1} · 1 МБ").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Доступна $version",substring=false).assertDoesNotExist()
    }
}
