package com.steampigeon.flightmanager

import androidx.compose.ui.geometry.Offset
import com.steampigeon.flightmanager.ui.ChartViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pan/zoom arithmetic for the flight profile chart.
 *
 * The focal-point math is easy to get subtly wrong — particularly on Y, which
 * is inverted (altitude grows upward from a baseline at plotH) — and a sign
 * error there shows up as the chart sliding away under your fingers rather than
 * as anything a compiler catches.
 */
class ChartViewportTest {

    private companion object {
        const val PLOT_W = 1000f
        const val PLOT_H = 600f
        const val TOTAL_MS = 20_000f   // 20 s flight
        const val MAX_ALT = 900f
        const val EPS = 0.01f
    }

    private fun ChartViewport.pinch(
        centroid: Offset,
        zoomChange: Float,
        panChange: Offset = Offset.Zero,
    ) = transform(centroid, panChange, zoomChange, PLOT_W, PLOT_H)

    // ── Identity ────────────────────────────────────────────────────────────

    @Test
    fun identityGestureChangesNothing() {
        val v = ChartViewport().pinch(Offset(400f, 300f), zoomChange = 1f)
        assertEquals(1f, v.zoom, EPS)
        assertEquals(0f, v.pan.x, EPS)
        assertEquals(0f, v.pan.y, EPS)
    }

    @Test
    fun defaultViewportFitsTheWholeFlight() {
        val v = ChartViewport()
        val ms = v.visibleMsRange(PLOT_W, TOTAL_MS)
        assertEquals(0f, ms.start, EPS)
        assertEquals(TOTAL_MS, ms.endInclusive, EPS)

        val alt = v.visibleValueRange(PLOT_H, 0f, MAX_ALT)
        assertEquals(0f, alt.start, EPS)
        assertEquals(MAX_ALT, alt.endInclusive, EPS)
    }

    // ── Focal point ─────────────────────────────────────────────────────────

    @Test
    fun zoomHoldsTheDataUnderTheCentroidStill() {
        // Pick a centroid inside the plot and record what data sits under it.
        val centroid = Offset(700f, 200f)
        val before = ChartViewport()

        // Invert the projection at the centroid, before and after the pinch.
        fun msUnder(v: ChartViewport): Float {
            val r = v.visibleMsRange(PLOT_W, TOTAL_MS)
            val frac = (centroid.x - 64f /* CHART_MARGIN_X */) / PLOT_W
            return r.start + frac * (r.endInclusive - r.start)
        }
        fun altUnder(v: ChartViewport): Float {
            val r = v.visibleValueRange(PLOT_H, 0f, MAX_ALT)
            val frac = (PLOT_H - centroid.y) / PLOT_H
            return r.start + frac * (r.endInclusive - r.start)
        }

        val msBefore = msUnder(before)
        val altBefore = altUnder(before)

        val after = before.pinch(centroid, zoomChange = 2.5f)
        assertEquals(2.5f, after.zoom, EPS)
        assertEquals("time under centroid moved", msBefore, msUnder(after), 1f)
        assertEquals("altitude under centroid moved", altBefore, altUnder(after), 0.5f)
    }

    @Test
    fun repeatedPinchesAccumulateWithoutDrift() {
        val centroid = Offset(500f, 300f)
        var v = ChartViewport()
        repeat(5) { v = v.pinch(centroid, zoomChange = 1.2f) }
        // 1.2^5 ≈ 2.488
        assertEquals(2.488f, v.zoom, 0.01f)
        // Zooming all the way back out must land exactly on the original fit.
        repeat(5) { v = v.pinch(centroid, zoomChange = 1f / 1.2f) }
        assertEquals(1f, v.zoom, EPS)
        assertEquals(0f, v.pan.x, EPS)
        assertEquals(0f, v.pan.y, EPS)
    }

    // ── Clamping ────────────────────────────────────────────────────────────

    @Test
    fun zoomIsClampedToTheAllowedRange() {
        // Cannot zoom out below the full-flight fit.
        val out = ChartViewport().pinch(Offset(500f, 300f), zoomChange = 0.1f)
        assertEquals(1f, out.zoom, EPS)

        // Cannot zoom in past the cap, however hard you pinch.
        var v = ChartViewport()
        repeat(30) { v = v.pinch(Offset(500f, 300f), zoomChange = 2f) }
        assertEquals(25f /* MAX_CHART_ZOOM */, v.zoom, EPS)
    }

    @Test
    fun pinchingPastTheZoomCapDoesNotDriftTheViewport() {
        // Once clamped at the cap, further pinches must not shift pan — that was
        // the reason for scaling by the applied factor rather than zoomChange.
        var v = ChartViewport()
        repeat(30) { v = v.pinch(Offset(500f, 300f), zoomChange = 2f) }
        val settled = v.pan
        repeat(5) { v = v.pinch(Offset(500f, 300f), zoomChange = 2f) }
        assertEquals(settled.x, v.pan.x, EPS)
        assertEquals(settled.y, v.pan.y, EPS)
    }

    @Test
    fun panCannotDragDataOffThePlot() {
        // Zoom in, then try to fling far past both edges.
        var v = ChartViewport().pinch(Offset(500f, 300f), zoomChange = 4f)
        v = v.pinch(Offset(500f, 300f), zoomChange = 1f, panChange = Offset(10_000f, 10_000f))

        // X pan is bounded to [-(zoom-1)*plotW, 0]; Y to [0, (zoom-1)*plotH].
        assertTrue("pan.x above upper bound: ${v.pan.x}", v.pan.x <= 0f + EPS)
        assertTrue("pan.x below lower bound: ${v.pan.x}", v.pan.x >= -(v.zoom - 1f) * PLOT_W - EPS)
        assertTrue("pan.y below lower bound: ${v.pan.y}", v.pan.y >= 0f - EPS)
        assertTrue("pan.y above upper bound: ${v.pan.y}", v.pan.y <= (v.zoom - 1f) * PLOT_H + EPS)

        // And the same in the opposite direction.
        v = v.pinch(Offset(500f, 300f), zoomChange = 1f, panChange = Offset(-10_000f, -10_000f))
        assertTrue(v.pan.x <= 0f + EPS)
        assertTrue(v.pan.x >= -(v.zoom - 1f) * PLOT_W - EPS)
        assertTrue(v.pan.y >= 0f - EPS)
        assertTrue(v.pan.y <= (v.zoom - 1f) * PLOT_H + EPS)
    }

    @Test
    fun visibleRangeStaysWithinTheData() {
        // At any zoom/pan the visible window must remain inside the full extent,
        // so the chart can never show blank space beside the flight.
        var v = ChartViewport().pinch(Offset(900f, 100f), zoomChange = 6f)
        v = v.pinch(Offset(900f, 100f), zoomChange = 1f, panChange = Offset(5_000f, -5_000f))

        val ms = v.visibleMsRange(PLOT_W, TOTAL_MS)
        assertTrue("visible start ${ms.start} before 0", ms.start >= -1f)
        assertTrue("visible end ${ms.endInclusive} past flight", ms.endInclusive <= TOTAL_MS + 1f)

        val alt = v.visibleValueRange(PLOT_H, 0f, MAX_ALT)
        assertTrue("visible low ${alt.start} below ground", alt.start >= -1f)
        assertTrue("visible high ${alt.endInclusive} above apogee", alt.endInclusive <= MAX_ALT + 1f)
    }

    // ── Projection ──────────────────────────────────────────────────────────

    @Test
    fun projectionMatchesItsOwnInverse() {
        val v = ChartViewport().pinch(Offset(300f, 400f), zoomChange = 3f)

        // The visible range endpoints must project back to the plot edges.
        val ms = v.visibleMsRange(PLOT_W, TOTAL_MS)
        assertEquals(64f, v.screenXOfMs(ms.start, PLOT_W, TOTAL_MS), EPS)
        assertEquals(64f + PLOT_W, v.screenXOfMs(ms.endInclusive, PLOT_W, TOTAL_MS), EPS)

        val alt = v.visibleValueRange(PLOT_H, 0f, MAX_ALT)
        assertEquals(PLOT_H, v.screenYOfValue(alt.start, PLOT_H, 0f, MAX_ALT), EPS)
        assertEquals(0f, v.screenYOfValue(alt.endInclusive, PLOT_H, 0f, MAX_ALT), EPS)
    }

    @Test
    fun degeneratePlotSizeIsIgnored() {
        // A canvas measured at zero must not produce NaN pan/zoom.
        val v = ChartViewport().transform(Offset(0f, 0f), Offset.Zero, 2f, 0f, 0f)
        assertEquals(1f, v.zoom, EPS)
        assertEquals(0f, v.pan.x, EPS)
        assertEquals(0f, v.pan.y, EPS)
    }
}
