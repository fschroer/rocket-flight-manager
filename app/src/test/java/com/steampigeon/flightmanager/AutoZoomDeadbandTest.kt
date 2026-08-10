package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.ui.AUTO_ZOOM_DEADBAND_MAX_LEVELS
import com.steampigeon.flightmanager.ui.AUTO_ZOOM_DEADBAND_MIN_LEVELS
import com.steampigeon.flightmanager.ui.autoZoomDeadbandLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The auto-zoom deadband: how far the fitted zoom has to drift before the map
 * follows it.
 *
 * Auto-zoom frames a box around the phone and the rocket, so its input is the
 * SEPARATION between them — and at recovery range that separation is mostly the
 * two receivers disagreeing with each other. Measured on a stationary locator,
 * Dist read 6, 7 and 11 m within a couple of minutes and the camera zoom swung
 * 0.6 levels chasing it.
 *
 * The closest-zoom limit does not cover this and it is worth being exact about
 * why, because the setting looks like it should: the limit binds how far IN the
 * fit may go, and these swings happen at and below it. Zoom recovered from the
 * rendered scale bar across four captures read 20.57, 20.46, 20.01 and 19.96
 * against Dist of 6, 7, 11 and 11 m — tracking continuously, which a clamped
 * value cannot do.
 */
class AutoZoomDeadbandTest {

    /** Zoom levels between two separations: zoom is log2 in the framed distance. */
    private fun levelsBetween(d1: Double, d2: Double) = abs(log2(d1 / d2)).toFloat()

    // ── Sizing ────────────────────────────────────────────────────────────────

    @Test
    fun theBandIsTheLogDerivativeOfTheSeparationError() {
        // 2σ of the drift between two noisy samples, converted through the log:
        // σ_zoom = √(σ_locator² + σ_phone²) / (D·ln2). No halving, unlike the
        // centering band — separation is a difference between the two fixes, so
        // both errors land on it at full weight rather than half.
        val sigmaSep = sqrt(3.0 * 3.0 + 5.0 * 5.0)
        val expected = (2.0 * sqrt(2.0) * sigmaSep / (50.0 * ln(2.0))).toFloat()
        assertEquals(expected, autoZoomDeadbandLevels(3f, 5f, 50.0), 1e-4f)
    }

    @Test
    fun theBandWidensAsTheFixesCloseIn() {
        // The property that makes this work at all. The same few meters of error
        // is most of a zoom level when the two are close together and nothing when
        // they are far apart, so a band in zoom space self-scales where a band in
        // meters of separation could not.
        var previous = autoZoomDeadbandLevels(3f, 5f, 500.0)
        for (d in listOf(400.0, 300.0, 200.0, 100.0, 50.0, 25.0, 12.0, 6.0, 3.0)) {
            val current = autoZoomDeadbandLevels(3f, 5f, d)
            assertTrue("separation $d m narrowed the band", current >= previous)
            previous = current
        }
    }

    @Test
    fun theMeasuredFieldSwingIsSuppressed() {
        // The actual observation this exists for: a stationary locator whose
        // reported separation wandered 6 m to 11 m. That is log2(11/6) = 0.87
        // levels of fitted zoom, and it must not move the camera.
        val swing = levelsBetween(11.0, 6.0)
        assertEquals(0.87f, swing, 0.01f)
        // Phone accuracy ~7 m and a good locator fix, which is what the field
        // capture showed (large blue accuracy circle, no ring on the rocket).
        val band = autoZoomDeadbandLevels(3f, 7f, 8.0)
        assertTrue("swing $swing levels vs band $band levels", swing < band)
    }

    @Test
    fun aRealApproachStillReframes() {
        // The band must not be so wide that walking in never re-zooms. Closing
        // from 30 m to 5 m is 2.6 levels of genuine change and has to get through,
        // or the map would stay framed for 30 m while the user stood on top of it.
        val approach = levelsBetween(30.0, 5.0)
        assertTrue("approach is only $approach levels", approach > AUTO_ZOOM_DEADBAND_MAX_LEVELS)
    }

    @Test
    fun theBandIsHeldWithinItsRange() {
        // Wide end: separation and error comparable, where the honest statistics
        // say the fitted zoom carries several levels of uncertainty. Uncapped that
        // means "never move", which is worse than the pumping it replaces.
        assertEquals(AUTO_ZOOM_DEADBAND_MAX_LEVELS, autoZoomDeadbandLevels(20f, 20f, 3.0), 1e-4f)
        // Narrow end: far apart with good fixes, where the fit is trustworthy and
        // the band should get out of the way.
        assertEquals(AUTO_ZOOM_DEADBAND_MIN_LEVELS, autoZoomDeadbandLevels(1f, 1f, 5000.0), 1e-4f)
    }

    @Test
    fun anUnknownSeparationGivesTheWidestBand() {
        // Failing toward "hold" is the right direction: not knowing how far apart
        // the two are is not evidence that the zoom ought to move.
        for (d in listOf(0.0, -1.0, Double.NaN, Double.NEGATIVE_INFINITY)) {
            assertEquals(
                "separation $d",
                AUTO_ZOOM_DEADBAND_MAX_LEVELS, autoZoomDeadbandLevels(3f, 5f, d), 1e-4f,
            )
        }
    }

    @Test
    fun garbageAccuracyStaysInRange() {
        // hacc is a raw float off the wire and Android reports 0 for "not set", so
        // neither input can be assumed to be a positive number. A NaN reaching the
        // comparison would make it false forever and freeze auto-zoom outright.
        val garbage = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 0f)
        for (locator in garbage) {
            for (phone in garbage + listOf(5f)) {
                val band = autoZoomDeadbandLevels(locator, phone, 40.0)
                assertTrue(
                    "locator=$locator phone=$phone gave $band",
                    band.isFinite() &&
                        band in AUTO_ZOOM_DEADBAND_MIN_LEVELS..AUTO_ZOOM_DEADBAND_MAX_LEVELS,
                )
            }
            val band = autoZoomDeadbandLevels(locator, null, 40.0)
            assertTrue(
                "locator=$locator with no tracker fix gave $band",
                band.isFinite() &&
                    band in AUTO_ZOOM_DEADBAND_MIN_LEVELS..AUTO_ZOOM_DEADBAND_MAX_LEVELS,
            )
        }
    }

    // ── Settling ──────────────────────────────────────────────────────────────

    /** Mirrors the anchor rule in MapCameraController for a sequence of fitted zooms. */
    private fun latchCount(fits: List<Float>, band: (Float) -> Float): Int {
        var anchor: Float? = null
        var latches = 0
        for (f in fits) {
            val a = anchor
            if (a == null || abs(f - a) > band(f)) { anchor = f; latches++ }
        }
        return latches
    }

    @Test
    fun aStationaryPairStopsMovingTheZoom() {
        // 600 fixes of two stationary receivers ~8 m apart. Without a deadband
        // every one of these re-fits the zoom.
        //
        // Jitter is modeled at 2.5 m, NOT at the 7 m the phone reports as its
        // accuracy, and the gap between those two numbers is the point rather than
        // a fudge. Reported accuracy is a confidence radius that includes
        // systematic bias — multipath, ephemeris, ionosphere — which is largely
        // common to consecutive fixes and does not make the reading move.
        // Sample-to-sample wander is much smaller, and 2.5 m is what the field
        // capture actually showed: a stationary locator whose reported separation
        // ranged 6 m to 11 m.
        //
        // So the band, being derived from reported accuracy, is wider than the
        // jitter it has to suppress. That is the safe direction — it errs toward
        // holding — but it is why this test asserts against observed movement
        // rather than against the number the receivers quote.
        val rng = Random(11)
        val fits = List(600) {
            val u1 = rng.nextDouble().coerceAtLeast(1e-12)
            val u2 = rng.nextDouble()
            val gauss = sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
            val reported = (8.0 + gauss * 2.5).coerceAtLeast(1.0)
            // Fitted zoom for that separation, up to an arbitrary constant.
            (24.0 - log2(reported)).toFloat()
        }
        val latches = latchCount(fits) { autoZoomDeadbandLevels(3f, 7f, 8.0) }
        // Same bar as the centering deadband: under a tenth of fixes, against one
        // in one without a band at all. Measures ~7%.
        //
        // The residual is not ordinary noise, it is the tail, and it arrives in
        // bursts. The band around 8 m spans roughly 2.8 m to 22.6 m, which a
        // 2.5 m-sigma reading clears about 2% of the time — but the anchor then
        // latches onto that outlier, and from 2.8 m the ordinary 8 m readings are
        // outside ITS band, so it re-latches two or three more times on the way
        // back. Latching to the live value is what makes the filter converge
        // (see the centering deadband); paying for it in short bursts after an
        // outlier is the other side of that choice.
        assertTrue("re-latched $latches times in 600 stationary fixes", latches < 600 / 10)
    }

    @Test
    fun walkingInIsFollowedWithBoundedLag() {
        // Steady real approach, 40 m down to 4 m. The zoom has to keep up, and the
        // lag it may build is one band — never more, however long the walk.
        var separation = 40.0
        var anchor: Float? = null
        var worstLag = 0f
        while (separation > 4.0) {
            val fit = (24.0 - log2(separation)).toFloat()
            val band = autoZoomDeadbandLevels(3f, 5f, separation)
            val a = anchor
            if (a == null || abs(fit - a) > band) anchor = fit
            worstLag = maxOf(worstLag, abs(fit - anchor!!))
            separation -= 0.5
        }
        assertTrue("lag reached $worstLag levels", worstLag <= AUTO_ZOOM_DEADBAND_MAX_LEVELS + 0.01f)
    }
}
