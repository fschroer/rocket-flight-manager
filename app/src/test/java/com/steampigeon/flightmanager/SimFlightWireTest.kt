package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.SimFlightProfile
import com.steampigeon.flightmanager.data.SimFlightWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SimFlightRequest's payload against bytes from a host build of the firmware's
 * own struct (locator MessageProtocol.hpp, ADR-0041): the C++ program filled
 * every field and printed bytes 10..29.
 */
class SimFlightWireTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun startMatchesTheFirmwareStruct() {
        val p = SimFlightProfile(apogeeM = 1500, burnTenths = 30, drogueMps = 25, mainMps = 7,
            windMps = 7, windFromDeg = 315)
        val payload = SimFlightWire.start(p, seed = 0xBEEF, originLat = 40.5123456, originLon = -105.2345678)
        assertEquals("dc0580b12518b27a46c13b01efbe011e19460700", hex(payload))
    }

    @Test fun sizeIsTheFrameLessHeaderAndTarget() {
        assertEquals(30 - 6 - 4, SimFlightWire.PAYLOAD_SIZE)   // C++ sizeof 30; WireLayoutTest pins Protocol
    }

    @Test fun noOriginSendsZeros() {
        val payload = SimFlightWire.start(SimFlightProfile(), seed = 1, originLat = null, originLon = null)
        assertArrayEquals(ByteArray(8), payload.copyOfRange(2, 10))
    }

    @Test fun cancelIsActionZero() {
        val c = SimFlightWire.cancel()
        assertEquals(SimFlightWire.PAYLOAD_SIZE, c.size)
        assertEquals(0, c[SimFlightWire.OFFSET_ACTION - 10].toInt())
    }

    @Test fun rangesAreTheAdrs() {
        assertTrue(SimFlightProfile().valid)
        assertFalse(SimFlightProfile(apogeeM = 3001).valid)
        assertFalse(SimFlightProfile(burnTenths = 2).valid)
        assertFalse(SimFlightProfile(mainMps = 16).valid)
        assertFalse(SimFlightProfile(windFromDeg = 360).valid)
    }
}
