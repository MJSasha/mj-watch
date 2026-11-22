package dev.mjwatch.playback

import dev.mjwatch.core.SegmentKind
import dev.mjwatch.core.SkipSegment
import org.junit.Assert.*
import org.junit.Test

class SegmentSkipStateTest {
    private val recap = SkipSegment(SegmentKind.RECAP, 1_000, 30_000)
    private val preview = SkipSegment(SegmentKind.PREVIEW, 50_000, 60_000)

    @Test fun countdownUsesActiveTimeAndCanBeDisabled() {
        val state = SegmentSkipState()
        var now = 0L
        fun tick(playing: Boolean = true, blocked: Boolean = false, automatic: Boolean = true) {
            state.update(listOf(recap), 10_000, 100_000, playing, blocked, automatic, now)
            now += 250
        }
        repeat(9) { tick() }
        assertEquals(3_000L, state.offer?.remainingMs)
        repeat(24) { tick(playing = false) }
        assertEquals(3_000L, state.offer?.remainingMs)
        tick(blocked = true)
        assertNull(state.offer)
        repeat(13) { tick() }
        assertEquals(0L, state.offer?.remainingMs)
        tick(automatic = false)
        assertNull(state.offer?.remainingMs)
        assertEquals(recap.endMs, state.takeTarget(10_000, 100_000))
    }

    @Test fun skippingCreditsAdvancesOnceOrSeeksWhenThereIsNoNextEpisode() {
        val state = SegmentSkipState()
        val credits = SkipSegment(SegmentKind.OUTRO, 70_000, 90_000)
        fun showCredits() {
            state.update(listOf(credits), 75_000, 100_000, true, false, false, 0)
        }
        var advances = 0
        showCredits()
        assertNull(state.takeTarget(75_000, 100_000) { advances++; true })
        assertNull(state.takeTarget(75_000, 100_000) { advances++; true })
        assertEquals(1, advances)
        state.reset()
        showCredits()
        assertEquals(90_000L, state.takeTarget(75_000, 100_000) { false })
    }

    @Test fun perKindDisableDismissalAndSeeking() {
        val state = SegmentSkipState()
        fun update(position: Long, enabled: (SegmentKind) -> Boolean = { true }) =
            state.update(listOf(recap, preview), position, 100_000, true, false, false, 0, enabled)
        update(10_000) { it != SegmentKind.RECAP }
        assertNull(state.offer)
        update(55_000)
        assertEquals(SegmentKind.PREVIEW, state.offer?.segment?.kind)
        assertNull(state.takeTarget(40_000, 100_000))
        state.dismiss()
        update(56_000)
        assertNull(state.offer)
        update(40_000)
        update(56_000)
        assertEquals(preview.endMs, state.takeTarget(56_000, 100_000) { error("Preview must not advance the queue") })
    }
}
