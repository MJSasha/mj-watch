package dev.mjwatch.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackBufferTest {
    private val mib=1048576L
    @Test fun leavesDecoderHeapAndNeighbouringAppsRoom() {
        val normal=playbackBuffer(512*mib,80*mib,2048*mib,false)
        assertEquals(128*mib,normal.maximumBytes.toLong())
        assertEquals(64*mib,normal.recommendedBytes.toLong())
        val weak=playbackBuffer(128*mib,40*mib,256*mib,true)
        assertEquals(24*mib,weak.maximumBytes.toLong())
        assertEquals(16*mib,weak.recommendedBytes.toLong())
        val busy=playbackBuffer(256*mib,64*mib,64*mib,false)
        assertEquals(8*mib,busy.maximumBytes.toLong())
        val full=playbackBuffer(128*mib,127*mib,0,true)
        assertEquals(4*mib,full.maximumBytes.toLong())
        assertTrue(full.recommendedBytes<=full.maximumBytes)
    }
}
