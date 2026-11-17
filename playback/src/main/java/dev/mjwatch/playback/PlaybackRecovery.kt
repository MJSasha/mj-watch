package dev.mjwatch.playback

import dev.mjwatch.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Retry temporary transport failures, never access denial, TLS or decoder failures. */
internal fun recoverablePlaybackError(code:Int,httpStatus:Int?,managed:Boolean):Boolean =
    if(httpStatus!=null) httpStatus in listOf(408,429,500,502,503,504)||httpStatus==404||httpStatus==401&&managed
    else code in listOf(2000,2001,2002,2004)

internal suspend fun recoverTorrent(gateway:StreamGateway,release:Release,handleId:String,expectedHash:String,path:String):TorrentHandle=withTimeout(60_000) {
    var handle=try {gateway.status(handleId)} catch(e:HttpFailure) {if(e.status!=404)throw e;gateway.add(release)}
    while(handle.files.isEmpty()) {delay(1000);handle=gateway.status(handle.hash)}
    check(expectedHash.isBlank()||(handle.contentHash ?: handle.hash).equals(expectedHash,true)) {"Раздача изменилась. Выберите её заново."}
    check(handle.files.count {it.path==path}==1) {"Исходный файл недоступен. Выберите его заново."}
    handle
}
