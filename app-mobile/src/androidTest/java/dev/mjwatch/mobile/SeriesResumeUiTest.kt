package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeriesResumeUiTest {
    private val hash="08ada5a7a6183aae1e09d831df6748d566095a10"
    private val config=Config(connectionMode="managed",managedUrl="https://127.0.0.1:1",serverId="series-resume",userId="fixture",demo=true)
    private val metadata=Metadata("tmdb:tv:777777","Сериал для продолжения",null,null,kind="tv")
    private val release=Release("fixture:series","Series S01","Fixture",hash,"magnet:?xt=urn:btih:$hash")
    private val files=listOf(TorrentFile(3,"Series.S01E01.mp4",2400000),TorrentFile(17,"Series.S01E02.mp4",2400000))
    private val saved=Progress(contentKey(hash,files[1].path,files[1].size),release.id,17,files[1].path,4000,12000,2,false)
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("history")
        SecretStore(c).put("release:${config.scope}:${release.id}",release.json())
        val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
        try {
            db.syncDao().clearEntities(config.scope);db.syncDao().clearOutbox(config.scope)
            db.syncDao().put(SyncCursor(config.scope,"fixture",1))
            db.dao().put(CachedMetadata("${config.scope}:${metadata.id}",metadata.json(),System.currentTimeMillis()))
            for(e in listOf(LibraryEntry(metadata.id,metadata.title,"TMDB",1,metadataId=metadata.id),LibraryEntry(release.id,release.title,"Fixture",2,metadataId=metadata.id)))
                db.syncDao().put(SyncEntity(config.scope,"opening",e.id,e.syncJson().toString(),1))
            db.syncDao().put(SyncEntity(config.scope,"progress",saved.key,saved.syncJson().toString(),1))
        } finally {db.close()}
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    @Test fun savedSeriesCannotResumeWithoutConnectedTv()=checkTargetGate(false)
    @Test fun syncedSeriesWithoutLocalLinkCannotBypassTvGate()=checkTargetGate(true)
    @Test fun unavailableLegacyReleaseCannotBypassTvGate()=checkTargetGate(true,true)
    private fun checkTargetGate(withoutLocalLink:Boolean,legacy:Boolean=false) {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {model.scopedLibrary.value.positions.isNotEmpty()}
        if(withoutLocalLink)model.secrets.put("release:${config.scope}:${release.id}","")
        compose.onNodeWithText(metadata.title).performClick()
        compose.waitUntil(10000) {model.state.value.page=="detail"}
        compose.onNodeWithTag("detail-cast").assertExists()
        compose.onNodeWithTag("watch-on-tv").assertDoesNotExist()
        compose.runOnIdle {model.resume(if(legacy)saved.copy(key="legacy-key") else saved)}
        compose.runOnIdle {Assert.assertEquals("detail",model.state.value.page);Assert.assertFalse(model.state.value.busy);Assert.assertNull(model.state.value.handle);Assert.assertEquals(listOf(saved),model.progress.value)}
        Assert.assertFalse("No local player for saved history",PlaybackActivity.hasActivePlayback())
    }
}
