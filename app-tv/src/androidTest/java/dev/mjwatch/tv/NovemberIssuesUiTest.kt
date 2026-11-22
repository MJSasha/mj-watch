@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
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
class NovemberIssuesUiTest {
    private val config=Config(connectionMode="managed",managedUrl="https://127.0.0.1:1",serverId="november-issues",userId="fixture",demo=true,theme="dark")
    @get:Rule(order=0) val testName=org.junit.rules.TestName()
    @get:Rule(order=1) val seed=object:org.junit.rules.ExternalResource() {override fun before()=runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("history")
        val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
        try {
            db.syncDao().clearEntities(config.scope);db.syncDao().clearOutbox(config.scope)
            db.syncDao().put(SyncCursor(config.scope,"fixture",1))
            db.dao().cacheMetadata("${config.scope}:home",listOf(Metadata("tmdb:movie:777","Тест постера",null,null,year=2026)),System.currentTimeMillis(),config.scope)
            val entry=LibraryEntry("fixture:series","Тест истории","fixture",1)
            db.syncDao().put(SyncEntity(config.scope,"opening",entry.id,entry.syncJson().toString(),1))
            val progress=Progress("fixture:progress",entry.id,7,"Show.S02E03.mkv",60000,300000,1,false)
            db.syncDao().put(SyncEntity(config.scope,"progress",progress.key,progress.syncJson().toString(),1))
            // Seed both cards before launching: cross-instance Room invalidation registration is asynchronous.
            if(testName.methodName=="continuingFilmAndSeriesCardsHaveEqualHeight") {
                val film=LibraryEntry("fixture:film","Тест фильма","fixture",1)
                db.syncDao().put(SyncEntity(config.scope,"opening",film.id,film.syncJson().toString(),1))
                val position=Progress("fixture:film-progress",film.id,1,"Movie.mp4",60000,300000,1,false)
                db.syncDao().put(SyncEntity(config.scope,"progress",position.key,position.syncJson().toString(),1))
            }
        } finally {db.close()}
    }}
    @get:Rule(order=2) val compose=createAndroidComposeRule<MainActivity>()
    private fun model(expected:Int=1):WatchModel {
        lateinit var m:WatchModel
        compose.runOnIdle {m=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {m.scopedLibrary.value.entries.size==expected&&m.scopedLibrary.value.positions.size==expected}
        return m
    }
    @Test fun continuingFilmAndSeriesCardsHaveEqualHeight() {
        val m=model(expected=2)
        compose.runOnIdle {m.page("home")}
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("library-fixture:film"))
        val film=compose.onNodeWithTag("library-fixture:film").getUnclippedBoundsInRoot()
        val series=compose.onNodeWithTag("library-fixture:series").getUnclippedBoundsInRoot()
        val expectedHeight=176f*compose.activity.resources.configuration.screenWidthDp/1280f
        Assert.assertEquals(expectedHeight,film.height.value,1f)
        Assert.assertEquals(expectedHeight,series.height.value,1f)
        val artwork=compose.onNodeWithTag("library-poster-fixture:series",useUnmergedTree=true).getUnclippedBoundsInRoot()
        Assert.assertEquals(series.left.value,artwork.left.value,1f)
        Assert.assertEquals(series.height.value,artwork.height.value,1f)
        Assert.assertEquals(2f/3f,artwork.width.value/artwork.height.value,0.02f)
        Assert.assertTrue("Artwork shares the row with the caption",artwork.width < series.width/2f)
        capture("equal-continue-cards")
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val i=InstrumentationRegistry.getInstrumentation()
        val dir=File(i.targetContext.getExternalFilesDir(null),"november-issues").apply {mkdirs()}
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
    }
    @Test fun episodeAndHistoryDeletion() {
        val m=model()
        compose.onNodeWithText("С2 · Серия 3",substring=true).assertIsDisplayed()
        capture("history-episode")
        val card=compose.onNodeWithText("Тест истории")
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("input keyevent --longpress 23")
        compose.waitForIdle()
        Assert.assertEquals("history",m.state.value.page)
        Assert.assertEquals(1,m.scopedLibrary.value.entries.size)
        compose.onNodeWithText("Удалить").assertIsDisplayed()
        capture("history-actions")
        compose.waitUntil(5000) {runCatching {compose.onNodeWithText("Удалить").assertIsFocused();true}.getOrDefault(false)}
        compose.onNodeWithText("Удалить").assertIsFocused().performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(10000) {m.scopedLibrary.value.entries.isEmpty()}
        compose.onNodeWithText("Пока пусто").assertExists()
    }
    @Test fun posterFocusAndPressKeepTheirBoundsInBothThemes() {
        val m=model()
        for(theme in listOf("dark","light")) {
            runBlocking {Settings(compose.activity).save(config.copy(theme=theme,metadataProvider="tmdb",demo=false))}
            compose.waitUntil(10000) {m.state.value.config.theme==theme&&m.state.value.catalog.isNotEmpty()}
            compose.runOnIdle {m.page("home")}
            val poster=hasText("Тест постера") and hasClickAction()
            compose.onNodeWithTag("home-list").performScrollToNode(poster)
            val card=compose.onNode(poster)
            card.performSemanticsAction(SemanticsActions.RequestFocus)
            card.assertIsFocused()
            val bounds=card.fetchSemanticsNode().boundsInRoot
            card.performKeyInput {keyDown(Key.DirectionCenter)}
            Assert.assertEquals(bounds,card.fetchSemanticsNode().boundsInRoot)
            capture("poster-pressed-$theme")
            card.performKeyInput {keyUp(Key.DirectionCenter)}
            compose.waitUntil(10000) {m.state.value.page=="detail"}
            compose.runOnIdle {m.back()}
        }
    }

}
