package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.ui.CalloutQueue
import org.junit.Assert.assertEquals
import org.junit.Test

/** The callout rules of locator ADR-0041 (amended 2026-11-03). */
class CalloutQueueTest {
    private var now = 0L
    private val said = mutableListOf<String>()
    private val queue = CalloutQueue({ text, _ -> said += text }, { now })

    @Test
    fun simulatedIsSaidOnceThenAgainAtLanding() {
        queue.event("Drogue charge.", flush = false, simulated = true)
        queue.utteranceEnded()
        queue.event("Main charge.", flush = false, simulated = true)
        queue.utteranceEnded()
        queue.event("Landing.", flush = true, simulated = true, mark = true)
        assertEquals(listOf("Simulated. Drogue charge.", "Main charge.", "Simulated. Landing."), said)
    }

    @Test
    fun aRealFlightIsNeverPrefixedAndANewSimulationIsPrefixedAgain() {
        queue.event("Apogee.", flush = false, simulated = true); queue.utteranceEnded()
        queue.event("Apogee.", flush = false, simulated = false); queue.utteranceEnded()
        queue.event("Landing.", flush = true, simulated = false, mark = true); queue.utteranceEnded()
        queue.event("Apogee.", flush = false, simulated = true)
        assertEquals(listOf("Simulated. Apogee.", "Apogee.", "Landing.", "Simulated. Apogee."), said)
    }

    @Test
    fun aReadingIsSpokenAtOnceWhenNothingIsQueued() {
        queue.offerCurrent("100 meters.", simulated = false)
        assertEquals(listOf("100 meters."), said)
    }

    @Test
    fun onlyTheNewestWaitingReadingIsSpoken() {
        queue.event("Drogue charge.", flush = false, simulated = false)
        queue.offerCurrent("100 meters.", simulated = false)
        now = 500; queue.offerCurrent("200 meters.", simulated = false)
        now = 1_000; queue.utteranceEnded()
        assertEquals(listOf("Drogue charge.", "200 meters."), said)
    }

    @Test
    fun aReadingThatWaitedTooLongIsDropped() {
        queue.event("Drogue charge.", flush = false, simulated = false)
        queue.offerCurrent("300 meters.", simulated = false)
        now = CalloutQueue.STALE_MS; queue.utteranceEnded()
        assertEquals(listOf("Drogue charge."), said)
    }

    @Test
    fun aDroppedReadingDoesNotUseUpThePrefix() {
        queue.event("Drogue charge.", flush = false, simulated = true)
        queue.offerCurrent("300 meters.", simulated = true)
        queue.event("Landing.", flush = true, simulated = true, mark = true)
        assertEquals(listOf("Simulated. Drogue charge.", "Simulated. Landing."), said)
        // The flush discarded the waiting reading.
        queue.utteranceEnded()
        assertEquals(2, said.size)
    }

    @Test
    fun aLostCompletionDoesNotSilenceReadingsForever() {
        queue.event("Drogue charge.", flush = false, simulated = false)
        now = CalloutQueue.LOST_COMPLETION_MS + 1
        queue.offerCurrent("400 meters.", simulated = false)
        assertEquals(listOf("Drogue charge.", "400 meters."), said)
    }
}
