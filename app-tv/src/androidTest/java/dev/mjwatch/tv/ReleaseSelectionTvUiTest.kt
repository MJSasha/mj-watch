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
        ui.onNodeWithText("Фильтры").remoteClick();shot("filters")
        ui.onNodeWithTag("release-filters").performScrollToNode(hasText("0 сидов"))
        ui.onNode(hasText("0 сидов") and hasAnyAncestor(hasTestTag("release-filters"))).remoteClick()
        ui.onNodeWithText("Показать результаты (1)").remoteClick()
        ui.onNodeWithText(DemoReleases.items.first().title).assertDoesNotExist()
        ui.onNodeWithText("Сбросить всё").remoteClick()
        ui.onNodeWithText(DemoReleases.items.first().title).assertExists()
        ui.runOnIdle{model.playRelease(DemoReleases.items.first(),null)}
        ui.waitUntil(10000){model.state.value.page=="files"}
        ui.onNodeWithTag("file-3").remoteClick().assertIsSelected();shot("episodes")
        ui.onNodeWithTag("file-continue").assertIsDisplayed()
        ui.onNodeWithText("Все файлы и sample").remoteClick()
        ui.onNodeWithTag("file-list").performScrollToNode(hasTestTag("file-17"))
        ui.onNodeWithTag("file-17").remoteClick().assertIsSelected()
        shot("episode-selected")
    }
}
