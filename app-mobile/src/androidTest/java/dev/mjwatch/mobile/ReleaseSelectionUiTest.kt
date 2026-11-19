package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File

class ReleaseSelectionUiTest {
    private val args get()=InstrumentationRegistry.getArguments()
    private val config get()=Config(connectionMode="managed",managedUrl="https://127.0.0.1:1",serverId="release-style",userId="viewer",demo=true,theme=args.getString("theme") ?: "dark")
    private val releases=listOf(
        Release("fixture:series-a","Тихая орбита S01-S02 1080p RUS LostFilm","Источник A",seeds=42),
        Release("fixture:series-b","Тихая орбита S01 2160p RUS NewStudio","Источник B",seeds=0),
        Release("fixture:series-c","Тихая орбита S02 720p Original","Источник A",seeds=null))
    private val files=listOf(TorrentFile(29,"Сезон 2/Orbit.S02E01.mkv",2_400_000_000),TorrentFile(17,"Сезон 1/Orbit.S01E02.mkv",1_800_000_000),TorrentFile(3,"Сезон 1/Orbit.S01E01.mkv",1_800_000_000),TorrentFile(10,"Сезон 1/Orbit.S01E10.mkv",1_900_000_000),TorrentFile(99,"sample.mp4",120_000_000),TorrentFile(100,"readme.txt",1024))
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        c.getSharedPreferences("playback-preferences",0).edit().clear().commit()
        Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("home")
        val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
        try {
            db.syncDao().clearEntities(config.scope);db.syncDao().clearOutbox(config.scope)
            db.syncDao().put(SyncCursor(config.scope,"fixture",1))
            for((index,completed) in listOf(3 to true,17 to false)) {
                val f=files.single{it.serverFileId==index};val p=Progress("fixture:$index",releases.first().id,index,f.path,if(completed)2_400_000 else 840_000,2_400_000,1,completed)
                db.syncDao().put(SyncEntity(config.scope,"progress",p.key,p.syncJson().toString(),1))
            }
        } finally {db.close()}
    }}
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private fun model():WatchModel {
        lateinit var model:WatchModel
        ui.runOnIdle{model=ViewModelProvider(ui.activity)[WatchModel::class.java]}
        ui.waitUntil(10000){model.state.value.config.profileKey==config.profileKey&&model.scopedLibrary.value.positions.size==2}
        val repository=object:ReleaseRepository {override suspend fun check()="fixture";override suspend fun search(query:String)=SearchResult(releases)}
        val gateway=object:StreamGateway {
            override suspend fun check()="fixture"
            override suspend fun add(release:Release)=TorrentHandle("fixture-handle",files,"08ada5a7a6183aae1e09d831df6748d566095a10")
            override suspend fun status(hash:String)=add(releases.first())
            override fun stream(handle:TorrentHandle,file:TorrentFile)=StreamRequest("asset:///demo.mp4")
        }
        ui.runOnIdle {
            WatchModel::class.java.getDeclaredField("cachedConfig").apply{isAccessible=true}.set(model,config)
            WatchModel::class.java.getDeclaredField("cachedRepositories").apply{isAccessible=true}.set(model,repository to gateway)
        }
        return model
    }
    private fun shot(name:String){
        val phase=args.getString("releaseReview") ?: return
        ui.waitForIdle();Thread.sleep(350);ui.waitForIdle()
        val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"release-review/$phase").apply{mkdirs()}
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
    }
    private fun awaitSelectedFileVisible(){
        ui.waitUntil(5000){
            val row=ui.onNodeWithTag("file-17").getUnclippedBoundsInRoot()
            val viewport=ui.onNodeWithTag("file-list").getUnclippedBoundsInRoot()
            row.top>=viewport.top-1.dp&&row.bottom<=viewport.bottom+1.dp
        }
    }
    @Test fun captureReleaseSelection(){
        Assume.assumeTrue(args.getString("releaseReview")!=null)
        val model=model();ui.runOnIdle{model.search("орбита")}
        ui.waitUntil(10000){!model.state.value.busy&&model.state.value.results.size==3}
        ui.runOnIdle{model.page("releases")};shot("releases")
        ui.onNodeWithText("Фильтры").performClick();shot("filters")
        ui.onNodeWithTag("release-filters").performScrollToNode(hasText("Русский не указан"));shot("filters-bottom")
        ui.onNodeWithText("Показать результаты (3)").performClick()
        ui.runOnIdle{model.playRelease(releases.first(),null)}
        ui.waitUntil(10000){model.state.value.page=="files"};shot("episodes")
        ui.onNodeWithText("Сезон 1").performClick()
        ui.onNodeWithTag("file-17").performScrollTo().performClick();awaitSelectedFileVisible();shot("episode-selected")
    }
    @Test fun filtersKeepAllSixChoicesAndResetTogether(){
        val model=model();ui.runOnIdle{model.search("орбита")}
        ui.waitUntil(10000){!model.state.value.busy&&model.state.value.results.size==3}
        ui.runOnIdle{model.page("releases")}
        ui.onNodeWithText("Фильтры").performClick()
        for(label in listOf("1080p","Сезон 2","LostFilm","Источник A","Есть сиды","Русский (название)")) {
            ui.onNodeWithTag("release-filters").performScrollToNode(hasText(label))
            ui.onNode(hasText(label) and hasAnyAncestor(hasTestTag("release-filters"))).performClick()
        }
        ui.onNodeWithText("Показать результаты (1)").assertIsDisplayed().performClick()
        ui.onNodeWithText("Фильтры · 6").assertExists()
        ui.onNodeWithText(releases.first().title).assertExists()
        ui.onNodeWithText(releases[1].title).assertDoesNotExist()
        ui.onNodeWithText("Фильтры · 6").performClick()
        ui.onNodeWithContentDescription("Закрыть фильтры").performClick()
        ui.onNodeWithText("Фильтры · 6").performClick()
        ui.onNode(hasText("Сбросить всё") and hasAnyAncestor(hasTestTag("release-filters-dialog"))).performClick()
        ui.onNodeWithText("Показать результаты (3)").performClick()
        ui.onNodeWithText("Фильтры").assertExists()
        ui.onNodeWithText(releases[1].title).assertExists()
    }
    @Test fun seriesKeepProgressSelectionAndAdditionalFiles(){
        val model=model();ui.runOnIdle{model.playRelease(releases.first(),null)}
        ui.waitUntil(10000){model.state.value.page=="files"}
        ui.onNodeWithTag("file-99").assertDoesNotExist()
        ui.onNodeWithText("Просмотрено").assertExists()
        ui.onNodeWithTag("file-list").performScrollToNode(hasTestTag("file-17"))
        ui.onNodeWithText("Продолжить с 14:00").assertExists()
        ui.onNodeWithTag("file-17").performScrollTo().performClick().assertIsSelected()
        awaitSelectedFileVisible()
        ui.onNodeWithTag("file-continue").assertIsDisplayed().assertTextContains("14:00",substring=true)
        shot("episode-progress")
        ui.onNodeWithTag("file-list").performScrollToIndex(0)
        ui.onNodeWithText("Сезон 2").performClick()
        ui.onNodeWithTag("file-17").assertDoesNotExist()
        ui.onNodeWithTag("file-continue").assertDoesNotExist()
        ui.onNodeWithTag("file-list").performScrollToNode(hasTestTag("file-29"))
        ui.onNodeWithTag("file-29").assertExists()
        ui.onNodeWithTag("file-list").performScrollToIndex(0)
        ui.onNodeWithText("Все сезоны").performClick()
        ui.onNodeWithText("Дополнительные файлы").performClick()
        ui.onNodeWithText("Все файлы и sample").performScrollTo().performClick()
        ui.onNodeWithTag("file-list").performScrollToNode(hasTestTag("file-99"))
        ui.onNodeWithTag("file-99").assertIsDisplayed()
        ui.onNodeWithTag("file-list").performScrollToNode(hasTestTag("file-100"))
        ui.onNodeWithTag("file-100").performClick().assertIsNotSelected()
    }

}
