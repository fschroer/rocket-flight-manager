package com.steampigeon.flightmanager.fullrecord

import com.steampigeon.flightmanager.data.fullrecord.FullRecordAssembler
import com.steampigeon.flightmanager.data.fullrecord.FullRecordAssembler.Result
import com.steampigeon.flightmanager.data.fullrecord.FullRecordCodec
import com.steampigeon.flightmanager.data.fullrecord.FullRecordCsv
import com.steampigeon.flightmanager.data.fullrecord.FullRecordPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * The app's decoder against frames the REAL locator code produced.
 *
 * `sender_fixture.bin` comes from the locator repo's Tools/full-record-fixture:
 * it runs FullRecordSender.hpp and FullRecordCodec.hpp over two synthetic
 * records (a realistic 1,100-sample one with every invalid marker in it, and a
 * 300-sample one of pure noise, the codec's worst case) plus an empty slot, and
 * the locator's FormatFloat over 4,100 values.  Regenerate it whenever either
 * side of the codec changes; a mismatch here is a wire-format break.
 */
class FullRecordCodecTest {
    private class Fixture(val frames: List<ByteArray>, val crcs: List<Long>, val fmt: List<Triple<Float, Int, String>>)

    private val fx: Fixture by lazy {
        val raw = javaClass.classLoader!!.getResourceAsStream("fullrecord/sender_fixture.bin")!!.readBytes()
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        check(String(magic) == "SPFX" && b.int == 1)
        val frames = List(b.int) { ByteArray(b.short.toInt() and 0xFFFF).also { f -> b.get(f) } }
        val crcs = List(2) { b.int.toLong() and 0xFFFFFFFFL }
        val fmt = List(b.int) {
            val v = Float.fromBits(b.int); val frac = b.get().toInt(); val l = b.get().toInt()
            Triple(v, frac, String(ByteArray(l).also { s -> b.get(s) }, Charsets.US_ASCII))
        }
        Fixture(frames, crcs, fmt)
    }

    private fun packets(record: Int) = fx.frames.mapNotNull { FullRecordPacket.parse(it) }.filter { it.record == record }

    private fun assemble(ps: List<FullRecordPacket>): FullRecordAssembler {
        val first = ps.first { !it.isMarker }
        val a = FullRecordAssembler(first.recordCrc, first.totalSamples)
        for (p in ps) a.onPacket(p)
        return a
    }

    @Test fun everyFrameParses() {
        for (f in fx.frames) assertNotNull(FullRecordPacket.parse(f))
    }

    @Test fun parityFramesAreFullSizeAndDataFramesExact() {
        for (f in fx.frames) {
            val p = FullRecordPacket.parse(f)!!
            when {
                p.isMarker -> assertEquals(25, f.size)
                p.parity -> assertEquals(255, f.size)
                else -> assertEquals(25 + p.payloadLen, f.size)
            }
        }
    }

    @Test fun recordsDecodeBitExact() {
        for ((i, rec) in listOf(0, 2).withIndex()) {
            val a = assemble(packets(rec))
            assertTrue("record $rec complete", a.complete)
            assertTrue("record $rec CRC", a.verified)
            assertEquals(fx.crcs[i], FullRecordCodec.crc32(a.data))
            assertEquals(0, a.recovered)
        }
    }

    @Test fun emptySlotIsAMarker() {
        val m = packets(1)
        assertTrue(m.isNotEmpty() && m.all { it.isMarker && it.totalSamples == 0 })
    }

    @Test fun oneLostPacketPerGroupIsRebuiltFromParity() {
        for (rec in listOf(0, 2)) {
            val all = packets(rec)
            // Drop the second data packet after every parity frame's group start.
            val dropped = all.filter { it.parity }.map { it.page to it.packetIndex + 1 }.toSet()
            val kept = all.filter { it.parity || (it.page to it.packetIndex) !in dropped }
            val a = assemble(kept)
            assertTrue(a.complete && a.verified)
            assertEquals(dropped.size, a.recovered)
        }
    }

    @Test fun packetsMayArriveInAnyOrderWithinAPage() {
        val rng = Random(3)
        for (rec in listOf(0, 2)) {
            val all = packets(rec)
            val shuffled = all.groupBy { it.page }.toSortedMap().values.flatMap { it.shuffled(rng) }
            val a = assemble(shuffled)
            assertTrue(a.complete && a.verified)
        }
    }

    @Test fun aCorruptPayloadIsNotAccepted() {
        val all = packets(2)
        val victim = all.first { !it.parity && it.page == 0 && it.packetIndex == 3 }
        val bad = FullRecordPacket(false, victim.recordCrc, victim.totalSamples, victim.page, victim.packetCount,
            victim.packetIndex, 0, victim.firstInPage, victim.sampleCount, victim.payloadLen, victim.record,
            victim.payload.copyOf(victim.payload.size - 20))       // truncated: cannot decode
        val first = all.first()
        val a = FullRecordAssembler(first.recordCrc, first.totalSamples)
        a.onPacket(bad)
        for (p in all.filter { !it.parity }) a.onPacket(p)         // the good copy arrives later
        assertTrue(a.complete && a.verified)
    }

    @Test fun foreignCrcIsDropped() {
        val ps = packets(0)
        val a = FullRecordAssembler(ps.first().recordCrc xor 1L, ps.first().totalSamples)
        assertEquals(Result.Foreign, a.onPacket(ps.first()))
        assertEquals(0, a.decoded)
    }

    @Test fun testEncoderRebuildsEveryFixtureFrame() {
        // Holds the test-only encoder (used by the simulated locator) to the C++.
        for (rec in listOf(0, 2)) {
            val a = assemble(packets(rec))
            for (p in packets(rec).filter { !it.parity && !it.isMarker }) {
                val plan = TestEncoder.planPage(a.data, a.totalSamples, p.page)
                assertEquals(plan.size - 1, p.packetCount)
                assertEquals(plan[p.packetIndex], p.firstInPage)
                assertEquals(plan[p.packetIndex + 1] - plan[p.packetIndex], p.sampleCount)
                val first = p.page * FullRecordCodec.SAMPLES_PER_PAGE + p.firstInPage
                assertArrayEquals(p.payload, TestEncoder.encode(a.data, first, p.sampleCount))
            }
        }
    }

    @Test fun formatFloatMatchesTheLocator() {
        var bad = 0
        val sb = StringBuilder()
        for ((v, frac, want) in fx.fmt) {
            sb.setLength(0); FullRecordCsv.fmt(sb, v, frac)
            if (sb.toString() != want) { if (bad++ < 5) println("fmt($v, $frac) = $sb, locator $want") }
        }
        assertEquals(0, bad)
    }

    @Test fun csvRowCarriesBlanksForInvalidFields() {
        val a = assemble(packets(0))
        val sb = StringBuilder()
        FullRecordCsv.row(a.data, 0, sb)       // sample 0: gps_vel_n invalid (0 % 13), s_acc 0, pressure 0
        val cols = sb.toString().removeSuffix("\r\n").split(',')
        assertEquals(FullRecordCsv.HEADER.split(',').size, cols.size)
        val h = FullRecordCsv.HEADER.split(',')
        assertEquals("", cols[h.indexOf("gps_vel_n_mps")])
        assertEquals("", cols[h.indexOf("gps_s_acc_mps")])
        assertEquals("", cols[h.indexOf("baro_raw_pressure_pa")])
        assertEquals("", cols[h.indexOf("baro_temp_c")])
        assertEquals("", cols[h.indexOf("mag_x_ut")])          // 0 % 97: unknown
        sb.setLength(0); FullRecordCsv.row(a.data, 50 * FullRecordCodec.SAMPLE_SIZE, sb)
        assertEquals("0.00", sb.toString().split(',')[h.indexOf("fused_agl_m")])   // NaN prints as the locator does
    }
}
