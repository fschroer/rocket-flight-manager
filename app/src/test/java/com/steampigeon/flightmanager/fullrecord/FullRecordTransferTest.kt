package com.steampigeon.flightmanager.fullrecord

import com.steampigeon.flightmanager.data.fullrecord.FullRecordCodec.SAMPLE_SIZE
import com.steampigeon.flightmanager.data.fullrecord.FullRecordStore
import com.steampigeon.flightmanager.data.fullrecord.FullRecordTransfer
import com.steampigeon.flightmanager.data.fullrecord.FullRecordTransfer.Slot
import com.steampigeon.flightmanager.data.fullrecord.FullRecordTransfer.Status
import com.steampigeon.flightmanager.data.fullrecord.FullRecordTransfer.Trigger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * The transfer controller against a simulated locator and a lossy link
 * (ADR "The radio download becomes the primary data path", decisions 5, 6, 6a).
 */
class FullRecordTransferTest {
    private val dir: File = Files.createTempDirectory("fullrecord").toFile()
    @After fun cleanup() { dir.deleteRecursively() }

    private fun record(n: Int, seed: Int): ByteArray {
        val r = Random(seed)
        val b = ByteArray(n * SAMPLE_SIZE)
        var t = 1000
        for (i in 0 until n) {
            val o = i * SAMPLE_SIZE
            t += if (i % 300 < 200) 20 else 200
            put32(b, o, t)
            for (f in 1..12) put32(b, o + 4 * f, i * f + r.nextInt(-40, 40))
            for (k in 52 until 76 step 2) put16(b, o + k, 500 + r.nextInt(-9, 9))
            b[o + 76] = (if (i < 100) 0 else 8).toByte()
            b[o + 80] = 12
            put16(b, o + 85, 30000 - i / 4)
            for (k in 88 until 94 step 2) put16(b, o + k, r.nextInt(-300, 300))
        }
        return b
    }
    private fun put32(b: ByteArray, o: Int, v: Int) { for (i in 0..3) b[o + i] = (v ushr (8 * i)).toByte() }
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }

    private val id = 0x1234ABCDL

    inner class Harness(
        val slots: MutableMap<Int, ByteArray>,
        val meta: List<Slot>,
        var lossUp: Double = 0.0,
        var lossDown: Double = 0.0,
        seed: Int = 9,
    ) {
        val rng = Random(seed)
        val sim = SimLocator(id, slots)
        var now = 10_000L
        var listingAsked = false
        val requests = mutableListOf<Pair<Int, Int>>()
        val finished = mutableListOf<FullRecordStore.Finished>()
        val store = FullRecordStore(dir)
        var ctl = newController()

        fun newController() = FullRecordTransfer(store, object : FullRecordTransfer.Link {
            override fun sendRequest(record: Int, page: Int): Boolean {
                requests += record to page
                if (rng.nextDouble() >= lossUp) sim.onRequest(record, page); return true
            }
            override fun sendAck(recordCrc: Long, page: Int, packetCount: Int, bitmap: ByteArray): Boolean {
                if (rng.nextDouble() >= lossUp) sim.onAck(recordCrc, page, packetCount, bitmap); return true
            }
            override fun requestListing(): Boolean {
                if (rng.nextDouble() >= lossUp) { sim.listingMode = true; listingAsked = true }; return true
            }
            override fun exitListing(): Boolean { sim.listingMode = false; return true }
        }, object : FullRecordTransfer.Listener {
            override fun onRecordFinished(record: FullRecordStore.Finished) { finished += record }
        })

        /** One locator cycle: its burst, then its periodic frame. */
        fun cycle() {
            if (sim.listingMode) {
                now += 300
                if (listingAsked && rng.nextDouble() >= lossDown) { listingAsked = false; ctl.onListing(id, meta) }
                else { now += 30_000; sim.listingMode = false }        // the locator's 30 s timeout
                return
            }
            val burst = sim.burst()
            now += if (burst.isEmpty()) 1_000 else 5_500
            for (f in burst) if (rng.nextDouble() >= lossDown) ctl.onFrame(f)
            if (rng.nextDouble() >= lossDown)
                ctl.onPeriodic(now, id, sim.armed, sim.flightState, sim.rssi, sim.pickedUp)
        }

        fun runUntil(maxCycles: Int = 20_000, cond: () -> Boolean): Int {
            var n = 0
            while (!cond() && n < maxCycles) { cycle(); n++ }
            return n
        }
    }

    private fun standard(): Harness {
        val a = record(1300, 1)     // 3 pages, the last one partial
        val b = record(700, 2)
        val slots = mutableMapOf(0 to a, 2 to b)
        val meta = listOf(Slot(0, 1_700_000_000, 120f), Slot(1, 1_700_100_000, 0f), Slot(2, 1_700_200_000, 300f))
        return Harness(slots, meta)
    }

    private fun assertBitExact(h: Harness) {
        for (f in h.finished) {
            assertArrayEquals("slot ${f.slot}", h.slots[f.slot], f.bin.readBytes())
            assertTrue(f.csv.length() > 0)
        }
    }

    @Test fun buttonTransfersEveryRecordNewestFirstAndStops() {
        val h = standard()
        h.ctl.start()
        h.runUntil { h.ctl.status is Status.Finished }
        assertEquals(Status.Finished(2, 0), h.ctl.status)
        assertEquals(listOf(2, 0), h.finished.map { it.slot })              // newest first; slot 1 is empty
        assertBitExact(h)
        h.cycle()
        assertEquals(0xFFFF, h.requests.last().second)                    // stop sent
    }

    @Test fun tenPercentLossEachWayStillBitExact() {
        val h = standard().apply { lossUp = 0.1; lossDown = 0.1 }
        h.ctl.start()
        h.runUntil { h.ctl.status is Status.Finished }
        assertEquals(Status.Finished(2, 0), h.ctl.status)
        assertBitExact(h)
    }

    @Test fun heavyLossOnSeveralSeedsStillBitExact() {
        for (seed in 1..4) {
            dir.listFiles()?.forEach { it.delete() }
            val base = standard()
            val h = Harness(base.slots, base.meta, lossUp = 0.25, lossDown = 0.25, seed = seed)
            h.ctl.start()
            h.runUntil { h.ctl.status is Status.Finished }
            assertEquals("seed $seed", Status.Finished(2, 0), h.ctl.status)
            assertBitExact(h)
        }
    }

    @Test fun resumesAfterTheAppIsKilledMidRecord() {
        val h = standard()
        h.ctl.start()
        h.runUntil { (h.ctl.status as? Status.Transferring)?.let { it.slot.record == 2 && it.decoded >= 600 } == true }
        val before = h.requests.size
        h.ctl = h.newController()                                         // the kill: memory gone, disk kept
        h.ctl.start()
        h.runUntil { h.ctl.status is Status.Finished }
        assertEquals(Status.Finished(2, 0), h.ctl.status)
        assertBitExact(h)
        // Page 0 was committed before the kill, so it is never asked for again.
        assertFalse("page 0 re-requested after the restart", (2 to 0) in h.requests.drop(before))
    }

    @Test fun armingStopsItAndTheRestResumesLater() {
        val h = standard()
        h.ctl.start()
        h.runUntil { (h.ctl.status as? Status.Transferring)?.let { it.decoded > 520 } == true }
        h.sim.armed = true
        h.runUntil(50) { h.ctl.status == Status.Idle }
        assertEquals(Status.Idle, h.ctl.status)
        assertNotNull(h.store.partial(id, 1_700_200_000))
        h.sim.armed = false
        h.ctl.start()
        h.runUntil { h.ctl.status is Status.Finished }
        assertBitExact(h)
    }

    @Test fun automaticTriggersWaitForALanding() {
        val h = standard()
        h.sim.rssi = -70
        h.runUntil(30) { false }
        assertFalse("strong signal on the pad, no landing: no start", h.ctl.active)

        h.sim.armed = true; h.sim.flightState = 8                          // landed, still armed
        h.runUntil(10) { false }
        assertFalse(h.ctl.active)
        h.sim.armed = false; h.sim.flightState = 0                         // disarmed: Factory's pad reset
        h.runUntil(10) { h.ctl.active }
        assertTrue(h.ctl.active)
        h.runUntil { h.ctl.status is Status.Finished }
        assertEquals(Status.Finished(2, 0), h.ctl.status)

        h.runUntil(30) { h.ctl.active }
        assertFalse("everything is on the phone: no restart until the next landing", h.ctl.active)
    }

    @Test fun theCableStartsARunWithNoLandingButOnlyThroughTheGate() {
        val h = standard()
        h.sim.armed = true
        h.ctl.onCable()
        h.runUntil(10) { false }
        assertFalse("armed: the cable waits for the disarm", h.ctl.active)
        h.sim.armed = false
        h.runUntil(5) { h.ctl.active }
        assertTrue(h.ctl.active)
        assertEquals(Trigger.Cable, (h.ctl.status as? Status.Listing)?.trigger ?: (h.ctl.status as? Status.Transferring)?.trigger)
        h.runUntil { h.ctl.status is Status.Finished }
        assertEquals(Status.Finished(2, 0), h.ctl.status)
        assertBitExact(h)
        h.runUntil(30) { h.ctl.active }
        assertFalse("still plugged in: one run per plug", h.ctl.active)
    }

    @Test fun stoppingACableRunDoesNotRestartIt() {
        val h = standard()
        h.ctl.onCable()
        h.runUntil(200) { h.ctl.status is Status.Transferring && (h.ctl.status as Status.Transferring).decoded > 0 }
        h.ctl.stop()
        h.runUntil(30) { h.ctl.active }
        assertFalse(h.ctl.active)
    }

    @Test fun weakSignalDoesNotStartButPickupDoes() {
        val h = standard()
        h.sim.rssi = -110
        h.sim.armed = true; h.sim.flightState = 8
        h.runUntil(5) { false }
        h.sim.pickedUp = true
        h.runUntil(3) { false }
        assertFalse("armed: the gate holds the trigger", h.ctl.active)
        h.sim.armed = false; h.sim.flightState = 0; h.sim.pickedUp = false
        h.runUntil(3) { h.ctl.active }
        assertTrue(h.ctl.active)
        assertEquals(Trigger.Pickup, (h.ctl.status as? Status.Listing)?.trigger ?: (h.ctl.status as Status.Transferring).trigger)
    }

    @Test fun stoppingStandsTheAutomaticTriggersDownUntilTheNextLanding() {
        val h = standard()
        h.sim.armed = true; h.sim.flightState = 8
        h.runUntil(3) { false }
        h.sim.armed = false; h.sim.flightState = 0; h.sim.rssi = -60
        h.runUntil(20) { h.ctl.status is Status.Transferring }
        assertTrue(h.ctl.active)
        h.ctl.stop()
        h.runUntil(30) { h.ctl.active }
        assertFalse("restarted itself after the user stopped it", h.ctl.active)
        h.ctl.start()                                                      // the button still works
        h.runUntil { h.ctl.status is Status.Finished }
        assertEquals(Status.Finished(2, 0), h.ctl.status)
        assertBitExact(h)
    }

    @Test fun signalTriggerCanBeTurnedOff() {
        val h = standard()
        h.ctl.settings.signalTrigger = false
        h.sim.armed = true; h.sim.flightState = 8
        h.runUntil(3) { false }
        h.sim.armed = false; h.sim.flightState = 0; h.sim.rssi = -60
        h.runUntil(30) { false }
        assertFalse(h.ctl.active)
    }

    @Test fun finishedRecordsAreNotTransferredAgain() {
        val h = standard()
        h.ctl.start()
        h.runUntil { h.ctl.status is Status.Finished }
        val cycles = h.sim.cycles
        h.ctl.start()
        h.runUntil { h.ctl.status is Status.Finished }
        assertEquals(Status.Finished(0, 0), h.ctl.status)
        assertTrue(h.sim.cycles - cycles < 10)
    }

    @Test fun aSlotRewrittenMidTransferIsStartedOver() {
        val h = standard()
        h.ctl.start()
        h.runUntil { (h.ctl.status as? Status.Transferring)?.let { it.slot.record == 2 && it.decoded > 0 } == true }
        h.slots[2] = record(650, 77)                                       // a different record, same slot and timestamp
        h.runUntil { h.ctl.status is Status.Finished }
        assertEquals(Status.Finished(2, 0), h.ctl.status)
        assertBitExact(h)
    }

    @Test fun storeHoldsOnlyFinishedFilesAfterwards() {
        val h = standard()
        h.ctl.start()
        h.runUntil { h.ctl.status is Status.Finished }
        val names = dir.listFiles()!!.map { it.name }
        assertTrue(names.none { it.endsWith(".part") || it.endsWith(".state") || it.endsWith(".tmp") })
        assertEquals(2, h.store.finished().size)
    }
}
