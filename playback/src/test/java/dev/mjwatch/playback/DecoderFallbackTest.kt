package dev.mjwatch.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Test

class DecoderFallbackTest {
    @Test fun onlyDifferentAvailableDecodersAreTriedAndRetriesAreBoundedPerFile() {
        val fallback=DecoderFallback()
        assertFalse(fallback.excludeFailed(null,listOf("software")))
        assertFalse(fallback.excludeFailed("hardware",listOf("hardware")))
        assertTrue(fallback.excludeFailed("hardware",listOf("hardware","software","third")))
        assertFalse(fallback.excludeFailed("hardware",listOf("software","third")))
        assertFalse(fallback.excludeFailed("software",listOf("hardware","software")))
        assertTrue(fallback.excludeFailed("software",listOf("third")))
        assertFalse(fallback.excludeFailed("third",listOf("fourth")))
        fallback.reset()
        assertTrue(fallback.excludeFailed("hardware",listOf("hardware","software")))
    }
    @Test fun oneCompatibilityRestartRequiresDeclaredSupportAndResetsForANewFile() {
        val fallback=DecoderFallback()
        assertFalse(fallback.tryCompatibility(null,true))
        assertFalse(fallback.tryCompatibility("decoder",false))
        assertTrue(fallback.tryCompatibility("decoder",true))
        assertFalse(fallback.tryCompatibility("decoder",true))
        assertFalse(fallback.tryCompatibility("another",true))
        fallback.reset()
        assertTrue(fallback.tryCompatibility("decoder",true))
    }
    @Test fun unknownHevcRangeExtensionsCanRetryTheInitializedDecoderOnce() {
        val fallback = DecoderFallback()
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.4.10.H120.9C.08").build()
        assertFalse(fallback.tryCompatibility(null,false,format))
        assertFalse(fallback.tryCompatibility("decoder",false,format.buildUpon().setCodecs("hvc1.2.4.L120").build()))
        assertTrue(fallback.tryCompatibility("decoder",false,format))
        assertFalse(fallback.tryCompatibility("decoder",false,format))
    }
}
