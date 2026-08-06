package com.steampigeon.flightmanager.data

/**
 * Ranks the receiver's channel sweep and turns it into a recommendation
 * (ADR-0019 tier 3).
 *
 * Two rules shape everything here, both from the ADR:
 *
 * **Rank relatively; never present absolute dBm as truth.** SX126x RSSI near the
 * noise floor is uncalibrated and varies unit to unit, so a level is only
 * meaningful compared with the other levels in the *same* sweep.
 *
 * **Detect the all-channels-hot case.** A locator sitting a few feet from the
 * receiver saturates the front end on every channel at once (user manual
 * Appendix G). Ranking that blindly would confidently recommend whichever channel
 * happened to read lowest, which is worthless advice — the fix is to move the
 * nearby transmitter, not to change channel. This is the exact scenario that
 * prompted the whole line of work, so getting it wrong here would be ironic.
 */
object ChannelSurvey {

    /** Mirrors the firmware's `ChannelSurveyStatus`. */
    enum class Status { Ok, RefusedArmed, RefusedBusy, Unknown;
        companion object {
            fun fromByte(v: Int) = when (v) {
                0 -> Ok
                1 -> RefusedArmed
                2 -> RefusedBusy
                else -> Unknown
            }
        }
    }

    /**
     * Even the quietest channel reading above this means the receiver is swamped
     * broadband — almost always a transmitter within a few feet. A genuinely quiet
     * channel sits far below it.
     */
    const val ALL_HOT_DBM = -90

    /**
     * Spread across the confirmed channels below which an elevated reading is a
     * *uniform* floor rather than real per-channel traffic.
     *
     * The confirm dwell exceeds one broadcast period, so it always overlaps a
     * transmission — and a locator within a few feet bleeds across the entire band
     * while transmitting. Every confirmed channel therefore reads at the bleed
     * level, which is the normal condition on a bench and says nothing about the
     * channels themselves.
     *
     * Measured on two bench traces: a locator ~2 ft away with genuine co-channel
     * traffic gave −52/−57/−71/−71/−71, a 19 dB spread with real structure. The
     * same locator several feet away with no co-channel traffic gave
     * −71/−74/−72/−71/−71 — 3 dB, flat. 6 dB sits well clear of both.
     */
    const val UNIFORM_SPREAD_DB = 6

    /** How many channels the recommendation offers. */
    const val SUGGESTION_COUNT = 5

    /** [frames] is locator broadcasts DECODED on this channel during its confirm
     *  dwell, or 0 for channels that were never confirmed. */
    data class Ranked(val channel: Int, val level: Int, val frames: Int = 0) {
        /**
         * A decoded frame had to be transmitted on this exact channel — off-channel
         * bleed does not survive the demodulator. So this is occupancy as fact
         * rather than as inference, and it outranks the level completely.
         *
         * The converse does not hold: zero frames does not prove empty. The dwell is
         * one broadcast period, so a sparser emitter slips through, and a non-locator
         * device is invisible to this test entirely — which is what the level is
         * still for.
         */
        val occupiedByLocator: Boolean get() = frames > 0
    }

    data class Result(
        val status: Status,
        /** Quietest first. Empty unless [status] is [Status.Ok]. */
        val ranked: List<Ranked>,
        /** True when every confirmed channel is loud. */
        val allChannelsHot: Boolean,
        /**
         * True when every confirmed channel is loud *and* they are all within
         * [UNIFORM_SPREAD_DB] of each other — a broadband floor from a nearby
         * transmitter rather than traffic on any particular channel.
         *
         * This is the normal bench condition, since the user's own locator is
         * usually a few feet away. It is information, not a fault: the channels are
         * genuinely indistinguishable, so any of them is as good as another.
         */
        val uniformFloor: Boolean,
        val homeChannel: Int,
        /**
         * Channels the receiver dwelled on for a full broadcast period. Only these
         * are evidence that a channel is free: the coarse pass dwells ~12 ms, while
         * a locator is on air ~138 ms per second, so it reads an occupied channel
         * as quiet about three times out of four.
         */
        val confirmed: List<Ranked>,
    ) {
        /**
         * Channels to offer, quietest first.
         *
         * Drawn from [confirmed] only, never from the coarse ranking. Suggesting an
         * unconfirmed channel is how a sweep ends up recommending the channel the
         * locators are already sitting on.
         *
         * Offered **even when [allChannelsHot]**. A transmitter a few feet from the
         * receiver puts a broadband floor on every channel at once, so everything
         * reads loud — but the ranking underneath is still correct, and the caller
         * still has to choose something. A bench sweep with a spare locator two feet
         * away confirmed channels at −52 and −57 dBm (adjacent to the occupied one)
         * against −71 dBm elsewhere; −71 was the near-field floor, meaning those
         * channels carried no traffic of their own and would be clean the moment the
         * spare was moved. Withholding them left the user with a correct warning and
         * no way to act on it. The warning is shown alongside, not instead.
         */
        val suggestions: List<Ranked>
            get() = if (status != Status.Ok) emptyList()
                    // A channel with a locator decoded on it is occupied, whatever
                    // its level says. That is the whole point of the frame count:
                    // RSSI cannot separate "a locator is using this channel" from
                    // "a locator near me is loud on every channel", and this can.
                    else confirmed.filterNot { it.occupiedByLocator }.take(SUGGESTION_COUNT)

        /** Confirmed channels that have a locator on them — shown, never suggested. */
        val occupied: List<Ranked> get() = confirmed.filter { it.occupiedByLocator }

        /** Where the current channel sits in the ranking, 1-based. Null if unknown. */
        val homeRank: Int?
            get() = ranked.indexOfFirst { it.channel == homeChannel }
                .takeIf { it >= 0 }
                ?.plus(1)
    }

    /**
     * @param status  the firmware's status byte, already decoded
     * @param levels  peak dBm per channel, index == channel number
     * @param homeChannel the receiver's current channel
     */
    fun analyze(
        status: Status,
        levels: List<Int>,
        homeChannel: Int,
        confirmedChannels: List<Int> = emptyList(),
        confirmedFrames: List<Int> = emptyList(),
    ): Result {
        if (status != Status.Ok || levels.isEmpty()) {
            return Result(status, emptyList(), allChannelsHot = false, uniformFloor = false,
                homeChannel = homeChannel, confirmed = emptyList())
        }
        val ranked = levels
            .mapIndexed { channel, level -> Ranked(channel, level) }
            // Stable tie-break on channel number, so an unchanged RF environment
            // produces an unchanged recommendation rather than shuffling each sweep.
            .sortedWith(compareBy({ it.level }, { it.channel }))
        val confirmed = confirmedChannels
            .withIndex()
            .filter { (_, ch) -> ch in levels.indices }
            .map { (i, ch) -> Ranked(ch, levels[ch], confirmedFrames.getOrElse(i) { 0 }) }
            .sortedWith(compareBy({ it.level }, { it.channel }))
        // Judged on the confirmed set, since those are the only readings that mean
        // anything. If every channel we actually verified is loud, the receiver is
        // swamped broadband and no channel change will help.
        val allHot = confirmed.isNotEmpty() && confirmed.first().level >= ALL_HOT_DBM
        // Flat and elevated = one transmitter raising everything equally. Structured
        // and elevated = real occupancy, and the ranking is telling us something.
        val spread = if (confirmed.isEmpty()) 0
                     else confirmed.last().level - confirmed.first().level
        val uniform = allHot && spread <= UNIFORM_SPREAD_DB
        return Result(status, ranked, allHot, uniform, homeChannel, confirmed)
    }
}
