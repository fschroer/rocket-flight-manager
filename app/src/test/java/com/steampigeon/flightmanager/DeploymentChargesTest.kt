package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.DeploymentCharges
import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.RocketState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The charge callouts must consult all four deployment channels.
 *
 * They consulted two, and the stock wiring puts main on channel 3 — so "Main
 * charge." was unreachable on a rocket nobody had reconfigured.  Found on the
 * iOS port 2026-09-02 (row 5 of `UI_PARITY.md`'s "ANDROID OWES THIS"), fixed
 * here 2026-09-04.  `testTheMainChargeOnChannelThreeIsAnnounced` below is
 * Android's copy of the iOS case of the same name.
 *
 * These drive [DeploymentCharges] rather than the callout site, which is
 * inside a composable and unreachable from this suite — the reason the helper
 * was lifted out at all.
 */
class DeploymentChargesTest {

    /** The defaults `LocatorConfigWire.payload` writes: ch3 is MainPrimary. */
    private val stockWiring = LocatorConfig(
        deploymentChannel1Mode = DeployMode.DroguePrimary,
        deploymentChannel2Mode = DeployMode.DrogueBackup,
        deploymentChannel3Mode = DeployMode.MainPrimary,
        deploymentChannel4Mode = DeployMode.MainBackup,
    )

    private fun fired(
        ch1: Boolean = false, ch2: Boolean = false,
        ch3: Boolean = false, ch4: Boolean = false,
    ) = RocketState(
        channel1Fired = ch1, channel2Fired = ch2,
        channel3Fired = ch3, channel4Fired = ch4,
    )

    @Test fun testTheMainChargeOnChannelThreeIsAnnounced() {
        assertTrue(
            DeploymentCharges.fired(stockWiring, fired(ch3 = true), DeployMode.MainPrimary)
        )
    }

    @Test fun `the main backup charge on channel four is announced`() {
        assertTrue(
            DeploymentCharges.fired(stockWiring, fired(ch4 = true), DeployMode.MainBackup)
        )
    }

    @Test fun `the drogue charges on channels one and two still work`() {
        assertTrue(
            DeploymentCharges.fired(stockWiring, fired(ch1 = true), DeployMode.DroguePrimary)
        )
        assertTrue(
            DeploymentCharges.fired(stockWiring, fired(ch2 = true), DeployMode.DrogueBackup)
        )
    }

    @Test fun `a channel wired for a mode that has not fired is silent`() {
        assertFalse(
            DeploymentCharges.fired(stockWiring, fired(), DeployMode.MainPrimary)
        )
    }

    /** The pairing is per channel. A fired drogue must not announce main just
     *  because something, somewhere, fired. */
    @Test fun `a fired channel does not announce another channel's mode`() {
        assertFalse(
            DeploymentCharges.fired(stockWiring, fired(ch1 = true), DeployMode.MainPrimary)
        )
    }

    /** Nothing here knows about convention: a rocket wired backwards reads
     *  correctly, which is the point of matching on mode. */
    @Test fun `main wired on channel one is announced`() {
        val reversed = LocatorConfig(
            deploymentChannel1Mode = DeployMode.MainPrimary,
            deploymentChannel2Mode = DeployMode.MainBackup,
            deploymentChannel3Mode = DeployMode.DroguePrimary,
            deploymentChannel4Mode = DeployMode.DrogueBackup,
        )
        assertTrue(DeploymentCharges.fired(reversed, fired(ch1 = true), DeployMode.MainPrimary))
        assertTrue(DeploymentCharges.fired(reversed, fired(ch3 = true), DeployMode.DroguePrimary))
    }

    /** A locator may wire the same job to two channels; either firing counts. */
    @Test fun `two channels wired for one mode both count`() {
        val doubled = stockWiring.copy(deploymentChannel4Mode = DeployMode.MainPrimary)
        assertTrue(DeploymentCharges.fired(doubled, fired(ch3 = true), DeployMode.MainPrimary))
        assertTrue(DeploymentCharges.fired(doubled, fired(ch4 = true), DeployMode.MainPrimary))
    }

    @Test fun `an unused channel never announces anything`() {
        val sparse = LocatorConfig(
            deploymentChannel1Mode = DeployMode.MainPrimary,
            deploymentChannel2Mode = DeployMode.Unused,
            deploymentChannel3Mode = DeployMode.Unused,
            deploymentChannel4Mode = DeployMode.Unused,
        )
        assertFalse(DeploymentCharges.fired(sparse, fired(ch2 = true), DeployMode.MainPrimary))
        assertTrue(DeploymentCharges.fired(sparse, fired(ch1 = true), DeployMode.MainPrimary))
    }

    /** A config the locator has not reported yet is all nulls. Guessing the
     *  stock wiring here would announce a charge on the strength of a
     *  convention rather than on what the locator said. */
    @Test fun `an unreported config announces nothing`() {
        assertFalse(
            DeploymentCharges.fired(LocatorConfig(), fired(ch3 = true), DeployMode.MainPrimary)
        )
    }

    // ── The latch: a charge that fires late is still announced ───────────────

    private fun state(
        flightState: FlightStates,
        ch1: Boolean = false, ch2: Boolean = false,
        ch3: Boolean = false, ch4: Boolean = false,
    ) = RocketState(
        flightState = flightState,
        channel1Fired = ch1, channel2Fired = ch2,
        channel3Fired = ch3, channel4Fired = ch4,
    )

    /**
     * The bench case that exposed the latch defect, from the locator's own
     * behaviour: ch1 DrogueBackup on a 1.0 s delay with an e-match fitted, ch2
     * MainPrimary at 130 m with none, noseover at 110 m.
     *
     * Main's firmware condition is `agl <= 130` — a test, not a downward
     * crossing — so it is already true at apogee: ch2 is commanded immediately
     * and the state jumps to MainPrimaryEvent, skipping DrogueBackupEvent
     * entirely since AdvanceFlightState only moves forward. The drogue fires a
     * second later.
     *
     * Latching on the state threshold announced the charge that did nothing and
     * stayed silent on the one that fired.
     */
    @Test fun `a drogue that fires after the state has passed it is still announced`() {
        val latch = DeploymentCharges.Latch()
        val wiring = LocatorConfig(
            deploymentChannel1Mode = DeployMode.DrogueBackup,
            deploymentChannel2Mode = DeployMode.MainPrimary,
        )

        // First broadcast after noseover: state is already MainPrimaryEvent,
        // ch2 commanded (no e-match, but commanded), ch1 not yet.
        assertEquals(
            listOf(DeployMode.MainPrimary),
            latch.due(wiring, state(FlightStates.MainPrimaryEvent, ch2 = true)),
        )

        // One second later the drogue actually fires. The state has not moved.
        assertEquals(
            listOf(DeployMode.DrogueBackup),
            latch.due(wiring, state(FlightStates.MainPrimaryEvent, ch1 = true, ch2 = true)),
        )
    }

    @Test fun `each callout is spoken once, however many broadcasts carry it`() {
        val latch = DeploymentCharges.Latch()
        val s = state(FlightStates.MainPrimaryEvent, ch3 = true)
        assertEquals(listOf(DeployMode.MainPrimary), latch.due(stockWiring, s))
        assertEquals(emptyList<DeployMode>(), latch.due(stockWiring, s))
        assertEquals(emptyList<DeployMode>(), latch.due(stockWiring, s))
    }

    /** The floor still holds: a fired flag arriving before its event is not
     *  announced early. It cannot happen from this firmware — the fired bit and
     *  the state advance are set in the same block — but the floor is what says
     *  so. */
    @Test fun `a charge is not announced before its flight state`() {
        val latch = DeploymentCharges.Latch()
        assertEquals(
            emptyList<DeployMode>(),
            latch.due(stockWiring, state(FlightStates.Noseover, ch3 = true)),
        )
    }

    /** A charge that never fires is never announced. Silence means the charge
     *  did not go, not that the app stopped listening. */
    @Test fun `passing the state alone announces nothing`() {
        val latch = DeploymentCharges.Latch()
        assertEquals(
            emptyList<DeployMode>(),
            latch.due(stockWiring, state(FlightStates.MainBackupEvent)),
        )
    }

    /** Two charges landing in one broadcast come out in ladder order, not in
     *  channel order or set order. */
    @Test fun `simultaneous charges are announced in ladder order`() {
        val latch = DeploymentCharges.Latch()
        assertEquals(
            listOf(DeployMode.DroguePrimary, DeployMode.MainPrimary),
            latch.due(stockWiring, state(FlightStates.MainPrimaryEvent, ch1 = true, ch3 = true)),
        )
    }

    @Test fun `reset re-arms every callout for the next flight`() {
        val latch = DeploymentCharges.Latch()
        val s = state(FlightStates.MainPrimaryEvent, ch3 = true)
        assertEquals(listOf(DeployMode.MainPrimary), latch.due(stockWiring, s))
        latch.reset()
        assertEquals(listOf(DeployMode.MainPrimary), latch.due(stockWiring, s))
    }
}
