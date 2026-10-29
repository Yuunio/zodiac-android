package com.example.witches.audio

import org.junit.Assert.*
import org.junit.Test

class MusicPolicyTest {
    @Test fun asksForFocusOnlyWhenAudibleAndStopsInBackground() {
        val policy = MusicPolicy()
        assertFalse(policy.update(MusicTrack.MENU, true, .35f, false).requestFocus)
        assertTrue(policy.update(MusicTrack.MENU, true, .35f, true).requestFocus)
        assertTrue(policy.focusResult(true).play)
        repeat(20) {
            val unchanged = policy.update(MusicTrack.MENU, true, .35f, true)
            assertTrue(unchanged.play)
            assertFalse(unchanged.requestFocus)
        }
        val stopped = policy.update(MusicTrack.MENU, true, .35f, false)
        assertFalse(stopped.play)
        assertTrue(stopped.abandonFocus)
        assertTrue(policy.update(MusicTrack.GAME, true, .35f, true).requestFocus)
    }

    @Test fun deniedFocusIsNotRequestedOnEachUpdateOrTrackChange() {
        val policy = MusicPolicy()
        policy.update(MusicTrack.MENU, true, .35f, true)
        policy.focusResult(false)
        repeat(20) { assertFalse(policy.update(MusicTrack.GAME, true, .5f, true).requestFocus) }
        policy.update(MusicTrack.GAME, false, .5f, true)
        assertTrue(policy.update(MusicTrack.GAME, true, .5f, true).requestFocus)
    }

    @Test fun transientFocusLossWaitsForGainWithoutRequestingAgain() {
        val policy = MusicPolicy()
        policy.update(MusicTrack.MENU, true, .35f, true)
        policy.focusResult(true)
        val loss = policy.focusLost(temporary = true)
        assertFalse(loss.play)
        assertFalse(loss.abandonFocus)
        assertFalse(policy.update(MusicTrack.MENU, true, .35f, true).requestFocus)
        assertTrue(policy.focusGained().play)
    }

    @Test fun unplugAndPermanentLossRequireAnExplicitNewActivation() {
        for (unplug in listOf(true, false)) {
            val policy = MusicPolicy()
            policy.update(MusicTrack.GAME, true, .35f, true)
            policy.focusResult(true)
            val loss = if (unplug) policy.interrupt() else policy.focusLost(temporary = false)
            assertFalse(loss.play)
            assertTrue(loss.abandonFocus)
            assertFalse(policy.update(MusicTrack.GAME, true, .35f, true).play)
            assertFalse(policy.focusGained().play)
            policy.update(MusicTrack.GAME, true, .35f, false)
            assertTrue(policy.update(MusicTrack.GAME, true, .35f, true).requestFocus)
        }
    }

    @Test fun invalidOrZeroVolumeDoesNotAcquireFocus() {
        val policy = MusicPolicy()
        for (volume in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f)) {
            assertFalse(policy.update(MusicTrack.MENU, true, volume, true).requestFocus)
            assertEquals(0f, policy.request.volume, 0f)
        }
        assertTrue(policy.update(MusicTrack.MENU, true, 2f, true).requestFocus)
        assertEquals(1f, policy.request.volume, 0f)
        policy.focusResult(true)
        assertTrue(policy.update(MusicTrack.MENU, false, 1f, true).abandonFocus)
    }
}
