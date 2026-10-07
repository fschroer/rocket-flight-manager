package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.DeployChannelStats
import com.steampigeon.flightmanager.data.DeployMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-channel deployment status byte (#52), decoded one way everywhere.
 *
 * Layout, from the locator's `Constants.hpp`: bits 0-2 mode | 3 fired |
 * 4 pre-fire continuity | 5 post-fire continuity | 6 live continuity (telemetry).
 *
 * Telemetry was decoded by hand with `and(4)` for fired and `and(32)` for
 * continuity. Bit 2 is a mode bit and bit 5 is the post-fire bit, so on Nike
 * Smoke two drogue charges fired with the logged fired mask reading 0.
 */
class DeployChannelStatsTest {

    private val fired = 1 shl 3
    private val preFire = 1 shl 4
    private val postFire = 1 shl 5
    private val live = 1 shl 6

    @Test
    fun `each field comes from its own bit`() {
        val s = DeployChannelStats.fromByte(DeployMode.MainBackup.deployMode.toInt() or fired or preFire or live)
        assertEquals(DeployMode.MainBackup, s.mode)
        assertTrue(s.fired)
        assertTrue(s.preFireContinuity)
        assertFalse(s.postFireContinuity)
        assertTrue(s.continuity)
    }

    @Test
    fun `a mode with bit 2 set is not a fired channel`() {
        // The old telemetry decode read bit 2 as "fired". Unused is mode 7, which
        // has bit 2 set, so every unused channel read as fired.
        val s = DeployChannelStats.fromByte(DeployMode.Unused.deployMode.toInt())
        assertEquals(DeployMode.Unused, s.mode)
        assertFalse(s.fired)
    }

    @Test
    fun `an armed channel on the pad shows live continuity before anything fires`() {
        // The old decode read bit 5, post-fire continuity, which is 0 until a charge
        // has fired: every intact channel on an armed pad read as no continuity.
        val s = DeployChannelStats.fromByte(DeployMode.DroguePrimary.deployMode.toInt() or live)
        assertTrue(s.continuity)
        assertFalse(s.postFireContinuity)
    }

    @Test
    fun `masks put channel 1 in bit 0 through channel 4 in bit 3`() {
        // Nike Smoke after both drogue charges: channels 1 and 2 fired.
        val bytes = listOf(
            DeployMode.DroguePrimary.deployMode.toInt() or fired or preFire,
            DeployMode.DrogueBackup.deployMode.toInt() or fired or preFire,
            DeployMode.MainPrimary.deployMode.toInt() or live,
            DeployMode.MainBackup.deployMode.toInt() or live,
        )
        assertEquals(0b0011, DeployChannelStats.mask(bytes) { it.fired })
        assertEquals(0b1100, DeployChannelStats.mask(bytes) { it.continuity })
    }
}
