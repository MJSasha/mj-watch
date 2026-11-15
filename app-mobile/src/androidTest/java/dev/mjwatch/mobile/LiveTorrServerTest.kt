package dev.mjwatch.mobile

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.*
import org.junit.runner.RunWith

/** Manual opt-in only: -e server http://host:port . Never touches user torrents or server settings. */
@RunWith(AndroidJUnit4::class)
class LiveTorrServerTest {
 @Test fun authorizedServerAdapterAndFirstFrame()=runBlocking {
    val server=InstrumentationRegistry.getArguments().getString("server")
    Assume.assumeTrue("Live server not supplied",!server.isNullOrBlank())
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    val context=instrumentation.targetContext
    val fixtureServer=okhttp3.mockwebserver.MockWebServer().apply {start()}
    val c=Config(jackett=fixtureServer.url("/").toString().trimEnd('/'),torrServer=server!!,localHttp=true)
    Settings(context).save(c)
    val client=guardedClient(c)
    val gateway=TorrServerGateway(c,"",client,TorznabRepository(c,"",client))
    Assert.assertTrue(gateway.check().startsWith("MatriX."))
    val r=Release("live:sintel","Sintel (CC BY 3.0)","Acceptance",hash="08ada5a7a6183aae1e09d831df6748d566095a10",magnet="magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel")
    gateway.add(r)
    // Load the public .torrent through a local fixture server. It has no Jackett credentials.
    val bytes=instrumentation.context.assets.open("sintel.torrent").use { it.readBytes() }
    validateTorrent(bytes)
    fixtureServer.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(bytes)))
    val uploaded=try { gateway.add(r.copy(magnet=null,hash=null,download=fixtureServer.url("/fixture.torrent").toString())) } finally {fixtureServer.shutdown()}
    var h=uploaded
    withTimeout(60_000) {while(h.files.isEmpty()) {delay(1000);h=gateway.status(h.hash)}}
    val file=h.files.single {it.video}
    Assert.assertEquals(6,file.serverFileId)
    val stream=gateway.stream(h,file)
    context.startActivity(Intent(context,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("uri",stream.uri).putExtra("releaseId",r.id).putExtra("fileId",file.serverFileId).putExtra("path",file.path))
    var seen=false
    withTimeout(60_000) {while(!seen) {instrumentation.runOnMainSync {seen=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().any {it.firstFrameSeen}};delay(200)}}
    Assert.assertTrue(seen)
    val hold=InstrumentationRegistry.getArguments().getString("holdMs")?.toLongOrNull()?.coerceIn(0,60000) ?: 0
    if(hold>0) {
        var initialPosition=0L
        instrumentation.runOnMainSync {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().single()
            val field=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}
            initialPosition=(field.get(activity) as androidx.media3.common.Player).currentPosition
        }
        delay(hold)
        instrumentation.runOnMainSync {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().single()
            val f=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}
            val player=f.get(activity) as androidx.media3.exoplayer.ExoPlayer
            val retries=PlaybackActivity::class.java.getDeclaredField("retries").apply {isAccessible=true}.getInt(activity)
            println("MJWatch sustained state: positionMs=${player.currentPosition}, durationMs=${player.duration}, bufferedMs=${player.bufferedPosition}, state=${player.playbackState}, ready=${player.playWhenReady}, error=${player.playerError?.errorCode}, retries=$retries")
            Assert.assertTrue("Sustained playback",player.isPlaying)
            Assert.assertTrue("Position advances during hold (not a throughput benchmark)",player.currentPosition-initialPosition>=1000)
            Assert.assertEquals("No deadline reconnect",0,retries)
            println("MJWatch live proof: video=${player.videoFormat?.sampleMimeType}, audio=${player.audioFormat?.sampleMimeType}, positionMs=${player.currentPosition}, retries=$retries")
        }
    }
    instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().forEach {it.finish()}}
 }
}
