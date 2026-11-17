package dev.mjwatch.playback

import org.json.JSONObject

/** Receiver truth; a successful command submission is never represented as playing. */
data class RemoteVideo(val session:String="",val contentId:String="",val title:String="",val status:String="idle",val position:Long=0,val duration:Long=0,val seek:Boolean=false,val volume:Int=0,val volumeMax:Int=0,val releaseId:String="",val metadataId:String="") {
    val active get()=session.isNotBlank()&&status !in setOf("idle","ended","error")
    fun json()=JSONObject().put("session",session).put("contentId",contentId).put("title",title).put("status",status).put("position",position).put("duration",duration).put("seek",seek).put("volume",volume).put("volumeMax",volumeMax).put("releaseId",releaseId).put("metadataId",metadataId)
    companion object {fun parse(j:JSONObject)=RemoteVideo(j.optString("session"),j.optString("contentId"),j.optString("title"),j.optString("status","idle"),j.optLong("position"),j.optLong("duration"),j.optBoolean("seek"),j.optInt("volume"),j.optInt("volumeMax"),j.optString("releaseId"),j.optString("metadataId"))}
}
data class RemoteTarget(val id:String,val name:String,val instance:String,val video:RemoteVideo,val pendingId:String="") {
    companion object {fun parse(j:JSONObject)=RemoteTarget(j.getString("deviceId"),j.getString("name"),j.getString("instance"),RemoteVideo.parse(j.getJSONObject("state")),j.optString("pendingId"))}
}
data class RemotePlaybackState(val target:RemoteTarget?=null,val connected:Boolean=false,val busy:Boolean=false,val message:String="",val conflict:RemoteReplacement?=null,val profileKey:String="",val observedAt:Long=0) {
    val canLaunch get()=connected&&!busy&&target!=null&&System.nanoTime()-observedAt<6_000_000_000L
}
data class RemoteReplacement(val targetId:String,val instance:String,val session:String,val name:String,val videoTitle:String,val media:RemoteMedia)
data class RemoteMedia(val handle:String,val fileId:Int,val title:String,val releaseId:String,val contentId:String,val metadataId:String="",val position:Long=0)
