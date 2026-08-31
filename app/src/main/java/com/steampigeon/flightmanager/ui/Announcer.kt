package com.steampigeon.flightmanager.ui

import android.speech.tts.TextToSpeech

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
 * ## What gets logged is what was actually said
 *
 * [tts] is null whenever voice is switched off, and then nothing is spoken and
 * nothing is recorded. The log is a record of what the operator heard, not of what
 * the app would have said — an entry for a callout that never reached anyone's ears
 * would put a cause in the timeline for a reaction that never happened.
 */
class Announcer(
    private val tts: TextToSpeech?,
    private val onSpoken: (String) -> Unit,
) {
    /** Interrupts whatever is mid-sentence. For anything that outranks a routine callout. */
    fun flush(text: String) = speak(text, TextToSpeech.QUEUE_FLUSH)

    /** Queues behind whatever is speaking. The default for routine callouts. */
    fun add(text: String) = speak(text, TextToSpeech.QUEUE_ADD)

    fun speak(text: String, queueMode: Int) {
        val engine = tts ?: return
        onSpoken(text)
        engine.speak(text, queueMode, null, null)
    }
}
