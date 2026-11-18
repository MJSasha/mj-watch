@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.mjwatch.playback

import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DecoderRuntimeRecoveryTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var player:ExoPlayer
    private val decoders=mutableListOf<String>()
    private var frames=0
    private var error:PlaybackException?=null
    private fun await(condition:()->Boolean) {
        compose.waitUntil(20000) {var done=false;instrumentation.runOnMainSync {done=condition()};done}
    }
    @Test fun runtimeFailureSwitchesDecoderAndPreservesPausedPosition()=verify(false)
    @Test fun runtimeFailureSwitchesDecoderAndResumesPlaying()=verify(true)
    @Test fun runtimeFailureStopsAfterOneCompatibleRestartWhenNoAlternateExists()=verify(false,true)
    private fun verify(playing:Boolean,oneDecoder:Boolean=false) {
        val context=instrumentation.targetContext
        val fallback=DecoderFallback(MediaCodecSelector {mime,secure,tunnel->
            val codecs=MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunnel)
            if(oneDecoder&&mime==MimeTypes.VIDEO_H264)codecs.take(1) else codecs
        })
        lateinit var surface:SurfaceView
        compose.setContent {AndroidView(modifier=Modifier.fillMaxSize(),factory={SurfaceView(it).also {surface=it}})}
        try {
            compose.runOnIdle {
                player=ExoPlayer.Builder(context,fallback.renderers(context)).build()
                player.setVideoSurfaceView(surface)
                player.addListener(object:Player.Listener {
                    override fun onRenderedFirstFrame(){frames++}
                    override fun onPlayerError(failure:PlaybackException){error=failure}
                })
                player.addAnalyticsListener(object:AnalyticsListener {
                    override fun onVideoDecoderInitialized(eventTime:AnalyticsListener.EventTime,decoderName:String,initializedTimestampMs:Long,initializationDurationMs:Long){decoders+=decoderName}
                })
                player.setMediaItem(MediaItem.Builder().setUri("asset:///matroska-fixture").setMimeType(MimeTypes.VIDEO_MATROSKA).setMediaId("runtime-fixture").build())
                player.prepare()
            }
            await {frames>0&&player.playbackState==Player.STATE_READY}
            compose.runOnIdle {player.seekTo(5000);player.setPlaybackSpeed(1.5f);player.playWhenReady=playing}
            await {player.playbackState==Player.STATE_READY&&player.currentPosition>=4900}
            var failedName=""
            lateinit var failure:ExoPlaybackException
            compose.runOnIdle {
                val format=player.videoFormat!!
                val codecs=MediaCodecSelector.DEFAULT.getDecoderInfos(format.sampleMimeType!!,false,false).filter {it.isFormatSupported(context,format)}
                failedName=decoders.last()
                val codec=codecs.single {it.name==failedName}
                assertTrue("Need two real AVC decoders: ${codecs.map {it.name}}",codecs.map {it.name}.distinct().size>=2)
                failure=ExoPlaybackException.createForRenderer(MediaCodecDecoderException(IllegalStateException("Injected runtime decoder failure"),codec),"MediaCodecVideoRenderer",0,format,C.FORMAT_HANDLED,null,false,PlaybackException.ERROR_CODE_DECODING_FAILED)

            }
            fun recover(sameCodec:Boolean) {
                var beforeFrames=0;var beforeDecoders=0;var position=0L
                compose.runOnIdle {
                    // Match the idle state after a real decoder failure, retaining playback intent.
                    player.stop();position=player.currentPosition;beforeFrames=frames;beforeDecoders=decoders.size
                    assertTrue(fallback.recover(context,player,failure))
                }
                await {frames>beforeFrames&&decoders.size>beforeDecoders&&(decoders.last()==failedName)==sameCodec&&player.playbackState==Player.STATE_READY}
                compose.runOnIdle {
                    assertNull(error)
                    assertEquals(playing,player.playWhenReady)
                    assertEquals(1.5f,player.playbackParameters.speed,0f)
                    assertEquals("runtime-fixture",player.currentMediaItem!!.mediaId)
                    assertTrue(kotlin.math.abs(player.currentPosition-position)<if(playing)2000 else 100)
                    val video=player.getRenderer((0 until player.rendererCount).first {player.getRendererType(it)==C.TRACK_TYPE_VIDEO})
                    val adapter=MediaCodecRenderer::class.java.getDeclaredMethod("getCodec").apply {isAccessible=true}.invoke(video)
                    assertEquals("SynchronousMediaCodecAdapter",adapter.javaClass.simpleName)
                }
            }
            recover(sameCodec=true)
            if(!oneDecoder)recover(sameCodec=false)
            else compose.runOnIdle {
                player.stop()
                val position=player.currentPosition
                assertFalse(fallback.recover(context,player,failure))
                assertEquals(Player.STATE_IDLE,player.playbackState)
                assertEquals(position,player.currentPosition)
            }
            compose.runOnIdle {
                val count=decoders.size
                assertFalse(fallback.recover(context,player,failure))
                assertEquals(count,decoders.size)
            }
        } finally {if(::player.isInitialized)instrumentation.runOnMainSync {player.clearVideoSurface();player.release()}}
    }
}
