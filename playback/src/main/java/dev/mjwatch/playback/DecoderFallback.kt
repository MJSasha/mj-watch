@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.mjwatch.playback

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.concurrent.ConcurrentHashMap

internal fun PlaybackException.failedMediaCodec()=generateSequence<Throwable>(this) {it.cause}
    .filterIsInstance<MediaCodecDecoderException>().firstOrNull()?.codecInfo

/** Exclusions last only for this file. Reads also happen on ExoPlayer's playback thread. */
internal class DecoderFallback(private val delegate:MediaCodecSelector=MediaCodecSelector.DEFAULT):MediaCodecSelector {
    private val failed=ConcurrentHashMap.newKeySet<String>()
    private var factory:DefaultRenderersFactory?=null
    private var compatibilityTried=false
    fun renderers(context:Context)=DefaultRenderersFactory(context).setEnableDecoderFallback(true).setMediaCodecSelector(this).also {factory=it}
    fun tryCompatibility(name:String?,supported:Boolean):Boolean {
        if(name.isNullOrBlank()||!supported||compatibilityTried||name in failed)return false
        compatibilityTried=true
        return true
    }
    override fun getDecoderInfos(mimeType:String,requiresSecureDecoder:Boolean,requiresTunnelingDecoder:Boolean)=
        delegate.getDecoderInfos(mimeType,requiresSecureDecoder,requiresTunnelingDecoder).filterNot {it.name in failed}

    fun excludeFailed(name:String?,candidates:List<String>):Boolean {
        if(name.isNullOrBlank()||name in failed||failed.size>=2)return false
        if(candidates.none {it!=name&&it !in failed})return false
        return failed.add(name)
    }
    fun reset() {
        failed.clear();compatibilityTried=false
        // Keep a working synchronous queue for the remaining files in this playback session.
    }

    fun recover(context:Context,player:ExoPlayer,error:PlaybackException):Boolean {
        if(error.errorCode!=PlaybackException.ERROR_CODE_DECODING_FAILED)return false
        val codec=error.failedMediaCodec() ?: return false
        val format=(error as? ExoPlaybackException)?.rendererFormat ?: return false
        val item=player.currentMediaItem ?: return false
        val retryCompatible=factory!=null&&tryCompatibility(codec.name,runCatching {codec.isFormatSupported(context,format)}.getOrDefault(false))
        if(retryCompatible)factory!!.forceDisableMediaCodecAsynchronousQueueing()
        val candidates=if(retryCompatible)emptyList() else runCatching {
            MediaCodecUtil.getDecoderInfosSoftMatch(this,format,codec.secure,codec.tunneling)
                .filter {it.isFormatSupported(context,format)}.map {it.name}
        }.getOrDefault(emptyList())
        if(!retryCompatible&&!excludeFailed(codec.name,candidates))return false
        val position=player.currentPosition.coerceAtLeast(0)
        val playing=player.playWhenReady
        player.setMediaItem(item,position)
        player.prepare()
        player.playWhenReady=playing
        return true
    }
}
