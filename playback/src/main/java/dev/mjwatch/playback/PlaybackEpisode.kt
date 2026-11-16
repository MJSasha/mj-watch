package dev.mjwatch.playback

import org.json.JSONArray
import org.json.JSONObject

/** Local playback queue; server file IDs and progress identities stay attached to each file. */
data class PlaybackEpisode(val uri:String,val path:String,val fileId:Int,val contentKey:String="") {
    companion object {
        fun encode(episodes:List<PlaybackEpisode>):String=JSONArray().apply {episodes.forEach {e->put(JSONObject().put("uri",e.uri).put("path",e.path).put("fileId",e.fileId).put("contentKey",e.contentKey))}}.toString()
        fun decode(value:String?):List<PlaybackEpisode> = runCatching {
            val array=JSONArray(value ?: "[]")
            (0 until array.length()).map {i->val e=array.getJSONObject(i);PlaybackEpisode(e.getString("uri"),e.getString("path"),e.getInt("fileId"),e.optString("contentKey"))}
        }.getOrDefault(emptyList())
    }
}
