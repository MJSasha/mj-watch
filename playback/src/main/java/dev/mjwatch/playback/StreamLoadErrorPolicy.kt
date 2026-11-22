@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.mjwatch.playback

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.mjwatch.core.HttpFailure
import org.json.JSONObject

internal fun streamLimitScope(error:Throwable?):String? {
    for(cause in generateSequence(error){it.cause}) {
        if(cause is HttpFailure&&cause.status==429&&runCatching{JSONObject(cause.responseBody.orEmpty()).optString("code")}.getOrDefault("")=="stream_limit")return "server"
        if(cause !is HttpDataSource.InvalidResponseCodeException||cause.responseCode!=429)continue
        fun header(name:String)=cause.headerFields.entries.firstOrNull{it.key.equals(name,true)}?.value?.firstOrNull()
        val limited=header("X-MJWatch-Error")=="stream_limit"||runCatching{JSONObject(cause.responseBody.toString(Charsets.UTF_8)).optString("code")}.getOrDefault("")=="stream_limit"
        if(limited)return if(header("X-MJWatch-Limit-Scope")=="account")"account" else "server"
    }
    return null
}

internal class StreamLoadErrorPolicy:DefaultLoadErrorHandlingPolicy(3) {
    override fun getRetryDelayMsFor(info:LoadErrorHandlingPolicy.LoadErrorInfo):Long =
        if ((info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 403 ||
            streamLimitScope(info.exception) != null) C.TIME_UNSET
        else super.getRetryDelayMsFor(info)
}

internal fun streamLimitStatus(scope:String)=PlaybackStatus(
    "Достигнут лимит просмотров",
    (if(scope=="account")"На вашем аккаунте уже занят лимит одновременных просмотров. " else "На сервере уже занят лимит одновременных просмотров. ")+
        "Остановите видео на другом устройстве и нажмите «Повторить». Владелец может изменить лимиты в настройках TorrServer.",retry=true)
