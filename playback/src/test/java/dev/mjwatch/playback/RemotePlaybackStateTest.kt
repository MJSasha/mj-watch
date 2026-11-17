package dev.mjwatch.playback

import org.junit.Assert.*
import org.junit.Test

class RemotePlaybackStateTest {
    @Test fun launchRequiresFreshReceiverObservation() {
        val target=RemoteTarget("tv","Гостиная","instance",RemoteVideo())
        val live=RemotePlaybackState(target=target,connected=true,observedAt=System.nanoTime())
        assertTrue(live.canLaunch)
        assertFalse(live.copy(connected=false).canLaunch)
        assertFalse(live.copy(target=null).canLaunch)
        assertFalse(live.copy(busy=true).canLaunch)
        assertFalse(live.copy(observedAt=System.nanoTime()-7_000_000_000L).canLaunch)
        assertFalse(RemotePlaybackState(target=target).canLaunch)
    }
    @Test fun onlyReceiverSessionHasActiveVideo() {
        assertFalse(RemoteVideo(status="playing").active)
        val video=RemoteVideo(session="receiver-session",status="loading")
        assertTrue(video.active)
        assertTrue(video.copy(status="playing").active)
        assertTrue(video.copy(status="paused").active)
        for(status in listOf("idle","ended","error"))assertFalse(video.copy(status=status).active)
    }
}
