package com.steampigeon.flightmanager.data

/**
 * The profile of an app-started simulated flight, and its SimFlightRequest
 * payload (locator ADR "An app-started simulated flight computed on the
 * locator", 0041; the firmware struct is `SimFlightRequest` in
 * MessageProtocol.hpp, pinned there by static_assert and here by
 * WireLayoutTest and SimFlightWireTest).
 *
 * The payload is everything after the target id, which BluetoothService's
 * sendMessage prepends: offsets below are the frame's, so 10 is the first
 * payload byte.  Ranges are the ADR's; the locator refuses a request with any
 * field outside them, so the screen refuses it first.
 *
 * Pure Kotlin, JVM-tested.
 */
data class SimFlightProfile(
    val apogeeM: Int = 300,
    val burnTenths: Int = 15,
    val drogueMps: Int = 20,
    val mainMps: Int = 6,
    val windMps: Int = 3,
    val windFromDeg: Int = 270,
) {
    val valid: Boolean
        get() = apogeeM in APOGEE_M && burnTenths in BURN_TENTHS && drogueMps in DROGUE_MPS &&
            mainMps in MAIN_MPS && windMps in WIND_MPS && windFromDeg in WIND_FROM_DEG

    companion object {
        val APOGEE_M = 30..3000
        val BURN_TENTHS = 3..50
        val DROGUE_MPS = 5..60
        val MAIN_MPS = 2..15
        val WIND_MPS = 0..20
        val WIND_FROM_DEG = 0..359
    }
}

object SimFlightWire {
    const val ACTION_CANCEL = 0
    const val ACTION_START = 1

    /** Bytes after the target id: frame offsets 10..29. */
    const val PAYLOAD_SIZE = 20

    // Frame offsets, from the C++ struct.
    const val OFFSET_APOGEE_M = 10
    const val OFFSET_ORIGIN_LAT = 12
    const val OFFSET_ORIGIN_LON = 16
    const val OFFSET_WIND_FROM_DEG = 20
    const val OFFSET_SEED = 22
    const val OFFSET_ACTION = 24
    const val OFFSET_BURN_DS = 25
    const val OFFSET_DROGUE_MPS = 26
    const val OFFSET_MAIN_DMPS = 27
    const val OFFSET_WIND_MPS = 28

    /**
     * A start.  [originLat]/[originLon] are the phone's position in degrees, used
     * by the locator only when it has no fix of its own; null sends "none".
     */
    fun start(p: SimFlightProfile, seed: Int, originLat: Double?, originLon: Double?): ByteArray {
        require(p.valid) { "profile out of range: $p" }
        val b = ByteArray(PAYLOAD_SIZE)
        fun at(frameOffset: Int) = frameOffset - 10
        put16(b, at(OFFSET_APOGEE_M), p.apogeeM)
        put32(b, at(OFFSET_ORIGIN_LAT), originLat?.let { Math.round(it * 1e7).toInt() } ?: 0)
        put32(b, at(OFFSET_ORIGIN_LON), originLon?.let { Math.round(it * 1e7).toInt() } ?: 0)
        put16(b, at(OFFSET_WIND_FROM_DEG), p.windFromDeg)
        put16(b, at(OFFSET_SEED), seed and 0xFFFF)
        b[at(OFFSET_ACTION)] = ACTION_START.toByte()
        b[at(OFFSET_BURN_DS)] = p.burnTenths.toByte()
        b[at(OFFSET_DROGUE_MPS)] = p.drogueMps.toByte()
        b[at(OFFSET_MAIN_DMPS)] = (p.mainMps * 10).toByte()
        b[at(OFFSET_WIND_MPS)] = p.windMps.toByte()
        return b
    }

    /** A cancel: never refused by the locator. */
    fun cancel(): ByteArray = ByteArray(PAYLOAD_SIZE).also { it[OFFSET_ACTION - 10] = ACTION_CANCEL.toByte() }

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
    }
    private fun put32(b: ByteArray, o: Int, v: Int) {
        for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte()
    }
}
