@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvCarouselUiTest {
    private val config=Config(connectionMode="managed",managedUrl="https://127.0.0.1:1",serverId="carousel-test",userId="fixture",metadataProvider="tmdb",demo=false,theme="dark")
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {
        override fun before()=runBlocking {
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
            Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("home")
            val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
            try {
                db.dao().cacheMetadata("${config.scope}:home",(1..20).map {Metadata("tmdb:movie:${8000+it}","Карусель $it",null,null,year=2026)},System.currentTimeMillis(),config.scope)
            } finally {db.close()}
        }
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun card(index:Int)=compose.onNode(hasText("Карусель $index") and hasClickAction())

    @Test fun remoteScrollAndBackKeepTheFocusedCardVisible() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {model.state.value.catalog.size==20}
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("Карусель 1") and hasClickAction())
        card(1).performSemanticsAction(SemanticsActions.RequestFocus)
        val firstLeft=card(1).fetchSemanticsNode().boundsInRoot.left
        for(index in 2..12) {
            card(index-1).performKeyInput {pressKey(Key.DirectionRight)}
            card(index).assertIsFocused().assertIsDisplayed()
            compose.waitForIdle()
        }
        val before=card(12).fetchSemanticsNode().boundsInRoot
        // After the row starts scrolling, the focused item stays on its leading line.
        Assert.assertEquals(firstLeft,before.left,2f)
        card(12).performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(5000) {model.state.value.page=="detail"}
        compose.runOnIdle {model.back()}
        compose.waitUntil(5000) {runCatching {card(12).assertIsFocused();true}.getOrDefault(false)}
        card(12).assertIsDisplayed()
        Assert.assertEquals(before.left,card(12).fetchSemanticsNode().boundsInRoot.left,2f)
        card(12).performKeyInput {pressKey(Key.DirectionUp)}
        compose.onNodeWithTag("hero-open").assertIsFocused()
        compose.onNodeWithTag("hero-open").performKeyInput {pressKey(Key.DirectionDown)}
        card(12).assertIsFocused().assertIsDisplayed()
    }
}
