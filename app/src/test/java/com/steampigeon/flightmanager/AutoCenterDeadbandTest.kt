package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.ui.RECENTER_DEADBAND_MAX_M
import com.steampigeon.flightmanager.ui.RECENTER_DEADBAND_MIN_M
import com.steampigeon.flightmanager.ui.autoZoomPadding
import com.steampigeon.flightmanager.ui.metersBetween
import com.steampigeon.flightmanager.ui.metersPerDevicePx
import com.steampigeon.flightmanager.ui.metersPerDp
import com.steampigeon.flightmanager.ui.recenterDeadbandM
import com.steampigeon.flightmanager.ui.viewportLimitedDeadbandM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The auto-center deadband: how far the framed center has to drift before the
 * map follows it.
 *
 * Same underlying problem as the auto-zoom cap, on the other axis. Both receivers
 * keep issuing fresh fixes while nothing is moving, so the point between them
 * wanders a few meters a second forever. Filtering alone does not fix that — a
 * filter with no deadband tracks a random walk faithfully, just smoothly, and the
 * imagery creeps under a rocket that is lying still in a field.
 *
 * The camera therefore follows a latched anchor and re-latches only when the live
 * center clears a distance sized from what the two receivers say their own error
 * is. These tests pin the sizing and the settling behavior that depends on it.
 */
class AutoCenterDeadbandTest {

    // ── Sizing ────────────────────────────────────────────────────────────────

    @Test
    fun bothFixesFramedGivesTheMidpointFormula() {
        // 2·√2·σ_target, with σ_target = ½·√(σ_locator² + σ_phone²): the camera
        // targets the point between the two, which moves half as far as they do,
        // and the anchor it is measured against is itself a noisy sample.
        val expected = 2f * sqrt(2f) * 0.5f * sqrt(3f * 3f + 5f * 5f)
        assertEquals(expected, recenterDeadbandM(3f, 5f), 1e-4f)
        // Sanity on the magnitude, so a refactor that loses a factor is visible as
        // more than a formula restated twice: a good fix at both ends is ~8 m.
        assertEquals(8.25f, recenterDeadbandM(3f, 5f), 0.05f)
    }

    @Test
    fun aRocketOnlyTargetCarriesTheFullLocatorError() {
        // No tracker fix means no midpoint — the camera sits on the rocket itself,
        // so its jitter is the locator's whole error rather than half the combined
        // one. The deadband has to be correspondingly wider for the same locator.
        assertTrue(recenterDeadbandM(6f, null) > recenterDeadbandM(6f, 6f))
        assertEquals(2f * sqrt(2f) * 6f, recenterDeadbandM(6f, null), 1e-4f)
    }

    @Test
    fun aWorseFixAtEitherEndWidensTheBand() {
        // Monotonic in both inputs. A receiver reporting more error must never buy
        // a tighter deadband, which is the shape of bug that shows up only in the
        // field, under canopy, while someone is walking.
        var previous = recenterDeadbandM(1f, 5f)
        for (hacc in 2..30) {
            val current = recenterDeadbandM(hacc.toFloat(), 5f)
            assertTrue("hacc=$hacc narrowed the band", current >= previous)
            previous = current
        }
        previous = recenterDeadbandM(5f, 1f)
        for (phone in 2..30) {
            val current = recenterDeadbandM(5f, phone.toFloat())
            assertTrue("phoneAcc=$phone narrowed the band", current >= previous)
            previous = current
        }
    }

    @Test
    fun anOptimisticFixIsHeldAtTheFloor() {
        // A receiver claiming sub-meter accuracy would compute a deadband smaller
        // than the jitter actually observed, and the map would creep exactly as it
        // did before the deadband existed.
        assertEquals(RECENTER_DEADBAND_MIN_M, recenterDeadbandM(0.5f, 0.5f), 1e-4f)
        assertEquals(RECENTER_DEADBAND_MIN_M, recenterDeadbandM(1f, null), 1e-4f)
    }

    @Test
    fun aHopelessFixIsHeldAtTheCeiling() {
        // Without a ceiling, a locator reporting hundreds of meters under canopy
        // would park the camera and leave it there while the user walks past it.
        assertEquals(RECENTER_DEADBAND_MAX_M, recenterDeadbandM(200f, 300f), 1e-4f)
        assertEquals(RECENTER_DEADBAND_MAX_M, recenterDeadbandM(500f, null), 1e-4f)
    }

    @Test
    fun garbageAccuracyDoesNotEscapeTheRange() {
        // hacc arrives off the wire as a raw float and Android reports 0 for
        // "accuracy not set", so neither value can be trusted to be a number, let
        // alone a positive one. A NaN reaching the comparison would make it false
        // forever and freeze auto-center outright.
        val garbage = listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1f, 0f)
        for (locator in garbage) {
            for (phone in garbage + listOf(5f)) {
                val band = recenterDeadbandM(locator, phone)
                assertTrue(
                    "locator=$locator phone=$phone gave $band",
                    band.isFinite() && band in RECENTER_DEADBAND_MIN_M..RECENTER_DEADBAND_MAX_M,
                )
            }
            val band = recenterDeadbandM(locator, null)
            assertTrue(
                "locator=$locator with no tracker fix gave $band",
                band.isFinite() && band in RECENTER_DEADBAND_MIN_M..RECENTER_DEADBAND_MAX_M,
            )
        }
    }

    // ── Distance ──────────────────────────────────────────────────────────────

    @Test
    fun aDegreeOfLatitudeIsTheExpectedDistance() {
        assertEquals(111_319.5, metersBetween(0.0, 0.0, 1.0, 0.0), 1.0)
    }

    @Test
    fun longitudeShrinksWithLatitude() {
        // The cos(lat) term. Without it the deadband would be ~30% too permissive
        // east-west at mid-latitudes and worse further north.
        val atEquator = metersBetween(0.0, 0.0, 0.0, 0.001)
        val atForty = metersBetween(40.0, -105.0, 40.0, -104.999)
        assertEquals(111.32, atEquator, 0.01)
        assertEquals(111.32 * cos(40.0 * PI / 180.0), atForty, 0.01)
    }

    @Test
    fun theAntimeridianIsMeasuredTheShortWay() {
        // A launch site near the dateline would otherwise measure two adjacent
        // fixes as most of the way round the planet, permanently clearing the
        // deadband and re-latching the anchor on every single fix.
        assertEquals(222.6, metersBetween(0.0, 179.999, 0.0, -179.999), 0.1)
        assertEquals(222.6, metersBetween(0.0, -179.999, 0.0, 179.999), 0.1)
    }

    @Test
    fun distanceIsSymmetricAndZeroForAPointAgainstItself() {
        assertEquals(0.0, metersBetween(40.1, -105.2, 40.1, -105.2), 1e-9)
        assertEquals(
            metersBetween(40.0, -105.0, 40.0005, -105.0007),
            metersBetween(40.0005, -105.0007, 40.0, -105.0),
            1e-9,
        )
    }

    // ── Settling ──────────────────────────────────────────────────────────────

    /**
     * Mirrors the anchor rule in MapCameraController: re-latch when the live
     * target has cleared the deadband, otherwise leave the anchor alone.
     */
    private fun runAnchor(targets: List<Pair<Double, Double>>, deadbandM: Float): Int {
        var anchor: Pair<Double, Double>? = null
        var latches = 0
        for (t in targets) {
            val a = anchor
            if (a == null || metersBetween(a.first, a.second, t.first, t.second) > deadbandM) {
                anchor = t
                latches++
            }
        }
        return latches
    }

    /** Fixes scattered around a fixed truth with the given radial 1σ error. */
    private fun noisyFixes(count: Int, sigmaM: Double, seed: Int): List<Pair<Double, Double>> {
        val rng = Random(seed)
        val lat0 = 40.0
        val lon0 = -105.0
        val mPerDegLat = 111_319.5
        val mPerDegLon = mPerDegLat * cos(lat0 * PI / 180.0)
        return List(count) {
            // Box-Muller on each axis, scaled so the radial magnitude has the
            // requested 1σ — the per-axis σ is therefore sigma/√2.
            val axis = sigmaM / sqrt(2.0)
            fun gauss(): Double {
                val u1 = rng.nextDouble().coerceAtLeast(1e-12)
                val u2 = rng.nextDouble()
                return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * PI * u2) * axis
            }
            Pair(lat0 + gauss() / mPerDegLat, lon0 + gauss() / mPerDegLon)
        }
    }

    @Test
    fun aStationaryRocketStopsMovingTheMap() {
        // The whole point. 600 fixes — ten minutes at the locator's cadence —
        // of a rocket lying still, seen by receivers reporting 3 m and 5 m.
        // Without a deadband every one of those re-centers the map.
        val deadband = recenterDeadbandM(3f, 5f)
        val sigmaTarget = 0.5 * sqrt(3.0 * 3.0 + 5.0 * 5.0)
        val latches = runAnchor(noisyFixes(600, sigmaTarget, seed = 7), deadband)
        assertTrue(
            "re-latched $latches times in 600 stationary fixes",
            latches < 600 / 10,
        )
    }

    @Test
    fun theBandIsNeverSoWideThatItSwallowsRealMotion() {
        // A deadband that suppresses actual travel is worse than none: the map
        // would sit on empty ground while the rocket drifted off the screen. At
        // the ceiling the widest band is 40 m, so anything past that must move
        // the camera even on the worst fixes either receiver can report.
        val worst = recenterDeadbandM(999f, 999f)
        val start = Pair(40.0, -105.0)
        val movedFar = Pair(40.0 + 200.0 / 111_319.5, -105.0)
        assertTrue(
            metersBetween(start.first, start.second, movedFar.first, movedFar.second) > worst,
        )
        assertEquals(2, runAnchor(listOf(start, movedFar), worst))
    }

    @Test
    fun aRocketWalkingAwayIsFollowedWithBoundedLag() {
        // Descending under canopy or being carried back to the pad: steady real
        // motion, so the anchor has to keep up. The lag it is allowed to build is
        // one deadband, never more, however long the walk goes on.
        val deadband = recenterDeadbandM(3f, 5f)
        val mPerDegLat = 111_319.5
        val path = List(200) { Pair(40.0 + it * 2.0 / mPerDegLat, -105.0) }  // 2 m per fix
        var anchor: Pair<Double, Double>? = null
        var worstLag = 0.0
        for (t in path) {
            val a = anchor
            if (a == null || metersBetween(a.first, a.second, t.first, t.second) > deadband) {
                anchor = t
            }
            val held = anchor!!
            worstLag = maxOf(worstLag, metersBetween(held.first, held.second, t.first, t.second))
        }
        assertTrue("lag reached $worstLag m against a $deadband m band", worstLag <= deadband + 2.0)
    }

    // ── Viewport limit ────────────────────────────────────────────────────────

    @Test
    fun theBandIsCutDownToWhatTheScreenCanAbsorb() {
        // The regression this exists for, with the numbers that produced it: a
        // Pixel at 360 dpi (density 2.25), 1008 px wide, framed at the scale the
        // field capture actually showed — 0.0133 m per device pixel, so about
        // 13 m of ground across the whole screen.
        //
        // The statistical band for a phone reporting 7 m of accuracy is ~10 m,
        // which is most of the visible width. Uncapped, the anchor could sit far
        // enough off-center to push a marker off the screen, which is exactly what
        // was reported from the field.
        val metersPerDevicePx = 0.0133
        val raw = recenterDeadbandM(1f, 7f)
        val capped = viewportLimitedDeadbandM(raw, 1008, metersPerDevicePx)
        assertTrue("raw band $raw m should exceed the visible width", raw > 1008 * metersPerDevicePx / 2)
        assertTrue("capped band $capped m still too wide", capped < raw)
        // The cap is the margin the bounds fit reserves outside the two markers,
        // so spending it on center drift can just reach the edge and no further.
        assertEquals((0.14 * 1008 * metersPerDevicePx).toFloat(), capped, 0.01f)
    }

    @Test
    fun aWideViewLeavesTheStatisticalBandAlone() {
        // Zoomed out — a kilometer of ground on screen — the screen is not the
        // binding constraint and the cap must not interfere with the sizing that
        // the receivers' own error justifies.
        val raw = recenterDeadbandM(3f, 5f)
        assertEquals(raw, viewportLimitedDeadbandM(raw, 1008, 1.0), 1e-4f)
    }

    @Test
    fun anUnmeasuredViewportDoesNotCollapseTheBand() {
        // The map view reports zero size until it has been laid out, and a zero
        // there must not produce a zero band — that would re-latch the anchor on
        // every fix, which is the creep this whole mechanism removes.
        val raw = recenterDeadbandM(3f, 5f)
        assertEquals(raw, viewportLimitedDeadbandM(raw, 0, 0.0133), 1e-4f)
        assertEquals(raw, viewportLimitedDeadbandM(raw, 1008, 0.0), 1e-4f)
        assertEquals(raw, viewportLimitedDeadbandM(raw, 1008, Double.NaN), 1e-4f)
    }

    // ── Screen scale ──────────────────────────────────────────────────────────

    @Test
    fun metersPerPixelDistinguishesLogicalFromDevicePixels() {
        // The bug behind both the scale bar overstating distances and this file's
        // previous viewport check passing when the real geometry did not: MapLibre
        // reports zoom in LOGICAL pixels, and anything comparing against real
        // screen pixels has to divide by the display density first.
        val logical = metersPerDp(20f, 47.6148)
        assertEquals(logical / 2.25, metersPerDevicePx(20f, 47.6148, 2.25f), 1e-9)
        // Density 1 is the only case where the two agree, which is why the bug
        // survived review and every emulator it was tried on.
        assertEquals(logical, metersPerDevicePx(20f, 47.6148, 1f), 1e-9)
        // A denser screen shows LESS ground per pixel, never more.
        assertTrue(metersPerDevicePx(20f, 47.6148, 3.5f) < metersPerDevicePx(20f, 47.6148, 2.25f))
    }

    // Absolute calibration deliberately is not asserted here. The recovered field
    // value (~0.015 m per device pixel at zoom 20.58 on a 2.25-density screen) came
    // from inverting this same formula, so a test of it would be circular, and the
    // independent check — on-screen marker separation against the Dist the app
    // printed — carries about 10% of uncertainty from the rocket icon's anchor
    // point. Whether the constant is right is a device question: put both markers
    // on screen, measure their pixel separation against the reported Dist, and
    // compare with what the scale bar claims. That ratio was 2.43 before this fix
    // and should be 1.0 after it.

    @Test
    fun zoomingInOneLevelHalvesTheGroundPerPixel() {
        for (z in 10..21) {
            assertEquals(
                metersPerDp(z.toFloat(), 47.6148) / 2.0,
                metersPerDp(z + 1f, 47.6148),
                1e-9,
            )
        }
    }

    // ── Bounds-fit padding ────────────────────────────────────────────────────

    @Test
    fun paddingIsAFractionOfTheViewportNotAFixedPixelCount() {
        // The previous value was 300 px on every side regardless of screen. On a
        // 1008 px-wide phone that gave away 600 px — 60% of the width — before
        // anything was framed, which is why the markers occupied a fifth of the
        // screen.
        val (l, t, r, b) = autoZoomPadding(1008, 2244).let {
            listOf(it[0], it[1], it[2], it[3])
        }
        assertEquals(l, r)
        assertEquals(t, b)
        assertTrue("horizontal padding $l px is not less than the old 300", l < 300)
        assertTrue("padding should leave most of the viewport", 2 * l < 1008 / 2)
        assertTrue("vertical padding should scale with height", t > l)
    }

    @Test
    fun paddingNeverMeetsInTheMiddle() {
        // Padding that exceeds half the viewport degenerates the fit and zooms far
        // out — indistinguishable from the bug being fixed, and far harder to spot
        // because it only happens on small or transient viewport sizes.
        for (w in listOf(1, 2, 10, 100, 480, 1008, 2400)) {
            for (h in listOf(1, 2, 10, 100, 800, 2244, 3000)) {
                val p = autoZoomPadding(w, h)
                assertTrue("w=$w padding ${p[0]}", p[0] + p[2] < w)
                assertTrue("h=$h padding ${p[1]}", p[1] + p[3] < h)
                assertTrue("negative padding", p.all { it >= 0 })
            }
        }
        assertTrue(autoZoomPadding(0, 0).all { it == 0 })
        assertTrue(autoZoomPadding(-5, 100).all { it == 0 })
    }
}

private operator fun <T> List<T>.component4(): T = this[3]
