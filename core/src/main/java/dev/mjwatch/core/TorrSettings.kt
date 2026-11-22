package dev.mjwatch.core

import org.json.JSONObject

/** Send a sparse patch of the scalar fields advertised by the pinned server contract. */
fun torrSettingsChanges(snapshot:JSONObject,draft:JSONObject):JSONObject {
 val before=snapshot.getJSONObject("settings")
 val fields=snapshot.getJSONArray("fields")
 val changes=JSONObject()
 for(index in 0 until fields.length()) {
  val name=fields.getJSONObject(index).getString("name")
  if(!before.has(name)||!draft.has(name))continue
  val previous=before.get(name);val current=draft.get(name)
  val equal=if(previous is Number&&current is Number)previous.toDouble()==current.toDouble() else previous==current
  if(!equal)changes.put(name,current)
 }
 return changes
}

/** Keep the viewer's pending edits while accepting the server's latest values and revision. */
fun refreshTorrSettingsDraft(previous:JSONObject,draft:JSONObject,fresh:JSONObject):JSONObject {
 val edits=torrSettingsChanges(previous,draft)
 val settings=JSONObject(fresh.getJSONObject("settings").toString())
 edits.keys().forEach {name->if(settings.has(name))settings.put(name,edits.get(name))}
 return settings
}

fun torrCacheUsesDisk(settings:JSONObject):Boolean? = settings.opt("UseDisk") as? Boolean

fun torrCacheBudget(snapshot:JSONObject,draft:JSONObject):JSONObject? = when(torrCacheUsesDisk(draft)) {
 true -> snapshot.optJSONObject("storage")?.takeIf {
  it.optBoolean("available")&&draft.optString("TorrentsSavePath")==snapshot.getJSONObject("settings").optString("TorrentsSavePath")
 }
 false -> snapshot.optJSONObject("resources")?.takeIf {it.optLong("totalBytes")>0}
 null -> null
}

data class TorrFieldAdvice(val recommended:Long?,val maximum:Long,val measured:Boolean=false)

fun torrFieldAdvice(snapshot:JSONObject,draft:JSONObject,field:JSONObject):TorrFieldAdvice {
 val name=field.getString("name");val maximum=field.optLong("max")
 val memory=snapshot.optJSONObject("resources")
 return when(name){
  "CacheSize"->{
   val budget=torrCacheBudget(snapshot,draft)?.takeIf {it.has("maximumBytes")}
   TorrFieldAdvice(budget?.optLong("recommendedBytes")?.takeIf{it>=field.optLong("min")},
    budget?.let {minOf(maximum,it.optLong("maximumBytes"))} ?: maximum,budget!=null)
  }
  "PreloadCache"->TorrFieldAdvice(25,maximum)
  "ReaderReadAHead"->TorrFieldAdvice(95,maximum)
  "ConnectionsLimit"->TorrFieldAdvice(if((memory?.optLong("totalBytes") ?: 0)<=1073741824L)30 else 60,maximum)
  "DownloadRateLimit","UploadRateLimit"->TorrFieldAdvice(0,maximum)
  else->TorrFieldAdvice(null,maximum)
 }
}

fun torrSettingsValid(snapshot:JSONObject,draft:JSONObject):Boolean {
 val fields=snapshot.optJSONArray("fields") ?: return false
 for(i in 0 until fields.length()){
  val field=fields.getJSONObject(i);val name=field.getString("name")
  if(!draft.has(name)||field.optBoolean("boolean")||field.optString("unit")=="path")continue
  val n=draft.opt(name) as? Number ?: return false
  if(n.toDouble()!=n.toLong().toDouble()||n.toLong()<field.optLong("min")||n.toLong()>field.optLong("max"))return false
  val before=snapshot.getJSONObject("settings")
  val changed=if(name=="CacheSize")n.toLong()>before.optLong(name)||torrCacheUsesDisk(before)!=torrCacheUsesDisk(draft) else n.toLong()!=before.optLong(name)
  if(changed&&n.toLong()>torrFieldAdvice(snapshot,draft,field).maximum)return false
 }
 return true
}
