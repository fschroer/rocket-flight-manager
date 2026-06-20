package com.steampigeon.flightmanager.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "FlightDataRepository"

// ============================================================================
//  Wire-format constants (must stay in sync with MessageProtocol.hpp / C++ side)
// ============================================================================

private const val MAX_PACKETS        = 256
private const val LATLON_SCALE       = 1e7   // matches FlightProfileCodec::LATLON_SCALE

// PacketHeader (6) + transfer_id(2) + packet_index(2) + packet_count(2) + total_samples(4)
private const val FLIGHT_DATA_HEADER_SIZE = Protocol.HEADER_SIZE + 2 + 2 + 2 + 4

// CompressedHeader layout (48 bytes, all little-endian):
//   base_timestamp_ms  u32   4
//   base_altitude_m    f32   4
//   base_accel_mps2    Vec3f 12  (3 × f32)
//   base_gyro_dps      Vec3f 12  (3 × f32)
//   base_lat_rad       f64   8
//   base_lon_rad       f64   8
private const val COMPRESSED_HEADER_SIZE = 4 + 4 + 12 + 12 + 8 + 8   // = 48

// CompressedDelta layout (24 bytes, all little-endian):
//   d_timestamp_ms          i16
//   d_alt_0p1m              i16
//   d_accel_x/y/z_0p1mps2  3 × i16
//   d_gyro_x/y/z_0p1dps    3 × i16
//   d_lat_scaled            i32
//   d_lon_scaled            i32
private const val COMPRESSED_DELTA_SIZE  = 2 + 2 + 6 + 6 + 4 + 4    // = 24

// FlightMetadata layout (after PacketHeader):
//   record[10] of FlightMetadataRecord { timestamp(u32,4) + apogee(f32,4) + flight_time(u16,2) }
private const val METADATA_RECORD_COUNT = 10
private const val METADATA_RECORD_SIZE  = 4 + 4 + 2     // = 10 bytes
const val FLIGHT_METADATA_PAYLOAD_SIZE  = METADATA_RECORD_COUNT * METADATA_RECORD_SIZE  // = 100

// FlightDataAck wire layout:
//   PacketHeader(6) + transfer_id(2) + packet_count(2) + bitmap[32](256/8)
private const val ACK_BITMAP_BYTES = MAX_PACKETS / 8   // = 32
const val FLIGHT_DATA_ACK_SIZE     = Protocol.HEADER_SIZE + 2 + 2 + ACK_BITMAP_BYTES  // = 42

// Variable-length FlightData / FlightDataParity packets: maximum possible size.
// sizeof(FlightDataPacket) == 255 (kMaxPayloadBytes on the C++ side).
const val FLIGHT_DATA_MAX_SIZE     = Protocol.MAX_PACKET_SIZE   // 256

// Samples packed into a full FlightData packet. MUST equal
// FlightProfileCodec::MaxSamplesPerPacket() on the C++ side
// (1 + (kPayloadSize 239 − CompressedHeader 48) / CompressedDelta 24 = 8).
const val SAMPLES_PER_PACKET = 8

// Compressed payload capacity the locator reserves per packet (C++ kPayloadSize).
// A FlightDataParity frame always carries the full buffer, so its on-wire size is
// fixed even though data packets are variable-length.
private const val FLIGHT_DATA_PAYLOAD_CAPACITY = 239
const val FLIGHT_DATA_PARITY_SIZE = FLIGHT_DATA_HEADER_SIZE + FLIGHT_DATA_PAYLOAD_CAPACITY  // = 255

// Exact on-wire length of a FlightData packet (MsgType 10), derived from its
// header so the BLE framer can delimit it precisely. Data packets are
// variable-length: the last packet of a transfer (and any short transfer)
// carries fewer than SAMPLES_PER_PACKET samples.
//
// Returns null when fewer than FLIGHT_DATA_HEADER_SIZE bytes are buffered — the
// caller should wait for more bytes rather than guess.
//
// Header layout (little-endian), offsets from start of frame:
//   PacketHeader        @0  (6)
//   transfer_id   u16   @6
//   packet_index  u16   @8
//   packet_count  u16   @10
//   total_samples u32   @12
fun flightDataPacketLength(buffer: ByteArray): Int? {
    if (buffer.size < FLIGHT_DATA_HEADER_SIZE) return null
    val o = Protocol.HEADER_SIZE
    val packetIndex = (buffer[o + 2].toInt() and 0xFF) or
            ((buffer[o + 3].toInt() and 0xFF) shl 8)
    val packetCount = (buffer[o + 4].toInt() and 0xFF) or
            ((buffer[o + 5].toInt() and 0xFF) shl 8)
    // packet_count == 0 is the locator's "no data for this record" marker:
    // a header-only frame with no payload.
    if (packetCount == 0) return FLIGHT_DATA_HEADER_SIZE
    val totalSamples = (buffer[o + 6].toLong() and 0xFF) or
            ((buffer[o + 7].toLong() and 0xFF) shl 8) or
            ((buffer[o + 8].toLong() and 0xFF) shl 16) or
            ((buffer[o + 9].toLong() and 0xFF) shl 24)
    val globalStart = packetIndex.toLong() * SAMPLES_PER_PACKET
    val count = (totalSamples - globalStart).coerceIn(1L, SAMPLES_PER_PACKET.toLong()).toInt()
    return FLIGHT_DATA_HEADER_SIZE + COMPRESSED_HEADER_SIZE + (count - 1) * COMPRESSED_DELTA_SIZE
}

// ============================================================================
//  Data classes
// ============================================================================

/** One reconstructed flight sample, mirroring FlightArchive::FlightSample. */
data class FlightSample(
    val timestampMs:  Long,   // uint32 on wire → Long for convenience
    val altitudeM:    Float,
    val accel:        Vec3f,
    val gyro:         Vec3f,
    val latRad:       Double,
    val lonRad:       Double,
)

/** Metadata for a single archived flight record, mirroring FlightMetadataRecord. */
data class FlightRecordMetadata(
    val position:    Int,
    val timestampS:  Long,    // seconds since locator epoch
    val apogeeM:     Float,
    val flightTimeMs: Int,    // uint16 on wire
)

/** Transfer progress exposed to the UI. */
data class FlightTransferProgress(
    val transferId:     Int   = 0,
    val packetCount:    Int   = 0,
    val totalSamples:   Long  = 0L,
    val receivedCount:  Int   = 0,
    val complete:       Boolean = false,
    val failed:         Boolean = false,
    val noData:         Boolean = false,   // locator reported the record has no samples
)

// ============================================================================
//  FlightDataRepository
//
//  Owns all receive-side state for the FlightData reliable transfer protocol.
//
//  Responsibilities:
//    - Track which packets have been received (bitmap)
//    - Store received packet payloads for parity-based recovery
//    - Decompress payloads into FlightSample lists when a packet is received
//    - Maintain a parity buffer so a single lost packet can be reconstructed
//    - Build and return FlightDataAck packets for the caller to send
//    - Expose parsed metadata and reassembled sample list via StateFlow
// ============================================================================

object FlightDataRepository {

    // -------------------------------------------------------------------------
    //  Public state
    // -------------------------------------------------------------------------

    private val _metadata = MutableStateFlow<List<FlightRecordMetadata>>(emptyList())
    val metadata: StateFlow<List<FlightRecordMetadata>> = _metadata.asStateFlow()

    private val _samples = MutableStateFlow<List<FlightSample>>(emptyList())
    val samples: StateFlow<List<FlightSample>> = _samples.asStateFlow()

    private val _progress = MutableStateFlow(FlightTransferProgress())
    val progress: StateFlow<FlightTransferProgress> = _progress.asStateFlow()

    // -------------------------------------------------------------------------
    //  Transfer state — reset by beginTransfer()
    // -------------------------------------------------------------------------

    private var transferId:   Int  = 0
    private var packetCount:  Int  = 0
    private var totalSamples: Long = 0L

    // When true, all incoming packets are refused. Set by cancelTransfer() when
    // the user navigates away mid-transfer; cleared by beginTransfer() so that
    // a fresh requestFlightProfileData() call re-enables reception.
    private var draining = false

    // received[i] = true once packet i has been decoded without error
    private val received = BooleanArray(MAX_PACKETS)

    // Raw compressed payloads stored for parity recovery.
    // payloads[i] is non-null once packet i is received; null until then.
    private val payloads = arrayOfNulls<ByteArray>(MAX_PACKETS)

    // One XOR parity accumulator per group of kParityGroupSize (4) packets.
    // parityPayloads[g] accumulates XOR of payloads for group g.
    private val parityGroupSize = 4
    private val parityPayloads  = Array(MAX_PACKETS / 4) { ByteArray(FLIGHT_DATA_MAX_SIZE) }
    private val parityReceived  = BooleanArray(MAX_PACKETS / 4)

    // Reassembled samples indexed by packet: samplesByPacket[i] holds the
    // decoded samples from packet i, or null if not yet received.
    private val samplesByPacket = arrayOfNulls<List<FlightSample>>(MAX_PACKETS)

    // -------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------

    /** Reset all transfer state for a new record request. */
    fun beginTransfer() {
        draining     = false
        transferId   = 0
        packetCount  = 0
        totalSamples = 0L
        received.fill(false)
        payloads.fill(null)
        parityPayloads.forEach { it.fill(0) }
        parityReceived.fill(false)
        samplesByPacket.fill(null)
        _samples.value = emptyList()
        _progress.value = FlightTransferProgress()
        Log.d(TAG, "Transfer reset")
    }

    /**
     * Cancel the current transfer and refuse any further incoming packets for it.
     * Use this when the user navigates away mid-transfer to prevent late-arriving
     * LoRa packets from re-populating the repository after it has been cleared.
     * The drain flag is cleared automatically by the next beginTransfer() call,
     * which is triggered by requestFlightProfileData() in BluetoothService.
     */
    fun cancelTransfer() {
        beginTransfer()
        draining = true
        Log.d(TAG, "Transfer cancelled — draining stale packets")
    }

    /** Clear metadata (call before requesting new metadata). */
    fun clearMetadata() {
        _metadata.value = emptyList()
    }

    /**
     * Process a received FlightMetadata packet (MsgType 8).
     * Frame includes the full PacketHeader; payload starts at offset HEADER_SIZE.
     * Returns true if successfully parsed.
     */
    fun onFlightMetadata(frame: ByteArray): Boolean {
        val minSize = Protocol.HEADER_SIZE + FLIGHT_METADATA_PAYLOAD_SIZE
        if (frame.size < minSize) {
            Log.w(TAG, "FlightMetadata too short: ${frame.size} < $minSize")
            return false
        }

        val records = mutableListOf<FlightRecordMetadata>()
        var o = Protocol.HEADER_SIZE
        for (i in 0 until METADATA_RECORD_COUNT) {
            val timestampS   = Bytes.u32(frame, o);        o += 4
            val apogeeM      = Bytes.f32(frame, o);        o += 4
            val flightTimeMs = Bytes.u16(frame, o).toInt(); o += 2

            // The locator only writes non-zero records; treat timestamp==0 as empty slot.
            if (timestampS > 0L) {
                records += FlightRecordMetadata(
                    position     = i,
                    timestampS   = timestampS,
                    apogeeM      = apogeeM,
                    flightTimeMs = flightTimeMs,
                )
            }
        }

        _metadata.value = records
        Log.d(TAG, "FlightMetadata parsed: ${records.size} records")
        return true
    }

    /**
     * Process a received FlightData packet (MsgType 10).
     * Returns a built ACK ByteArray if the caller should send one, or null.
     */
    fun onFlightData(frame: ByteArray): ByteArray? {
        if (frame.size < FLIGHT_DATA_HEADER_SIZE) {
            Log.w(TAG, "FlightData too short: ${frame.size}")
            return null
        }

        var o = Protocol.HEADER_SIZE
        val rxTransferId   = Bytes.u16(frame, o).toUShort(); o += 2
        val packetIndex    = Bytes.u16(frame, o);             o += 2
        val rxPacketCount  = Bytes.u16(frame, o);             o += 2
        val rxTotalSamples = Bytes.u32(frame, o);             o += 4

        // packet_count == 0 is the locator's "no data for this record" marker.
        // There is nothing to ACK; surface it to the UI and stop here.
        if (rxPacketCount == 0) {
            if (draining || rxTransferId.toInt() == 0) return null
            if (rxTransferId.toInt() != transferId) {
                beginTransfer()
                transferId = rxTransferId.toInt()
            }
            packetCount = 0
            _progress.value = _progress.value.copy(
                transferId = transferId, packetCount = 0, totalSamples = 0L,
                receivedCount = 0, complete = true, noData = true,
            )
            Log.d(TAG, "FlightData: empty-record marker for transfer $transferId")
            return null
        }

        if (!acceptTransferHeader(rxTransferId.toInt(), rxPacketCount, rxTotalSamples))
            return null

        if (packetIndex >= packetCount) {
            Log.w(TAG, "FlightData: packet_index $packetIndex >= packet_count $packetCount")
            return null
        }

        if (received[packetIndex]) {
            Log.d(TAG, "FlightData: duplicate packet $packetIndex — re-ACKing")
            return buildAck()
        }

        val payloadBytes = frame.copyOfRange(o, frame.size)
        payloads[packetIndex] = payloadBytes

        // XOR into parity accumulator for this packet's group
        val group = packetIndex / parityGroupSize
        val acc = parityPayloads[group]
        for (b in payloadBytes.indices) {
            if (b < acc.size) acc[b] = (acc[b].toInt() xor payloadBytes[b].toInt()).toByte()
        }

        val decoded = decodePayload(payloadBytes)
        if (decoded == null) {
            Log.w(TAG, "FlightData: decode failed for packet $packetIndex")
            return null
        }

        samplesByPacket[packetIndex] = decoded
        received[packetIndex] = true
        Log.d(TAG, "FlightData: received packet $packetIndex / ${packetCount - 1}")

        tryRecoverMissingPackets()
        publishSamples()
        return buildAck()
    }

    /**
     * Process a received FlightDataParity packet (MsgType 11).
     * Returns a built ACK ByteArray if the caller should send one, or null.
     */
    fun onFlightDataParity(frame: ByteArray): ByteArray? {
        if (frame.size < FLIGHT_DATA_HEADER_SIZE) {
            Log.w(TAG, "FlightDataParity too short: ${frame.size}")
            return null
        }

        var o = Protocol.HEADER_SIZE
        val rxTransferId   = Bytes.u16(frame, o).toUShort(); o += 2
        val groupIndex     = Bytes.u16(frame, o);             o += 2
        val rxPacketCount  = Bytes.u16(frame, o);             o += 2
        val rxTotalSamples = Bytes.u32(frame, o);             o += 4

        if (!acceptTransferHeader(rxTransferId.toInt(), rxPacketCount, rxTotalSamples))
            return null

        if (groupIndex >= parityPayloads.size) {
            Log.w(TAG, "FlightDataParity: group_index $groupIndex out of range")
            return null
        }

        if (parityReceived[groupIndex]) {
            Log.d(TAG, "FlightDataParity: duplicate parity for group $groupIndex")
            return buildAck()
        }

        // Store the sender's XOR parity payload for this group.
        // The sender XORs all member packet payloads into the parity payload.
        // We store it separately so we can XOR our received-member accumulator
        // against it to recover a missing packet.
        val parityPayload = frame.copyOfRange(o, frame.size)
        parityPayloads[groupIndex] = parityPayload.copyOf(FLIGHT_DATA_MAX_SIZE)
        parityReceived[groupIndex] = true
        Log.d(TAG, "FlightDataParity: received parity for group $groupIndex")

        tryRecoverMissingPackets()
        publishSamples()
        return buildAck()
    }

    /**
     * Build a FlightDataAck packet ready to send via BluetoothService.
     * The caller is responsible for computing and embedding the CRC
     * (via BluetoothService.buildMessage / computeMessageCrc) before sending.
     *
     * Layout (42 bytes):
     *   PacketHeader (6): systemId + MsgType.FlightDataAck + msgCount(0) + crc(0 — filled by caller)
     *   transfer_id  (2): uint16 LE
     *   packet_count (2): uint16 LE
     *   bitmap      (32): one bit per packet, LSB-first
     */
    fun buildAck(): ByteArray {
        val out = ByteArray(FLIGHT_DATA_ACK_SIZE)
        var i = 0

        // PacketHeader — CRC is zeroed here; caller recomputes it
        out[i++] = Protocol.SYSTEM_ID
        out[i++] = MsgType.FlightDataAck.value.toByte()
        out[i++] = 0; out[i++] = 0     // msgCount
        out[i++] = 0; out[i++] = 0     // crc placeholder

        // transfer_id (LE)
        out[i++] = (transferId and 0xFF).toByte()
        out[i++] = ((transferId shr 8) and 0xFF).toByte()

        // packet_count (LE)
        out[i++] = (packetCount and 0xFF).toByte()
        out[i++] = ((packetCount shr 8) and 0xFF).toByte()

        // bitmap: one bit per packet, received[n] → bit n%8 of byte n/8
        for (n in 0 until minOf(packetCount, MAX_PACKETS)) {
            if (received[n]) out[i + n / 8] = (out[i + n / 8].toInt() or (1 shl (n % 8))).toByte()
        }

        return out
    }

    // -------------------------------------------------------------------------
    //  Private helpers
    // -------------------------------------------------------------------------

    /**
     * Accept or initialise the transfer header fields.
     * A new transfer_id resets all state. Returns false if the packet should
     * be discarded (e.g. stale packet from a previous transfer).
     */
    private fun acceptTransferHeader(
        rxTransferId:   Int,
        rxPacketCount:  Int,
        rxTotalSamples: Long,
    ): Boolean {
        if (rxTransferId == 0) return false  // 0 is reserved
        if (draining) return false           // user cancelled — refuse stale packets

        if (transferId == 0 || rxTransferId != transferId) {
            // New or changed transfer — reset and adopt the new ID
            if (rxTransferId != transferId && transferId != 0)
                Log.w(TAG, "Transfer ID changed $transferId → $rxTransferId; resetting")
            beginTransfer()
            transferId   = rxTransferId
            packetCount  = rxPacketCount.coerceAtMost(MAX_PACKETS)
            totalSamples = rxTotalSamples
            _progress.value = _progress.value.copy(
                transferId   = transferId,
                packetCount  = packetCount,
                totalSamples = totalSamples,
            )
        }
        return true
    }

    /**
     * For each parity group where exactly one data packet is missing and the
     * parity packet has been received, reconstruct the missing packet by
     * XORing all received member payloads against the received parity payload.
     */
    private fun tryRecoverMissingPackets() {
        for (g in 0 until (packetCount + parityGroupSize - 1) / parityGroupSize) {
            if (!parityReceived[g]) continue

            val firstPacket = g * parityGroupSize
            val lastPacket  = minOf(firstPacket + parityGroupSize, packetCount)

            val missing = (firstPacket until lastPacket).filter { !received[it] }
            if (missing.size != 1) continue  // 0 = nothing to do; 2+ = can't recover

            val missingIndex = missing[0]
            Log.d(TAG, "Recovering missing packet $missingIndex via parity for group $g")

            // XOR all received member payloads against the stored parity payload
            // to reconstruct the missing payload.
            // parityPayloads[g] already holds the sender's XOR of all members.
            // We XOR out each received member to leave only the missing one.
            val recovered = parityPayloads[g].copyOf()
            for (p in firstPacket until lastPacket) {
                if (p == missingIndex) continue
                val memberPayload = payloads[p] ?: continue
                for (b in memberPayload.indices) {
                    if (b < recovered.size)
                        recovered[b] = (recovered[b].toInt() xor memberPayload[b].toInt()).toByte()
                }
            }

            val decoded = decodePayload(recovered)
            if (decoded != null) {
                payloads[missingIndex]      = recovered
                samplesByPacket[missingIndex] = decoded
                received[missingIndex]      = true
                Log.d(TAG, "Recovered packet $missingIndex (${decoded.size} samples)")
            } else {
                Log.w(TAG, "Parity recovery of packet $missingIndex produced undecodable payload")
            }
        }
    }

    /**
     * Reassemble all received (and recovered) packets in order and publish to
     * [samples]. Gaps (still-missing packets) are omitted; the receiver UI
     * should handle a partial list gracefully.
     */
    private fun publishSamples() {
        val allSamples = mutableListOf<FlightSample>()
        var receivedCount = 0
        for (i in 0 until packetCount) {
            if (received[i]) {
                receivedCount++
                samplesByPacket[i]?.let { allSamples.addAll(it) }
            }
        }
        _samples.value = allSamples

        val complete = receivedCount == packetCount
        _progress.value = _progress.value.copy(
            receivedCount = receivedCount,
            complete      = complete,
        )
        if (complete) Log.d(TAG, "Transfer complete: ${allSamples.size} samples")
    }

    /**
     * Decompress a FlightDataPacket payload into a list of FlightSamples.
     * Mirrors FlightProfileCodec::UnpackSamples exactly.
     *
     * Layout:
     *   CompressedHeader (48 bytes)
     *   CompressedDelta  (24 bytes each, 0 or more)
     */
    fun decodePayload(payload: ByteArray): List<FlightSample>? {
        if (payload.size < COMPRESSED_HEADER_SIZE) return null

        var o = 0

        // CompressedHeader
        val baseTimestampMs = Bytes.u32(payload, o);       o += 4
        val baseAltitudeM   = Bytes.f32(payload, o);       o += 4
        val baseAccelX      = Bytes.f32(payload, o);       o += 4
        val baseAccelY      = Bytes.f32(payload, o);       o += 4
        val baseAccelZ      = Bytes.f32(payload, o);       o += 4
        val baseGyroX       = Bytes.f32(payload, o);       o += 4
        val baseGyroY       = Bytes.f32(payload, o);       o += 4
        val baseGyroZ       = Bytes.f32(payload, o);       o += 4
        val baseLatRad      = Bytes.f64(payload, o);       o += 8
        val baseLonRad      = Bytes.f64(payload, o);       o += 8

        val samples = mutableListOf<FlightSample>()

        // First sample: absolute values from header
        var prevTimestampMs = baseTimestampMs
        var prevAltitudeM   = baseAltitudeM
        var prevAccelX      = baseAccelX
        var prevAccelY      = baseAccelY
        var prevAccelZ      = baseAccelZ
        var prevGyroX       = baseGyroX
        var prevGyroY       = baseGyroY
        var prevGyroZ       = baseGyroZ

        samples += FlightSample(
            timestampMs = baseTimestampMs,
            altitudeM   = baseAltitudeM,
            accel       = Vec3f(baseAccelX, baseAccelY, baseAccelZ),
            gyro        = Vec3f(baseGyroX,  baseGyroY,  baseGyroZ),
            latRad      = baseLatRad,
            lonRad      = baseLonRad,
        )

        // Subsequent samples: delta-decode from CompressedDelta entries
        while (o + COMPRESSED_DELTA_SIZE <= payload.size) {
            val dTimestampMs  = Bytes.i16(payload, o);  o += 2
            val dAlt0p1m      = Bytes.i16(payload, o);  o += 2
            val dAccelX0p1    = Bytes.i16(payload, o);  o += 2
            val dAccelY0p1    = Bytes.i16(payload, o);  o += 2
            val dAccelZ0p1    = Bytes.i16(payload, o);  o += 2
            val dGyroX0p1     = Bytes.i16(payload, o);  o += 2
            val dGyroY0p1     = Bytes.i16(payload, o);  o += 2
            val dGyroZ0p1     = Bytes.i16(payload, o);  o += 2
            val dLatScaled    = Bytes.i32(payload, o);  o += 4
            val dLonScaled    = Bytes.i32(payload, o);  o += 4

            // Kinematic fields chain from the previous sample
            val timestampMs = prevTimestampMs + dTimestampMs
            val altitudeM   = prevAltitudeM   + dAlt0p1m   / 10f
            val accelX      = prevAccelX      + dAccelX0p1 / 10f
            val accelY      = prevAccelY      + dAccelY0p1 / 10f
            val accelZ      = prevAccelZ      + dAccelZ0p1 / 10f
            val gyroX       = prevGyroX       + dGyroX0p1  / 10f
            val gyroY       = prevGyroY       + dGyroY0p1  / 10f
            val gyroZ       = prevGyroZ       + dGyroZ0p1  / 10f

            // Lat/lon are relative to the packet's absolute base (not prev sample)
            val latRad = baseLatRad + dLatScaled / LATLON_SCALE
            val lonRad = baseLonRad + dLonScaled / LATLON_SCALE

            samples += FlightSample(
                timestampMs = timestampMs,
                altitudeM   = altitudeM,
                accel       = Vec3f(accelX, accelY, accelZ),
                gyro        = Vec3f(gyroX,  gyroY,  gyroZ),
                latRad      = latRad,
                lonRad      = lonRad,
            )

            prevTimestampMs = timestampMs
            prevAltitudeM   = altitudeM
            prevAccelX      = accelX;  prevAccelY = accelY;  prevAccelZ = accelZ
            prevGyroX       = gyroX;   prevGyroY  = gyroY;   prevGyroZ  = gyroZ
        }

        return samples
    }

    // -------------------------------------------------------------------------
    //  Wire-reading helpers (little-endian, matching the C++ side)
    // -------------------------------------------------------------------------

    private object Bytes {
        fun u16(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, o: Int): Long =
            (b[o].toLong()     and 0xFF) or
                    ((b[o + 1].toLong() and 0xFF) shl 8) or
                    ((b[o + 2].toLong() and 0xFF) shl 16) or
                    ((b[o + 3].toLong() and 0xFF) shl 24)

        fun i16(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).let {
                if (it and 0x8000 != 0) it or -0x10000 else it
            }

        fun i32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or
                    ((b[o + 1].toInt() and 0xFF) shl 8) or
                    ((b[o + 2].toInt() and 0xFF) shl 16) or
                    (b[o + 3].toInt() shl 24)

        fun f32(b: ByteArray, o: Int): Float =
            java.nio.ByteBuffer.wrap(b, o, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float

        fun f64(b: ByteArray, o: Int): Double =
            java.nio.ByteBuffer.wrap(b, o, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).double
    }
}