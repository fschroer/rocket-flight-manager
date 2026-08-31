package com.steampigeon.flightmanager.data

/**
 * The order in which an unconfirmed channel move is resolved (ADR-0011, amendment
 * "revert on evidence, not on silence").
 *
 * [ChannelMove] holds the individual decisions and is pinned separately. This holds
 * the **sequence** — how many times to look, when to wait, what may move and in what
 * order — which is where the rest of that amendment's defects were, and which was
 * unreachable by any test while it sat inside a coroutine holding a `BluetoothService`.
 * Every one of those defects was found by hand on a bench.
 *
 * All side effects and observations go through [Ops] so the sequence can be driven
 * against a script. The runner holds no state of its own beyond the call stack, and
 * deliberately contains no timing, no flows and no Android types.
 */
class ChannelMoveRunner(
    private val ops: Ops,
    private val refusedRetryMs: Long,
) {

    /** One finished probe run, as the caller's search layer reports it. */
    data class ProbeRun(
        /** The run reached a `Done` terminator. False for a refusal or a timeout. */
        val completed: Boolean,
        /** Meaningless unless [completed]. */
        val verdict: ChannelMove.Verdict,
    )

    interface Ops {
        /** A search is already streaming, and it is not an answer to our question. */
        fun probeInProgress(): Boolean

        /** Start one two-channel census and wait for its terminator; null if none came. */
        suspend fun runProbe(newChannel: Int, oldChannel: Int): ProbeRun?

        suspend fun pause(ms: Long)

        fun nowMs(): Long

        /** Receiver-only channel change over BLE. */
        suspend fun pointReceiverAt(channel: Int)

        /** Wait for a frame admitted after [sinceMs] with both ends reading [oldChannel]. */
        suspend fun awaitRelink(oldChannel: Int, sinceMs: Long): Boolean

        /** Re-send the locator config; false if the send itself failed. */
        suspend fun resendLocatorConfig(): Boolean

        /** Wait for the locator to echo the staged config back. */
        suspend fun awaitConfirmation(): Boolean

        /** Publish a verdict for the UI. Called for every verdict the runner acts on. */
        fun onVerdict(verdict: ChannelMove.Verdict)
    }

    /**
     * Resolve the move. True if the locator ended up on [newChannel].
     *
     * **Silence is asked for twice.** ADR-0029's *zero frames proves nothing* applies
     * directly — one 1.4 s dwell can miss a 1 Hz burst — and `NoEvidence` is the branch
     * that ends with the receiver on a channel nothing has been heard on, so it is the
     * one worth another probe to avoid entering by accident.
     *
     * **A refusal is not.** [probe] already re-asks a refused run once; a third ask
     * would be pestering a receiver that has twice declined.
     */
    suspend fun resolve(newChannel: Int, oldChannel: Int): Boolean {
        var verdict = probe(newChannel, oldChannel)
        if (verdict == ChannelMove.Verdict.NoEvidence)
            verdict = probe(newChannel, oldChannel)
        ops.onVerdict(verdict)
        return when (ChannelMove.action(verdict)) {
            ChannelMove.Action.Succeed -> true
            ChannelMove.Action.Revert -> recover(newChannel, oldChannel)
            ChannelMove.Action.Stand -> false
        }
    }

    /**
     * One verdict, with the refusal retry folded in.
     *
     * The receiver turns a search down while an operator command is queued, and after
     * a move to a locator that has gone silent the queued command is **our own
     * undeliverable one** — so the probe is blocked by exactly the situation it was
     * sent to diagnose, until the receiver's stale-drop clears it. [refusedRetryMs] is
     * sized to outlast that.
     */
    private suspend fun probe(newChannel: Int, oldChannel: Int): ChannelMove.Verdict {
        if (ops.probeInProgress()) return ChannelMove.Verdict.NotChecked
        val first = ops.runProbe(newChannel, oldChannel) ?: return ChannelMove.Verdict.NotChecked
        val settled = if (first.completed) first else {
            ops.pause(refusedRetryMs)
            ops.runProbe(newChannel, oldChannel)
        }
        if (settled == null || !settled.completed) return ChannelMove.Verdict.NotChecked
        return settled.verdict
    }

    /**
     * Put the receiver back, retry once, and look again if the retry goes unanswered.
     *
     * Reached only from an evidenced [ChannelMove.Verdict.LocatorStayed].
     *
     * **The retry is not exempt from the rule the amendment establishes.** It is itself
     * a single unacknowledged frame on the channel being left, and the receiver follows
     * it whether or not the locator hears it — so losing it reproduces the split one
     * layer down. Measured at ~1 run in 8 before the second look was added. The
     * invariant that closes it: *a failed move never ends with the receiver on a
     * channel the probe did not confirm.*
     *
     * Bounded by construction: [probe] cannot recurse, there is no second retry, and
     * the only action available after the second look is putting the receiver back
     * where the evidence already points.
     */
    private suspend fun recover(newChannel: Int, oldChannel: Int): Boolean {
        val askedAtMs = ops.nowMs()
        ops.pointReceiverAt(oldChannel)
        if (!ops.awaitRelink(oldChannel, askedAtMs)) return false
        if (!ops.resendLocatorConfig()) return false
        if (ops.awaitConfirmation()) return true

        val after = probe(newChannel, oldChannel)
        ops.onVerdict(after)
        if (after == ChannelMove.Verdict.Confirmed)
            return true   // the retry landed after all; only its confirmation was late
        if (after == ChannelMove.Verdict.LocatorStayed)
            ops.pointReceiverAt(oldChannel)   // end together rather than split
        return false
    }
}
