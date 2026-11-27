@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File

class ReleaseSelectionTvUiTest {
    @get:Rule(order=0)val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        c.getSharedPreferences("playback-preferences",0).edit().clear().commit()
        Settings(c).handleLegacyHistory();Settings(c).save(Config(demo=true,theme="dark"));Settings(c).destination("home")
    }}
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private fun SemanticsNodeInteraction.remoteClick():SemanticsNodeInteraction {
        performSemanticsAction(SemanticsActions.RequestFocus)
        assertIsFocused()
        performKeyInput{pressKey(Key.DirectionCenter)}
        return this
    }
    private fun shot(name:String){
        if(InstrumentationRegistry.getArguments().getString("releaseReview")==null)return
        ui.waitForIdle();Thread.sleep(350)
        val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"release-review").apply{mkdirs()}
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"tv-$name.png")))
    }
    @Test fun remoteFiltersResetAndSeriesSelectionRemainReachable(){
        lateinit var model:WatchModel
        ui.runOnIdle{model=ViewModelProvider(ui.activity)[WatchModel::class.java];model.search("тест")}
        ui.waitUntil(10000){!model.state.value.busy&&model.state.value.results.size==2}
        ui.runOnIdle{model.page("releases")}
        ui.waitUntil(5000){runCatching{ui.onNodeWithText(DemoReleases.items.first().title).assertIsFocused();true}.getOrDefault(false)}
        val remote=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val filters=ui.onNodeWithText("Фильтры")
        filters.performSemanticsAction(SemanticsActions.RequestFocus)
        filters.assertIsFocused()
        remote.pressDPadCenter()
        val filterContent=hasAnyAncestor(hasTestTag("release-filters"))
        ui.onNode(hasText("Все") and filterContent).assertIsFocused()
        remote.pressDPadRight()
        ui.onNode(hasText("1080p") and filterContent).assertIsFocused()
        remote.pressDPadRight()
        val quality=ui.onNode(hasText("720p") and filterContent).assertIsFocused()
        remote.pressDPadCenter()
        quality.assertIsFocused().assertIsSelected()
        remote.pressBack()
        ui.onNodeWithText("Фильтры · 1").assertIsFocused()
        remote.pressDPadCenter()
        quality.assertIsFocused()
        remote.pressDPadLeft()
        ui.onNode(hasText("Все") and filterContent).assertIsFocused()
        remote.pressDPadCenter()
        shot("filters")
        ui.onNodeWithTag("release-filters").performScrollToNode(hasText("0 сидов"))
        ui.onNode(hasText("0 сидов") and hasAnyAncestor(hasTestTag("release-filters"))).remoteClick()
        ui.waitUntil(5000) {ui.onAllNodesWithText("Показать результаты (1)").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("Показать результаты (1)").remoteClick()
        ui.onNodeWithText("Фильтры · 1").assertIsFocused()
        ui.onNodeWithText(DemoReleases.items.last().title).assertIsDisplayed()
        ui.onNodeWithText(DemoReleases.items.first().title).assertDoesNotExist()
        ui.onNodeWithText("Сбросить всё").remoteClick()
        ui.waitUntil(5000) {ui.onAllNodesWithText(DemoReleases.items.first().title).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText(DemoReleases.items.first().title).assertIsDisplayed()
        ui.runOnIdle{model.playRelease(DemoReleases.items.first(),null)}
        ui.waitUntil(10000){ui.onAllNodesWithTag("file-3").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("file-3").remoteClick().assertIsSelected();shot("episodes")
        ui.onNodeWithTag("file-continue").assertIsDisplayed()
        ui.onNodeWithText("Все файлы и sample").remoteClick()
        ui.onNodeWithTag("file-list").performScrollToNode(hasTestTag("file-17"))
        ui.onNodeWithTag("file-17").remoteClick().assertIsSelected()
        shot("episode-selected")
    }
}
