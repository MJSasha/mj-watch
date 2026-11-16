package dev.mjwatch.playback

import org.junit.Assert.*
import org.junit.Test

class TvPlaybackStateTest {
    @Test fun previewIsClampedAcceleratedAndOnlyCommittedExplicitly() {
        val state=TvPlaybackState()
        state.previewSeek(50_000,1_000_000,1,0,10_000)
        assertEquals(60_000L,state.preview)
        state.previewSeek(50_000,1_000_000,1,10,10_000)
        assertEquals(90_000L,state.preview)
        state.previewSeek(50_000,1_000_000,1,20,10_000)
        assertEquals(150_000L,state.confirmSeek())
        assertNull(state.preview)
        state.previewSeek(5_000,100_000,-1,0,10_000)
        assertEquals(0L,state.preview)
        state.cancelSeek()
        assertNull(state.confirmSeek())
        state.previewSeek(99_000,100_000,1,0,10_000)
        assertEquals(100_000L,state.preview)
    }
    @Test fun inactivityTimerIsBlockedByModalAndPreview() {
        val s=TvPlaybackState();s.interact(1000)
        assertFalse(s.shouldHide(6999,false));assertTrue(s.shouldHide(7000,false))
        assertFalse(s.shouldHide(10_000,true))
        s.previewSeek(0,100_000,1,0,10_000)
        assertFalse(s.shouldHide(10_000,false))
        s.cancelSeek();s.interact(10_000)
        assertFalse(s.shouldHide(15_999,false))
    }
    @Test fun markersAreValidatedAndEndingFallsBackToThirtySeconds() {
        assertNull(markerTarget(null,null,20_000,100_000))
        assertNull(markerTarget(10_000,200_000,20_000,100_000))
        assertNull(markerTarget(30_000,10_000,20_000,100_000))
        assertEquals(30_000L,markerTarget(10_000,30_000,20_000,100_000))
        assertNull(markerTarget(10_000,30_000,30_000,100_000))
        assertEquals(970_000L,nextThreshold(null,1_000_000))
        assertEquals(900_000L,nextThreshold(900_000,1_000_000))
        assertEquals(970_000L,nextThreshold(700_000,1_000_000))
        assertEquals(970_000L,nextThreshold(1_000_000,1_000_000))
        assertEquals(0L,nextThreshold(null,20_000))
    }
    @Test fun autoNextCountsPlaybackPausesForPanelsAndSeeksAndTransitionsOnlyOnce() {
        val s=TvPlaybackState()
        fun update(p:Long,playing:Boolean=true,blocked:Boolean=false)=s.update(p,100_000,playing,null,true,blocked,true)
        assertFalse(update(70_000));assertEquals("next",s.prompt)
        assertFalse(update(75_000));assertEquals(5000L,s.nextElapsed)
        assertFalse(update(75_000,false));assertFalse(update(75_000,false))
        assertEquals(5000L,s.nextElapsed)
        assertFalse(update(75_000));assertFalse(update(77_000,blocked=true))
        assertFalse(update(77_000));assertEquals(5000L,s.nextElapsed)
        s.discontinuity();assertFalse(update(90_000));assertEquals(5000L,s.nextElapsed)
        assertTrue(update(95_000));assertFalse(update(99_000))
    }
    @Test fun stayCancelsCurrentTailVisitAndLastEpisodeNeverAdvances() {
        val s=TvPlaybackState()
        fun update(p:Long,hasNext:Boolean=true,auto:Boolean=true)=s.update(p,100_000,true,null,hasNext,false,auto)
        update(70_000);s.dismissPrompt();assertFalse(update(95_000));assertNull(s.prompt)
        update(10_000);update(70_000);assertEquals("next",s.prompt)
        assertFalse(update(99_000,auto=false))
        s.reset();assertFalse(update(99_000,hasNext=false));assertNull(s.prompt)
    }
    @Test fun openingDismissalResetsAfterLeavingItsMarkedRange() {
        val s=TvPlaybackState();val e=PlaybackEpisode("asset:///demo.mp4","1",1,openingStartMs=10_000,openingEndMs=30_000)
        fun update(p:Long)=s.update(p,100_000,true,e,true,false,true)
        update(20_000);assertEquals("opening",s.prompt)
        s.dismissPrompt();update(21_000);assertNull(s.prompt)
        update(35_000);update(20_000);assertEquals("opening",s.prompt)
    }
}
