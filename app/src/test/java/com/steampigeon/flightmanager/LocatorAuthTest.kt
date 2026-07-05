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
}
