package dev.mjwatch.mobile

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

/** Explicit opt-in: public Blender Sintel only. Does not remove torrents or alter server settings. */
@RunWith(AndroidJUnit4::class)
class LiveJacredTest {
 @Test fun publicSintelSearchToAuthorizedServerFirstFrame()=runBlocking {
    val args=InstrumentationRegistry.getArguments()
    val parser=args.getString("parser");val server=args.getString("server")
    Assume.assumeTrue("Live endpoints not supplied",!parser.isNullOrBlank()&&!server.isNullOrBlank())
    val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
    val config=Config(jackett=parser!!,torrServer=server!!,localHttp=true)
    Settings(context).save(config)
    val client=guardedClient(config);val repository=TorznabRepository(config,"",client)
    Assert.assertTrue(repository.check().contains("JacRed JSON"))
    val result=repository.search("Sintel")
    Assert.assertTrue(result.failures.isEmpty());Assert.assertTrue(result.releases.isNotEmpty())
    // Public CC BY 3.0 short film; avoid multi-GB variants and releases without seeds.
    val release=result.releases.filter {it.title.contains("Sintel",ignoreCase=true)&&(it.seeds ?: 0)>0&&(it.size ?: Long.MAX_VALUE)<100_000_000}.minByOrNull {it.size!!}
    Assert.assertNotNull("Small seeded Sintel release",release)
    println("MJWatch live JacRed: results=${result.releases.size}, selectedBytes=${release!!.size}, seeds=${release.seeds}")
    val gateway=TorrServerGateway(config,"",client,repository)
    Assert.assertTrue(gateway.check().startsWith("MatriX."))
    var handle=gateway.add(release)
    withTimeout(60_000) {while(handle.files.none {it.video&&!it.sample}) {delay(1000);handle=gateway.status(handle.hash)}}
    val file=handle.files.single {it.video&&!it.sample}
    val stream=gateway.stream(handle,file)
    context.startActivity(Intent(context,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("uri",stream.uri).putExtra("releaseId",release.id).putExtra("fileId",file.serverFileId).putExtra("path",file.path))
    try {
        var seen=false
        withTimeout(60_000) {while(!seen) {i.runOnMainSync {seen=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().any {it.firstFrameSeen}};delay(200)}}
        Assert.assertTrue(seen)
        println("MJWatch live JacRed: serverFileId=${file.serverFileId}, firstFrame=true")
    } finally {i.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().forEach {it.finish()}}}
 }
}
