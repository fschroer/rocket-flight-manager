package com.steampigeon.flightmanager.data

/**
 * Password authentication for locator recognition.
 *
 * The locator authenticates both of its unsolicited broadcasts with a
 * password-seeded checksum (`auth_tag`) carried in the message: `PreLaunchData`
 * while disarmed, `TelemetryData` while armed.  The seed is derived from the
 * user's password with FNV-1a (32-bit) — mirrored byte-for-byte by the firmware
 * (`PasswordKdf.hpp`).  The tag itself is two CRC-16 passes over the base message
 * bytes (packet_header.crc and auth_tag zeroed), seeded from the low/high halves
 * of the key — mirroring `Communication::ComputePasswordAuthTag`.
 *
 * Both message types are handled by the same code because the firmware
 * authenticates them by the same rule.  All that differs is the size of the base
 * struct, which callers pass in: the crc always sits at bytes 4..5 and the
 * auth_tag is always the last 4 bytes of the base region, with any
 * receiver-appended metadata sitting outside it.
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
     * Recompute the expected auth_tag over a received [frame] whose base struct is
     * [baseSize] bytes, using [passwordKey].  Returns null if the frame is too
     * short to contain the base struct.  Compare against the locator-supplied
     * `authTag` to verify.
     */
    fun expectedAuthTag(
        frame: ByteArray,
        passwordKey: Long,
        baseSize: Int = Protocol.PRELAUNCH_BASE_STRUCT_SIZE,
    ): Long? {
        if (frame.size < baseSize) return null
        val region = frame.copyOf(baseSize)
        region[4] = 0; region[5] = 0                            // packet_header.crc
        for (i in baseSize - 4 until baseSize) region[i] = 0    // auth_tag (last 4 bytes)
        val lo = crc16((passwordKey and 0xFFFF).toInt(), region, baseSize)
        val hi = crc16(((passwordKey ushr 16) and 0xFFFF).toInt(), region, baseSize)
        return ((hi.toLong() and 0xFFFF) shl 16) or (lo.toLong() and 0xFFFF)
    }

    /** True if [passwordKey] authenticates the [frame] carrying [authTag]. */
    fun verify(
        frame: ByteArray,
        authTag: Long,
        passwordKey: Long,
        baseSize: Int = Protocol.PRELAUNCH_BASE_STRUCT_SIZE,
    ): Boolean = expectedAuthTag(frame, passwordKey, baseSize) == authTag

    /** The auth_tag embedded in a received [frame], or null if too short. */
    fun embeddedAuthTag(
        frame: ByteArray,
        baseSize: Int = Protocol.PRELAUNCH_BASE_STRUCT_SIZE,
    ): Long? {
        if (frame.size < baseSize) return null
        val o = baseSize - 4
        return (frame[o].toLong() and 0xFF) or
                ((frame[o + 1].toLong() and 0xFF) shl 8) or
                ((frame[o + 2].toLong() and 0xFF) shl 16) or
                ((frame[o + 3].toLong() and 0xFF) shl 24)
    }

    /** True if [passwordKey] authenticates [frame] against its own embedded tag. */
    fun verifyFrame(
        frame: ByteArray,
        passwordKey: Long,
        baseSize: Int = Protocol.PRELAUNCH_BASE_STRUCT_SIZE,
    ): Boolean {
        val expected = expectedAuthTag(frame, passwordKey, baseSize) ?: return false
        return expected == embeddedAuthTag(frame, baseSize)
    }
}
