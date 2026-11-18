@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.mobile

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.mjwatch.designsystem.ServerStatusOverview
import dev.mjwatch.designsystem.WatchTheme
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
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {override fun before()=runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("history")
        val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
        try {
            db.syncDao().clearEntities(config.scope);db.syncDao().clearOutbox(config.scope)
            db.syncDao().put(SyncCursor(config.scope,"fixture",1))
            val entry=LibraryEntry("fixture:series","Тест истории","fixture",1)
            db.syncDao().put(SyncEntity(config.scope,"opening",entry.id,entry.syncJson().toString(),1))
            val progress=Progress("fixture:progress",entry.id,7,"Show.S02E03.mkv",60000,300000,1,false)
            db.syncDao().put(SyncEntity(config.scope,"progress",progress.key,progress.syncJson().toString(),1))
        } finally {db.close()}
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun model():WatchModel {
        lateinit var m:WatchModel
        compose.runOnIdle {m=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {m.scopedLibrary.value.entries.size==1&&m.scopedLibrary.value.positions.size==1}
        return m
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val i=InstrumentationRegistry.getInstrumentation()
        val dir=File(i.targetContext.getExternalFilesDir(null),"november-issues").apply {mkdirs()}
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
    }
    @Test fun episodeAndHistoryDeletion() {
        val m=model()
        compose.onNodeWithText("Сезон 2 · Серия 3").assertIsDisplayed()
        capture("history-episode")
        compose.onNodeWithContentDescription("Действия с «Тест истории»").performClick()
        compose.onNodeWithText("Удалить").assertIsDisplayed()
        capture("history-actions")
        compose.onNodeWithText("Удалить").performClick()
        compose.waitUntil(10000) {m.scopedLibrary.value.entries.isEmpty()}
        compose.onNodeWithText("Пока пусто").assertExists()
    }
    @Test fun searchSpinnerCoversSlowReleaseRequests() {
        val m=model()
        val server=okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val body="""{"Results":[{"Title":"Sintel 720p","Tracker":"Fixture","Seeders":1,"MagnetUri":"magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"}],"jacred":true}"""
            repeat(2) {server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(body).setBodyDelay(3,java.util.concurrent.TimeUnit.SECONDS))}
            val url=server.url("/parser").toString()
            runBlocking {Settings(compose.activity).save(config.copy(demo=false,jackett=url,releaseApi="jacred",localHttp=true))}
            compose.waitUntil(10000) {m.state.value.config.jackett==url}
            compose.runOnIdle {m.search("Sintel")}
            compose.onNodeWithContentDescription("Поиск…").assertIsDisplayed()
            capture("search-loading")
            compose.waitUntil(15000) {!m.state.value.busy}
            compose.onNodeWithContentDescription("Поиск…").assertDoesNotExist()
            compose.onNodeWithText("Sintel 720p").assertExists()
            compose.runOnIdle {m.metadata(Metadata("tmdb:movie:1","Sintel",null,null));m.findReleases()}
            compose.onNodeWithContentDescription("Ищем раздачи…").assertIsDisplayed()
            capture("releases-loading")
            compose.waitUntil(15000) {!m.state.value.busy}
            compose.onNodeWithContentDescription("Ищем раздачи…").assertDoesNotExist()
            compose.onNodeWithText("Sintel 720p").assertExists()
        } finally {server.shutdown()}
    }
    @Test fun serverOverviewAdaptsToBothThemes() {
        val m=model()
        for(theme in listOf("dark","light")) {
            runBlocking {Settings(compose.activity).save(config.copy(theme=theme))}
            compose.waitUntil(10000) {m.state.value.config.theme==theme}
            compose.runOnIdle {m.page("server-overview")}
            compose.onNodeWithText("База данных").assertIsDisplayed()
            compose.onNodeWithText("Потоки").assertIsDisplayed()
            capture("server-status-$theme")
        }
    }

    @Test fun compactTelemetryShowsHealthStreamsAndRefresh() {
        var theme by mutableStateOf("dark")
        var refreshes=0
        val data=org.json.JSONObject().put("databaseHealthy",true).put("torrserverHealthy",true).put("activeStreams",2).put("version","0.1.3")
        compose.runOnUiThread {compose.activity.setContent {
            WatchTheme(theme) {Surface(Modifier.fillMaxSize()) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("Мой сервер",style=MaterialTheme.typography.headlineLarge)
                Box(Modifier.testTag("server-telemetry")) {ServerStatusOverview("https://watch.example:8443","admin",data,false,false,System.currentTimeMillis(),false){refreshes++}}
            }}}
        }}
        for(value in listOf("dark","light")) {
            compose.runOnIdle {theme=value}
            compose.onNodeWithText("Сервер доступен").assertIsDisplayed()
            compose.onNodeWithText("Работает").assertIsDisplayed()
            compose.onNodeWithText("В норме").assertIsDisplayed()
            compose.onNodeWithText("2").assertIsDisplayed()
            compose.onNodeWithContentDescription("Обновить статус").performClick()
            capture("server-telemetry-$value")
            val bitmap=compose.onNodeWithTag("server-telemetry").captureToImage().asAndroidBitmap()
            val file=File(compose.activity.getExternalFilesDir(null),"november-issues/server-strip-$value.png")
            file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
        compose.runOnIdle {Assert.assertEquals(2,refreshes)}
    }

}
