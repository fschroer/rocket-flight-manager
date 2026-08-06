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
 * `all channels hot yields no suggestions` is the one that matters: a locator a
 * few feet from the receiver saturates every channel, and a naive ranking would
 * confidently recommend whichever one read lowest. The right answer is "move the
 * transmitter", not "switch to channel 37".
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
        val r = ChannelSurvey.analyze(Status.Ok, levels, homeChannel = 0)
        assertEquals(37, r.ranked[0].channel)
        assertEquals(12, r.ranked[1].channel)
        assertFalse(r.allChannelsHot)
    }

    @Test
    fun `a known interferer surfaces as the loudest channel`() {
        val r = ChannelSurvey.analyze(Status.Ok, quietBandWithHotChannel(hot = 20), homeChannel = 0)
        assertEquals(20, r.ranked.last().channel)
        assertTrue(r.suggestions.none { it.channel == 20 })
    }

    @Test
    fun `all channels hot yields no suggestions`() {
        // Every channel loud — a transmitter next to the receiver, not a busy band.
        val levels = List(64) { -60 - (it % 3) }
        val r = ChannelSurvey.analyze(Status.Ok, levels, homeChannel = 0)
        assertTrue(r.allChannelsHot)
        assertTrue("must not recommend a channel when everything is saturated",
            r.suggestions.isEmpty())
    }

    @Test
    fun `all-hot boundary is inclusive`() {
        val atThreshold = List(64) { ChannelSurvey.ALL_HOT_DBM }
        assertTrue(ChannelSurvey.analyze(Status.Ok, atThreshold, 0).allChannelsHot)
        val justBelow = List(64) { ChannelSurvey.ALL_HOT_DBM - 1 }
        assertFalse(ChannelSurvey.analyze(Status.Ok, justBelow, 0).allChannelsHot)
    }

    @Test
    fun `refusal produces no ranking`() {
        for (s in listOf(Status.RefusedArmed, Status.RefusedBusy, Status.Unknown)) {
            val r = ChannelSurvey.analyze(s, List(64) { -120 }, homeChannel = 3)
            assertTrue(r.ranked.isEmpty())
            assertTrue(r.suggestions.isEmpty())
            assertFalse(r.allChannelsHot)
        }
    }

    @Test
    fun `empty levels do not crash the ranking`() {
        val r = ChannelSurvey.analyze(Status.Ok, emptyList(), homeChannel = 0)
        assertTrue(r.ranked.isEmpty())
        assertTrue(r.suggestions.isEmpty())
    }

    @Test
    fun `home channel rank is reported one-based`() {
        val levels = MutableList(64) { -100 }
        levels[5] = -125   // quietest
        levels[9] = -120   // second
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
    fun `suggestions are capped and ordered quietest first`() {
        val levels = List(64) { -100 - it }   // channel 63 quietest
        val r = ChannelSurvey.analyze(Status.Ok, levels, homeChannel = 0)
        assertEquals(ChannelSurvey.SUGGESTION_COUNT, r.suggestions.size)
        assertEquals(63, r.suggestions.first().channel)
        assertTrue(r.suggestions.zipWithNext().all { (a, b) -> a.level <= b.level })
    }

    @Test
    fun `status decodes from the wire byte`() {
        assertEquals(Status.Ok, Status.fromByte(0))
        assertEquals(Status.RefusedArmed, Status.fromByte(1))
        assertEquals(Status.RefusedBusy, Status.fromByte(2))
        assertEquals(Status.Unknown, Status.fromByte(99))
    }
}
