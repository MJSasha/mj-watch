package dev.mjwatch.mobile
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
    compose.waitUntil(10000) { compose.onAllNodesWithTag("release-list").fetchSemanticsNodes().isNotEmpty() }
    compose.onNodeWithTag("release-list").performScrollToNode(hasText(DemoReleases.items.first().title))
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
    compose.waitUntil(10000) { compose.onAllNodesWithTag("season-selector").fetchSemanticsNodes().isNotEmpty() }
    val grid = compose.onNodeWithTag("detail-episode-grid")
    grid.performScrollToNode(hasTestTag("episode-1"))
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
