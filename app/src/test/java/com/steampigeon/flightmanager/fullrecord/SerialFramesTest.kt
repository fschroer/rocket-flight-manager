package com.steampigeon.flightmanager.fullrecord

import com.steampigeon.flightmanager.data.fullrecord.FullRecordTransfer
import com.steampigeon.flightmanager.data.fullrecord.SerialFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The USB cable's frames (locator ADR "The radio download becomes the primary
 * data path", decision 6c; the locator side is SerialLink.hpp).
 *
 * The request and ACK vectors were printed by a host build of the firmware's
 * own MessageProtocol.hpp structs (FullRecordRequest, FullRecordAck) with its
 * kCrc16Key, so a layout or CRC drift on either side fails here.  The periodic
 * offsets are the structs' offsetof values from the same build.
 */
class SerialFramesTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val requestVector = hex("441a0000da16cdab341202010300")
    private val ackVector = hex("441d0000ad67cdab3412efbeadde07002301010a131c252e374049525b646d767f88919aa3acb5bec7d0d9e2ebf4fd060f18")
    private val id = 0x1234ABCDL

    @Test fun requestAndAckMatchTheFirmwareBytes() {
        assertArrayEquals(requestVector, SerialFrames.request(id, record = 3, page = 0x0102))
        val bitmap = ByteArray(32) { (it * 9 + 1).toByte() }
        assertArrayEquals(ackVector, SerialFrames.ack(id, 0xDEADBEEFL, page = 7, packetCount = 0x0123, bitmap = bitmap))
        assertTrue(SerialFrames.crcOk(requestVector) && SerialFrames.crcOk(ackVector))
    }

    private fun prelaunch(locatorId: Long, armed: Boolean): ByteArray {
        val f = ByteArray(SerialFrames.PRELAUNCH_SIZE)
        f[0] = SerialFrames.SYSTEM_ID; f[1] = SerialFrames.MSG_PRELAUNCH.toByte()
        f[108] = if (armed) 1 else 0
        for (i in 0 until 4) f[110 + i] = (locatorId shr (8 * i)).toByte()
        return seal(f)
    }
    private fun telemetry(locatorId: Long, state: Int, pickedUp: Boolean): ByteArray {
        val f = ByteArray(SerialFrames.TELEMETRY_SIZE)
        f[0] = SerialFrames.SYSTEM_ID; f[1] = SerialFrames.MSG_TELEMETRY.toByte()
        f[34] = if (pickedUp) 0x83.toByte() else 0x03
        f[67] = state.toByte(); f[68] = 0
        for (i in 0 until 4) f[69 + i] = (locatorId shr (8 * i)).toByte()
        return seal(f)
    }
    private fun data(payloadLen: Int, seed: Int): ByteArray {
        val f = ByteArray(25 + payloadLen) { (it * seed + 3).toByte() }
        f[0] = SerialFrames.SYSTEM_ID; f[1] = SerialFrames.MSG_FULL_RECORD_DATA.toByte()
        f[23] = payloadLen.toByte()
        return seal(f)
    }
    private fun parity(): ByteArray {
        val f = ByteArray(255) { (it * 5).toByte() }
        f[0] = SerialFrames.SYSTEM_ID; f[1] = SerialFrames.MSG_FULL_RECORD_PARITY.toByte()
        f[23] = 17                                         // an XOR, not its length
        return seal(f)
    }
    private fun seal(f: ByteArray): ByteArray {
        val c = SerialFrames.crc(f); f[4] = c.toByte(); f[5] = (c shr 8).toByte(); return f
    }

    @Test fun periodicFramesGiveTheTransferWhatItNeeds() {
        val p = SerialFrames.periodic(prelaunch(0xCAFEF00DL, armed = false))!!
        assertEquals(0xCAFEF00DL, p.locatorId)
        assertFalse(p.armed)
        assertEquals(FullRecordTransfer.FLIGHT_STATE_WAITING_LAUNCH, p.flightState)
        val t = SerialFrames.periodic(telemetry(id, FullRecordTransfer.FLIGHT_STATE_LANDED, pickedUp = true))!!
        assertEquals(id, t.locatorId)
        assertEquals(FullRecordTransfer.FLIGHT_STATE_LANDED, t.flightState)
        assertTrue(t.pickedUp)
        assertFalse(SerialFrames.periodic(telemetry(id, 8, pickedUp = false))!!.pickedUp)
        assertNull(SerialFrames.periodic(data(40, 1)))
    }

    @Test fun framerCutsAStreamWithTextAndNoiseInIt() {
        val frames = listOf(prelaunch(id, false), data(200, 7), parity(), data(3, 11), telemetry(id, 8, false))
        val text = "\r\nDIAG|BAUD: detected 921600 - saved\r\n".toByteArray()   // has a 'D' = system id
        val stream = ArrayList<Byte>()
        stream += text.toList()
        for ((i, f) in frames.withIndex()) {
            stream += f.toList()
            if (i == 1) stream += listOf(SerialFrames.SYSTEM_ID, 0x99.toByte(), SerialFrames.SYSTEM_ID)  // a false start
        }
        val all = stream.toByteArray()
        // Whole, and in every split of up to 37 bytes per read.
        for (chunk in listOf(all.size, 1, 7, 37)) {
            val fr = SerialFrames.Framer()
            val got = ArrayList<ByteArray>()
            var at = 0
            while (at < all.size) {
                val n = minOf(chunk, all.size - at)
                got += fr.feed(all.copyOfRange(at, at + n)); at += n
            }
            assertEquals("chunk $chunk", frames.size, got.size)
            for (k in frames.indices) assertArrayEquals("chunk $chunk frame $k", frames[k], got[k])
        }
    }

    @Test fun aCorruptFrameIsDroppedAndTheNextOneFound() {
        val bad = data(100, 3).also { it[60] = (it[60] + 1).toByte() }
        val good = data(50, 5)
        val got = SerialFrames.Framer().feed(bad + good)
        assertEquals(1, got.size)
        assertArrayEquals(good, got[0])
    }
}
