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
