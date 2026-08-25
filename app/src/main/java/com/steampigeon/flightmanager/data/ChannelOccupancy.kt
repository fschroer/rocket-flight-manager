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
 * A locator with no id reported (an older receiver, or a frame type that carries
 * none) resolves to no name and therefore to null: the channel is still occupied
 * and the survey still withholds it from suggestions, but naming nobody would be a
 * warning with nothing in it.
 */
object ChannelOccupancy {

    /**
     * @param excludeLocatorId the connected locator: its broadcasts are why the
     *        channel reads occupied, and they are not a conflict
     * @param labelOf resolves a stored display name for an id, or null if unknown.
     *        A lambda rather than the known-locator map so this stays free of the
     *        generated proto types and can be tested on its own.
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
    ): String? {
        // Search hits win: a search is the more recent and more direct evidence,
        // since it went looking for exactly this.
        search?.hits
            ?.firstOrNull { it.channel == channel && it.locatorId != excludeLocatorId }
            ?.let { hit ->
                return hit.deviceName.takeIf { it.isNotEmpty() }
                    ?: labelOf(hit.locatorId)
                    ?: "%08X".format(hit.locatorId)
            }
        val occupied = survey?.confirmed
            ?.firstOrNull {
                it.channel == channel && it.occupiedByLocator &&
                        it.locatorId != excludeLocatorId
            }
            ?: return null
        return labelOf(occupied.locatorId)
            ?: occupied.locatorId.takeIf { it != 0L }?.let { "%08X".format(it) }
    }
}
