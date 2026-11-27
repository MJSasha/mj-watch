package dev.mjwatch.tv

import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.runBlocking
import org.junit.*

class MatroskaPlaybackTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {
        override fun before()=runBlocking {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
            Settings(context).handleLegacyHistory();Settings(context).save(Config(demo=true));Settings(context).destination("home")
        }
    }
    @get:Rule(order=1) val app=ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()

    private fun awaitPlayback(timeout:Long,message:String,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        do {
            if(condition())return
            Thread.sleep(50)
        }while(SystemClock.elapsedRealtime()<deadline)
        Assert.fail(message)
    }

    @Test fun extensionlessMatroskaPlaysInTvApk()=verifyMatroska()
    @Test fun hevcMainMatroskaRendersAndSeeks()=verifyMatroska("hevc-main-1080-fixture",true)
    @Test fun hevcMain10UhdFormatIsParsedWithoutAssumingDeviceDecoderSupport() {
        var prepared:ExoPlayer?=null
        lateinit var player:ExoPlayer
        try {
            app.scenario.onActivity {activity->
                player=ExoPlayer.Builder(activity).build();prepared=player
                player.trackSelectionParameters=player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO,true)
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO,true).build()
                player.setMediaItem(androidx.media3.common.MediaItem.Builder().setUri("asset:///hevc-main10-4k-fixture").setMimeType("video/x-matroska").build())
                player.prepare()
            }
            awaitPlayback(10000,"HEVC Main10 track was not parsed") {
                var parsed=false
                instrumentation.runOnMainSync {parsed=player.currentTracks.groups.any {it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO}}
                parsed
            }
            app.scenario.onActivity {
                val track=player.currentTracks.groups.first {it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO}.getTrackFormat(0)
                Assert.assertEquals("video/hevc",track.sampleMimeType)
                Assert.assertEquals(3840,track.width);Assert.assertEquals(2160,track.height)
                Assert.assertEquals("hvc1.2.4.L150.90",track.codecs)
                Assert.assertFalse(player.currentTracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_VIDEO))
                Assert.assertNull(player.playerError)
            }
        } finally {instrumentation.runOnMainSync {prepared?.release()}}
    }
    private fun verifyMatroska(asset:String="matroska-fixture",hevc:Boolean=false) {
        val context=instrumentation.targetContext
        Assert.assertTrue("This regression must run on Android TV",context.packageManager.hasSystemFeature("android.software.leanback"))
        Assert.assertTrue(TorrentFile(1,"Show.S01E01.MKV",1).video)
        // Real Matroska fixtures, deliberately without a URI extension.
        context.startActivity(Intent(context,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("mobile",false).putExtra("uri","asset:///$asset")
            .putExtra("title","MKV regression").putExtra("path","Show.S01E01.MKV").putExtra("fileId",1))
        var activity:PlaybackActivity?=null
        try {
            awaitPlayback(25000,"No first frame for $asset") {
                var ready=false;var failure:PlaybackException?=null
                instrumentation.runOnMainSync {
                    activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()
                    ready=activity?.firstFrameSeen==true
                    failure=activity?.let {runCatching {PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}.get(it) as Player}.getOrNull()?.playerError}
                }
                if(failure!=null)throw AssertionError("$asset failed with ${failure!!.errorCode}",failure)
                ready
            }
            instrumentation.runOnMainSync {
                val player=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}.get(activity) as ExoPlayer
                Assert.assertEquals(if(hevc) "video/hevc" else "video/avc",player.videoFormat?.sampleMimeType)
                if(hevc) {
                    Assert.assertEquals(1920,player.videoFormat!!.width);Assert.assertEquals(1080,player.videoFormat!!.height)
                    Assert.assertTrue(player.videoFormat!!.codecs.orEmpty().startsWith("hvc1.1."))
                }
                Assert.assertNull(player.playerError)
                Assert.assertTrue(player.duration>=11000)
                Assert.assertTrue(player.currentTracks.groups.any {it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO})
                Assert.assertTrue(player.currentTracks.groups.any {it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO})
                Assert.assertTrue(player.isCurrentMediaItemSeekable)
                player.pause();player.seekTo(5000)
            }
            awaitPlayback(5000,"Seek did not reach 5000 ms for $asset") {
                var position=0L
                instrumentation.runOnMainSync {position=(PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}.get(activity) as Player).currentPosition}
                position in 4900..5100
            }
        } finally {instrumentation.runOnMainSync {activity?.finish()}}
    }
}
