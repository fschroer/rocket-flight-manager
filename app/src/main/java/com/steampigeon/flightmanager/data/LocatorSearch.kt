package com.steampigeon.flightmanager.data

/**
 * The receiver-driven hunt for a locator whose channel you have lost, and the
 * pure part of deciding where to look first (#33 follow-up to ADR-0019).
 *
 * This is the survey's opposite question and cannot share its sweep. The survey
 * shortlists the **quietest** channels and dwells there, because it is answering
 * "where should I move to". A locator you are looking for is by definition making
 * noise on the channel you want, so it is shortlisted by that rule only by
 * accident.
 *
 * The cost of a dwell is what shapes everything else. A **disarmed** locator —
 * which is what a search hunts — is on air ~200 ms once per second
 * (`PreLaunchData`, 118 bytes at SF7/125 kHz/CR 4/5). A dwell has to contain a
 * WHOLE burst to rule a channel out, so it must exceed the period *plus* the
 * airtime: 1000 + 200 = 1200 ms minimum, and the dwell is 1400 ms to leave margin
 * for cadence jitter. Anything shorter reads an occupied channel as empty most of
 * the time — the coarse pass's known failure, and the reason the survey has a
 * confirm phase at all. At 1.4 s per channel a whole band with nothing on it is
 * up to ~90 s, which is a long time to be deaf. Hence candidates first: a handful
 * of channels the locator is actually likely to be on answers the usual case in
 * seconds, and the full band stays available for when it does not.
 *
 * The figures were wrong until 2026-08-30 and are worth stating precisely for
 * that reason: they read ~138 ms and ~77 s, which is `TelemetryData` — the frame
 * an **armed** locator sends. Both scans look for a disarmed one. See ADR-0029,
 * "The dwell was sized against the wrong frame".
 */
object LocatorSearch {

    /** Mirrors the firmware's `LocatorSearchStatus`. */
    enum class Status { Progress, Done, RefusedArmed, RefusedBusy, Cancelled, Unknown;
        companion object {
            fun fromByte(v: Int) = when (v) {
                0 -> Progress
                1 -> Done
                2 -> RefusedArmed
                3 -> RefusedBusy
                4 -> Cancelled
                else -> Unknown
            }
        }
    }

    /** The channel every locator ships on, so it is where a factory-reset or
     *  freshly-flashed one will be (ADR-0025 fixes the default at 0). Always worth
     *  a dwell: it costs one slot and covers the case where the locator's settings
     *  did not survive. */
    const val DEFAULT_CHANNEL = 0

    /**
     * One locator heard on one channel.
     *
     * [locatorId] and [deviceName] are cleartext, straight off the air, and
     * **unauthenticated** — the receiver holds no password and never inspects the
     * auth tag. They are here to make a hit readable ("your Redline is on 12"),
     * not to prove anything. Recognition happens the normal way once the receiver
     * is pointed at the channel and real broadcasts start arriving.
     */
    data class Hit(
        val channel: Int,
        val locatorId: Long,
        val deviceName: String,
        // Shown on the hit row, in the same form and with the same colour scales the
        // status panel already uses for the connected locator.
        //
        // An earlier comment here claimed ADR-0019 forbade displaying this. That
        // conflated two different measurements: the ADR's rule is about the survey's
        // uncalibrated CHANNEL LEVEL near the noise floor, not about a decoded
        // packet's RSSI, which this app has displayed since long before the search
        // existed.
        //
        // Both numbers, because neither decides alone. A locator a few feet from the
        // receiver is heard on channels it is nowhere near, and that artifact reads
        // as strong; SNR is what separates it from a genuine occupant. Bench
        // 2026-08-27 hit exactly that — one locator reported on two channels — and
        // relocating it 15-20 ft was the only way to tell which was real.
        val rssi: Int,
        val snr: Int,
        val armed: Boolean,
    ) {
        /**
         * Whether the receiver is connected to this locator **on this channel**.
         *
         * Both halves are needed and each was got wrong on its own first.
         *
         * Channel alone is not connection: tuned to a channel, the app may still be
         * waiting on an ADR-0006 password challenge, and the row claimed Connected
         * throughout it.
         *
         * Identity alone is not enough either, and that is the subtler one. A locator
         * close to the receiver is reported on more than one channel (see
         * [Run.suspectChannels]) — and every one of those hits carries the SAME id, so
         * an identity test marks them all Connected. Bench 2026-08-28 hit exactly
         * that: both rows read Connected, neither offered a button, and a user sitting
         * on the false channel had no way to reach the real one. The row is about a
         * channel, so the test has to be too.
         */
        fun connectedOn(currentChannel: Int, connectedLocatorId: Long?): Boolean =
            channel == currentChannel &&
                    locatorId != 0L &&
                    locatorId == connectedLocatorId
    }

    /**
     * A run in progress or just finished.
     *
     * [searched] / [total] come from the firmware rather than being counted here,
     * so the progress shown is the receiver's real position in the sweep and not
     * the app's guess from elapsed time.
     */
    data class Run(
        val running: Boolean,
        val searched: Int = 0,
        val total: Int = 0,
        val hits: List<Hit> = emptyList(),
        /** Null while running; the terminator's status once it ends. */
        val status: Status? = null,
        /** True for a whole-band run, so the UI can say how long this will take. */
        val wholeBand: Boolean = false,
        /** The locator this run was told to stop on, or 0 for a census. Carried so
         *  [missed] can ask the question that actually matters — see below. */
        val targetLocatorId: Long = 0L,
    ) {
        val fraction: Float get() = if (total <= 0) 0f else (searched.toFloat() / total)

        /**
         * A finished short run that did not find **what it was looking for**.
         *
         * With a target named, that means no hit carried its id — *whatever else*
         * turned up. Defining a miss as "found nothing at all" reads sensibly and
         * fails in the case this feature exists for: hunting Prometheus while Twist 0
         * is audible on the current channel, the run finds Twist 0, and a hit-count
         * test calls that success. The user is then hunting a locator the app has
         * decided it already found.
         */
        val missed: Boolean
            get() = canWiden && if (targetLocatorId != 0L) {
                hits.none { it.locatorId == targetLocatorId }
            } else {
                hits.isEmpty()
            }

        /**
         * Whether widening to the whole band is a coherent next step.
         *
         * Any **completed** short run qualifies, not only a missed one: finding some
         * locator is not evidence that the one you want is not out there, and gating
         * the band sweep behind an empty result left no way to reach it at all while
         * anything was audible.
         *
         * A *cancelled* run does not qualify — the user just stopped a search, and
         * answering that by offering a 90-second one is not reading the room.
         */
        val canWiden: Boolean
            get() = !running && status == Status.Done && !wholeBand

        /**
         * This run with [hit] recorded — or unchanged, when that channel already has one.
         *
         * **One channel yields at most one hit per run, by the firmware's design.**
         * The receiver holds a single hit slot, the first frame of a dwell fills it,
         * and the dwell then ends early because nothing further can be learned about
         * that channel (`ServiceLocatorSearch`). Each channel is reported exactly once
         * and the slot is cleared before the next. A second hit on a channel already
         * in this list is therefore not new information — it is the same dwell
         * arriving twice.
         *
         * Enforced here rather than trusted, because the list is the one piece of
         * accumulating state on the whole inbound path: everything else a search
         * message touches is an assignment, which absorbs a repeat silently, so a
         * duplicate would be visible ONLY here. Reported 2026-09-04 as one marginal
         * false hit on channel 0 rendered as four rows, with the only live locator on
         * 60 — four identical rows for a dwell that happened once.
         *
         * The first report wins. Two copies of one dwell carry the same reading, so
         * there is nothing to choose between them; keeping the first also keeps the
         * row from flickering under a late repeat.
         */
        fun withHit(hit: Hit): Run =
            if (hits.any { it.channel == hit.channel }) this else copy(hits = hits + hit)

        /**
         * Channels carrying a hit that is probably **not** where that locator is.
         *
         * One locator cannot be on two channels, but a search reports it on two when
         * it is close enough to the receiver to overload the front end: bench
         * 2026-08-27 found a locator on 57 also reported on 17, 8 MHz away, and only
         * moving it 15–20 ft settled which was real. Every hit for one locator except
         * its best is therefore suspect, and saying so is the difference between a
         * result the user can act on and two channels to guess between.
         *
         * Ranked by `rssi + snr`. The two are different units and adding them is a
         * figure of merit rather than a physical quantity — the reasoning is that both
         * are "more is better" and that the true channel should lead on both, since an
         * off-frequency leak reaches the demodulator attenuated by the filter it leaked
         * through.
         *
         * **Measured 2026-08-28, and it holds.** One locator, receiver close in, two
         * channels reported: the channel this rule flagged was the one that disappears
         * when the locator is moved 15–20 ft away — i.e. the artifact. The rule picks
         * the real channel. It would need revisiting only if an artifact were ever seen
         * arriving *stronger* than the true channel, which this rig did not produce.
         *
         * Hits with no id are never grouped: id 0 means the frame did not say who,
         * so two of them cannot be known to be the same locator.
         */
        val suspectChannels: Set<Int>
            get() = hits
                .filter { it.locatorId != 0L }
                .groupBy { it.locatorId }
                .filterValues { it.size > 1 }
                .flatMap { (_, group) ->
                    val best = group.maxByOrNull { it.rssi + it.snr }
                    group.filter { it !== best }.map { it.channel }
                }
                .toSet()
    }

    /**
     * Where to look, in the order the receiver should look.
     *
     * Order is load-bearing only for a targeted run, and there it is worth a lot:
     * the firmware stops on the first frame from [targetChannel]'s owner, so
     * putting that channel first usually ends the whole thing after one dwell.
     * Everything after it is a fallback and the ordering between those is
     * arbitrary — the run is short enough that it does not matter.
     *
     * @param targetChannel  the wanted locator's last known channel, if it has one
     * @param knownChannels  last known channels of every other locator the app has
     *                       heard — a receiver used with several rockets has been
     *                       tuned to each of them at some point, which is exactly
     *                       the memory this search exists to exploit
     * @param attemptedChannel a channel a move was staged to but never confirmed;
     *                       the locator may have taken it while the receiver did not
     * @param currentChannel where the receiver is sitting now. Last, not first: we
     *                       are already here and hearing nothing. It is included at
     *                       all because "already here" is not proof — a locator
     *                       powered on ten seconds ago has not been waited out yet.
     */
    fun candidates(
        currentChannel: Int,
        targetChannel: Int? = null,
        knownChannels: List<Int> = emptyList(),
        attemptedChannel: Int? = null,
        max: Int = Protocol.LOCATOR_SEARCH_MAX_CHANNELS,
    ): List<Int> =
        buildList {
            targetChannel?.let { add(it) }
            addAll(knownChannels)
            attemptedChannel?.let { add(it) }
            add(DEFAULT_CHANNEL)
            add(currentChannel)
        }
            .filter { it in 0 until Protocol.SURVEY_CHANNEL_COUNT }
            // The firmware dedupes too, because it must — it cannot trust a caller
            // it does not control. Doing it here as well keeps the list the user is
            // shown identical to the list that gets searched.
            .distinct()
            .take(max)
}
