package dev.mjwatch.mobile
import android.app.PictureInPictureParams
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class PipTest {
 @Test fun pipKeepsPlaybackAndClosesCleanly()=runBlocking {
    Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("pip")=="true")
    val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
    Assume.assumeTrue(c.packageManager.hasSystemFeature("android.software.picture_in_picture"))
    Settings(c).save(Config(demo=true))
    c.startActivity(Intent(c,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("uri","asset:///demo.mp4").putExtra("releaseId","pip:fixture").putExtra("fileId",3).putExtra("path","pip.mp4"))
    var activity:PlaybackActivity?=null
    withTimeout(15000) {while(activity==null) {i.runOnMainSync {activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull {it.firstFrameSeen}};delay(100)}}
    i.runOnMainSync {Assert.assertTrue(activity!!.enterPictureInPictureMode(PictureInPictureParams.Builder().build()))}
    delay(500)
    i.runOnMainSync {
        Assert.assertTrue(activity!!.isInPictureInPictureMode)
        val f=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}
        Assert.assertTrue((f.get(activity) as androidx.media3.common.Player).isPlaying)
        activity!!.finish()
    }
    delay(500)
    Assert.assertTrue(activity!!.isFinishing)
 }
}
