package com.steampigeon.flightmanager.data

/**
 * Arbitration for the app's single locator connection.
 *
 * Authorization and connection are different things (ADR-0006, "One connection at
 * a time"): the app may hold passwords for any number of locators, but displays and
 * commands exactly one.  Collapsing the two let whichever authorized locator
 * transmitted most recently seize the display, so the panel alternated between two
 * rockets packet by packet.
 *
 * This is the whole decision, kept pure so it can be tested without a ViewModel.
 */
object LocatorConnection {

    /**
     * May an authorized [sender] take the connection currently held by [connected]?
     *
     * True when the slot is free, when [sender] already holds it, or when the holder
     * has been silent for at least [holdMs].  False for a *different* authorized
     * locator while the holder is still live — the caller reports that as
     * conflicting traffic and waits for the user to switch deliberately.
     *
     * [ageMs] is the time since the last frame accepted from [connected]; it is
     * meaningless when [connected] is null and is ignored in that case.
     */
    fun mayConnect(connected: Long?, sender: Long, ageMs: Long, holdMs: Long): Boolean =
        connected == null || connected == sender || ageMs >= holdMs
}
