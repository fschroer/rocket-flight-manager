package com.steampigeon.flightmanager.data.fullrecord

import com.steampigeon.flightmanager.data.fullrecord.FullRecordCodec.SAMPLE_SIZE
import java.io.Writer

/**
 * The CSV the locator's USB export prints (`UserInteraction::ExportData`), built
 * from the raw v8 bytes so the uploaded .csv matches it character for character
 * (ADR "The radio download becomes the primary data path", decision 8).
 *
 * The number formatting is a port of the locator's `FormatFloat`, in Float
 * arithmetic, not String.format: the locator converts every value to float and
 * rounds with its own add-a-half, and a "better" formatter would print different
 * digits for the same record.  One consequence carried over on purpose: a NaN
 * (fixed-point INT32_MIN) prints as 0.00, as it does on the locator.
 *
 * Lat/lon are the exception: both sides print them from the stored integer
 * (degrees x 1e-7) with no float in between (the locator's FormatDeg1e7), so
 * all 7 decimals are exact.  Until 2026-11-02 the export passed them through
 * float and kept only ~0.4 m.
 */
object FullRecordCsv {
    const val HEADER = "time_ms,raw_baro_agl_m,fused_agl_m,raw_baro_vel_mps,fused_vspeed_mps," +
        "accel_x_g,accel_y_g,accel_z_g,gyro_x_dps,gyro_y_dps,gyro_z_dps,lat_deg,lon_deg," +
        "flight_state,armed,ekf_health,gps_vel_n_mps,gps_vel_e_mps,gps_vel_d_mps,gps_h_acc_m," +
        "tilt_deg,q_w,q_x,q_y,q_z,fix_type,num_sv,accel_alt_x_g,accel_alt_y_g,accel_alt_z_g," +
        "accel_source,pps_status,gps_s_acc_mps,baro_raw_pressure_pa,baro_temp_c,mag_x_ut,mag_y_ut,mag_z_ut"
    private const val CRLF = "\r\n"
    private const val FIXED_INVALID = Int.MIN_VALUE
    private const val I16_INVALID = -32768

    fun write(record: ByteArray, samples: Int, out: Writer) {
        out.write(HEADER); out.write(CRLF)
        val sb = StringBuilder(320)
        for (i in 0 until samples) {
            sb.setLength(0)
            row(record, i * SAMPLE_SIZE, sb)
            out.write(sb.toString())
        }
        out.flush()
    }

    fun row(b: ByteArray, o: Int, sb: StringBuilder) {
        sb.append(u32(b, o)).append(',')
        fixed(sb, i32(b, o + 4), 100f, 2); sb.append(',')     // raw_baro_agl_cm
        fixed(sb, i32(b, o + 8), 100f, 2); sb.append(',')     // fused_agl_cm
        fixed(sb, i32(b, o + 12), 100f, 2); sb.append(',')    // raw_baro_vel_cms
        fixed(sb, i32(b, o + 16), 100f, 2); sb.append(',')    // fused_vspeed_cms
        for (a in 0 until 3) { fixed(sb, i32(b, o + 20 + 4 * a), 1000f, 3); sb.append(',') }   // accel_mg
        for (a in 0 until 3) { fixed(sb, i32(b, o + 32 + 4 * a), 100f, 2); sb.append(',') }    // gyro_cdps
        deg1e7(sb, i32(b, o + 44)); sb.append(',')
        deg1e7(sb, i32(b, o + 48)); sb.append(',')
        sb.append(u8(b, o + 76)).append(',')                  // flight_state
        sb.append(u8(b, o + 77)).append(',')                  // armed
        sb.append(u8(b, o + 78)).append(',')                  // ekf_health
        for (a in 0 until 3) {                                // gps_vel, dm/s; blank = no velocity
            val dms = i16(b, o + 52 + 2 * a)
            if (dms != I16_INVALID) fmt(sb, dms / 10.0f, 1)
            sb.append(',')
        }
        fmt(sb, u16(b, o + 58) / 100.0f, 2); sb.append(',')   // h_acc
        fmt(sb, i16(b, o + 60) / 100.0f, 2); sb.append(',')   // tilt
        for (a in 0 until 4) { fmt(sb, i16(b, o + 62 + 2 * a) / 32767.0f, 4); sb.append(',') }
        sb.append(u8(b, o + 79)).append(',')                  // fix type
        sb.append(u8(b, o + 80)).append(',')                  // num sv
        for (a in 0 until 3) {                                // accel_alt, 0.01 g; blank = invalid
            val cg = i16(b, o + 70 + 2 * a)
            if (cg != I16_INVALID) fmt(sb, cg / 100.0f, 2)
            sb.append(',')
        }
        sb.append(u8(b, o + 81)).append(',')                  // accel source
        sb.append(u8(b, o + 82)).append(',')                  // pps status
        val sAcc = u16(b, o + 83)
        if (sAcc != 0) fmt(sb, sAcc / 100.0f, 2)
        sb.append(',')
        val p2 = u16(b, o + 85)
        if (p2 != 0) {
            sb.append(20000L + 2L * p2).append(',')
            fmt(sb, b[o + 87].toInt() / 2.0f, 1)              // int8, signed
        } else {
            sb.append(',')
        }
        for (a in 0 until 3) {
            sb.append(',')
            val q = i16(b, o + 88 + 2 * a)
            if (q != I16_INVALID) fmt(sb, q * (100.0f / 4096.0f), 2)
        }
        sb.append(CRLF)
    }

    private fun fixed(sb: StringBuilder, q: Int, scale: Float, frac: Int) =
        fmt(sb, if (q == FIXED_INVALID) Float.NaN else q.toFloat() / scale, frac)

    /** The locator's FormatDeg1e7: degrees x 1e-7 as exact decimal degrees. */
    internal fun deg1e7(sb: StringBuilder, v: Int) {
        val m = if (v < 0) { sb.append('-'); -(v.toLong()) } else v.toLong()
        sb.append(m / 10_000_000L).append('.')
        val frac = (m % 10_000_000L).toString()
        repeat(7 - frac.length) { sb.append('0') }
        sb.append(frac)
    }

    /** The locator's FormatFloat, in the same float arithmetic. */
    internal fun fmt(sb: StringBuilder, v0: Float, frac: Int) {
        var v = v0
        if (v < 0.0f) { sb.append('-'); v = -v }
        var ip = f2ui(v)
        var scale = 1.0f
        repeat(frac) { scale *= 10.0f }
        var fp = f2ui((v - ip.toFloat()) * scale + 0.5f)
        if (fp >= f2ui(scale)) { fp = 0; ip += 1 }
        sb.append(ip and 0xFFFFFFFFL)
        if (frac > 0) {
            sb.append('.')
            var pow10 = 1L
            repeat(frac - 1) { pow10 *= 10 }
            repeat(frac) {
                val digit = fp / pow10
                sb.append(('0'.code + digit.toInt()).toChar())
                fp -= digit * pow10
                pow10 /= 10
            }
        }
    }

    // __aeabi_f2uiz: truncate; NaN and negatives give 0, overflow saturates.
    private fun f2ui(v: Float): Long = when {
        v.isNaN() || v <= 0f -> 0L
        v >= 4294967296.0f -> 0xFFFFFFFFL
        else -> v.toLong()
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun i16(b: ByteArray, o: Int) = u16(b, o).toShort().toInt()
    private fun i32(b: ByteArray, o: Int) = FullRecordCodec.u32(b, o)
    private fun u32(b: ByteArray, o: Int) = i32(b, o).toLong() and 0xFFFFFFFFL
}
