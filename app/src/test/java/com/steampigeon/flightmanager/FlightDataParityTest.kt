package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.FLIGHT_DATA_PARITY_SIZE
import com.steampigeon.flightmanager.data.FlightDataRepository
import com.steampigeon.flightmanager.data.FlightSample
import com.steampigeon.flightmanager.data.Protocol
import com.steampigeon.flightmanager.data.SAMPLES_PER_PACKET
import com.steampigeon.flightmanager.data.compressedPayloadBytes
import com.steampigeon.flightmanager.data.samplesInPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Parity FEC recovery in the FlightData transfer.
 *
 * The locator sends one XOR parity frame per group of four packets, which lets
 * the app rebuild a single lost member without asking for a retransmit. Both
 * failure modes pinned here are SILENT: they do not fail the transfer, they
 * insert wrong samples into a flight and then tell the locator — through the ACK
 * bitmap — that the packet arrived, so it is never retransmitted and the
 * corruption is permanent.
 *
 * 1. Recovery must not depend on the ORDER the members and the parity frame
 *    arrived in. The parity slot used to double as an accumulator XORed on every
 *    received packet, so a member retransmitted after the parity landed was
 *    XORed in once and out once and therefore never cancelled.
 * 2. A recovered payload must be trimmed to the packet's real length. The parity
 *    frame always carries the full payload capacity, so an untrimmed copy leaves
 *    the parity's own padding where the decoder reads CompressedDelta entries.
 */
class FlightDataParityTest {

    private companion object {
        const val TRANSFER_ID = 0x1234

        // Frame geometry, derived from the public protocol constants so a triad
        // change to either trips this test rather than silently reshaping it.
        const val DATA_HEADER = Protocol.HEADER_SIZE + 2 + 2 + 2 + 4          // 16
        const val PAYLOAD_CAPACITY = FLIGHT_DATA_PARITY_SIZE - DATA_HEADER    // 239

        const val COMPRESSED_HEADER = 48
        const val COMPRESSED_DELTA = 24
    }

    @Before
    fun reset() = FlightDataRepository.beginTransfer()

    // ---------------------------------------------------------------------
    //  The two regressions
    // ---------------------------------------------------------------------

    @Test
    fun parityStillRecoversWhenAMemberArrivesAfterTheParityFrame() {
        // Two of four lost, so the parity frame cannot recover anything when it
        // lands. One is then retransmitted, which leaves exactly one missing and
        // makes the group recoverable — the case the accumulator corrupted.
        val total = 4L * SAMPLES_PER_PACKET
        val members = (0..3).map { payload(it, SAMPLES_PER_PACKET) }

        FlightDataRepository.onFlightData(dataFrame(0, 4, total, members[0]))
        FlightDataRepository.onFlightData(dataFrame(1, 4, total, members[1]))
        FlightDataRepository.onFlightDataParity(parityFrame(0, 4, total, members))
        // 2 and 3 are still missing here; nothing can be recovered yet.
        assertEquals(2 * SAMPLES_PER_PACKET, FlightDataRepository.samples.value.size)

        FlightDataRepository.onFlightData(dataFrame(3, 4, total, members[3]))

        // Packet 2 is now the only gap, so parity must reconstruct it exactly.
        assertEquals(expectedSamples(members), FlightDataRepository.samples.value)
        assertTrue(FlightDataRepository.progress.value.complete)
    }

    @Test
    fun aRecoveredFullPacketCarriesExactlyEightSamples() {
        // The parity frame is 239 payload bytes; a full data packet is 216. An
        // untrimmed recovery decodes the extra 23 bytes plus the copy's own pad
        // as one more CompressedDelta — a phantom sample on EVERY recovery, not
        // only on the short tail packet.
        val total = 4L * SAMPLES_PER_PACKET
        val members = (0..3).map { payload(it, SAMPLES_PER_PACKET) }

        deliverAllExcept(missing = 2, members = members, total = total)

        assertEquals(total.toInt(), FlightDataRepository.samples.value.size)
        assertEquals(expectedSamples(members), FlightDataRepository.samples.value)
    }

    @Test
    fun aRecoveredLastPacketCarriesOnlyItsRemainderOfSamples() {
        // 27 samples over 4 packets: 8 + 8 + 8 + 3. The last payload is 96 bytes
        // against the parity frame's 239, so it is the longest reach for the pad.
        val total = 27L
        val members = (0..3).map { payload(it, samplesInPacket(it, total)) }
        assertEquals(3, samplesInPacket(3, total))

        deliverAllExcept(missing = 3, members = members, total = total)

        assertEquals(total.toInt(), FlightDataRepository.samples.value.size)
        assertEquals(expectedSamples(members), FlightDataRepository.samples.value)
    }

    // ---------------------------------------------------------------------
    //  The ordinary paths, so neither fix breaks them
    // ---------------------------------------------------------------------

    @Test
    fun parityArrivingLastRecoversTheOneMissingMember() {
        val total = 4L * SAMPLES_PER_PACKET
        val members = (0..3).map { payload(it, SAMPLES_PER_PACKET) }

        for (i in listOf(0, 1, 3))
            FlightDataRepository.onFlightData(dataFrame(i, 4, total, members[i]))
        FlightDataRepository.onFlightDataParity(parityFrame(0, 4, total, members))

        assertEquals(expectedSamples(members), FlightDataRepository.samples.value)
    }

    @Test
    fun aCompleteGroupNeedsNoRecoveryAndIsUnchangedByTheParityFrame() {
        val total = 4L * SAMPLES_PER_PACKET
        val members = (0..3).map { payload(it, SAMPLES_PER_PACKET) }

        for (i in 0..3)
            FlightDataRepository.onFlightData(dataFrame(i, 4, total, members[i]))
        val beforeParity = FlightDataRepository.samples.value
        FlightDataRepository.onFlightDataParity(parityFrame(0, 4, total, members))

        assertEquals(beforeParity, FlightDataRepository.samples.value)
        assertEquals(expectedSamples(members), FlightDataRepository.samples.value)
    }

    @Test
    fun twoMissingMembersAreNotRecovered() {
        // One parity frame carries one packet's worth of redundancy. Two gaps
        // must leave both gaps rather than inventing a payload for either.
        val total = 4L * SAMPLES_PER_PACKET
        val members = (0..3).map { payload(it, SAMPLES_PER_PACKET) }

        FlightDataRepository.onFlightData(dataFrame(0, 4, total, members[0]))
        FlightDataRepository.onFlightData(dataFrame(1, 4, total, members[1]))
        FlightDataRepository.onFlightDataParity(parityFrame(0, 4, total, members))

        assertEquals(2 * SAMPLES_PER_PACKET, FlightDataRepository.samples.value.size)
        assertFalse(FlightDataRepository.progress.value.complete)
    }

    // ---------------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------------

    /** Deliver every member but [missing], then the parity frame that rebuilds it. */
    private fun deliverAllExcept(missing: Int, members: List<ByteArray>, total: Long) {
        for (i in members.indices) {
            if (i == missing) continue
            FlightDataRepository.onFlightData(dataFrame(i, members.size, total, members[i]))
        }
        FlightDataRepository.onFlightDataParity(parityFrame(0, members.size, total, members))
    }

    /** What the reassembled flight must look like: every member, in index order. */
    private fun expectedSamples(members: List<ByteArray>): List<FlightSample> =
        members.flatMap { member ->
            FlightDataRepository.decodePayload(member)
                ?: fail("test payload does not decode") as Nothing
        }

    /**
     * A compressed payload for [packetIndex] holding [samples] samples, with
     * values keyed on the index so a member landing in the wrong slot — or a
     * recovered packet built from the wrong XOR — is visible in the assertion.
     */
    private fun payload(packetIndex: Int, samples: Int): ByteArray {
        val p = ByteArray(COMPRESSED_HEADER + (samples - 1) * COMPRESSED_DELTA)
        val k = packetIndex + 1
        p.putU32(0, 1_000L * k)                      // base timestamp, ms
        p.putF32(4, 100f * k)                        // base altitude, m
        p.putF32(8, 0.1f * k); p.putF32(12, 0.2f * k); p.putF32(16, 0.3f * k)   // accel
        p.putF32(20, 1.1f * k); p.putF32(24, 1.2f * k); p.putF32(28, 1.3f * k)  // gyro
        p.putF64(32, 0.8310 + k * 1e-4)              // lat, rad
        p.putF64(40, -2.1385 + k * 1e-4)             // lon, rad
        for (d in 0 until samples - 1) {
            val o = COMPRESSED_HEADER + d * COMPRESSED_DELTA
            p.putI16(o, 100 + d)                     // dt, ms
            p.putI16(o + 2, 10 * k + d)              // dAltitude, 0.1 m
            p.putI16(o + 4, k); p.putI16(o + 6, k + 1); p.putI16(o + 8, k + 2)
            p.putI16(o + 10, -k); p.putI16(o + 12, -k - 1); p.putI16(o + 14, -k - 2)
            p.putI32(o + 16, 500 * k + d)            // dLat, scaled
            p.putI32(o + 20, -500 * k - d)           // dLon, scaled
        }
        return p
    }

    private fun dataFrame(packetIndex: Int, packetCount: Int, total: Long, payload: ByteArray): ByteArray {
        // The payload the locator puts on the wire is exactly this packet's
        // length — variable, unlike the parity frame's.
        assertEquals(compressedPayloadBytes(packetIndex, total), payload.size)
        return header(packetIndex, packetCount, total, payload.size).also { payload.copyInto(it, DATA_HEADER) }
    }

    /**
     * The sender's parity frame for a group: the XOR of every member payload,
     * zero-padded to the full payload capacity as the locator sends it.
     */
    private fun parityFrame(groupIndex: Int, packetCount: Int, total: Long, members: List<ByteArray>): ByteArray {
        val acc = ByteArray(PAYLOAD_CAPACITY)
        for (m in members)
            for (i in m.indices) acc[i] = (acc[i].toInt() xor m[i].toInt()).toByte()
        return header(groupIndex, packetCount, total, PAYLOAD_CAPACITY)
            .also { acc.copyInto(it, DATA_HEADER) }
    }

    private fun header(indexField: Int, packetCount: Int, total: Long, payloadSize: Int): ByteArray {
        val f = ByteArray(DATA_HEADER + payloadSize)
        // PacketHeader (systemId, msgType, msgCount, crc) is the framer's business,
        // not this layer's — it reads from HEADER_SIZE on and never looks back.
        f.putU16(Protocol.HEADER_SIZE, TRANSFER_ID)
        f.putU16(Protocol.HEADER_SIZE + 2, indexField)
        f.putU16(Protocol.HEADER_SIZE + 4, packetCount)
        f.putU32(Protocol.HEADER_SIZE + 6, total)
        return f
    }

    // Little-endian writers, matching the C++ side.
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
