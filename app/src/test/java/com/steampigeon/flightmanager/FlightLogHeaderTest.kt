package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.FlightLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `session_opened` header, and the battery clause in particular.
 *
 * Extracted from `RocketViewModel` so it can be tested at all: nothing in this
 * suite constructs an `AndroidViewModel`, so logic left inside one is verified by
 * running the app and nothing else.
 *
 * The clause exists because a real log exposed the gap on 2026-09-04 — 341
 * telemetry rows, 285 receiver-info rows and ZERO pre-launch rows, so every
 * battery column in the file was blank. The locator sends `PreLaunchData` only
 * while disarmed and `WaitingLaunch`, and a flight is armed before it launches,
 * so an armed flight's two-second pre-roll can never contain one.
 */
class FlightLogHeaderTest {

    private fun header(
        locatorBatteryMv: Int? = 3900,
        receiverBatteryMv: Int? = 4050,
        batteryAgeMs: Long? = 47_300,
        locatorName: String = "Mike 8",
        locatorId: Long? = 3711341977L,
        receiverName: String = "Mike's Communicator",
    ) = FlightLog.sessionHeader(
        locatorName = locatorName,
        locatorId = locatorId,
        receiverName = receiverName,
        receiverChannel = 8,
        appVersion = "2026.09.04-abc1234",
        locatorBatteryMv = locatorBatteryMv,
        receiverBatteryMv = receiverBatteryMv,
        batteryAgeMs = batteryAgeMs,
    )

    @Test
    fun `the identifying fields are all present`() {
        val h = header()
        listOf(
            "locator=Mike 8",
            "locator_id=3711341977",
            "receiver=Mike's Communicator",
            "receiver_channel=8",
            "app_version=2026.09.04-abc1234",
        ).forEach { assertTrue("missing '$it' in: $h", h.contains(it)) }
    }

    @Test
    fun `batteries are carried with the age of the reading`() {
        val h = header()
        assertTrue(h, h.contains("locator_batt_mv=3900"))
        assertTrue(h, h.contains("receiver_batt_mv=4050"))
        assertTrue(h, h.contains("batt_age_s=47.3"))
    }

    /**
     * An app started after the rocket was armed never hears a pre-launch frame, so
     * it genuinely does not know. Said out loud rather than omitted: a missing
     * clause is indistinguishable from an older app that never wrote one.
     */
    @Test
    fun `never having heard the batteries says so rather than omitting the clause`() {
        val h = header(locatorBatteryMv = null, receiverBatteryMv = null, batteryAgeMs = null)
        assertTrue(h, h.contains("batteries=unknown"))
        assertFalse(h, h.contains("locator_batt_mv="))
        assertFalse(h, h.contains("batt_age_s="))
    }

    @Test
    fun `a known battery with an unknown age still reports the reading`() {
        val h = header(batteryAgeMs = null)
        assertTrue(h, h.contains("locator_batt_mv=3900"))
        assertTrue(h, h.contains("batt_age_s=unknown"))
    }

    /**
     * A reading stamped after the launch it precedes is a clock that moved
     * backwards, not a battery age. Reporting it as a negative number of seconds
     * would invite someone to subtract it and land in the future.
     */
    @Test
    fun `a negative age is reported as unknown rather than as a negative number`() {
        val h = header(batteryAgeMs = -5_000)
        assertTrue(h, h.contains("batt_age_s=unknown"))
        assertFalse(h, h.contains("-5.0"))
    }

    /**
     * The header is one CSV field. A semicolon-separated clause list keeps it out
     * of the quoting path entirely, which is worth checking rather than assuming —
     * a comma here would split the row.
     */
    @Test
    fun `the header contains no comma, so it cannot split its own row`() {
        assertFalse(header(), header().contains(","))
        assertFalse(header(locatorBatteryMv = null, receiverBatteryMv = null),
            header(locatorBatteryMv = null, receiverBatteryMv = null).contains(","))
    }

    @Test
    fun `an unnamed locator or receiver reads as unknown, not as an empty field`() {
        val h = header(locatorName = "", receiverName = "", locatorId = null)
        assertTrue(h, h.contains("locator=unknown"))
        assertTrue(h, h.contains("receiver=unknown"))
        assertTrue(h, h.contains("locator_id=0"))
    }
}
