package dev.mjwatch.tv

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File

class TvFormLayoutTest {
 @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
  Settings(c).handleLegacyHistory();Settings(c).save(Config(demo=true,theme="dark"));Settings(c).destination("settings")
 }}
 @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
 @Test fun standaloneFormsUseTvWidthAndKeepDpadBack() {
  lateinit var model:WatchModel
  ui.runOnIdle{model=ViewModelProvider(ui.activity)[WatchModel::class.java]}
  ui.waitUntil(15000){model.state.value.configured}
  val i=InstrumentationRegistry.getInstrumentation();val device=UiDevice.getInstance(i)
  val expected=ui.activity.resources.configuration.screenWidthDp*720f/1280f
  val density=ui.activity.resources.displayMetrics.density
  val decor=ui.activity.window.decorView
  val imeAnimating=AtomicBoolean(false)
  ui.runOnUiThread{ViewCompat.setWindowInsetsAnimationCallback(decor,object:WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE){
   override fun onPrepare(animation:WindowInsetsAnimationCompat){if(animation.typeMask and WindowInsetsCompat.Type.ime()!=0)imeAnimating.set(true)}
   override fun onEnd(animation:WindowInsetsAnimationCompat){if(animation.typeMask and WindowInsetsCompat.Type.ime()!=0)imeAnimating.set(false)}
   override fun onProgress(insets:WindowInsetsCompat,runningAnimations:MutableList<WindowInsetsAnimationCompat>)=insets
  })}
  fun imeHidden():Boolean {
   var hidden=false
   ui.runOnUiThread{val insets=ViewCompat.getRootWindowInsets(decor);hidden=insets!=null&&!insets.isVisible(WindowInsetsCompat.Type.ime())&&insets.getInsets(WindowInsetsCompat.Type.ime()).bottom==0&&!imeAnimating.get()}
   return hidden
  }
  for((page,label) in listOf("settings-search" to "Адрес Jackett / JacRed","settings-server" to "Адрес TorrServer")) {
   ui.runOnIdle{model.page(page)}
   ui.waitUntil(5000){runCatching{ui.onNodeWithText(label).assertIsFocused();true}.getOrDefault(false)}
   val bounds=ui.onNodeWithTag("settings-list").fetchSemanticsNode().boundsInRoot
   Assert.assertTrue("TV task pane width",bounds.width/density<=expected+1)
   Assert.assertTrue("Form remains usable",bounds.width/density>=minOf(expected,500f)-1)
   device.pressDPadDown();ui.waitForIdle();device.waitForIdle()
   ui.onNodeWithTag("settings-list").onChildren().assertAny(hasAnyDescendant(isFocused()))
   val dir=File(i.targetContext.getExternalFilesDir(null),"tv-form-layout").apply{mkdirs()}
   Assert.assertTrue(device.takeScreenshot(File(dir,"$page.png")))
   // Complete IME dismissal before sending the separate navigation Back.
   if(!imeHidden()) {
    device.pressBack()
    ui.waitUntil(5000){imeHidden()}
    ui.waitForIdle();device.waitForIdle()
   }
   if(model.state.value.page!="settings") {
    val back=ui.onNodeWithContentDescription("Назад").assertIsDisplayed()
    back.performSemanticsAction(SemanticsActions.RequestFocus)
    back.assertIsFocused() // Editor has exited before the navigation key.
    ui.waitUntil(5000){imeHidden()}
    ui.waitForIdle()
    device.pressBack()
   }
   ui.waitUntil(5000){model.state.value.page=="settings"}
  }
  ui.runOnUiThread{ViewCompat.setWindowInsetsAnimationCallback(decor,null)}
 }
}
