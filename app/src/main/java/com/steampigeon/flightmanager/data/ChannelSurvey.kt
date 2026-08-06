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
        /** True when every channel is loud — advise moving the transmitter, not switching. */
        val allChannelsHot: Boolean,
        val homeChannel: Int,
    ) {
        /** Best channels to offer, quietest first. Empty when the ranking is not actionable. */
        val suggestions: List<Ranked>
            get() = if (status != Status.Ok || allChannelsHot) emptyList()
                    else ranked.take(SUGGESTION_COUNT)

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
    fun analyze(status: Status, levels: List<Int>, homeChannel: Int): Result {
        if (status != Status.Ok || levels.isEmpty()) {
            return Result(status, emptyList(), allChannelsHot = false, homeChannel = homeChannel)
        }
        val ranked = levels
            .mapIndexed { channel, level -> Ranked(channel, level) }
            // Stable tie-break on channel number, so an unchanged RF environment
            // produces an unchanged recommendation rather than shuffling each sweep.
            .sortedWith(compareBy({ it.level }, { it.channel }))
        val allHot = ranked.first().level >= ALL_HOT_DBM
        return Result(status, ranked, allHot, homeChannel)
    }
}
