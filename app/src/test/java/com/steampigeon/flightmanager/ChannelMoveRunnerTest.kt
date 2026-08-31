package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.ChannelMove
import com.steampigeon.flightmanager.data.ChannelMoveRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **sequence** an unconfirmed channel move is resolved by — how many times it
 * looks, what it is allowed to move, and in what order.
 *
 * Every defect this suite pins was found by hand on a bench during ADR-0011's
 * validation (#20), because the sequence lived inside a coroutine holding a
 * `BluetoothService` and nothing could reach it. The decisions it calls are pinned
 * separately in `ChannelMoveTest` / `ChannelMoveOrchestrationTest`.
 */
class ChannelMoveRunnerTest {

    private val NEW = 60
    private val OLD = 34
    private val RETRY_MS = 6_000L

    /** Scripted probe results, and a log of everything the runner did. */
    private class FakeOps(
        vararg probes: ChannelMoveRunner.ProbeRun?,
        val relinks: Boolean = true,
        val resends: Boolean = true,
        val confirms: Boolean = false,
        val busy: Boolean = false,
    ) : ChannelMoveRunner.Ops {
        private val queue = ArrayDeque(probes.toList())
        val log = mutableListOf<String>()
        val verdicts = mutableListOf<ChannelMove.Verdict>()
        var probeCount = 0; private set
        var now = 1_000L

        override fun probeInProgress(): Boolean = busy

        override suspend fun runProbe(newChannel: Int, oldChannel: Int): ChannelMoveRunner.ProbeRun? {
            probeCount++
            log += "probe($newChannel,$oldChannel)"
            // Running dry means the script was too short for the sequence under test.
            return if (queue.isEmpty()) error("unscripted probe #$probeCount") else queue.removeFirst()
        }

        override suspend fun pause(ms: Long) { log += "pause($ms)"; now += ms }
        override fun nowMs(): Long = now
        override suspend fun pointReceiverAt(channel: Int) { log += "point($channel)" }
        override suspend fun awaitRelink(oldChannel: Int, sinceMs: Long): Boolean {
            log += "relink($oldChannel,since=$sinceMs)"
            return relinks
        }
        override suspend fun resendLocatorConfig(): Boolean { log += "resend"; return resends }
        override suspend fun awaitConfirmation(): Boolean { log += "confirm"; return confirms }
        override fun onVerdict(verdict: ChannelMove.Verdict) { verdicts += verdict }
    }

    private fun done(v: ChannelMove.Verdict) = ChannelMoveRunner.ProbeRun(completed = true, verdict = v)
    private fun refused() = ChannelMoveRunner.ProbeRun(completed = false, verdict = ChannelMove.Verdict.NoEvidence)

    private fun run(ops: FakeOps) = runBlocking {
        ChannelMoveRunner(ops, RETRY_MS).resolve(NEW, OLD)
    }

    // ------------------------------------------------------------- happy paths

    @Test
    fun `a confirmed probe succeeds and moves nothing`() {
        val ops = FakeOps(done(ChannelMove.Verdict.Confirmed))
        assertTrue(run(ops))
        assertEquals(listOf("probe($NEW,$OLD)"), ops.log)
    }

    @Test
    fun `an evidenced stay reverts, retries, and succeeds`() {
        val ops = FakeOps(done(ChannelMove.Verdict.LocatorStayed), confirms = true)
        assertTrue(run(ops))
        assertEquals(
            listOf("probe($NEW,$OLD)", "point($OLD)", "relink($OLD,since=1000)", "resend", "confirm"),
            ops.log,
        )
    }

    // -------------------------------------------------- silence is asked twice

    /** ADR-0029: zero frames proves nothing. One 1.4 s dwell can miss a 1 Hz burst. */
    @Test
    fun `no evidence is probed a second time before it is accepted`() {
        val ops = FakeOps(done(ChannelMove.Verdict.NoEvidence), done(ChannelMove.Verdict.NoEvidence))
        assertFalse(run(ops))
        assertEquals(2, ops.probeCount)
        assertEquals(listOf(ChannelMove.Verdict.NoEvidence), ops.verdicts)
    }

    @Test
    fun `a second look can still find the locator`() {
        val ops = FakeOps(done(ChannelMove.Verdict.NoEvidence), done(ChannelMove.Verdict.Confirmed))
        assertTrue(run(ops))
        assertEquals(2, ops.probeCount)
    }

    @Test
    fun `no evidence never moves the receiver`() {
        val ops = FakeOps(done(ChannelMove.Verdict.NoEvidence), done(ChannelMove.Verdict.NoEvidence))
        assertFalse(run(ops))
        assertFalse("nothing may be moved on no evidence", ops.log.any { it.startsWith("point") })
    }

    // ------------------------------------------------------ refusals are not silence

    /**
     * The move's own queued `LocatorCfgChgRequest` blocks the search that would explain
     * why it could not be delivered, until the receiver's stale-drop clears it.
     */
    @Test
    fun `a refused probe is re-asked after the stale-drop delay`() {
        val ops = FakeOps(refused(), done(ChannelMove.Verdict.LocatorStayed), confirms = true)
        assertTrue(run(ops))
        assertEquals(
            listOf(
                "probe($NEW,$OLD)", "pause($RETRY_MS)", "probe($NEW,$OLD)",
                "point($OLD)", "relink($OLD,since=${1_000L + RETRY_MS})", "resend", "confirm",
            ),
            ops.log,
        )
    }

    /** Twice declined is an answer about the app, not about the locator. */
    @Test
    fun `two refusals give NotChecked and are not asked a third time`() {
        val ops = FakeOps(refused(), refused())
        assertFalse(run(ops))
        assertEquals(2, ops.probeCount)
        assertEquals(listOf(ChannelMove.Verdict.NotChecked), ops.verdicts)
        assertFalse(ops.log.any { it.startsWith("point") })
    }

    /** NotChecked must not inherit NoEvidence's second look — that would be a third ask. */
    @Test
    fun `NotChecked is not re-probed the way NoEvidence is`() {
        val ops = FakeOps(refused(), refused())
        run(ops)
        assertEquals("exactly one refusal retry, no NoEvidence re-probe", 2, ops.probeCount)
    }

    @Test
    fun `a probe that never terminates is NotChecked, not silence`() {
        val ops = FakeOps(null)
        assertFalse(run(ops))
        assertEquals(listOf(ChannelMove.Verdict.NotChecked), ops.verdicts)
    }

    @Test
    fun `a search already running is not an answer to this question`() {
        val ops = FakeOps(busy = true)
        assertFalse(run(ops))
        assertEquals(0, ops.probeCount)
        assertEquals(listOf(ChannelMove.Verdict.NotChecked), ops.verdicts)
    }

    // ------------------------------------------------ the retry is not exempt

    /**
     * The ~1-in-8 residual split. The retried `LocatorCfgChgRequest` is itself a single
     * unacknowledged frame on the channel being left, and the receiver follows it
     * regardless — so losing it reproduces the split one layer down. The invariant:
     * **a failed move never ends with the receiver on a channel the probe did not
     * confirm.**
     */
    @Test
    fun `a lost retry is looked at again and ends together rather than split`() {
        val ops = FakeOps(
            done(ChannelMove.Verdict.LocatorStayed),
            done(ChannelMove.Verdict.LocatorStayed),
            confirms = false,
        )
        assertFalse(run(ops))
        assertEquals(
            listOf(
                "probe($NEW,$OLD)", "point($OLD)", "relink($OLD,since=1000)", "resend", "confirm",
                "probe($NEW,$OLD)", "point($OLD)",
            ),
            ops.log,
        )
        assertEquals("the receiver is put back where the locator is", 2, ops.log.count { it == "point($OLD)" })
    }

    @Test
    fun `a retry whose confirmation was merely late still succeeds`() {
        val ops = FakeOps(
            done(ChannelMove.Verdict.LocatorStayed),
            done(ChannelMove.Verdict.Confirmed),
            confirms = false,
        )
        assertTrue(run(ops))
        assertEquals("no revert after a confirmed second look", 1, ops.log.count { it == "point($OLD)" })
    }

    /** Nothing established by the second look means nothing further is moved. */
    @Test
    fun `a second look that hears nothing does not move the receiver again`() {
        val ops = FakeOps(
            done(ChannelMove.Verdict.LocatorStayed),
            done(ChannelMove.Verdict.NoEvidence),
            confirms = false,
        )
        assertFalse(run(ops))
        assertEquals(1, ops.log.count { it == "point($OLD)" })
    }

    /** Bounded: one retry, ever. */
    @Test
    fun `the locator config is never re-sent more than once`() {
        val ops = FakeOps(
            done(ChannelMove.Verdict.LocatorStayed),
            done(ChannelMove.Verdict.LocatorStayed),
            confirms = false,
        )
        run(ops)
        assertEquals(1, ops.log.count { it == "resend" })
    }

    // ------------------------------------------------------- revert bail-outs

    @Test
    fun `a link that does not come back stops before the retry`() {
        val ops = FakeOps(done(ChannelMove.Verdict.LocatorStayed), relinks = false)
        assertFalse(run(ops))
        assertFalse("no retry without a link", ops.log.contains("resend"))
        assertEquals(1, ops.probeCount)
    }

    @Test
    fun `a failed re-send stops before waiting for a confirmation`() {
        val ops = FakeOps(done(ChannelMove.Verdict.LocatorStayed), resends = false)
        assertFalse(run(ops))
        assertFalse(ops.log.contains("confirm"))
        assertEquals(1, ops.probeCount)
    }

    /** The relink wait must be told when we asked, so it can require a newer frame. */
    @Test
    fun `the relink deadline is taken before the receiver is pointed`() {
        val ops = FakeOps(done(ChannelMove.Verdict.LocatorStayed), confirms = true)
        run(ops)
        val point = ops.log.indexOf("point($OLD)")
        val relink = ops.log.indexOfFirst { it.startsWith("relink") }
        assertTrue("point before relink", point < relink)
        assertTrue("relink carries the pre-point timestamp", ops.log[relink].contains("since=1000"))
    }
}
