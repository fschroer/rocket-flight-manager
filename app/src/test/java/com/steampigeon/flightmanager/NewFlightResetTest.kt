package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.ui.startsNewFlight
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the recorded flight path (and the 3D altitude curtain drawn from it) is
 * cleared for a new flight.
 *
 * The rule is any grounded -> airborne transition rather than the
 * WaitingLaunch -> Launched edge specifically. The narrower rule needs the whole
 * Launched window to survive, and at the locator's actual 1 Hz that window is only
 * a few frames — one, on a short boost — so losing it is ordinary rather than
 * exotic. (An earlier version of this comment said 5 Hz and called the loss a
 * bad-but-possible case; see `startsNewFlight`.) A missed reset leaves the
 * previous flight's track drawn under the new one for the whole flight.
 */
class NewFlightResetTest {

    @Test
    fun theLaunchEdgeResets() {
        assertTrue(startsNewFlight(FlightStates.WaitingLaunch, FlightStates.Launched))
    }

    @Test
    fun aLostBoostWindowStillResets() {
        // Every Launched packet lost — the whole boost. Whichever ascent state is
        // the first one actually heard has to trigger the reset.
        assertTrue(startsNewFlight(FlightStates.WaitingLaunch, FlightStates.Burnout))
        assertTrue(startsNewFlight(FlightStates.WaitingLaunch, FlightStates.Noseover))
        assertTrue(startsNewFlight(FlightStates.WaitingLaunch, FlightStates.MainPrimaryEvent))
    }

    @Test
    fun launchingStraightFromTheLastFlightsLandedStateResets() {
        // A locator re-armed for a second flight can go airborne without the app
        // ever seeing a WaitingLaunch packet in between.
        assertTrue(startsNewFlight(FlightStates.Landed, FlightStates.Launched))
        assertTrue(startsNewFlight(FlightStates.Landed, FlightStates.Burnout))
    }

    @Test
    fun continuingAFlightDoesNotReset() {
        // The path must accumulate across the whole flight; resetting mid-flight
        // would erase the track while the rocket is still in the air.
        assertFalse(startsNewFlight(FlightStates.Launched, FlightStates.Burnout))
        assertFalse(startsNewFlight(FlightStates.Burnout, FlightStates.Noseover))
        assertFalse(startsNewFlight(FlightStates.Noseover, FlightStates.DroguePrimaryEvent))
        assertFalse(startsNewFlight(FlightStates.DroguePrimaryEvent, FlightStates.MainPrimaryEvent))
    }

    @Test
    fun landingDoesNotReset() {
        // The landed track is the recovery aid — it stays on screen until the
        // next flight starts or the user resets it by hand.
        assertFalse(startsNewFlight(FlightStates.MainPrimaryEvent, FlightStates.Landed))
        assertFalse(startsNewFlight(FlightStates.Landed, FlightStates.Landed))
        assertFalse(startsNewFlight(FlightStates.Landed, FlightStates.WaitingLaunch))
    }

    @Test
    fun sittingOnThePadDoesNotReset() {
        // Armed and waiting: repeated WaitingLaunch packets must not keep firing
        // a reset (harmless for the path, but it also drops the archived-track
        // display back to live).
        assertFalse(startsNewFlight(FlightStates.WaitingLaunch, FlightStates.WaitingLaunch))
    }

    @Test
    fun anUnrecognizedStateMidFlightDoesNotReset() {
        // NoSignal is what FlightStates.fromUByte returns for ANY state byte the
        // app does not know, so a state added to the firmware later decodes as
        // NoSignal on an older app. If that counted as grounded, the next real
        // packet would read as a fresh launch and erase the track of the flight
        // still in the air — the one moment the path matters most.
        assertFalse(startsNewFlight(FlightStates.NoSignal, FlightStates.Burnout))
        assertFalse(startsNewFlight(FlightStates.NoSignal, FlightStates.Launched))
    }
}
