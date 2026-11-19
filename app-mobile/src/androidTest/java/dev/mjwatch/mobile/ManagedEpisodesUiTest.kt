package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        val grid=ui.onNodeWithTag("detail-episode-grid")
        grid.performScrollToNode(hasText("Сезон 1"))
        ui.waitUntil(10000){ui.onAllNodesWithTag("operation-message").fetchSemanticsNodes().isNotEmpty()}
        grid.performScrollToNode(hasText("Повторить загрузку"))
        ui.onNodeWithText("Повторить загрузку").performClick()
        grid.performScrollToNode(hasText("Сезон 2"))
        ui.onNodeWithText("Сезон 2").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithTag("episode-1").fetchSemanticsNodes().isNotEmpty()}
        grid.performScrollToNode(hasTestTag("episode-1"))
        val first=ui.onNodeWithTag("episode-1").fetchSemanticsNode().boundsInRoot
        val second=ui.onNodeWithTag("episode-2").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals("The first two episodes share a row",first.top,second.top,2f)
        Assert.assertTrue("The two cards have an actual gutter",first.right<second.left)
        grid.performScrollToNode(hasTestTag("episode-menu-3"))
        ui.onNodeWithTag("episode-menu-3").performClick()
        ui.onNodeWithText("Сезон 2 · Серия 3").assertIsDisplayed();shot("episode-menu")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        ui.onNodeWithTag("episode-3").assertIsDisplayed();shot("episodes")
        ui.onNodeWithTag("episode-menu-3").performClick()
        ui.onNodeWithTag("episode-sources").performClick()
        ui.waitUntil(10000){model.state.value.page=="releases"}
        Assert.assertEquals(2,model.state.value.releaseSeason)
        Assert.assertEquals(3,model.state.value.releaseEpisode)
        Assert.assertEquals("tmdb:tv:8844",model.state.value.selectedMetadata?.id)
        // The same episode number occurs in two seasons of one release. The file picker
        // must preselect only S02E03, including when preparing a download.
        val pack = Release("fixture:two-seasons","Тихая орбита S01-S02","Fixture")
        val files = listOf(TorrentFile(13,"Show.S01E03.mkv",1000),TorrentFile(23,"Show.S02E03.mkv",1000))
        val handle = TorrentHandle("fixture-pack",files)
        val gateway = object : StreamGateway {
            override suspend fun check() = "fixture"
            override suspend fun add(release:Release) = handle
            override suspend fun status(hash:String) = handle
            override fun stream(handle:TorrentHandle,file:TorrentFile) = StreamRequest("asset:///demo.mp4")
        }
        ui.waitUntil(10000){!model.state.value.busy}
        ui.runOnIdle {
            WatchModel::class.java.getDeclaredField("cachedConfig").apply { isAccessible = true }.set(model,model.state.value.config)
            WatchModel::class.java.getDeclaredField("cachedRepositories").apply { isAccessible = true }.set(model,DemoReleases to gateway)
            model.playRelease(pack,model.state.value.releaseSeason,download=true)
        }
        ui.waitUntil(10000){model.state.value.page=="files"}
        ui.onNodeWithTag("file-list").performScrollToNode(hasTestTag("file-23"))
        ui.onNodeWithTag("file-23").assertIsSelected()
        ui.onNodeWithTag("file-13").assertDoesNotExist()

    }
}
