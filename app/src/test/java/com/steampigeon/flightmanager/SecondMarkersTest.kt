package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.ui.PathPoint
import com.steampigeon.flightmanager.ui.secondMarkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Polygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * One-second markers on the 3D flight path.
 *
 * These are read as a ruler — climb rate comes from the spacing between them —
 * so the thing under test is that a mark sits at the altitude the rocket
 * actually had one second in, not at the altitude of the nearest sample. The
 * path is live radio telemetry with a variable interval and dropped packets, so
 * counting samples would drift; every test here is built to fail if the
 * implementation ever falls back to that.
 */
class SecondMarkersTest {

    private companion object {
        const val LAT = 47.6146
        const val LON = -122.5526
        const val METERS_PER_DEG_LAT = 111_320.0
        const val T0 = 1_700_000_000_000L   // arbitrary wall-clock epoch
    }

    /** A fix [tMs] after T0, [northM] metres north of the reference point. */
    private fun fix(tMs: Long, northM: Double, altM: Float) = PathPoint(
        LAT + northM / METERS_PER_DEG_LAT, LON, altM, T0 + tMs,
    )

    private fun heights(fc: org.maplibre.geojson.FeatureCollection): List<Double> =
        fc.features()!!.map { it.getNumberProperty("height").toDouble() }

    /** A steady 20 Hz climb at [climbRate] m/s lasting [seconds]. */
    private fun climb(seconds: Int, climbRate: Float): List<PathPoint> =
        (0..seconds * 20).map { i ->
            val t = i * 50L
            fix(t, i * 0.5, climbRate * t / 1000f)
        }

    // ── Placement in time ───────────────────────────────────────────────────

    @Test
    fun oneMarkPerElapsedSecondExcludingTheStart() {
        // 5 s of flight yields marks at t=1..5. The t=0 mark is deliberately
        // absent: the rocket is on the pad and the post would have no height.
        assertEquals(5, secondMarkers(climb(5, 100f)).features()!!.size)
    }

    @Test
    fun marksLandOnTrueSecondsAtASteadyClimbRate() {
        // 100 m/s means the Nth mark must stand at exactly N × 100 m.
        val h = heights(secondMarkers(climb(5, 100f)))
        h.forEachIndexed { i, height ->
            assertEquals("mark ${i + 1} off the true second", (i + 1) * 100.0, height, 0.5)
        }
    }

    @Test
    fun marksInterpolateBetweenSamplesRatherThanSnappingToOne() {
        // Elapsed time runs from the first fix. Samples straddle the one-second
        // boundary at 0.4 s and 1.4 s, so none sits near it; snapping to the
        // nearest would put the mark at 1400 m, but it belongs at 1000 m.
        val path = listOf(
            fix(0, 0.0, 0f),
            fix(400, 4.0, 400f),
            fix(1400, 14.0, 1400f),
        )
        val h = heights(secondMarkers(path))
        assertEquals(1, h.size)
        assertEquals(1000.0, h.first(), 1.0)
    }

    @Test
    fun aDroppedPacketDoesNotShiftLaterMarks() {
        // The defining case for timestamps over sample counting: drop every fix
        // in the second second, and marks 2..5 must not slide.
        val full = climb(5, 100f)
        val gapped = full.filterNot { it.timestampMs - T0 in 1051..1949 }
        assertTrue("gap not actually created", gapped.size < full.size)

        val h = heights(secondMarkers(gapped))
        assertEquals(5, h.size)
        h.forEachIndexed { i, height ->
            assertEquals("mark ${i + 1} drifted after the dropout", (i + 1) * 100.0, height, 1.0)
        }
    }

    @Test
    fun anIrregularRadioIntervalStillYieldsEvenlySpacedMarks() {
        // Wildly uneven sample spacing over a linear 50 m/s climb: the marks are
        // a function of time, so they must still come out at 50 m intervals.
        val path = listOf(
            fix(0, 0.0, 0f),
            fix(120, 1.0, 6f),
            fix(1830, 15.0, 91.5f),
            fix(1900, 16.0, 95f),
            fix(3400, 30.0, 170f),
        )
        val h = heights(secondMarkers(path))
        assertEquals(3, h.size)
        assertEquals(50.0, h[0], 1.0)
        assertEquals(100.0, h[1], 1.0)
        assertEquals(150.0, h[2], 1.0)
    }

    @Test
    fun marksFollowTheProfileBackDownThroughApogee() {
        val path = listOf(
            fix(0, 0.0, 0f),
            fix(2000, 20.0, 600f),
            fix(4000, 60.0, 900f),   // apogee
            fix(6000, 110.0, 300f),
        )
        val h = heights(secondMarkers(path))
        val peak = h.indices.maxByOrNull { h[it] }!!
        assertTrue("peak at start", peak > 0)
        assertTrue("peak at end", peak < h.size - 1)
    }

    // ── Degenerate input ────────────────────────────────────────────────────

    @Test
    fun tooShortOrTooBriefAPathProducesNoMarks() {
        assertTrue(secondMarkers(emptyList()).features()!!.isEmpty())
        assertTrue(secondMarkers(listOf(fix(0, 0.0, 100f))).features()!!.isEmpty())
        // Under a second of recording: no boundary has been crossed yet.
        assertTrue(
            secondMarkers(listOf(fix(0, 0.0, 100f), fix(800, 5.0, 200f)))
                .features()!!.isEmpty()
        )
    }

    @Test
    fun nonMonotonicTimestampsProduceNoMarks() {
        // A clock adjustment mid-recording leaves no time axis to mark up; the
        // builder must bail rather than emit marks at invented times.
        val path = listOf(fix(5000, 0.0, 100f), fix(0, 50.0, 500f))
        assertTrue(secondMarkers(path).features()!!.isEmpty())
    }

    @Test
    fun groundLevelSecondsAreSkipped() {
        // A path recorded on the pad must not stand up zero-height posts.
        val path = listOf(fix(0, 0.0, 0f), fix(3000, 1.0, 0.1f))
        assertTrue(secondMarkers(path).features()!!.isEmpty())
    }

    @Test
    fun aStationaryDescentStillGetsItsMarks() {
        // Under canopy in still air the fix can repeat exactly. There is no
        // direction of travel to orient the post across, but the altitude is
        // still worth marking, so a fallback orientation must be used rather
        // than the mark being dropped.
        val path = listOf(fix(0, 0.0, 300f), fix(3000, 0.0, 150f))
        val fc = secondMarkers(path)
        assertEquals(3, fc.features()!!.size)
        fc.features()!!.forEach { f ->
            (f.geometry() as Polygon).coordinates()[0].forEach {
                assertTrue("NaN corner from a zero-length interval", it.latitude().isFinite())
                assertTrue("NaN corner from a zero-length interval", it.longitude().isFinite())
            }
        }
    }

    @Test
    fun markCountIsCapped() {
        // An hour-long recording left running must not emit 3600 posts, all
        // rebuilt on every telemetry message.
        val path = listOf(fix(0, 0.0, 500f), fix(3_600_000, 5000.0, 500f))
        assertTrue(secondMarkers(path).features()!!.size <= 600)
    }

    // ── Geometry ────────────────────────────────────────────────────────────

    @Test
    fun postsAreClosedRingsWiderThanTheCurtainTheyMark() {
        // The post must protrude from the 1.5 m-wide curtain on both faces, or
        // the coincident geometry z-fights instead of reading as a mark.
        val fc = secondMarkers(climb(3, 100f))
        val cosLat = cos(LAT * PI / 180.0)
        fc.features()!!.forEach { f ->
            val ring = (f.geometry() as Polygon).coordinates()[0]
            assertEquals("ring must have 5 points (closed quad)", 5, ring.size)
            assertEquals(ring.first().longitude(), ring.last().longitude(), 1e-12)
            assertEquals(ring.first().latitude(), ring.last().latitude(), 1e-12)

            // Corners 0 and 3 straddle the post across the track.
            val dx = (ring[3].longitude() - ring[0].longitude()) * METERS_PER_DEG_LAT * cosLat
            val dy = (ring[3].latitude() - ring[0].latitude()) * METERS_PER_DEG_LAT
            assertEquals("post width", 3.2, hypot(dx, dy), 0.05)
        }
    }
}
