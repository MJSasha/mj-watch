package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabletTest {
 @get:Rule(order=0) val settings=object:org.junit.rules.ExternalResource() {override fun before()=runBlocking {
    val c=InstrumentationRegistry.getInstrumentation().targetContext
    Settings(c).save(Config(demo=true,theme="dark"));Settings(c).destination("search")
 }}
 @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
 @Test fun searchAndDetailUseSeparatePanes() {
    Assume.assumeTrue(compose.activity.resources.configuration.screenWidthDp>=840)
    compose.onNodeWithText("Название фильма или раздачи").performTextInput("тест")
    compose.waitUntil(10000) {compose.onAllNodesWithText(DemoReleases.items.first().title).fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithText(DemoReleases.items.first().title).performClick()
    val search=compose.onNodeWithText("Название фильма или раздачи").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val detail=compose.onNodeWithTag("detail-cast").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    Assert.assertTrue(search.right<detail.left)
    compose.waitForIdle()
    val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync()
    val dir=java.io.File(i.targetContext.getExternalFilesDir(null),"screenshots").apply {mkdirs()}
    UiDevice.getInstance(i).takeScreenshot(java.io.File(dir,"tablet-search-detail.png"))
 }
}
