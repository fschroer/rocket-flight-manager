package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.FlightEventIndex
import com.steampigeon.flightmanager.data.FlightEvents
import com.steampigeon.flightmanager.data.FlightSample
import com.steampigeon.flightmanager.data.MsgType
import com.steampigeon.flightmanager.data.Protocol
import com.steampigeon.flightmanager.data.Vec3f
import com.steampigeon.flightmanager.ui.resolveEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decode + placement tests for the FlightEvents message (MsgType 19).
 *
 * These cover the two halves the firmware can't be exercised against here: that
 * the app reads the C++ FlightEventsMessage layout at the right offsets, and
 * that event times land on the right samples.  Field offsets are written out
 * longhand in [buildFrame] so a firmware layout change breaks the test rather
 * than silently shifting every event on the chart.
 */
class FlightEventsTest {

    private companion object {
        const val RECORD = 3
        const val FLIGHT_TS_S = 1_770_000_000L
        const val MAX_ALT = 921.5f

        // Deployment stat byte: mode in bits 0-2, fired (3), pre-fire
        // continuity (4), post-fire continuity (5).  Mirrors Constants.hpp.
        fun statByte(
            mode: DeployMode,
            fired: Boolean = false,
            pre: Boolean = false,
            post: Boolean = false,
        ): Int = mode.deployMode.toInt() or
                (if (fired) 1 shl 3 else 0) or
                (if (pre) 1 shl 4 else 0) or
                (if (post) 1 shl 5 else 0)
    }

    /** Build a wire frame exactly as the locator lays FlightEventsMessage out. */
    private fun buildFrame(
        timestamps: Map<FlightEventIndex, Long>,
        channelStats: List<Int> = listOf(
            statByte(DeployMode.DroguePrimary, fired = true, pre = true),
            statByte(DeployMode.DrogueBackup),
            statByte(DeployMode.MainPrimary, fired = true, pre = true, post = true),
            statByte(DeployMode.MainBackup),
        ),
        record: Int = RECORD,
    ): ByteArray {
        val frame = ByteArray(Protocol.HEADER_SIZE + Protocol.FLIGHT_EVENTS_PAYLOAD_SIZE)
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)

        // PacketHeader: system_id, msg_type, msg_count, crc
        buf.put(Protocol.SYSTEM_ID)
        buf.put(MsgType.FlightEvents.value.toByte())
        buf.putShort(0)
        buf.putShort(0)

        buf.put(record.toByte())
        buf.put(0)                                   // reserved
        var mask = 0
        timestamps.keys.forEach { mask = mask or (1 shl it.ordinal) }
        buf.putShort(mask.toShort())
        buf.putInt(FLIGHT_TS_S.toInt())

        FlightEventIndex.entries.forEach { buf.putInt((timestamps[it] ?: 0L).toInt()) }

        buf.putFloat(MAX_ALT)
        channelStats.forEach { buf.put(it.toByte()) }

        assertEquals("frame not fully written", frame.size, buf.position())
        return frame
    }

    /** 20 Hz samples rising to apogee then descending, matching the archive cadence. */
    private fun samples(count: Int): List<FlightSample> = List(count) { i ->
        val t = i * 50L
        val apogeeAt = count / 2
        val alt = if (i <= apogeeAt) i * 2f else (2 * apogeeAt - i) * 2f
        FlightSample(
            timestampMs = t,
            altitudeM   = alt,
            accel       = Vec3f(0f, 0f, 0f),
            gyro        = Vec3f(0f, 0f, 0f),
            latRad      = 0.0,
            lonRad      = 0.0,
        )
    }

    // ── Decode ──────────────────────────────────────────────────────────────

    @Test
    fun parsesEveryFieldAtTheRightOffset() {
        val times = mapOf(
            FlightEventIndex.Launch to 0L,
            FlightEventIndex.Burnout to 1_200L,
            FlightEventIndex.Apogee to 9_000L,
            FlightEventIndex.Landing to 60_000L,
        )
        val events = FlightEvents.parse(buildFrame(times))
        assertNotNull(events)
        requireNotNull(events)

        assertEquals(RECORD, events.record)
        assertEquals(FLIGHT_TS_S, events.flightTimestampS)
        assertEquals(MAX_ALT, events.maxAltitudeM, 0.001f)

        times.forEach { (event, expected) ->
            assertEquals("timestamp for $event", expected, events.timestampMs(event))
        }
        // Events outside the present mask read as absent, not as time 0 — the
        // distinction is what keeps unrecorded events off the chart.
        assertNull(events.timestampMs(FlightEventIndex.MainPrimaryDeploy))
        assertNull(events.timestampMs(FlightEventIndex.Noseover))

        // Launch at 0 ms IS present, and must not be confused with absent.
        assertEquals(0L, events.timestampMs(FlightEventIndex.Launch))
    }

    @Test
    fun decodesChannelStatBits() {
        val events = requireNotNull(FlightEvents.parse(buildFrame(emptyMap())))

        val ch1 = events.channelStats[0]
        assertEquals(DeployMode.DroguePrimary, ch1.mode)
        assertTrue(ch1.fired)
        assertTrue(ch1.preFireContinuity)
        assertTrue("post-fire continuity should be clear", !ch1.postFireContinuity)

        val ch2 = events.channelStats[1]
        assertEquals(DeployMode.DrogueBackup, ch2.mode)
        assertTrue("backup never fired", !ch2.fired)

        assertEquals(1, events.channelFor(DeployMode.DroguePrimary))
        assertEquals(3, events.channelFor(DeployMode.MainPrimary))
        assertNull(events.channelFor(DeployMode.Unused))
    }

    @Test
    fun rejectsShortFrame() {
        val short = buildFrame(emptyMap()).copyOf(Protocol.HEADER_SIZE + 10)
        assertNull(FlightEvents.parse(short))
    }

    // ── Placement ───────────────────────────────────────────────────────────

    @Test
    fun placesEventsOnTheNearestSample() {
        // 200 samples @ 50 ms = 0..9950 ms, apogee at index 100 (5000 ms).
        val data = samples(200)
        val events = requireNotNull(FlightEvents.parse(buildFrame(mapOf(
            FlightEventIndex.Launch to 0L,
            FlightEventIndex.Burnout to 1_220L,   // between samples 24 (1200) and 25 (1250)
            FlightEventIndex.Apogee to 5_000L,
        ))))

        val resolved = resolveEvents(data, events).associateBy { it.event }
        assertEquals(3, resolved.size)

        assertEquals(0, resolved.getValue(FlightEventIndex.Launch).sampleIndex)
        // 1220 is 20 ms past sample 24 and 30 ms short of 25 — nearest is 24.
        assertEquals(24, resolved.getValue(FlightEventIndex.Burnout).sampleIndex)
        assertEquals(100, resolved.getValue(FlightEventIndex.Apogee).sampleIndex)

        // Altitude comes from the matched sample, so a marker sits on the trace.
        assertEquals(data[100].altitudeM, resolved.getValue(FlightEventIndex.Apogee).altitudeM, 0.001f)
    }

    @Test
    fun dropsEventsWithNoNearbySample() {
        // Landing is recorded well past the end of the sample data — as happens
        // while a transfer is still streaming.  It must not collapse onto the
        // last sample, nor onto sample 0.
        val data = samples(50)   // 0..2450 ms
        val events = requireNotNull(FlightEvents.parse(buildFrame(mapOf(
            FlightEventIndex.Burnout to 1_000L,
            FlightEventIndex.Landing to 60_000L,
        ))))

        val resolved = resolveEvents(data, events)
        assertEquals(listOf(FlightEventIndex.Burnout), resolved.map { it.event })
    }

    @Test
    fun skipsDeploymentEventsWithNoChannelAssigned() {
        val data = samples(200)
        // No channel is configured for MainBackup, so its event can't be
        // attributed to a set of continuity indicators.
        val stats = listOf(
            statByte(DeployMode.DroguePrimary, fired = true),
            statByte(DeployMode.Unused),
            statByte(DeployMode.MainPrimary, fired = true),
            statByte(DeployMode.Unused),
        )
        val events = requireNotNull(FlightEvents.parse(buildFrame(
            timestamps = mapOf(
                FlightEventIndex.MainPrimaryDeploy to 6_000L,
                FlightEventIndex.MainBackupDeploy to 6_500L,
            ),
            channelStats = stats,
        )))

        val resolved = resolveEvents(data, events)
        assertEquals(listOf(FlightEventIndex.MainPrimaryDeploy), resolved.map { it.event })
        // Channel number is 1-based and carried into the label.
        assertEquals("Ch 3 Main Primary", resolved.single().label)
        assertEquals(true, resolved.single().stats?.fired)
    }

    @Test
    fun returnsEventsInChronologicalOrder() {
        val data = samples(400)
        val events = requireNotNull(FlightEvents.parse(buildFrame(mapOf(
            FlightEventIndex.Landing to 15_000L,
            FlightEventIndex.Launch to 0L,
            FlightEventIndex.Apogee to 10_000L,
            FlightEventIndex.Burnout to 1_000L,
        ))))

        val resolved = resolveEvents(data, events)
        assertEquals(
            listOf(
                FlightEventIndex.Launch,
                FlightEventIndex.Burnout,
                FlightEventIndex.Apogee,
                FlightEventIndex.Landing,
            ),
            resolved.map { it.event },
        )
    }

    @Test
    fun emptySummaryOrEmptySamplesResolvesToNothing() {
        // No summary received yet (or an old-firmware locator that never sends one).
        assertTrue(resolveEvents(samples(100), FlightEvents()).isEmpty())

        // Summary in hand but no samples yet — the transfer has only just started.
        val events = requireNotNull(
            FlightEvents.parse(buildFrame(mapOf(FlightEventIndex.Apogee to 5_000L)))
        )
        assertTrue(resolveEvents(emptyList(), events).isEmpty())
    }
}
