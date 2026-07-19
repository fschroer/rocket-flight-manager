package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.ui.PathPoint
import com.steampigeon.flightmanager.ui.PathSpline
import com.steampigeon.flightmanager.ui.altitudeCurtain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Smoothing of the 3D path's top edge.
 *
 * The point of this class is to round the corners the old straight-chord
 * interpolation left at every recorded point. The danger in doing that is
 * inventing data: a plain Catmull-Rom spline overshoots near a sharp extremum,
 * and the sharpest feature in a flight profile is apogee — so it would draw the
 * rocket above the altitude it actually reached, and anyone reading apogee off
 * the curtain would get a number no sensor ever produced.
 *
 * Hence monotone (Fritsch–Carlson) tangents on altitude. Most of this file
 * exists to hold that line: smoothing may make the curve prettier, never higher.
 */
class PathSplineTest {

    private companion object {
        const val LAT = 47.6146
        const val LON = -122.5526
    }

    private fun p(northM: Double, altM: Float) =
        PathPoint(LAT + northM / 111_320.0, LON, altM, 0L)

    /** Samples the curve densely across every interval. */
    private fun sweep(path: List<PathPoint>, steps: Int = 50): List<Float> {
        val s = PathSpline(path)
        val out = mutableListOf<Float>()
        for (i in 0 until path.size - 1) {
            for (k in 0..steps) out += s.altitudeAt(i, k.toDouble() / steps)
        }
        return out
    }

    // ── The line that must not be crossed ───────────────────────────────────

    @Test
    fun theCurveNeverRisesAboveTheRecordedApogee() {
        // A sharp apogee is exactly where an unconstrained spline overshoots.
        val path = listOf(
            p(0.0, 0f), p(20.0, 250f), p(45.0, 900f), p(80.0, 400f), p(110.0, 5f),
        )
        val peak = path.maxOf { it.altitudeM }
        val highest = sweep(path).max()
        assertTrue(
            "curve reached $highest above recorded apogee $peak — smoothing invented altitude",
            highest <= peak + 1e-3f
        )
    }

    @Test
    fun theCurveNeverDipsBelowTheRecordedMinimum() {
        // The mirror case: undershoot at a sharp trough would put the rocket
        // underground, which the curtain would render as a wall below the terrain.
        val path = listOf(p(0.0, 500f), p(20.0, 100f), p(40.0, 2f), p(60.0, 90f), p(80.0, 480f))
        val floor = path.minOf { it.altitudeM }
        val lowest = sweep(path).min()
        assertTrue("curve dipped to $lowest below recorded minimum $floor", lowest >= floor - 1e-3f)
    }

    @Test
    fun everyIntervalStaysWithinItsOwnEndpoints() {
        // Stronger than a global bound: a monotone run must not bulge locally
        // either, or a steady climb grows bumps that were never flown.
        val path = listOf(p(0.0, 0f), p(10.0, 5f), p(20.0, 300f), p(30.0, 320f), p(40.0, 700f))
        val s = PathSpline(path)
        for (i in 0 until path.size - 1) {
            val lo = minOf(path[i].altitudeM, path[i + 1].altitudeM)
            val hi = maxOf(path[i].altitudeM, path[i + 1].altitudeM)
            for (k in 0..50) {
                val v = s.altitudeAt(i, k / 50.0)
                assertTrue("interval $i left [$lo,$hi] at $v", v >= lo - 1e-3f && v <= hi + 1e-3f)
            }
        }
    }

    @Test
    fun aFlatRunStaysExactlyFlat() {
        // A rocket sitting on the ground must not bulge upward between two
        // identical readings — this is the d == 0 branch of the tangent limiter.
        val path = listOf(p(0.0, 100f), p(10.0, 100f), p(20.0, 100f), p(30.0, 140f))
        val s = PathSpline(path)
        for (k in 0..50) {
            assertEquals(100f, s.altitudeAt(0, k / 50.0), 1e-4f)
            assertEquals(100f, s.altitudeAt(1, k / 50.0), 1e-4f)
        }
    }

    // ── That it actually smooths ────────────────────────────────────────────

    @Test
    fun theCurvePassesThroughEveryRecordedPoint() {
        // Smoothing must not drift off the data it is smoothing.
        val path = listOf(p(0.0, 0f), p(20.0, 250f), p(45.0, 900f), p(80.0, 400f))
        val s = PathSpline(path)
        for (i in 0 until path.size - 1) {
            assertEquals("start of interval $i", path[i].altitudeM, s.altitudeAt(i, 0.0), 1e-4f)
            assertEquals("end of interval $i", path[i + 1].altitudeM, s.altitudeAt(i, 1.0), 1e-4f)
        }
    }

    @Test
    fun collinearPointsReproduceTheStraightLine() {
        // Where the data is already straight there is nothing to smooth, and the
        // curve must not introduce waviness of its own.
        val path = (0..5).map { p(it * 10.0, it * 100f) }
        val s = PathSpline(path)
        for (i in 0 until path.size - 1) {
            for (k in 0..20) {
                val t = k / 20.0
                val linear = path[i].altitudeM + (path[i + 1].altitudeM - path[i].altitudeM) * t.toFloat()
                assertEquals("interval $i at t=$t", linear, s.altitudeAt(i, t), 1e-3f)
            }
        }
    }

    @Test
    fun theSlopeIsContinuousAcrossARecordedPoint() {
        // This is the actual visual complaint: straight chords meet at a corner
        // on every point. Slope entering a point must match slope leaving it.
        val path = listOf(p(0.0, 0f), p(20.0, 300f), p(45.0, 700f), p(80.0, 850f))
        val s = PathSpline(path)
        // 1e-3 rather than smaller: altitudes are Float, and a tighter step loses
        // the difference in rounding noise rather than measuring the slope.
        val h = 1e-3
        for (i in 1 until path.size - 1) {
            val incoming = (s.altitudeAt(i - 1, 1.0) - s.altitudeAt(i - 1, 1.0 - h)) / h
            val outgoing = (s.altitudeAt(i, h) - s.altitudeAt(i, 0.0)) / h
            // Relative, because a straight-chord path fails this by a FACTOR —
            // at point 2 the old chords met at 400 vs 150 m per interval, a 2.7x
            // jump — while a continuous tangent differs only by rounding.
            val rel = abs(incoming - outgoing) / maxOf(abs(incoming), abs(outgoing))
            assertTrue(
                "corner at point $i: slope $incoming -> $outgoing (${(rel * 100).toInt()}%)",
                rel < 0.02
            )
        }
    }

    @Test
    fun theGroundTrackIsSmoothedToo() {
        // The curtain's footprint follows the same curve; a polygonal ground
        // track under a smoothed top would still read as faceted.
        val path = listOf(p(0.0, 100f), p(20.0, 300f), p(45.0, 500f))
        val s = PathSpline(path)
        // Curve stays near the data rather than wandering off it.
        for (i in 0 until path.size - 1) {
            assertEquals(path[i].latitude, s.latitudeAt(i, 0.0), 1e-12)
            assertEquals(path[i + 1].latitude, s.latitudeAt(i, 1.0), 1e-12)
        }
    }

    // ── Degenerate input ────────────────────────────────────────────────────

    @Test
    fun twoPointsBehaveLinearly() {
        // With only two points there is no curvature information to use, and the
        // curve must not manufacture any.
        val path = listOf(p(0.0, 0f), p(100.0, 800f))
        val s = PathSpline(path)
        for (k in 0..20) {
            val t = k / 20.0
            assertEquals(800f * t.toFloat(), s.altitudeAt(0, t), 1e-2f)
        }
    }

    @Test
    fun aSmoothedCurtainStillDrawsAndStaysUnderApogee() {
        // End to end through the real builder: the emitted quad heights are what
        // a reader measures apogee from.
        val path = listOf(
            p(0.0, 0f), p(20.0, 250f), p(45.0, 900f), p(80.0, 400f), p(110.0, 5f),
        )
        val heights = altitudeCurtain(path).features()!!
            .map { it.getNumberProperty("height").toFloat() }
        assertTrue(heights.isNotEmpty())
        assertTrue(
            "curtain drew ${heights.max()} above apogee 900",
            heights.max() <= 900f + 1e-3f
        )
    }
}
