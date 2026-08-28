package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.ChannelSurvey
import com.steampigeon.flightmanager.data.ChannelSurvey.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ranking and, more importantly, the refusal to rank.
 *
 * Two bench findings drive most of this file:
 *
 * - A locator a few feet from the receiver saturates every channel, and a naive
 *   ranking would confidently recommend whichever one read lowest. The right
 *   answer is "move the transmitter", not "switch to channel 37".
 * - A locator is on air ~138 ms per second, so a short coarse dwell misses it
 *   roughly three times in four. A sweep once ranked the channel BOTH locators
 *   were using as the quietest in the band. Only channels given the long
 *   confirmation dwell may be suggested.
 */
class ChannelSurveyTest {

    /** A quiet band with one obviously busy channel. */
    private fun quietBandWithHotChannel(hot: Int, hotLevel: Int = -55): List<Int> =
        List(64) { if (it == hot) hotLevel else -115 - (it % 5) }

    @Test
    fun `quietest channel ranks first`() {
        val levels = MutableList(64) { -100 }
        levels[37] = -122
        levels[12] = -118
        val r = ChannelSurvey.analyze(Status.Ok, levels, homeChannel = 0, confirmedChannels = listOf(37, 12))
        assertEquals(37, r.ranked[0].channel)
        assertEquals(12, r.ranked[1].channel)
        assertFalse(r.allChannelsHot)
    }

    @Test
    fun `a known interferer surfaces as the loudest channel`() {
        val r = ChannelSurvey.analyze(
            Status.Ok, quietBandWithHotChannel(hot = 20), homeChannel = 0,
            confirmedChannels = listOf(1, 2, 3, 4, 5),
        )
        assertEquals(20, r.ranked.last().channel)
        assertTrue(r.suggestions.none { it.channel == 20 })
    }

    // ── Only confirmed channels may be suggested ─────────────────────────────────

    @Test
    fun `an unconfirmed channel is never suggested however quiet it looks`() {
        // Channel 0 reads quiet in the coarse pass -- which is exactly what happened
        // on the bench with two locators sitting on it, because the 12 ms dwell
        // landed between their 1 Hz bursts. It was not confirmed, so it must not be
        // offered no matter how good the coarse number looks.
        val levels = MutableList(64) { -100 }
        levels[0] = -125
        val r = ChannelSurvey.analyze(
            Status.Ok, levels, homeChannel = 0, confirmedChannels = listOf(9, 17),
        )
        assertEquals(0, r.ranked.first().channel)          // still ranks first on coarse data
        assertTrue(r.suggestions.none { it.channel == 0 }) // but is not recommended
        assertEquals(listOf(9, 17), r.suggestions.map { it.channel })
    }

    @Test
    fun `no confirmed channels means no suggestions`() {
        val r = ChannelSurvey.analyze(Status.Ok, List(64) { -120 }, homeChannel = 0)
        assertTrue(r.ranked.isNotEmpty())
        assertTrue(r.suggestions.isEmpty())
    }

    @Test
    fun `confirmed channels are ordered by their confirmed level`() {
        val levels = MutableList(64) { -100 }
        levels[5] = -80    // confirmation found this one busy
        levels[9] = -120
        levels[17] = -110
        val r = ChannelSurvey.analyze(
            Status.Ok, levels, homeChannel = 0, confirmedChannels = listOf(5, 9, 17),
        )
        assertEquals(listOf(9, 17, 5), r.suggestions.map { it.channel })
    }

    @Test
    fun `confirmed channels outside the level range are ignored`() {
        val r = ChannelSurvey.analyze(
            Status.Ok, List(64) { -120 }, homeChannel = 0, confirmedChannels = listOf(3, 99, -1),
        )
        assertEquals(listOf(3), r.suggestions.map { it.channel })
    }

    // ── All channels hot ─────────────────────────────────────────────────────────

    @Test
    fun `all channels hot still ranks, because the ranking is still correct`() {
        // A transmitter a few feet away puts a broadband floor on every channel, so
        // everything reads loud. That is worth warning about, but the ordering
        // underneath remains valid and the user still has to pick something.
        val levels = List(64) { -60 - (it % 3) }
        val r = ChannelSurvey.analyze(
            Status.Ok, levels, homeChannel = 0, confirmedChannels = listOf(0, 1, 2, 3, 4),
        )
        assertTrue(r.allChannelsHot)
        assertTrue("warning must not remove the user's ability to choose",
            r.suggestions.isNotEmpty())
    }

    // ── The two bench traces, kept as regression cases ───────────────────────────
    //
    // Both have every confirmed channel above ALL_HOT_DBM. They mean opposite
    // things, and the spread is what separates them.

    @Test
    fun `a flat elevated reading is a uniform floor, not a channel problem`() {
        // Real trace, locator several feet from the receiver, home channel 22.
        // Coarse read -115 everywhere; confirmation caught the 1 Hz burst on every
        // channel because the dwell exceeds one broadcast period. 3 dB of spread —
        // the transmitter is bleeding across the band and favors nothing.
        val levels = MutableList(64) { -115 }
        levels[45] = -71; levels[58] = -74; levels[62] = -72
        levels[2] = -71;  levels[4] = -71
        val r = ChannelSurvey.analyze(
            Status.Ok, levels, homeChannel = 22, confirmedChannels = listOf(45, 58, 62, 2, 4),
        )
        assertTrue(r.allChannelsHot)
        assertTrue("flat + elevated must read as a uniform floor", r.uniformFloor)
        assertTrue("channels are equivalent, so still offer them", r.suggestions.isNotEmpty())
    }

    @Test
    fun `an elevated reading with structure is not a uniform floor`() {
        // The earlier trace: 19 dB of spread, because channels 1 and 2 sit beside
        // genuinely occupied channel 0. Here the ranking means something.
        val levels = MutableList(64) { -71 }
        levels[1] = -52; levels[2] = -57
        val r = ChannelSurvey.analyze(
            Status.Ok, levels, homeChannel = 0, confirmedChannels = listOf(1, 2, 22, 23, 24),
        )
        assertTrue(r.allChannelsHot)
        assertFalse("structure means real traffic, not a broadband floor", r.uniformFloor)
    }

    @Test
    fun `uniform floor needs the levels to be elevated, not merely flat`() {
        // A genuinely quiet band is flat too. Flatness alone must not trigger it.
        val r = ChannelSurvey.analyze(
            Status.Ok, List(64) { -115 }, homeChannel = 0, confirmedChannels = listOf(1, 2, 3),
        )
        assertFalse(r.allChannelsHot)
        assertFalse(r.uniformFloor)
    }

    @Test
    fun `the near-field bench case ranks the quietest channels above the adjacent ones`() {
        // Real trace, spare locator ~2 ft from the receiver, both locators on ch 0.
        // -71 was the broadband near-field floor; ch 1 and 2 read louder because they
        // are adjacent to the occupied channel and carry genuine leakage. Channels at
        // the bare floor carry no traffic of their own and are the right answer.
        val levels = MutableList(64) { -71 }
        levels[1] = -52
        levels[2] = -57
        val r = ChannelSurvey.analyze(
            Status.Ok, levels, homeChannel = 0, confirmedChannels = listOf(1, 2, 22, 23, 24),
        )
        assertTrue(r.allChannelsHot)
        assertEquals(listOf(22, 23, 24, 2, 1), r.suggestions.map { it.channel })
    }

    @Test
    fun `all-hot is judged on confirmed channels only`() {
        // Coarse says channel 0 is quiet; confirmation says every verified channel is
        // loud. The verdict must follow the evidence that was actually gathered.
        val levels = MutableList(64) { -60 }
        levels[0] = -125
        val r = ChannelSurvey.analyze(
            Status.Ok, levels, homeChannel = 0, confirmedChannels = listOf(1, 2, 3),
        )
        assertTrue(r.allChannelsHot)
        assertTrue(r.suggestions.none { it.channel == 0 })
    }

    @Test
    fun `all-hot boundary is inclusive`() {
        val confirmed = listOf(0, 1, 2)
        val atThreshold = List(64) { ChannelSurvey.ALL_HOT_DBM }
        assertTrue(ChannelSurvey.analyze(Status.Ok, atThreshold, 0, confirmed).allChannelsHot)
        val justBelow = List(64) { ChannelSurvey.ALL_HOT_DBM - 1 }
        assertFalse(ChannelSurvey.analyze(Status.Ok, justBelow, 0, confirmed).allChannelsHot)
    }

    // ── Refusals and edge cases ──────────────────────────────────────────────────

    @Test
    fun `refusal produces no ranking`() {
        for (s in listOf(Status.RefusedArmed, Status.RefusedBusy, Status.Unknown)) {
            val r = ChannelSurvey.analyze(s, List(64) { -120 }, homeChannel = 3, confirmedChannels = listOf(1))
            assertTrue(r.ranked.isEmpty())
            assertTrue(r.suggestions.isEmpty())
            assertFalse(r.allChannelsHot)
        }
    }

    @Test
    fun `empty levels do not crash the ranking`() {
        val r = ChannelSurvey.analyze(Status.Ok, emptyList(), homeChannel = 0, confirmedChannels = listOf(1))
        assertTrue(r.ranked.isEmpty())
        assertTrue(r.suggestions.isEmpty())
    }

    @Test
    fun `home channel rank is reported one-based`() {
        val levels = MutableList(64) { -100 }
        levels[5] = -125
        levels[9] = -120
        val r = ChannelSurvey.analyze(Status.Ok, levels, homeChannel = 9)
        assertEquals(2, r.homeRank)
    }

    @Test
    fun `ties break on channel number so repeat sweeps agree`() {
        val flat = List(64) { -110 }
        val a = ChannelSurvey.analyze(Status.Ok, flat, 0).ranked.map { it.channel }
        val b = ChannelSurvey.analyze(Status.Ok, flat, 0).ranked.map { it.channel }
        assertEquals(a, b)
        assertEquals(0, a.first())
    }

    @Test
    fun `suggestions are capped at the confirm count`() {
        val levels = List(64) { -100 - it }
        val r = ChannelSurvey.analyze(
            Status.Ok, levels, homeChannel = 0,
            confirmedChannels = listOf(63, 62, 61, 60, 59),
        )
        assertEquals(ChannelSurvey.SUGGESTION_COUNT, r.suggestions.size)
        assertEquals(63, r.suggestions.first().channel)
        assertTrue(r.suggestions.zipWithNext().all { (a, b) -> a.level <= b.level })
    }

    // ── Decoded frames: occupancy as fact rather than inference (#33) ────────────
    //
    // RSSI cannot separate "a locator is using this channel" from "a locator near me
    // is loud on every channel" — a near-field locator raises them all at once. A
    // frame that DECODES on the dwelt channel had to be transmitted on it, because
    // off-channel bleed does not survive the demodulator.

    @Test
    fun `a channel with a locator on it is never suggested, however quiet it reads`() {
        // The acceptance case: a known interferer parked on channel 40. Its level can
        // sit at the same near-field floor as everything else and it must still be
        // excluded, because the frames prove what the level cannot.
        val levels = MutableList(64) { -71 }
        val r = ChannelSurvey.analyze(
            Status.Ok, levels, homeChannel = 0,
            confirmedChannels = listOf(40, 12, 19),
            confirmedFrames   = listOf(2, 0, 0),
        )
        assertTrue(r.suggestions.none { it.channel == 40 })
        assertEquals(listOf(12, 19), r.suggestions.map { it.channel })
        assertEquals(listOf(40), r.occupied.map { it.channel })
    }

    @Test
    fun `frames outrank a quiet level`() {
        // Channel 5 is the quietest reading in the sweep and still occupied.
        val levels = MutableList(64) { -100 }
        levels[5] = -125
        val r = ChannelSurvey.analyze(
            Status.Ok, levels, homeChannel = 0,
            confirmedChannels = listOf(5, 9),
            confirmedFrames   = listOf(1, 0),
        )
        assertEquals(listOf(9), r.suggestions.map { it.channel })
    }

    @Test
    fun `zero frames does not by itself make a channel good`() {
        // Frames only ever EXCLUDE. A silent channel still has to survive the level
        // checks, because a non-locator emitter decodes nothing and is invisible here.
        val levels = List(64) { -60 }
        val r = ChannelSurvey.analyze(
            Status.Ok, levels, homeChannel = 0,
            confirmedChannels = listOf(1, 2, 3),
            confirmedFrames   = listOf(0, 0, 0),
        )
        assertTrue(r.allChannelsHot)
        assertTrue(r.occupied.isEmpty())
    }

    @Test
    fun `frames are matched to their channel by position, not by value`() {
        val levels = MutableList(64) { -110 }
        val r = ChannelSurvey.analyze(
            Status.Ok, levels, homeChannel = 0,
            confirmedChannels = listOf(30, 31, 32),
            confirmedFrames   = listOf(0, 4, 0),
        )
        assertEquals(listOf(31), r.occupied.map { it.channel })
        assertEquals(4, r.occupied.single().frames)
    }

    @Test
    fun `the home channel is reported separately, not as another locator`() {
        // Home is always confirmed and normally decodes frames -- our own locator
        // transmits there. Calling that "another locator" would be plainly wrong.
        val r = ChannelSurvey.analyze(
            Status.Ok, List(64) { -115 }, homeChannel = 40,
            confirmedChannels = listOf(40, 11, 63),
            confirmedFrames   = listOf(3, 0, 0),
        )
        assertTrue(r.homeChannelInUse)
        assertTrue("home must not appear as a foreign locator", r.occupied.isEmpty())
        assertTrue("nor be suggested as a clean channel", r.suggestions.none { it.channel == 40 })
        assertEquals(listOf(11, 63), r.suggestions.map { it.channel })
    }

    @Test
    fun `a quiet home channel is not reported as in use`() {
        val r = ChannelSurvey.analyze(
            Status.Ok, List(64) { -115 }, homeChannel = 40,
            confirmedChannels = listOf(40, 11),
            confirmedFrames   = listOf(0, 0),
        )
        assertFalse(r.homeChannelInUse)
        // Ordered by (level, channel), so at equal levels the lower channel leads;
        // home gets no special ranking, only a guaranteed confirm slot.
        assertEquals(listOf(11, 40), r.suggestions.map { it.channel })
    }

    @Test
    fun `a missing frame list degrades to no exclusions`() {
        // An older receiver sends no frame counts. Suggestions must still work.
        val r = ChannelSurvey.analyze(
            Status.Ok, List(64) { -120 }, homeChannel = 0, confirmedChannels = listOf(1, 2),
        )
        assertEquals(listOf(1, 2), r.suggestions.map { it.channel })
        assertTrue(r.occupied.isEmpty())
    }

    @Test
    fun `the occupant of a confirmed channel is carried alongside the count`() {
        // Identity rides index-aligned with the confirmed list, and it is what turns
        // "channel 11 is busy" into "your other rocket is on channel 11".
        val r = ChannelSurvey.analyze(
            Status.Ok, List(64) { -110 }, homeChannel = 40,
            confirmedChannels   = listOf(40, 11, 22),
            confirmedFrames     = listOf(0, 3, 1),
            confirmedLocatorIds = listOf(0L, 0x11223344L, 0L),
        )
        assertEquals(0x11223344L, r.occupied.first { it.channel == 11 }.locatorId)
        // Occupied with no id: a frame type that carries none. Still excluded from
        // the suggestions, because the count is what decides that, not the id.
        assertEquals(0L, r.occupied.first { it.channel == 22 }.locatorId)
        assertFalse(22 in r.suggestions.map { it.channel })
    }

    @Test
    fun `a missing id list leaves the ids zero`() {
        // A receiver flashed before identity was added ends the frame early. The
        // sweep must still rank and exclude exactly as it did before.
        val r = ChannelSurvey.analyze(
            Status.Ok, List(64) { -110 }, homeChannel = 0,
            confirmedChannels = listOf(1, 2),
            confirmedFrames   = listOf(0, 2),
        )
        assertEquals(0L, r.confirmed.first { it.channel == 2 }.locatorId)
        assertEquals(listOf(1), r.suggestions.map { it.channel })
    }

    @Test
    fun `status decodes from the wire byte`() {
        assertEquals(Status.Ok, Status.fromByte(0))
        assertEquals(Status.RefusedArmed, Status.fromByte(1))
        assertEquals(Status.RefusedBusy, Status.fromByte(2))
        // Added when a queued locator command was given the right to end a sweep.
        // One more value in an existing byte, so nothing resized; an older receiver
        // simply never sends it.
        assertEquals(Status.Cancelled, Status.fromByte(3))
        assertEquals(Status.Unknown, Status.fromByte(99))
    }
}
