package dev.mjwatch.mobile

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

@RunWith(AndroidJUnit4::class)
class CatalogSectionsUiTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        c.getSharedPreferences("playback-preferences",0).edit().clear().commit()
        val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
        try {
            for(section in CatalogSection.entries) {
                val item=Metadata("tmdb:${if(section==CatalogSection.SERIES)"tv" else "movie"}:${900000+section.ordinal}","Карточка: ${section.label}",null,"Описание ${section.label}",kind=if(section==CatalogSection.SERIES)"tv" else "movie",animation=section in listOf(CatalogSection.ANIME,CatalogSection.CARTOONS),originalLanguage=if(section==CatalogSection.ANIME)"ja" else "ru")
                val suffix=if(section==CatalogSection.FOR_YOU)"" else ":${section.key}"
                db.dao().cacheMetadata("direct:home$suffix",listOf(item),System.currentTimeMillis(),"direct")
            }
        }finally {db.close()}
        Settings(c).handleLegacyHistory();Settings(c).save(Config(demo=true,metadataProvider="tmdb"));Settings(c).destination("home")
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun shot(name:String) {
        compose.waitForIdle();compose.mainClock.advanceTimeBy(350);Thread.sleep(350)
        compose.waitForIdle();Thread.sleep(350)
        val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"issues-8-12").apply {mkdirs()}
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
    }
    @Test fun everyCategoryLoadsItsOwnCardsAndSurvivesBack() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000){model.state.value.catalog.isNotEmpty()}
        for(section in listOf(CatalogSection.SERIES,CatalogSection.ANIME,CatalogSection.CARTOONS,CatalogSection.MOVIES)) {
            compose.onNodeWithTag("home-list").performScrollToIndex(0)
            compose.onNode(hasText(section.label) and isSelectable()).performScrollTo().performClick()
            compose.waitUntil(10000){!model.state.value.catalogBusy&&model.state.value.catalog.singleOrNull()?.title=="Карточка: ${section.label}"}
            compose.onNodeWithTag("hero-open").performClick()
            compose.onNodeWithText("Описание ${section.label}").assertExists()
            compose.runOnIdle {model.back()}
            compose.onNode(hasText(section.label) and isSelectable()).assertIsSelected()
        }
        shot("catalog-movies")
    }
    @Test fun groupedFiltersShowCountAndResetEveryChoice() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java];model.search("тест")}
        compose.waitUntil(10000){!model.state.value.busy&&model.state.value.results.size==2}
        compose.runOnIdle {model.page("releases")}
        compose.onNodeWithText("Фильтры").performClick()
        compose.onNode(hasText("1080p") and hasAnyAncestor(hasTestTag("release-filters"))).performClick()
        compose.onNodeWithTag("release-filters").performScrollToNode(hasText("0 сидов"))
        compose.onNode(hasText("0 сидов") and hasAnyAncestor(hasTestTag("release-filters"))).performClick()
        shot("release-filters-dialog")
        compose.onNodeWithText("Показать результаты (1)").performClick()
        compose.onNodeWithText(DemoReleases.items.last().title).assertExists()
        compose.onNodeWithText(DemoReleases.items.first().title).assertDoesNotExist()
        compose.onNodeWithText("Фильтры · 2").assertExists()
        shot("release-filters-applied")
        compose.onNodeWithText("Сбросить всё").performClick()
        compose.onNodeWithText(DemoReleases.items.first().title).assertExists()
        compose.onNodeWithText(DemoReleases.items.last().title).assertExists()
    }
}
