@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
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
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.mjwatch.designsystem.TorrServerEditor
import dev.mjwatch.designsystem.PlaybackLimitsEditor
import dev.mjwatch.designsystem.WatchTheme
import org.json.JSONObject

class TorrSettingsUiTest {
 @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
  Settings(context).save(Config(demo=true,theme="dark"));Settings(context).destination("home")
 }}
 @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
 @Test fun accessPageIsReadable() {
  ui.runOnIdle {ViewModelProvider(ui.activity)[WatchModel::class.java].page("server-torr")}
  ui.onNodeWithText("Настройки TorrServer").assertExists()
  val i=InstrumentationRegistry.getInstrumentation()
  val dir=File(i.targetContext.getExternalFilesDir(null),"torr-settings").apply{mkdirs()}
  ui.waitForIdle()
  Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"access.png")))
 }
 @Test fun inlineRecommendationsAndMeasuredMaximumFollowCacheMode(){
  val i=InstrumentationRegistry.getInstrumentation()
  val tv=InstrumentationRegistry.getArguments().getString("renderTv")=="true"
  val snapshot=JSONObject("""{
   "settings":{"UseDisk":false,"CacheSize":67108864,"PreloadCache":50,"ConnectionsLimit":60,"ReaderReadAHead":95},
   "fields":[{"name":"UseDisk","label":"Дисковый кэш","boolean":true},{"name":"CacheSize","label":"Размер кэша","min":16777216,"max":8589934592},{"name":"PreloadCache","label":"Предзагрузка","min":0,"max":100,"unit":"%"},{"name":"ConnectionsLimit","label":"Соединения","min":10,"max":1000},{"name":"ReaderReadAHead","label":"Чтение вперёд","min":5,"max":100,"unit":"%"}],
   "resources":{"totalBytes":536870912,"availableBytes":402653184,"reserveBytes":234881024,"plannedStreams":1,"maximumBytes":167772160,"recommendedBytes":67108864},
   "storage":{"available":true,"availableBytes":4294967296,"maximumBytes":2147483648,"recommendedBytes":268435456}
  }""")
  var observed=JSONObject(snapshot.getJSONObject("settings").toString())
  ui.runOnUiThread{ui.activity.setContent{WatchTheme("dark"){
   var draft by remember{mutableStateOf(observed)}
   Surface{Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("torr-editor-test"),verticalArrangement=Arrangement.spacedBy(16.dp)){
    TorrServerEditor(snapshot,draft,tv,{draft=it;observed=it},{})
   }}
  }}}
  ui.onNodeWithText("160 МиБ").assertExists()
  ui.onNodeWithContentDescription("Размер кэша").performTextReplacement("256")
  ui.onNodeWithText("Выше доступного бюджета сервера").assertExists()
  ui.onNodeWithText("64 МиБ").performClick()
  ui.runOnIdle{Assert.assertEquals(67108864L,observed.getLong("CacheSize"))}
  capture("memory")
  ui.onNodeWithText("На диске").performClick()
  ui.onNodeWithText("2048 МиБ").assertExists()
  ui.onNodeWithText("256 МиБ").performClick()
  ui.runOnIdle{Assert.assertTrue(observed.getBoolean("UseDisk"));Assert.assertEquals(268435456L,observed.getLong("CacheSize"))}
  capture("disk")
  ui.onNodeWithTag("torr-editor-test").performScrollToNode(hasText("Сеть"))
  if(tv)ui.onNodeWithText("Сеть").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus).performKeyInput{pressKey(androidx.compose.ui.input.key.Key.DirectionCenter)}
  else ui.onNodeWithText("Сеть").performClick()
  ui.onNodeWithTag("torr-editor-test").performScrollToNode(hasText("Соединения"))
  ui.onNodeWithContentDescription("Соединения").assertExists()
 }
 @Test fun perAccountMaximumTracksSelectedServerLimit(){
  val snapshot=JSONObject("""{"settings":{"maxStreams":0,"maxStreamsPerUser":0},"recommendedMaxStreams":1,"maximumStreams":8}""")
  var observed=JSONObject(snapshot.getJSONObject("settings").toString())
  ui.runOnUiThread{ui.activity.setContent{WatchTheme("dark"){
   var draft by remember{mutableStateOf(observed)}
   Surface{Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)){
    PlaybackLimitsEditor(snapshot,draft,InstrumentationRegistry.getArguments().getString("renderTv")=="true",false,{draft=it;observed=it},{})
   }}
  }}}
  ui.onNodeWithContentDescription("На сервере").performClick();ui.onNodeWithText("2",useUnmergedTree=true).performClick()
  ui.onNodeWithContentDescription("На один аккаунт").performClick()
  ui.onAllNodesWithText("3",useUnmergedTree=true).assertCountEquals(0)
  ui.onAllNodesWithText("2",useUnmergedTree=true).onLast().performClick()
  ui.runOnIdle{Assert.assertEquals(2,observed.getInt("maxStreams"));Assert.assertEquals(2,observed.getInt("maxStreamsPerUser"))}
  capture("limits")
 }
 private fun capture(name:String){
  ui.waitForIdle();Thread.sleep(300);val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"torr-settings").apply{mkdirs()}
  Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
 }
}
