package com.steampigeon.flightmanager.data.simflight

/**
 * The rocket the Simulated Flight screen offers before the flyer has saved one
 * of their own: Frank's "Scratch Built Tron Short 2.6" OpenRocket design, on
 * the Cesaroni 164H90-12A it is set up for (locator ADR-0041 amendment).
 *
 * Every number comes from that .ork's first simulation, which was saved with
 * its data: 501 g without the motor, 67.4 mm reference diameter, a 1 m rail and
 * 2 m/s of wind.  Cd is OpenRocket's own over the ascent, fitted with
 * [Drag.fitOpenRocketCsv]; the flight peaks at Mach 0.52, so the points above
 * Mach 0.5 are the standard transonic rise, not OpenRocket's.  The motor curve
 * is the thrust column of that simulation, which is OpenRocket's reading of the
 * thrustcurve.org file.  OpenRocket gets 641 m at 9.8 s.
 *
 * Recovery: one 0.61 m parachute (Cd 0.75), which the .ork opens on the
 * motor's ejection charge.  The simulation opens a canopy only on a locator
 * charge, so both rates are that chute's, about 7 m/s at the 607 g burnout
 * mass: the descent is the same whichever charge the locator's settings fire.
 */
object DefaultRocket {
    const val MOTOR_ID = "file:Cesaroni_164H90-12A"

    val rocket = SimRocket(
        name = "Tron Short 2.6 (example)",
        dryMassG = 501,
        diameterMm = 67.4f,
        cd = listOf(0.661f, 0.665f, 0.691f, 0.705f, 0.774f, 1.071f, 1.002f, 0.898f),
        motorId = MOTOR_ID,
        drogueMps = 7,
        mainMps = 7,
        railLengthCm = 100,
        railAngleTenths = 0,
        launchAzDeg = 0,
        tempC = null,
        windMps = 2,
        windFromDeg = 270,
    )

    /** RASP text, so it is cached and parsed like any imported motor. */
    val MOTOR_ENG = """
; Cesaroni 164H90-12A, from the thrust column of the Tron Short 2.6 .ork
164H90-12A 29 152 12 0.084 0.19 Cesaroni
   0.010 4.8
   0.021 10.0
   0.028 154.0
   0.038 154.4
   0.048 154.7
   0.055 155.0
   0.065 131.5
   0.075 108.0
   0.085 110.1
   0.093 112.0
   0.101 113.7
   0.108 115.2
   0.115 116.7
   0.121 118.0
   0.127 119.3
   0.130 120.0
   0.135 119.9
   0.143 119.7
   0.154 119.5
   0.171 119.1
   0.196 118.5
   0.220 118.0
   0.243 117.4
   0.269 116.8
   0.305 116.0
   0.350 115.0
   0.382 114.0
   0.399 113.5
   0.414 113.0
   0.427 112.6
   0.440 112.3
   0.452 111.9
   0.465 111.5
   0.481 111.0
   0.506 110.3
   0.542 109.1
   0.562 108.5
   0.577 108.1
   0.591 107.7
   0.605 107.2
   0.623 106.7
   0.649 105.9
   0.687 104.7
   0.703 104.2
   0.719 103.8
   0.738 103.2
   0.765 102.3
   0.807 101.1
   0.830 100.4
   0.860 99.5
   0.905 98.1
   0.943 96.9
   0.993 95.4
   1.043 93.9
   1.093 92.3
   1.143 90.8
   1.193 89.3
   1.236 88.0
   1.286 86.5
   1.336 85.0
   1.386 83.4
   1.400 83.0
   1.422 83.3
   1.454 83.7
   1.480 84.0
   1.519 78.6
   1.560 73.0
   1.610 53.4
   1.660 33.8
   1.690 22.0
   1.735 13.0
   1.760 8.0
   1.798 4.7
   1.850 0.0
""".trimIndent()

    val motor: SimMotor get() = MotorFiles.parseEng(MOTOR_ENG, MOTOR_ID)!!
}
