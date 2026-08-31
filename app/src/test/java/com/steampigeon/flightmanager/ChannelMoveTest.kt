package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.ChannelMove
import com.steampigeon.flightmanager.data.LocatorSearch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ADR-0011 probe's verdict, pinned.
 *
 * These are the cases that decide whether the app reverts the receiver, and the
 * whole point of the amendment is that it must revert only on evidence — so the
 * "no evidence" answers matter as much as the positive ones.
 */
class ChannelMoveTest {

    private val ours = 0x11223344L
    private val theirs = 0x55667788L

    private fun hit(channel: Int, id: Long, rssi: Int, snr: Int) =
        LocatorSearch.Hit(
            channel = channel,
            locatorId = id,
            deviceName = "Twist 0",
            rssi = rssi,
            snr = snr,
            armed = false,
        )

    @Test
    fun `heard on the new channel confirms the move`() {
        val v = ChannelMove.verdict(
            hits = listOf(hit(60, ours, -70, 9)),
            locatorId = ours, newChannel = 60, oldChannel = 34,
        )
        assertEquals(ChannelMove.Verdict.Confirmed, v)
    }

    @Test
    fun `heard on the old channel means the locator stayed behind`() {
        val v = ChannelMove.verdict(
            hits = listOf(hit(34, ours, -70, 9)),
            locatorId = ours, newChannel = 60, oldChannel = 34,
        )
        assertEquals(ChannelMove.Verdict.LocatorStayed, v)
    }

    @Test
    fun `nothing heard is no evidence, not a failed move`() {
        val v = ChannelMove.verdict(
            hits = emptyList(),
            locatorId = ours, newChannel = 60, oldChannel = 34,
        )
        assertEquals(ChannelMove.Verdict.NoEvidence, v)
    }

    /**
     * The near-field artifact, which is why both dwells always run. One locator
     * reported on both channels; `rssi + snr` picks the real one. Same figure of
     * merit as [LocatorSearch.Run.suspectChannels], validated on hardware
     * 2026-08-28.
     */
    @Test
    fun `heard on both channels picks the stronger by rssi plus snr`() {
        val confirmed = ChannelMove.verdict(
            hits = listOf(hit(60, ours, -60, 10), hit(34, ours, -95, 2)),
            locatorId = ours, newChannel = 60, oldChannel = 34,
        )
        assertEquals(ChannelMove.Verdict.Confirmed, confirmed)

        val stayed = ChannelMove.verdict(
            hits = listOf(hit(60, ours, -95, 2), hit(34, ours, -60, 10)),
            locatorId = ours, newChannel = 60, oldChannel = 34,
        )
        assertEquals(ChannelMove.Verdict.LocatorStayed, stayed)
    }

    /** An artifact reading exactly as strong as the real channel separates nothing,
     *  and a tie must never be allowed to fire the revert. */
    @Test
    fun `an exact tie is no evidence`() {
        val v = ChannelMove.verdict(
            hits = listOf(hit(60, ours, -70, 5), hit(34, ours, -70, 5)),
            locatorId = ours, newChannel = 60, oldChannel = 34,
        )
        assertEquals(ChannelMove.Verdict.NoEvidence, v)
    }

    /**
     * The reason the probe is a census rather than a targeted run: it surfaces a
     * stranger on the new channel. That is worth showing the user, and it is not
     * evidence about where OUR locator went.
     */
    @Test
    fun `another locator on the new channel is not confirmation`() {
        val v = ChannelMove.verdict(
            hits = listOf(hit(60, theirs, -55, 11)),
            locatorId = ours, newChannel = 60, oldChannel = 34,
        )
        assertEquals(ChannelMove.Verdict.NoEvidence, v)
    }

    @Test
    fun `a stranger on the new channel does not mask our locator on the old one`() {
        val v = ChannelMove.verdict(
            hits = listOf(hit(60, theirs, -55, 11), hit(34, ours, -90, 1)),
            locatorId = ours, newChannel = 60, oldChannel = 34,
        )
        assertEquals(ChannelMove.Verdict.LocatorStayed, v)
    }

    /** With no id to attribute against, no hit can be evidence. */
    @Test
    fun `an unknown connected locator yields no evidence`() {
        assertEquals(
            ChannelMove.Verdict.NoEvidence,
            ChannelMove.verdict(listOf(hit(60, ours, -60, 10)), null, 60, 34),
        )
        assertEquals(
            ChannelMove.Verdict.NoEvidence,
            ChannelMove.verdict(listOf(hit(60, ours, -60, 10)), 0L, 60, 34),
        )
    }

    /** An unidentified frame (id 0) labels a channel and nothing else. */
    @Test
    fun `an unidentified hit is not attributed to us`() {
        val v = ChannelMove.verdict(
            hits = listOf(hit(60, 0L, -55, 11)),
            locatorId = ours, newChannel = 60, oldChannel = 34,
        )
        assertEquals(ChannelMove.Verdict.NoEvidence, v)
    }

    @Test
    fun `probe order puts the new channel first`() {
        assertEquals(listOf(60, 34), ChannelMove.probeChannels(60, 34))
    }

    @Test
    fun `probe channels are deduped`() {
        assertEquals(listOf(34), ChannelMove.probeChannels(34, 34))
    }
}
