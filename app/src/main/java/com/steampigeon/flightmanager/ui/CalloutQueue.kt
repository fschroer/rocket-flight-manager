package com.steampigeon.flightmanager.ui

/**
 * The two rules that decide what [Announcer] says, kept free of Android so they
 * can be tested on the JVM.
 *
 * ## "Simulated." is said once, not before every callout (locator ADR-0041)
 *
 * The prefix goes on the first callout of a simulated session and on the landing
 * callout. Those are the two moments a listener could mistake for a real flight.
 * Prefixing every callout made each one about a second longer, and at a 1 Hz
 * altitude cadence the queue fell four to five seconds behind the panel on the
 * first simulated flight flown (2026-11-03).
 *
 * ## A callout that would be stale is dropped, not queued (all flights)
 *
 * An altitude callout is a reading. If it waits behind other speech, it describes
 * where the rocket was. [offerCurrent] speaks it at once when nothing is queued.
 * Otherwise it parks in a single slot, where a newer reading replaces it. When the
 * queue drains it is spoken only if it is still under [staleMs] old.
 * Event callouts (apogee, charges, landing) are never dropped. They are facts, not
 * readings, and they stay true however late they arrive.
 *
 * Thread safety: the TTS engine reports progress on its own thread, so every entry
 * point is synchronized.
 */
class CalloutQueue(
    private val speakNow: (text: String, flush: Boolean) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleMs: Long = STALE_MS,
) {
    private var outstanding = 0
    private var lastSpokeAt = 0L
    private var pending: String? = null
    private var pendingSimulated = false
    private var pendingAt = 0L
    private var simPrefixSaid = false

    /**
     * An event callout. Always spoken; [flush] interrupts and discards the queue.
     * [simulated] is the telemetry's simulated bit. [mark] repeats the prefix
     * during a simulated flight, and is used for the landing callout.
     */
    @Synchronized
    fun event(text: String, flush: Boolean, simulated: Boolean, mark: Boolean = false) {
        if (flush) {
            outstanding = 0
            pending = null
        }
        say(text, flush, simulated, mark)
    }

    /** A reading that is worth hearing only while it is current. */
    @Synchronized
    fun offerCurrent(text: String, simulated: Boolean) {
        // An engine that never reports completion must not silence every reading
        // for the rest of the flight: no callout runs this long.
        if (outstanding > 0 && clock() - lastSpokeAt > LOST_COMPLETION_MS) outstanding = 0
        if (outstanding == 0) {
            say(text, false, simulated, false)
        } else {
            pending = text
            pendingSimulated = simulated
            pendingAt = clock()
        }
    }

    /** One utterance finished or failed. */
    @Synchronized
    fun utteranceEnded() {
        if (outstanding > 0) outstanding--
        if (outstanding > 0) return
        val text = pending ?: return
        pending = null
        if (clock() - pendingAt < staleMs) say(text, false, pendingSimulated, false)
    }

    // The prefix is decided when the words are actually handed to the engine, so
    // a dropped reading never uses up the one "Simulated." a session gets.
    private fun say(text: String, flush: Boolean, simulated: Boolean, mark: Boolean) {
        val said = when {
            !simulated -> { simPrefixSaid = false; text }
            simPrefixSaid && !mark -> text
            else -> { simPrefixSaid = true; "$SIMULATED_PREFIX $text" }
        }
        outstanding++
        lastSpokeAt = clock()
        speakNow(said, flush)
    }

    companion object {
        const val SIMULATED_PREFIX = "Simulated."

        /**
         * About two altitude callouts at a coast-phase cadence. Anything older has
         * been overtaken by the number on the panel.
         */
        const val STALE_MS = 2_000L

        /** Longer than any callout takes to say; past it, a completion was lost. */
        const val LOST_COMPLETION_MS = 15_000L
    }
}
