package dev.mjwatch.playback

import android.app.*
import android.content.Intent
import android.os.Bundle
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Managed receiver uses its own credentials; controllers send IDs, never stream secrets or URLs. */
object RemoteReceiver {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private var profile=""
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
        if(profile==config.profileKey&&job?.isActive==true)return
        profile=config.profileKey;job?.cancel()
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
                                try {
                                    val handle=command.getString("handle")
                                    val response=withContext(Dispatchers.IO){api.authorized("playback/$handle")}
                                    val torrent=response.getJSONObject("torrent");val rows=torrent.getJSONArray("file_stats")
                                    val files=(0 until rows.length()).map {rows.getJSONObject(it)}
                                    val file=files.single {it.getInt("id")==command.getInt("fileId")}
                                    check(TorrentFile(file.getInt("id"),file.getString("path"),file.optLong("length")).video)
                                    val contentId=response.getString("hash")+":"+file.getInt("id")
                                    val current=PlaybackActivity.remoteVideo() ?: failure ?: RemoteVideo()
                                    if(current.contentId!=contentId||!current.active) {
                                        check(current.session==command.optString("session")){"Session changed"}
                                        check(android.os.SystemClock.elapsedRealtime()<expiresAt){"Expired command"}
                                        val activity=foreground?.get() ?: error("Receiver left foreground")
                                        val uri=api.base.newBuilder().addPathSegments("api/v1/playback/$handle/stream").addQueryParameter("file",file.getInt("id").toString()).build().toString()
                                        activity.startActivity(Intent(activity,PlaybackActivity::class.java).putExtra("uri",uri).putExtra("torrentHandle",handle).putExtra("remoteSession",id).putExtra("remotePosition",command.optLong("position")).putExtra("remoteContentId",contentId).putExtra("title",command.getString("title")).putExtra("metadataId",command.optString("metadataId")).putExtra("releaseId",command.optString("releaseId")).putExtra("path",file.getString("path")).putExtra("fileId",file.getInt("id")).putExtra("mobile",false).putExtra("contentKey",contentKey(response.getString("hash"),file.getString("path"),file.optLong("length"))))
                                        // Wait until the new Activity owns the session; never report old video as the new one.
                                        withTimeout(5000){while(PlaybackActivity.remoteVideo()?.session!=id){delay(50)}}
                                    }
                                    failure=null
                                } catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;failure=RemoteVideo(session=id,title=command.optString("title"),status="error")}
                                catch(e:Exception){failure=RemoteVideo(session=id,title=command.optString("title"),status="error")}
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
