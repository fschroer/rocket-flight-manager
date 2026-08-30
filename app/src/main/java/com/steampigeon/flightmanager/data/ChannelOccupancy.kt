package com.steampigeon.flightmanager.data

/**
 * Who — other than us — is known to be sitting on a given channel.
 *
 * Both scans already answer this; the value of having it in one place is the word
 * **other**. A scan run while connected reports our own rocket on our own channel,
 * every time, correctly. Every consumer of this answer is asking in order to warn
 * about *sharing* a channel with somebody, so the one locator that cannot possibly
 * collide with itself has to come out first — and it has now been left in twice, in
 * two different ways, which is why this is no longer inline in a composable.
 *
 * Excluded **by identity, not by channel**. [ChannelSurvey.Result.occupied] drops
 * the home channel wholesale, which is the closest ADR-0019 could get: when it was
 * written the sweep reported a frame count and no id, so "on our channel" was the
 * only available stand-in for "ours". It is a lossy one — it also hides a genuine
 * neighbour sharing your channel, which is precisely the thing worth warning about.
 * Now that `locator_id` rides in the response (ADR-0029), the question can be asked
 * directly, so this reads `confirmed` and filters on who rather than where.
 *
 * A locator with no id reported resolves to no name, and the two scans then part
 * company deliberately:
 *
 * - The **survey** reports nothing at all. Its `locator_id` slots are also zero
 *   against a receiver whose firmware predates ADR-0029 (the response was 84 bytes
 *   and carried no ids), so "id 0" there cannot be told from "this receiver does
 *   not report ids", and naming an occupant would be wrong across the whole band.
 * - The **search** says [UNRECOGNIZED_LOCATOR]. `LocatorSearchResult` is new in
 *   ADR-0029 and has always carried the field, so a zero means exactly one thing:
 *   the frame that was heard carried no id. The receiver captures a hit for **any**
 *   frame that clears `ParseLoraFrame` and fills `sender_id` only from
 *   `PreLaunchData` and `TelemetryData` (receiver `Communication.cpp`), so a dwell
 *   landing on a flight-data transfer, a deployment test or an arm command scores a
 *   hit with no id — a routine event at a launch, not an edge case. The channel is
 *   occupied, saying so is the point, and `00000000` is not a name.
 */
object ChannelOccupancy {

    /** The English default for [occupantOf]'s `unrecognizedLabel`, so this object
     *  stays testable without a `Context`. The screen passes the localized resource. */
    const val UNRECOGNIZED_LOCATOR = "An unrecognized locator"

    /**
     * @param excludeLocatorId the connected locator: its broadcasts are why the
     *        channel reads occupied, and they are not a conflict
     * @param labelOf resolves a stored display name for an id, or null if unknown.
     *        A lambda rather than the known-locator map so this stays free of the
     *        generated proto types and can be tested on its own.
     * @param unrecognizedLabel what to call a search hit that named nobody. Reads
     *        into both of the screen's sentences as a subject.
     * @return a display name, a hex id when nothing better is known, or null when
     *         nothing is known at all — which is **not** the same as "channel free":
     *         a channel nothing has scanned is simply unmeasured.
     */
    fun occupantOf(
        channel: Int,
        survey: ChannelSurvey.Result?,
        search: LocatorSearch.Run?,
        excludeLocatorId: Long? = null,
        labelOf: (Long) -> String? = { null },
        unrecognizedLabel: String = UNRECOGNIZED_LOCATOR,
    ): String? {
        // A hit the run itself calls suspect is not evidence that anything is on
        // this channel. Near-field saturation puts one locator on channels it is
        // nowhere near (bench 2026-08-27, a locator on 57 reported on 17), and the
        // hit row already flags those `· likely false hit` — so naming the phantom
        // here made the screen contradict itself, in red, and talked the user out
        // of a channel that was free. The firmware reports at most one hit per
        // channel per run, so dropping the suspect one leaves the channel to the
        // survey rather than to a second hit.
        val suspect = search?.suspectChannels.orEmpty()
        // Search hits otherwise win: a search is the more recent and more direct
        // evidence, since it went looking for exactly this.
        val hit = search?.hits?.firstOrNull {
            it.channel == channel && it.locatorId != excludeLocatorId &&
                    it.channel !in suspect
        }
        if (hit != null) {
            val named = hit.deviceName.takeIf { it.isNotEmpty() }
                ?: hit.locatorId.takeIf { it != 0L }
                    ?.let { id -> labelOf(id) ?: "%08X".format(id) }
            if (named != null) return named
            // Occupancy without identity. Fall THROUGH rather than returning: the
            // survey may have named this very channel, and answering "nobody knows"
            // over the top of an answer the app already holds is worse than the
            // 00000000 this replaced.
        }
        val occupied = survey?.confirmed
            ?.firstOrNull {
                it.channel == channel && it.occupiedByLocator &&
                        it.locatorId != excludeLocatorId
            }
        val surveyed = occupied?.let {
            labelOf(it.locatorId)
                ?: it.locatorId.takeIf { id -> id != 0L }?.let { id -> "%08X".format(id) }
        }
        if (surveyed != null) return surveyed
        // Something was heard here and nothing could name it.
        return if (hit != null) unrecognizedLabel else null
    }
}
