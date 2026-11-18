package dev.mjwatch.tv

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
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
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @Test fun extensionlessMatroskaPlaysInTvApk()=verifyMatroska()
    private fun verifyMatroska() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        Assert.assertTrue("This regression must run on Android TV",context.packageManager.hasSystemFeature("android.software.leanback"))
        Assert.assertTrue(TorrentFile(1,"Show.S01E01.MKV",1).video)
        // A real H.264/AAC Matroska container, deliberately without a URI extension.
        context.startActivity(Intent(context,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("mobile",false).putExtra("uri","asset:///matroska-fixture")
            .putExtra("title","MKV regression").putExtra("path","Show.S01E01.MKV").putExtra("fileId",1))
        var activity:PlaybackActivity?=null
        try {
            ui.waitUntil(25000) {instrumentation.runOnMainSync {
                activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PlaybackActivity>().firstOrNull {it.firstFrameSeen}
            };activity!=null}
            instrumentation.runOnMainSync {
                val player=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}.get(activity) as Player
                Assert.assertNull(player.playerError)
                Assert.assertTrue(player.duration>=11000)
                Assert.assertTrue(player.currentTracks.groups.any {it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO})
                Assert.assertTrue(player.currentTracks.groups.any {it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO})
                Assert.assertTrue(player.isCurrentMediaItemSeekable)
                player.pause();player.seekTo(5000)
            }
            ui.waitUntil(5000) {
                var position=0L
                instrumentation.runOnMainSync {position=(PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}.get(activity) as Player).currentPosition}
                position in 4900..5100
            }
        } finally {instrumentation.runOnMainSync {activity?.finish()}}
    }
}
