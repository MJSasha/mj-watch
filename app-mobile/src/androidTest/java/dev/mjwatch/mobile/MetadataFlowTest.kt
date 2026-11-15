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
    compose.onNodeWithTag("detail-sources").performClick()
    compose.waitUntil(10000) {!model.state.value.busy&&model.state.value.results.isNotEmpty()}
    compose.onNodeWithTag("release-list").performScrollToNode(hasText(DemoReleases.items.first().title))
    compose.onNodeWithText(DemoReleases.items.first().title).performClick()
    compose.onNodeWithTag("source-continue").assertIsDisplayed()
    compose.onNodeWithTag("source-continue").performClick()
    compose.waitUntil(10000) {model.state.value.page=="files"}
    compose.runOnIdle {Assert.assertTrue(model.library.value.any {it.id==item.id&&it.bookmarked});Assert.assertFalse("Direct opening does not create history",model.library.value.any {it.id==DemoReleases.items.first().id});Assert.assertEquals(item.id,model.state.value.selectedMetadata?.id)}
    Assert.assertEquals(3,model.state.value.handle!!.files.single {it.path.endsWith("01.mp4")}.serverFileId)
 }
}
