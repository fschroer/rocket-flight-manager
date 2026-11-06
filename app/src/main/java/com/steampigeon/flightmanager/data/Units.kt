package com.steampigeon.flightmanager.data

import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Metric or English units, chosen on App Settings (locator ADR "Units are SI
 * underneath; English is a display choice").
 *
 * Everything the app stores, sends or exports is SI and stays SI. This is a
 * DISPLAY choice: it decides how a number is printed, spoken or typed, never what
 * is held. The full-record CSV, the app flight log, KML and Firebase uploads do
 * not change with it.
 */
enum class UnitSystem { Metric, English }

/**
 * Every metric-to-English conversion, rounding rule and unit label in the app.
 *
 * Defined once, the way [LocatorSettingsBounds] is, because a screen that does its
 * own `* 3.28` does not break when the rule changes: it quietly prints a number
 * that disagrees with the one next to it. Pure Kotlin, no android.*, so the host
 * tests run it (UnitsTest).
 *
 * Every threshold in the app (the freefall descent rate, the 500 m bound, the
 * radio-range plausibility check) stays in SI and is compared in SI. Only the
 * printed result goes through here.
 */
object Units {
    /** Exact by definition: the international foot is 0.3048 m. */
    const val METERS_PER_FOOT = 0.3048
    const val FEET_PER_METER = 1.0 / METERS_PER_FOOT
    const val FEET_PER_MILE = 5280.0
    const val MPS_PER_MPH = 0.44704

    /**
     * Above this a ground distance is given in miles. A recovery walk is read in
     * feet up close; beyond about a fifth of a mile, a five-digit number of feet
     * is harder to read than "0.42 mi".
     */
    const val MILES_ABOVE_FEET = 1000.0

    fun metersToFeet(m: Double): Double = m * FEET_PER_METER
    fun feetToMeters(ft: Double): Double = ft * METERS_PER_FOOT

    // ── Altitude ─────────────────────────────────────────────────────────────

    fun altitudeUnit(u: UnitSystem) = if (u == UnitSystem.English) "ft" else "m"

    /** Altitude in the display unit, unrounded. */
    fun altitude(m: Float, u: UnitSystem): Float =
        if (u == UnitSystem.English) metersToFeet(m.toDouble()).toFloat() else m

    /** Altitude in SI from a value in the display unit. */
    fun altitudeToMeters(v: Float, u: UnitSystem): Float =
        if (u == UnitSystem.English) feetToMeters(v.toDouble()).toFloat() else v

    /**
     * An altitude for the screen with its unit, "123.4 m" or "405 ft". Meters keep
     * the tenth they always had; feet are whole, because a tenth of a foot would
     * claim three times the precision the baro has.
     */
    fun formatAltitude(m: Float, u: UnitSystem, metricDecimals: Int = 1): String =
        if (u == UnitSystem.English) "${metersToFeet(m.toDouble()).roundToInt()} ft"
        else String.format(Locale.US, "%.${metricDecimals}f m", m)

    /** Just the number of [formatAltitude], padded to [width], for fixed-width readouts. */
    fun altitudeNumber(m: Float, u: UnitSystem, width: Int, metricDecimals: Int = 1): String =
        if (u == UnitSystem.English) String.format(Locale.US, "%${width}d", metersToFeet(m.toDouble()).roundToInt())
        else String.format(Locale.US, "%$width.${metricDecimals}f", m)

    /**
     * An altitude as said aloud: whole meters, or feet to the nearest 10. A spoken
     * "four thousand seven hundred and thirteen feet" takes longer to say than the
     * rocket takes to move 10 ft, and the last digit is inside the baro's noise.
     */
    fun spokenAltitude(m: Float, u: UnitSystem): String =
        if (u == UnitSystem.English) "${(metersToFeet(m.toDouble()) / 10.0).roundToInt() * 10} feet"
        else "${m.toInt()} meters"

    /**
     * The ascent-callout step in the display unit: every 100 m, or every 500 ft.
     * A literal conversion (every 328 ft) would call out numbers nobody counts in.
     */
    fun ascentCalloutStep(u: UnitSystem): Int = if (u == UnitSystem.English) 500 else 100

    /**
     * The last ascent-callout mark at or below [m], in the display unit: 300 for
     * 357 m, or 1000 for 1,150 ft. Zero below the first step.
     */
    fun ascentCalloutMark(m: Float, u: UnitSystem): Int {
        val step = ascentCalloutStep(u)
        return (altitude(m, u) / step).toInt() * step
    }

    // ── Vertical and air speed ───────────────────────────────────────────────

    fun speedUnit(u: UnitSystem) = if (u == UnitSystem.English) "ft/s" else "m/s"

    fun speed(mps: Float, u: UnitSystem): Float =
        if (u == UnitSystem.English) metersToFeet(mps.toDouble()).toFloat() else mps

    /** "12.3 m/s" or "40 ft/s" (whole feet per second, for the same reason as altitude). */
    fun formatSpeed(mps: Float, u: UnitSystem): String =
        if (u == UnitSystem.English) "${speed(mps, u).roundToInt()} ft/s"
        else String.format(Locale.US, "%.1f m/s", mps)

    /** Just the number of [formatSpeed], padded to [width]. */
    fun speedNumber(mps: Float, u: UnitSystem, width: Int): String =
        if (u == UnitSystem.English) String.format(Locale.US, "%${width}d", speed(mps, u).roundToInt())
        else String.format(Locale.US, "%$width.1f", mps)

    fun spokenSpeed(mps: Float, u: UnitSystem): String =
        if (u == UnitSystem.English) "${speed(mps, u).toInt()} feet per second"
        else "${mps.toInt()} meters per second"

    /**
     * The heads-up speed gauge's full scale and tick marks in the display unit:
     * 0 to 500 m/s by 100, or 0 to 1,600 ft/s by 400 (500 m/s is 1,640 ft/s).
     */
    fun speedGaugeScale(u: UnitSystem): Pair<Float, List<Float>> =
        if (u == UnitSystem.English) 1600f to listOf(0f, 400f, 800f, 1200f, 1600f)
        else 500f to listOf(0f, 100f, 200f, 300f, 400f, 500f)

    // ── Ground distance ─────────────────────────────────────────────────────

    /** "250 m", or "820 ft" up to [MILES_ABOVE_FEET], then "0.42 mi". */
    fun formatDistance(m: Int, u: UnitSystem): String {
        if (u == UnitSystem.Metric) return "$m m"
        val ft = metersToFeet(m.toDouble())
        return if (ft <= MILES_ABOVE_FEET) "${ft.roundToInt()} ft"
        else String.format(Locale.US, "%.2f mi", ft / FEET_PER_MILE)
    }

    /** [formatDistance] right-aligned to [width] characters, unit included. */
    fun distanceField(m: Int, u: UnitSystem, width: Int): String =
        formatDistance(m, u).padStart(width)

    /** "250 meters", "820 feet", "0.42 miles". */
    fun spokenDistance(m: Int, u: UnitSystem): String {
        if (u == UnitSystem.Metric) return "$m meters"
        val ft = metersToFeet(m.toDouble())
        return if (ft <= MILES_ABOVE_FEET) "${ft.roundToInt()} feet"
        else String.format(Locale.US, "%.2f miles", ft / FEET_PER_MILE)
    }

    /**
     * The map scale bar: the longest "nice" distance (1, 2 or 5 times a power of
     * ten, in the display unit) that fits in [maxMeters]. Returns the distance in
     * meters, for drawing, and its label. English steps in feet up to 5,000 ft and
     * in miles from 1 mi up.
     */
    fun scaleBar(maxMeters: Double, u: UnitSystem): Pair<Double, String> {
        if (u == UnitSystem.Metric) {
            val nice = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500,
                1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000, 500000)
            val m = nice.lastOrNull { it.toDouble() <= maxMeters } ?: nice.first()
            return m.toDouble() to (if (m >= 1000) "${m / 1000} km" else "$m m")
        }
        val maxFt = metersToFeet(maxMeters)
        val miles = listOf(1, 2, 5, 10, 20, 50, 100, 200)
        val mi = miles.lastOrNull { it * FEET_PER_MILE <= maxFt }
        if (mi != null) return feetToMeters(mi * FEET_PER_MILE) to "$mi mi"
        val feet = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000)
        val ft = feet.lastOrNull { it <= maxFt } ?: feet.first()
        return feetToMeters(ft.toDouble()) to "$ft ft"
    }

    // ── Chart axes ───────────────────────────────────────────────────────────

    /**
     * A chart gridline label for [displayValue], already in the display unit:
     * "120m" or "400ft", with one decimal when the grid is finer than one unit.
     */
    fun gridLabel(displayValue: Float, interval: Float, u: UnitSystem): String {
        val unit = altitudeUnit(u)
        return if (interval < 1f) String.format(Locale.US, "%.1f%s", displayValue, unit)
        else "${displayValue.roundToInt()}$unit"
    }

    // ── Deployment altitudes (the wire carries whole meters) ─────────────────

    /**
     * The step English deployment altitudes move in. The wire carries whole meters
     * (uint16), and 1 m is 3.28 ft, so a value typed to the foot cannot survive the
     * round trip: 601 ft is stored as 183 m and reads back as 600 ft. Every multiple
     * of 10 ft is within 0.5 m (1.6 ft) of a whole meter, so it rounds back to the
     * same 10 ft value every time, and no two of them land on the same meter.
     */
    const val DEPLOY_STEP_FT = 10

    /** The whole meters the locator will hold for a deployment altitude typed in feet. */
    fun deployFeetToMeters(ft: Int): Int = feetToMeters(ft.toDouble()).roundToInt()

    /**
     * A stored deployment altitude in feet, for display. A value that is the image
     * of a 10 ft step reads as that step (183 m reads 600 ft, not 600.4 or 601).
     * Anything else (a value set in meters, or the 130 m default) reads to the
     * nearest foot: 427 ft, not a 430 the locator does not hold.
     */
    fun deployMetersToFeet(m: Int): Int {
        val ft = metersToFeet(m.toDouble())
        val step = (ft / DEPLOY_STEP_FT).roundToInt() * DEPLOY_STEP_FT
        return if (deployFeetToMeters(step) == m) step else ft.roundToInt()
    }

    /** Whether [ft] is a value the arrows and a typed entry can land on. */
    fun onDeployStep(ft: Int) = ft % DEPLOY_STEP_FT == 0

    /** The next 10 ft step above (delta > 0) or below (delta < 0) [ft]. */
    fun nextDeployStep(ft: Int, delta: Int): Int {
        val s = DEPLOY_STEP_FT
        return if (delta > 0) (floor(ft.toDouble() / s).toInt() + 1) * s
        else (kotlin.math.ceil(ft.toDouble() / s).toInt() - 1) * s
    }

    /** "600 ft" or "183 m" for a stored deployment altitude. */
    fun formatDeployAltitude(m: Int, u: UnitSystem): String =
        if (u == UnitSystem.English) "${deployMetersToFeet(m)} ft" else "$m m"

    // ── Default ──────────────────────────────────────────────────────────────

    /**
     * The choice for an install that has never made one. An install already in
     * use stays metric: changing the unit under a flyer who has been reading meters
     * is itself the units mistake this setting must not cause. A fresh install
     * follows the phone's region: English in the US (and Liberia and Myanmar, the
     * other two countries that never adopted metric), metric everywhere else.
     */
    fun defaultFor(priorUse: Boolean, country: String): UnitSystem =
        if (!priorUse && country.uppercase(Locale.US) in setOf("US", "LR", "MM")) UnitSystem.English
        else UnitSystem.Metric

}
