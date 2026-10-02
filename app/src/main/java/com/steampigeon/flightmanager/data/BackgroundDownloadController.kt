package com.steampigeon.flightmanager.data

/**
 * The decisions behind finishing a record download in the background (#49,
 * ADR-0009 invariants 12–13), kept free of Android, the service and the clock
 * so they can be unit-tested. `RocketViewModel` feeds it one [Inputs] per tick
 * and carries out the [Action]s it returns.
 *
 * Two rules came from a bench failure (2026-09-30), and the tests pin both:
 *
 * 1. **"In flight" is decided by evidence, not by who asked.** Leaving the
 *    Flight Profiles screen sends one `DisarmRequest`. The locator's radio is
 *    half-duplex and spends ~4 s of every ~5 s burst transmitting, so that
 *    frame is easily lost — and then the transfer the chart started simply
 *    carries on. The first version counted a download as in flight only if it
 *    had sent the request itself, so it showed "paused" while the record
 *    streamed in, and never handed the locator back when it finished.
 * 2. **Handing the locator back is retried until it is heard broadcasting.**
 *    One `DisarmRequest` can be lost for the same reason. The locator's own
 *    safety net after a *completed* transfer is 5 minutes of silence, far too
 *    long to rely on.
 */
class BackgroundDownloadController(
    /** Transfer frames this recent mean the locator is sending now. Bursts
     *  run ~4.3 s with sub-second gaps between frames, so 3 s is comfortably
     *  "still sending" without lingering after it stops. */
    private val sendingWindowMs: Long = 3_000L,
    /** A request of ours with no frames for this long was lost, or the link
     *  dropped; under the locator's own 15 s kDataActiveTimeoutMs. */
    private val stallMs: Long = 12_000L,
    /** Interval between hand-back (`DisarmRequest`) attempts. */
    private val handBackRetryMs: Long = 2_000L,
    /** Stop retrying after this long; the locator's timeouts take over. */
    private val handBackGiveUpMs: Long = 30_000L,
) {
    enum class Phase { Downloading, Paused }

    /** What the map shows while a record is held but not finished. */
    data class Status(val record: Int, val percent: Int, val phase: Phase)

    data class Inputs(
        val nowMs: Long,
        /** The chart is open and driving the transfer itself. */
        val onChart: Boolean,
        /** Record and locator of a held, unfinished download, or null. */
        val held: Pair<Int, Long>?,
        val percent: Int,
        /** A screen that uses the locator link, or an arm attempt, holds it. */
        val linkHeld: Boolean,
        /** Phone connected, the held record's locator is the connected one,
         *  it is disarmed and its PreLaunchData is fresh. */
        val locatorFree: Boolean,
        /** Any fresh PreLaunchData: the locator is back to broadcasting. */
        val locatorBroadcasting: Boolean,
        /** When the last FlightData/FlightDataParity frame arrived (0 = never). */
        val lastTransferFrameMs: Long,
    )

    sealed class Action {
        data class RequestRecord(val record: Int, val locatorId: Long) : Action()
        /** Send `DisarmRequest`: return the locator to broadcasting. */
        object HandBack : Action()
    }

    data class Output(val actions: List<Action>, val status: Status?)

    private var inFlight = false
    private var requestedMs = 0L
    private var handBackSinceMs: Long? = null
    private var lastHandBackMs = Long.MIN_VALUE / 2

    /** The user stopped the download (the caller discards the held packets). */
    fun stop(nowMs: Long, lastTransferFrameMs: Long) {
        if (inFlight || sending(nowMs, lastTransferFrameMs)) handBackSinceMs = nowMs
        inFlight = false
    }

    /** The RequestRecord just returned could not be sent. */
    fun requestNotSent() {
        inFlight = false
    }

    fun tick(i: Inputs): Output {
        val actions = mutableListOf<Action>()
        val sending = sending(i.nowMs, i.lastTransferFrameMs)

        if (i.onChart) {
            // The chart owns the transfer and hands the locator back itself
            // when the Flight Profiles screen closes.
            inFlight = false
            handBackSinceMs = null
            return Output(actions, null)
        }

        val held = i.held
        if (held == null) {
            // Finished (or nothing held). A transfer that was running in the
            // background has just completed: the locator is now sitting silent
            // in its "complete" state, so hand it back.
            if ((inFlight || sending) && handBackSinceMs == null) handBackSinceMs = i.nowMs
            inFlight = false
            handBack(i, actions)
            return Output(actions, null)
        }
        val (record, locatorId) = held

        // Rule 1: a transfer already arriving is in flight, whoever started it.
        if (sending) inFlight = true

        if (i.linkHeld) {
            // Something else needs the link. Ask the locator to stop sending —
            // but only while it is: once it has stopped, a DisarmRequest could
            // knock it out of what the holding screen asked for (the Flight
            // Profiles list puts it in MetadataRequested).
            if (sending) handBackRateLimited(i.nowMs, actions)
            inFlight = false
            handBackSinceMs = null
        } else {
            if (inFlight && !sending && i.nowMs - maxOf(i.lastTransferFrameMs, requestedMs) > stallMs)
                inFlight = false
            if (!inFlight && i.locatorFree) {
                actions += Action.RequestRecord(record, locatorId)
                inFlight = true
                requestedMs = i.nowMs
            }
            // A hand-back left over from an earlier completion or stop.
            handBack(i, actions)
        }

        return Output(actions, Status(record, i.percent, if (inFlight) Phase.Downloading else Phase.Paused))
    }

    private fun sending(nowMs: Long, lastFrameMs: Long) =
        lastFrameMs > 0L && nowMs - lastFrameMs < sendingWindowMs

    /** Rule 2: retry the hand-back until the locator is heard, or give up. */
    private fun handBack(i: Inputs, actions: MutableList<Action>) {
        val since = handBackSinceMs ?: return
        if (i.linkHeld || i.locatorBroadcasting || i.nowMs - since > handBackGiveUpMs) {
            handBackSinceMs = null
            return
        }
        handBackRateLimited(i.nowMs, actions)
    }

    private fun handBackRateLimited(nowMs: Long, actions: MutableList<Action>) {
        if (nowMs - lastHandBackMs >= handBackRetryMs) {
            actions += Action.HandBack
            lastHandBackMs = nowMs
        }
    }
}
