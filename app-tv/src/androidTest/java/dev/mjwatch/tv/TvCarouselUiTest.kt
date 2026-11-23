@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import java.io.File
import kotlin.math.roundToInt
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
    @get:Rule(order=0) val testName=org.junit.rules.TestName()
    @get:Rule(order=1) val seed=object:org.junit.rules.ExternalResource() {
        override fun before()=runBlocking {
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
            Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("home")
            val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
            try {
                db.syncDao().clearEntities(config.scope)
                db.syncDao().clearOutbox(config.scope)
                if(testName.methodName=="verticalShelfNavigationCentresRowsAndRestoresCards") {
                    for(index in 1..2) {
                        val entry=LibraryEntry("shelf:$index","Продолжить $index","fixture",1,metadataId="tmdb:movie:${8000+index}")
                        db.syncDao().put(SyncEntity(config.scope,"opening",entry.id,entry.syncJson().toString(),1))
                        val saved=Progress("shelf-progress:$index",entry.id,index,if(index==2)"Series.S02E04.mp4" else "Movie$index.mp4",60000,300000,1,false)
                        db.syncDao().put(SyncEntity(config.scope,"progress",saved.key,saved.syncJson().toString(),1))
                    }
                }
                db.dao().cacheMetadata("${config.scope}:home",(1..20).map {Metadata("tmdb:movie:${8000+it}","Карусель $it","android.resource://dev.mjwatch.tv/drawable/demo_orbit",null,year=2026,backdrop="android.resource://dev.mjwatch.tv/drawable/demo_orbit")},System.currentTimeMillis(),config.scope)
            } finally {db.close()}
        }
    }
    @get:Rule(order=2) val compose=createAndroidComposeRule<MainActivity>()
    private fun card(index:Int)=compose.onNode(hasText("Карусель $index") and hasClickAction())

    private fun capture(name:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"tv-polish/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun verticalShelfNavigationCentresRowsAndRestoresCards() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {model.state.value.catalog.size==20&&model.scopedLibrary.value.titles.size==2}
        val wide=compose.onNodeWithTag("library-shelf:1")
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("library-shelf:1"))
        wide.performSemanticsAction(SemanticsActions.RequestFocus)
        wide.assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("hero-open").assertDoesNotExist()
        val upper=compose.onNodeWithTag("home-shelf-continue").fetchSemanticsNode().boundsInRoot
        val lower=compose.onNodeWithTag("home-shelf-catalog").fetchSemanticsNode().boundsInRoot
        val expectedGap=32f*compose.activity.resources.configuration.screenWidthDp/1280f*compose.activity.resources.displayMetrics.density
        Assert.assertEquals(expectedGap,lower.top-upper.bottom,2f)
        compose.onNodeWithText("С2 · Серия 4",substring=true).assertIsDisplayed()
        compose.onNodeWithText("OK",substring=true).assertDoesNotExist()
        compose.onNodeWithText("Каталог из кеша").assertDoesNotExist()
        capture("home-compact-shelves")
        wide.performKeyInput {pressKey(Key.DirectionDown)}
        val focused=compose.onNode(isFocused() and hasClickAction())
        focused.assert(hasText("Карусель",substring=true)).assertIsDisplayed()
        compose.waitForIdle()
        wide.assertIsNotDisplayed()
        val viewport=compose.onNodeWithTag("home-list").fetchSemanticsNode().boundsInRoot
        val centred=compose.onNodeWithTag("home-shelf-catalog").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(viewport.center.y,centred.center.y,3f)
        capture("home-centred-shelf")
        repeat(6) {compose.onNode(isFocused() and hasClickAction()).performKeyInput {pressKey(Key.DirectionRight)}}
        compose.onNode(isFocused() and hasClickAction()).performKeyInput {pressKey(Key.DirectionUp)}
        wide.assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("hero-open").assertDoesNotExist()
        compose.onNodeWithTag("hero-bookmark").assertDoesNotExist()
        capture("home-two-shelves")
        wide.performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(5000) {model.state.value.page=="detail"}
        val titleId="tmdb:movie:8001"
        compose.waitUntil(5000) {model.scopedLibrary.value.titles.any {it.entry.id==titleId}}
        compose.runOnIdle {model.back()}
        val reopened=compose.onNodeWithTag("library-$titleId")
        compose.waitUntil(5000) {runCatching {reopened.assertIsFocused();true}.getOrDefault(false)}
        reopened.assertIsDisplayed()
    }

    @Test fun remoteScrollAndBackKeepTheFocusedCardVisible() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {model.state.value.catalog.size==20}
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("Карусель 1") and hasClickAction())
        card(1).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("hero-open").assertDoesNotExist()
        compose.onNodeWithTag("hero-bookmark").assertDoesNotExist()
        for(index in 2..12) {
            card(index-1).performKeyInput {pressKey(Key.DirectionRight)}
            card(index).assertIsFocused().assertIsDisplayed()
            compose.waitForIdle()
        }
        val before=card(12).fetchSemanticsNode().boundsInRoot
        // Once scroll bounds allow it, the active card is centred on the shelf.
        val shelf=compose.onNodeWithTag("tv-carousel").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(shelf.center.x,before.center.x,2f)
        compose.onNodeWithTag("hero-open").assertDoesNotExist()
        compose.onNodeWithTag("hero-bookmark").assertDoesNotExist()
        val image=compose.onNodeWithTag("poster-tmdb:movie:8012",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(2f/3f,image.width/image.height,0.02f)
        val pixels=compose.onRoot().captureToImage().toPixelMap()
        val density=compose.activity.resources.displayMetrics.density
        val frame=pixels[image.center.x.roundToInt(),(image.top-5*density).roundToInt()].luminance()
        Assert.assertTrue("Focused poster frame must be bright",frame>.8f)
        capture("home-centred")
        card(12).performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(5000) {model.state.value.page=="detail"}
        compose.runOnIdle {model.back()}
        compose.waitUntil(5000) {runCatching {card(12).assertIsFocused();true}.getOrDefault(false)}
        card(12).assertIsDisplayed()
        Assert.assertEquals(before.left,card(12).fetchSemanticsNode().boundsInRoot.left,2f)
        card(12).performKeyInput {pressKey(Key.DirectionUp)}
        compose.onNode(isFocused()).assert(hasText("Для вас"))
        compose.onNode(isFocused()).performKeyInput {pressKey(Key.DirectionDown)}
        card(12).assertIsFocused().assertIsDisplayed()
        for(index in 13..20) {card(index-1).performKeyInput {pressKey(Key.DirectionRight)};card(index).assertIsFocused().assertIsDisplayed()}
        compose.onNodeWithTag("hero-open").assertDoesNotExist()
        compose.onNodeWithTag("hero-bookmark").assertDoesNotExist()
        capture("home-last-card")
    }
}
