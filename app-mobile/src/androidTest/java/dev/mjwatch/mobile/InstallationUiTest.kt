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
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InstallationUiTest {
 @get:Rule(order=0)val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
  Settings(context).save(Config(theme="dark"));Settings(context).destination("settings")
 }}
 @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
 @Test fun failedPreflightShowsStagesSafeDetailsAndEditableRetry(){
  lateinit var model:WatchModel
  compose.runOnIdle{model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
  compose.waitUntil(10000){model.state.value.config.theme=="dark"&&model.state.value.page=="connection-choice"}
  compose.runOnIdle{model.legacyHandled();model.page("server-install")}
  // Invalid host fails locally. No SSH request or real server is involved.
  compose.onNodeWithContentDescription("IP или домен сервера").performTextInput("https://fixture.invalid")
  compose.onNodeWithContentDescription("Пароль SSH").performTextInput("fixture-secret")
  compose.onNodeWithText("Установить автоматически").performScrollTo().performClick()
  compose.onNodeWithText("Установка приостановлена").assertExists()
  compose.onNodeWithText("Проверяем сервер").assertExists()
  compose.onNodeWithText("Устанавливаем TorrServer").assertExists()
  compose.onNodeWithText("Устанавливаем сервис MJWatch").assertExists()
  compose.onNodeWithText("Настраиваем защиту и автозапуск").assertExists()
  compose.onNodeWithText("Проверяем и подключаем приложение").assertExists()
  compose.onNodeWithText("Укажите IP или домен без протокола и пути").assertExists()
  compose.onNodeWithContentDescription("Пароль SSH").assertDoesNotExist()
  val i=InstrumentationRegistry.getInstrumentation()
  val folder=File(i.targetContext.getExternalFilesDir(null),"installation-stages").apply{mkdirs()}
  Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(folder,"failed-stages.png")))
  compose.onNodeWithText("Подробности").performScrollTo().performClick()
  compose.onNodeWithText("stage=checking\nstate=failed\ncode=client_validation\nupdated=0").performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("fixture-secret",substring=true).assertDoesNotExist()
  compose.onNodeWithText("https://fixture.invalid",substring=true).assertDoesNotExist()
  compose.onNodeWithText("Вернуться к SSH-параметрам").performScrollTo().performClick()
  compose.onNodeWithContentDescription("IP или домен сервера").assertExists()
 }
}
