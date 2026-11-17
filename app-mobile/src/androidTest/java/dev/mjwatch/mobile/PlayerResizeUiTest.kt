package dev.mjwatch.mobile

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import dev.mjwatch.playback.PlaybackEpisode
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File

/** Explicit emulator-only resize check: pass -e playerResize true. Never resizes a normal CI/device run. */
class PlayerResizeUiTest {
 @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
  Settings(c).handleLegacyHistory();Settings(c).save(Config(demo=true));Settings(c).destination("home")
 }}
 @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
 private val i get()=InstrumentationRegistry.getInstrumentation()
 private val device get()=UiDevice.getInstance(i)
 private var activity:PlaybackActivity?=null
 private var originalSize:String?=null
 private var originalDensity:String?=null
 private var resized=false
 private fun field(a:PlaybackActivity,name:String)=PlaybackActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)
 private fun current():PlaybackActivity?=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()
 private fun resize(width:Int,height:Int) {
  device.executeShellCommand("wm size ${width*2}x${height*2}")
  ui.waitUntil(10000){var fits=false;i.runOnMainSync {fits=activity?.window?.decorView?.let {it.width==width*2&&it.height==height*2} ?: (ui.activity.resources.configuration.screenWidthDp==width)};fits}
  ui.waitForIdle()
 }
 @After fun cleanup(){if(resized){i.runOnMainSync{activity?.finish()};device.executeShellCommand("wm size ${originalSize ?: "reset"}");device.executeShellCommand("wm density ${originalDensity ?: "reset"}")}}
 @Test fun episodeControlsFitCurrentWindowAndKeepPausedPositionThroughFoldResize() {
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("playerResize")=="true")
  originalSize=Regex("Override size: (\\d+x\\d+)").find(device.executeShellCommand("wm size"))?.groupValues?.get(1)
  originalDensity=Regex("Override density: (\\d+)").find(device.executeShellCommand("wm density"))?.groupValues?.get(1)
  resized=true;device.executeShellCommand("wm density 320");resize(720,800)
  val queue=(1..3).map{PlaybackEpisode("asset:///demo.mp4","Серия $it.mp4",it)}
  i.targetContext.startActivity(Intent(i.targetContext,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
   .putExtra("mobile",true).putExtra("uri",queue[1].uri).putExtra("title","Fold resize fixture").putExtra("path",queue[1].path).putExtra("fileId",2).putExtra("episodes",PlaybackEpisode.encode(queue)))
  ui.waitUntil(20000){i.runOnMainSync{activity=current()?.takeIf{it.firstFrameSeen}};activity!=null}
  val a=activity!!
  lateinit var player:Player
  i.runOnMainSync{player=field(a,"player") as Player;player.pause();player.seekTo(2000)}
  ui.waitUntil(5000){var ready=false;i.runOnMainSync{ready=player.playbackState==Player.STATE_READY&&!player.isPlaying&&kotlin.math.abs(player.currentPosition-2000)<500};ready}
  if(ui.onAllNodesWithTag("player-play").fetchSemanticsNodes().isEmpty())ui.onNodeWithTag("player-gestures").performClick()
  ui.onNodeWithContentDescription("Воспроизвести").assertIsDisplayed()
  var position=0L;i.runOnMainSync{position=player.currentPosition}
  for((index,size) in listOf(420 to 740,720 to 800,420 to 740,460 to 700,800 to 720,460 to 700).withIndex()) {
   resize(size.first,size.second)
   val bounds=ui.onNodeWithTag("player-controls-actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
   val density=a.resources.displayMetrics.density
   for(label in listOf("Предыдущая серия","Назад на 10 секунд","Воспроизвести","Вперёд на 10 секунд","Следующая серия","Аудио и субтитры","Масштаб","PiP")) {
    val action=ui.onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().touchBoundsInRoot
    Assert.assertTrue("$label inside current window",action.left>=bounds.left&&action.right<=bounds.right&&action.top>=bounds.top&&action.bottom<=bounds.bottom&&action.right<=size.first*2&&action.bottom<=size.second*2)
    Assert.assertTrue("$label retains touch target: ${action.width/density} x ${action.height/density}dp",action.width/density>=47.5f&&action.height/density>=47.5f)
   }
   ui.onNodeWithTag("player-play").assertWidthIsAtLeast(androidx.compose.ui.unit.Dp(56f)).assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(56f))
   val view=field(a,"view")!!;var before=0
   i.runOnMainSync{before=view.javaClass.getMethod("getResizeMode").invoke(view) as Int}
   ui.onNodeWithContentDescription("Масштаб").performClick()
   i.runOnMainSync{Assert.assertNotEquals(before,view.javaClass.getMethod("getResizeMode").invoke(view));Assert.assertSame(a,current());Assert.assertSame(player,field(a,"player"));Assert.assertFalse(player.playWhenReady);Assert.assertFalse(player.isPlaying);Assert.assertEquals(position,player.currentPosition)}
   val dir=File(i.targetContext.getExternalFilesDir(null),"player-resize").apply{mkdirs()}
   Assert.assertTrue(device.takeScreenshot(File(dir,"$index-${size.first}x${size.second}.png")))
  }
 }
}
