package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import java.io.File

/** Opt-in HTTPS metadata fixture: docs/testing/release-selection-2026-11-19/episodes_fixture.py. */
class ManagedEpisodesUiTest {
    @get:Rule(order=0)val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("episodesFixture")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val bootstrap=JSONObject(java.net.URL("http://127.0.0.1:18546/bootstrap").readText())
        val config=Config(connectionMode="managed",managedUrl=bootstrap.getString("url"),serverId="episodes-ui",userId="viewer",demo=true,theme=InstrumentationRegistry.getArguments().getString("theme") ?: "dark")
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        c.getSharedPreferences("playback-preferences",0).edit().clear().commit()
        val secrets=SecretStore(c);secrets.put("ca:${endpoint(config.managedUrl).origin()}",bootstrap.getString("ca"));ManagedApi(config,secrets).saveTokens(bootstrap)
        Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("home")
    }}
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private fun shot(name:String){
        ui.waitForIdle();Thread.sleep(350)
        val i=InstrumentationRegistry.getInstrumentation();val theme=InstrumentationRegistry.getArguments().getString("theme") ?: "dark"
        val dir=File(i.targetContext.getExternalFilesDir(null),"release-review/managed-$theme").apply{mkdirs()}
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
    }
    @Test fun seasonsRecoverFromErrorsAndEpisodeSearchKeepsBothNumbers(){
        lateinit var model:WatchModel
        ui.runOnIdle{model=ViewModelProvider(ui.activity)[WatchModel::class.java]}
        ui.waitUntil(10000){model.state.value.config.serverId=="episodes-ui"}
        ui.runOnIdle{model.metadata(Metadata("tmdb:tv:8844","Тихая орбита",null,"История экипажа исследовательской станции.",kind="tv"),cache=false)}
        ui.onNodeWithText("Выбрать сезон").performScrollTo().performClick()
        ui.waitUntil(10000){ui.onAllNodesWithText("Сезон 2").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("Сезон 2").performScrollTo();shot("seasons")
        ui.onNodeWithText("Сезон 1").performScrollTo().performClick()
        ui.waitUntil(10000){ui.onAllNodesWithTag("operation-message").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("Выбрать сезон").performScrollTo().assertIsEnabled().performClick()
        ui.onNodeWithText("Сезон 2").performScrollTo().performClick()
        ui.waitUntil(10000){ui.onAllNodesWithText("Возвращение домой").fetchSemanticsNodes().isNotEmpty()}
        val seasonAction=ui.onNodeWithText("Выбрать сезон").getUnclippedBoundsInRoot()
        val seasonBadge=ui.onNodeWithText("Сезон 2").getUnclippedBoundsInRoot()
        Assert.assertTrue("Season badge is separated from the action",seasonBadge.top-seasonAction.bottom>=15.dp)
        ui.onNodeWithText("Возвращение домой").performScrollTo().assertIsDisplayed();shot("episodes")
        val row=ui.onNodeWithText("Возвращение домой").fetchSemanticsNode().boundsInRoot
        val action=ui.onNodeWithText("Выбрать раздачу").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("Episode stays above the bottom action",row.bottom<=action.top)
        ui.onNodeWithText("Возвращение домой").performClick()
        ui.waitUntil(10000){model.state.value.page=="releases"}
        Assert.assertEquals(2,model.state.value.releaseSeason)
        Assert.assertEquals(3,model.state.value.releaseEpisode)
        Assert.assertEquals("tmdb:tv:8844",model.state.value.selectedMetadata?.id)
    }
}
