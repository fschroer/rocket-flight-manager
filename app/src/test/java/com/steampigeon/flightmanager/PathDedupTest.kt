package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.ui.PathPoint
import com.steampigeon.flightmanager.ui.repeatsFix
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recording-time de-duplication of the flight path.
 *
 * In flight the locator transmits at ~5 Hz while its position/altitude payload
 * refreshes at ~1 Hz, so about five consecutive frames repeat one fix. The
 * guard's job is to drop exactly those repeats and nothing else — the risk on
 * the other side is silently swallowing real slow movement, which is what a
 * descent under canopy looks like.
 */
class PathDedupTest {

    private companion object {
        const val LAT = 47.6146
        const val LON = -122.5526
    }

    private fun point(lat: Double = LAT, lon: Double = LON, alt: Float = 100f) =
        PathPoint(lat, lon, alt, 1_700_000_000_000L)

    @Test
    fun theFirstFixIsNeverARepeat() {
        // Nothing to compare against — an empty path must always accept.
        assertFalse(repeatsFix(null, LAT, LON, 100f))
    }

    @Test
    fun anIdenticalFixIsARepeat() {
        // The case this exists for: the 2nd through 5th copies of one payload.
        assertTrue(repeatsFix(point(), LAT, LON, 100f))
    }

    @Test
    fun anyChangedComponentDefeatsTheRepeat() {
        // Each field is checked independently: the locator's baro and GPS
        // refresh on different schedules, so altitude alone routinely moves
        // while the position is still latched. Missing that would flatten the
        // altitude profile the curtain is drawn from.
        assertFalse("latitude change missed", repeatsFix(point(), LAT + 1e-7, LON, 100f))
        assertFalse("longitude change missed", repeatsFix(point(), LAT, LON + 1e-7, 100f))
        assertFalse("altitude change missed", repeatsFix(point(), LAT, LON, 100.01f))
    }

    @Test
    fun aSlowDriftIsNotSwallowed() {
        // Under canopy in light wind the rocket moves centimetres per fix. Those
        // are real measurements and must all be recorded; a tolerance-based
        // comparison would erase the drift and with it the landing track.
        var last = point(alt = 300f)          // the fix the drift starts from
        var accepted = 0
        for (i in 1..20) {
            val lat = LAT + i * 1e-7          // ~1 cm steps
            val alt = 300f - i * 0.25f
            if (!repeatsFix(last, lat, LON, alt)) {
                accepted++
                last = PathPoint(lat, LON, alt, 0L)
            }
        }
        assertTrue("drift was swallowed: only $accepted of 20 kept", accepted == 20)
    }

    @Test
    fun aStationaryRocketCollapsesToOnePoint() {
        // Landed and still transmitting: every later frame repeats, so the path
        // stops growing instead of accreting hundreds of identical vertices.
        val last = point(alt = 0.2f)
        repeat(50) { assertTrue(repeatsFix(last, LAT, LON, 0.2f)) }
    }

    @Test
    fun aFiveFoldBurstReducesToItsDistinctFixes() {
        // End to end on the observed pattern: 5 copies of each of 12 fixes must
        // record as 12 points, in order, with no fix lost.
        val fixes = (0 until 12).map { Triple(LAT + it * 1e-5, LON, 100f + it * 40f) }
        val recorded = mutableListOf<PathPoint>()
        for ((lat, lon, alt) in fixes) {
            repeat(5) {
                if (!repeatsFix(recorded.lastOrNull(), lat, lon, alt)) {
                    recorded += PathPoint(lat, lon, alt, 0L)
                }
            }
        }
        assertTrue("expected 12 points, got ${recorded.size}", recorded.size == 12)
        recorded.forEachIndexed { i, p ->
            assertTrue("fix $i altitude wrong", p.altitudeM == 100f + i * 40f)
        }
    }

    @Test
    fun aFixThatReturnsToAnEarlierValueIsStillRecorded() {
        // Only the immediately preceding point is compared. A rocket that
        // descends back through an altitude it already passed must record it —
        // this is a repeat-suppressor, not a set.
        val last = point(alt = 50f)
        assertFalse(repeatsFix(last, LAT, LON, 120f))
        // ...and coming back down to 50 m later, against a different last point:
        assertFalse(repeatsFix(point(alt = 120f), LAT, LON, 50f))
    }
}
