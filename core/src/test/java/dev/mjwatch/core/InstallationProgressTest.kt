package dev.mjwatch.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InstallationProgressTest {
 @Test fun actualFailedStageAndSafeCodeSurviveParsing(){
  val progress=InstallationProgress.from(JSONObject("""{"stage":"securing","state":"failed","code":"CalledProcessError","updated":123}"""))
  assertEquals(3,progress.index);assertTrue(progress.terminal)
  assertTrue(progress.diagnostic.contains("code=CalledProcessError"));assertEquals(123,progress.updated)
  assertFalse(installationFailure("unsupported_os").contains("HTTP"))
  val legacy=InstallationProgress.from(JSONObject("""{"stage":"failed","state":"failed","code":"unsupported_os"}"""))
  assertEquals(-1,legacy.index);assertTrue(legacy.terminal)
 }
 @Test fun diagnosticDropsUntrustedFieldsAndUnsafeCodes(){
  val progress=InstallationProgress.from(JSONObject("""{"stage":"https://secret.example","state":"password","code":"token=secret","password":"secret","updated":12}"""))
  assertEquals("unknown",progress.stage);assertEquals("unknown",progress.state);assertEquals("",progress.code)
  assertFalse(progress.diagnostic.contains("secret"));assertFalse(progress.diagnostic.contains("password"))
  assertFalse(installationError(IllegalArgumentException("https://host?key=secret")).contains("secret"))
 }
 @Test fun localPreflightDoesNotMasqueradeAsHttpFailure(){
  val invalid=runCatching {SshTarget("https://example.org",password="secret")}.exceptionOrNull()!!
  assertEquals("Укажите IP или домен без протокола и пути",installationError(invalid))
  assertEquals("SSH-порт должен быть от 1 до 65535",installationError(runCatching {SshTarget("example.org",0,password="secret")}.exceptionOrNull()!!))
  assertEquals(4,InstallationProgress.from(JSONObject("""{"stage":"awaiting_app_verification","state":"installed"}""")).index)
 }
}
