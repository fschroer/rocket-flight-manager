package com.steampigeon.flightmanager

import android.hardware.SensorManager
import com.steampigeon.flightmanager.ui.classifyFieldMagnitude
import com.steampigeon.flightmanager.ui.fieldMagnitudeUt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Judging the compass by the strength of the field it is reading.
 *
 * This exists because the two accuracy flags Android offers cannot be relied on.
 * A Pixel 9 Pro XL never fires the rotation vector's; a Moto G 5S never fires the
 * magnetometer's and pins the rotation vector's at HIGH under a magnet held
 * against the case. A flag pinned at HIGH is indistinguishable from a healthy
 * compass, so on that device the calibration warning was unreachable however it
 * was plumbed — see ADR-0023.
 *
 * The field magnitude is the one test that does not depend on the vendor being
 * honest. Earth's field is 22-67 µT everywhere on the surface, so a reading well
 * outside that is something local, and no amount of firmware opinion changes the
 * arithmetic.
 *
 * What it does NOT catch, and the thing not to assume: a stale hard-iron offset
 * rotates the heading while leaving the magnitude entirely plausible. HIGH here
 * means "nothing is obviously swamping the sensor", never "the heading is right".
 */
class FieldMagnitudeTest {

    @Test
    fun `magnitude is the euclidean norm of the three axes`() {
        assertEquals(5f, fieldMagnitudeUt(floatArrayOf(3f, 4f, 0f)), 1e-4f)
        // Sign must not matter — the field points wherever it points.
        assertEquals(5f, fieldMagnitudeUt(floatArrayOf(-3f, 0f, -4f)), 1e-4f)
    }

    @Test
    fun `a plausible earth field reads HIGH`() {
        // ~48 µT: mid-latitude continental US, which is where this is flown.
        assertEquals(
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
            classifyFieldMagnitude(48f),
        )
    }

    @Test
    fun `the weakest and strongest real places on earth still read HIGH`() {
        // The envelope is deliberately wider than the true 22-67 µT span. A user
        // in the South Atlantic anomaly or near a pole must not be told their
        // compass is broken because of where they are standing.
        assertEquals(
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
            classifyFieldMagnitude(22f),
        )
        assertEquals(
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
            classifyFieldMagnitude(67f),
        )
    }

    @Test
    fun `mildly out of range raises the prompt but does not suppress the overlay`() {
        // LOW is the level that shows the figure-8 prompt and leaves the AR marker
        // drawn. Something is off, but not so far off that the app should stop
        // pointing at the rocket.
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, classifyFieldMagnitude(80f))
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, classifyFieldMagnitude(15f))
    }

    @Test
    fun `a magnet reads UNRELIABLE`() {
        // A fridge magnet at a few centimetres reads in the hundreds or thousands.
        // This is the case that has to work: it is the one the vendor flags miss
        // entirely on a Moto G.
        assertEquals(SensorManager.SENSOR_STATUS_UNRELIABLE, classifyFieldMagnitude(450f))
        assertEquals(SensorManager.SENSOR_STATUS_UNRELIABLE, classifyFieldMagnitude(3000f))
    }

    @Test
    fun `a collapsed reading reads UNRELIABLE`() {
        // Too weak to be the Earth: shielding, or a sensor returning nothing
        // useful. Either way the heading built on it cannot be trusted.
        assertEquals(SensorManager.SENSOR_STATUS_UNRELIABLE, classifyFieldMagnitude(2f))
        assertEquals(SensorManager.SENSOR_STATUS_UNRELIABLE, classifyFieldMagnitude(0f))
    }

    @Test
    fun `the bands meet without a gap`() {
        // Every magnitude lands in exactly one band. A hole here would publish
        // whatever the previous classification was and look like a stuck warning.
        var magnitude = 0f
        while (magnitude <= 500f) {
            val level = classifyFieldMagnitude(magnitude)
            assertEquals(
                "magnitude $magnitude classified outside the three known levels",
                true,
                level == SensorManager.SENSOR_STATUS_ACCURACY_HIGH ||
                    level == SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
                    level == SensorManager.SENSOR_STATUS_UNRELIABLE,
            )
            magnitude += 0.5f
        }
    }

    @Test
    fun `worse readings never classify better than milder ones`() {
        // Monotonic away from the envelope in both directions: further from a
        // plausible Earth field can only mean equal or lower trust. A non-monotonic
        // rule would let a stronger magnet clear a warning a weaker one raised.
        assertEquals(true, classifyFieldMagnitude(200f) <= classifyFieldMagnitude(80f))
        assertEquals(true, classifyFieldMagnitude(80f) <= classifyFieldMagnitude(48f))
        assertEquals(true, classifyFieldMagnitude(5f) <= classifyFieldMagnitude(15f))
        assertEquals(true, classifyFieldMagnitude(15f) <= classifyFieldMagnitude(48f))
    }
}
