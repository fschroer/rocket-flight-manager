package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.LocatorSettingsBounds
import com.steampigeon.flightmanager.data.LocatorSettingsBounds.Parsed
import com.steampigeon.flightmanager.data.LocatorSettingsBounds.Problem
import com.steampigeon.flightmanager.data.LocatorSettingsBounds.Setting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deployment settings bounds table and push rule (ADR-0034, #50).
 *
 * Android's copy of the locator's `Tests/SettingsBounds`, which guards the
 * firmware's `SettingsBounds.hpp`.  The two tables must agree: a value this
 * screen accepts and the locator rejects shows as "not confirmed" with no
 * reason given.
 *
 * The motivating record: Red Ryder Revisited flew with main primary 2,500 m and
 * backup 2,400 m, typed as feet, so both mains came due at apogee.
 */
class LocatorSettingsBoundsTest {

    /** The locator's defaults: drogue 0.0 / 2.0 s, main 130 / 100 m. */
    private val defaults = LocatorConfig(
        droguePrimaryDeployDelay = 0, drogueBackupDeployDelay = 20,
        mainPrimaryDeployAltitude = 130, mainBackupDeployAltitude = 100,
    )

    private fun LocatorConfig.drogue() = droguePrimaryDeployDelay to drogueBackupDeployDelay
    /** (backup, primary): the main pair's LOWER member is the backup. */
    private fun LocatorConfig.main() = mainBackupDeployAltitude to mainPrimaryDeployAltitude

    @Test
    fun theDefaultsPassTheirOwnBounds() = assertTrue(LocatorSettingsBounds.isValid(defaults))

    @Test
    fun redRyderIsRejected() {
        assertFalse(LocatorSettingsBounds.isValid(
            defaults.copy(mainPrimaryDeployAltitude = 2500, mainBackupDeployAltitude = 2400)))
    }

    @Test
    fun theTablesEdgesMatchTheFirmware() {
        val v = LocatorSettingsBounds::isValid
        assertTrue(v(defaults.copy(droguePrimaryDeployDelay = 20, drogueBackupDeployDelay = 40)))
        assertFalse(v(defaults.copy(droguePrimaryDeployDelay = 21, drogueBackupDeployDelay = 40)))
        assertFalse(v(defaults.copy(droguePrimaryDeployDelay = 20, drogueBackupDeployDelay = 41)))
        assertFalse(v(defaults.copy(droguePrimaryDeployDelay = 20, drogueBackupDeployDelay = 20)))
        assertTrue(v(defaults.copy(mainPrimaryDeployAltitude = 500, mainBackupDeployAltitude = 499)))
        assertFalse(v(defaults.copy(mainPrimaryDeployAltitude = 501, mainBackupDeployAltitude = 499)))
        assertFalse(v(defaults.copy(mainPrimaryDeployAltitude = 499, mainBackupDeployAltitude = 499)))
        assertTrue(v(defaults.copy(mainPrimaryDeployAltitude = 1, mainBackupDeployAltitude = 0)))
        assertTrue(v(defaults.copy(loraChannel = 63)))
        assertFalse(v(defaults.copy(loraChannel = 64)))
    }

    // ── Typed entry: refused, never clamped (decision 3) ──────────────────────

    @Test
    fun aTypedMainAboveTheLimitIsRefusedNotClamped() {
        assertEquals(Parsed.Refused(Problem.AboveMax(500)), LocatorSettingsBounds.validate(Setting.MainPrimary, "2500"))
        assertEquals(Parsed.Refused(Problem.AboveMax(499)), LocatorSettingsBounds.validate(Setting.MainBackup, "2400"))
        assertEquals(Parsed.Ok(500), LocatorSettingsBounds.validate(Setting.MainPrimary, "500"))
        assertEquals(Parsed.Ok(0), LocatorSettingsBounds.validate(Setting.MainBackup, "0"))
        assertEquals(Parsed.Refused(Problem.BelowMin(1)), LocatorSettingsBounds.validate(Setting.MainPrimary, "0"))
    }

    @Test
    fun typedDrogueDelaysAreTenths() {
        assertEquals(Parsed.Ok(15), LocatorSettingsBounds.validate(Setting.DroguePrimary, "1.5"))
        assertEquals(Parsed.Ok(10), LocatorSettingsBounds.validate(Setting.DroguePrimary, "1."))
        assertEquals(Parsed.Ok(5), LocatorSettingsBounds.validate(Setting.DroguePrimary, ".5"))
        assertEquals(Parsed.Ok(20), LocatorSettingsBounds.validate(Setting.DroguePrimary, "2"))
        assertEquals(Parsed.Refused(Problem.AboveMax(20)), LocatorSettingsBounds.validate(Setting.DroguePrimary, "2.1"))
        assertEquals(Parsed.Ok(40), LocatorSettingsBounds.validate(Setting.DrogueBackup, "4.0"))
        assertEquals(Parsed.Refused(Problem.AboveMax(40)), LocatorSettingsBounds.validate(Setting.DrogueBackup, "4.1"))
        assertEquals(Parsed.Refused(Problem.BelowMin(1)), LocatorSettingsBounds.validate(Setting.DrogueBackup, "0"))
        assertEquals(Parsed.Refused(Problem.TooPrecise), LocatorSettingsBounds.validate(Setting.DrogueBackup, "1.25"))
    }

    @Test
    fun emptyOrUnreadableEntriesAreRefused() {
        for (s in Setting.entries) {
            assertEquals(Parsed.Refused(Problem.Empty), LocatorSettingsBounds.validate(s, ""))
            assertEquals(Parsed.Refused(Problem.Empty), LocatorSettingsBounds.validate(s, "1-2"))
        }
        // A long run of digits is above the limit, not an Int overflow.
        assertEquals(Parsed.Refused(Problem.AboveMax(500)),
            LocatorSettingsBounds.validate(Setting.MainPrimary, "99999999999"))
    }

    @Test
    fun formatRoundTripsThroughValidate() {
        for (s in Setting.entries) for (v in s.min..s.max) {
            assertEquals(Parsed.Ok(v), LocatorSettingsBounds.validate(s, LocatorSettingsBounds.format(s, v)))
        }
    }

    // ── The push rule, both ways (decision 2) ─────────────────────────────────

    @Test
    fun drogueRaisingThePrimaryOntoTheBackupPushesItUp() {
        var c = defaults.copy(droguePrimaryDeployDelay = 18, drogueBackupDeployDelay = 19)
        c = LocatorSettingsBounds.step(c, Setting.DroguePrimary, +1); assertEquals(19 to 20, c.drogue())
        c = LocatorSettingsBounds.step(c, Setting.DroguePrimary, +1); assertEquals(20 to 21, c.drogue())
        c = LocatorSettingsBounds.step(c, Setting.DroguePrimary, +1); assertEquals(20 to 21, c.drogue())
    }

    @Test
    fun drogueLoweringTheBackupOntoThePrimaryPushesItDown() {
        var c = defaults.copy(droguePrimaryDeployDelay = 20, drogueBackupDeployDelay = 21)
        c = LocatorSettingsBounds.step(c, Setting.DrogueBackup, -1); assertEquals(19 to 20, c.drogue())
        repeat(30) { c = LocatorSettingsBounds.step(c, Setting.DrogueBackup, -1) }
        assertEquals(0 to 1, c.drogue())        // primary at 0: the backup stops at 0.1 s
        repeat(50) { c = LocatorSettingsBounds.step(c, Setting.DrogueBackup, +1) }
        assertEquals(0 to 40, c.drogue())       // the backup stops at 4.0 s
        repeat(30) { c = LocatorSettingsBounds.step(c, Setting.DroguePrimary, -1) }
        assertEquals(0 to 40, c.drogue())       // lowering the primary never moves the backup
    }

    @Test
    fun mainRaisingTheBackupOntoThePrimaryPushesItUpTo500() {
        var c = defaults.copy(mainBackupDeployAltitude = 129, mainPrimaryDeployAltitude = 130)
        c = LocatorSettingsBounds.step(c, Setting.MainBackup, +1); assertEquals(130 to 131, c.main())
        c = defaults.copy(mainBackupDeployAltitude = 498, mainPrimaryDeployAltitude = 499)
        c = LocatorSettingsBounds.step(c, Setting.MainBackup, +1); assertEquals(499 to 500, c.main())
        c = LocatorSettingsBounds.step(c, Setting.MainBackup, +1); assertEquals(499 to 500, c.main())
    }

    @Test
    fun mainLoweringThePrimaryOntoTheBackupPushesItDown() {
        var c = defaults.copy(mainBackupDeployAltitude = 499, mainPrimaryDeployAltitude = 500)
        c = LocatorSettingsBounds.step(c, Setting.MainPrimary, -1); assertEquals(498 to 499, c.main())
        c = defaults.copy(mainBackupDeployAltitude = 0, mainPrimaryDeployAltitude = 1)
        c = LocatorSettingsBounds.step(c, Setting.MainPrimary, -1); assertEquals(0 to 1, c.main())
    }

    @Test
    fun aTypedValuePushesTooInBothDirections() {
        // ADR-0034's example: drogue primary typed 1.9 s with the backup at 1.5 s.
        var c = defaults.copy(droguePrimaryDeployDelay = 5, drogueBackupDeployDelay = 15)
        c = LocatorSettingsBounds.withValue(c, Setting.DroguePrimary, 19); assertEquals(19 to 20, c.drogue())
        c = LocatorSettingsBounds.withValue(c, Setting.DrogueBackup, 3);   assertEquals(2 to 3, c.drogue())
        c = defaults.copy(mainBackupDeployAltitude = 100, mainPrimaryDeployAltitude = 130)
        c = LocatorSettingsBounds.withValue(c, Setting.MainPrimary, 50);   assertEquals(49 to 50, c.main())
        c = LocatorSettingsBounds.withValue(c, Setting.MainBackup, 300);   assertEquals(300 to 301, c.main())
    }

    @Test
    fun typingPushesFromTheValueBeforeTypingBegan() {
        // The screen applies each keystroke to the config as it stood when typing
        // began.  Typing 300 passes through 3 and 30; from that base the backup
        // ends where it started, not dragged down to 29.
        val base = defaults   // main backup 100, primary 130
        var c = base
        for (keystrokes in listOf(3, 30, 300)) c = LocatorSettingsBounds.withValue(base, Setting.MainPrimary, keystrokes)
        assertEquals(100 to 300, c.main())
    }

    @Test
    fun tenThousandRandomStepsNeverLeaveTheTable() {
        val rnd = java.util.Random(50)
        var c = defaults
        repeat(10_000) {
            val s = Setting.entries[rnd.nextInt(4)]
            c = LocatorSettingsBounds.step(c, s, if (rnd.nextBoolean()) 1 else -1)
            assertTrue("after stepping $s: $c", LocatorSettingsBounds.isValid(c))
        }
    }

    @Test
    fun everyAcceptedTypedValueLeavesAValidPair() {
        val starts = listOf(
            defaults,
            defaults.copy(droguePrimaryDeployDelay = 20, drogueBackupDeployDelay = 40,
                mainBackupDeployAltitude = 499, mainPrimaryDeployAltitude = 500),
            defaults.copy(droguePrimaryDeployDelay = 0, drogueBackupDeployDelay = 1,
                mainBackupDeployAltitude = 0, mainPrimaryDeployAltitude = 1),
        )
        for (start in starts) for (s in Setting.entries) for (v in s.min..s.max) {
            val c = LocatorSettingsBounds.withValue(start, s, v)
            assertTrue("$s = $v from $start", LocatorSettingsBounds.isValid(c))
            assertEquals(v, LocatorSettingsBounds.valueOf(c, s))
        }
    }
}
