package com.steampigeon.flightmanager.data

import com.steampigeon.flightmanager.data.simflight.SimMotor
import com.steampigeon.flightmanager.data.simflight.SimRocket
import com.steampigeon.flightmanager.data.simflight.Thrust

/**
 * The simulated-flight commands (locator ADR "An app-started simulated flight
 * computed on the locator", 0041, amended 2026-11-03).
 *
 * - **SimFlightStart** (MsgType 31, 199 B): the whole rocket in one frame.  The
 *   firmware struct is `SimFlightStart` in MessageProtocol.hpp, pinned there by
 *   static_assert and here by WireLayoutTest and SimFlightWireTest.
 * - **SimFlightRequest** (MsgType 30, 30 B): since the amendment, only cancels.
 *
 * Payloads are everything after the target id, which BluetoothService's
 * sendMessage prepends: the offsets below are the frame's, so 10 is the first
 * payload byte.  The locator rejects a start with any field out of range, so the
 * screen checks [SimRocket.problems] first.
 *
 * Pure Kotlin, JVM-tested.
 */
object SimFlightWire {
    const val ACTION_CANCEL = 0

    /** temp_c meaning "use the locator's barometer temperature" (kSimTempFromBaro). */
    const val TEMP_FROM_BARO = -128

    /** SimFlightRequest bytes after the target id: frame offsets 10..29. */
    const val CANCEL_PAYLOAD_SIZE = 20
    const val OFFSET_CANCEL_ACTION = 24

    /** SimFlightStart bytes after the target id: frame offsets 10..198. */
    const val START_PAYLOAD_SIZE = 189

    // SimFlightStart frame offsets, from the C++ struct.
    const val OFFSET_ORIGIN_LAT = 10
    const val OFFSET_ORIGIN_LON = 14
    const val OFFSET_SEED = 18
    const val OFFSET_WIND_FROM_DEG = 20
    const val OFFSET_LAUNCH_AZ_DEG = 22
    const val OFFSET_RAIL_LEN_CM = 24
    const val OFFSET_RAIL_ANGLE_DDEG = 26
    const val OFFSET_WIND_MPS = 27
    const val OFFSET_DROGUE_MPS = 28
    const val OFFSET_MAIN_DMPS = 29
    const val OFFSET_TEMP_C = 30
    const val OFFSET_N_POINTS = 31
    const val OFFSET_PEAK_THRUST_N = 32
    const val OFFSET_MOTOR_LOADED_KG = 36
    const val OFFSET_MOTOR_PROP_KG = 40
    const val OFFSET_DRY_MASS_KG = 44
    const val OFFSET_REF_DIAMETER_M = 48
    const val OFFSET_CD_MILLI = 52
    const val OFFSET_T_MS = 68
    const val OFFSET_THRUST_FRAC = 132
    // Added 2026-11-03 for the restoring-moment weathercock model.
    const val OFFSET_LENGTH_MM = 196
    const val OFFSET_STABILITY_DCAL = 198

    /**
     * A start.  [originLat]/[originLon] are the phone's position in degrees, used
     * by the locator only when it has no fix of its own; null sends "none".
     */
    fun start(r: SimRocket, motor: SimMotor, seed: Int, originLat: Double?, originLon: Double?): ByteArray {
        require(r.problems(motor).isEmpty()) { "rocket out of range: ${r.problems(motor)}" }
        val curve = Thrust.thin(motor.curve)
        val peak = curve.maxOf { it.second }
        val b = ByteArray(START_PAYLOAD_SIZE)
        fun at(frameOffset: Int) = frameOffset - 10
        put32(b, at(OFFSET_ORIGIN_LAT), originLat?.let { Math.round(it * 1e7).toInt() } ?: 0)
        put32(b, at(OFFSET_ORIGIN_LON), originLon?.let { Math.round(it * 1e7).toInt() } ?: 0)
        put16(b, at(OFFSET_SEED), seed and 0xFFFF)
        put16(b, at(OFFSET_WIND_FROM_DEG), r.windFromDeg)
        put16(b, at(OFFSET_LAUNCH_AZ_DEG), r.launchAzDeg)
        put16(b, at(OFFSET_RAIL_LEN_CM), r.railLengthCm)
        b[at(OFFSET_RAIL_ANGLE_DDEG)] = r.railAngleTenths.toByte()
        b[at(OFFSET_WIND_MPS)] = r.windMps.toByte()
        b[at(OFFSET_DROGUE_MPS)] = r.drogueMps.toByte()
        b[at(OFFSET_MAIN_DMPS)] = (r.mainMps * 10).toByte()
        b[at(OFFSET_TEMP_C)] = (r.tempC ?: TEMP_FROM_BARO).toByte()
        b[at(OFFSET_N_POINTS)] = curve.size.toByte()
        putF(b, at(OFFSET_PEAK_THRUST_N), peak)
        putF(b, at(OFFSET_MOTOR_LOADED_KG), motor.loadedKg)
        putF(b, at(OFFSET_MOTOR_PROP_KG), motor.propKg)
        putF(b, at(OFFSET_DRY_MASS_KG), r.dryMassG / 1000f)
        putF(b, at(OFFSET_REF_DIAMETER_M), r.diameterMm / 1000f)
        put16(b, at(OFFSET_LENGTH_MM), r.lengthMm)
        b[at(OFFSET_STABILITY_DCAL)] = Math.round(r.stabilityCal * 10f).toByte()
        r.cd.forEachIndexed { i, cd -> put16(b, at(OFFSET_CD_MILLI) + 2 * i, Math.round(cd * 1000f)) }
        curve.forEachIndexed { i, (t, f) ->
            put16(b, at(OFFSET_T_MS) + 2 * i, Math.round(t * 1000f))
            put16(b, at(OFFSET_THRUST_FRAC) + 2 * i, Math.round(f / peak * 65535f).coerceIn(0, 65535))
        }
        return b
    }

    /** A cancel: never refused by the locator. */
    fun cancel(): ByteArray =
        ByteArray(CANCEL_PAYLOAD_SIZE).also { it[OFFSET_CANCEL_ACTION - 10] = ACTION_CANCEL.toByte() }

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
    }
    private fun put32(b: ByteArray, o: Int, v: Int) {
        for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte()
    }
    private fun putF(b: ByteArray, o: Int, v: Float) = put32(b, o, java.lang.Float.floatToRawIntBits(v))
}
