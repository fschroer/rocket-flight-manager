package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.SensorFaultSummary
import com.steampigeon.flightmanager.data.SensorFaults
import com.steampigeon.flightmanager.data.SensorHealth
import com.steampigeon.flightmanager.data.joinSensorNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0038: the app's copy of the locator's sensor-fault rule. The firmware's
 * Tests/SensorFaults pins the same table; a row changed in one is a row to
 * change in the other.
 */
class SensorFaultsTest {

    @Test
    fun `error, off and initializing are failures`() {
        assertTrue(SensorFaults.failed(SensorHealth.Error))
        assertTrue(SensorFaults.failed(SensorHealth.Off))
        assertTrue(SensorFaults.failed(SensorHealth.Initializing))
    }

    @Test
    fun `ok, warning and stale are not`() {
        assertFalse(SensorFaults.failed(SensorHealth.Ok))
        assertFalse(SensorFaults.failed(SensorHealth.Warning))
        // A GPS waiting on a fix is up; counting it would flag every pad.
        assertFalse(SensorFaults.failed(SensorHealth.Stale))
    }

    @Test
    fun `health bytes decode to the firmware's values`() {
        assertEquals(SensorHealth.Error, SensorHealth.fromUByte(4u))
        assertEquals(SensorHealth.Ok, SensorHealth.fromUByte(2u))
        assertEquals(SensorHealth.Stale, SensorHealth.fromUByte(5u))
    }

    @Test
    fun `arming needs the IMU and the barometer`() {
        for (imu in SensorHealth.entries) for (baro in SensorHealth.entries) {
            assertEquals(
                "imu=$imu baro=$baro",
                !SensorFaults.failed(imu) && !SensorFaults.failed(baro),
                SensorFaults.armingAllowed(imu, baro),
            )
        }
        assertFalse(SensorFaults.armingAllowed(SensorHealth.Ok, SensorHealth.Error))
        assertFalse(SensorFaults.armingAllowed(SensorHealth.Error, SensorHealth.Ok))
    }

    @Test
    fun `a failed GPS is reported but does not block arming`() {
        val s = SensorFaultSummary.of(SensorHealth.Ok, SensorHealth.Ok, SensorHealth.Error)
        assertTrue(s.any)
        assertTrue(s.gpsFailed)
        assertTrue(s.armingAllowed)
    }

    @Test
    fun `a healthy locator reports nothing`() {
        val s = SensorFaultSummary.of(SensorHealth.Ok, SensorHealth.Ok, SensorHealth.Stale)
        assertFalse(s.any)
        assertTrue(s.armingAllowed)
    }

    @Test
    fun `a failed barometer blocks arming`() {
        val s = SensorFaultSummary.of(SensorHealth.Ok, SensorHealth.Error, SensorHealth.Ok)
        assertTrue(s.baroFailed)
        assertFalse(s.armingAllowed)
    }

    @Test
    fun `sensor names join as English`() {
        assertEquals("", joinSensorNames(emptyList()))
        assertEquals("IMU", joinSensorNames(listOf("IMU")))
        assertEquals("IMU and barometer", joinSensorNames(listOf("IMU", "barometer")))
        assertEquals("IMU, barometer and GPS", joinSensorNames(listOf("IMU", "barometer", "GPS")))
    }
}
