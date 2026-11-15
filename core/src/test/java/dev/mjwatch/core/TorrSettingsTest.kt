package dev.mjwatch.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TorrSettingsTest {
 private fun snapshot()=JSONObject("""{
  "settings":{"CacheSize":67108864,"PreloadCache":50,"ResponsiveMode":false,"BTScrapeTrackers":["fixture"],"FutureObject":{"enabled":true},"EnableDLNA":false},
  "fields":[{"name":"CacheSize"},{"name":"PreloadCache"},{"name":"ResponsiveMode"}]
 }""")
 @Test fun clonedSettingsDoNotSubmitUnknownArraysOrObjects(){
  val snapshot=snapshot();val before=snapshot.getJSONObject("settings");val draft=JSONObject(before.toString())
  // JSONObject retains identity equality; an unchanged cloned object leaked into the old patch.
  assertNotEquals(before.get("FutureObject"),draft.get("FutureObject"))
  assertEquals(0,torrSettingsChanges(snapshot,draft).length())
  draft.put("ResponsiveMode",true).put("EnableDLNA",true)
  val patch=torrSettingsChanges(snapshot,draft)
  assertEquals(1,patch.length());assertTrue(patch.getBoolean("ResponsiveMode"))
  assertFalse(patch.has("BTScrapeTrackers"));assertFalse(patch.has("FutureObject"));assertFalse(patch.has("EnableDLNA"))
 }
 @Test fun numericTypesDoNotCreatePhantomChangesAndActualNumbersArePreserved(){
  val snapshot=snapshot();val draft=JSONObject(snapshot.getJSONObject("settings").toString()).put("PreloadCache",50L)
  assertEquals(0,torrSettingsChanges(snapshot,draft).length())
  draft.put("CacheSize",128L*1048576).put("PreloadCache",25L)
  val patch=torrSettingsChanges(snapshot,draft)
  assertEquals(2,patch.length());assertEquals(128L*1048576,patch.getLong("CacheSize"));assertEquals(25,patch.getInt("PreloadCache"))
 }
}
