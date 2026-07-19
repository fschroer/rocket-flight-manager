package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.ui.PathPoint
import com.steampigeon.flightmanager.ui.altitudeCurtain
import com.steampigeon.flightmanager.ui.curtainRiser
import com.steampigeon.flightmanager.ui.curtainSubdivisions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Polygon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Geometry for the 3D flight-path altitude curtain.
 *
 * The wall is built by offsetting perpendicular to each track segment. That
 * offset has to be computed in metres — longitude degrees shrink by cos(lat) —
 * or the wall silently skews and widens with latitude, which is invisible on a
 * tilted 3D map until you measure it.
 */
class AltitudeCurtainTest {

    private companion object {
        const val LAT = 47.6146       // the user's test site — cos(lat) ≈ 0.674
        const val LON = -122.5526
        const val METERS_PER_DEG_LAT = 111_320.0
        const val TARGET_RISER_M = 0.25f // CURTAIN_TARGET_RISER_M
        const val MAX_SUBDIVISIONS = 512 // CURTAIN_MAX_SUBDIVISIONS
        const val HALF_WIDTH_M = 0.75  // CURTAIN_HALF_WIDTH_M
    }

    /**
     * Offset [northM]/[eastM] metres from the reference point.  Timestamps are
     * irrelevant to the curtain, so they default to a 50 ms cadence by index.
     */
    private fun offset(
        northM: Double, eastM: Double, altM: Float, timestampMs: Long = 0L,
    ): PathPoint {
        val dLat = northM / METERS_PER_DEG_LAT
        val dLon = eastM / (METERS_PER_DEG_LAT * cos(LAT * PI / 180.0))
        return PathPoint(LAT + dLat, LON + dLon, altM, timestampMs)
    }

    private fun heights(fc: org.maplibre.geojson.FeatureCollection): List<Double> =
        fc.features()!!.map { it.getNumberProperty("height").toDouble() }

    /** Ground distance in metres between two lng/lat points near the test site. */
    private fun metersBetween(
        lon0: Double, lat0: Double, lon1: Double, lat1: Double,
    ): Double {
        val cosLat = cos(lat0 * PI / 180.0)
        val dx = (lon1 - lon0) * METERS_PER_DEG_LAT * cosLat
        val dy = (lat1 - lat0) * METERS_PER_DEG_LAT
        return hypot(dx, dy)
    }

    // ── Degenerate input ────────────────────────────────────────────────────

    @Test
    fun emptyOrSinglePointProducesNoCurtain() {
        assertTrue(altitudeCurtain(emptyList()).features()!!.isEmpty())
        assertTrue(altitudeCurtain(listOf(offset(0.0, 0.0, 100f))).features()!!.isEmpty())
    }

    @Test
    fun groundLevelTrackProducesNoCurtain() {
        // A rocket still on the pad, or a path recorded at zero AGL, must not
        // draw a degenerate zero-height wall over the ground track.
        val path = listOf(
            offset(0.0, 0.0, 0f),
            offset(10.0, 0.0, 0f),
            offset(20.0, 0.0, 0.1f),
        )
        assertTrue(altitudeCurtain(path).features()!!.isEmpty())
    }

    @Test
    fun stationaryPointsAreSkipped() {
        // Repeated identical fixes (rocket landed, still transmitting) have no
        // direction to offset perpendicular to — they must not emit NaN geometry.
        val p = offset(0.0, 0.0, 50f)
        val fc = altitudeCurtain(listOf(p, p, p))
        assertTrue(fc.features()!!.isEmpty())
    }

    // ── Subdivision ─────────────────────────────────────────────────────────

    @Test
    fun subdivisionIsBudgetedByAltitudeChangeNotSegmentCount() {
        // The whole point of the adaptive split: a steep interval must be cut
        // finer than a shallow one covering the same ground.  A fixed count
        // divides the ground run evenly and gives both the same treatment,
        // which is what left the boost phase looking like stairs.
        assertEquals(1, curtainSubdivisions(100f, 100f, 1f))
        assertEquals(1, curtainSubdivisions(100f, 100.5f, 1f))
        assertEquals(10, curtainSubdivisions(100f, 110f, 1f))
        assertEquals(10, curtainSubdivisions(110f, 100f, 1f))   // descent is symmetric
        assertEquals(MAX_SUBDIVISIONS, curtainSubdivisions(0f, 5000f, 1f))
        // A relaxed riser buys back proportionally fewer quads.
        assertEquals(5, curtainSubdivisions(100f, 110f, 2f))
    }

    @Test
    fun anOrdinaryFlightGetsTheFullTargetRiser() {
        val hop = listOf(offset(0.0, 0.0, 0f), offset(50.0, 0.0, 100f))
        assertEquals(TARGET_RISER_M, curtainRiser(hop), 1e-6f)
    }

    @Test
    fun sensorNoiseOnAHighRatePathDoesNotCoarsenTheRiser() {
        // The regression this guards is subtle and cost several rounds to find.
        //
        // The quad backstop is driven by SUMMED |altitude change|, which cannot
        // distinguish flight profile from sensor noise. Raw baro at the 20 Hz
        // archive cadence jitters a few metres per sample, so summing absolute
        // differences over ~2000 samples reports thousands of metres of
        // "variation" for a flight that only climbed ~120 m. With a low ceiling
        // that inflated the riser from 0.25 m to several metres — coarsening the
        // wall precisely on the densest data, which is the opposite of intended.
        val rng = java.util.Random(7)
        val noisy = (0..2000).map { i ->
            val t = i / 20.0
            val profile = if (t < 12.0) 122.0 * (t / 12.0) else 122.0 * (1 - (t - 12) / 88).coerceAtLeast(0.0)
            offset(i * 0.1, 0.0, (profile + (rng.nextDouble() - 0.5) * 6.0).toFloat())
        }
        val summed = noisy.zipWithNext { a, b -> abs(b.altitudeM - a.altitudeM) }.sum()
        assertTrue("test data should be noise-dominated, got $summed m", summed > 2000f)
        assertEquals(
            "noise must not inflate the riser (summed variation was $summed m)",
            TARGET_RISER_M, curtainRiser(noisy), 1e-6f
        )
    }

    @Test
    fun anExtremePathStillRelaxesRatherThanExploding() {
        // The backstop is deliberately high, not absent: something pathological
        // must still be bounded rather than emitting unbounded geometry.
        val absurd = listOf(
            offset(0.0, 0.0, 0f), offset(500.0, 0.0, 30_000f), offset(1000.0, 0.0, 0f),
        )
        assertTrue("riser should relax on an absurd path", curtainRiser(absurd) > TARGET_RISER_M)
        assertTrue(
            "quad count should stay bounded",
            altitudeCurtain(absurd).features()!!.size <= 21_000
        )
    }

    @Test
    fun everyRiserStaysUnderTheBudget() {
        // The invariant the smoothness actually depends on: no step in the
        // wall's top edge taller than the path's riser target, across a segment
        // whose split is not capped.
        val path = listOf(offset(0.0, 0.0, 0f), offset(20.0, 0.0, 40f))
        val h = heights(altitudeCurtain(path))
        h.zipWithNext { a, b ->
            // Tolerance is float-rounding slack, not budget slack: heights are
            // Float and accumulate ~1e-6 of error across the walk.
            assertTrue("riser ${b - a} exceeds budget: $h", abs(b - a) <= TARGET_RISER_M + 1e-3)
        }
    }

    @Test
    fun levelFlightIsNotSubdividedAtAll() {
        // Sub-segments of a level interval would all carry the same height, so
        // splitting it buys nothing and just multiplies the feature count on a
        // source that is rebuilt on every telemetry message.
        val path = listOf(
            offset(0.0, 0.0, 300f),
            offset(100.0, 0.0, 300f),
            offset(200.0, 0.0, 300f),
        )
        assertEquals(2, altitudeCurtain(path).features()!!.size)
    }

    @Test
    fun heightsRampAcrossTheSegmentRatherThanStepAtItsEnds() {
        // A single climbing segment: sub-segment heights must increase
        // monotonically and stay strictly inside the endpoint altitudes, since
        // each is the mean of its own sub-endpoints.
        val path = listOf(offset(0.0, 0.0, 0f), offset(100.0, 0.0, 800f))
        val h = heights(altitudeCurtain(path))

        assertEquals(MAX_SUBDIVISIONS, h.size)   // 800 m of climb hits the cap
        h.zipWithNext { a, b -> assertTrue("heights not increasing: $h", b > a) }
        assertTrue("first step too low: ${h.first()}", h.first() > 0.0)
        assertTrue("last step exceeds apogee: ${h.last()}", h.last() < 800.0)
        // Mid-segment should sit near the midpoint altitude.
        assertEquals(400.0, h[h.size / 2 - 1] / 2 + h[h.size / 2] / 2, 60.0)
    }

    @Test
    fun descentProducesDecreasingHeights() {
        val path = listOf(offset(0.0, 0.0, 500f), offset(50.0, 50.0, 20f))
        val h = heights(altitudeCurtain(path))
        h.zipWithNext { a, b -> assertTrue("heights not decreasing: $h", b < a) }
    }

    // ── Wall geometry ───────────────────────────────────────────────────────

    @Test
    fun wallWidthIsCorrectInMetersRegardlessOfHeading() {
        // Guards the half-width constant and the normalisation step. Note this
        // does NOT catch a missing cos(lat) correction: the normal is normalised
        // to a fixed length before being converted back to degrees, so its
        // magnitude survives that bug and only its direction skews — which is
        // what wallIsPerpendicularToItsSegment covers (verified by injecting the
        // bug: this test still passed, that one failed).
        for ((north, east) in listOf(100.0 to 0.0, 0.0 to 100.0, 70.0 to 70.0)) {
            val path = listOf(offset(0.0, 0.0, 100f), offset(north, east, 100f))
            val poly = altitudeCurtain(path).features()!!.first().geometry() as Polygon
            val ring = poly.coordinates()[0]

            // Ring is [left0, left1, right1, right0, left0] — the first and last
            // corners straddle the segment start, two half-widths apart.
            val width = metersBetween(
                ring[0].longitude(), ring[0].latitude(),
                ring[3].longitude(), ring[3].latitude(),
            )
            assertEquals(
                "wall width wrong for heading N=$north E=$east",
                HALF_WIDTH_M * 2, width, 0.05,
            )
        }
    }

    @Test
    fun wallIsPerpendicularToItsSegment() {
        // The offset must be normal to the direction of travel, not an arbitrary
        // diagonal — otherwise the wall leans away from the track.
        val path = listOf(offset(0.0, 0.0, 100f), offset(100.0, 100.0, 100f))
        val poly = altitudeCurtain(path).features()!!.first().geometry() as Polygon
        val ring = poly.coordinates()[0]
        val cosLat = cos(LAT * PI / 180.0)

        // Segment direction (metres) from corner 0 to corner 1.
        val segX = (ring[1].longitude() - ring[0].longitude()) * METERS_PER_DEG_LAT * cosLat
        val segY = (ring[1].latitude() - ring[0].latitude()) * METERS_PER_DEG_LAT
        // Width direction (metres) from corner 0 to corner 3.
        val widX = (ring[3].longitude() - ring[0].longitude()) * METERS_PER_DEG_LAT * cosLat
        val widY = (ring[3].latitude() - ring[0].latitude()) * METERS_PER_DEG_LAT

        val dot = segX * widX + segY * widY
        val norm = hypot(segX, segY) * hypot(widX, widY)
        assertTrue("wall not perpendicular (cos=${dot / norm})", abs(dot / norm) < 0.01)
    }

    @Test
    fun polygonRingsAreClosed() {
        val path = listOf(offset(0.0, 0.0, 100f), offset(100.0, 40.0, 300f))
        altitudeCurtain(path).features()!!.forEach { f ->
            val ring = (f.geometry() as Polygon).coordinates()[0]
            assertEquals("ring must have 5 points (closed quad)", 5, ring.size)
            assertEquals(ring.first().longitude(), ring.last().longitude(), 1e-12)
            assertEquals(ring.first().latitude(), ring.last().latitude(), 1e-12)
        }
    }

    @Test
    fun curtainFollowsAFullFlightProfile() {
        // Boost, apogee, descent — heights must peak once, in the middle.
        val path = listOf(
            offset(0.0, 0.0, 0f),
            offset(20.0, 5.0, 250f),
            offset(45.0, 12.0, 900f),
            offset(80.0, 30.0, 400f),
            offset(110.0, 55.0, 5f),
        )
        val h = heights(altitudeCurtain(path))
        assertTrue(h.isNotEmpty())
        val peakIndex = h.indices.maxByOrNull { h[it] }!!
        assertTrue("peak at start", peakIndex > 0)
        assertTrue("peak at end", peakIndex < h.size - 1)
        assertTrue("peak height ${h[peakIndex]} not near apogee", h[peakIndex] > 800.0)
    }
}
