package dev.mjwatch.playback

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Managed receiver uses its own credentials; controllers send IDs, never stream secrets or URLs. */
object RemoteReceiver {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private var connection:Pair<String,String>?=null
    private var registered=false
    private var foreground:WeakReference<Activity>?=null
    fun start(app:Application,config:Config) {
        if(!registered) {
            registered=true
            app.registerActivityLifecycleCallbacks(object:Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(a:Activity){foreground=WeakReference(a)}
                override fun onActivityPaused(a:Activity){if(foreground?.get()===a)foreground=null}
                override fun onActivityCreated(a:Activity,b:Bundle?){}
                override fun onActivityStarted(a:Activity){}
                override fun onActivityStopped(a:Activity){}
                override fun onActivitySaveInstanceState(a:Activity,b:Bundle){}
                override fun onActivityDestroyed(a:Activity){}
            })
        }
        val requested=config.profileKey to config.managedUrl
        if(connection==requested&&job?.isActive==true)return
        connection=requested;job?.cancel()
        if(!config.historyEnabled)return
        job=scope.launch {
            val api=ManagedApi(config,SecretStore(app))
            var instance="";var ack="";var last="";var failure:RemoteVideo?=null
            while(isActive) {
                try {
                    if(instance.isBlank()) {
                        withContext(Dispatchers.IO){api.authorized("devices/self/profile","PUT",JSONObject().put("type","tv"))}
                        instance=withContext(Dispatchers.IO){api.authorized("remote/receiver/open","POST")}.getString("instance")
                    }
                    val observed=PlaybackActivity.remoteVideo() ?: failure ?: RemoteVideo()
                    val requestedAt=android.os.SystemClock.elapsedRealtime()
                    val result=withContext(Dispatchers.IO){api.authorized("remote/receiver","POST",JSONObject().put("instance",instance).put("ack",ack).put("ready",foreground?.get()!=null).put("state",observed.json()))}
                    val expiresAt=requestedAt+result.optLong("commandTtlMs").coerceIn(0,8000)
                    val command=result.optJSONObject("command")
                    val id=command?.optString("id").orEmpty()
                    if(id.isNotBlank()&&id!=last&&foreground?.get()!=null) {
                        last=id;ack=id
                        if(command!!.getString("instance")==instance&&command.optString("session")==observed.session&&android.os.SystemClock.elapsedRealtime()<expiresAt) {
                            if(command.getString("action")=="load") {
                                var pendingPlayback:Intent?=null
                                try {
                                    val handle=command.getString("handle")
                                    val playback = withContext(Dispatchers.IO) {
                                        remotePlaybackIntent(app, api.base, api.authorized("playback/$handle"), command)
                                            .also {pendingPlayback=it}
                                    }
                                    val contentId = playback.getStringExtra("remoteContentId")
                                    val current=PlaybackActivity.remoteVideo() ?: failure ?: RemoteVideo()
                                    if(current.contentId!=contentId||!current.active) {
                                        check(current.session==command.optString("session")){"Session changed"}
                                        check(android.os.SystemClock.elapsedRealtime()<expiresAt){"Expired command"}
                                        val activity=foreground?.get() ?: error("Receiver left foreground")
                                        activity.startActivity(playback)
                                        pendingPlayback=null
                                        // Wait until the new Activity owns the session; never report old video as the new one.
                                        withTimeout(5000){while(PlaybackActivity.remoteVideo()?.session!=id){delay(50)}}
                                    }
                                    failure=null
                                } catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;failure=RemoteVideo(session=id,title=command.optString("title"),status="error")}
                                catch(e:Exception){failure=RemoteVideo(session=id,title=command.optString("title"),status="error")}
                                finally {
                                    pendingPlayback?.getStringExtra(PlaybackQueue.EXTRA)?.let {token ->
                                        withContext(NonCancellable+Dispatchers.IO) {PlaybackQueue.open(app.cacheDir,token)?.delete()}
                                    }
                                }
                            } else PlaybackActivity.remoteCommand(command)
                        }
                    }
                } catch(e:CancellationException){throw e}
                catch(e:HttpFailure){if(e.status==409)instance="";if(e.status in setOf(401,403)){PlaybackActivity.stopActivePlayback();return@launch}}
                catch(_:Exception){/* Losing a controller/relay must never stop the TV. */}
                delay(1000)
            }
        }
    }
    fun attach(activity:Activity){foreground=WeakReference(activity)}
}

/** The TV derives every playable URL and progress key from its own authorized handle response. */
internal fun remotePlaybackIntent(context: Context, base: HttpUrl, response: JSONObject, command: JSONObject): Intent {
    val handle = command.getString("handle")
    val hash = response.getString("hash")
    val rows = response.getJSONObject("torrent").getJSONArray("file_stats")
    val files = (0 until rows.length()).map { index ->
        val file = rows.getJSONObject(index)
        TorrentFile(file.getInt("id"), file.getString("path"), file.optLong("length"))
    }
    val selected = files.single { it.serverFileId == command.getInt("fileId") }
    require(selected.video)

    val endpoint = base.newBuilder().addPathSegments("api/v1/playback").addPathSegment(handle).addPathSegment("stream").build()
    fun episode(file: TorrentFile) = PlaybackEpisode(
        endpoint.newBuilder().addQueryParameter("file", file.serverFileId.toString()).build().toString(),
        file.path, file.serverFileId, contentKey(hash, file.path, file.size)
    )
    val current = episode(selected)
    val queue = if (!selected.sample && episodeNumber(selected.path).episode != null) {
        val videos = sortedVideoFiles(files.filter { it.video && !it.sample })
        videos.groupBy { episodeNumber(it.path) }.filterKeys { it.episode != null }.values.map { versions ->
            episode(versions.firstOrNull { it == selected } ?: versions.first())
        }
    } else emptyList()

    return Intent(context, PlaybackActivity::class.java)
        .putExtra("torrentHandle", handle)
        .putExtra("remoteSession", command.getString("id"))
        .putExtra("remotePosition", command.optLong("position"))
        .putExtra("remoteContentId", "$hash:${current.fileId}")
        .putExtra("title", command.getString("title"))
        .putExtra("metadataId", command.optString("metadataId"))
        .putExtra("releaseId", command.optString("releaseId"))
        .putExtra("uri", current.uri)
        .putExtra("path", current.path)
        .putExtra("fileId", current.fileId)
        .putExtra("contentKey", current.contentKey)
        .putExtra("mobile", false)
        .apply {
            if(queue.isNotEmpty()) {
                val stored=PlaybackQueue.create(context.cacheDir)
                try {
                    stored.write(queue)
                    putExtra(PlaybackQueue.EXTRA,stored.token)
                } catch(e:Exception) {
                    stored.delete()
                    throw e
                }
            }
        }
}
