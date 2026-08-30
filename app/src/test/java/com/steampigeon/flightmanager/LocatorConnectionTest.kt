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

    // ── The channel a receiver-only move is leaving (reported 2026-08-29) ────────
    //
    // Connected to Twist 0 on 34, Connect tapped on Twist Lock 5's hit on 60. The move
    // releases the connection first, and Twist 0 goes on broadcasting on 34 until the
    // receiver retunes. Intermittent because it depends on whether one of those 1 Hz
    // broadcasts lands inside the window.

    @Test
    fun `the locator we are leaving does not reclaim the slot mid-move`() {
        // Twist 0's frame, relayed while the receiver was still on 34.
        assertTrue(
            LocatorConnection.isFromChannelBeingLeft(
                frameChannel = 34, previousChannel = 34,
                awaitingRecognition = true, moveInFlight = true,
            )
        )
    }

    @Test
    fun `the locator on the channel we are moving to is admitted`() {
        // Twist Lock 5's frame, relayed after the retune. This is the frame the
        // recognition cycle was armed for; suppressing it would break the feature
        // rather than fix it.
        assertFalse(
            LocatorConnection.isFromChannelBeingLeft(
                frameChannel = 60, previousChannel = 34,
                awaitingRecognition = true, moveInFlight = true,
            )
        )
    }

    @Test
    fun `an unstamped frame cannot be placed during the move, so it waits`() {
        // TelemetryData carries no receiver channel. During the window it could be
        // either locator, and guessing wrong is the reported bug.
        assertTrue(
            LocatorConnection.isFromChannelBeingLeft(
                frameChannel = null, previousChannel = 34,
                awaitingRecognition = true, moveInFlight = true,
            )
        )
    }

    @Test
    fun `the window closes when the move stops being in flight`() {
        // The recognition flag alone would suppress forever on a move to a channel
        // with nothing on it, leaving the app deaf to the locator it still has. The
        // receiver's config message state always returns to idle, acknowledged or not.
        assertFalse(
            LocatorConnection.isFromChannelBeingLeft(
                frameChannel = 34, previousChannel = 34,
                awaitingRecognition = true, moveInFlight = false,
            )
        )
    }

    @Test
    fun `no channel move in progress suppresses nothing`() {
        // A receiver RENAME also drives the config message state, and leaves a stale
        // channelChangePreviousChannel behind it.
        assertFalse(
            LocatorConnection.isFromChannelBeingLeft(
                frameChannel = 34, previousChannel = 34,
                awaitingRecognition = false, moveInFlight = true,
            )
        )
    }
}
