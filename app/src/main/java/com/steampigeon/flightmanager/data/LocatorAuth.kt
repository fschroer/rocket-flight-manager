package com.steampigeon.flightmanager.data

/**
 * Password authentication for locator recognition.
 *
 * The locator authenticates its PreLaunchData broadcasts with a password-seeded
 * checksum (`auth_tag`) carried in the message.  The seed is derived from the
 * user's password with FNV-1a (32-bit) — mirrored byte-for-byte by the firmware
 * (`PasswordKdf.hpp`).  The tag itself is two CRC-16 passes over the base
 * PreLaunchData bytes (packet_header.crc and auth_tag zeroed), seeded from the
 * low/high halves of the key — mirroring `Communication::ComputePasswordAuthTag`.
 *
 * A key of 0 means "open" (no password set on the locator).
 */
object LocatorAuth {
    private const val POLY = 0xA001
    private const val FNV_OFFSET = 0x811c9dc5L
    private const val FNV_PRIME = 0x01000193L
    private const val MASK32 = 0xFFFFFFFFL

    /** FNV-1a 32-bit over the ASCII password bytes. */
    fun fnv1a32(password: String): Long {
        var hash = FNV_OFFSET
        for (b in password.encodeToByteArray()) {
            hash = hash xor (b.toLong() and 0xFF)
            hash = (hash * FNV_PRIME) and MASK32
        }
        return hash
    }

    /** Derive the stored key: blank clears (0 = open); a real password never yields 0. */
    fun deriveKey(password: String): Long {
        if (password.isEmpty()) return 0L
        val key = fnv1a32(password)
        return if (key == 0L) 1L else key
    }

    private fun crc16(seed: Int, data: ByteArray, len: Int): Int {
        var crc = seed and 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if ((crc and 1) != 0) (crc ushr 1) xor POLY else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    /**
     * Recompute the expected auth_tag over a received PreLaunchData [frame] using
     * [passwordKey].  Returns null if the frame is too short to contain the base
     * struct.  Compare against the locator-supplied `authTag` to verify.
     */
    fun expectedAuthTag(frame: ByteArray, passwordKey: Long): Long? {
        val size = Protocol.PRELAUNCH_BASE_STRUCT_SIZE
        if (frame.size < size) return null
        val region = frame.copyOf(size)
        region[4] = 0; region[5] = 0                    // packet_header.crc
        for (i in size - 4 until size) region[i] = 0    // auth_tag (last 4 bytes)
        val lo = crc16((passwordKey and 0xFFFF).toInt(), region, size)
        val hi = crc16(((passwordKey ushr 16) and 0xFFFF).toInt(), region, size)
        return ((hi.toLong() and 0xFFFF) shl 16) or (lo.toLong() and 0xFFFF)
    }

    /** True if [passwordKey] authenticates the PreLaunchData [frame] carrying [authTag]. */
    fun verify(frame: ByteArray, authTag: Long, passwordKey: Long): Boolean =
        expectedAuthTag(frame, passwordKey) == authTag

    /** The auth_tag embedded in a received PreLaunchData [frame], or null if too short. */
    fun embeddedAuthTag(frame: ByteArray): Long? {
        val size = Protocol.PRELAUNCH_BASE_STRUCT_SIZE
        if (frame.size < size) return null
        val o = size - 4
        return (frame[o].toLong() and 0xFF) or
                ((frame[o + 1].toLong() and 0xFF) shl 8) or
                ((frame[o + 2].toLong() and 0xFF) shl 16) or
                ((frame[o + 3].toLong() and 0xFF) shl 24)
    }

    /** True if [passwordKey] authenticates PreLaunchData [frame] against its own embedded tag. */
    fun verifyFrame(frame: ByteArray, passwordKey: Long): Boolean {
        val expected = expectedAuthTag(frame, passwordKey) ?: return false
        return expected == embeddedAuthTag(frame)
    }
}
