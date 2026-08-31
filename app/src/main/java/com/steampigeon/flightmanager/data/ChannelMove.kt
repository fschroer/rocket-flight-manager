package com.steampigeon.flightmanager.data

/**
 * Deciding what actually happened to a locator channel change, from what the
 * receiver heard rather than from what it failed to hear (ADR-0011, amendment
 * "revert on evidence, not on silence").
 *
 * There is no acknowledgement message. A change is confirmed by inference — the
 * next `PreLaunchData` relayed on the new channel — so what goes missing when a
 * move "fails" is a *broadcast*, and two opposite states produce the same silence:
 *
 * - the locator missed the command and stayed on the old channel, while the
 *   receiver followed onto the new one (a real split), and
 * - everything moved correctly and the confirmation was merely late.
 *
 * No test taken at the timeout can separate them, because the only evidence that
 * the locator moved is hearing it on the new channel — which *is* the
 * confirmation. So the app stops guessing and looks: a two-channel
 * `LocatorSearchRequest` over the new channel and the old one, and this object
 * reads the result.
 */
object ChannelMove {

    /**
     * What the probe established.
     *
     * [NoEvidence] is a real answer and not a failure of this function: it means
     * the receiver heard nothing it could attribute, and the caller must then do
     * the *non-destructive* thing — leave the receiver where it is and say so.
     */
    enum class Verdict {
        Confirmed,
        LocatorStayed,
        NoEvidence,

        /**
         * The question was never asked — the receiver refused the probe.
         *
         * Distinct from [NoEvidence] on purpose, and the distinction is the whole
         * lesson: *a rule about the link must be able to tell a gap the app created
         * from a gap the world created.* A refused search means the app failed to
         * look; treating that as "heard nothing" is the app mistaking its own
         * blindness for the locator's absence.
         *
         * It is reachable by construction rather than by bad luck. A channel move
         * queues a `LocatorCfgChgRequest`, `IsOperatorCommand` counts that as an
         * operator command, and the receiver refuses a `LocatorSearchRequest` while
         * one is pending. If the locator is silent the forward can never leave — the
         * forwarding window needs a recent `PreLaunchData` — so the undelivered
         * command blocks the very probe that would explain why it was undeliverable,
         * until the receiver's `kPendingTxStaleMs` drops it.
         */
        NotChecked,
    }

    /**
     * Which channel the locator is on, judged from one probe run.
     *
     * **The ranking is the whole point, and a single hit is not enough.** A locator
     * within a few feet of the receiver decodes on channels it is nowhere near
     * (ADR-0029, bench 2026-08-28), and the artifact reads as *strong* — so RSSI
     * alone cannot separate it and neither can "the first hit we got". Both dwells
     * are therefore always run, and the two are compared by `rssi + snr`, the same
     * figure of merit [LocatorSearch.Run.suspectChannels] uses and the same one the
     * bench validated. This matters more here than in an ordinary search, because a
     * channel move is something a user does with the locator in their hands.
     *
     * Only hits carrying [locatorId] count. A different locator sitting on the new
     * channel is a real and useful finding — it is why the probe is a census rather
     * than a targeted run — but it says nothing about where *ours* went, and
     * treating it as confirmation would report success for a move that stranded the
     * rocket.
     *
     * @param locatorId the connected locator, captured when the move was sent. Null
     *   or 0 means the app cannot attribute a hit to anything, so no hit can be
     *   evidence and the answer is [Verdict.NoEvidence].
     */
    fun verdict(
        hits: List<LocatorSearch.Hit>,
        locatorId: Long?,
        newChannel: Int,
        oldChannel: Int,
    ): Verdict {
        if (locatorId == null || locatorId == 0L) return Verdict.NoEvidence
        val mine = hits.filter { it.locatorId == locatorId }
        val onNew = mine.filter { it.channel == newChannel }.maxOfOrNull { it.rssi + it.snr }
        val onOld = mine.filter { it.channel == oldChannel }.maxOfOrNull { it.rssi + it.snr }
        return when {
            onNew != null && (onOld == null || onNew > onOld) -> Verdict.Confirmed
            onOld != null && (onNew == null || onOld > onNew) -> Verdict.LocatorStayed
            // Nothing heard, or heard equally on both — which separates nothing.
            // A tie is not evidence, and the caller must not revert on it.
            else -> Verdict.NoEvidence
        }
    }

    /**
     * The two channels to probe, in the order the receiver should dwell on them.
     *
     * New first. Not for an early exit — the run is a census and always dwells on
     * both — but because results stream, so if the run is cut short by its silence
     * timeout the more probable answer is already in hand.
     */
    fun probeChannels(newChannel: Int, oldChannel: Int): List<Int> =
        listOf(newChannel, oldChannel).distinct()
}
