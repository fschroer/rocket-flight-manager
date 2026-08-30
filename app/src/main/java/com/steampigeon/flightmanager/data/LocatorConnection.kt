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

    /**
     * Was this frame relayed from the channel a receiver-only move is **leaving**?
     *
     * A receiver-only change (ADR-0011 invariant 5) releases the connection before the
     * change goes out, so the first authorized locator on the *new* channel can claim
     * the slot without waiting out [mayConnect]'s hold. That opens a window: the BLE
     * write, the receiver's own retune and its next relay all take time, and the
     * locator we just let go of is still broadcasting on the old channel at 1 Hz. Its
     * frames arrive into an empty slot and are perfectly authorized — so it takes the
     * connection straight back and resolves the recognition cycle that was armed for a
     * locator on a channel the receiver has not reached yet.
     *
     * Reported 2026-08-29, and **intermittent for exactly that reason** — it depends on
     * whether one of those broadcasts lands inside the window. Four locators on four
     * channels, connected to Twist 0 on 34, Connect tapped on Twist Lock 5 on 60: the
     * receiver arrived on 60, but the app had already re-adopted Twist 0, so the
     * password prompt Twist Lock 5 should have raised never came. What came instead was
     * the conflict banner — whose Connect action asks for the password, which is why the
     * feature looked reachable by another route and broken by this one.
     *
     * The discriminator is the receiver's own channel stamp on every relayed frame
     * (ADR-0011 invariant 3). A frame stamped with [previousChannel] was relayed before
     * the retune and says nothing about where we are going. `TelemetryData` carries no
     * stamp at all, so during the window it cannot be placed either and is treated the
     * same way; an armed locator on the new channel is admitted a few seconds later,
     * when the move resolves, and it could not have raised a challenge in the meantime
     * anyway (the prompt needs a device name).
     *
     * Bounded by [moveInFlight] rather than by the recognition flag alone. The flag
     * stays set until something arrives on the new channel, which may be never — a move
     * onto an empty channel, say — and suppressing forever would leave the app deaf to
     * the locator it still has. The receiver's config message state always returns to
     * idle, so the window closes whether the move is acknowledged or times out.
     *
     * @param frameChannel the receiver's stamp, or null for a message that carries none
     */
    fun isFromChannelBeingLeft(
        frameChannel: Int?,
        previousChannel: Int,
        awaitingRecognition: Boolean,
        moveInFlight: Boolean,
    ): Boolean =
        awaitingRecognition && moveInFlight &&
                (frameChannel == null || frameChannel == previousChannel)
}
