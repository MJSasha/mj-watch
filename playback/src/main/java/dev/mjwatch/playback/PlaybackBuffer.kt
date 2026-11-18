package dev.mjwatch.playback

internal data class PlaybackBuffer(val maximumBytes:Int,val recommendedBytes:Int)

// Leave room for the decoder, UI, images and other apps; these are targets, not an RSS cap.
internal fun playbackBuffer(heapLimit:Long,heapUsed:Long,availableRam:Long,lowRam:Boolean):PlaybackBuffer {
    val mib=1048576L
    val maximum=minOf(heapLimit/4,(heapLimit-heapUsed-32*mib).coerceAtLeast(0)/2,
        availableRam.coerceAtLeast(0)/8,if(lowRam)24*mib else 128*mib)
        .coerceAtLeast(4*mib)/mib*mib
    return PlaybackBuffer(maximum.toInt(),minOf(maximum,if(lowRam)16*mib else 64*mib).toInt())
}
