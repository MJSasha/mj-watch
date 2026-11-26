package dev.mjwatch.mobile

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.Config
import dev.mjwatch.core.Settings
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DailyUpdateOfferUiTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = target.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val title = "Обновить MJ Watch?"

    @get:Rule(order = 0) val seed = object : org.junit.rules.ExternalResource() {
        override fun before() = runBlocking {
            prefs.edit().clear().putBoolean("automatic", false).commit()
            Settings(target).handleLegacyHistory()
            Settings(target).save(Config(demo = true, theme = "dark"))
            Settings(target).destination("home")
        }
    }
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    private fun model() = ui.runOnIdle { ViewModelProvider(ui.activity)[WatchModel::class.java] }

    private fun exposeUpdate(version: Long?) {
        val model = model()
        ui.runOnIdle {
            val edit = prefs.edit().putLong("lastAttempt", System.currentTimeMillis())
            if (version == null) edit.remove("manifest") else edit.putString("manifest", """
                {"schemaVersion":1,"channel":"testing","versionCode":$version,"versionName":"9.9.9",
                "releaseUrl":"https://github.com/MJSasha/mj-watch/releases/tag/test-999999",
                "packages":{"mobile":{"packageName":"dev.mjwatch.mobile","size":1048576,
                "sha256":"${"0".repeat(64)}","url":"https://github.com/MJSasha/mj-watch/releases/download/test-999999/MJWatch-mobile.apk"}}}
            """.trimIndent())
            edit.commit()
            model.updates.refreshCached()
            model.updates.automatic(true)
        }
    }

    private fun awaitOffer() {
        ui.waitUntil(8_000) {
            runCatching { ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }

    private fun assertNoOfferAfterIdle() {
        Thread.sleep(3_400)
        ui.onNodeWithText(title).assertDoesNotExist()
    }

    @After fun stopUpdate() {
        val model = model()
        ui.runOnIdle {
            PlaybackActivity.stopActivePlayback()
            model.updates.automatic(false)
            model.updates.cancel()
            prefs.edit().clear().putBoolean("automatic", false).commit()
        }
    }

    @Test fun onlyNewVersionAndDismissalPersistsForRollingDay() {
        exposeUpdate(null)
        assertNoOfferAfterIdle()
        exposeUpdate(model().updates.versionCode)
        assertNoOfferAfterIdle()
        exposeUpdate(1_000_999)
        awaitOffer()
        ui.onNodeWithText("Позже").performClick()
        val claimedAt = prefs.getLong("lastDailyOffer", 0)
        Assert.assertTrue(claimedAt > 0)
        ui.activityRule.scenario.recreate()
        assertNoOfferAfterIdle()
        Assert.assertEquals(claimedAt, prefs.getLong("lastDailyOffer", 0))

        prefs.edit().putLong("lastDailyOffer", System.currentTimeMillis() - 86_400_001L).commit()
        val model = model()
        ui.runOnIdle { model.page("search") }
        ui.runOnIdle { model.page("home") }
        awaitOffer()
        Assert.assertTrue(prefs.getLong("lastDailyOffer", 0) > claimedAt)
    }

    @Test fun heldTouchAndManualUpdateScreenDeferOffer() {
        val model = model()
        ui.runOnIdle { model.page("settings-updates") }
        exposeUpdate(1_000_999)
        assertNoOfferAfterIdle()
        ui.onNodeWithText("Проверить сейчас").assertExists()
        ui.onNodeWithTag("settings-list").performScrollToNode(hasText("Скачать обновление"))
        ui.onNodeWithText("Скачать обновление").assertIsDisplayed()
        ui.runOnIdle { model.page("home") }
        val navigation = ui.onNodeWithTag("bottom-navigation")
        navigation.performTouchInput { down(center) }
        try {
            assertNoOfferAfterIdle()
        } finally {
            navigation.performTouchInput { cancel() }
        }
        awaitOffer()
    }

    @Test fun confirmStartsExistingDownloadFromHome() {
        exposeUpdate(1_000_999)
        awaitOffer()
        val model = model()
        val started = CountDownLatch(1)
        val observer = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            model.updates.state.first { it.downloading }
            started.countDown()
        }
        try {
            ui.onNodeWithText("Обновить").performClick()
            Assert.assertTrue("The existing download started", started.await(5, TimeUnit.SECONDS))
            Assert.assertEquals("home", model.state.value.page)
            ui.onNodeWithText(title).assertDoesNotExist()
        } finally {
            observer.cancel()
        }
    }

    @Test fun foregroundPlayerDoesNotConsumeTheOffer() {
        exposeUpdate(1_000_999)
        ui.runOnIdle {
            ui.activity.startActivity(Intent(ui.activity, PlaybackActivity::class.java)
                .putExtra("uri", "asset:///demo.mp4").putExtra("title", "Daily offer test"))
        }
        ui.waitUntil(10_000) { PlaybackActivity.hasActivePlayback() }
        Thread.sleep(3_400)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        Assert.assertFalse(device.hasObject(By.text(title)))
        Assert.assertEquals(0L, prefs.getLong("lastDailyOffer", 0))
        ui.runOnIdle { PlaybackActivity.stopActivePlayback() }
        awaitOffer()
    }
}
