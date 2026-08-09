package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.data.SensorHealth
import com.steampigeon.flightmanager.ui.distanceIsPlausible
import com.steampigeon.flightmanager.ui.distanceWithinRadioRange
import com.steampigeon.flightmanager.ui.locatorHasFix
import com.steampigeon.flightmanager.ui.maxGroundSpeedMs
import com.steampigeon.flightmanager.ui.phaseTravelM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rejecting distances to the locator that cannot be true.
 *
 * The reading that prompted this was **779070 m reported with zero satellites** —
 * a locator 779 km away, from a radio whose telemetry was arriving. Both halves
 * of that are wrong, and each is caught on its own: a distance beyond radio range
 * is impossible whatever the locator claims, and a position measured with no fix
 * is not a measurement.
 *
 * The thing NOT to do is blank every distance the moment the fix degrades. A
 * locator that loses its fix on the ground keeps reporting the last position it
 * did measure, and that stale distance is what the user walks toward — the same
 * reason ADR-0017 grays a degraded fix on the map instead of hiding it. So a
 * fixless reading is rejected on having *jumped*, not on being fixless.
 */
class DistancePlausibilityTest {

    private companion object {
        const val NEARBY = 450          // a rocket in the next field
        const val ONE_SECOND = 1_000L
    }

    /**
     * The budget a fixless stretch opens up: [seconds] spent in [state], as the
     * ViewModel accumulates it one update at a time.
     */
    private fun budget(state: FlightStates, seconds: Int) =
        (1..seconds).sumOf { phaseTravelM(state, ONE_SECOND) }

    // ── The range ceiling ────────────────────────────────────────────────────

    @Test
    fun theReportedFailureIsRejected() {
        // 779 km, zero satellites. Rejected on range alone, before the fix state
        // is even consulted.
        assertFalse(distanceWithinRadioRange(779_070))
        assertFalse(
            distanceIsPlausible(
                distanceM = 779_070,
                locatorHasFix = false,
                lastFixDistanceM = NEARBY,
                travelBudgetM = budget(FlightStates.Landed, 1),
            )
        )
    }

    @Test
    fun aGoodFixCannotVouchForAnImpossibleDistance() {
        // Satellites do not make it reachable: if the packet arrived, the locator
        // is within radio range, and the coordinates say otherwise.
        assertFalse(
            distanceIsPlausible(
                distanceM = 779_070,
                locatorHasFix = true,
                lastFixDistanceM = null,
                travelBudgetM = 0.0,
            )
        )
    }

    @Test
    fun theCeilingCannotFireOnARealFlight() {
        // Recovery distances are hundreds of meters to a few km; the ceiling sits
        // several times past practical LoRa range, so nothing flyable reaches it.
        assertTrue(distanceWithinRadioRange(0))
        assertTrue(distanceWithinRadioRange(2_000))
        assertTrue(distanceWithinRadioRange(20_000))
    }

    @Test
    fun aNegativeDistanceIsRejected() {
        // Not reachable through the great-circle maths, but the display formats
        // whatever it is handed and a negative distance would render as one.
        assertFalse(distanceWithinRadioRange(-1))
    }

    // ── What counts as a fix ─────────────────────────────────────────────────

    @Test
    fun fewerThanFourSatellitesIsNotAFix() {
        // Four is what a 3D fix takes, so fewer cannot have produced the position
        // in the packet — whether the count means satellites used or in view.
        assertFalse(locatorHasFix(0, SensorHealth.Ok))
        assertFalse(locatorHasFix(3, SensorHealth.Ok))
        assertTrue(locatorHasFix(4, SensorHealth.Ok))
        assertTrue(locatorHasFix(11, SensorHealth.Ok))
    }

    @Test
    fun theLocatorSayingItsGpsIsUnhealthyIsNotAFix() {
        // Plenty of satellites and a module reporting itself sick: the position is
        // latched, not current. Stale is also what fromUByte falls back to for a
        // health byte the app does not recognize.
        assertFalse(locatorHasFix(11, SensorHealth.Error))
        assertFalse(locatorHasFix(11, SensorHealth.Stale))
    }

    // ── The jump test ────────────────────────────────────────────────────────

    @Test
    fun aStaleDistanceThatHasNotMovedIsStillShown() {
        // The case that must NOT be blanked: fix lost on the ground, position
        // latched at the last real measurement. That number is the recovery aid.
        repeat(60) { second ->
            assertTrue(
                "blanked after $second s of held position",
                distanceIsPlausible(
                    distanceM = NEARBY,
                    locatorHasFix = false,
                    lastFixDistanceM = NEARBY,
                    travelBudgetM = budget(FlightStates.Landed, second),
                )
            )
        }
    }

    @Test
    fun gpsNoiseAroundAStationaryRocketIsNotAJump() {
        // A latched position still wanders a few meters between reports, and with
        // no elapsed time the budget is zero — the noise margin has to carry it.
        assertTrue(
            distanceIsPlausible(
                distanceM = NEARBY + 40,
                locatorHasFix = false,
                lastFixDistanceM = NEARBY,
                travelBudgetM = 0.0,
            )
        )
    }

    @Test
    fun aFixlessPositionThatTeleportsIsRejected() {
        // Inside the range ceiling, so the ceiling cannot catch this one: 40 km
        // one second after a fix that had the rocket 450 m away.
        assertFalse(
            distanceIsPlausible(
                distanceM = 40_000,
                locatorHasFix = false,
                lastFixDistanceM = NEARBY,
                travelBudgetM = budget(FlightStates.MainPrimaryEvent, 1),
            )
        )
    }

    @Test
    fun realMovementDuringALinkGapIsNotAJump() {
        // Out of contact for a minute of descent, then heard from again with the
        // fix not yet reacquired. The rocket genuinely covered ground, and the
        // budget has to grow with the gap or the first packet back is discarded.
        assertTrue(
            distanceIsPlausible(
                distanceM = 4_000,
                locatorHasFix = false,
                lastFixDistanceM = NEARBY,
                travelBudgetM = budget(FlightStates.MainPrimaryEvent, 60),
            )
        )
    }

    @Test
    fun theFirstReadingHasNothingToBeJudgedAgainst() {
        // No fix has ever been had, so there is no anchor. Only the ceiling
        // applies — a believable distance is shown rather than withheld until the
        // locator manages a fix.
        assertTrue(
            distanceIsPlausible(
                distanceM = NEARBY,
                locatorHasFix = false,
                lastFixDistanceM = null,
                travelBudgetM = 0.0,
            )
        )
    }

    @Test
    fun aFixVouchesForAJumpTheJumpTestWouldReject() {
        // With satellites, the position IS a measurement. A big move with a good
        // fix is a rocket that moved, and the app has no standing to argue.
        assertTrue(
            distanceIsPlausible(
                distanceM = 40_000,
                locatorHasFix = true,
                lastFixDistanceM = NEARBY,
                travelBudgetM = 0.0,
            )
        )
    }

    // ── The phase bounds ─────────────────────────────────────────────────────

    @Test
    fun theBoundsAreOnGroundSpeedNotAirspeed() {
        // The calibration that is easy to get wrong later. locatorVector is a
        // haversine over lat/lon with no altitude term, so the distance moves only
        // with the ground track: a Mach 5 boost is Mach 5 straight up and barely
        // registers here. 400 m/s covers the horizontal component of even a badly
        // weathercocked flight, and a bound sized for airspeed instead would be
        // four times looser than the measurement can justify.
        assertEquals(400.0, maxGroundSpeedMs(FlightStates.Launched), 0.5)
        assertEquals(400.0, maxGroundSpeedMs(FlightStates.Burnout), 0.5)
    }

    @Test
    fun theBoostAllowanceEndsAtApogee() {
        // The point of splitting the bound: everything after the coast is held to
        // a descent rate, so boost's allowance cannot be spent drifting under a
        // canopy. 200 m/s is far past wind drift — it is sized for a failed
        // deployment coming down ballistic on the horizontal momentum it had at
        // apogee.
        assertEquals(200.0, maxGroundSpeedMs(FlightStates.Noseover), 0.5)
        assertEquals(200.0, maxGroundSpeedMs(FlightStates.DroguePrimaryEvent), 0.5)
        assertEquals(200.0, maxGroundSpeedMs(FlightStates.MainBackupEvent), 0.5)
    }

    @Test
    fun agroundedRocketIsHeldToWalkingPace() {
        // The looseness this split existed to fix. On the ground the old single
        // bound allowed ~12 km of movement across 30 s without a fix; walking
        // pace allows ~150 m, so a jump on the ground is now caught by the jump
        // test rather than only by the range ceiling.
        assertEquals(5.0, maxGroundSpeedMs(FlightStates.WaitingLaunch), 0.01)
        assertEquals(5.0, maxGroundSpeedMs(FlightStates.Landed), 0.01)
        assertFalse(
            distanceIsPlausible(
                distanceM = 12_000,
                locatorHasFix = false,
                lastFixDistanceM = NEARBY,
                travelBudgetM = budget(FlightStates.Landed, 30),
            )
        )
    }

    @Test
    fun anUnrecognizedStateIsJudgedPermissively() {
        // NoSignal is the fallback for any state byte the app does not know, so a
        // state added to the firmware later decodes to it on an older app.
        // Blanking a distance on the strength of a state we failed to parse is the
        // wrong direction to fail in.
        assertEquals(400.0, maxGroundSpeedMs(FlightStates.NoSignal), 0.5)
    }

    @Test
    fun aBudgetIsCountedPhaseByPhaseAcrossAGap() {
        // A fixless stretch that starts under canopy and ends on the ground: the
        // descent seconds are charged at descent rate and the ground seconds at
        // walking pace. Charging the whole gap at either one gets it wrong in a
        // different direction — 2 km of real flight read as a jump, or a rocket
        // in a field licensed to have crossed a county.
        val mixed = budget(FlightStates.MainPrimaryEvent, 20) + budget(FlightStates.Landed, 40)
        assertTrue("descent undercharged", mixed > budget(FlightStates.Landed, 60))
        assertTrue("ground overcharged", mixed < budget(FlightStates.MainPrimaryEvent, 60))
    }

    @Test
    fun aBudgetNeverRunsBackwards() {
        // Wall-clock intervals, so a clock adjustment can hand this a negative one.
        assertTrue(phaseTravelM(FlightStates.Burnout, -5_000L) >= 0.0)
    }
}
