package com.steampigeon.flightmanager.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The phone's time zone, for the locator (locator ADR "Times are stored in UTC
 * and shown with their offset", 0043).
 *
 * Every time is stored as UTC: the locator's GPS timestamps, the Firebase
 * `record_ts`, the record keys.  Only printing converts, and whatever prints a
 * time prints its offset beside it.  The locator has no zone rules, so the app
 * sends it the phone's offset as it is NOW (DST included), and the locator
 * labels every time it prints with that offset.  A flight from before a DST
 * change therefore prints one hour off the clock on the wall that day, but
 * with the offset that makes it right.
 *
 * Kept free of android.* so it runs under plain kotlinc.
 */
object TimeZoneWire {
    /** The locator ignores anything outside UTC-14:00 .. UTC+14:00. */
    const val OFFSET_LIMIT_MIN = 14 * 60
    /** The payload after the target id: int16 little-endian minutes east of UTC. */
    const val PAYLOAD_SIZE = 2
    /** Offset of utc_offset_min in the whole frame (header 6 + target 4). */
    const val OFFSET_UTC_OFFSET_MIN = 10
    /**
     * A TimeZoneSet is one unacknowledged LoRa frame, and the locator keeps the
     * offset in RAM only, so it is sent again on this period while the link is
     * up: a lost frame or a locator reset is repaired within it.
     */
    const val RESEND_MS = 10 * 60_000L

    fun offsetMinutes(zone: ZoneId, at: Instant): Int =
        zone.rules.getOffset(at).totalSeconds / 60

    fun payload(offsetMin: Int): ByteArray {
        val v = offsetMin.coerceIn(-OFFSET_LIMIT_MIN, OFFSET_LIMIT_MIN)
        return byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }

    /**
     * Whether to send now: the link is up and the locator has answered a
     * VersionRequest since it came up (so this never lands on top of that
     * request in the receiver's single forward slot), and either the offset
     * changed, nothing was sent this link-up, or [RESEND_MS] has passed.
     */
    fun shouldSend(linkReady: Boolean, offsetMin: Int, lastSentOffsetMin: Int?,
                   lastSentMs: Long, nowMs: Long): Boolean =
        linkReady && (lastSentOffsetMin == null || lastSentOffsetMin != offsetMin ||
            nowMs - lastSentMs >= RESEND_MS)
}

/**
 * How the app prints a wall-clock time: local, with its offset, e.g.
 * "2026-11-05 23:36:49 -08:00", the same form the locator's console uses.
 */
object TimeText {
    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.US)

    fun format(t: ZonedDateTime): String = t.format(FORMAT)
}

/**
 * A record's GPS time as ISO 8601 UTC, "2026-11-06T07:36:49Z", or null before
 * 2001 (no GPS fix: such a record_ts is not a wall-clock time).
 */
fun flightTimeIso(recordTsS: Long): String? =
    if (recordTsS < 978_307_200L) null
    else DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(recordTsS))
