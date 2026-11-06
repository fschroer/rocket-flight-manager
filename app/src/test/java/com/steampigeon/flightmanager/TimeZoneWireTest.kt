package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.TimeText
import com.steampigeon.flightmanager.data.TimeZoneWire
import com.steampigeon.flightmanager.data.flightTimeIso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Locator ADR "Times are stored in UTC and shown with their offset" (0043). */
class TimeZoneWireTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val t = Instant.ofEpochSecond(1_793_950_609L)   // 2026-11-06 07:36:49 UTC

    @Test fun offsetFollowsDst() {
        assertEquals(-480, TimeZoneWire.offsetMinutes(la, t))                                   // PST
        assertEquals(-420, TimeZoneWire.offsetMinutes(la, Instant.parse("2026-07-04T19:00:00Z"))) // PDT
        assertEquals(345, TimeZoneWire.offsetMinutes(ZoneId.of("Asia/Kathmandu"), t))
    }

    @Test fun payloadIsInt16LittleEndian() {
        assertEquals(listOf(0x20, 0xFE), TimeZoneWire.payload(-480).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x59, 0x01), TimeZoneWire.payload(345).map { it.toInt() and 0xFF })
        assertEquals(TimeZoneWire.PAYLOAD_SIZE, TimeZoneWire.payload(0).size)
    }

    @Test fun payloadClampsToTheLocatorsRange() {
        assertEquals(TimeZoneWire.payload(840).toList(), TimeZoneWire.payload(2000).toList())
        assertEquals(TimeZoneWire.payload(-840).toList(), TimeZoneWire.payload(-2000).toList())
    }

    @Test fun sendRule() {
        val now = 100_000_000L
        assertFalse(TimeZoneWire.shouldSend(false, -480, null, 0, now))          // link down
        assertTrue(TimeZoneWire.shouldSend(true, -480, null, 0, now))            // first this link-up
        assertFalse(TimeZoneWire.shouldSend(true, -480, -480, now - 1000, now))  // just sent
        assertTrue(TimeZoneWire.shouldSend(true, -420, -480, now - 1000, now))   // DST or travel
        assertTrue(TimeZoneWire.shouldSend(true, -480, -480, now - TimeZoneWire.RESEND_MS, now))
    }

    @Test fun displayCarriesTheOffset() {
        assertEquals("2026-11-05 23:36:49 -08:00", TimeText.format(t.atZone(la)))
        assertEquals("2026-11-06 07:36:49 +00:00", TimeText.format(t.atZone(ZoneId.of("UTC"))))
    }

    @Test fun uploadTimeIsIsoUtc() {
        assertEquals("2026-11-06T07:36:49Z", flightTimeIso(1_793_950_609L))
        assertNull(flightTimeIso(0L))
    }
}
