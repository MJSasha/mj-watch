package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvitationUiTest {
 @get:Rule(order=0)val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
  SecretStore(context).put("ca:https://fixture.invalid:8443","")
  Settings(context).save(Config(theme="dark"));Settings(context).destination("settings")
 }}
 @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
 @Test fun wholeInvitationRequiresExplicitOriginBoundCaConfirmation(){
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val ca=instrumentation.context.assets.open("invitation-ca.pem").bufferedReader().use{it.readText()}.trim()
  val packet=JSONObject().put("url","https://fixture.invalid:8443").put("invite","fixture-invitation-token").put("ca",ca).toString()
  lateinit var model:WatchModel
  compose.runOnIdle{model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
  compose.waitUntil(10000){model.state.value.config.theme=="dark"&&model.state.value.page=="connection-choice"}
  compose.runOnIdle{model.legacyHandled();model.page("managed-login")}
  compose.runOnIdle{
   val clipboard=instrumentation.targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
   clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture",packet))
  }
  compose.onNodeWithText("Вставить приглашение").performScrollTo().performClick()
  Assert.assertEquals("",model.secrets.get("ca:https://fixture.invalid:8443"))
  compose.onNodeWithText("Подтвердить").performClick()
  compose.runOnIdle{Assert.assertEquals(ca,model.secrets.get("ca:https://fixture.invalid:8443"));Assert.assertEquals("",model.secrets.get("ca:https://fixture.invalid:443"))}
  compose.onNodeWithText("https://fixture.invalid:8443").assertExists()
  compose.onNodeWithContentDescription("Придумайте пароль · от 10 символов").performScrollTo().performTextInput("fixture-password")
  compose.onNodeWithText("Создать аккаунт и войти").performScrollTo().assertIsEnabled()
  compose.onNodeWithText("Войти",useUnmergedTree=false).assertDoesNotExist()
  compose.runOnIdle{
   val input=compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
   input.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken,0)
  }
  compose.waitForIdle();Thread.sleep(350)
  val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"invitation-flow").apply{mkdirs()}
  Assert.assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(folder,"ready.png")))
  // Do not consume the fixture token or contact any server.
 }
}
