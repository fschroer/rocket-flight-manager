package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.FLIGHT_DATA_PARITY_SIZE
import com.steampigeon.flightmanager.data.FlightDataRepository
import com.steampigeon.flightmanager.data.Protocol
import com.steampigeon.flightmanager.data.SAMPLES_PER_PACKET
import com.steampigeon.flightmanager.data.compressedPayloadBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Resuming an interrupted record download, and telling a truncated transfer
 * from a complete one (#49; ADR-0009 amendment 2026-09-28, invariants 12–13).
 *
 * A resume keeps every packet already held and ACKs them under the locator's
 * new transfer id, so the locator skips them.  The danger it must not create
 * is SPLICING: two different flights in one archive slot can share packet_count
 * and total_samples (the locator plans every long record to ~2,048 samples),
 * so geometry alone never confirms a resume — the first overlapping packet
 * must be byte-identical, or the record's flight timestamp must match.
 */
class FlightDataResumeTest {

    private companion object {
        const val RECORD = 3
        const val LOCATOR = 0x1234_5678L
        const val ID_A = 0x0101
        const val ID_B = 0x0102
        const val PACKETS = 4
        val TOTAL = PACKETS.toLong() * SAMPLES_PER_PACKET

        const val DATA_HEADER = Protocol.HEADER_SIZE + 2 + 2 + 2 + 4          // 16
        const val PAYLOAD_CAPACITY = FLIGHT_DATA_PARITY_SIZE - DATA_HEADER    // 239
        const val COMPRESSED_HEADER = 48
        const val COMPRESSED_DELTA = 24
        const val ACK_BITMAP_OFFSET = Protocol.HEADER_SIZE + 2 + 2            // 10
    }

    @Before
    fun reset() = FlightDataRepository.beginTransfer()

    @Test
    fun aResumedTransferKeepsHeldPacketsAndAcksThemAtOnce() {
        val flight = (0 until PACKETS).map { payload(it, seed = 1) }
        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        FlightDataRepository.onFlightData(dataFrame(ID_A, 0, flight[0]))
        FlightDataRepository.onFlightData(dataFrame(ID_A, 1, flight[1]))

        // Interrupted; the same record is requested again and the locator
        // starts over under a new id — beginning, as always, with packet 0.
        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        val ack = FlightDataRepository.onFlightData(dataFrame(ID_B, 0, flight[0]))

        assertNotNull(ack)
        assertEquals(ID_B, u16(ack!!, Protocol.HEADER_SIZE))
        // Both held packets are acknowledged in the very first ACK, so the
        // locator skips packet 1 instead of sending it again.
        assertTrue(bit(ack, 0)); assertTrue(bit(ack, 1))
        assertFalse(bit(ack, 2)); assertFalse(bit(ack, 3))
        assertTrue(FlightDataRepository.progress.value.resumed)
        assertEquals(2 * SAMPLES_PER_PACKET, FlightDataRepository.samples.value.size)

        FlightDataRepository.onFlightData(dataFrame(ID_B, 2, flight[2]))
        FlightDataRepository.onFlightData(dataFrame(ID_B, 3, flight[3]))
        assertTrue(FlightDataRepository.progress.value.complete)
        assertEquals(PACKETS * SAMPLES_PER_PACKET, FlightDataRepository.samples.value.size)
    }

    @Test
    fun aDifferentFlightWithTheSameGeometryIsNotSpliced() {
        val oldFlight = (0 until PACKETS).map { payload(it, seed = 1) }
        val newFlight = (0 until PACKETS).map { payload(it, seed = 7) }
        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        FlightDataRepository.onFlightData(dataFrame(ID_A, 0, oldFlight[0]))
        FlightDataRepository.onFlightData(dataFrame(ID_A, 1, oldFlight[1]))

        // The slot was re-flown: same packet_count and total_samples, other data.
        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        val ack = FlightDataRepository.onFlightData(dataFrame(ID_B, 0, newFlight[0]))

        assertNotNull(ack)
        assertTrue(bit(ack!!, 0))
        assertFalse("old packet 1 must not survive into the new flight", bit(ack, 1))
        assertFalse(FlightDataRepository.progress.value.resumed)
        assertEquals(SAMPLES_PER_PACKET, FlightDataRepository.samples.value.size)
    }

    @Test
    fun anUnheldFirstPacketResumesOnlyOnAMatchingFlightTimestamp() {
        val flight = (0 until PACKETS).map { payload(it, seed = 1) }
        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        FlightDataRepository.onFlightEvents(RECORD, 1_790_000_000L)
        FlightDataRepository.onFlightData(dataFrame(ID_A, 1, flight[1]))   // packet 0 was lost

        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        FlightDataRepository.onFlightEvents(RECORD, 1_790_000_000L)
        val ack = FlightDataRepository.onFlightData(dataFrame(ID_B, 0, flight[0]))

        assertTrue(bit(ack!!, 0)); assertTrue(bit(ack, 1))
        assertTrue(FlightDataRepository.progress.value.resumed)
    }

    @Test
    fun anUnheldFirstPacketWithAnotherFlightTimestampStartsOver() {
        val flight = (0 until PACKETS).map { payload(it, seed = 1) }
        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        FlightDataRepository.onFlightEvents(RECORD, 1_790_000_000L)
        FlightDataRepository.onFlightData(dataFrame(ID_A, 1, flight[1]))

        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        FlightDataRepository.onFlightEvents(RECORD, 1_790_086_400L)          // re-flown next day
        val ack = FlightDataRepository.onFlightData(dataFrame(ID_B, 0, flight[0]))

        assertTrue(bit(ack!!, 0))
        assertFalse(bit(ack, 1))
        assertFalse(FlightDataRepository.progress.value.resumed)
    }

    @Test
    fun parityIsIgnoredUntilTheResumeIsConfirmed() {
        val flight = (0 until PACKETS).map { payload(it, seed = 1) }
        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        FlightDataRepository.onFlightData(dataFrame(ID_A, 0, flight[0]))

        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        // A parity frame arriving before any data packet of the new transfer
        // could rebuild a packet of the wrong flight; it must be dropped.
        assertNull(FlightDataRepository.onFlightDataParity(parityFrame(ID_B, 0, flight)))
        assertEquals(SAMPLES_PER_PACKET, FlightDataRepository.samples.value.size)
    }

    @Test
    fun anotherRecordDiscardsTheHeldOne() {
        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        FlightDataRepository.onFlightData(dataFrame(ID_A, 0, payload(0, seed = 1)))

        FlightDataRepository.requestRecord(RECORD + 1, LOCATOR)
        assertTrue(FlightDataRepository.samples.value.isEmpty())
        assertEquals(RECORD + 1, FlightDataRepository.progress.value.record)
    }

    @Test
    fun lateFramesOfARetiredTransferAreIgnored() {
        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        FlightDataRepository.onFlightData(dataFrame(ID_A, 0, payload(0, seed = 1)))
        FlightDataRepository.requestRecord(RECORD + 1, LOCATOR)
        FlightDataRepository.onFlightData(dataFrame(ID_B, 0, payload(0, seed = 9)))

        // Still in the air from the abandoned transfer: must not reset ID_B's.
        assertNull(FlightDataRepository.onFlightData(dataFrame(ID_A, 1, payload(1, seed = 1))))
        assertEquals(ID_B, FlightDataRepository.progress.value.transferId)
        assertEquals(SAMPLES_PER_PACKET, FlightDataRepository.samples.value.size)
    }

    @Test
    fun discardEndsTheDownloadAndRetiresItsTransfer() {
        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        FlightDataRepository.onFlightData(dataFrame(ID_A, 0, payload(0, seed = 1)))
        assertNotNull(FlightDataRepository.incompleteDownload())

        FlightDataRepository.discard()
        assertNull(FlightDataRepository.incompleteDownload())
        assertNull(FlightDataRepository.onFlightData(dataFrame(ID_A, 1, payload(1, seed = 1))))
    }

    @Test
    fun incompleteDownloadNamesTheRecordAndLocator() {
        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        FlightDataRepository.onFlightData(dataFrame(ID_A, 0, payload(0, seed = 1)))
        assertEquals(RECORD to LOCATOR, FlightDataRepository.incompleteDownload())
    }

    @Test
    fun oldFirmwaresClampedTransferIsFlaggedTruncated() {
        // Pre-#49 locators sent packet_count = 256 with the archive's true
        // total_samples (Red Ryder: 4,683) and let the transfer complete.
        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        FlightDataRepository.onFlightData(
            dataFrame(ID_A, 0, payload(0, seed = 1), packetCount = 256, total = 4_683L))
        assertTrue(FlightDataRepository.progress.value.truncated)
    }

    @Test
    fun aPlannedTransferIsNotTruncated() {
        FlightDataRepository.requestRecord(RECORD, LOCATOR)
        FlightDataRepository.onFlightData(dataFrame(ID_A, 0, payload(0, seed = 1)))
        assertFalse(FlightDataRepository.progress.value.truncated)
    }

    // ---------------------------------------------------------------------
    //  Frame builders (same geometry as FlightDataParityTest)
    // ---------------------------------------------------------------------

    private fun payload(packetIndex: Int, seed: Int, samples: Int = SAMPLES_PER_PACKET): ByteArray {
        val p = ByteArray(COMPRESSED_HEADER + (samples - 1) * COMPRESSED_DELTA)
        val k = packetIndex + 1
        p.putU32(0, 1_000L * k)
        p.putF32(4, 100f * k + seed)
        p.putF32(8, 0.1f * k); p.putF32(12, 0.2f * k); p.putF32(16, 0.3f * seed)
        p.putF32(20, 1.1f * k); p.putF32(24, 1.2f * k); p.putF32(28, 1.3f * seed)
        p.putF64(32, 0.8310 + k * 1e-4)
        p.putF64(40, -2.1385 + k * 1e-4)
        for (d in 0 until samples - 1) {
            val o = COMPRESSED_HEADER + d * COMPRESSED_DELTA
            p.putI16(o, 50)
            p.putI16(o + 2, 10 * k + d + seed)
            p.putI16(o + 4, k); p.putI16(o + 6, k + 1); p.putI16(o + 8, seed)
            p.putI16(o + 10, -k); p.putI16(o + 12, -k - 1); p.putI16(o + 14, -seed)
            p.putI32(o + 16, 500 * k + d)
            p.putI32(o + 20, -500 * k - d)
        }
        return p
    }

    private fun dataFrame(
        id: Int, packetIndex: Int, payload: ByteArray,
        packetCount: Int = PACKETS, total: Long = TOTAL,
    ): ByteArray {
        assertEquals(compressedPayloadBytes(packetIndex, total), payload.size)
        return header(id, packetIndex, packetCount, total, payload.size).also { payload.copyInto(it, DATA_HEADER) }
    }

    private fun parityFrame(id: Int, groupIndex: Int, members: List<ByteArray>): ByteArray {
        val acc = ByteArray(PAYLOAD_CAPACITY)
        for (m in members)
            for (i in m.indices) acc[i] = (acc[i].toInt() xor m[i].toInt()).toByte()
        return header(id, groupIndex, PACKETS, TOTAL, PAYLOAD_CAPACITY).also { acc.copyInto(it, DATA_HEADER) }
    }

    private fun header(id: Int, indexField: Int, packetCount: Int, total: Long, payloadSize: Int): ByteArray {
        val f = ByteArray(DATA_HEADER + payloadSize)
        f.putU16(Protocol.HEADER_SIZE, id)
        f.putU16(Protocol.HEADER_SIZE + 2, indexField)
        f.putU16(Protocol.HEADER_SIZE + 4, packetCount)
        f.putU32(Protocol.HEADER_SIZE + 6, total)
        return f
    }

    private fun bit(ack: ByteArray, n: Int): Boolean =
        (ack[ACK_BITMAP_OFFSET + n / 8].toInt() shr (n % 8)) and 1 == 1

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.putU16(o: Int, v: Int) {
        this[o] = (v and 0xFF).toByte(); this[o + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putU32(o: Int, v: Long) {
        for (i in 0..3) this[o + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun ByteArray.putI16(o: Int, v: Int) = putU16(o, v and 0xFFFF)

    private fun ByteArray.putI32(o: Int, v: Int) = putU32(o, v.toLong() and 0xFFFFFFFFL)

    private fun ByteArray.putF32(o: Int, v: Float) =
        putU32(o, java.lang.Float.floatToRawIntBits(v).toLong() and 0xFFFFFFFFL)

    private fun ByteArray.putF64(o: Int, v: Double) {
        val bits = java.lang.Double.doubleToRawLongBits(v)
        for (i in 0..7) this[o + i] = ((bits ushr (8 * i)) and 0xFF).toByte()
    }
}
