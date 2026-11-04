package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.ui.usableFix
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A received position MapLibre would throw on becomes the 0,0 "no fix"
 * placeholder (crash reported 2026-11-03: "latitude must be between -90 and 90").
 */
class UsableFixTest {
    @Test fun aRealFixPassesThrough() = assertEquals(40.5 to -105.2, usableFix(40.5, -105.2))
    @Test fun thePolesAndTheAntimeridianAreFixes() = assertEquals(-90.0 to 180.0, usableFix(-90.0, 180.0))
    @Test fun latitudeOutOfRangeIsNoFix() = assertEquals(0.0 to 0.0, usableFix(90.0001, 10.0))
    @Test fun nanIsNoFix() = assertEquals(0.0 to 0.0, usableFix(Double.NaN, 10.0))
    @Test fun infinityIsNoFix() = assertEquals(0.0 to 0.0, usableFix(10.0, Double.POSITIVE_INFINITY))
    @Test fun longitudeOutOfRangeIsNoFix() = assertEquals(0.0 to 0.0, usableFix(10.0, -180.5))
}
