package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.LocatorConnection
import com.steampigeon.flightmanager.ui.RocketViewModel.Companion.CONNECTION_HOLD_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection is single-holder and sticky.
 *
 * The bug this pins: two authorized locators in earshot (both open, or both in the
 * known-locator store) each rewrote the connection as their packets arrived, so the
 * display alternated between two rockets at the 1 Hz broadcast rate.  Being
 * authorized is permission to connect, not a claim on the connection.
 *
 * Reachable at close range even with the locators on *different* LoRa channels: at
 * 200 kHz spacing against a 125 kHz bandwidth, a 22 dBm locator a few feet from the
 * receiver arrives ~110 dB above sensitivity and walks straight through the channel
 * filter, and the receiver stamps its own channel on everything it relays.
 */
class LocatorConnectionTest {

    private val ours = 0x1122_3344L
    private val theirs = 0x5566_7788L

    @Test
    fun `free slot is claimed`() {
        assertTrue(LocatorConnection.mayConnect(null, ours, 0L, CONNECTION_HOLD_MS))
    }

    @Test
    fun `holder refreshes its own connection`() {
        assertTrue(LocatorConnection.mayConnect(ours, ours, 0L, CONNECTION_HOLD_MS))
        // Still ours after a long silence — a returning locator reclaims, it does
        // not have to be re-selected.
        assertTrue(LocatorConnection.mayConnect(ours, ours, 60_000L, CONNECTION_HOLD_MS))
    }

    @Test
    fun `second authorized locator cannot take a live connection`() {
        assertFalse(LocatorConnection.mayConnect(ours, theirs, 0L, CONNECTION_HOLD_MS))
        // One missed broadcast is not a release.
        assertFalse(LocatorConnection.mayConnect(ours, theirs, 1_000L, CONNECTION_HOLD_MS))
        // Nor is a fade that outlasts the 5 s "link up" test used elsewhere.
        assertFalse(LocatorConnection.mayConnect(ours, theirs, 5_001L, CONNECTION_HOLD_MS))
        assertFalse(LocatorConnection.mayConnect(ours, theirs, CONNECTION_HOLD_MS - 1, CONNECTION_HOLD_MS))
    }

    @Test
    fun `connection releases once the holder has gone silent`() {
        assertTrue(LocatorConnection.mayConnect(ours, theirs, CONNECTION_HOLD_MS, CONNECTION_HOLD_MS))
        assertTrue(LocatorConnection.mayConnect(ours, theirs, 60_000L, CONNECTION_HOLD_MS))
    }

    @Test
    fun `hold is long enough to ride out a burst of dropped broadcasts`() {
        // Broadcasts are 1 Hz, so the hold must cover several consecutive misses;
        // releasing early is how another rocket's data reaches the screen mid-flight.
        assertTrue("hold covers >= 10 missed broadcasts", CONNECTION_HOLD_MS >= 10_000L)
    }
}
