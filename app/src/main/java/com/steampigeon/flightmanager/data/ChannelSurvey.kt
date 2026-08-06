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

    /** How many channels the recommendation offers. */
    const val SUGGESTION_COUNT = 5

    data class Ranked(val channel: Int, val level: Int)

    data class Result(
        val status: Status,
        /** Quietest first. Empty unless [status] is [Status.Ok]. */
        val ranked: List<Ranked>,
        /** True when every confirmed channel is loud — advise moving the transmitter. */
        val allChannelsHot: Boolean,
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
         */
        val suggestions: List<Ranked>
            get() = if (status != Status.Ok || allChannelsHot) emptyList()
                    else confirmed.take(SUGGESTION_COUNT)

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
    ): Result {
        if (status != Status.Ok || levels.isEmpty()) {
            return Result(status, emptyList(), allChannelsHot = false,
                homeChannel = homeChannel, confirmed = emptyList())
        }
        val ranked = levels
            .mapIndexed { channel, level -> Ranked(channel, level) }
            // Stable tie-break on channel number, so an unchanged RF environment
            // produces an unchanged recommendation rather than shuffling each sweep.
            .sortedWith(compareBy({ it.level }, { it.channel }))
        val confirmed = confirmedChannels
            .filter { it in levels.indices }
            .map { Ranked(it, levels[it]) }
            .sortedWith(compareBy({ it.level }, { it.channel }))
        // Judged on the confirmed set, since those are the only readings that mean
        // anything. If every channel we actually verified is loud, the receiver is
        // swamped broadband and no channel change will help.
        val allHot = confirmed.isNotEmpty() && confirmed.first().level >= ALL_HOT_DBM
        return Result(status, ranked, allHot, homeChannel, confirmed)
    }
}
