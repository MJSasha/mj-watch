package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File

class OpenIssuesUiTest {
    private val config=Config(connectionMode="managed",managedUrl="https://127.0.0.1:1",serverId="open-issues",userId="fixture",demo=true,theme="dark")
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {override fun before()=runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("home")
        val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
        try {
            db.syncDao().clearEntities(config.scope);db.syncDao().clearOutbox(config.scope)
            db.syncDao().put(SyncCursor(config.scope,"fixture",1))
            for((id,title,path) in listOf(Triple("demo:film","Фильм","Movie.mkv"),Triple("demo:series","Сериал с длинным названием","Show.S02E03.mkv"))) {
                val entry=LibraryEntry(id,title,"Проверка",1)
                db.syncDao().put(SyncEntity(config.scope,"opening",id,entry.syncJson().toString(),1))
                val progress=Progress("fixture:$id",id,1,path,60000,300000,1,false)
                db.syncDao().put(SyncEntity(config.scope,"progress",progress.key,progress.syncJson().toString(),1))
            }
        } finally {db.close()}
    }}
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @Test fun cardsAlignAndServerShortcutOpensServerSettings() {
        lateinit var model:WatchModel
        ui.runOnIdle {model=ViewModelProvider(ui.activity)[WatchModel::class.java]}
        ui.waitUntil(10000) {model.scopedLibrary.value.entries.size==2}
        ui.onNodeWithTag("home-list").performScrollToNode(hasTestTag("library-demo:series"))
        val film=ui.onNodeWithTag("library-demo:film").getUnclippedBoundsInRoot()
        val series=ui.onNodeWithTag("library-demo:series").getUnclippedBoundsInRoot()
        Assert.assertEquals((film.bottom-film.top).value,(series.bottom-series.top).value,1f)
        capture("cards")
        ui.onNodeWithContentDescription("Действия с «Сериал с длинным названием»").performClick()
        ui.onNodeWithText("Удалить").assertIsDisplayed()
        ui.onNodeWithText("Отмена").assertIsDisplayed()
        capture("history-dialog")
        ui.onNodeWithText("Отмена").performClick()
        ui.onNodeWithContentDescription("Мой сервер · Демонстрация").performClick()
        ui.waitUntil(5000) {model.state.value.page=="settings-server"}
        capture("server-shortcut")
        ui.runOnIdle {model.page("managed-account")}
        ui.onNodeWithContentDescription("Синхронизировать").assertIsDisplayed()
        ui.onNodeWithText("Устройства").assertIsDisplayed()
        capture("account")
    }
    @Test fun serverIndicatorReflectsProbeAndOpensDirectSettings() {
        lateinit var model:WatchModel
        ui.runOnIdle {model=ViewModelProvider(ui.activity)[WatchModel::class.java]}
        for(healthy in listOf(true,false)) {
            val server=okhttp3.mockwebserver.MockWebServer()
            server.start()
            try {
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(if(healthy)200 else 503).setBody(if(healthy)"MatriX.145.2" else "unavailable"))
                val url=server.url("/").toString()
                runBlocking {Settings(ui.activity).save(Config(torrServer=url,localHttp=true,theme="dark"))}
                ui.waitUntil(10000) {model.state.value.config.torrServer==url}
                ui.runOnIdle {model.page("home")}
                val label="Мой сервер · "+if(healthy)"Сервер доступен" else "Нет связи с сервером"
                ui.waitUntil(10000) {ui.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithContentDescription(label).performClick()
                ui.waitUntil(5000) {model.state.value.page=="settings-server"}
            } finally {server.shutdown()}
        }
    }
    private fun capture(name:String) {
        ui.waitForIdle()
        val i=InstrumentationRegistry.getInstrumentation()
        val dir=File(i.targetContext.getExternalFilesDir(null),"open-issues/"+InstrumentationRegistry.getArguments().getString("evidenceDir","phone")).apply {mkdirs()}
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
    }
}
