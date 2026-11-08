package com.steampigeon.flightmanager.ui

import kotlin.math.PI
import kotlin.math.cos

/**
 * Where to draw the rocket marker under canopy while the locator's fix is lost
 * (locator ADR-0022 amendment 2026-11-08, issue #72).
 *
 * While the fix is lost the locator keeps re-sending the last good lat/lon, so
 * the map held the marker where the rocket *was*, while under canopy it went on
 * drifting with the wind. This carries the last fix forward at the wind: the
 * mean north/east GPS velocity of the fixed packets heard in the last 5 s of
 * descent. Replayed at 1 Hz with half the packets lost, a 10 s outage landed
 * 14 m from the truth (p95 34 m) against 47 m (188 m) for holding the fix.
 *
 * The result is drawn, never measured: distance, bearing, the AR marker and the
 * spoken callouts stay on the last real fix, and ADR-0022's jump test stays
 * anchored there. Kept free of Android so it can be tested on the JVM.
 */
class WindCoast(
    private val windowMs: Long = WINDOW_MS,
    private val maxCoastMs: Long = MAX_COAST_MS,
    private val growthMps: Double = GROWTH_MPS,
) {
    /** A coasted marker: position, the ring to draw around it, and its age. */
    data class Coasted(
        val latitude: Double,
        val longitude: Double,
        val radiusM: Double,
        val sinceFixMs: Long,
    )

    private class Velocity(val atMs: Long, val northMps: Float, val eastMps: Float)

    private val recent = ArrayDeque<Velocity>()
    private var fixLat = 0.0
    private var fixLon = 0.0
    private var fixHaccM = 0.0
    private var fixAtMs = -1L
    // Latched once per outage, at its first fixless descent packet. A null wind
    // after latching means "this outage does not coast".
    private var latched = false
    private var windNorth: Double? = null
    private var windEast: Double? = null
    private var landedAtMs = -1L

    fun reset() {
        recent.clear(); fixAtMs = -1L; latched = false
        windNorth = null; windEast = null; landedAtMs = -1L
    }

    /**
     * Feed every TelemetryData packet. Returns the coasted marker, or null when
     * the map should draw the locator's own position (a live fix, no fix ever,
     * no wind to coast on, or not under canopy).
     *
     * [inDescent] is flight states 4–7 (drogue deployment through main backup);
     * [landed] is `Landed`.
     */
    fun update(
        nowMs: Long,
        hasFix: Boolean,
        inDescent: Boolean,
        landed: Boolean,
        latitude: Double,
        longitude: Double,
        velNorthMps: Float,
        velEastMps: Float,
        haccM: Float,
    ): Coasted? {
        if (hasFix) {
            fixLat = latitude; fixLon = longitude
            fixHaccM = if (haccM.isFinite() && haccM >= 0f) haccM.toDouble() else 0.0
            fixAtMs = nowMs
            latched = false; windNorth = null; windEast = null; landedAtMs = -1L
            // Only descent velocity is wind: before deployment it is the
            // rocket's own apogee momentum.
            if (inDescent && velNorthMps.isFinite() && velEastMps.isFinite())
                recent.addLast(Velocity(nowMs, velNorthMps, velEastMps))
            while (recent.isNotEmpty() && nowMs - recent.first().atMs > windowMs) recent.removeFirst()
            return null
        }
        if (fixAtMs < 0) return null

        if (!latched) {
            if (!inDescent) return null      // wait: coasting starts only under canopy
            latched = true
            val w = recent.filter { fixAtMs - it.atMs <= windowMs }
            if (w.size >= MIN_SAMPLES) {
                windNorth = w.sumOf { it.northMps.toDouble() } / w.size
                windEast = w.sumOf { it.eastMps.toDouble() } / w.size
            }
        }
        val wn = windNorth ?: return null
        val we = windEast ?: return null

        if (landed && landedAtMs < 0) landedAtMs = nowMs
        // Landed: the rocket has stopped, so the point and the ring stop with it.
        val endMs = if (landedAtMs >= 0) landedAtMs else nowMs
        val sinceFixMs = (endMs - fixAtMs).coerceAtLeast(0L)
        val movingS = sinceFixMs.coerceAtMost(maxCoastMs) / 1000.0
        val dLat = wn * movingS / EARTH_RADIUS_M * 180.0 / PI
        val dLon = we * movingS / (EARTH_RADIUS_M * cos(fixLat * PI / 180.0)) * 180.0 / PI
        return Coasted(
            latitude = fixLat + dLat,
            longitude = fixLon + dLon,
            radiusM = fixHaccM + growthMps * sinceFixMs / 1000.0,
            sinceFixMs = sinceFixMs,
        )
    }

    companion object {
        /** The wind is the mean of this much descent before the fix was lost. */
        const val WINDOW_MS = 5_000L
        /** Fewer fixed packets than this in the window and the outage does not coast. */
        const val MIN_SAMPLES = 2
        /** The point moves for at most this long; the corpus measures no further. */
        const val MAX_COAST_MS = 30_000L
        /**
         * Ring growth past the last fix's h_acc. 4 m/s held 95–98 % of 9 replayed
         * descents at every outage length from 2 to 30 s; 3 m/s held 79–93 %.
         */
        const val GROWTH_MPS = 4.0
        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Flight states 4–7: drogue deployment through main backup. */
        fun isDescent(flightStateOrdinal: Int) = flightStateOrdinal in 4..7
    }
}
