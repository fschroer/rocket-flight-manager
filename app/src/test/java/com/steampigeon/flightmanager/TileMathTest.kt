package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.ui.OfflineMapManager
import com.steampigeon.flightmanager.ui.SatelliteProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.cos
import kotlin.math.roundToLong

/**
 * The offline-download estimate: how many tiles a region costs, and how many bytes.
 *
 * This is the number the whole Download maps screen exists to report, and the one
 * the 1 GB guard reads. It was ~4x low on tiles and ~2.7x low on bytes, because it
 * counted the map-zoom grid while MapLibre — with a `"tileSize": 256` source on a
 * 512 logical grid — fetches one level deeper.
 *
 * Low is the dangerous direction: an under-estimate waves a region through the
 * guard that is really over budget, rather than refusing one that would have fit.
 */
class TileMathTest {

    private companion object {
        // The measured region: 9.1 x 9.1 km near 47.6 N, cached z10–z17, which
        // downloaded 139 MB. The only real anchor either platform has.
        const val LAT = 47.6
        const val LON = -122.4
        const val SIDE_KM = 9.1
        const val MIN_Z = 10
        const val MAX_Z = 17
        const val MEASURED_BYTES = 139_000_000.0
    }

    private fun boundsAround(lat: Double, lon: Double, km: Double): LatLngBounds {
        val dLat = (km / 2.0) / 110.574
        val dLon = (km / 2.0) / (111.320 * cos(Math.toRadians(lat)))
        return LatLngBounds.from(lat + dLat, lon + dLon, lat - dLat, lon - dLon)
    }

    private val region = boundsAround(LAT, LON, SIDE_KM)

    // ── The zoom convention ─────────────────────────────────────────────────

    @Test
    fun sourceZoomIsOneDeeperThanMapZoom() {
        // The style declares 256-px tiles against MapLibre's 512-px logical grid.
        assertEquals(18, OfflineMapManager.sourceZoomOf(17))
        assertEquals(11, OfflineMapManager.sourceZoomOf(10))
    }

    @Test
    fun aZoomRangeCostsFourTimesWhatTheMapZoomGridWouldSuggest() {
        // Each level is 4x the one above, so counting the wrong grid is a clean
        // factor of ~4 — which is exactly the 3.94 MapLibre's own resource count
        // reported against the old estimate.
        val counted = OfflineMapManager.tileCount(region, MIN_Z, MAX_Z)
        val mapZoomGrid = (MIN_Z..MAX_Z).sumOf { OfflineMapManager.tileCountAtZoom(region, it) }
        val ratio = counted.toDouble() / mapZoomGrid
        assertTrue("expected ~3.9x, got $ratio", ratio > 3.8 && ratio < 4.0)
    }

    // ── The estimate against reality ────────────────────────────────────────

    @Test
    fun theByteEstimateLandsOnTheOneRegionActuallyMeasured() {
        val est = OfflineMapManager.estimateBytes(region, MIN_Z, MAX_Z, SatelliteProvider.MAPBOX)
        val ratio = est / MEASURED_BYTES
        // Within 15%. Tighter would be false precision: the bounds here are
        // reconstructed from "9.1 x 9.1 km near 47.6 N", not the exact corners.
        assertTrue(
            "estimate ${est / 1_000_000} MB vs measured 139 MB (${"%.2f".format(ratio)}x)",
            ratio > 0.85 && ratio < 1.15,
        )
    }

    @Test
    fun theOldArithmeticWouldHaveBeenLowByMoreThanHalf() {
        // Guards the direction, not the constant: if someone reverts either half of
        // the fix, the estimate drops back under the measured download and the guard
        // silently goes back to passing over-budget regions.
        val est = OfflineMapManager.estimateBytes(region, MIN_Z, MAX_Z, SatelliteProvider.MAPBOX)
        assertTrue("estimate must not fall back under the measured 139 MB", est > 100_000_000L)
    }

    // ── Shape of the pyramid ────────────────────────────────────────────────

    @Test
    fun everyLevelBelowTheDeepestCostsLessThanHalfOfIt() {
        // Why the download always caches down to the provider's floor rather than
        // maxZoom-N: the whole context pyramid is nearly free, and without it the
        // map is blank offline at any zoomed-out level — exactly when someone is
        // getting their bearings on site.
        val deepest = OfflineMapManager.tileCountAtZoom(region, OfflineMapManager.sourceZoomOf(MAX_Z))
        val rest = (MIN_Z until MAX_Z).sumOf {
            OfflineMapManager.tileCountAtZoom(region, OfflineMapManager.sourceZoomOf(it))
        }
        assertTrue("context pyramid $rest vs deepest level $deepest", rest < deepest / 2)
    }

    @Test
    fun theDeepestLevelDominatesTheEstimate() {
        val total = OfflineMapManager.tileCount(region, MIN_Z, MAX_Z)
        val deepest = OfflineMapManager.tileCountAtZoom(region, OfflineMapManager.sourceZoomOf(MAX_Z))
        val share = deepest.toDouble() / total
        assertTrue("deepest level is $share of the pyramid", share > 0.7)
    }

    // ── Both providers move together ────────────────────────────────────────

    @Test
    fun bothProvidersAreCalibratedTheSameWay() {
        // Esri has no download of its own to anchor against, so it carries the same
        // correction: both tables were built the same way in the same commit, and
        // correcting only the one with an anchor would leave the other ~3.9x high.
        val mapbox = OfflineMapManager.estimateBytes(region, MIN_Z, MAX_Z, SatelliteProvider.MAPBOX)
        val esri = OfflineMapManager.estimateBytes(region, MIN_Z, MAX_Z, SatelliteProvider.ESRI)
        val tiles = OfflineMapManager.tileCount(region, MIN_Z, MAX_Z)
        // Both must price the same pyramid; only the per-tile figure differs.
        val mapboxPerTile = (mapbox.toDouble() / tiles).roundToLong()
        val esriPerTile = (esri.toDouble() / tiles).roundToLong()
        assertTrue("mapbox $mapboxPerTile B/tile", mapboxPerTile in 10_000..16_000)
        assertTrue("esri $esriPerTile B/tile", esriPerTile in 12_000..20_000)
    }
}
