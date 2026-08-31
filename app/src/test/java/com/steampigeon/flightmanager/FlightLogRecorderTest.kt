package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.FlightLog
import com.steampigeon.flightmanager.data.FlightLogRecord
import com.steampigeon.flightmanager.data.FlightLogRecorder
import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.data.LogCloseReason
import com.steampigeon.flightmanager.data.LogEvent
import com.steampigeon.flightmanager.data.LogSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The recorder's sequencing: what is kept, what is discarded, what ends a file.
 *
 * Driven against a scripted sink with no clock and no Android types, the same way
 * `ChannelMoveRunnerTest` drives the channel move — these are the decisions that can
 * be silently wrong, and none of them is observable from a green build.
 */
class FlightLogRecorderTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    /** Records every call so the ORDER can be asserted, not just the contents. */
    private class FakeSink : FlightLogRecorder.Sink {
        val opened = mutableListOf<String>()
        val rows = mutableListOf<String>()
        var closes = 0
        var refuseOpen = false

        override fun open(fileName: String): Boolean {
            if (refuseOpen) return false
            opened += fileName
            return true
        }

        override fun append(rows: List<String>) { this.rows += rows }
        override fun close() { closes++ }
    }

    private fun recorder(sink: FakeSink, preRollMs: Long = 2_000L) =
        FlightLogRecorder(sink, preRollMs).also { it.zone = zone }

    private fun sample(t: Long, rssi: Int = -80) = FlightLogRecord.Sample(
        timestampMs = t,
        source = LogSource.Prelaunch,
        rssi = rssi,
    )

    private fun event(t: Long, text: String) =
        FlightLogRecord.Event(t, LogEvent.Announcement, text)

    // ── Nothing is written without a launch ──────────────────────────────────

    @Test
    fun `a session that never flies writes nothing at all`() {
        val sink = FakeSink()
        val r = recorder(sink)
        // Connect, arm, configure, disarm: two minutes of pre-launch broadcasts.
        for (t in 0L until 120_000L step 1_000L) r.offer(sample(t))
        r.offer(event(60_000, "Armed."))
        assertTrue("no file may be opened without a launch", sink.opened.isEmpty())
        assertTrue(sink.rows.isEmpty())
        assertFalse(r.isRecording)
    }

    @Test
    fun `closing when nothing is open is a no-op`() {
        val sink = FakeSink()
        recorder(sink).close(1_000, LogCloseReason.Disarmed)
        assertEquals(0, sink.closes)
        assertTrue(sink.rows.isEmpty())
    }

    // ── The pre-roll ─────────────────────────────────────────────────────────

    /**
     * The pre-roll window is a SPAN measured back from the newest record, so the
     * frame sitting exactly on the boundary is kept.  At 1 Hz that is three frames
     * covering two seconds, not two frames covering one — which is the reading that
     * satisfies "from 2 seconds prior": the two-second-old frame is the one the
     * requirement names, so dropping it would leave the window starting at 1 s.
     */
    @Test
    fun `the pre-roll spans two seconds back from the newest frame and no further`() {
        val sink = FakeSink()
        val r = recorder(sink)
        // 1 Hz pre-launch broadcasts, ten of them, each identifiable by its RSSI.
        for (i in 0..9) r.offer(sample(i * 1_000L, rssi = -100 + i))
        r.onLaunch(10_000, "Pigeon", "header")

        val rssis = sink.rows.mapNotNull { row ->
            row.split(",").getOrNull(RSSI_COLUMN)?.takeIf { it.isNotEmpty() }?.toInt()
        }
        // t=7000, 8000, 9000 span exactly 2 s back from the newest; t=6000 does not.
        assertEquals(listOf(-93, -92, -91), rssis)
    }

    @Test
    fun `the pre-roll holds the last frames heard, not the last two seconds of clock`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.offer(sample(0, rssi = -95))
        r.offer(sample(1_000, rssi = -96))
        // Then the signal is lost for a minute and the launch happens unheard.
        r.onLaunch(61_000, "Pigeon", "header")

        val rssis = sink.rows.mapNotNull { row ->
            row.split(",").getOrNull(RSSI_COLUMN)?.takeIf { it.isNotEmpty() }?.toInt()
        }
        // Both survive: ageing them against the launch instant would have discarded
        // the last frames before the dropout, which are the ones worth having.
        assertEquals(listOf(-95, -96), rssis)
    }

    @Test
    fun `app events in the pre-roll are kept alongside the frames`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.offer(event(9_100, "Pad alert."))
        r.offer(sample(9_500))
        r.onLaunch(10_000, "Pigeon", "header")
        assertTrue(sink.rows.any { it.contains("Pad alert.") })
    }

    @Test
    fun `pre-roll survives a refused open so the next launch still has it`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.offer(sample(9_000, rssi = -77))
        sink.refuseOpen = true
        assertFalse(r.onLaunch(10_000, "Pigeon", "header"))
        assertFalse("a refused open must not look like recording", r.isRecording)

        sink.refuseOpen = false
        assertTrue(r.onLaunch(10_500, "Pigeon", "header"))
        assertTrue(sink.rows.any { it.split(",").getOrNull(RSSI_COLUMN) == "-77" })
    }

    @Test
    fun `discardPreRoll drops buffered frames so they cannot reach the next flight`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.offer(sample(9_000, rssi = -77))
        r.discardPreRoll()
        r.onLaunch(10_000, "Pigeon", "header")
        assertFalse(sink.rows.any { it.split(",").getOrNull(RSSI_COLUMN) == "-77" })
    }

    // ── Opening ──────────────────────────────────────────────────────────────

    @Test
    fun `the file opens with the session header then the pre-roll then the launch`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.offer(sample(9_000))
        r.onLaunch(10_000, "Pigeon", "locator=Pigeon")

        val events = sink.rows.map { it.split(",")[EVENT_COLUMN] }
        assertEquals(LogEvent.SessionOpened.label, events.first())
        assertEquals("", events[1])                       // the pre-roll frame
        assertEquals(LogEvent.LaunchDetected.label, events.last())
    }

    @Test
    fun `the header row is stamped no later than the oldest row in the file`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.offer(sample(9_000))
        r.onLaunch(10_000, "Pigeon", "header")
        // elapsed_s of the header must not be ahead of the first data row, or a
        // reader sorting by time finds the file's own header two seconds in.
        val elapsed = sink.rows.map { it.split(",")[ELAPSED_COLUMN].toDouble() }
        assertEquals(elapsed.sorted(), elapsed)
    }

    @Test
    fun `elapsed_s is measured from launch detect, so the pre-roll is negative`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.offer(sample(8_000))
        r.offer(sample(9_000))
        r.onLaunch(10_000, "Pigeon", "header")

        val elapsed = sink.rows.map { it.split(",")[ELAPSED_COLUMN].toDouble() }
        assertTrue("pre-roll rows sit before zero", elapsed.any { it < 0.0 })
        assertEquals(0.0, elapsed.last(), 1e-9)
    }

    @Test
    fun `rows offered after the launch reach the file`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.onLaunch(10_000, "Pigeon", "header")
        val before = sink.rows.size
        r.offer(sample(11_000, rssi = -60))
        assertEquals(before + 1, sink.rows.size)
        assertTrue(sink.rows.last().split(",")[RSSI_COLUMN] == "-60")
    }

    // ── Closing ──────────────────────────────────────────────────────────────

    @Test
    fun `landing does not close the log`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.onLaunch(10_000, "Pigeon", "header")
        r.offer(FlightLogRecord.Event(60_000, LogEvent.LandingDetected, "locator reported Landed"))
        assertTrue("the recovery walk-in is the point of the tail", r.isRecording)
        assertEquals(0, sink.closes)

        // And the rows recorded during recovery still land in the file.
        r.offer(sample(120_000, rssi = -110))
        assertTrue(sink.rows.last().split(",")[RSSI_COLUMN] == "-110")
    }

    @Test
    fun `close writes the reason as the last row`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.onLaunch(10_000, "Pigeon", "header")
        r.close(200_000, LogCloseReason.Disarmed)

        val last = sink.rows.last().split(",")
        assertEquals(LogEvent.SessionClosed.label, last[EVENT_COLUMN])
        assertTrue(last[DETAIL_COLUMN].contains("disarmed"))
        assertEquals(1, sink.closes)
        assertFalse(r.isRecording)
    }

    @Test
    fun `rows offered after a close are buffered for the next flight, not written`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.onLaunch(10_000, "Pigeon", "header")
        r.close(20_000, LogCloseReason.Disarmed)
        val after = sink.rows.size
        r.offer(sample(21_000))
        assertEquals(after, sink.rows.size)
    }

    @Test
    fun `a second launch closes the first log and opens another`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.onLaunch(10_000, "Pigeon", "header")
        r.onLaunch(500_000, "Pigeon", "header")

        assertEquals(2, sink.opened.size)
        assertEquals("two flights must not share a file", 1, sink.closes)
        assertTrue(r.isRecording)
        // The close of the first names the launch that caused it.
        assertTrue(sink.rows.any {
            val c = it.split(",")
            c[EVENT_COLUMN] == LogEvent.SessionClosed.label &&
                c[DETAIL_COLUMN].contains(LogCloseReason.NewLaunch.label)
        })
    }

    @Test
    fun `the second flight is timed from its own launch`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.onLaunch(10_000, "Pigeon", "header")
        r.onLaunch(500_000, "Pigeon", "header")
        sink.rows.clear()
        r.offer(sample(501_000))
        assertEquals(1.0, sink.rows.last().split(",")[ELAPSED_COLUMN].toDouble(), 1e-9)
    }

    // ── Naming ───────────────────────────────────────────────────────────────

    @Test
    fun `the file is named for the locator and the local launch time`() {
        val sink = FakeSink()
        recorder(sink).onLaunch(1_756_000_000_000, "Kestrel", "header")
        assertEquals("Kestrel_2025-08-24_014640.csv", sink.opened.single())
    }

    @Test
    fun `a locator name that would escape the directory is made safe`() {
        assertEquals(
            ".._etc_passwd_2025-08-24_014640.csv",
            FlightLog.fileName("../etc/passwd", 1_756_000_000_000, zone),
        )
    }

    @Test
    fun `an unnamed locator still produces a usable filename`() {
        val name = FlightLog.fileName("", 1_756_000_000_000, zone)
        assertEquals("locator_2025-08-24_014640.csv", name)
    }

    // ── Format ───────────────────────────────────────────────────────────────

    @Test
    fun `every row has exactly as many columns as the header`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.offer(sample(9_000))
        r.onLaunch(10_000, "Pigeon", "header")
        r.offer(
            FlightLogRecord.Sample(
                timestampMs = 11_000,
                source = LogSource.Telemetry,
                flightState = FlightStates.Burnout,
                latitude = 42.5,
                longitude = -71.25,
                aglM = 1234.5f,
            )
        )
        r.close(12_000, LogCloseReason.AppStopped)

        val expected = FlightLog.COLUMN_COUNT
        sink.rows.forEach { row ->
            assertEquals("row: $row", expected, splitCsv(row).size)
        }
    }

    @Test
    fun `an announcement containing a comma does not become two columns`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.onLaunch(10_000, "Pigeon", "header")
        r.offer(event(11_000, "Landing, 240 meters north east of the pad."))

        val row = sink.rows.last()
        assertEquals(FlightLog.COLUMN_COUNT, splitCsv(row).size)
        assertTrue("free text must be quoted", row.contains("\"Landing, 240 meters"))
    }

    @Test
    fun `a non-finite altitude renders blank rather than as NaN text`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.onLaunch(10_000, "Pigeon", "header")
        r.offer(
            FlightLogRecord.Sample(
                timestampMs = 11_000,
                source = LogSource.Telemetry,
                aglM = Float.NaN,
            )
        )
        assertEquals("", sink.rows.last().split(",")[AGL_COLUMN])
    }

    @Test
    fun `an unknown noise floor is blank rather than a plausible reading`() {
        val sink = FakeSink()
        val r = recorder(sink)
        r.onLaunch(10_000, "Pigeon", "header")
        r.offer(
            FlightLogRecord.Sample(
                timestampMs = 11_000,
                source = LogSource.Telemetry,
                noiseFloor = com.steampigeon.flightmanager.data.LinkQuality.NOISE_FLOOR_UNKNOWN,
            )
        )
        assertEquals("", sink.rows.last().split(",")[NOISE_FLOOR_COLUMN])
    }

    /** Splits on commas outside quotes, the way a CSV reader would. */
    private fun splitCsv(row: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                quoted && c == '"' && i + 1 < row.length && row[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cell.toString(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    private companion object {
        // Resolved from the header so a column added in the middle moves these
        // rather than silently re-pointing every assertion at its neighbour.
        val COLUMNS = FlightLog.CSV_HEADER.split(",")
        val ELAPSED_COLUMN = COLUMNS.indexOf("elapsed_s")
        val EVENT_COLUMN = COLUMNS.indexOf("event")
        val DETAIL_COLUMN = COLUMNS.indexOf("detail")
        val AGL_COLUMN = COLUMNS.indexOf("agl_m")
        val RSSI_COLUMN = COLUMNS.indexOf("rssi_dbm")
        val NOISE_FLOOR_COLUMN = COLUMNS.indexOf("noise_floor_dbm")
    }
}
