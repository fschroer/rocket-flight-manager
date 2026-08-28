package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.ChannelOccupancy
import com.steampigeon.flightmanager.data.ChannelSurvey
import com.steampigeon.flightmanager.data.ChannelSurvey.Status
import com.steampigeon.flightmanager.data.LocatorSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Who else is on this channel" — with the emphasis on **else**.
 *
 * The first version of this shipped a warning that fired on the channel the user's
 * own locator was already using, telling them that staying put would put two
 * locators on one channel. It was right about the occupancy and wrong about
 * everything that mattered.
 */
class ChannelOccupancyTest {

    private val ours = 0x11111111L
    private val theirs = 0x22222222L

    private fun survey(vararg occupants: Pair<Int, Long>, home: Int) =
        ChannelSurvey.analyze(
            Status.Ok, List(64) { -110 }, homeChannel = home,
            confirmedChannels = occupants.map { it.first },
            confirmedFrames = occupants.map { 2 },
            confirmedLocatorIds = occupants.map { it.second },
        )

    @Test fun `our own locator on our own channel is not an occupant`() {
        // The reported bug. A scan run while connected always finds us on our own
        // channel — that is the scan working, not a conflict.
        val r = survey(34 to ours, home = 34)
        assertNull(
            ChannelOccupancy.occupantOf(
                34, r, null, excludeLocatorId = ours,
                labelOf = { if (it == ours) "Twist 0" else null },
            )
        )
    }

    @Test fun `a different locator on our channel IS an occupant`() {
        // Same channel, different rocket: somebody really is sharing it with us, and
        // ADR-0019's home-channel exclusion must not swallow that.
        val r = survey(34 to theirs, home = 34)
        assertEquals(
            "Prometheus",
            ChannelOccupancy.occupantOf(
                34, r, null, excludeLocatorId = ours,
                labelOf = { if (it == theirs) "Prometheus" else null },
            )
        )
    }

    @Test fun `a locator on another channel is reported`() {
        val r = survey(34 to ours, 12 to theirs, home = 34)
        assertEquals(
            "Prometheus",
            ChannelOccupancy.occupantOf(
                12, r, null, excludeLocatorId = ours,
                labelOf = { if (it == theirs) "Prometheus" else null },
            )
        )
    }

    @Test fun `a search hit outranks the survey`() {
        // A search went looking for exactly this, and did so more recently.
        val r = survey(12 to theirs, home = 34)
        val search = LocatorSearch.Run(
            running = false, status = LocatorSearch.Status.Done,
            hits = listOf(LocatorSearch.Hit(12, 0x33333333L, "Testy McTestface", -60, 8, false)),
        )
        assertEquals(
            "Testy McTestface",
            ChannelOccupancy.occupantOf(12, r, search, excludeLocatorId = ours)
        )
    }

    @Test fun `a search hit from ourselves is excluded too`() {
        // Not only the survey path: a search run while connected finds us as readily.
        val search = LocatorSearch.Run(
            running = false, status = LocatorSearch.Status.Done,
            hits = listOf(LocatorSearch.Hit(34, ours, "Twist 0", -50, 8, false)),
        )
        assertNull(ChannelOccupancy.occupantOf(34, null, search, excludeLocatorId = ours))
    }

    @Test fun `an unknown locator falls back to its id`() {
        val r = survey(12 to theirs, home = 34)
        assertEquals(
            "22222222",
            ChannelOccupancy.occupantOf(12, r, null, excludeLocatorId = ours)
        )
    }

    @Test fun `an occupied channel with no id reports nothing rather than zero`() {
        // Occupancy without identity: a frame type that carries no locator_id. The
        // channel is still occupied — the survey excludes it from suggestions — but
        // there is no name to put on it, and "00000000" would be a lie.
        val r = survey(12 to 0L, home = 34)
        assertNull(ChannelOccupancy.occupantOf(12, r, null, excludeLocatorId = ours))
    }

    @Test fun `an unscanned channel is unknown, not free`() {
        assertNull(ChannelOccupancy.occupantOf(7, null, null, excludeLocatorId = ours))
    }
}
