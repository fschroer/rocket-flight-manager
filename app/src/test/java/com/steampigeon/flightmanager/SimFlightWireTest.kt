package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.SimFlightWire
import com.steampigeon.flightmanager.data.simflight.SimMotor
import com.steampigeon.flightmanager.data.simflight.SimRocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SimFlightStart's payload against bytes from a host build of the firmware's
 * own struct (locator MessageProtocol.hpp, ADR-0041 amendment): a C++ program
 * filled every field with the values below and printed bytes 10..198.
 */
class SimFlightWireTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val motor = SimMotor("test", "Test", "T100", 29f, loadedKg = 0.25f, propKg = 0.12f,
        curve = listOf(0.05f to 100f, 0.5f to 80f, 1.0f to 60f, 1.2f to 0f))
    private val rocket = SimRocket(name = "vector", dryMassG = 1200, diameterMm = 54f,
        cd = listOf(0.45f, 0.45f, 0.45f, 0.459f, 0.504f, 0.6975f, 0.6525f, 0.585f),
        lengthMm = 1350, stabilityCal = 2.7f,
        drogueMps = 25, mainMps = 7, railLengthCm = 244, railAngleTenths = 50, launchAzDeg = 90,
        tempC = -5, windMps = 7, windFromDeg = 315)

    @Test fun startMatchesTheFirmwareStruct() {
        val payload = SimFlightWire.start(rocket, motor, seed = 0xBEEF, originLat = 40.5123456, originLon = -105.2345678)
        assertEquals(
            "80b12518b27a46c1efbe3b015a00f40032071946fb040000c8420000803e8fc2f53d9a99993f1b2f5d3d" +
            "c201c201c201cb01f801ba028d0249023200f401e803b004" + "0".repeat(112) +
            "ffffcccc9999" + "0".repeat(116) + "46051b",
            hex(payload))
    }

    @Test fun sizesAreTheFrameLessHeaderAndTarget() {
        assertEquals(199 - 6 - 4, SimFlightWire.START_PAYLOAD_SIZE)
        assertEquals(30 - 6 - 4, SimFlightWire.CANCEL_PAYLOAD_SIZE)
    }

    @Test fun noOriginSendsZeros() {
        val payload = SimFlightWire.start(rocket, motor, seed = 1, originLat = null, originLon = null)
        assertArrayEquals(ByteArray(8), payload.copyOfRange(0, 8))
    }

    @Test fun noTemperatureAsksForTheLocatorsBaro() {
        val payload = SimFlightWire.start(rocket.copy(tempC = null), motor, seed = 1, originLat = null, originLon = null)
        assertEquals(0x80.toByte(), payload[SimFlightWire.OFFSET_TEMP_C - 10])
    }

    @Test fun cancelIsActionZero() {
        val c = SimFlightWire.cancel()
        assertEquals(SimFlightWire.CANCEL_PAYLOAD_SIZE, c.size)
        assertEquals(0, c[SimFlightWire.OFFSET_CANCEL_ACTION - 10].toInt())
    }

    @Test fun aLongCurveIsThinnedToThirtyTwoPoints() {
        val long = motor.copy(curve = (1..200).map { i -> i * 0.01f to (if (i < 200) 100f - i * 0.3f else 0f) })
        val payload = SimFlightWire.start(rocket, long, seed = 1, originLat = null, originLon = null)
        assertEquals(32, payload[SimFlightWire.OFFSET_N_POINTS - 10].toInt())
        assertTrue(rocket.problems(long).isEmpty())
    }
}
