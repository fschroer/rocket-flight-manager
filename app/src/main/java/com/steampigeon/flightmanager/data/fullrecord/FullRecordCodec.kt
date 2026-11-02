package com.steampigeon.flightmanager.data.fullrecord

import java.util.zip.CRC32

/**
 * Decoder for the lossless full-record codec (locator `FullRecordCodec.hpp`,
 * ADR "The radio download becomes the primary data path", decision 2).
 *
 * A bit-exact port: every 94 B `FlightSample` decodes to the same bytes it has in
 * the locator's flash, so the record is kept as raw bytes, never as floats.  The
 * field table, the variable-length code and the state rules must match the C++
 * exactly; `FullRecordCodecTest` checks them against packets the real C++ encoder
 * produced.
 *
 * Pure Kotlin, no android.* imports, so the JVM unit tests run the real code.
 */
object FullRecordCodec {
    /** sizeof(FlightArchive::FlightSample), ARCHIVE_VERSION 8. */
    const val SAMPLE_SIZE = 94
    const val SAMPLES_PER_PAGE = 512
    const val MAX_PACKETS_PER_PAGE = 256

    private enum class Kind(val width: Int) { Timestamp(4), D32(4), D16(2), Byte(1) }
    private class Field(val offset: Int, val kind: Kind)

    // Every byte of FlightSample, in layout order.  Mirrors kFields in the C++.
    private val FIELDS: Array<Field> = run {
        val k = mutableListOf(Field(0, Kind.Timestamp))
        for (o in 4..48 step 4) k += Field(o, Kind.D32)          // AGL, vspeed, accel, gyro, lat, lon
        for (o in 52..74 step 2) k += Field(o, Kind.D16)         // gps_vel, h_acc, tilt, quat, accel_alt
        for (o in 76..82) k += Field(o, Kind.Byte)               // status bytes
        k += Field(83, Kind.D16)                                 // s_acc
        k += Field(85, Kind.D16)                                 // raw pressure
        k += Field(87, Kind.Byte)                                // die temperature
        for (o in 88..92 step 2) k += Field(o, Kind.D16)         // mag
        k.toTypedArray()
    }

    init {
        var at = 0
        for (f in FIELDS) {
            check(f.offset == at) { "FullRecordCodec field table is not contiguous at $at" }
            at += f.kind.width
        }
        check(at == SAMPLE_SIZE) { "FullRecordCodec field table covers $at bytes, not $SAMPLE_SIZE" }
    }

    /** What the decoder needs about the samples before the next one. */
    class State(
        val prev: ByteArray = ByteArray(SAMPLE_SIZE),   // all zeros before sample 0
        var prevDt: Int = 0,                            // uint32 arithmetic in an Int
    )

    /**
     * The state before a sample, from the two samples preceding it (either may be
     * null at the start of the record).  Matches the C++ `StateBefore`.
     */
    fun stateBefore(before2: ByteArray?, before1: ByteArray?): State {
        val st = State()
        if (before1 != null) {
            before1.copyInto(st.prev, 0, 0, SAMPLE_SIZE)
            st.prevDt = u32(before1, 0) - (if (before2 != null) u32(before2, 0) else 0)
        }
        return st
    }

    /** [stateBefore] for sample [index] of a record held as raw bytes in [record]. */
    fun stateAt(record: ByteArray, index: Int): State {
        val b1 = if (index >= 1) record.copyOfRange((index - 1) * SAMPLE_SIZE, index * SAMPLE_SIZE) else null
        val b2 = if (index >= 2) record.copyOfRange((index - 2) * SAMPLE_SIZE, (index - 1) * SAMPLE_SIZE) else null
        return stateBefore(b2, b1)
    }

    /**
     * Decode [count] samples from [payload] (its first [bytes] bytes) after [st],
     * writing them to [out] at [outOffset].  Returns how many decoded: fewer only
     * when the payload is truncated or corrupt.  [st] is advanced past each one.
     */
    fun decode(st: State, payload: ByteArray, bytes: Int, count: Int, out: ByteArray, outOffset: Int): Int {
        val r = BitReader(payload, bytes * 8)
        val s = ByteArray(SAMPLE_SIZE)
        var done = 0
        while (done < count) {
            for (f in FIELDS) {
                if (!r.ok) break
                val was = load(st.prev, f)
                when (f.kind) {
                    Kind.Timestamp -> store(s, f, was + st.prevDt + unZigZag(r.varCode()))
                    Kind.D32 -> store(s, f, was + unZigZag(r.varCode()))
                    Kind.D16 -> store(s, f, (was + unZigZag(r.varCode())) and 0xFFFF)
                    Kind.Byte -> store(s, f, if (r.get(1) != 0) r.get(8) else was)
                }
            }
            if (!r.ok) break
            s.copyInto(out, outOffset + done * SAMPLE_SIZE)
            st.prevDt = u32(s, 0) - u32(st.prev, 0)
            s.copyInto(st.prev)
            done++
        }
        return done
    }

    /** Page p is samples [p * 512, (p + 1) * 512); 0 when past the end. */
    fun pageLength(totalSamples: Int, page: Int): Int {
        val first = page.toLong() * SAMPLES_PER_PAGE
        if (first >= totalSamples) return 0
        return minOf(SAMPLES_PER_PAGE.toLong(), totalSamples - first).toInt()
    }

    fun pageCount(totalSamples: Int): Int = (totalSamples + SAMPLES_PER_PAGE - 1) / SAMPLES_PER_PAGE

    /** IEEE CRC-32 over the record's raw sample bytes, as the locator computes it. */
    fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Long {
        val c = CRC32()
        c.update(data, offset, length)
        return c.value
    }

    // ---- bits --------------------------------------------------------------

    private fun unZigZag(z: Int): Int = (z ushr 1) xor -(z and 1)

    private class BitReader(val buf: ByteArray, val lenBits: Int) {
        var pos = 0
        var ok = true
        fun get(n: Int): Int {
            var v = 0
            for (i in 0 until n) {
                if (pos >= lenBits) { ok = false; return 0 }
                v = (v shl 1) or ((buf[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
        fun varCode(): Int {
            if (get(1) == 0) return 0
            val len = get(5) + 1
            val low = if (len > 1) get(len - 1) else 0
            return (1 shl (len - 1)) or low       // len 32: 1 shl 31 is 0x80000000
        }
    }

    private fun load(s: ByteArray, f: Field): Int = when (f.kind) {
        Kind.Byte -> s[f.offset].toInt() and 0xFF
        Kind.D16 -> (s[f.offset].toInt() and 0xFF) or ((s[f.offset + 1].toInt() and 0xFF) shl 8)
        else -> u32(s, f.offset)
    }

    private fun store(s: ByteArray, f: Field, v: Int) {
        s[f.offset] = v.toByte()
        if (f.kind.width >= 2) s[f.offset + 1] = (v ushr 8).toByte()
        if (f.kind.width == 4) {
            s[f.offset + 2] = (v ushr 16).toByte()
            s[f.offset + 3] = (v ushr 24).toByte()
        }
    }

    internal fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
