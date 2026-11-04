package com.steampigeon.flightmanager.ui

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Every spoken callout goes through here, so the flight log can hold what was said
 * and when.
 *
 * The announcements were the hardest part of the flight to reconstruct afterwards:
 * they are the app's own reading of the telemetry — apogee, drogue, telemetry lost,
 * landing bearing — and unlike the numbers behind them they left no trace at all
 * once the words had gone past. Whether the app said "telemetry lost" nine seconds
 * before it said "landing" is a question about the app, and nothing but the app
 * could ever have answered it.
 *
 * A facade rather than a logging call beside each `speak`: there are twenty-odd call
 * sites, one of them inside a repeating loop, and a rule that has to be remembered
 * at each of them is a rule that will be missed at the next one added.
 *
 * What it says, and when, is decided by [CalloutQueue]: the "Simulated." prefix
 * goes on the first callout of a simulated flight and on its landing, and a
 * reading offered through [addCurrent] is dropped rather than spoken late.
 *
 * ## What gets logged is what was actually said
 *
 * [tts] is null whenever voice is switched off, and then nothing is spoken and
 * nothing is recorded. The log is a record of what the operator heard, not of what
 * the app would have said — an entry for a callout that never reached anyone's ears
 * would put a cause in the timeline for a reaction that never happened. A dropped
 * stale callout is likewise never logged.
 */
class Announcer(
    private val tts: TextToSpeech?,
    private val onSpoken: (String) -> Unit,
    /** True while the locator is flying a simulated flight (locator ADR-0041). */
    private val simulated: () -> Boolean = { false },
) {
    private var nextId = 0L

    private val queue = CalloutQueue({ text, flush ->
        val engine = tts ?: return@CalloutQueue
        onSpoken(text)
        engine.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null, "callout-${nextId++}")
    })

    init {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = queue.utteranceEnded()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = queue.utteranceEnded()
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                // A QUEUE_FLUSH stops what was queued; CalloutQueue.event has
                // already reset its count for that, so nothing to do here.
            }
        })
    }

    /**
     * Interrupts whatever is mid-sentence. For anything that outranks a routine
     * callout. [mark] repeats "Simulated." during a simulated flight, for landing.
     */
    fun flush(text: String, mark: Boolean = false) = speak(text, TextToSpeech.QUEUE_FLUSH, mark)

    /** Queues behind whatever is speaking. The default for routine callouts. */
    fun add(text: String) = speak(text, TextToSpeech.QUEUE_ADD)

    /**
     * A reading, such as an altitude, that is worth hearing only while it is
     * current. Dropped if it would wait behind other speech for too long.
     */
    fun addCurrent(text: String) {
        if (tts == null) return
        queue.offerCurrent(text, simulated())
    }

    fun speak(text: String, queueMode: Int, mark: Boolean = false) {
        if (tts == null) return
        queue.event(text, queueMode == TextToSpeech.QUEUE_FLUSH, simulated(), mark)
    }

    companion object {
        const val SIMULATED_PREFIX = CalloutQueue.SIMULATED_PREFIX
    }
}
