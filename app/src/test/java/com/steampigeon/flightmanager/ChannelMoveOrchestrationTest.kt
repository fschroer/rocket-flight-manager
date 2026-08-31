package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.ChannelMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three decision points around [ChannelMove.verdict] that each shipped a defect
 * during ADR-0011's bench validation (#20, 2026-08-30).
 *
 * Every one of those defects was found by hand on a bench because the logic lived
 * inside a coroutine with a `BluetoothService` in it and nothing could reach it. The
 * decisions are lifted out here; the orchestration around them is still uncovered,
 * and that remains the honest gap.
 */
class ChannelMoveOrchestrationTest {

    // ---------------------------------------------------------------- action

    /** Reverting on anything but evidence is the defect the amendment removes. */
    @Test
    fun `only an evidenced LocatorStayed moves the receiver`() {
        assertEquals(ChannelMove.Action.Revert, ChannelMove.action(ChannelMove.Verdict.LocatorStayed))
        assertEquals(ChannelMove.Action.Succeed, ChannelMove.action(ChannelMove.Verdict.Confirmed))
        assertEquals(ChannelMove.Action.Stand, ChannelMove.action(ChannelMove.Verdict.NoEvidence))
        assertEquals(ChannelMove.Action.Stand, ChannelMove.action(ChannelMove.Verdict.NotChecked))
    }

    /** A refusal is the app failing to look. It must never authorise a move. */
    @Test
    fun `a refused probe never moves anything`() {
        assertEquals(ChannelMove.Action.Stand, ChannelMove.action(ChannelMove.Verdict.NotChecked))
    }

    // ------------------------------------------------------- confirmDeadline

    private val started = 1_000_000L
    private val window = 5_000L
    private val hard = started + 2 * window

    @Test
    fun `no receipt leaves the deadline alone`() {
        val d = started + window
        assertEquals(d, ChannelMove.confirmDeadline(started, d, 0L, window, hard))
    }

    @Test
    fun `a receipt re-bases the window from when the command went on air`() {
        val d = started + window
        val receipt = started + 3_000L
        assertEquals(receipt + window, ChannelMove.confirmDeadline(started, d, receipt, window, hard))
    }

    /**
     * The hang. `ReceiverInfo` arrives every 2 s from the channel watch while the
     * locator is silent; each one re-based by 5 s, and 2 s < 5 s meant the window
     * never closed — the probe never ran and the locator was lost. Latching is the
     * primary guard; this is the one that holds if the latch ever fails.
     */
    @Test
    fun `repeated receipts cannot push the deadline past the hard ceiling`() {
        var d = started + window
        var t = started
        repeat(50) {
            t += 2_000L                      // the 2 s ReceiverInfo poll
            d = ChannelMove.confirmDeadline(started, d, t, window, hard)
        }
        assertEquals(hard, d)
        assertTrue("the window must be able to close", d <= hard)
    }

    @Test
    fun `a receipt never shortens a deadline already reached`() {
        val d = started + 2 * window
        val receipt = started + 100L
        assertEquals(d, ChannelMove.confirmDeadline(started, d, receipt, window, hard))
    }

    /** A receipt older than the wait belongs to a previous move. */
    @Test
    fun `a stale receipt is ignored`() {
        val d = started + window
        assertEquals(d, ChannelMove.confirmDeadline(started, d, started - 1, window, hard))
    }

    // -------------------------------------------------------------- relinked

    private val asked = 500_000L

    @Test
    fun `relink needs a frame admitted after the revert was asked for`() {
        assertTrue(ChannelMove.relinked(12, 12, 12, asked + 1, asked))
    }

    /**
     * The stale-reading bug. Both channel readings are updated only by a relayed
     * `PreLaunchData`, so after a move whose confirmation never arrived they were
     * both still reading the old channel — and the old test passed on its first
     * 100 ms poll having verified nothing.
     */
    @Test
    fun `matching channels alone are not a relink`() {
        assertFalse(ChannelMove.relinked(12, 12, 12, asked, asked))
        assertFalse(ChannelMove.relinked(12, 12, 12, asked - 5_000L, asked))
        assertFalse(ChannelMove.relinked(12, 12, 12, 0L, asked))
    }

    @Test
    fun `a fresh frame on the wrong channel is not a relink`() {
        assertFalse(ChannelMove.relinked(60, 12, 12, asked + 1, asked))
        assertFalse(ChannelMove.relinked(12, 60, 12, asked + 1, asked))
    }

    // --------------------------------------------------------------- message

    /**
     * Nothing moved: the forward never transmitted, so the receiver never left. A
     * much smaller problem than a stranded locator, and it must not be described as
     * one.
     */
    @Test
    fun `no evidence with the receiver still on the old channel reads as nothing moved`() {
        assertEquals(
            ChannelMove.Message.NothingMoved,
            ChannelMove.message(ChannelMove.Verdict.NoEvidence, attemptedChannel = 60, receiverChannel = 34),
        )
    }

    @Test
    fun `no evidence with the receiver on the new channel is unresolved`() {
        assertEquals(
            ChannelMove.Message.Unresolved,
            ChannelMove.message(ChannelMove.Verdict.NoEvidence, attemptedChannel = 60, receiverChannel = 60),
        )
    }

    @Test
    fun `a refusal says it could not check, whatever the channels read`() {
        assertEquals(
            ChannelMove.Message.NotChecked,
            ChannelMove.message(ChannelMove.Verdict.NotChecked, attemptedChannel = 60, receiverChannel = 34),
        )
        assertEquals(
            ChannelMove.Message.NotChecked,
            ChannelMove.message(ChannelMove.Verdict.NotChecked, attemptedChannel = 60, receiverChannel = 60),
        )
    }

    @Test
    fun `an evidenced revert says the locator was left on its previous channel`() {
        assertEquals(
            ChannelMove.Message.LeftOnPrevious,
            ChannelMove.message(ChannelMove.Verdict.LocatorStayed, attemptedChannel = 60, receiverChannel = 34),
        )
    }

    /** A failure with no probe recorded at all — a send failure, say. */
    @Test
    fun `a null verdict falls back to the previous-channel wording`() {
        assertEquals(
            ChannelMove.Message.LeftOnPrevious,
            ChannelMove.message(null, attemptedChannel = 60, receiverChannel = 34),
        )
    }

    /**
     * The rule the three message defects all broke: **no sentence may claim the
     * receiver is somewhere the app has not read.** Every message that names a
     * channel must name the one actually reported, so the only verdict allowed to
     * differ between "aimed at 60, sitting on 34" and "aimed at 60, sitting on 60"
     * is the one whose whole job is telling those apart.
     */
    @Test
    fun `only NoEvidence changes its wording with where the receiver actually is`() {
        val verdicts = listOf(
            ChannelMove.Verdict.Confirmed,
            ChannelMove.Verdict.LocatorStayed,
            ChannelMove.Verdict.NotChecked,
        )
        for (v in verdicts) {
            assertEquals(
                "$v must not depend on the receiver's channel",
                ChannelMove.message(v, 60, 34),
                ChannelMove.message(v, 60, 60),
            )
        }
        assertTrue(
            ChannelMove.message(ChannelMove.Verdict.NoEvidence, 60, 34) !=
                    ChannelMove.message(ChannelMove.Verdict.NoEvidence, 60, 60)
        )
    }
}
