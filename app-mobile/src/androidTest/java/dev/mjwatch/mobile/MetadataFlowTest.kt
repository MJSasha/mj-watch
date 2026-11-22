@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.mobile
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
/** Synthetic cached TMDB-shaped data; no real metadata credentials or network requests. */
@RunWith(AndroidJUnit4::class)
class MetadataFlowTest {
 private val item=Metadata("tmdb:movie:999999999","Тихая орбита",null,"Вымышленный фильм для fixture-проверки.",year=2026)
 @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {override fun before()=runBlocking {
    val c=InstrumentationRegistry.getInstrumentation().targetContext
    val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
    try {db.dao().delete(item.id);db.dao().cacheMetadata("direct:home",listOf(item),System.currentTimeMillis(),"direct");db.dao().cacheMetadata("direct:search:${item.title}",listOf(item),System.currentTimeMillis(),"direct")} finally {db.close()}
    Settings(c).save(Config(demo=true,metadataProvider="tmdb"));Settings(c).destination("home")
 }}
 @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
 @Test fun portraitPosterFillsCardWidthWithoutLosingItsEdges() {
    val context=InstrumentationRegistry.getInstrumentation().targetContext
    val poster=File.createTempFile("fixture-poster",".png",context.cacheDir)
    try {
        val bitmap=Bitmap.createBitmap(120,180,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.MAGENTA)}
        val canvas=Canvas(bitmap)
        val paint=Paint().apply {color=Color.CYAN}
        canvas.drawRect(0f,0f,120f,12f,paint)
        paint.color=Color.GREEN
        canvas.drawRect(0f,168f,120f,180f,paint)
        poster.outputStream().use {Assert.assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        bitmap.recycle()
        val model=compose.runOnIdle {ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {model.state.value.catalog.any {it.id==item.id}}
        runBlocking {
            model.dao.cacheMetadata("direct:search:${item.title}",listOf(item.copy(poster=Uri.fromFile(poster).toString())),
                System.currentTimeMillis(),"direct")
        }
        compose.runOnIdle {model.search(item.title)}
        compose.waitUntil(10000) {model.state.value.metadata.any {it.id==item.id&&it.poster!=null}}
        val artwork=compose.onNodeWithTag("poster-${item.id}",useUnmergedTree=true).performScrollTo()
        compose.waitUntil(5000) {
            val image=artwork.captureToImage().asAndroidBitmap()
            val inset=image.width/20
            val y=image.height/4
            image.getPixel(inset,y)==Color.MAGENTA&&image.getPixel(image.width-1-inset,y)==Color.MAGENTA
        }
        val image=artwork.captureToImage().asAndroidBitmap()
        Assert.assertEquals("Top of the poster",Color.CYAN,image.getPixel(image.width/2,image.height/40))
        Assert.assertEquals("Bottom of the poster",Color.GREEN,image.getPixel(image.width/2,image.height-1-image.height/40))
    } finally {
        poster.delete()
    }
 }
 @Test fun searchShowsCinematicTileAndOpensTitle() {
    lateinit var model: WatchModel
    compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
    compose.waitUntil(10000) {model.state.value.catalog.any {it.id==item.id}}
    compose.runOnIdle {model.search(item.title)}
    compose.waitUntil(10000) {model.state.value.metadata.any {it.id==item.id}}
    val artwork=compose.onNodeWithTag("poster-${item.id}",useUnmergedTree=true).getUnclippedBoundsInRoot()
    val resultTitle=hasText(item.title) and !hasTestTag("search-field")
    val title=compose.onNode(resultTitle,useUnmergedTree=true).getUnclippedBoundsInRoot()
    val height=artwork.bottom-artwork.top
    Assert.assertEquals(2f/3f,(artwork.right-artwork.left).value/height.value,0.02f)
    Assert.assertTrue("Title should sit below the poster",title.top>=artwork.bottom)
    compose.onNode(resultTitle and hasClickAction()).performClick()
    compose.waitUntil(5000) {model.state.value.page=="detail"&&model.state.value.selectedMetadata?.id==item.id}
 }
 @Test fun dismissingSourcesKeepsTheSameDetailScreenAndTab() {
    lateinit var model: WatchModel
    compose.runOnIdle { model=ViewModelProvider(compose.activity)[WatchModel::class.java] }
    compose.waitUntil(10000) { model.state.value.configured }
    compose.runOnIdle { model.metadata(item) }
    compose.onNodeWithText("Похожие").performClick().assertIsSelected()
    val detailNode=compose.onNodeWithTag("detail-title").fetchSemanticsNode().id

    compose.runOnIdle { model.findReleases() }
    compose.waitUntil(10000) { !model.state.value.busy&&model.state.value.results.isNotEmpty() }
    compose.onNodeWithTag("release-list").assertIsDisplayed()
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    compose.waitUntil(5000) { model.state.value.page=="detail" }

    compose.onNodeWithText("Похожие").assertIsSelected()
    Assert.assertEquals(detailNode,compose.onNodeWithTag("detail-title").fetchSemanticsNode().id)
 }

 @Test fun cachedMetadataDetailBookmarksAndReleaseAssociation() {
    lateinit var model:WatchModel
    compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
    compose.waitUntil(10000) {model.state.value.catalog.any {it.id==item.id}}
    compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("hero-open"))
    compose.onNodeWithTag("hero-open").performClick()
    compose.onNodeWithText(item.overview!!).assertExists()
    compose.onNodeWithContentDescription("В «Буду смотреть»").performClick()
    compose.waitUntil(5000) {model.library.value.any {it.id==item.id&&it.bookmarked}}
    compose.onNodeWithText("Похожие").performClick()
    compose.onNodeWithText("Раздачи").performClick()
    val releases = compose.onNodeWithTag("release-list")
    val expected = hasText(DemoReleases.items.first().title)
    compose.waitUntil(10000) {
        try {
            releases.performScrollToNode(expected)
            true
        } catch (_: AssertionError) {
            false
        }
    }
    compose.onNodeWithText(DemoReleases.items.first().title).performClick()
    compose.onNodeWithTag("source-continue").assertIsDisplayed()
    compose.runOnIdle { Assert.assertEquals("detail", model.state.value.page) }
    compose.runOnIdle {Assert.assertTrue(model.library.value.any {it.id==item.id&&it.bookmarked});Assert.assertFalse(model.library.value.any {it.id==DemoReleases.items.first().id});Assert.assertEquals(item.id,model.state.value.selectedMetadata?.id)}
 }
 @Test fun episodeGridAndActionMenu() {
    lateinit var model: WatchModel
    compose.runOnIdle { model = ViewModelProvider(compose.activity)[WatchModel::class.java] }
    compose.waitUntil(10000) { model.state.value.catalog.any { it.id == item.id } }
    val release = DemoReleases.items.single { it.id == "demo:series" }
    compose.runOnIdle {
        model.open(release, metadata = Metadata(release.id, "Тестовый сериал", null, null, kind = "tv"))
    }
    val grid = compose.onNodeWithTag("detail-episode-grid")
    grid.performScrollToNode(hasTestTag("season-selector"))
    compose.onNodeWithTag("season-selector").assertIsDisplayed()
    grid.performScrollToNode(hasTestTag("episode-1"))
    val banner = compose.onNodeWithTag("episode-banner-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
    val badge = compose.onNodeWithContentDescription("Серия 1", useUnmergedTree = true).getUnclippedBoundsInRoot()
    Assert.assertTrue(badge.left > banner.left && badge.top > banner.top)
    Assert.assertTrue(badge.right < (banner.left + banner.right) / 2)
    val first = compose.onNodeWithTag("episode-1").getUnclippedBoundsInRoot()
    val second = compose.onNodeWithTag("episode-2").getUnclippedBoundsInRoot()
    Assert.assertEquals(first.top.value, second.top.value, 1f)
    Assert.assertTrue(first.right <= second.left)
    compose.onNodeWithTag("episode-menu-2").performClick()
    compose.onNodeWithText("Сезон 1 · Серия 2").assertIsDisplayed()
    compose.onNodeWithText("Скачать").assertExists()
    androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    compose.onNodeWithTag("episode-2").assertIsDisplayed()
 }
}
