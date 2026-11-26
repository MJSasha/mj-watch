package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File

/** Geometry and navigation contracts; local demo data, no backend. Run at phone and tablet sizes. */
class AdaptiveLayoutTest {
 @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {override fun before()=runBlocking {
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
  c.getSharedPreferences("playback-preferences",0).edit().clear().commit()
  Settings(c).handleLegacyHistory();Settings(c).save(Config(demo=true,theme="dark"));Settings(c).destination("home")
 }}
 @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
 private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
 private val density get()=ui.activity.resources.displayMetrics.density
 private fun capture(name:String) {
  ui.waitForIdle();Thread.sleep(400)
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val dir=File(c.getExternalFilesDir(null),"adaptive-layout").apply{mkdirs()}
  Assert.assertTrue(device.takeScreenshot(File(dir,"$name.png")))
 }
 @After fun restoreRotation(){device.setOrientationNatural();device.unfreezeRotation()}
 @Test fun floatingNavigationUsesTheLargerOfSystemInsetAndEightDp() {
  Assume.assumeTrue(ui.activity.resources.configuration.screenWidthDp<600)
  val navigation=ui.onNodeWithTag("bottom-navigation").assertIsDisplayed()
  val window=ui.activity.window
  val type=WindowInsetsCompat.Type.navigationBars()
  val controller=WindowCompat.getInsetsController(window,window.decorView)
  fun bottomInset()=ui.runOnIdle {
   (ViewCompat.getRootWindowInsets(window.decorView)?.getInsets(type)?.bottom ?: 0)/density
  }
  fun assertGap() {
   val expected=maxOf(8f,bottomInset())
   val available=ui.onRoot().getUnclippedBoundsInRoot().bottom
   val bar=navigation.getUnclippedBoundsInRoot().bottom
   Assert.assertEquals("System inset or eight dp, whichever is larger",expected,(available-bar).value,1f)
  }
  assertGap()
  try {
   ui.runOnIdle {controller.hide(type)}
   ui.waitUntil(5000) {bottomInset()==0f}
   assertGap()
   capture("navigation-zero-inset")
  } finally {
   ui.runOnIdle { controller.show(type) }
  }
  device.setOrientationLeft()
  ui.waitUntil(5000) {ui.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
  if(ui.onAllNodesWithTag("bottom-navigation").fetchSemanticsNodes().isNotEmpty()) assertGap()
 }
 @Test fun boundedActionsFormsAndReleaseBackSurviveRotation() {
  lateinit var model:WatchModel
  ui.runOnIdle{model=ViewModelProvider(ui.activity)[WatchModel::class.java]}
  ui.waitUntil(15000){model.state.value.config.demo&&model.state.value.configured}
  val width=ui.activity.resources.configuration.screenWidthDp
  ui.onNodeWithTag("home-list").performScrollToNode(hasTestTag("hero-open"))
  val hero=ui.onNodeWithTag("hero-open").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
  Assert.assertTrue("CTA touch height",hero.height/density>=51)
  if(width>=600) Assert.assertTrue("Tablet CTA bounded",hero.width/density<=321)
  else Assert.assertEquals("CTA spans the hero caption",(width-64).toFloat(),hero.width/density,2f)
  ui.onNodeWithTag("hero-open").assertHasClickAction()
  capture("01-home")
  if(width>=800) {
   ui.onNodeWithTag("home-list").performScrollToNode(hasText(DemoReleases.items.last().title))
   val first=ui.onNodeWithText(DemoReleases.items.first().title).fetchSemanticsNode().boundsInRoot
   val second=ui.onNodeWithText(DemoReleases.items.last().title).fetchSemanticsNode().boundsInRoot
   Assert.assertTrue("Home cards share a row after rail",first.right<second.left)
  }
  ui.runOnIdle{model.page("settings")}
  Assert.assertTrue("Settings reading width",ui.onNodeWithTag("settings-list").fetchSemanticsNode().boundsInRoot.width/density<=641)
  capture("02-settings")
  ui.onNodeWithTag("settings-list").performScrollToNode(hasText("Выйти из приложения"))
  ui.onNodeWithText("Выйти из приложения").assertIsDisplayed()
  ui.runOnIdle{model.page("settings-server")}
  val field=ui.onNodeWithText("Адрес TorrServer").assertIsDisplayed()
  Assert.assertTrue("Form width",field.fetchSemanticsNode().boundsInRoot.width/density<=641)
  field.performTextReplacement("http://192.0.2.1:8090")
  capture("03-form-ime");device.pressBack()
  ui.onNodeWithTag("settings-list").performScrollToNode(hasText("Сохранить"))
  ui.onNodeWithText("Сохранить").assertIsDisplayed()
  capture("04-form-scrolled")
  ui.onNodeWithContentDescription("Назад").performClick()
  ui.onNodeWithText("Сохранить изменения?").assertIsDisplayed()
  ui.onNodeWithText("Не сохранять").performClick()
  ui.runOnIdle{model.page("search")}
  ui.onNodeWithTag("search-field").performTextInput("тест")
  ui.waitUntil(10000){model.state.value.results.isNotEmpty()};device.pressBack()
  ui.onNodeWithText(DemoReleases.items.first().title).performClick()
  ui.onNodeWithTag("detail-sources").assertIsDisplayed().performClick()
  ui.onNodeWithTag("release-list").performScrollToNode(hasText(DemoReleases.items.first().title))
  ui.onNodeWithText(DemoReleases.items.first().title).performClick()
  ui.onNodeWithTag("source-continue").assertIsDisplayed()
  if(width>=840) ui.onNodeWithContentDescription("Назад").assertIsDisplayed()
  capture("05-releases")
  device.setOrientationLeft();Thread.sleep(900);ui.waitForIdle()
  Assert.assertEquals("releases",model.state.value.page)
  Assert.assertEquals("тест",model.state.value.query)
  ui.onNodeWithTag("source-continue").assertIsDisplayed()
  capture("06-releases-rotated")
  if(ui.activity.resources.configuration.screenWidthDp>=840) ui.onNodeWithContentDescription("Назад").performClick() else device.pressBack()
  ui.waitUntil(5000){model.state.value.page=="detail"}
  Assert.assertEquals(DemoReleases.items.first().id,model.state.value.selected?.id)
  ui.onNodeWithContentDescription("Назад").performClick()
  ui.waitUntil(5000){model.state.value.page=="search"}
  Assert.assertEquals("тест",model.state.value.query)
  capture("07-search-restored")
 }
 @Test fun shortEmptySearchShowsHeadingBeforeScrolling() {
  Assume.assumeTrue(ui.activity.resources.configuration.screenHeightDp<480)
  lateinit var model:WatchModel
  ui.runOnIdle{model=ViewModelProvider(ui.activity)[WatchModel::class.java];model.page("search")}
  ui.waitUntil(10000){model.state.value.configured}
  ui.onNodeWithTag("search-field").performTextInput("no-fixture-match")
  ui.waitUntil(10000){!model.state.value.busy&&model.state.value.query=="no-fixture-match"}
  device.pressBack()
  ui.onNodeWithText("Ничего не найдено").assertIsDisplayed()
  capture("08-short-empty-search")
  ui.onNodeWithText("Изменить запрос").performScrollTo().assertIsDisplayed()
 }
}
