package com.steampigeon.flightmanager.data.fullrecord

import com.steampigeon.flightmanager.data.fullrecord.FullRecordCodec.SAMPLES_PER_PAGE
import com.steampigeon.flightmanager.data.fullrecord.FullRecordCodec.SAMPLE_SIZE

/**
 * One FullRecordData (27) or FullRecordParity (28) frame, parsed.  Offsets are
 * the `Protocol.FULL_RECORD_OFFSET_*` constants, pinned by WireLayoutTest.
 */
class FullRecordPacket(
    val parity: Boolean,
    val recordCrc: Long,
    val totalSamples: Int,
    val page: Int,
    val packetCount: Int,      // 0 = marker
    val packetIndex: Int,      // data: 0..255; parity: the group's first index
    val parityMask: Int,       // data: 0; parity: members, relative to packetIndex
    val firstInPage: Int,      // parity: XOR of the members'
    val sampleCount: Int,      // parity: XOR of the members'
    val payloadLen: Int,       // parity: XOR of the members'
    val record: Int,
    val payload: ByteArray,    // data: payloadLen bytes; parity: the full 230
) {
    val isMarker get() = packetCount == 0

    companion object {
        const val HEADER_SIZE = 25
        const val PAYLOAD_CAPACITY = 230
        const val MSG_DATA = 27
        const val MSG_PARITY = 28

        /** Null when the frame is not a well-formed full-record packet. */
        fun parse(frame: ByteArray): FullRecordPacket? {
            if (frame.size < HEADER_SIZE) return null
            val type = frame[1].toInt() and 0xFF
            if (type != MSG_DATA && type != MSG_PARITY) return null
            val parity = type == MSG_PARITY
            val len = u8(frame, 23)
            val count = u16(frame, 16)
            val body = when {
                count == 0 -> ByteArray(0)
                parity -> if (frame.size < HEADER_SIZE + PAYLOAD_CAPACITY) return null
                          else frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + PAYLOAD_CAPACITY)
                else -> if (len > PAYLOAD_CAPACITY || frame.size < HEADER_SIZE + len) return null
                        else frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + len)
            }
            return FullRecordPacket(
                parity = parity,
                recordCrc = FullRecordCodec.u32(frame, 6).toLong() and 0xFFFFFFFFL,
                totalSamples = FullRecordCodec.u32(frame, 10),
                page = u16(frame, 14),
                packetCount = count,
                packetIndex = u8(frame, 18),
                parityMask = u8(frame, 19),
                firstInPage = u16(frame, 20),
                sampleCount = u8(frame, 22),
                payloadLen = len,
                record = u8(frame, 24),
                payload = body,
            )
        }

        private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
        private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    }
}

/**
 * Assembles one record from its pages (ADR "The radio download becomes the
 * primary data path", decisions 1, 2 and 5).
 *
 * Packet k of a page is coded from the two samples before it, so it decodes
 * only once everything before it has; a packet that arrives early is held until
 * then.  The decoded samples are therefore always a PREFIX of the record.
 *
 * Resume state is two parts (FullRecordStore): the COMMITTED prefix, which is
 * every completed page and only grows, and the packets held for the page in
 * progress, kept whole (even once decoded) so a restart rebuilds the page from
 * them.  Pages are a fixed 512 samples, so the committed prefix always ends on a
 * page boundary and the page to request next is a pure function of it.
 *
 * Pure Kotlin; the transfer controller owns one.
 */
class FullRecordAssembler(
    val recordCrc: Long,
    val totalSamples: Int,
    committedPrefix: ByteArray = ByteArray(0),
) {
    class Held(val firstInPage: Int, val sampleCount: Int, val payload: ByteArray)

    enum class Result { Foreign, OtherPage, Duplicate, New, PageComplete, RecordComplete, CrcMismatch }

    /** The record as raw flash bytes; samples [0, decoded) are valid. */
    val data = ByteArray(totalSamples * SAMPLE_SIZE)
    /** Samples decoded, including the page in progress (the partial view). */
    var decoded = 0
        private set
    /** First sample of the page in progress; everything before it is committed. */
    var committed = 0
        private set
    private var handedOut = 0

    val page get() = committed / SAMPLES_PER_PAGE
    val pageCount get() = FullRecordCodec.pageCount(totalSamples)
    val complete get() = committed >= totalSamples
    var verified = false
        private set

    /** Packets in the page in progress; 0 until one of its packets has arrived. */
    var packetCount = 0
        private set
    private val held = arrayOfNulls<Held>(FullRecordCodec.MAX_PACKETS_PER_PAGE)
    private var nextPacket = 0          // the packet that decodes next
    private val parities = mutableListOf<FullRecordPacket>()
    /** Packets that arrived intact but would not decode, and the last one. */
    var decodeFailures = 0
        private set
    var lastDecodeFailure = ""
        private set
    /** Packets rebuilt from parity, for the log. */
    var recovered = 0
        private set

    init {
        // Whole pages only (the store writes nothing else), and never past the end.
        val pages = minOf(committedPrefix.size / SAMPLE_SIZE, totalSamples) / SAMPLES_PER_PAGE
        val n = minOf(pages * SAMPLES_PER_PAGE, totalSamples)
        val usable = if (committedPrefix.size / SAMPLE_SIZE >= totalSamples) totalSamples else n
        committedPrefix.copyInto(data, 0, 0, usable * SAMPLE_SIZE)
        decoded = usable
        committed = usable
        handedOut = usable
        if (complete) verify()
    }

    /** Restore the packets held for the page in progress (FullRecordStore). */
    fun restoreHeld(count: Int, packets: Map<Int, Held>) {
        if (count <= 0 || complete) return
        packetCount = count
        for ((i, h) in packets) if (i in 0 until count) held[i] = h
        decodeChain()
    }

    /** Every packet held for the page in progress, decoded or not. */
    fun heldPackets(): Map<Int, Held> =
        (0 until packetCount).mapNotNull { i -> held[i]?.let { i to it } }.toMap()

    fun onPacket(p: FullRecordPacket): Result {
        if (p.recordCrc != recordCrc || p.totalSamples != totalSamples) return Result.Foreign
        if (complete) return if (verified) Result.RecordComplete else Result.CrcMismatch
        if (p.isMarker || p.page != page) return Result.OtherPage
        if (packetCount == 0) packetCount = p.packetCount
        else if (p.packetCount != packetCount) return Result.Foreign   // one CRC has one plan
        var fresh = false
        if (p.parity) {
            parities += p
        } else if (p.packetIndex < packetCount && held[p.packetIndex] == null) {
            held[p.packetIndex] = Held(p.firstInPage, p.sampleCount, p.payload)
            fresh = true
        }
        if (recover()) fresh = true
        if (!fresh) return Result.Duplicate
        val pageBefore = page
        decodeChain()
        return when {
            complete -> if (verify()) Result.RecordComplete else Result.CrcMismatch
            page != pageBefore -> Result.PageComplete
            else -> Result.New
        }
    }

    /**
     * The FullRecordAck bitmap for the page in progress.  Bit (i % 8) of byte
     * (i / 8) means packet i is held.
     */
    fun ackBitmap(): ByteArray {
        val b = ByteArray(32)
        for (i in 0 until packetCount) if (held[i] != null) b[i / 8] = (b[i / 8].toInt() or (1 shl (i % 8))).toByte()
        return b
    }

    fun heldCount(): Int = (0 until packetCount).count { held[it] != null }

    /** Raw bytes committed since the last call, for an append-only store. */
    fun takeNewlyCommitted(): ByteArray {
        val out = data.copyOfRange(handedOut * SAMPLE_SIZE, committed * SAMPLE_SIZE)
        handedOut = committed
        return out
    }

    // ---- internals ---------------------------------------------------------

    private fun recover(): Boolean {
        var any = false
        var progress = true
        while (progress) {
            progress = false
            val iter = parities.iterator()
            while (iter.hasNext()) {
                val par = iter.next()
                val members = (0 until 8).filter { (par.parityMask shr it) and 1 == 1 }.map { par.packetIndex + it }
                if (members.isEmpty() || members.any { it >= packetCount }) { iter.remove(); continue }
                val missing = members.filter { held[it] == null }
                if (missing.isEmpty()) { iter.remove(); continue }
                if (missing.size > 1) continue
                var first = par.firstInPage
                var count = par.sampleCount
                var len = par.payloadLen
                val buf = par.payload.copyOf(FullRecordPacket.PAYLOAD_CAPACITY)
                for (m in members) {
                    val h = held[m] ?: continue
                    first = first xor h.firstInPage
                    count = count xor h.sampleCount
                    len = len xor h.payload.size
                    for (b in h.payload.indices) buf[b] = (buf[b].toInt() xor h.payload[b].toInt()).toByte()
                }
                iter.remove()
                if (len > FullRecordPacket.PAYLOAD_CAPACITY) continue
                held[missing[0]] = Held(first, count, buf.copyOf(len))
                recovered++
                progress = true
                any = true
            }
        }
        return any
    }

    private fun decodeChain() {
        while (packetCount > 0 && nextPacket < packetCount) {
            val h = held[nextPacket] ?: return
            val start = committed + h.firstInPage
            val fits = start == decoded && h.sampleCount > 0 && start + h.sampleCount <= totalSamples
            val n = if (fits) FullRecordCodec.decode(FullRecordCodec.stateAt(data, decoded), h.payload,
                h.payload.size, h.sampleCount, data, decoded * SAMPLE_SIZE) else 0
            if (n != h.sampleCount) {
                // Corrupt or inconsistent: forget it so the next ACK asks again.
                // Counted for the transfer log: the same packet failing every
                // time is a codec disagreement, not a lossy link.
                decodeFailures++
                lastDecodeFailure = "p$page#$nextPacket fits=$fits n=$n of ${h.sampleCount}"
                held[nextPacket] = null
                return
            }
            decoded += n
            nextPacket++
        }
        if (packetCount > 0 && nextPacket == packetCount) {
            val end = minOf(totalSamples, committed + SAMPLES_PER_PAGE)
            if (decoded == end) committed = end
            else decoded = committed      // the packets did not cover the page: do it again
            newPage()
        }
    }

    private fun newPage() {
        packetCount = 0
        nextPacket = 0
        held.fill(null)
        parities.clear()
    }

    private fun verify(): Boolean {
        verified = FullRecordCodec.crc32(data) == recordCrc
        return verified
    }
}
