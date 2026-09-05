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
        // one the receiver is still on. A duplicate would spend 1.4 s proving the
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
        // cancel — answering "stop" with a 90-second sweep is not reading the room.
        assertFalse(base.copy(running = true).canWiden)
        assertFalse(base.copy(wholeBand = true).canWiden)
        assertFalse(base.copy(status = LocatorSearch.Status.Cancelled).canWiden)
        assertFalse(base.copy(status = LocatorSearch.Status.RefusedArmed).canWiden)
    }

    private fun run(vararg hits: LocatorSearch.Hit) = LocatorSearch.Run(
        running = false, searched = 64, total = 64,
        status = LocatorSearch.Status.Done, wholeBand = true, hits = hits.toList(),
    )

    @Test fun `a second hit on a channel already reported is dropped`() {
        // Reported 2026-09-04: one marginal false hit on channel 0 shown as four
        // identical rows, while the only live locator was on 60. A dwell happens once
        // — the receiver holds a single hit slot, the first frame fills it and the
        // dwell then ends early — so a repeat is the same dwell arriving twice, not a
        // second locator.
        val first = LocatorSearch.Hit(0, ours, "Prometheus", -104, -12, false)
        val again = LocatorSearch.Hit(0, ours, "Prometheus", -104, -12, false)
        val r = run().withHit(first).withHit(again).withHit(again)
        assertEquals(listOf(first), r.hits)
    }

    @Test fun `the first report of a channel wins`() {
        // Two copies of one dwell carry the same reading, so there is nothing to
        // choose between them — but pinning it down keeps a late repeat from
        // rewriting a row the user is already reading.
        val first = LocatorSearch.Hit(0, ours, "Prometheus", -104, -12, false)
        val louder = LocatorSearch.Hit(0, ours, "Prometheus", -55, 9, false)
        assertEquals(listOf(first), run().withHit(first).withHit(louder).hits)
    }

    @Test fun `hits on different channels all survive`() {
        // The dedupe is per channel and nothing else. A census finding two rockets is
        // the answer the search exists to give.
        val a = LocatorSearch.Hit(12, ours, "Prometheus", -70, 5, false)
        val b = LocatorSearch.Hit(34, theirs, "Twist 0", -65, 7, false)
        assertEquals(listOf(a, b), run().withHit(a).withHit(b).hits)
    }

    @Test fun `four duplicates of one false hit cannot masquerade as a real channel`() {
        // The symptom exactly: without the dedupe these four share an id, so
        // suspectChannels groups them, flags three of the four rows as likely false
        // and leaves one reading as a genuine occupant of channel 0.
        val hit = LocatorSearch.Hit(0, ours, "Prometheus", -104, -12, false)
        var r = run()
        repeat(4) { r = r.withHit(hit) }
        assertEquals(1, r.hits.size)
        assertTrue(r.suspectChannels.isEmpty())
    }

    @Test fun `one locator on two channels flags all but the strongest`() {
        // The measured case: a locator on 57 also reported on 17, 8 MHz away, because
        // it was close enough to overload the front end.
        val r = run(
            LocatorSearch.Hit(17, ours, "Prometheus", -95, -7, false),
            LocatorSearch.Hit(57, ours, "Prometheus", -60, 9, false),
        )
        assertEquals(setOf(17), r.suspectChannels)
    }

    @Test fun `a locator on one channel is never suspect`() {
        val r = run(LocatorSearch.Hit(57, ours, "Prometheus", -60, 9, false))
        assertTrue(r.suspectChannels.isEmpty())
    }

    @Test fun `two different locators on two channels are both fine`() {
        // The census case. Grouping is per locator, so two rockets on two channels is
        // the normal answer and must not be flagged as a contradiction.
        val r = run(
            LocatorSearch.Hit(12, ours, "Prometheus", -70, 5, false),
            LocatorSearch.Hit(34, theirs, "Twist 0", -65, 7, false),
        )
        assertTrue(r.suspectChannels.isEmpty())
    }

    @Test fun `hits with no id are never grouped`() {
        // id 0 means the frame did not say who. Two of them cannot be known to be the
        // same locator, so neither can be called the other's stray.
        val r = run(
            LocatorSearch.Hit(12, 0L, "", -90, -5, false),
            LocatorSearch.Hit(34, 0L, "", -60, 8, false),
        )
        assertTrue(r.suspectChannels.isEmpty())
    }

    @Test fun `three channels leave exactly one unflagged`() {
        val r = run(
            LocatorSearch.Hit(5, ours, "Prometheus", -99, -9, false),
            LocatorSearch.Hit(17, ours, "Prometheus", -95, -7, false),
            LocatorSearch.Hit(57, ours, "Prometheus", -60, 9, false),
        )
        assertEquals(setOf(5, 17), r.suspectChannels)
    }

    @Test fun `ranking is rssi plus snr, so a strong noisy hit can lose`() {
        // Pins the ordering rule deliberately. Bench 2026-08-28 confirmed it against
        // hardware: the channel this rule flags is the one that disappears when the
        // locator is moved away, so it picks the real channel. This test is still where
        // the rule would change if an artifact were ever seen arriving STRONGER than
        // the true channel — not observed on that rig.
        val r = run(
            LocatorSearch.Hit(17, ours, "Prometheus", -50, -12, false),  // sum -62
            LocatorSearch.Hit(57, ours, "Prometheus", -70, 10, false),   // sum -60, wins
        )
        assertEquals(setOf(17), r.suspectChannels)
    }

    @Test fun `only the row on the receiver's own channel reads connected`() {
        // Bench 2026-08-28: one locator close to the receiver was reported on two
        // channels, both rows read Connected because both hits carry the same id, and
        // sitting on the false channel left no way to reach the real one.
        val onFalse = LocatorSearch.Hit(17, ours, "Prometheus", -95, -7, false)
        val onReal = LocatorSearch.Hit(57, ours, "Prometheus", -60, 9, false)

        // Receiver parked on the false channel: that row is where it is connected, and
        // the real channel must still offer a way to get there.
        assertTrue(onFalse.connectedOn(currentChannel = 17, connectedLocatorId = ours))
        assertFalse(onReal.connectedOn(currentChannel = 17, connectedLocatorId = ours))

        // And the other way round once it has moved.
        assertFalse(onFalse.connectedOn(currentChannel = 57, connectedLocatorId = ours))
        assertTrue(onReal.connectedOn(currentChannel = 57, connectedLocatorId = ours))
    }

    @Test fun `being tuned to the channel is not being connected`() {
        // An unknown locator: the receiver arrives on the channel while an ADR-0006
        // password challenge is still outstanding, and nothing is connected yet.
        val hit = LocatorSearch.Hit(57, theirs, "Borrowed", -60, 9, false)
        assertFalse(hit.connectedOn(currentChannel = 57, connectedLocatorId = null))
        assertFalse(hit.connectedOn(currentChannel = 57, connectedLocatorId = ours))
        assertTrue(hit.connectedOn(currentChannel = 57, connectedLocatorId = theirs))
    }

    @Test fun `a hit with no id is never connected`() {
        // id 0 is "the frame did not say who", which cannot match anything.
        val hit = LocatorSearch.Hit(57, 0L, "", -60, 9, false)
        assertFalse(hit.connectedOn(currentChannel = 57, connectedLocatorId = null))
        assertFalse(hit.connectedOn(currentChannel = 57, connectedLocatorId = 0L))
    }

    @Test fun progressFractionSurvivesAnEmptyRun() {
        // total is 0 until the first result arrives; a bare division would throw or
        // produce NaN and the progress bar would render garbage.
        assertEquals(0f, LocatorSearch.Run(running = true).fraction, 0.0001f)
    }
}
