package com.steampigeon.flightmanager.fullrecord

import com.steampigeon.flightmanager.data.fullrecord.FullRecordCodec
import com.steampigeon.flightmanager.data.fullrecord.FullRecordCodec.SAMPLE_SIZE
import com.steampigeon.flightmanager.data.fullrecord.FullRecordCodec.SAMPLES_PER_PAGE
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Test-only port of the locator's ENCODER side (FullRecordCodec Encoder and
 * PagePlanner, FullRecordSender's packet and parity framing).  The app never
 * encodes; this exists so a simulated locator can retransmit under loss.  It is
 * held to the real thing by FullRecordCodecTest, which requires it to rebuild
 * every frame in the C++-generated fixture byte for byte.
 */
object TestEncoder {
    private enum class Kind(val width: Int) { Timestamp(4), D32(4), D16(2), Byte(1) }
    private val kinds: List<Pair<Int, Kind>> = buildList {
        add(0 to Kind.Timestamp)
        for (o in 4..48 step 4) add(o to Kind.D32)
        for (o in 52..74 step 2) add(o to Kind.D16)
        for (o in 76..82) add(o to Kind.Byte)
        add(83 to Kind.D16); add(85 to Kind.D16); add(87 to Kind.Byte)
        for (o in 88..92 step 2) add(o to Kind.D16)
    }

    class St(val prev: ByteArray = ByteArray(SAMPLE_SIZE), var prevDt: Int = 0)

    fun stateAt(rec: ByteArray, i: Int): St {
        val st = St()
        if (i >= 1) {
            rec.copyInto(st.prev, 0, (i - 1) * SAMPLE_SIZE, i * SAMPLE_SIZE)
            st.prevDt = u32(rec, (i - 1) * SAMPLE_SIZE) - (if (i >= 2) u32(rec, (i - 2) * SAMPLE_SIZE) else 0)
        }
        return st
    }

    private fun load(s: ByteArray, o: Int, k: Kind): Int = when (k) {
        Kind.Byte -> s[o].toInt() and 0xFF
        Kind.D16 -> (s[o].toInt() and 0xFF) or ((s[o + 1].toInt() and 0xFF) shl 8)
        else -> u32(s, o)
    }
    private fun zz(d: Int, bits: Int): Int =
        if (bits == 16) { val s = d.toShort().toInt(); ((s shl 1) xor (s shr 15)) and 0xFFFF } else (d shl 1) xor (d shr 31)
    private fun bitLen(v: Int) = 32 - Integer.numberOfLeadingZeros(v)
    private fun varBits(v: Int) = if (v == 0) 1 else 1 + 5 + (bitLen(v) - 1)

    /** (isVar, value, was) per field, exactly as the C++ Walk. */
    private fun walk(st: St, s: ByteArray, so: Int, emit: (Boolean, Int, Int) -> Unit) {
        for ((o, k) in kinds) {
            val now = load(s, so + o, k); val was = load(st.prev, o, k)
            when (k) {
                Kind.Timestamp -> emit(true, zz((now - was) - st.prevDt, 32), 0)
                Kind.D32 -> emit(true, zz(now - was, 32), 0)
                Kind.D16 -> emit(true, zz((now - was) and 0xFFFF, 16), 0)
                Kind.Byte -> emit(false, now, was)
            }
        }
    }
    private fun advance(st: St, s: ByteArray, so: Int) {
        st.prevDt = u32(s, so) - u32(st.prev, 0)
        s.copyInto(st.prev, 0, so, so + SAMPLE_SIZE)
    }
    fun sampleBits(st: St, s: ByteArray, so: Int): Int {
        var b = 0
        walk(st, s, so) { v, x, was -> b += if (v) varBits(x) else if (x == was) 1 else 9 }
        return b
    }

    /** Packet boundaries of [page], as offsets into the page plus its length. */
    fun planPage(rec: ByteArray, total: Int, page: Int, cap: Int = 230): IntArray {
        val first = page * SAMPLES_PER_PAGE
        val len = FullRecordCodec.pageLength(total, page)
        val st = stateAt(rec, first)
        val offs = mutableListOf(0)
        var used = 0
        for (i in 0 until len) {
            val bits = sampleBits(st, rec, (first + i) * SAMPLE_SIZE)
            if (used + bits > cap * 8) { offs += i; used = 0 }
            used += bits
            advance(st, rec, (first + i) * SAMPLE_SIZE)
        }
        offs += len
        return offs.toIntArray()
    }

    /** The payload for samples [first, first + n), and its byte length. */
    fun encode(rec: ByteArray, first: Int, n: Int, cap: Int = 230): ByteArray {
        val buf = ByteArray(cap)
        var pos = 0
        fun put(v: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                val m = 0x80 ushr (pos and 7)
                if ((v ushr i) and 1 == 1) buf[pos ushr 3] = (buf[pos ushr 3].toInt() or m).toByte()
                pos++
            }
        }
        val st = stateAt(rec, first)
        for (i in 0 until n) {
            val so = (first + i) * SAMPLE_SIZE
            walk(st, rec, so) { isVar, v, was ->
                if (isVar) {
                    if (v == 0) put(0, 1) else { val l = bitLen(v); put(1, 1); put(l - 1, 5); if (l > 1) put(v, l - 1) }
                } else if (v == was) put(0, 1) else { put(1, 1); put(v, 8) }
            }
            advance(st, rec, so)
        }
        return buf.copyOf((pos + 7) / 8)
    }

    fun header(type: Int, crc: Long, total: Int, page: Int, count: Int, index: Int, mask: Int,
               firstInPage: Int, sampleCount: Int, payloadLen: Int, record: Int): ByteBuffer =
        ByteBuffer.allocate(255).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x5A.toByte()); put(type.toByte()); putShort(0); putShort(0)
            putInt(crc.toInt()); putInt(total); putShort(page.toShort()); putShort(count.toShort())
            put(index.toByte()); put(mask.toByte()); putShort(firstInPage.toShort())
            put(sampleCount.toByte()); put(payloadLen.toByte()); put(record.toByte())
        }

    fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}

/**
 * A simulated locator: its archive, and FullRecordSender's burst / periodic /
 * listen cycle reduced to whole cycles.  Packets come from [TestEncoder], so
 * what it sends is what the real sender sends (FullRecordCodecTest).
 */
class SimLocator(val locatorId: Long, val slots: MutableMap<Int, ByteArray>, val rng: Random = Random(1)) {
    var armed = false
    var flightState = 0
    var pickedUp = false
    var listingMode = false
    var rssi = -80

    private var record = -1
    private var page = 0
    private var plan = IntArray(0)
    private var acked = BooleanArray(256)
    private var running = false
    private var silent = 0
    private var gotAck = false
    private var markerLeft = 0
    private var markerTotal = 0
    var cycles = 0
        private set

    fun crc(r: Int) = FullRecordCodec.crc32(slots[r]!!)
    fun debug() = "rec=$record page=$page running=$running silent=$silent plan=${plan.size - 1} acked=${acked.take(maxOf(0, plan.size - 1)).count { it }} listing=$listingMode armed=$armed"
    private fun total(r: Int) = (slots[r]?.size ?: 0) / SAMPLE_SIZE

    fun onRequest(r: Int, p: Int) {
        if (armed || listingMode) return                   // Gate != Run: dropped
        if (p == 0xFFFF) { running = false; record = -1; markerLeft = 0; return }
        if (r == record && p == page && running) { silent = 0; return }
        if (r == record && p == page && !running && plan.isNotEmpty()) {
            if (acked.take(plan.size - 1).all { it }) acked.fill(false)
            silent = 0; running = true; return
        }
        record = r; page = p; silent = 0; acked.fill(false); markerLeft = 0
        val n = total(r)
        if (n == 0 || p >= FullRecordCodec.pageCount(n)) {
            markerLeft = 2; markerTotal = n; running = false; plan = IntArray(0); return
        }
        plan = TestEncoder.planPage(slots[r]!!, n, p)
        running = true
    }

    fun onAck(crc: Long, p: Int, count: Int, bitmap: ByteArray) {
        if (armed || listingMode || record < 0 || p != page || slots[record] == null || crc != crc(record)) return
        for (i in 0 until minOf(count, plan.size - 1)) if ((bitmap[i / 8].toInt() shr (i % 8)) and 1 == 1) acked[i] = true
        gotAck = true
        if (!running && acked.take(plan.size - 1).any { !it }) { running = true; silent = 0 }
    }

    /** One cycle's burst: up to 8 unacked data packets and their parity. */
    fun burst(): List<ByteArray> {
        cycles++
        val out = mutableListOf<ByteArray>()
        if (markerLeft > 0) {
            markerLeft--
            val h = TestEncoder.header(27, if (markerTotal == 0) 0 else crc(record), markerTotal, page, 0, 0, 0, 0, 0, 0, record)
            out += h.array().copyOf(25)
            return out
        }
        if (!running || armed) return out
        val count = plan.size - 1
        if (acked.take(count).all { it }) { running = false; return out }
        if (!gotAck && cycles > 1) { if (++silent >= 3) { running = false; return out } } else silent = 0
        gotAck = false
        val rec = slots[record]!!
        val n = total(record)
        val start = (0 until count).first { !acked[it] }
        val members = mutableListOf<Int>()
        fun flushParity() {
            if (members.size >= 2) {
                var fx = 0; var cx = 0; var lx = 0; val pl = ByteArray(230); var mask = 0
                for (k in members) {
                    val first = page * SAMPLES_PER_PAGE + plan[k]
                    val p = TestEncoder.encode(rec, first, plan[k + 1] - plan[k])
                    fx = fx xor plan[k]; cx = cx xor (plan[k + 1] - plan[k]); lx = lx xor p.size
                    for (b in p.indices) pl[b] = (pl[b].toInt() xor p[b].toInt()).toByte()
                    mask = mask or (1 shl (k - members[0]))
                }
                val h = TestEncoder.header(28, crc(record), n, page, count, members[0], mask, fx, cx, lx, record)
                h.put(pl)
                out += h.array().copyOf(255)
            }
            members.clear()
        }
        for (k in start until minOf(count, start + 8)) {
            if (acked[k]) continue
            val first = page * SAMPLES_PER_PAGE + plan[k]
            val p = TestEncoder.encode(rec, first, plan[k + 1] - plan[k])
            val h = TestEncoder.header(27, crc(record), n, page, count, k, 0, plan[k], plan[k + 1] - plan[k], p.size, record)
            h.put(p)
            out += h.array().copyOf(25 + p.size)
            members += k
            if (members.size == 4) flushParity()
        }
        flushParity()
        return out
    }
}
