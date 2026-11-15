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
    val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2).build()
    try {db.dao().delete(item.id);db.dao().cacheMetadata("home",listOf(item),System.currentTimeMillis());db.dao().cacheMetadata("search:${item.title}",listOf(item),System.currentTimeMillis())} finally {db.close()}
    Settings(c).save(Config(demo=true,metadataProvider="tmdb"));Settings(c).destination("home")
 }}
 @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
 @Test fun cachedMetadataDetailBookmarksAndReleaseAssociation() {
    lateinit var model:WatchModel
    compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
    compose.waitUntil(10000) {model.state.value.catalog.any {it.id==item.id}}
    compose.onNodeWithTag("home-list").performScrollToNode(hasText("О произведении"))
    compose.onNodeWithText("О произведении").performClick()
    compose.onNodeWithText(item.overview!!).assertExists()
    compose.onNodeWithText("В «Буду смотреть»").performScrollTo().performClick()
    compose.waitUntil(5000) {model.library.value.any {it.id==item.id&&it.bookmarked}}
    compose.onNodeWithText("Выбрать раздачу").performScrollTo().performClick()
    compose.waitUntil(10000) {!model.state.value.busy&&model.state.value.results.isNotEmpty()}
    compose.onNodeWithTag("release-list").performScrollToNode(hasText(DemoReleases.items.first().title))
    compose.onNodeWithText(DemoReleases.items.first().title).performClick()
    compose.onNodeWithTag("release-list").performScrollToNode(hasText("Продолжить с выбранной раздачей"))
    compose.onNodeWithText("Продолжить с выбранной раздачей").performClick()
    compose.waitUntil(10000) {model.state.value.page=="files"}
    compose.waitUntil(5000) {model.library.value.any {it.id==DemoReleases.items.first().id&&it.metadataId==item.id}}
    Assert.assertEquals(3,model.state.value.handle!!.files.single {it.path.endsWith("01.mp4")}.serverFileId)
 }
}
