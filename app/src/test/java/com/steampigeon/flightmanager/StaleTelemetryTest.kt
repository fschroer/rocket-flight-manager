package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.ui.StaleTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The statistics panel's stale indicator (it stays up, dimmed, when the link drops). */
class StaleTelemetryTest {
    @Test
    fun goesStaleAtTheMapScreensTwoSecondTimeout() {
        assertFalse(StaleTelemetry.isStale(1_999L))
        assertTrue(StaleTelemetry.isStale(2_000L))
    }

    @Test
    fun shortAgeSteps() {
        assertEquals("0 s", StaleTelemetry.shortAge(-5L))
        assertEquals("2 s", StaleTelemetry.shortAge(2_999L))
        assertEquals("59 s", StaleTelemetry.shortAge(59_999L))
        assertEquals("1 min", StaleTelemetry.shortAge(60_000L))
        assertEquals("59 min", StaleTelemetry.shortAge(3_599_999L))
        assertEquals("1 h", StaleTelemetry.shortAge(3_600_000L))
        assertEquals("1 h 5 min", StaleTelemetry.shortAge(3_900_000L))
    }

    @Test
    fun spokenAgeIsPluralized() {
        assertEquals("1 second", StaleTelemetry.spokenAge(1_500L))
        assertEquals("12 seconds", StaleTelemetry.spokenAge(12_000L))
        assertEquals("1 minute", StaleTelemetry.spokenAge(61_000L))
        assertEquals("2 hours", StaleTelemetry.spokenAge(7_200_000L))
        assertEquals("1 hour 1 minute", StaleTelemetry.spokenAge(3_660_000L))
    }
}
