package dev.mjwatch.core

import org.json.JSONObject
import java.io.IOException

class ServerMove internal constructor(
    val baseUrl:String,
    internal val ca:String,
    private val serverId:String,
    private val serverEpoch:String,
    private val migrationId:String,
):IOException("Сервер переехал. Проверяем подключение к новому адресу.") {
    internal fun verify(identity:JSONObject) {
        check(identity.optString("serverId")==serverId) {"На новом адресе найден другой сервер"}
        check(identity.optString("serverEpoch")==serverEpoch&&identity.optString("migrationId")==migrationId) {"На новом сервере другая версия данных"}
    }
}

internal fun serverMove(config:Config,error:HttpFailure):ServerMove? {
    if(error.status!=409||!config.historyEnabled)return null
    val payload=runCatching {JSONObject(error.responseBody ?: "")}.getOrNull() ?: return null
    if(payload.optString("code")!="server_moved")return null
    val target=runCatching {endpoint(payload.getString("baseUrl"))}.getOrNull()
    val serverEpoch=payload.optString("serverEpoch")
    val migrationId=payload.optString("migrationId")
    val ca=payload.optString("ca")
    if(target==null||target.scheme!="https"||target.origin()==endpoint(config.managedUrl).origin()||
        payload.optString("serverId")!=config.serverId||serverEpoch.isBlank()||migrationId.isBlank()||ca.isBlank()) {
        throw IOException("Сервер сообщил некорректные настройки переноса")
    }
    return ServerMove(target.toString(),ca,config.serverId,serverEpoch,migrationId)
}
