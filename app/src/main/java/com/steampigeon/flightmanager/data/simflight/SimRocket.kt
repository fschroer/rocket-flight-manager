package com.steampigeon.flightmanager.data.simflight

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The rocket a simulated flight is computed from (locator ADR "An app-started
 * simulated flight computed on the locator", 0041, amendment 2026-11-03).
 *
 * The locator integrates the flight; this file holds what the app works out
 * before it sends anything: the motor read from a RASP (.eng) or RockSim (.rse)
 * file and thinned to the 32 points the wire carries, Cd spread over the Mach
 * grid or fitted from an OpenRocket export, and the canopy calculator.
 *
 * Pure Kotlin, no android.*, so the JVM tests run it (SimRocketTest).
 */

/** One motor, as the simulation needs it. */
data class SimMotor(
    /** thrustcurve.org motorId, or "file:<name>" for an imported file. */
    val id: String,
    val manufacturer: String,
    val designation: String,
    val diameterMm: Float,
    val loadedKg: Float,
    val propKg: Float,
    /** (seconds after ignition, newtons), strictly increasing in time. */
    val curve: List<Pair<Float, Float>>,
) {
    val impulseNs: Float get() = Thrust.impulse(curve)
    val burnS: Float get() = curve.lastOrNull()?.first ?: 0f
    val peakN: Float get() = curve.maxOfOrNull { it.second } ?: 0f
    /** The class letter by total impulse (A = 1.26-2.5 N s, doubling each). */
    val impulseClass: Char
        get() {
            var limit = 2.5f; var c = 'A'
            while (impulseNs > limit && c < 'Z') { limit *= 2f; c++ }
            return c
        }
}

object MotorFiles {
    /**
     * A RASP .eng file: ';' comments, a header
     * `name diameter_mm length_mm delays prop_kg total_kg manufacturer`,
     * then `time thrust` lines until a line starting with ';' or the end.
     * The first motor in the file is read.
     */
    fun parseEng(text: String, id: String): SimMotor? {
        val lines = text.lines().map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val h = lines[0].split(Regex("\\s+"))
        if (h.size < 7) return null
        val dia = h[1].toFloatOrNull() ?: return null
        val prop = h[4].toFloatOrNull() ?: return null
        val total = h[5].toFloatOrNull() ?: return null
        val pts = ArrayList<Pair<Float, Float>>()
        for (l in lines.drop(1)) {
            val f = l.split(Regex("\\s+"))
            val t = f.getOrNull(0)?.toFloatOrNull()
            val n = f.getOrNull(1)?.toFloatOrNull()
            if (t == null || n == null) break   // the next motor's header
            pts.add(t to n)
        }
        return build(id, h.drop(6).joinToString(" "), h[0], dia, total, prop, pts)
    }

    /**
     * A RockSim .rse file: an `<engine>` with `mfg`, `code`, `dia` (mm), `initWt`
     * and `propWt` (grams), and `<eng-data t=".." f=".."/>` points.  Read with
     * regular expressions rather than an XML parser, so it stays pure Kotlin.
     */
    fun parseRse(text: String, id: String): SimMotor? {
        val engine = Regex("<engine\\s([^>]*)>", RegexOption.IGNORE_CASE).find(text) ?: return null
        fun attr(src: String, name: String) =
            Regex("\\b$name\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(src)?.groupValues?.get(1)
        val a = engine.groupValues[1]
        val dia = attr(a, "dia")?.toFloatOrNull() ?: return null
        val init = attr(a, "initWt")?.toFloatOrNull() ?: return null
        val prop = attr(a, "propWt")?.toFloatOrNull() ?: return null
        val pts = Regex("<eng-data\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(text).mapNotNull {
            val t = attr(it.groupValues[1], "t")?.toFloatOrNull()
            val f = attr(it.groupValues[1], "f")?.toFloatOrNull()
            if (t == null || f == null) null else t to f
        }.toList()
        return build(id, attr(a, "mfg") ?: "", attr(a, "code") ?: "", dia, init / 1000f, prop / 1000f, pts)
    }

    fun parse(text: String, id: String): SimMotor? =
        if (text.contains("<engine", ignoreCase = true)) parseRse(text, id) else parseEng(text, id)

    private fun build(id: String, mfg: String, name: String, dia: Float, total: Float, prop: Float,
                      raw: List<Pair<Float, Float>>): SimMotor? {
        // Drop a leading (0, 0), which RASP implies anyway, and any point that
        // does not move forward in time.
        val pts = ArrayList<Pair<Float, Float>>()
        for (p in raw) {
            if (p.first <= 0f && p.second <= 0f) continue
            if (pts.isNotEmpty() && p.first <= pts.last().first) continue
            if (p.first < 0f || p.second < 0f) return null
            pts.add(p)
        }
        if (pts.size < 2 || !(prop > 0f) || !(total > prop)) return null
        return SimMotor(id, mfg.trim(), name.trim(), dia, total, prop, pts)
    }
}

object Thrust {
    const val MAX_POINTS = 32

    /** Trapezoid impulse from (0, 0) through every point, as the locator sums it. */
    fun impulse(curve: List<Pair<Float, Float>>): Float {
        var sum = 0.0; var pt = 0.0; var pf = 0.0
        for ((t, f) in curve) { sum += 0.5 * (pf + f) * (t - pt); pt = t.toDouble(); pf = f.toDouble() }
        return sum.toFloat()
    }

    /**
     * At most [maxPoints] points, the shape kept and the total impulse exact.
     * Removes, one at a time, the interior point whose removal changes the
     * curve's area least, then scales the thrust so the impulse matches.  Times
     * are rounded to whole milliseconds first, as the wire carries them, and
     * points that would collide are merged.
     */
    fun thin(curve: List<Pair<Float, Float>>, maxPoints: Int = MAX_POINTS): List<Pair<Float, Float>> {
        val target = impulse(curve)
        val pts = ArrayList<Pair<Float, Float>>()
        for ((t, f) in curve) {
            val tm = Math.round(t * 1000f) / 1000f
            if (tm <= 0f) continue
            if (pts.isNotEmpty() && tm <= pts.last().first) {
                pts[pts.size - 1] = pts.last().first to max(pts.last().second, f)
            } else pts.add(tm to f)
        }
        while (pts.size > maxPoints) {
            var best = -1; var bestErr = Float.MAX_VALUE
            for (i in 0 until pts.size - 1) {   // never the last: it sets the burn time
                val (t0, f0) = if (i == 0) 0f to 0f else pts[i - 1]
                val (t1, f1) = pts[i]
                val (t2, f2) = pts[i + 1]
                // Area of the triangle the point adds over the straight line.
                val err = abs((t1 - t0) * (f2 - f0) - (t2 - t0) * (f1 - f0)) * 0.5f
                if (err < bestErr) { bestErr = err; best = i }
            }
            pts.removeAt(best)
        }
        val now = impulse(pts)
        if (now <= 0f) return pts
        val k = target / now
        return pts.map { it.first to it.second * k }
    }
}

object Drag {
    /** The fixed Mach grid the locator's Cd table is given on (SimFlight::kMachGrid). */
    val MACH_GRID = floatArrayOf(0.0f, 0.3f, 0.5f, 0.7f, 0.85f, 1.0f, 1.2f, 1.5f)

    /**
     * A typed subsonic Cd spread over the grid with a typical transonic rise.
     * An approximation, from the shape OpenRocket's curves usually have: flat
     * to Mach 0.7, about +55 % at Mach 1, easing after.  A flight that stays
     * below Mach 0.7 uses the typed number as typed.
     */
    val TRANSONIC_RISE = floatArrayOf(1.0f, 1.0f, 1.0f, 1.02f, 1.12f, 1.55f, 1.45f, 1.30f)

    fun spread(cd: Float): FloatArray = FloatArray(MACH_GRID.size) { cd * TRANSONIC_RISE[it] }

    /**
     * Cd on the grid, fitted from an OpenRocket simulation exported as CSV.
     * Uses the "Mach number" and "Drag coefficient" columns over the ascent
     * (until "Vertical velocity" first goes negative, when the column is there),
     * averaging the rows within 0.05 of each grid point.  A grid point the
     * flight never reached takes the highest fitted point's Cd times the
     * standard rise between them.  Null when the columns are missing or no
     * ascent row is usable.
     */
    fun fitOpenRocketCsv(text: String): FloatArray? {
        var cols: List<String>? = null
        val rows = ArrayList<FloatArray>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val body = line.removePrefix("#").trim()
                if (cols == null && body.contains(",") && body.contains("Time", ignoreCase = true))
                    cols = body.split(",").map { it.trim() }
                continue
            }
            if (cols == null) {
                // Some exports write the header without '#'.
                if (line.contains("Time", ignoreCase = true)) { cols = line.split(",").map { it.trim() }; continue }
            }
            val vals = line.split(",").map { it.trim().toFloatOrNull() ?: Float.NaN }
            rows.add(vals.toFloatArray())
        }
        val c = cols ?: return null
        fun col(name: String) = c.indexOfFirst { it.startsWith(name, ignoreCase = true) }
        val iMach = col("Mach number"); val iCd = col("Drag coefficient"); val iVz = col("Vertical velocity")
        if (iMach < 0 || iCd < 0) return null
        val samples = ArrayList<Pair<Float, Float>>()
        var rising = false
        for (r in rows) {
            val m = r.getOrNull(iMach) ?: continue
            val cd = r.getOrNull(iCd) ?: continue
            if (iVz >= 0) {
                val vz = r.getOrNull(iVz) ?: continue
                if (vz > 1f) rising = true
                if (rising && vz < 0f) break   // apogee: the ascent is what Cd is for
                if (!rising) continue
            }
            if (m.isNaN() || cd.isNaN() || m < 0.02f || cd <= 0f) continue
            samples.add(m to cd)
        }
        if (samples.isEmpty()) return null
        val out = FloatArray(MACH_GRID.size) { Float.NaN }
        for (g in MACH_GRID.indices) {
            val near = samples.filter { abs(it.first - MACH_GRID[g]) <= 0.05f }
            if (near.isNotEmpty()) out[g] = near.map { it.second }.average().toFloat()
        }
        // Mach 0: the slowest rows stand in for it.
        if (out[0].isNaN()) out[0] = samples.minByOrNull { it.first }!!.second
        // Interior holes: linear between fitted neighbors.  Past the fastest
        // fitted point: the standard rise.
        val last = out.indices.last { !out[it].isNaN() }
        for (g in 1..last) if (out[g].isNaN()) {
            val lo = (g - 1 downTo 0).first { !out[it].isNaN() }
            val hi = (g + 1..last).first { !out[it].isNaN() }
            out[g] = out[lo] + (out[hi] - out[lo]) * (MACH_GRID[g] - MACH_GRID[lo]) / (MACH_GRID[hi] - MACH_GRID[lo])
        }
        for (g in last + 1 until out.size) out[g] = out[last] * TRANSONIC_RISE[g] / TRANSONIC_RISE[last]
        return FloatArray(out.size) { min(3.0f, max(0.05f, out[it])) }
    }
}

object Canopy {
    /**
     * Steady descent rate under a canopy of [diameterM] and [cd], carrying
     * [massKg] (the rocket after burnout), in air at [tempC] near sea level.
     * v = sqrt(2 m g / (rho Cd A)).
     */
    fun descentRate(massKg: Float, diameterM: Float, cd: Float, tempC: Float = 15f): Float {
        val rho = 101325.0 / (287.05 * (tempC + 273.15))
        val area = PI * diameterM * diameterM / 4.0
        if (massKg <= 0f || area <= 0.0 || cd <= 0f) return 0f
        return sqrt(2.0 * massKg * 9.80665 / (rho * cd * area)).toFloat()
    }
}

/** A rocket as the flyer describes it, saved by name on the phone. */
data class SimRocket(
    val name: String = "",
    val dryMassG: Int = 1200,
    val diameterMm: Float = 54f,
    /** Overall length, nose tip to the aft end. */
    val lengthMm: Int = 1000,
    /**
     * Static stability margin in calibers, as OpenRocket shows it with the motor
     * loaded.  With [lengthMm] it sets how fast the rocket turns into the wind.
     */
    val stabilityCal: Float = 2.0f,
    /** Cd on [Drag.MACH_GRID]. */
    val cd: List<Float> = Drag.spread(0.45f).toList(),
    val motorId: String? = null,
    val drogueMps: Int = 20,
    /** Tenths of a m/s are what the wire carries (main_dmps), so this is not whole. */
    val mainMps: Float = 6f,
    val railLengthCm: Int = 180,
    val railAngleTenths: Int = 0,
    val launchAzDeg: Int = 0,
    /** Ground temperature in °C, or null: the locator uses its own barometer's. */
    val tempC: Int? = null,
    val windMps: Int = 3,
    val windFromDeg: Int = 270,
) {
    fun burnoutMassKg(motor: SimMotor?): Float =
        dryMassG / 1000f + (motor?.let { it.loadedKg - it.propKg } ?: 0f)

    /** The locator's ranges (SimFlight::Valid), checked before anything is sent. */
    fun problems(motor: SimMotor?): List<String> = buildList {
        if (motor == null) { add("No motor selected"); return@buildList }
        if (dryMassG !in 10..200_000) add("Dry mass out of range")
        if (diameterMm !in 5f..500f) add("Diameter out of range")
        if (lengthMm !in 100..5000) add("Length out of range")
        if (Math.round(stabilityCal * 10f) !in 5..100) add("Stability margin out of range")
        if (cd.size != Drag.MACH_GRID.size || cd.any { it !in 0.05f..3.0f }) add("Cd out of range")
        if (drogueMps !in 5..60) add("Drogue rate out of range")
        if (Math.round(mainMps * 10f) !in 20..150) add("Main rate out of range")
        if (railLengthCm !in 0..2000) add("Rail length out of range")
        if (railAngleTenths !in 0..200) add("Rail angle out of range")
        if (launchAzDeg !in 0..359) add("Launch direction out of range")
        if (tempC != null && tempC !in -40..50) add("Temperature out of range")
        if (windMps !in 0..20) add("Wind speed out of range")
        if (windFromDeg !in 0..359) add("Wind direction out of range")
        if (motor.loadedKg > 100f) add("Motor too heavy")
        val weightN = (dryMassG / 1000f + motor.loadedKg) * 9.80665f
        if (motor.peakN <= weightN) add("The motor cannot lift this rocket")
    }
}
