package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.FlightSample
import com.steampigeon.flightmanager.data.Vec3f
import com.steampigeon.flightmanager.ui.archivedPathPoints
import com.steampigeon.flightmanager.ui.secondMarkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Turning a downloaded archive record into a map path.
 *
 * The archive stores position in **radians** (`base_lat_rad` + `d_lat_scaled /
 * 1e7`), while the map takes degrees. Nothing consumed those fields before this,
 * so the unit assumption had never been exercised — and getting it wrong does not
 * fail loudly, it silently draws the flight in the wrong hemisphere. That is what
 * most of these tests are for.
 */
class ArchivedPathTest {

    private companion object {
        // The user's test site, in the radians the archive actually stores.
        const val LAT_DEG = 47.6146
        const val LON_DEG = -122.5526
        val LAT_RAD = LAT_DEG * PI / 180.0
        val LON_RAD = LON_DEG * PI / 180.0
    }

    private fun sample(
        latRad: Double = LAT_RAD,
        lonRad: Double = LON_RAD,
        altM: Float = 100f,
        tMs: Long = 0L,
    ) = FlightSample(
        timestampMs = tMs,
        altitudeM = altM,
        accel = Vec3f(0f, 0f, 0f),
        gyro = Vec3f(0f, 0f, 0f),
        latRad = latRad,
        lonRad = lonRad,
    )

    // ── Units ───────────────────────────────────────────────────────────────

    @Test
    fun radiansAreConvertedToDegrees() {
        // The whole feature rests on this. Treating the stored radians as degrees
        // would put this flight at 0.83°N 2.14°W — in the Atlantic, ~5000 km off,
        // with no error raised anywhere.
        val p = archivedPathPoints(listOf(sample())).single()
        assertEquals(LAT_DEG, p.latitude, 1e-9)
        assertEquals(LON_DEG, p.longitude, 1e-9)
    }

    @Test
    fun aSouthernAndEasternSiteKeepsItsSigns() {
        // Guards against an abs() or a swapped lat/lon creeping in.
        val latDeg = -33.8688
        val lonDeg = 151.2093
        val p = archivedPathPoints(
            listOf(sample(latRad = latDeg * PI / 180.0, lonRad = lonDeg * PI / 180.0))
        ).single()
        assertEquals(latDeg, p.latitude, 1e-9)
        assertEquals(lonDeg, p.longitude, 1e-9)
    }

    // ── Unusable positions ──────────────────────────────────────────────────

    @Test
    fun samplesWithoutAFixAreDropped() {
        // A record starts before GPS necessarily has a lock. A zero coordinate is
        // not a position on the Gulf of Guinea — it is "no fix" — and plotting it
        // would run the path to null island and wreck the map's bounds.
        val points = archivedPathPoints(
            listOf(
                sample(latRad = 0.0, lonRad = 0.0, altM = 0f),
                sample(altM = 50f),
                sample(latRad = 0.0, lonRad = 0.0, altM = 80f),
            )
        )
        assertEquals(1, points.size)
        assertEquals(50f, points.single().altitudeM)
    }

    @Test
    fun nonFiniteAndOutOfRangeCoordinatesAreDropped() {
        val points = archivedPathPoints(
            listOf(
                sample(latRad = Double.NaN),
                sample(lonRad = Double.POSITIVE_INFINITY),
                sample(latRad = 100.0 * PI / 180.0 * 2),  // > 90° after conversion
                sample(altM = 42f),                        // the only good one
            )
        )
        assertEquals(1, points.size)
        assertEquals(42f, points.single().altitudeM)
    }

    @Test
    fun anEmptyRecordProducesAnEmptyPath() {
        assertTrue(archivedPathPoints(emptyList()).isEmpty())
    }

    // ── Time axis ───────────────────────────────────────────────────────────

    @Test
    fun archiveTimestampsCarryThroughAsRealTime() {
        // The archive clock is GPS-disciplined and counts from the record start,
        // so an archived path's markers mean what they say. These points must NOT
        // be flagged synthetic — that flag is for restored pre-timestamp paths.
        val points = archivedPathPoints(
            (0..40).map { sample(altM = it * 10f, tMs = it * 50L) }
        )
        assertEquals(41, points.size)
        assertTrue("archive times must not be flagged synthetic", points.none { it.timeSynthetic })
        assertEquals(0L, points.first().timestampMs)
        assertEquals(2000L, points.last().timestampMs)
    }

    @Test
    fun aTwentyHertzRecordMarksEverySecondOfFlightTime() {
        // End to end at the real archive cadence: 5 s of samples, one marker per
        // elapsed second (t=0 excluded), each at the altitude of that second.
        val samples = (0..100).map { i ->
            sample(
                latRad = LAT_RAD + i * 1e-7,
                altM = i * 5f,           // 100 m/s at 20 Hz
                tMs = i * 50L,
            )
        }
        val marks = secondMarkers(archivedPathPoints(samples))
        val heights = marks.features()!!.map { it.getNumberProperty("height").toDouble() }
        assertEquals(5, heights.size)
        heights.forEachIndexed { i, h ->
            assertEquals("second ${i + 1}", (i + 1) * 100.0, h, 1.0)
        }
    }

    @Test
    fun droppedLeadingSamplesDoNotShiftTheTimeAxis() {
        // Pre-fix samples are removed, so the path begins at the first sample that
        // had a position. Elapsed time must run from there — not from the record
        // start — or every marker sits a fixed offset early.
        val samples = (0..60).map { i ->
            sample(
                latRad = if (i < 20) 0.0 else LAT_RAD,   // no fix for the first 20
                lonRad = if (i < 20) 0.0 else LON_RAD,
                altM = i * 5f,
                tMs = i * 50L,
            )
        }
        val points = archivedPathPoints(samples)
        assertEquals(41, points.size)
        assertEquals("path must start at the first fixed sample", 1000L, points.first().timestampMs)

        val heights = secondMarkers(points).features()!!
            .map { it.getNumberProperty("height").toDouble() }
        // Path spans t=1000..3000 ms, so marks land at 2000 and 3000 ms —
        // altitudes 200 m and 300 m.
        assertEquals(2, heights.size)
        assertEquals(200.0, heights[0], 1.0)
        assertEquals(300.0, heights[1], 1.0)
    }
}
