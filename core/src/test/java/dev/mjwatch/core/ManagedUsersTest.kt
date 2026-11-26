package dev.mjwatch.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[35],manifest=org.robolectric.annotation.Config.NONE)
class ManagedUsersTest {
    @Test fun onlyActiveAdministratorsCountForLastOwnerProtection() {
        val owner=ManagedUser("owner","Владелец","admin",false)
        val blocked=owner.copy(id="blocked",blocked=true)
        val member=owner.copy(id="member",role="user")
        assertTrue(isLastAdmin(owner,listOf(owner,blocked,member)))
        assertFalse(isLastAdmin(owner,listOf(owner,owner.copy(id="other"))))
        assertFalse(isLastAdmin(member,listOf(owner,member)))
        assertEquals("Администратор",owner.roleLabel)
        assertEquals("Пользователь",member.roleLabel)
    }
    @Test fun namesRespectTheServersUtf8ByteLimit() {
        assertFalse(validManagedUserName("   "))
        assertTrue(validManagedUserName(" Анна "))
        assertTrue(validManagedUserName("я".repeat(50)))
        assertFalse(validManagedUserName("я".repeat(51)))
        assertTrue(safeError(HttpFailure(409,responseBody="""{"code":"name_unavailable"}""")).contains("уже используется"))
    }
    @Test fun parsingKeepsDeviceIdsAndActualAccessState() {
        val users=managedUsers(JSONObject("""{"users":[{"id":"u","name":"Анна","role":"user","blocked":true}]}"""))
        assertTrue(users.single().blocked)
        val devices=managedDevices(JSONObject("""{"devices":[{"id":"d","name":"Телефон","lastSeen":42,"revoked":true}]}"""))
        assertEquals("d",devices.single().id);assertEquals(42L,devices.single().lastSeen);assertTrue(devices.single().revoked)
        assertEquals(0L, devices.single().appVersionCode)
        val reported = managedDevices(JSONObject("""{"devices":[{"id":"d","name":"Телефон","appVersionName":"0.12.44","appVersionCode":1265}]}""")).single()
        assertEquals("0.12.44", reported.appVersionName)
        assertEquals(1265L, reported.appVersionCode)
    }
 @org.junit.Test fun unicodeDeviceAliasesAndBackwardCompatibleTypes() {
  org.junit.Assert.assertTrue(validDeviceName("  Гостиная 😀  "))
  org.junit.Assert.assertTrue(validDeviceName("😀".repeat(32)))
  for(value in listOf("", " ", "😀".repeat(33), "x\ny", "x\u0000y"))org.junit.Assert.assertFalse(value,validDeviceName(value))
  val old=managedDevices(org.json.JSONObject("""{"devices":[{"id":"a","name":"Legacy"}]}""")).single()
  org.junit.Assert.assertEquals("unknown",old.type)
  org.junit.Assert.assertFalse(old.remoteVideo)
  org.junit.Assert.assertFalse(old.available)
  val tv=managedDevices(org.json.JSONObject("""{"devices":[{"id":"tv","name":"Living room","type":"tv","remoteVideo":true,"available":true}]}""")).single()
  org.junit.Assert.assertEquals("Телевизор",tv.typeLabel)
  org.junit.Assert.assertTrue(tv.remoteVideo&&tv.available)
 }
}
