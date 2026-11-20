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
import android.net.Uri
import android.view.KeyEvent
import dev.mjwatch.playback.PlaybackEpisode
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
    @Test fun continueSeriesStartsSavedEpisodeAtSavedPosition()=resumeSeries(false)
    @Test fun syncedSeriesResumesWithoutDeviceLocalReleaseLink()=resumeSeries(true)
    @Test fun unavailableLegacyReleaseShowsAnErrorOnTheDetailScreen() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {model.scopedLibrary.value.positions.isNotEmpty()}
        model.secrets.put("release:${config.scope}:${release.id}","")
        compose.onNodeWithText(metadata.title).performClick()
        compose.waitUntil(10000) {model.state.value.page=="detail"}
        compose.runOnIdle {model.resume(saved.copy(key="legacy-key"))}
        compose.waitUntil(10000) {!model.state.value.busy&&model.state.value.message.isNotBlank()}
        compose.waitUntil(5000) {runCatching {compose.onNodeWithText("Сохранённая раздача недоступна. Выберите другую.").assertIsDisplayed();true}.getOrDefault(false)}
        compose.onNodeWithTag("detail-sources").assertIsDisplayed()
    }
    @Test fun completedEpisodeSurvivesBackOnPhone()=completeEpisode(mobile=true)
    @Test fun completedEpisodeSurvivesNextAndImmediateBackOnTv()=completeEpisode(mobile=false)

    @Test fun earlierServerAckKeepsCompletedLocalEpisode()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.databaseBuilder(context,WatchDatabase::class.java,"watch.db")
            .addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
        try {
            val dao=db.syncDao()
            val engine=SyncEngine(ManagedApi(config,SecretStore(context)),dao)
            engine.enqueue("progress",saved.key,saved.syncJson(),playSession="play",sequence=1)
            val completed=saved.copy(positionMs=12000,completed=true)
            engine.enqueue("progress",saved.key,completed.syncJson(),playSession="play",sequence=2)
            val first=dao.pending(config.scope).first {it.entityKey==saved.key}
            val server=SyncEntity(config.scope,"progress",saved.key,saved.syncJson().toString(),2)
            dao.acknowledged(first,server)
            Assert.assertEquals(completed,dao.entity(config.scope,"progress",saved.key)?.progress())
        } finally {db.close()}
    }

    private fun completeEpisode(mobile:Boolean) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val clip=context.cacheDir.resolve("completion-test.mp4")
        context.assets.open("demo.mp4").use {input->clip.outputStream().use {input.copyTo(it)}}
        val episodes=files.map {PlaybackEpisode(Uri.fromFile(clip).toString(),it.path,it.serverFileId,contentKey(hash,it.path,it.size))}
        val initial=episodes.first()
        lateinit var model:WatchModel
        compose.runOnIdle {
            val activity=compose.activity
            model=ViewModelProvider(activity)[WatchModel::class.java]
            activity.startActivity(PlaybackActivity.offlineIntent(activity,initial,metadata.title,release.id,mobile,episodes))
        }
        var playback:PlaybackActivity?=null
        compose.waitUntil(15000) {
            instrumentation.runOnMainSync {playback=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PlaybackActivity>().firstOrNull {it.firstFrameSeen}}
            playback!=null
        }
        val activity=playback!!
        val session=PlaybackActivity::class.java.getDeclaredField("playbackSession").apply {isAccessible=true}.get(activity)
        // Hold the writer until destruction to exercise quick exits even on fast devices.
        runBlocking {SyncEngine.mutex.lock()}
        try {
            instrumentation.runOnMainSync {
                val player=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}.get(activity) as Player
                Assert.assertTrue(player.duration>0)
                player.seekTo(player.duration)
                if(!mobile) {
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_NEXT))
                    Assert.assertEquals(episodes[1].path,activity.intent.getStringExtra("path"))
                }
                activity.onBackPressedDispatcher.onBackPressed()
            }
            compose.waitUntil(10000) {activity.isDestroyed}
        } finally {SyncEngine.mutex.unlock()}
        compose.waitUntil(10000) {
            val library=model.scopedLibrary.value
            val entry=library.titles.firstOrNull {release.id in it.releaseIds}
            val progress=entry?.progress(library.positions)
            progress?.key==initial.contentKey && progress.completed
        }
        val db=Room.databaseBuilder(context,WatchDatabase::class.java,"watch.db")
            .addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
        try {
            val writes=runBlocking {db.syncDao().pending(config.scope)}.filter {it.entityKey==initial.contentKey}
            Assert.assertTrue(writes.isNotEmpty())
            Assert.assertTrue(writes.all {it.playSession==session})
        } finally {db.close();clip.delete()}
    }
    private fun resumeSeries(withoutLocalLink:Boolean) {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {model.scopedLibrary.value.positions.isNotEmpty()}
        // Supply local files/MP4 at the network boundary; exercise the real resume and player code.
        val gateway=object:StreamGateway {
            override suspend fun check()="fixture"
            override suspend fun add(release:Release)=TorrentHandle("fixture-handle",files,hash)
            override suspend fun status(hash:String)=TorrentHandle("fixture-handle",files,this@SeriesResumeUiTest.hash)
            override fun stream(handle:TorrentHandle,file:TorrentFile)=StreamRequest("asset:///demo.mp4")
        }
        compose.runOnIdle {
            WatchModel::class.java.getDeclaredField("cachedConfig").apply {isAccessible=true}.set(model,config)
            WatchModel::class.java.getDeclaredField("cachedRepositories").apply {isAccessible=true}.set(model,DemoReleases to gateway)
        }
        if(withoutLocalLink)model.secrets.put("release:${config.scope}:${release.id}","")
        compose.onNodeWithText(metadata.title).performClick()
        compose.waitUntil(10000) {model.state.value.page=="detail"}
        compose.onNodeWithTag("detail-resume").performClick()
        val i=InstrumentationRegistry.getInstrumentation()
        var player:PlaybackActivity?=null
        compose.waitUntil(15000) {
            i.runOnMainSync {player=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull {it.firstFrameSeen}}
            player!=null
        }
        i.runOnMainSync {
            val a=player!!
            Assert.assertEquals(saved.path,a.intent.getStringExtra("path"))
            val exo=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}.get(a) as Player
            Assert.assertTrue("Saved position: ${exo.currentPosition}",exo.currentPosition>=saved.positionMs)
            a.finish()
        }
    }
}
