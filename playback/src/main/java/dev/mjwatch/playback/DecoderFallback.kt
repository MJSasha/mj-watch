@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.mjwatch.playback

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
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
    enum class Recovery { SAME_DECODER, ALTERNATE_DECODER, SOFTWARE_AAC }
    var lastRecovery: Recovery? = null
        private set
    private val failed=ConcurrentHashMap.newKeySet<String>()
    private var factory:DefaultRenderersFactory?=null
    private var compatibilityTried=false
    @Volatile private var useSoftwareAac = false

    fun renderers(context:Context)=DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setMediaCodecSelector(this).also {factory=it}
    fun tryCompatibility(name:String?,supported:Boolean):Boolean {
        if(name.isNullOrBlank()||!supported||compatibilityTried||name in failed)return false
        compatibilityTried=true
        return true
    }
    override fun getDecoderInfos(mimeType:String,requiresSecureDecoder:Boolean,requiresTunnelingDecoder:Boolean)=
        if (useSoftwareAac && mimeType == MimeTypes.AUDIO_AAC && !requiresSecureDecoder) emptyList()
        else delegate.getDecoderInfos(mimeType,requiresSecureDecoder,requiresTunnelingDecoder).filterNot {it.name in failed}

    fun excludeFailed(name:String?,candidates:List<String>):Boolean {
        if(name.isNullOrBlank()||name in failed||failed.size>=2)return false
        if(candidates.none {it!=name&&it !in failed})return false
        return failed.add(name)
    }
    fun reset() {
        failed.clear();compatibilityTried=false
        useSoftwareAac = false
        // Keep a working synchronous queue for the remaining files in this playback session.
    }

    fun recover(context: Context, player: ExoPlayer, error: PlaybackException): Boolean {
        lastRecovery = null
        if (error.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED &&
            error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
        val failure = error as? ExoPlaybackException ?: return false
        val format = failure.rendererFormat ?: return false
        val item = player.currentMediaItem ?: return false
        val platformRetry = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED &&
            retryPlatformDecoder(context, error, format)
        if (!platformRetry) {
            if (useSoftwareAac || format.sampleMimeType != MimeTypes.AUDIO_AAC || format.drmInitData != null ||
                failure.rendererName != "MediaCodecAudioRenderer" || !FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_AAC)) return false
            useSoftwareAac = true
            lastRecovery = Recovery.SOFTWARE_AAC
        }
        val position = player.currentPosition.coerceAtLeast(0)
        val playing = player.playWhenReady
        player.setMediaItem(item, position)
        player.prepare()
        player.playWhenReady = playing
        return true
    }

    private fun retryPlatformDecoder(context: Context, error: PlaybackException, format: Format): Boolean {
        val codec = error.failedMediaCodec() ?: return false
        val renderers = factory
        if (renderers != null && tryCompatibility(codec.name, runCatching { codec.isFormatSupported(context, format) }.getOrDefault(false))) {
            renderers.forceDisableMediaCodecAsynchronousQueueing()
            lastRecovery = Recovery.SAME_DECODER
            return true
        }
        val candidates = runCatching {
            MediaCodecUtil.getDecoderInfosSoftMatch(this,format,codec.secure,codec.tunneling)
                .filter { it.isFormatSupported(context, format) }.map { it.name }
        }.getOrDefault(emptyList())
        return excludeFailed(codec.name, candidates).also {
            if (it) lastRecovery = Recovery.ALTERNATE_DECODER
        }
    }
}
