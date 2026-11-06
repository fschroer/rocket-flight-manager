package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.LocatorSettingsBounds
import com.steampigeon.flightmanager.data.LocatorSettingsBounds.Parsed
import com.steampigeon.flightmanager.data.LocatorSettingsBounds.Problem
import com.steampigeon.flightmanager.data.LocatorSettingsBounds.Setting
import com.steampigeon.flightmanager.data.UnitSystem
import com.steampigeon.flightmanager.data.UnitSystem.English
import com.steampigeon.flightmanager.data.UnitSystem.Metric
import com.steampigeon.flightmanager.data.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display-units rules (locator ADR "Units are SI underneath; English is a
 * display choice"). Everything stored stays SI; these pin how a number is
 * printed, spoken and typed in each system, and above all that a deployment
 * altitude typed in feet survives the whole-meter wire unchanged.
 */
class UnitsTest {

    private val defaults = LocatorConfig(
        droguePrimaryDeployDelay = 0, drogueBackupDeployDelay = 20,
        mainPrimaryDeployAltitude = 130, mainBackupDeployAltitude = 100,
    )

    // ── Deployment altitudes on the whole-meter wire ──────────────────────────

    @Test
    fun everyTenFootStepRoundTripsThroughWholeMeters() {
        val seen = HashSet<Int>()
        for (ft in 0..LocatorSettingsBounds.MAIN_PRIMARY_MAX_FT step Units.DEPLOY_STEP_FT) {
            val m = Units.deployFeetToMeters(ft)
            assertEquals("$ft ft -> $m m", ft, Units.deployMetersToFeet(m))
            assertTrue("two steps share $m m", seen.add(m))
        }
    }

    @Test
    fun aFootByFootValueWouldNotSurvive() {
        // Why the step exists: 601 ft is 183 m, which reads back as 600 ft.
        assertEquals(183, Units.deployFeetToMeters(601))
        assertEquals(600, Units.deployMetersToFeet(183))
    }

    @Test
    fun aMeterValueOffTheGridReadsToTheFoot() {
        // The 130 m default is 426.5 ft: shown as 427, never as a 430 it is not.
        assertEquals(427, Units.deployMetersToFeet(130))
        assertEquals(328, Units.deployMetersToFeet(100))
        assertEquals(1640, Units.deployMetersToFeet(500))
    }

    @Test
    fun feetLimitsSitInsideTheMeterBounds() {
        assertEquals(500, Units.deployFeetToMeters(LocatorSettingsBounds.MAIN_PRIMARY_MAX_FT))
        assertTrue(Units.deployFeetToMeters(LocatorSettingsBounds.MAIN_BACKUP_MAX_FT) <= LocatorSettingsBounds.MAIN_BACKUP_MAX)
        assertTrue(Units.deployFeetToMeters(LocatorSettingsBounds.MAIN_PRIMARY_MIN_FT) >= Setting.MainPrimary.min)
    }

    @Test
    fun feetEntryIsRefusedNotClamped() {
        // Red Ryder Revisited, typed into a feet field: 2,500 ft is 762 m.
        assertEquals(Parsed.Refused(Problem.AboveMax(1640)), LocatorSettingsBounds.validate(Setting.MainPrimary, "2500", English))
        assertEquals(Parsed.Refused(Problem.AboveMax(1630)), LocatorSettingsBounds.validate(Setting.MainBackup, "2400", English))
        assertEquals(Parsed.Refused(Problem.NotOnStep(10)), LocatorSettingsBounds.validate(Setting.MainPrimary, "601", English))
        assertEquals(Parsed.Refused(Problem.BelowMin(10)), LocatorSettingsBounds.validate(Setting.MainPrimary, "0", English))
        assertEquals(Parsed.Refused(Problem.Empty), LocatorSettingsBounds.validate(Setting.MainPrimary, "", English))
        assertEquals(Parsed.Ok(183), LocatorSettingsBounds.validate(Setting.MainPrimary, "600", English))
        assertEquals(Parsed.Ok(0), LocatorSettingsBounds.validate(Setting.MainBackup, "0", English))
    }

    @Test
    fun drogueDelaysIgnoreTheUnitSystem() {
        assertEquals(LocatorSettingsBounds.validate(Setting.DroguePrimary, "1.5"),
            LocatorSettingsBounds.validate(Setting.DroguePrimary, "1.5", English))
        assertEquals(defaults.copy(droguePrimaryDeployDelay = 1),
            LocatorSettingsBounds.step(defaults, Setting.DroguePrimary, +1, English))
    }

    @Test
    fun metricIsExactlyTheOldRule() {
        for (s in Setting.entries) for (d in listOf(-1, 1)) {
            assertEquals(LocatorSettingsBounds.step(defaults, s, d), LocatorSettingsBounds.step(defaults, s, d, Metric))
        }
        assertEquals(LocatorSettingsBounds.validate(Setting.MainPrimary, "300"),
            LocatorSettingsBounds.validate(Setting.MainPrimary, "300", Metric))
    }

    @Test
    fun feetArrowsLandOnTenFootSteps() {
        // From the 130 m default (427 ft): up to 430 ft, down to 420 ft.
        val up = LocatorSettingsBounds.step(defaults, Setting.MainPrimary, +1, English)
        assertEquals(430, Units.deployMetersToFeet(up.mainPrimaryDeployAltitude))
        val down = LocatorSettingsBounds.step(defaults, Setting.MainPrimary, -1, English)
        assertEquals(420, Units.deployMetersToFeet(down.mainPrimaryDeployAltitude))
        // And from a step, one step each way.
        val again = LocatorSettingsBounds.step(up, Setting.MainPrimary, +1, English)
        assertEquals(440, Units.deployMetersToFeet(again.mainPrimaryDeployAltitude))
    }

    @Test
    fun feetArrowsStopAtTheFeetLimits() {
        val top = defaults.copy(mainPrimaryDeployAltitude = 500)
        assertEquals(top, LocatorSettingsBounds.step(top, Setting.MainPrimary, +1, English))
        val bottom = defaults.copy(mainBackupDeployAltitude = 0)
        assertEquals(bottom, LocatorSettingsBounds.step(bottom, Setting.MainBackup, -1, English))
        // A 2 m primary is 7 ft: "down" would clamp UP to 10 ft, which is no step.
        val low = defaults.copy(mainPrimaryDeployAltitude = 2, mainBackupDeployAltitude = 0)
        assertEquals(low, LocatorSettingsBounds.step(low, Setting.MainPrimary, -1, English))
    }

    @Test
    fun aFeetPushMovesThePartnerOneStep() {
        // Backup 600 ft typed with the primary at 600 ft: the primary goes to 610 ft.
        val c = defaults.copy(mainPrimaryDeployAltitude = Units.deployFeetToMeters(600),
            mainBackupDeployAltitude = Units.deployFeetToMeters(500))
        val pushed = LocatorSettingsBounds.withValue(c, Setting.MainBackup, Units.deployFeetToMeters(600), English)
        assertEquals(600, Units.deployMetersToFeet(pushed.mainBackupDeployAltitude))
        assertEquals(610, Units.deployMetersToFeet(pushed.mainPrimaryDeployAltitude))
        assertTrue(LocatorSettingsBounds.isValid(pushed))
        // And down: primary 10 ft pushes the backup to 0.
        val floor = LocatorSettingsBounds.withValue(c, Setting.MainPrimary, Units.deployFeetToMeters(10), English)
        assertEquals(0, floor.mainBackupDeployAltitude)
        assertTrue(LocatorSettingsBounds.isValid(floor))
    }

    @Test
    fun everyFeetArrowSequenceStaysValid() {
        var c = defaults
        repeat(400) { c = LocatorSettingsBounds.step(c, Setting.MainBackup, +1, English); assertTrue(LocatorSettingsBounds.isValid(c)) }
        assertEquals(1630, Units.deployMetersToFeet(c.mainBackupDeployAltitude))
        assertEquals(1640, Units.deployMetersToFeet(c.mainPrimaryDeployAltitude))
        repeat(400) { c = LocatorSettingsBounds.step(c, Setting.MainPrimary, -1, English); assertTrue(LocatorSettingsBounds.isValid(c)) }
        assertEquals(10, Units.deployMetersToFeet(c.mainPrimaryDeployAltitude))
        assertEquals(0, c.mainBackupDeployAltitude)
    }

    @Test
    fun deployReadback() {
        assertEquals("183 m", Units.formatDeployAltitude(183, Metric))
        assertEquals("600 ft", Units.formatDeployAltitude(183, English))
        assertEquals("1640 ft", LocatorSettingsBounds.shown(Setting.MainPrimary, 1640, English))
        assertEquals("500 m", LocatorSettingsBounds.shown(Setting.MainPrimary, 500, Metric))
        assertEquals("2.0 s", LocatorSettingsBounds.shown(Setting.DrogueBackup, 20, English))
    }

    // ── Display and speech ────────────────────────────────────────────────────

    @Test
    fun altitudes() {
        assertEquals("123.4 m", Units.formatAltitude(123.4f, Metric))
        assertEquals("405 ft", Units.formatAltitude(123.4f, English))
        assertEquals("1432 meters", Units.spokenAltitude(1432.6f, Metric))
        assertEquals("4700 feet", Units.spokenAltitude(1432.6f, English))   // 4700.1 ft
        assertEquals("  405", Units.altitudeNumber(123.4f, English, 5))
        assertEquals("123.4", Units.altitudeNumber(123.4f, Metric, 5))
    }

    @Test
    fun ascentCallouts() {
        assertEquals(300, Units.ascentCalloutMark(357f, Metric))
        assertEquals(1000, Units.ascentCalloutMark(350f, English))   // 1148 ft
        assertEquals(0, Units.ascentCalloutMark(150f, English))      // 492 ft
        assertEquals(500, Units.ascentCalloutMark(153f, English))    // 502 ft
    }

    @Test
    fun speeds() {
        assertEquals("12.3 m/s", Units.formatSpeed(12.3f, Metric))
        assertEquals("40 ft/s", Units.formatSpeed(12.3f, English))
        assertEquals("50 meters per second", Units.spokenSpeed(50.9f, Metric))
        assertEquals("164 feet per second", Units.spokenSpeed(50f, English))
        assertEquals(1600f, Units.speedGaugeScale(English).first)
    }

    @Test
    fun distancesSwitchToMilesPastAThousandFeet() {
        assertEquals("250 m", Units.formatDistance(250, Metric))
        assertEquals("820 ft", Units.formatDistance(250, English))
        assertEquals("984 ft", Units.formatDistance(300, English))
        assertEquals("0.21 mi", Units.formatDistance(340, English))
        assertEquals("1.00 mi", Units.formatDistance(1609, English))
        assertEquals("820 feet", Units.spokenDistance(250, English))
        assertEquals("0.42 miles", Units.spokenDistance(680, English))
        assertEquals("250 meters", Units.spokenDistance(250, Metric))
    }

    @Test
    fun distanceFieldLinesUpWithTheOtherRows() {
        // The last digit sits where "AGL : %15.1f m" puts its last digit,
        // whatever the unit's length.
        assertEquals("%15d m".format(250), Units.distanceField(250, Metric, 15))
        assertEquals("%15d ft".format(820), Units.distanceField(250, English, 15))
        assertEquals("%15s mi".format("0.21"), Units.distanceField(340, English, 15))
    }

    @Test
    fun regionSizes() {
        assertEquals("10.0 km", Units.formatRegionKm(10.0, Metric))
        assertEquals("6.2 mi", Units.formatRegionKm(10.0, English))
        assertEquals("16 mi", Units.formatRegionKm(25.0, English, 0))
        assertEquals(1, Units.regionDecimals(2.0))     // 1.2 mi, not "1"
        assertEquals(0, Units.regionDecimals(25.0))
    }

    @Test
    fun scaleBar() {
        assertEquals(200.0 to "200 m", Units.scaleBar(250.0, Metric))
        assertEquals(2000.0 to "2 km", Units.scaleBar(2500.0, Metric))
        val (m, label) = Units.scaleBar(250.0, English)   // 820 ft fits 500 ft
        assertEquals("500 ft", label); assertEquals(152.4, m, 1e-9)
        assertEquals("1 mi", Units.scaleBar(2000.0, English).second)
        assertEquals("1 ft", Units.scaleBar(0.1, English).second)
    }

    @Test
    fun gridLabels() {
        assertEquals("400ft", Units.gridLabel(400f, 100f, English))
        assertEquals("0.5m", Units.gridLabel(0.5f, 0.5f, Metric))
    }

    // ── The default ───────────────────────────────────────────────────────────

    @Test
    fun anInstallInUseStaysMetric() {
        assertEquals(Metric, Units.defaultFor(priorUse = true, country = "US"))
        assertEquals(English, Units.defaultFor(priorUse = false, country = "US"))
        assertEquals(English, Units.defaultFor(priorUse = false, country = "us"))
        assertEquals(Metric, Units.defaultFor(priorUse = false, country = "CA"))
        assertEquals(Metric, Units.defaultFor(priorUse = false, country = ""))
        assertEquals(UnitSystem.entries.size, 2)
    }
}
