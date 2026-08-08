package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.ui.landingConcluded
import com.steampigeon.flightmanager.ui.recordsPathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the drawn flight path stops.
 *
 * The track exists to be walked to, and its last few metres were the least
 * useful part of it: the app kept appending fixes while the rocket lay in the
 * grass, so the end of the path — the bit the user zooms into — was a scribble of
 * GPS wander around the landing site rather than a point.
 *
 * So drawing stops when the app concludes the flight is over, the locator's own
 * first Landed report is allowed through to correct where that point sits, and
 * the recording ends there for the flight.
 */
class LandingPathFreezeTest {

    /** Drives a sequence of fixes through the same rules the recorder applies. */
    private class Recorder {
        var concluded = false
        var landedSeen = false
        val points = mutableListOf<String>()

        /** Records one fix, labelled so the resulting track is readable. */
        fun fix(state: FlightStates, agl: Float, descentRate: Float, label: String) {
            val records = recordsPathPoint(state, concluded, landedSeen)
            if (landingConcluded(state, agl, descentRate)) concluded = true
            if (state == FlightStates.Landed) landedSeen = true
            if (records) points += label
        }
    }

    @Test
    fun theWholeFlightIsDrawn() {
        val r = Recorder()
        r.fix(FlightStates.Launched, 50f, -200f, "boost")
        r.fix(FlightStates.Burnout, 900f, -80f, "coast")
        r.fix(FlightStates.Noseover, 1500f, 0f, "apogee")
        r.fix(FlightStates.DroguePrimaryEvent, 1400f, 25f, "drogue")
        r.fix(FlightStates.MainPrimaryEvent, 250f, 5f, "main")
        assertEquals(listOf("boost", "coast", "apogee", "drogue", "main"), r.points)
    }

    @Test
    fun theFixThatConcludesTheFlightIsStillDrawn() {
        // It is the lowest and last-known position — the single most useful point
        // on the track. Deciding against what was known before the fix arrived is
        // what keeps it.
        val r = Recorder()
        r.fix(FlightStates.MainPrimaryEvent, 200f, 5f, "under canopy")
        r.fix(FlightStates.MainPrimaryEvent, 12f, 5f, "touching down")
        assertEquals(listOf("under canopy", "touching down"), r.points)
        assertTrue(r.concluded)
    }

    @Test
    fun nothingIsDrawnBetweenTheInferredLandingAndTheLocatorConfirmingIt() {
        // The locator goes on transmitting the descent state for a while after the
        // rocket is down. Those fixes are the wander this exists to stop.
        val r = Recorder()
        r.fix(FlightStates.MainPrimaryEvent, 12f, 5f, "touching down")
        repeat(20) { i -> r.fix(FlightStates.MainPrimaryEvent, 0.4f, 0f, "wander $i") }
        assertEquals(listOf("touching down"), r.points)
    }

    @Test
    fun theLocatorsFirstLandedFixIsDrawnAndIsTheLast() {
        // The app's inference is a prediction made from the last fix before
        // touchdown; the locator's Landed report is where the rocket actually is,
        // so it draws even though the path was frozen. Everything after it is a
        // stationary rocket transmitting from a field — the flight is over and so
        // is the recording.
        val r = Recorder()
        r.fix(FlightStates.MainPrimaryEvent, 12f, 5f, "predicted")
        r.fix(FlightStates.Landed, 0.3f, 0f, "reported")
        repeat(200) { i -> r.fix(FlightStates.Landed, 0.3f, 0f, "later $i") }
        assertEquals(listOf("predicted", "reported"), r.points)
    }

    @Test
    fun aLandingHeardBeforeItIsInferredStillEndsInOnePoint() {
        // The link drops under canopy and comes back with the rocket already down,
        // so the first thing heard about the landing is the locator's own report —
        // the inference never fires. That must not leave the ground fixes
        // accreting.
        val r = Recorder()
        r.fix(FlightStates.DroguePrimaryEvent, 900f, 25f, "last seen")
        repeat(50) { i -> r.fix(FlightStates.Landed, 0.3f, 0f, "reported $i") }
        assertEquals(listOf("last seen", "reported 0"), r.points)
    }

    @Test
    fun aLandedFixEndsTheRecordingEvenWhenItIsNotDrawn() {
        // The de-duplicator drops a Landed fix identical to the point already
        // ending the path. The flight is over all the same — the flag is set on
        // the status being received, not on a point being appended, so a later
        // Landed fix that HAS drifted a metre cannot reopen the recording.
        val r = Recorder()
        r.fix(FlightStates.MainPrimaryEvent, 12f, 5f, "touching down")
        // Stands in for the dedup rejecting it: the recorder was willing, the
        // point was a repeat, and the flag is set regardless.
        assertTrue(recordsPathPoint(FlightStates.Landed, r.concluded, r.landedSeen))
        r.landedSeen = true
        repeat(20) { i -> r.fix(FlightStates.Landed, 0.3f + i * 0.01f, 0f, "drifted $i") }
        assertEquals(listOf("touching down"), r.points)
    }

    @Test
    fun aFrameAfterALongGapThatStillReportsDescentIsDrawn() {
        // The recorder has no blackout arm on purpose: silence records nothing, so
        // there is nothing for one to stop, and a rocket that reports 600 m on the
        // way down after a minute of quiet is still flying. Freezing the path here
        // would drop the whole second half of the descent.
        assertFalse(landingConcluded(FlightStates.MainPrimaryEvent, 600f, 5f))
        assertTrue(
            recordsPathPoint(
                FlightStates.MainPrimaryEvent,
                landingConcluded = false,
                landedStatusReceived = false,
            )
        )
    }

    @Test
    fun sittingOnThePadIsNotALanding() {
        // AGL is ~0 and the descent rate is noise around zero for as long as the
        // rocket is armed and waiting — the imminent-landing test on its own would
        // read that as a touchdown and freeze the path before the flight.
        assertFalse(landingConcluded(FlightStates.WaitingLaunch, 0.2f, 0.1f))
        assertFalse(landingConcluded(FlightStates.WaitingLaunch, 0.2f, -0.1f))
        // ...and boost, which passes through low altitude going the other way.
        assertFalse(landingConcluded(FlightStates.Launched, 15f, -180f))
    }

    @Test
    fun nothingIsDrawnBeforeLaunch() {
        assertFalse(
            recordsPathPoint(
                FlightStates.WaitingLaunch,
                landingConcluded = false,
                landedStatusReceived = false,
            )
        )
    }

    @Test
    fun descentUnderCanopyIsNotALanding() {
        // The rule has to survive the long, slow part of the descent: 5 m/s under
        // main from altitude is minutes of flight, all of it worth drawing.
        assertFalse(landingConcluded(FlightStates.MainPrimaryEvent, 300f, 5f))
        assertFalse(landingConcluded(FlightStates.DroguePrimaryEvent, 1200f, 25f))
        // Three seconds out is where it flips (landingLeadTimeSeconds).
        assertTrue(landingConcluded(FlightStates.MainPrimaryEvent, 14f, 5f))
        // As does the altitude floor, whatever the rate is doing.
        assertTrue(landingConcluded(FlightStates.MainPrimaryEvent, 20f, 0f))
    }

    @Test
    fun anUnrecognisedStateAfterALandingDoesNotResumeDrawing() {
        // NoSignal is what any state byte the app does not know decodes to, and it
        // sorts above Landed. It must not read as "flying again" and start
        // appending ground fixes to a concluded flight.
        assertFalse(
            recordsPathPoint(
                FlightStates.NoSignal,
                landingConcluded = true,
                landedStatusReceived = true,
            )
        )
        // ...nor when only the app's inference has fired.
        assertFalse(
            recordsPathPoint(
                FlightStates.NoSignal,
                landingConcluded = true,
                landedStatusReceived = false,
            )
        )
    }
}
