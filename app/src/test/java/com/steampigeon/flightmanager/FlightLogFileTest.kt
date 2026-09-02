package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.FlightLog
import com.steampigeon.flightmanager.data.FlightLogFile
import com.steampigeon.flightmanager.data.FlightLogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How stored logs are named, parsed and ordered.
 *
 * The ordering shipped wrong from 2026-08-31 to 2026-09-01: [FlightLogStore.list] sorted
 * on the whole filename, which begins with the LOCATOR, so it was locator-major and only
 * chronological within one airframe — while the comment above it claimed ordering by the
 * timestamp. Nothing caught it because a single-locator directory sorts identically either
 * way, and the defect only shows with two airframes on screen at once. It was found on the
 * iOS port, which had mirrored the sort faithfully.
 *
 * [FlightLogStore.ordered] is pure so this suite needs no `Context`.
 */
class FlightLogFileTest {

    private fun log(name: String) = FlightLogFile(name, sizeBytes = 100, modifiedMs = 0)

    // ── Ordering ─────────────────────────────────────────────────────────────

    @Test
    fun `newest first, across different locators`() {
        // The reported case: a different airframe, flown the day before.
        val older = log("Twist_Lock_5_2025-08-23_183012.csv")
        val newer = log("Kestrel_2025-08-24_014640.csv")
        assertEquals(listOf(newer, older), FlightLogStore.ordered(listOf(older, newer)))
    }

    @Test
    fun `newest first within one locator too`() {
        val first = log("Kestrel_2025-08-24_014640.csv")
        val second = log("Kestrel_2025-08-24_101500.csv")
        assertEquals(listOf(second, first), FlightLogStore.ordered(listOf(first, second)))
    }

    /**
     * The whole point of sorting on the parsed time rather than on the name: mtime moves
     * when a log is appended to, so an old flight still open through a long recovery
     * would otherwise sort above a newer one that had already closed.
     */
    @Test
    fun `modification time does not affect the order`() {
        val older = FlightLogFile("Kestrel_2025-08-23_014640.csv", 100, modifiedMs = 9_999_999)
        val newer = FlightLogFile("Kestrel_2025-08-24_014640.csv", 100, modifiedMs = 1)
        assertEquals(listOf(newer, older), FlightLogStore.ordered(listOf(older, newer)))
    }

    /**
     * A name that does not parse did not come from this recorder. It still has to land
     * somewhere deterministic rather than interleaving with real dates — and note that a
     * naive sort on `capturedAt` would put it FIRST, since its stem starts with a letter
     * and letters sort above digits descending.
     */
    @Test
    fun `an unparseable name sorts last, not first`() {
        val real = log("Kestrel_2025-08-24_014640.csv")
        val junk = log("whatever.csv")
        assertEquals(listOf(real, junk), FlightLogStore.ordered(listOf(junk, real)))
    }

    @Test
    fun `ordering is stable for two logs captured in the same second`() {
        val a = log("Alpha_2025-08-24_014640.csv")
        val b = log("Bravo_2025-08-24_014640.csv")
        // Deterministic rather than input-order dependent, whichever way round they arrive.
        assertEquals(FlightLogStore.ordered(listOf(a, b)), FlightLogStore.ordered(listOf(b, a)))
    }

    @Test
    fun `an empty directory orders to nothing`() {
        assertEquals(emptyList<FlightLogFile>(), FlightLogStore.ordered(emptyList()))
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    @Test
    fun `the locator name and capture time are recovered from the filename`() {
        val f = log("Kestrel_2025-08-24_014640.csv")
        assertEquals("Kestrel", f.locatorName)
        assertEquals("2025-08-24 01:46:40", f.capturedAt)
        assertEquals("2025-08-24_014640", f.captureKey)
    }

    /**
     * A locator name may contain underscores — the sanitiser turns every unusable
     * character into one — so the split has to come off the END rather than the start.
     */
    @Test
    fun `an underscored locator name survives the round trip`() {
        val name = FlightLog.fileName("Twist Lock 5", 1_756_000_000_000, java.time.ZoneId.of("UTC"))
        assertEquals("Twist_Lock_5", log(name).locatorName)
    }

    @Test
    fun `an unparseable name falls back to the stem rather than lying`() {
        val f = log("whatever.csv")
        assertEquals("whatever", f.capturedAt)
        assertNull(f.captureKey)
        assertEquals(FlightLog.UNNAMED_LOCATOR, f.locatorName)
    }

    /** The key is fixed-width and zero-padded, so string order IS chronological order. */
    @Test
    fun `capture keys sort chronologically as plain strings`() {
        val keys = listOf(
            "Rocket_2025-01-02_000000.csv",
            "Rocket_2025-01-10_000000.csv",
            "Rocket_2025-01-02_235959.csv",
        ).map { log(it).captureKey!! }
        assertEquals(keys.sorted(), listOf("2025-01-02_000000", "2025-01-02_235959", "2025-01-10_000000"))
    }
}
