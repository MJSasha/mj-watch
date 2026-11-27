@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
class RemoteNavigationUiTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        Settings(c).handleLegacyHistory();Settings(c).save(Config(demo=true,theme="light"));Settings(c).destination("home")
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun shot(name:String) {
        compose.waitForIdle();compose.mainClock.advanceTimeBy(350);Thread.sleep(350)
        val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"issues-8-12").apply {mkdirs()}
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
    }
    private val remote get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun startAtHome():WatchModel {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {model.state.value.config.demo&&model.state.value.page=="home"}
        compose.waitUntil(5000) {compose.onAllNodes(hasText(DemoReleases.items.first().title) and isFocused()).fetchSemanticsNodes().isNotEmpty()}
        remote.pressBack()
        compose.onNode(hasText("Главная") and isFocused()).assertExists()
        return model
    }

    @Test fun arrowsPreviewRailAndSettingsWithoutExecutingActions() {
        val model=startAtHome()
        remote.pressDPadDown()
        compose.waitUntil(5000) {model.state.value.page=="search"}
        compose.onNode(hasText("Поиск") and isFocused()).assertExists()
        compose.onNodeWithTag("search-field").assertIsDisplayed()
        shot("tv-rail-search")
        for(destination in listOf("history","library","settings")) {
            remote.pressDPadDown()
            compose.waitUntil(5000) {model.state.value.page==destination}
        }
        compose.onNode(hasText("Настройки") and isFocused()).assertExists()
        remote.pressDPadRight()
        compose.onNode(hasText("Подключения") and isFocused()).assertIsSelected()
        remote.pressDPadDown()
        compose.onNode(hasText("Воспроизведение") and isFocused()).assertIsSelected()
        compose.onNodeWithText("Предпочтительное качество").assertIsDisplayed()
        remote.pressDPadDown()
        compose.onNode(hasText("Оформление") and isFocused()).assertIsSelected()
        compose.onNodeWithText("Тема приложения").assertIsDisplayed()
        shot("tv-settings-preview")
        remote.pressDPadRight()
        compose.onNode(hasText("Как в системе") and isFocused()).assertExists()
        Assert.assertEquals("light",model.state.value.config.theme)
        remote.pressDPadLeft()
        compose.onNode(hasText("Оформление") and isFocused()).assertExists()
    }

    @Test fun heldUpMovesAcrossRailBeforeOpeningHome() {
        val model=startAtHome()
        repeat(4) { remote.pressDPadDown() }
        compose.waitUntil(5000) { model.state.value.page=="settings" }
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val uptime=android.os.SystemClock.uptimeMillis()
        val up=android.view.KeyEvent.KEYCODE_DPAD_UP
        repeat(4) { repeatCount ->
            instrumentation.sendKeySync(android.view.KeyEvent(uptime,android.os.SystemClock.uptimeMillis(),
                android.view.KeyEvent.ACTION_DOWN,up,repeatCount))
            compose.waitForIdle()
            Assert.assertEquals("Passing over a destination must not open it while Up is held", "settings",model.state.value.page)
        }
        compose.onNode(hasText("Главная") and isFocused()).assertExists()
        instrumentation.sendKeySync(android.view.KeyEvent(uptime,android.os.SystemClock.uptimeMillis(),
            android.view.KeyEvent.ACTION_UP,up,0))
        compose.waitUntil(5000) { model.state.value.page=="home" }
        compose.onNode(hasText("Главная") and isFocused()).assertIsSelected()
    }

    @Test fun downFromFileSeasonFilterEntersEpisodeList() {
        val model=startAtHome()
        compose.runOnIdle { model.open(DemoReleases.items.last());model.prepare() }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("file-3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сезон 1").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Сезон 1").assertIsFocused()
        remote.pressDPadDown()
        compose.onNodeWithTag("file-3").assertIsFocused()
        remote.pressDPadDown()
        compose.onNodeWithTag("file-17").assertIsFocused()
    }

    @Test fun searchAndEpisodeSelectionRestoreTheirOwnFocus() {
        val model=startAtHome()
        remote.pressDPadDown()
        compose.waitUntil(5000) {model.state.value.page=="search"}
        val field=compose.onNodeWithTag("search-field").assertIsDisplayed()
        remote.pressDPadRight()
        field.assertIsFocused()
        field.performTextReplacement("сериал")
        field.performImeAction()
        val result=compose.onNodeWithText(DemoReleases.items.last().title)
        compose.waitUntil(10000) {compose.onAllNodes(hasText(DemoReleases.items.last().title) and isFocused()).fetchSemanticsNodes().isNotEmpty()}
        shot("tv-search-result")
        remote.pressDPadCenter()
        compose.waitUntil(5000) {model.state.value.page=="detail"}
        compose.onNodeWithTag("detail-choose-episode").assertIsFocused()
        remote.pressDPadCenter()
        compose.onNode(hasText("Сезон 1") and isFocused()).assertIsSelected()
        remote.pressDPadDown()
        compose.onNodeWithTag("episode-1").assertIsFocused()
        remote.pressDPadRight()
        val episode=compose.onNodeWithTag("episode-2").assertIsFocused()
        shot("tv-episode-selection")
        remote.pressDPadCenter()
        compose.waitUntil(5000) {model.state.value.page=="releases"&&!model.state.value.busy}
        Assert.assertEquals(2,model.state.value.releaseEpisode)
        remote.pressBack()
        compose.waitUntil(5000) {model.state.value.page=="detail"}
        episode.assertIsFocused()
        remote.pressBack()
        compose.waitUntil(5000) {model.state.value.page=="search"}
        Assert.assertEquals("сериал",model.state.value.query)
        compose.waitUntil(5000) {compose.onAllNodes(hasText(DemoReleases.items.last().title) and isFocused()).fetchSemanticsNodes().isNotEmpty()}
        result.assertIsFocused()
    }

    @Test fun connectionScreensFocusTheirFirstUsefulControl() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000){model.state.value.config.demo}
        compose.runOnIdle {model.page("connection-choice")}
        compose.waitUntil(5000){runCatching{compose.onNodeWithText("Готовый TorrServer").assertIsFocused();true}.getOrDefault(false)}
        compose.runOnIdle {model.page("settings-search")}
        compose.waitUntil(5000){runCatching{compose.onNodeWithText("Адрес Jackett / JacRed").assertIsFocused();true}.getOrDefault(false)}
        shot("tv-connection-focus")
        compose.runOnIdle {model.back()}
    }
    @Test fun repeatedOkPlaysAndTimelineArrowsLeaveWithoutSeeking() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000){model.state.value.config.demo}
        compose.onNodeWithText("История").assertIsDisplayed()
        compose.onNodeWithText("Моё").assertIsDisplayed()
        shot("tv-light-home")
        compose.runOnIdle {model.open(DemoReleases.items.first());model.prepare()}
        compose.waitUntil(10000){compose.onAllNodesWithTag("file-3").fetchSemanticsNodes().isNotEmpty()}
        val file=compose.onNodeWithTag("file-3")
        file.performSemanticsAction(SemanticsActions.RequestFocus)
        file.performKeyInput {pressKey(Key.DirectionCenter)}
        compose.onNodeWithText("OK — выбрать серию · повторный OK — смотреть").assertExists()
        file.performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(20000){compose.onAllNodesWithTag("player-play").fetchSemanticsNodes().isNotEmpty()}
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("(?i)got it"))),1000)?.click()
        // The controls may exist while the short fixture is still buffering on a cold TV.
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription("Пауза").fetchSemanticsNodes().isNotEmpty()&&
                compose.onAllNodesWithTag("playback-status").fetchSemanticsNodes().isEmpty()
        }
        // A real D-pad event exits the touch mode entered by Android's fullscreen hint.
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressDPadDown()
        compose.waitForIdle()
        compose.onNodeWithTag("player-play").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("player-play").assertIsFocused()
        compose.onNodeWithContentDescription("Пауза").performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(5000){compose.onAllNodesWithContentDescription("Воспроизвести").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("player-play").performKeyInput {pressKey(Key.DirectionUp)}
        val timeline=compose.onNodeWithTag("player-timeline")
        timeline.assertIsFocused()
        val before=timeline.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        timeline.performKeyInput {pressKey(Key.DirectionUp)}
        compose.waitUntil(5000){compose.onAllNodesWithTag("player-play").fetchSemanticsNodes().isEmpty()}
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressDPadDown()
        compose.waitForIdle()
        compose.onNodeWithTag("player-play").assertIsFocused()
        val after=timeline.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        Assert.assertEquals("Vertical navigation must not seek",before,after,250f)
        compose.onNodeWithTag("player-play").performKeyInput {pressKey(Key.DirectionUp)}
        timeline.performKeyInput {pressKey(Key.DirectionRight)}
        compose.waitUntil(5000){timeline.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current>before+1000}
        shot("tv-timeline-focus")
        timeline.performKeyInput {pressKey(Key.DirectionDown)}
        compose.onNodeWithTag("player-tracks").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("player-tracks").performKeyInput {pressKey(Key.DirectionCenter)}
        Assert.assertNotNull(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.textContains("язык не указан")),3000))
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    }
}
