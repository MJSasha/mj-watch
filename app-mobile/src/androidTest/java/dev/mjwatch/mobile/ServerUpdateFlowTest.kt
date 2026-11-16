package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ServerUpdateFlowTest {
 @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
  SecretStore(c).put("serverUpdate:","")
  SecretStore(c).put("maintenance::update:0.1.2","")
  Settings(c).save(Config(demo=true,theme="dark"));Settings(c).destination("settings")
 }}
 @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
 private fun capture(name:String) {
  compose.waitForIdle()
  val i=InstrumentationRegistry.getInstrumentation()
  val dir=File(i.targetContext.getExternalFilesDir(null),"server-update-proof").apply{mkdirs()}
  Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
 }
 @Test fun guidesSelectionCredentialsBackupAndExplicitConfirmation() {
  lateinit var model:WatchModel
  compose.runOnIdle{model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
  compose.waitUntil(10000){model.state.value.config.demo}
  compose.runOnIdle{model.page("server-maintenance")}
  compose.onNodeWithText("Обновить сервер").performScrollTo().performClick()
  compose.onNodeWithText("Шаг 1 из 4").assertExists()
  compose.onNodeWithText("Выбрать встроенный пакет").performScrollTo().performClick()
  compose.onNodeWithText("Встроенный пакет",substring=true).assertExists()
  capture("01-package")
  compose.onNodeWithText("Далее").performScrollTo().performClick()
  compose.onNodeWithText("Далее").assertIsNotEnabled()
  compose.onNodeWithText("Доступ к вашему серверу").assertExists()
  compose.onNodeWithContentDescription("IP или домен сервера").performScrollTo().performTextReplacement("server.example")
  compose.onNodeWithContentDescription("Пароль SSH").performScrollTo().performTextInput("synthetic-ssh-password")
  compose.onNodeWithText("Далее").performScrollTo().performClick()
  compose.onNodeWithText("Защитите резервную копию").assertExists()
  compose.onNodeWithText("Далее").assertIsNotEnabled()
  compose.onNodeWithContentDescription("Придумайте фразу от 16 символов").performScrollTo().performTextInput("synthetic-backup-phrase")
  compose.onNodeWithContentDescription("Повторите фразу").performScrollTo().performTextInput("different")
  compose.onNodeWithText("Далее").assertIsNotEnabled()
  compose.onNodeWithContentDescription("Повторите фразу").performTextReplacement("synthetic-backup-phrase")
  compose.onNode(isToggleable()).performScrollTo().performClick()
  UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
  capture("03-backup")
  compose.onNodeWithText("Далее").performScrollTo().performClick()
  compose.onNodeWithText("Всё готово к обновлению").assertExists()
  capture("04-review")
  compose.onNodeWithText("Обновить до",substring=true).performScrollTo().performClick()
  compose.onNodeWithText("Начать обновление?").assertExists()
  compose.onNodeWithText("Отмена").performClick()
  Assert.assertTrue("No operation starts before confirmation",model.secrets.get("serverUpdate:${model.state.value.config.scope}").isBlank())
  compose.onNodeWithText("Назад").performScrollTo().performClick()
  compose.onNodeWithText("Шаг 3 из 4").assertExists()
 }
 @Test fun pendingOperationOffersCheckingWithoutRestartOrBackupPassword() {
  lateinit var model:WatchModel
  compose.runOnIdle{model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
  compose.waitUntil(10000){model.state.value.config.demo}
  compose.runOnIdle{
   val version=ServerBundles(compose.activity).active().manifest().getString("version")
   model.secrets.put("ssh:${model.state.value.config.scope}",JSONObject().put("host","server.example").put("port",2222).toString())
   model.secrets.put("maintenance:${model.state.value.config.scope}:update:$version","synthetic-previous-operation")
   model.page("server-update")
  }
  compose.onNodeWithText("Проверим результат обновления").assertExists()
  compose.onNodeWithText("Проверить результат обновления").performScrollTo().assertIsNotEnabled()
  compose.onNodeWithContentDescription("Пароль SSH").performScrollTo().performTextInput("synthetic-only")
  compose.onNodeWithText("Проверить результат обновления").performScrollTo().assertIsEnabled()
  compose.onNodeWithText("Обновить до",substring=true).assertDoesNotExist()
  compose.onNodeWithContentDescription("Придумайте фразу от 16 символов").assertDoesNotExist()
  capture("05-pending")
  compose.activityRule.scenario.recreate()
  compose.onNodeWithText("Проверим результат обновления").assertExists()
  compose.onNodeWithText("Проверить результат обновления").performScrollTo().assertIsNotEnabled()
  compose.runOnIdle{
   model.secrets.put("serverUpdate:${model.state.value.config.scope}","")
   model.secrets.put("ssh:${model.state.value.config.scope}","")
   val version=ServerBundles(compose.activity).active().manifest().getString("version")
   model.secrets.put("maintenance:${model.state.value.config.scope}:update:$version","")
  }
 }
}
