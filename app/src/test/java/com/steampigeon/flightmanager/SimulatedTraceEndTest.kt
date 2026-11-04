package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.ui.endsSimulatedTrace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A simulated flight stops tracing at its landing; a real one keeps tracing,
 * since wind can carry a landed rocket (Frank, 2026-11-04).
 */
class SimulatedTraceEndTest {
    @Test fun aSimulatedLandingEndsTheTrace() = assertTrue(endsSimulatedTrace(FlightStates.Landed, true))
    @Test fun aRealLandingKeepsTracing() = assertFalse(endsSimulatedTrace(FlightStates.Landed, false))
    @Test fun aSimulatedFlightTracesUntilLanding() {
        for (s in FlightStates.entries.filter { it != FlightStates.Landed })
            assertFalse(s.name, endsSimulatedTrace(s, true))
    }
}
