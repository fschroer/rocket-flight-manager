package com.steampigeon.flightmanager.data

/**
 * The one bounds table for the locator's deployment settings, and the rule that
 * keeps each primary/backup pair in order (ADR "Deployment settings have one
 * bounds table", ADR-0034, #50).
 *
 * Red Ryder Revisited flew with main primary 2,500 m / backup 2,400 m, typed as
 * feet: both mains came due at apogee.  The arrows here stopped at 500 m, but a
 * typed value went straight into the staged config, and the locator stored
 * whatever arrived.  Every boundary now applies this table:
 *
 *   * this screen refuses an out-of-range typed value — it shows the error and
 *     disables Update, and never clamps (decision 3);
 *   * the locator rejects a whole config request that fails it (decision 4),
 *     and resets stored out-of-range fields to defaults at boot (decision 5).
 *
 * The firmware's copy is `Rocket/Archive/Inc/SettingsBounds.hpp` in the Locator
 * repo; change both together.  Drogue delays are in tenths of a second, main
 * altitudes in meters — the wire units.
 */
object LocatorSettingsBounds {
    const val DROGUE_PRIMARY_MAX = 20     // 2.0 s
    const val DROGUE_BACKUP_MAX = 40      // 4.0 s
    const val MAIN_PRIMARY_MAX = 500      // m
    const val MAIN_BACKUP_MAX = MAIN_PRIMARY_MAX - 1
    const val LORA_CHANNEL_MAX = 63

    /** A deployment setting, named by what it is rather than which channel shows it. */
    enum class Setting(val tenths: Boolean, val min: Int, val max: Int) {
        // The upper member of each pair has a floor of 1 step: it must stay above
        // a lower member that can itself go no lower than 0.
        DroguePrimary(tenths = true, min = 0, max = DROGUE_PRIMARY_MAX),
        DrogueBackup(tenths = true, min = 1, max = DROGUE_BACKUP_MAX),
        MainBackup(tenths = false, min = 0, max = MAIN_BACKUP_MAX),
        MainPrimary(tenths = false, min = 1, max = MAIN_PRIMARY_MAX),
    }

    fun droguePairValid(primary: Int, backup: Int) =
        primary in 0..DROGUE_PRIMARY_MAX && backup <= DROGUE_BACKUP_MAX && backup > primary

    fun mainPairValid(primary: Int, backup: Int) =
        backup >= 0 && primary <= MAIN_PRIMARY_MAX && primary > backup

    /** Every deployment field in range and in order — what the locator accepts. */
    fun isValid(c: LocatorConfig) =
        droguePairValid(c.droguePrimaryDeployDelay, c.drogueBackupDeployDelay) &&
            mainPairValid(c.mainPrimaryDeployAltitude, c.mainBackupDeployAltitude) &&
            c.loraChannel in 0..LORA_CHANNEL_MAX

    fun valueOf(c: LocatorConfig, s: Setting) = when (s) {
        Setting.DroguePrimary -> c.droguePrimaryDeployDelay
        Setting.DrogueBackup -> c.drogueBackupDeployDelay
        Setting.MainBackup -> c.mainBackupDeployAltitude
        Setting.MainPrimary -> c.mainPrimaryDeployAltitude
    }

    /**
     * [c] with setting [s] set to [value], pushing its partner along if the two
     * would meet (decision 2).  The push works both ways: moving the lower member
     * of a pair up onto the upper one moves the upper one up a step; moving the
     * upper member down onto the lower one moves the lower one down a step.
     *
     * [value] is clamped to the setting's own range, which is right for the
     * arrows (they stop at the limits) and never reached for typed values, which
     * [validate] refuses before they get here.  Within those ranges the push
     * always fits: the lower member's maximum is below the upper member's, and
     * the upper member's floor is one step above the lower member's.
     */
    fun withValue(c: LocatorConfig, s: Setting, value: Int): LocatorConfig {
        val v = value.coerceIn(s.min, s.max)
        return when (s) {
            Setting.DroguePrimary -> c.copy(
                droguePrimaryDeployDelay = v,
                drogueBackupDeployDelay = maxOf(c.drogueBackupDeployDelay, v + 1))
            Setting.DrogueBackup -> c.copy(
                drogueBackupDeployDelay = v,
                droguePrimaryDeployDelay = minOf(c.droguePrimaryDeployDelay, v - 1))
            Setting.MainBackup -> c.copy(
                mainBackupDeployAltitude = v,
                mainPrimaryDeployAltitude = maxOf(c.mainPrimaryDeployAltitude, v + 1))
            Setting.MainPrimary -> c.copy(
                mainPrimaryDeployAltitude = v,
                mainBackupDeployAltitude = minOf(c.mainBackupDeployAltitude, v - 1))
        }
    }

    /** One arrow press: [delta] steps, stopping at the setting's limits. */
    fun step(c: LocatorConfig, s: Setting, delta: Int) = withValue(c, s, valueOf(c, s) + delta)

    /** Why a typed entry is refused. */
    sealed interface Problem {
        data object Empty : Problem
        /** Drogue delays are tenths of a second: "1.25" is not a value the locator can hold. */
        data object TooPrecise : Problem
        data class BelowMin(val min: Int) : Problem
        data class AboveMax(val max: Int) : Problem
        /** English deployment altitudes move in [Units.DEPLOY_STEP_FT] steps; see there. */
        data class NotOnStep(val step: Int) : Problem
    }

    sealed interface Parsed {
        data class Ok(val value: Int) : Parsed
        data class Refused(val problem: Problem) : Parsed
    }

    private val tenthsPattern = Regex("""^\d*(\.\d?)?$""")
    private val wholePattern = Regex("""^\d*$""")

    /**
     * Parse what was typed into a field for [s], in the setting's wire units.
     * Out of range is refused, never clamped (decision 3): 2,500 meant as feet
     * would clamp to 500 m, which is still wrong and now looks valid.
     */
    fun validate(s: Setting, text: String): Parsed {
        val t = text.trim()
        if (t.isEmpty() || t == ".") return Parsed.Refused(Problem.Empty)
        val value = if (s.tenths) {
            if (!tenthsPattern.matches(t)) {
                return Parsed.Refused(
                    if (Regex("""^\d*\.\d{2,}$""").matches(t)) Problem.TooPrecise else Problem.Empty)
            }
            val (whole, frac) = t.split('.').let { it[0] to it.getOrElse(1) { "" } }
            // A long run of digits is above any limit; don't let it overflow Int.
            if (whole.length > 6) return Parsed.Refused(Problem.AboveMax(s.max))
            (whole.ifEmpty { "0" }.toInt()) * 10 + (frac.ifEmpty { "0" }.toInt())
        } else {
            if (!wholePattern.matches(t)) return Parsed.Refused(Problem.Empty)
            if (t.length > 6) return Parsed.Refused(Problem.AboveMax(s.max))
            t.toInt()
        }
        return when {
            value < s.min -> Parsed.Refused(Problem.BelowMin(s.min))
            value > s.max -> Parsed.Refused(Problem.AboveMax(s.max))
            else -> Parsed.Ok(value)
        }
    }

    /** How a value is shown in its field: "2.0" for tenths, "500" for meters. */
    fun format(s: Setting, value: Int): String =
        if (s.tenths) "${value / 10}.${value % 10}" else value.toString()

    // ── English entry (locator ADR "Units are SI underneath; English is a display
    // choice") ─────────────────────────────────────────────────────────────────
    //
    // The functions above work in wire units and stay the rule. These wrap them
    // for a field shown in feet: they take and give wire units (whole meters), so
    // the staged config, isValid and what is sent are untouched; only the values a
    // field can land on, and the numbers its refusals quote, are in feet.
    //
    // Feet ranges are 10 ft steps inside the meter ranges: main primary 10 to
    // 1,640 ft (3 to 500 m), main backup 0 to 1,630 ft (0 to 497 m). A stored value
    // outside them (a backup of 498 m set in meters) is still valid and still
    // shown, to the foot; the arrows just cannot reach it in feet.

    const val MAIN_PRIMARY_MIN_FT = 10
    const val MAIN_PRIMARY_MAX_FT = 1640    // 499.9 m -> 500 m
    const val MAIN_BACKUP_MAX_FT = MAIN_PRIMARY_MAX_FT - Units.DEPLOY_STEP_FT

    private fun inFeet(s: Setting, u: UnitSystem) = u == UnitSystem.English && !s.tenths

    /** A setting's limits in the unit its field is shown in. */
    fun minShown(s: Setting, u: UnitSystem) =
        if (!inFeet(s, u)) s.min else if (s == Setting.MainPrimary) MAIN_PRIMARY_MIN_FT else 0

    fun maxShown(s: Setting, u: UnitSystem) =
        if (!inFeet(s, u)) s.max else if (s == Setting.MainPrimary) MAIN_PRIMARY_MAX_FT else MAIN_BACKUP_MAX_FT

    /**
     * [withValue] for a field shown in [u]. In feet a pushed partner moves to the
     * next 10 ft step beyond [value] rather than one meter, so the pair stays on
     * values the field can show and the arrows can return to.
     */
    fun withValue(c: LocatorConfig, s: Setting, value: Int, u: UnitSystem): LocatorConfig {
        if (!inFeet(s, u)) return withValue(c, s, value)
        val v = value.coerceIn(s.min, s.max)
        val ft = Units.deployMetersToFeet(v)
        return when (s) {
            Setting.MainBackup -> c.copy(
                mainBackupDeployAltitude = v,
                mainPrimaryDeployAltitude =
                    if (c.mainPrimaryDeployAltitude > v) c.mainPrimaryDeployAltitude
                    else Units.deployFeetToMeters(Units.nextDeployStep(ft, +1)).coerceAtMost(MAIN_PRIMARY_MAX))
            Setting.MainPrimary -> c.copy(
                mainPrimaryDeployAltitude = v,
                mainBackupDeployAltitude =
                    if (c.mainBackupDeployAltitude < v) c.mainBackupDeployAltitude
                    else Units.deployFeetToMeters(Units.nextDeployStep(ft, -1)).coerceAtLeast(0))
            else -> withValue(c, s, value)
        }
    }

    /** One arrow press in [u]: to the next 10 ft step in feet, stopping at the feet limits. */
    fun step(c: LocatorConfig, s: Setting, delta: Int, u: UnitSystem): LocatorConfig {
        if (!inFeet(s, u)) return step(c, s, delta)
        val ft = Units.deployMetersToFeet(valueOf(c, s))
        val target = Units.nextDeployStep(ft, delta).coerceIn(minShown(s, u), maxShown(s, u))
        // At or past a limit the clamp can land behind where it started (a 2 m
        // primary is 7 ft; down clamps to 10 ft, which is UP). That is no step.
        if ((delta > 0 && target <= ft) || (delta < 0 && target >= ft)) return c
        return withValue(c, s, Units.deployFeetToMeters(target), u)
    }

    /**
     * [validate] for a field shown in [u]. In feet: whole feet, in range, on a
     * 10 ft step, returned as the whole meters the locator will hold. Range is
     * checked before the step, because 2,500 is a units mistake to name as one,
     * and still refused, never clamped (decision 3): 2,500 ft is 762 m.
     */
    fun validate(s: Setting, text: String, u: UnitSystem): Parsed {
        if (!inFeet(s, u)) return validate(s, text)
        val t = text.trim()
        if (t.isEmpty()) return Parsed.Refused(Problem.Empty)
        if (!wholePattern.matches(t)) return Parsed.Refused(Problem.Empty)
        if (t.length > 6) return Parsed.Refused(Problem.AboveMax(maxShown(s, u)))
        val ft = t.toInt()
        return when {
            ft < minShown(s, u) -> Parsed.Refused(Problem.BelowMin(minShown(s, u)))
            ft > maxShown(s, u) -> Parsed.Refused(Problem.AboveMax(maxShown(s, u)))
            !Units.onDeployStep(ft) -> Parsed.Refused(Problem.NotOnStep(Units.DEPLOY_STEP_FT))
            else -> Parsed.Ok(Units.deployFeetToMeters(ft))
        }
    }

    /** [format] for a field shown in [u]: "600" for 183 m in feet. */
    fun format(s: Setting, value: Int, u: UnitSystem): String =
        if (!inFeet(s, u)) format(s, value) else Units.deployMetersToFeet(value).toString()

    /** A value with its unit, for a refusal or a readback: "2.0 s", "500 m", "1640 ft". */
    fun shown(s: Setting, valueShown: Int, u: UnitSystem): String = when {
        s.tenths -> "${format(s, valueShown)} s"
        inFeet(s, u) -> "$valueShown ft"
        else -> "$valueShown m"
    }
}
