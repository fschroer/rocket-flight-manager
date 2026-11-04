package com.steampigeon.flightmanager.data.fullrecord

/**
 * The full-record transfer over the locator's USB cable (locator ADR "The radio
 * download becomes the primary data path", decision 6c; the locator side is
 * `SerialLink.hpp`, read its header comment first).
 *
 * Over the cable the app talks to the locator itself, not to a receiver, so the
 * frames are the locator's own: the same FullRecordRequest / FullRecordAck going
 * up and FullRecordData / FullRecordParity / PreLaunchData / TelemetryData coming
 * down, byte for byte, with the frame CRC, but WITHOUT the metadata a receiver
 * appends to the periodic frames (channel, name, RSSI and the rest).  That is why
 * the cable has its own [Framer] and [periodic] reader rather than BluetoothService's.
 *
 * The app holds the console in binary mode by sending [HELLO] at least once a
 * second, and the locator answers each with its periodic frame; it falls back to
 * its text console 3 s after the last hello or frame.
 *
 * Pure Kotlin, JVM-tested in `app/src/test/.../fullrecord/SerialFramesTest.kt`.
 */
object SerialFrames {
    /** Not ASCII, so no terminal produces it: what puts the console in binary mode. */
    const val HELLO: Byte = 0xA5.toByte()
    const val HELLO_INTERVAL_MS = 1_000L
    /** The locator leaves binary mode after this long with no hello or frame. */
    const val LOCATOR_HOLD_MS = 3_000L

    const val SYSTEM_ID: Byte = 0x44
    const val HEADER_SIZE = 6
    // The locator's own sizes (C++ sizeof), receiver metadata not included.
    const val PRELAUNCH_SIZE = 118
    const val TELEMETRY_SIZE = 77
    const val REQUEST_SIZE = 14
    const val ACK_SIZE = 50

    const val MSG_PRELAUNCH = 5
    const val MSG_TELEMETRY = 6
    const val MSG_FULL_RECORD_REQUEST = 26
    const val MSG_FULL_RECORD_DATA = 27
    const val MSG_FULL_RECORD_PARITY = 28
    const val MSG_FULL_RECORD_ACK = 29

    // Offsets of the fields the transfer needs, from the C++ structs.
    private const val PRELAUNCH_ARMED = 108
    private const val PRELAUNCH_LOCATOR_ID = 110
    private const val TELEMETRY_PHYSICAL_STATS = 34
    private const val TELEMETRY_FLIGHT_STATE = 67
    private const val TELEMETRY_ARMED = 68
    private const val TELEMETRY_LOCATOR_ID = 69
    private const val TELEMETRY_PICKED_UP_BIT = 0x80
    private const val OFFSET_PAYLOAD_LEN = 23           // FullRecordPacketHeader::payload_len

    /** What a periodic frame tells the transfer. */
    data class Periodic(val locatorId: Long, val armed: Boolean, val flightState: Int, val pickedUp: Boolean)

    fun periodic(f: ByteArray): Periodic? = when {
        f.size == PRELAUNCH_SIZE && type(f) == MSG_PRELAUNCH -> Periodic(
            u32(f, PRELAUNCH_LOCATOR_ID), f[PRELAUNCH_ARMED].toInt() != 0,
            FullRecordTransfer.FLIGHT_STATE_WAITING_LAUNCH, pickedUp = false)
        f.size == TELEMETRY_SIZE && type(f) == MSG_TELEMETRY -> Periodic(
            u32(f, TELEMETRY_LOCATOR_ID), f[TELEMETRY_ARMED].toInt() != 0,
            f[TELEMETRY_FLIGHT_STATE].toInt() and 0xFF,
            pickedUp = (f[TELEMETRY_PHYSICAL_STATS].toInt() and TELEMETRY_PICKED_UP_BIT) != 0)
        else -> null
    }

    fun isFullRecord(f: ByteArray) = f.size > 1 && (type(f) == MSG_FULL_RECORD_DATA || type(f) == MSG_FULL_RECORD_PARITY)

    fun request(locatorId: Long, record: Int, page: Int): ByteArray {
        val f = ByteArray(REQUEST_SIZE)
        header(f, MSG_FULL_RECORD_REQUEST)
        putU32(f, 6, locatorId)
        f[10] = page.toByte(); f[11] = (page shr 8).toByte()
        f[12] = record.toByte()
        return seal(f)
    }

    fun ack(locatorId: Long, recordCrc: Long, page: Int, packetCount: Int, bitmap: ByteArray): ByteArray {
        require(bitmap.size == 32)
        val f = ByteArray(ACK_SIZE)
        header(f, MSG_FULL_RECORD_ACK)
        putU32(f, 6, locatorId)
        putU32(f, 10, recordCrc)
        f[14] = page.toByte(); f[15] = (page shr 8).toByte()
        f[16] = packetCount.toByte(); f[17] = (packetCount shr 8).toByte()
        bitmap.copyInto(f, 18)
        return seal(f)
    }

    /**
     * The frame CRC (CRC-16/0xA001, seed 0xFFFF) over every byte but the CRC
     * field itself, as the locator's ComputeMessageCrcPartial computes it.
     */
    fun crc(f: ByteArray, len: Int = f.size): Int {
        var crc = 0xFFFF
        fun upd(b: Byte) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        for (i in 0 until 4) upd(f[i])
        for (i in 6 until len) upd(f[i])
        return crc and 0xFFFF
    }

    fun crcOk(f: ByteArray, len: Int = f.size): Boolean =
        len >= HEADER_SIZE && ((f[4].toInt() and 0xFF) or ((f[5].toInt() and 0xFF) shl 8)) == crc(f, len)

    /**
     * Cuts the cable's byte stream into frames.  A frame starts with the system
     * id; its length follows from its type (the 25-byte header's payload_len
     * for FullRecordData, the fixed 255 B for parity), and it counts only if its
     * CRC holds.  Anything else, console text included, is skipped a byte at a
     * time, which is how the stream finds the next frame after noise.
     */
    class Framer {
        private var buf = ByteArray(4096)
        private var n = 0

        fun reset() { n = 0 }

        fun feed(bytes: ByteArray, len: Int = bytes.size): List<ByteArray> {
            if (n + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + len))
            bytes.copyInto(buf, n, 0, len)
            n += len
            val out = ArrayList<ByteArray>()
            var at = 0
            while (n - at >= 2) {
                if (buf[at] != SYSTEM_ID) { at++; continue }
                val want = expected(at)
                if (want < 0) { at++; continue }
                if (want == 0 || n - at < want) break       // wait for more
                val f = buf.copyOfRange(at, at + want)
                if (crcOk(f)) { out += f; at += want } else at++
            }
            buf.copyInto(buf, 0, at, n)
            n -= at
            return out
        }

        // -1: not a frame start; 0: not enough bytes yet to know its length.
        private fun expected(at: Int): Int = when (buf[at + 1].toInt() and 0xFF) {
            MSG_PRELAUNCH -> PRELAUNCH_SIZE
            MSG_TELEMETRY -> TELEMETRY_SIZE
            MSG_FULL_RECORD_PARITY -> FullRecordPacket.HEADER_SIZE + FullRecordPacket.PAYLOAD_CAPACITY
            MSG_FULL_RECORD_DATA ->
                if (n - at <= OFFSET_PAYLOAD_LEN) 0
                else (FullRecordPacket.HEADER_SIZE + (buf[at + OFFSET_PAYLOAD_LEN].toInt() and 0xFF))
                    .takeIf { it <= FullRecordPacket.HEADER_SIZE + FullRecordPacket.PAYLOAD_CAPACITY } ?: -1
            else -> -1
        }
    }

    private fun type(f: ByteArray) = f[1].toInt() and 0xFF
    private fun header(f: ByteArray, type: Int) { f[0] = SYSTEM_ID; f[1] = type.toByte() }
    private fun seal(f: ByteArray): ByteArray {
        val c = crc(f)
        f[4] = c.toByte(); f[5] = (c shr 8).toByte()
        return f
    }
    private fun u32(b: ByteArray, o: Int): Long = FullRecordCodec.u32(b, o).toLong() and 0xFFFFFFFFL
    private fun putU32(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte()
    }
}
