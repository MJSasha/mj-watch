package dev.mjwatch.core

import org.json.JSONObject

data class ManagedUser(val id:String,val name:String,val role:String,val blocked:Boolean) {
    val roleLabel get()=if(role=="admin")"Администратор" else "Пользователь"
}
data class ManagedDevice(val id:String,val name:String,val lastSeen:Long,val revoked:Boolean,val type:String="unknown",val remoteVideo:Boolean=false,val available:Boolean=false) {
    val typeLabel get()=when(type){"tv"->"Телевизор";"tablet"->"Планшет";"phone"->"Телефон";else->"Устройство"}
    val shortId get()=id.take(6)
}
data class UserInvitation(val userId:String,val token:String,val expiresAt:Long)
fun managedUsers(json:JSONObject):List<ManagedUser> {
    val rows=json.getJSONArray("users")
    return (0 until rows.length()).map {rows.getJSONObject(it).let {v->ManagedUser(v.getString("id"),v.getString("name"),v.getString("role"),v.getBoolean("blocked"))}}
}
fun managedDevices(json:JSONObject):List<ManagedDevice> {
    val rows=json.getJSONArray("devices")
    return (0 until rows.length()).map {rows.getJSONObject(it).let {v->ManagedDevice(v.getString("id"),v.getString("name"),v.optLong("lastSeen"),v.optBoolean("revoked"),v.optString("type","unknown"),v.optBoolean("remoteVideo"),v.optBoolean("available"))}}
}
fun isLastAdmin(user:ManagedUser,users:List<ManagedUser>)=user.role=="admin"&&!user.blocked&&users.count {it.role=="admin"&&!it.blocked}<=1
fun validManagedUserName(name:String)=name.trim().isNotEmpty()&&name.trim().toByteArray(Charsets.UTF_8).size<=100

fun validDeviceName(value:String):Boolean {val name=value.trim();return name.codePointCount(0,name.length) in 1..32&&name.codePoints().noneMatch {Character.isISOControl(it)}}
