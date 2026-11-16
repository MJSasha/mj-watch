package dev.mjwatch.playback

import org.json.JSONArray
import org.json.JSONObject

/** Local playback queue; server file IDs and progress identities stay attached to each file. */
data class PlaybackEpisode(val uri:String,val path:String,val fileId:Int,val contentKey:String="",val openingStartMs:Long?=null,val openingEndMs:Long?=null,val endingStartMs:Long?=null,val endingEndMs:Long?=null) {
    companion object {
        fun encode(episodes:List<PlaybackEpisode>):String=JSONArray().apply {episodes.forEach {e->put(JSONObject().put("uri",e.uri).put("path",e.path).put("fileId",e.fileId).put("contentKey",e.contentKey).put("openingStartMs",e.openingStartMs).put("openingEndMs",e.openingEndMs).put("endingStartMs",e.endingStartMs).put("endingEndMs",e.endingEndMs))}}.toString()
        fun decode(value:String?):List<PlaybackEpisode> = runCatching {
            val array=JSONArray(value ?: "[]")
            (0 until array.length()).map {i->val e=array.getJSONObject(i);PlaybackEpisode(e.getString("uri"),e.getString("path"),e.getInt("fileId"),e.optString("contentKey"),e.marker("openingStartMs"),e.marker("openingEndMs"),e.marker("endingStartMs"),e.marker("endingEndMs"))}
        }.getOrDefault(emptyList())
    }
}

private fun JSONObject.marker(key:String):Long? = if(isNull(key)) null else optLong(key,-1).takeIf {it>=0}
