package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.ui.landedThroughBlackout
import com.steampigeon.flightmanager.ui.landingImminent
import com.steampigeon.flightmanager.ui.timeToGroundSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app decides the rocket has landed.
 *
 * The case that matters is the one observed in the field: the link dies in the
 * last few hundred metres, because that is where line of sight to a rocket
 * across a field runs out, and it does not come back until someone walks toward
 * it. A landing callout that waits to *hear* the touchdown therefore almost
 * never fires — which is the bug these tests exist to keep fixed.
 *
 * The opposing risk is real too: concluding a flight is not a decision that can
 * be taken back, so a routine dropout must not trigger it.
 */
class LandingCalloutTest {

    @Test
    fun timeToGroundIsAltitudeOverRate() {
        assertEquals(20f, timeToGroundSeconds(100f, 5f), 1e-3f)
    }

    @Test
    fun aNegligibleDescentRateNeverPredictsAGround() {
        // Ascending, hovering, or noise around zero: dividing by it would produce
        // a huge or negative time, and treating either as a landing would end the
        // flight while the rocket is still climbing.
        assertEquals(Float.MAX_VALUE, timeToGroundSeconds(100f, 0f), 0f)
        assertEquals(Float.MAX_VALUE, timeToGroundSeconds(100f, -30f), 0f)
        assertEquals(Float.MAX_VALUE, timeToGroundSeconds(100f, 0.5f), 0f)
    }

    @Test
    fun landingIsImminentNearTheGroundOrSecondsFromIt() {
        assertTrue("below the altitude floor", landingImminent(20f, 5f))
        assertTrue("two seconds out", landingImminent(10f, 5f))
        assertFalse("still under canopy at 300 m", landingImminent(300f, 5f))
    }

    @Test
    fun aRoutineDropoutDoesNotConcludeTheFlight() {
        // 3 s gaps happen constantly. At 300 m under a main there is a minute of
        // flight left, and calling it landed here would suppress every callout
        // for the rest of a flight still in progress.
        assertFalse(landedThroughBlackout(300f, 5f, 3_000L))
        assertFalse(landedThroughBlackout(300f, 5f, 10_000L))
        assertFalse(landedThroughBlackout(300f, 5f, 30_000L))
    }

    @Test
    fun theRocketIsDownOnceItHasHadTimeToGetThere() {
        // The fix: link lost at 300 m descending 5 m/s — 60 s to the ground. The
        // app hears nothing more, and must conclude the landing on its own.
        assertFalse("too early at 59 s", landedThroughBlackout(300f, 5f, 59_000L))
        assertTrue("landed by 61 s", landedThroughBlackout(300f, 5f, 61_000L))
    }

    @Test
    fun aLinkLostLowStillConcludesQuickly() {
        // Lost at 40 m: under 10 s of flight left, so the blackout floor is what
        // governs rather than the descent time.
        assertFalse(landedThroughBlackout(40f, 5f, 4_000L))
        assertTrue(landedThroughBlackout(40f, 5f, 9_000L))
    }

    @Test
    fun aLinkLostAlreadyOnTheGroundConcludesAtTheFloor() {
        // Below the altitude threshold the last known position IS the landing
        // site, so only the dropout floor has to elapse.
        assertFalse(landedThroughBlackout(10f, 5f, 4_000L))
        assertTrue(landedThroughBlackout(10f, 5f, 5_000L))
    }

    @Test
    fun aBlackoutFromHighUnderDrogueTakesTheWholeDescent() {
        // Lost at apogee, 3000 m, descending 25 m/s under drogue: 120 s. The
        // callout has to wait it out rather than firing on the dropout alone —
        // announcing a landing two minutes early would send the user walking to
        // a point the rocket has not reached.
        assertFalse(landedThroughBlackout(3000f, 25f, 30_000L))
        assertFalse(landedThroughBlackout(3000f, 25f, 119_000L))
        assertTrue(landedThroughBlackout(3000f, 25f, 121_000L))
    }

    @Test
    fun aBlackoutWithNoUsableDescentRateNeverConcludes() {
        // No descent rate to reckon with (hung in a tree, or the last packet
        // caught it at a rate near zero). Silence is correct: the app has no
        // basis for saying where or whether it came down.
        assertFalse(landedThroughBlackout(500f, 0f, 600_000L))
    }
}
