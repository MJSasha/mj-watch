package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File

/** Manual native screenshot review. Admin forms are captured unloaded; no live operations run. */
class SettingsReviewTest {
 @get:Rule(order=0)val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking{
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("settingsReview")!=null)
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
  Settings(c).save(Config(demo=true,theme=InstrumentationRegistry.getArguments().getString("theme") ?: "dark",torrServer="http://127.0.0.1:8090",localHttp=true));Settings(c).destination("settings")
 }}
 @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
 @Test fun captureSettings(){
  lateinit var model:WatchModel
  ui.runOnIdle{model=ViewModelProvider(ui.activity)[WatchModel::class.java]}
  ui.waitUntil(10000){model.state.value.config.demo&&model.state.value.configured}
  val i=InstrumentationRegistry.getInstrumentation()
  val dir=File(i.targetContext.getExternalFilesDir(null),"settings-review/${InstrumentationRegistry.getArguments().getString("settingsReview")}").apply{mkdirs()}
  for(page in listOf("settings","settings-search","settings-server","settings-catalog","connections","settings-updates","server-tmdb","server-retention","server-history","server-maintenance","server-update","server-user-create","connection-choice","managed-account","managed-pairing")){
   ui.runOnIdle{model.discardDraft();model.page(page)};ui.waitForIdle();Thread.sleep(350)
   var imeVisible=false
   ui.runOnUiThread{imeVisible=androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}
   if(imeVisible){UiDevice.getInstance(i).pressBack();ui.waitForIdle();Thread.sleep(350)}
   Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$page.png")))
   if(page=="settings"){
    ui.onNodeWithTag("settings-list").performScrollToNode(hasText("Субтитры"));ui.waitForIdle();Thread.sleep(350)
    Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"settings-playback.png")))
   }
  }
 }
}
