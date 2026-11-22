@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.mjwatch.playback

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class BackwardSeekTest {
    @Test fun videoRendersAgainAfterSeekingBackward() {
        verify("demo.mp4","demo.mp4")
        verify("matroska-fixture.mkv","matroska-fixture")
    }

    private fun verify(path:String,asset:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val intent=Intent(context,PlaybackActivity::class.java)
            .putExtra("mobile",false).putExtra("path",path).putExtra("uri","asset:///$asset")
        ActivityScenario.launch<PlaybackActivity>(intent).use {scenario->
            fun await(label:String,condition:()->Boolean) {
                val deadline=System.nanoTime()+20_000_000_000L
                while(System.nanoTime()<deadline) {
                    var result=false
                    scenario.onActivity {result=condition()}
                    if(result)return
                    Thread.sleep(50)
                }
                error("$path: $label")
            }
            await("initial playback") {PlaybackActivity.remoteVideo()?.status=="playing"}
            val rendered=AtomicLong(-1)
            lateinit var frozenFrame:View
            scenario.onActivity {activity->
                val content=activity.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame)
                frozenFrame=content.children.filterIsInstance<VideoFreezeFrame>().single()
                var parent:View=content
                while(parent !is PlayerView) parent=parent.parent as View
                val player=parent.player as ExoPlayer
                player.setVideoFrameMetadataListener {timestamp,_,_,_->rendered.set(timestamp)}
                player.seekTo(minOf(12_000,player.duration-1_000))
            }
            await("reached later frame") {rendered.get()>3_000_000}
            val before=rendered.get()
            scenario.onActivity {activity->
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_REWIND)))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MEDIA_REWIND))
            }
            await("new earlier frame after rewinding") {
                rendered.get() in 0 until before-2_000_000 && PlaybackActivity.remoteVideo()?.status=="playing"
            }
            val earlier=rendered.get()
            await("live video continues advancing") {rendered.get()>earlier+250_000&&frozenFrame.visibility==View.GONE}
        }
    }
}
