package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.simflight.Canopy
import com.steampigeon.flightmanager.data.simflight.DefaultRocket
import com.steampigeon.flightmanager.data.simflight.Drag
import com.steampigeon.flightmanager.data.simflight.MotorFiles
import com.steampigeon.flightmanager.data.simflight.SimMotor
import com.steampigeon.flightmanager.data.simflight.SimRocket
import com.steampigeon.flightmanager.data.simflight.Thrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The app-side half of the physics simulation (locator ADR-0041 amendment). */
class SimRocketTest {
    // A made-up motor in RASP form, not a real product's data.
    private val eng = """
        ; synthetic test motor
        T200 54 300 6-10-14 0.12 0.25 Test
          0.02 150
          0.10 210
          0.80 140
          1.50 110
          1.60 0
        ;
    """.trimIndent()

    @Test fun readsARaspFile() {
        val m = MotorFiles.parseEng(eng, "file:t200")!!
        assertEquals("T200", m.designation)
        assertEquals("Test", m.manufacturer)
        assertEquals(54f, m.diameterMm)
        assertEquals(0.25f, m.loadedKg)
        assertEquals(0.12f, m.propKg)
        assertEquals(5, m.curve.size)
        assertEquals(1.6f, m.burnS)
        assertEquals('H', m.impulseClass)   // 160-320 N s
    }

    @Test fun readsARockSimFile() {
        val rse = """<engine-database><engine-list>
            <engine mfg="Test" code="T200" Type="reload" dia="54." len="300." initWt="250." propWt="120." delays="6">
              <data><eng-data t="0." f="0." m="120."/><eng-data t="0.1" f="210." m="110."/>
              <eng-data t="1.6" f="0." m="0."/></data></engine></engine-list></engine-database>"""
        val m = MotorFiles.parse(rse, "file:rse")!!
        assertEquals("T200", m.designation)
        assertEquals(0.25f, m.loadedKg, 1e-6f)
        assertEquals(0.12f, m.propKg, 1e-6f)
        assertEquals(2, m.curve.size)   // the leading (0, 0) is implied, so dropped
    }

    @Test fun rejectsABrokenFile() {
        assertNull(MotorFiles.parseEng("; nothing\n", "x"))
        assertNull(MotorFiles.parseEng("T 54 300 0 0.3 0.25 Test\n0.1 10\n0.2 0\n", "x"))   // prop > total
    }

    @Test fun thinningKeepsTheImpulseAndTheBurnTime() {
        val curve = (1..300).map { i -> val t = i * 0.01f; t to (200f * kotlin.math.exp(-t) + if (i % 7 == 0) 15f else 0f) } + (3.01f to 0f)
        val thin = Thrust.thin(curve)
        assertEquals(32, thin.size)
        assertEquals(Thrust.impulse(curve), Thrust.impulse(thin), 1e-3f * Thrust.impulse(curve))
        assertEquals(3.01f, thin.last().first)
        assertTrue(thin.zipWithNext().all { it.second.first > it.first.first })
    }

    @Test fun spreadKeepsTheTypedCdBelowMachPointSeven() {
        val cd = Drag.spread(0.5f)
        assertEquals(0.5f, cd[0]); assertEquals(0.5f, cd[3] / 1.02f, 1e-6f)
        assertTrue(cd[5] > cd[3])
    }

    @Test fun fitsCdFromAnOpenRocketExport() {
        // The shape OpenRocket's CSV export has; values made up for the test.
        val csv = buildString {
            appendLine("# A simulation")
            appendLine("# Time (s),Altitude (m),Vertical velocity (m/s),Mach number (​),Drag coefficient (​)")
            appendLine("# Event LAUNCH occurred at t=0 seconds")
            appendLine("0,0,0,0,0.6")
            for (i in 1..60) {
                val m = i * 0.01f   // up to Mach 0.6
                appendLine("${i * 0.1},${i * 2},${200 - i},$m,${0.40f + 0.1f * m}")
            }
            appendLine("6.1,120,-1,0.01,0.9")   // after apogee: ignored
        }
        val cd = Drag.fitOpenRocketCsv(csv)!!
        assertEquals(0.40f + 0.1f * 0.3f, cd[1], 0.003f)
        assertEquals(0.40f + 0.1f * 0.5f, cd[2], 0.003f)
        // Past Mach 0.6 the standard rise carries the last fitted point.
        assertEquals(cd[2] * Drag.TRANSONIC_RISE[5] / Drag.TRANSONIC_RISE[2], cd[5], 1e-5f)
        assertNull(Drag.fitOpenRocketCsv("# Time (s),Altitude (m)\n0,0\n"))
    }

    @Test fun canopyCalculatorMatchesTheFormula() {
        // 1.33 kg under a 0.9 m, Cd 1.5 canopy at 15 C:
        // sqrt(2 * 1.33 * 9.80665 / (1.225 * 1.5 * 0.6362)) = 4.72 m/s.
        val v = Canopy.descentRate(1.33f, 0.9f, 1.5f)
        assertEquals(4.72f, v, 0.01f)
        assertTrue(Canopy.descentRate(1.33f, 0.45f, 1.5f) > 2 * v - 0.01f)
    }

    @Test fun rocketProblemsMirrorTheLocatorsRanges() {
        val m = MotorFiles.parseEng(eng, "f")!!
        assertTrue(SimRocket().problems(m).isEmpty())
        assertFalse(SimRocket(mainMps = 1f).problems(m).isEmpty())
        assertFalse(SimRocket(dryMassG = 30_000).problems(m).isEmpty())   // cannot lift
        assertFalse(SimRocket(railAngleTenths = 201).problems(m).isEmpty())
        assertFalse(SimRocket().problems(null).isEmpty())
        assertTrue(abs(SimRocket().burnoutMassKg(m) - 1.33f) < 1e-5f)
        assertNotNull(m)
    }

    @Test fun theDefaultRocketIsTheTronOrk() {
        val m = DefaultRocket.motor
        // OpenRocket: 164.1 N s, 1.85 s burn, 0.19 kg loaded with 0.084 kg propellant.
        assertTrue("impulse ${m.impulseNs}", abs(m.impulseNs - 164.1f) < 1.7f)
        assertEquals(1.85f, m.burnS, 1e-4f)
        assertEquals('H', m.impulseClass)
        assertTrue(DefaultRocket.rocket.problems(m).toString(), DefaultRocket.rocket.problems(m).isEmpty())
        // 691 g at liftoff, 607 g at burnout, as the .ork's simulation has it.
        assertEquals(0.607f, DefaultRocket.rocket.burnoutMassKg(m), 1e-4f)
        // The 0.61 m, Cd 0.75 chute at that mass: about 6.7 m/s.
        assertEquals(6.7f, Canopy.descentRate(0.607f, 0.61f, 0.75f), 0.1f)
    }
}
