package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.ui.WindCoast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/** The canopy wind coast (locator ADR-0022 amendment 2026-11-08, #72). */
class WindCoastTest {
    private val lat0 = 46.25
    private val lon0 = -119.10
    private val mPerDegLat = 6_371_000.0 * PI / 180.0
    private val mPerDegLon = mPerDegLat * cos(lat0 * PI / 180.0)

    /** 1 Hz fixed packets drifting at (north, east) m/s, ending at t = (n−1) s. */
    private fun drift(c: WindCoast, n: Int, north: Float, east: Float, descent: Boolean = true) {
        for (i in 0 until n) {
            c.update(i * 1000L, hasFix = true, inDescent = descent, landed = false,
                latitude = lat0 + north * i / mPerDegLat, longitude = lon0 + east * i / mPerDegLon,
                velNorthMps = north, velEastMps = east, haccM = 2f)
        }
    }

    private fun lost(c: WindCoast, atMs: Long, descent: Boolean = true, landed: Boolean = false) =
        c.update(atMs, hasFix = false, inDescent = descent, landed = landed,
            latitude = 0.0, longitude = 0.0, velNorthMps = 0f, velEastMps = 0f, haccM = 99f)

    @Test
    fun aLiveFixIsNeverCoasted() {
        val c = WindCoast()
        drift(c, 6, 3f, -4f)
        assertNull(c.update(6_000L, true, true, false, lat0, lon0, 3f, -4f, 2f))
    }

    @Test
    fun carriesTheLastFixForwardAtTheWind() {
        val c = WindCoast()
        drift(c, 6, 3f, -4f)                       // last fix at t = 5 s
        val k = lost(c, 15_000L)                   // 10 s later
        assertNotNull(k)
        val lastLat = lat0 + 3.0 * 5 / mPerDegLat
        val lastLon = lon0 - 4.0 * 5 / mPerDegLon
        assertEquals(30.0, (k!!.latitude - lastLat) * mPerDegLat, 0.01)
        assertEquals(-40.0, (k.longitude - lastLon) * mPerDegLon, 0.01)
        assertEquals(2.0 + 4.0 * 10, k.radiusM, 1e-9)
        assertEquals(10_000L, k.sinceFixMs)
    }

    @Test
    fun stopsMovingAt30sButTheRingKeepsGrowing() {
        val c = WindCoast()
        drift(c, 6, 0f, 5f)
        val at30 = lost(c, 35_000L)!!
        val at60 = lost(c, 65_000L)!!
        assertEquals(at30.longitude, at60.longitude, 1e-12)
        assertEquals(2.0 + 4.0 * 60, at60.radiusM, 1e-9)
    }

    @Test
    fun landedFreezesPointAndRing() {
        val c = WindCoast()
        drift(c, 6, 0f, 5f)
        lost(c, 10_000L)
        val atLanding = lost(c, 12_000L, descent = false, landed = true)!!
        val later = lost(c, 40_000L, descent = false, landed = true)!!
        assertEquals(atLanding, later)
        assertEquals(7_000L, later.sinceFixMs)
        assertEquals(2.0 + 4.0 * 7, later.radiusM, 1e-9)
    }

    @Test
    fun noCoastWithoutEnoughWind() {
        val c = WindCoast()
        drift(c, 1, 3f, 3f)
        assertNull(lost(c, 5_000L))
    }

    @Test
    fun windOnlyFromTheLastFiveSeconds() {
        // Two old samples at another speed, then a 6 s silence: they no longer count.
        val d = WindCoast()
        d.update(0L, true, true, false, lat0, lon0, 0f, 50f, 2f)
        d.update(1_000L, true, true, false, lat0, lon0, 0f, 50f, 2f)
        d.update(7_000L, true, true, false, lat0, lon0, 0f, 5f, 2f)
        assertNull(lost(d, 9_000L))   // only one sample inside the window
    }

    @Test
    fun ascentAndPreDeploymentVelocityIsNotWind() {
        val c = WindCoast()
        drift(c, 6, 0f, 80f, descent = false)       // Noseover momentum, not wind
        assertNull(lost(c, 8_000L, descent = false))
        assertNull(lost(c, 9_000L))                 // deployment during the outage: no wind
    }

    @Test
    fun reacquiringReplacesTheCoast() {
        val c = WindCoast()
        drift(c, 6, 0f, 5f)
        lost(c, 10_000L)!!
        assertNull(c.update(11_000L, true, true, false, lat0, lon0, 0f, 5f, 2f))
    }

    @Test
    fun aNewOutageLatchesAFreshWind() {
        val c = WindCoast()
        drift(c, 6, 0f, 5f)
        lost(c, 8_000L)!!
        for (i in 9..14) c.update(i * 1000L, true, true, false, lat0, lon0, 2f, 0f, 2f)
        val k = lost(c, 24_000L)!!
        assertEquals(20.0, (k.latitude - lat0) * mPerDegLat, 0.01)
        assertEquals(0.0, (k.longitude - lon0) * mPerDegLon, 0.01)
    }

    @Test
    fun descentStatesAreFourToSeven() {
        assertEquals(listOf(4, 5, 6, 7), (0..9).filter { WindCoast.isDescent(it) })
    }
}
