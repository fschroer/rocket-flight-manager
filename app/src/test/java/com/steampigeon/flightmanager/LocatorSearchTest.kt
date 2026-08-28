package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.LocatorSearch
import com.steampigeon.flightmanager.data.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The candidate list, which is the whole reason this search is usable.
 *
 * Every channel in the list costs a full broadcast period of deafness, so the
 * tests here are mostly about what the list must NOT contain: duplicates, invalid
 * channels, or more entries than the firmware will read.
 */
class LocatorSearchTest {

    private val ours = 0x11111111L
    private val theirs = 0x22222222L

    @Test fun targetChannelIsSearchedFirst() {
        // The firmware stops on the first frame from the target, so the ordering is
        // the difference between one dwell and all of them.
        val c = LocatorSearch.candidates(
            currentChannel = 7,
            targetChannel = 21,
            knownChannels = listOf(3, 9),
        )
        assertEquals(21, c.first())
    }

    @Test fun otherLocatorsChannelsFollow() {
        val c = LocatorSearch.candidates(
            currentChannel = 7,
            targetChannel = 21,
            knownChannels = listOf(3, 9),
        )
        assertEquals(listOf(21, 3, 9, LocatorSearch.DEFAULT_CHANNEL, 7), c)
    }

    @Test fun defaultChannelIsAlwaysTried() {
        // A locator whose settings did not survive is on 0, and nothing else in the
        // list would ever point there (ADR-0025).
        val c = LocatorSearch.candidates(currentChannel = 40, knownChannels = listOf(12))
        assertTrue(LocatorSearch.DEFAULT_CHANNEL in c)
    }

    @Test fun currentChannelIsLast() {
        // We are already sitting here hearing nothing, so it is the least likely
        // answer — but not an impossible one, so it is included rather than dropped.
        val c = LocatorSearch.candidates(currentChannel = 40, knownChannels = listOf(12))
        assertEquals(40, c.last())
    }

    @Test fun duplicatesCollapse() {
        // The sources overlap constantly: a locator's last channel is usually the
        // one the receiver is still on. A duplicate would spend 1.2 s proving the
        // same thing twice.
        val c = LocatorSearch.candidates(
            currentChannel = 12,
            targetChannel = 12,
            knownChannels = listOf(12, 12),
            attemptedChannel = 12,
        )
        assertEquals(listOf(12, LocatorSearch.DEFAULT_CHANNEL), c)
    }

    @Test fun outOfRangeChannelsAreDropped() {
        val c = LocatorSearch.candidates(
            currentChannel = 5,
            targetChannel = 64,          // one past the top of the band
            knownChannels = listOf(-1, 63),
        )
        assertFalse(64 in c)
        assertFalse(-1 in c)
        assertTrue(63 in c)
    }

    @Test fun listIsCappedToWhatTheFirmwareReads() {
        // The firmware truncates a longer list, so a list built past the cap would
        // silently not be searched — the worst kind of miss, since the UI would have
        // promised those channels.
        val c = LocatorSearch.candidates(
            currentChannel = 5,
            knownChannels = (10..59).toList(),
        )
        assertEquals(Protocol.LOCATOR_SEARCH_MAX_CHANNELS, c.size)
    }

    @Test fun attemptedChannelIsTriedBeforeTheDefault() {
        // The half-landed channel move: the locator took the new channel and the
        // receiver did not, so the attempted one is a strong guess.
        val c = LocatorSearch.candidates(
            currentChannel = 4,
            knownChannels = emptyList(),
            attemptedChannel = 33,
        )
        assertEquals(listOf(33, LocatorSearch.DEFAULT_CHANNEL, 4), c)
    }

    @Test fun missedIsOnlyTrueForAShortRunThatFoundNothing() {
        val short = LocatorSearch.Run(
            running = false, searched = 4, total = 4,
            status = LocatorSearch.Status.Done, wholeBand = false,
        )
        assertTrue(short.missed)
        // Widening after a whole-band run would mean sweeping the band twice.
        assertFalse(short.copy(wholeBand = true).missed)
        // A refusal is not a miss: nothing was searched, so there is nothing to widen.
        assertFalse(short.copy(status = LocatorSearch.Status.RefusedArmed).missed)
        assertFalse(
            short.copy(hits = listOf(LocatorSearch.Hit(4, 1L, "x", -70, 8, false))).missed
        )
    }

    @Test fun `a targeted run that finds somebody else has still missed`() {
        // The case the feature exists for: hunting Prometheus while Twist 0 is audible
        // on the current channel. Counting hits calls that success and leaves the user
        // hunting a locator the app believes it already found.
        val run = LocatorSearch.Run(
            running = false, searched = 6, total = 6,
            status = LocatorSearch.Status.Done, wholeBand = false,
            targetLocatorId = ours,
            hits = listOf(LocatorSearch.Hit(34, theirs, "Twist 0", -60, 8, false)),
        )
        assertTrue(run.missed)
        assertTrue(run.canWiden)
    }

    @Test fun `a targeted run that finds the target has not missed`() {
        val run = LocatorSearch.Run(
            running = false, searched = 1, total = 6,
            status = LocatorSearch.Status.Done, wholeBand = false,
            targetLocatorId = ours,
            hits = listOf(LocatorSearch.Hit(48, ours, "Prometheus", -55, 8, false)),
        )
        assertFalse(run.missed)
        // Still widenable: finding it does not prove there is nothing else worth a look.
        assertTrue(run.canWiden)
    }

    @Test fun `an untargeted run that finds anything has not missed`() {
        val run = LocatorSearch.Run(
            running = false, searched = 6, total = 6,
            status = LocatorSearch.Status.Done, wholeBand = false,
            hits = listOf(LocatorSearch.Hit(34, theirs, "Twist 0", -60, 8, false)),
        )
        assertFalse(run.missed)
        assertTrue(run.canWiden)
    }

    @Test fun `widening is offered after any completed short run`() {
        val base = LocatorSearch.Run(
            running = false, searched = 6, total = 6,
            status = LocatorSearch.Status.Done, wholeBand = false,
        )
        assertTrue(base.canWiden)
        assertTrue(base.copy(hits = listOf(LocatorSearch.Hit(1, theirs, "x", -70, 8, false))).canWiden)
        // But not while it is still going, not after a whole-band run, and not after a
        // cancel — answering "stop" with an 80-second sweep is not reading the room.
        assertFalse(base.copy(running = true).canWiden)
        assertFalse(base.copy(wholeBand = true).canWiden)
        assertFalse(base.copy(status = LocatorSearch.Status.Cancelled).canWiden)
        assertFalse(base.copy(status = LocatorSearch.Status.RefusedArmed).canWiden)
    }

    @Test fun progressFractionSurvivesAnEmptyRun() {
        // total is 0 until the first result arrives; a bare division would throw or
        // produce NaN and the progress bar would render garbage.
        assertEquals(0f, LocatorSearch.Run(running = true).fraction, 0.0001f)
    }
}
