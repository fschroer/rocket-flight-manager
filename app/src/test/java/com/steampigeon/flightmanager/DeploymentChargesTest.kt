package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.DeploymentCharges
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.RocketState
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
}
