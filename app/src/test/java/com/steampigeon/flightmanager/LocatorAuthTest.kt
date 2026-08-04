package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.LocatorAuth
import com.steampigeon.flightmanager.data.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the app's password auth matches the firmware primitives byte-for-byte.
 *
 * FNV-1a mirrors PasswordKdf.hpp (offset 0x811c9dc5, prime 0x01000193); the
 * canonical vectors below fix that. The auth_tag itself uses the same CRC-16
 * (poly 0xA001) as Communication::ComputePasswordAuthTag over the base struct
 * with crc + auth_tag zeroed — exercised here by a build/verify round-trip.
 */
class LocatorAuthTest {

    // Canonical FNV-1a 32-bit test vectors (FNV reference).
    @Test fun fnvEmptyIsOffsetBasis() = assertEquals(0x811c9dc5L, LocatorAuth.fnv1a32(""))
    @Test fun fnvA() = assertEquals(0xe40c292cL, LocatorAuth.fnv1a32("a"))
    @Test fun fnvFoobar() = assertEquals(0xbf9cf968L, LocatorAuth.fnv1a32("foobar"))

    @Test fun blankPasswordIsOpenKey() = assertEquals(0L, LocatorAuth.deriveKey(""))
    @Test fun realPasswordIsNonZero() = assertNotEquals(0L, LocatorAuth.deriveKey("launch42"))

    @Test
    fun authTagRoundTrip() {
        val size = Protocol.PRELAUNCH_BASE_STRUCT_SIZE
        // Frame = base struct (115) + a few receiver-appended bytes that must be ignored.
        val frame = ByteArray(size + 25) { (it * 7 + 3).toByte() }
        // Simulate the receiver rewriting packet_header.crc — it must not affect auth.
        frame[4] = 0x12; frame[5] = 0x34

        val key = LocatorAuth.deriveKey("s3cret")
        val tag = LocatorAuth.expectedAuthTag(frame, key)!!
        // Embed the tag as the locator would (last 4 bytes of the base struct, LE).
        frame[size - 4] = (tag and 0xFF).toByte()
        frame[size - 3] = ((tag ushr 8) and 0xFF).toByte()
        frame[size - 2] = ((tag ushr 16) and 0xFF).toByte()
        frame[size - 1] = ((tag ushr 24) and 0xFF).toByte()

        assertTrue(LocatorAuth.verifyFrame(frame, key))
        assertFalse(LocatorAuth.verifyFrame(frame, LocatorAuth.deriveKey("wrong")))
        // Appended receiver metadata is outside the authenticated region.
        frame[size + 5] = (frame[size + 5] + 1).toByte()
        assertTrue(LocatorAuth.verifyFrame(frame, key))
    }

    @Test
    fun telemetryAuthTagRoundTrip() {
        // TelemetryData carries the same trailing locator_id + auth_tag pair, so an
        // ARMED locator authenticates by the identical rule — only the base size
        // differs. This is what lets the app recognise a locator it has never heard
        // a PreLaunchData from this session.
        val size = Protocol.TELEMETRY_BASE_STRUCT_SIZE
        val frame = ByteArray(size + 2) { (it * 5 + 11).toByte() }   // + appended rssi
        frame[4] = 0x56; frame[5] = 0x78                              // receiver's re-CRC

        val key = LocatorAuth.deriveKey("s3cret")
        val tag = LocatorAuth.expectedAuthTag(frame, key, size)!!
        frame[size - 4] = (tag and 0xFF).toByte()
        frame[size - 3] = ((tag ushr 8) and 0xFF).toByte()
        frame[size - 2] = ((tag ushr 16) and 0xFF).toByte()
        frame[size - 1] = ((tag ushr 24) and 0xFF).toByte()

        assertTrue(LocatorAuth.verifyFrame(frame, key, size))
        assertFalse(LocatorAuth.verifyFrame(frame, LocatorAuth.deriveKey("wrong"), size))
        // The receiver-appended RSSI is outside the authenticated region.
        frame[size] = (frame[size] + 1).toByte()
        assertTrue(LocatorAuth.verifyFrame(frame, key, size))
    }

    @Test
    fun openLocatorAuthenticatesUnderKeyZeroOnBothMessages() {
        // An unprovisioned locator (no password) must keep working with no prompt,
        // armed or disarmed — the backward-compatibility guarantee in ADR-0006.
        for (size in listOf(
            Protocol.PRELAUNCH_BASE_STRUCT_SIZE,
            Protocol.TELEMETRY_BASE_STRUCT_SIZE,
        )) {
            val frame = ByteArray(size + 2) { (it * 3 + 1).toByte() }
            val tag = LocatorAuth.expectedAuthTag(frame, 0L, size)!!
            frame[size - 4] = (tag and 0xFF).toByte()
            frame[size - 3] = ((tag ushr 8) and 0xFF).toByte()
            frame[size - 2] = ((tag ushr 16) and 0xFF).toByte()
            frame[size - 1] = ((tag ushr 24) and 0xFF).toByte()
            assertTrue("open locator rejected at base size $size",
                LocatorAuth.verifyFrame(frame, 0L, size))
        }
    }

    @Test
    fun aFrameShorterThanItsBaseStructIsRejected() {
        // A truncated frame must fail closed rather than authenticating on
        // whatever bytes happened to be present.
        val short = ByteArray(Protocol.TELEMETRY_BASE_STRUCT_SIZE - 1)
        assertFalse(LocatorAuth.verifyFrame(short, 0L, Protocol.TELEMETRY_BASE_STRUCT_SIZE))
    }
}
