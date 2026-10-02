package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.BackgroundDownloadController
import com.steampigeon.flightmanager.data.BackgroundDownloadController.Action
import com.steampigeon.flightmanager.data.BackgroundDownloadController.Inputs
import com.steampigeon.flightmanager.data.BackgroundDownloadController.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Background record download decisions (#49, ADR-0009 invariant 13).
 *
 * The first two tests reproduce the 2026-09-30 bench failure: the DisarmRequest
 * sent on leaving Flight Profiles was lost (the locator's half-duplex radio was
 * mid-burst), so the chart's transfer carried on. The map showed it "paused",
 * and when it completed nobody handed the locator back — it sat silent in its
 * 5-minute "complete" state.
 */
class BackgroundDownloadControllerTest {

    private companion object {
        const val RECORD = 2
        const val LOCATOR = 0xABCDL
    }

    private fun inputs(
        now: Long,
        held: Boolean = true,
        onChart: Boolean = false,
        linkHeld: Boolean = false,
        free: Boolean = false,
        broadcasting: Boolean = false,
        lastFrame: Long = 0L,
        percent: Int = 40,
    ) = Inputs(
        nowMs = now, onChart = onChart, held = if (held) RECORD to LOCATOR else null,
        percent = percent, linkHeld = linkHeld, locatorFree = free,
        locatorBroadcasting = broadcasting, lastTransferFrameMs = lastFrame,
    )

    @Test
    fun aTransferAlreadyRunningIsShownAsDownloading() {
        // Left the chart; the DisarmRequest was lost, so frames keep coming and
        // the locator is not broadcasting. The controller never asked for them.
        val c = BackgroundDownloadController()
        val out = c.tick(inputs(now = 10_000, lastFrame = 9_600))
        assertEquals(Phase.Downloading, out.status!!.phase)
        assertTrue("must not re-request a transfer already running", out.actions.isEmpty())
    }

    @Test
    fun anAdoptedTransferThatCompletesHandsTheLocatorBackUntilItIsHeard() {
        val c = BackgroundDownloadController()
        c.tick(inputs(now = 10_000, lastFrame = 9_600))
        // Completed: nothing held any more, last frame just arrived.
        var out = c.tick(inputs(now = 11_000, held = false, lastFrame = 10_900))
        assertEquals(listOf(Action.HandBack), out.actions)
        assertNull(out.status)
        // Lost again: retried every 2 s, not every tick.
        assertTrue(c.tick(inputs(now = 12_000, held = false, lastFrame = 10_900)).actions.isEmpty())
        assertEquals(listOf(Action.HandBack), c.tick(inputs(now = 13_000, held = false, lastFrame = 10_900)).actions)
        // Heard broadcasting: stop.
        out = c.tick(inputs(now = 15_000, held = false, lastFrame = 10_900, broadcasting = true))
        assertTrue(out.actions.isEmpty())
        assertTrue(c.tick(inputs(now = 17_000, held = false, lastFrame = 10_900)).actions.isEmpty())
    }

    @Test
    fun theHandBackGivesUpAfterThirtySeconds() {
        val c = BackgroundDownloadController()
        c.tick(inputs(now = 10_000, lastFrame = 9_600))
        var handBacks = 0
        var t = 11_000L
        while (t < 60_000) {
            handBacks += c.tick(inputs(now = t, held = false, lastFrame = 10_900)).actions.size
            t += 1_000
        }
        assertEquals(16, handBacks)   // 11 s, 13 s, … 41 s: every 2 s for 30 s
    }

    @Test
    fun aFreeLocatorIsAskedForTheRecordOnceThenRetriedAfterAStall() {
        val c = BackgroundDownloadController()
        var out = c.tick(inputs(now = 1_000, free = true, broadcasting = true))
        assertEquals(listOf(Action.RequestRecord(RECORD, LOCATOR)), out.actions)
        assertEquals(Phase.Downloading, out.status!!.phase)
        // The request was lost: no frames. No second request until the stall.
        for (t in 2_000L..13_000L step 1_000) assertTrue(c.tick(inputs(now = t, free = true)).actions.isEmpty())
        out = c.tick(inputs(now = 14_000, free = true, broadcasting = true))
        assertEquals(listOf(Action.RequestRecord(RECORD, LOCATOR)), out.actions)
    }

    @Test
    fun aLocatorThatIsNotFreeIsNotAskedAndShowsPaused() {
        val c = BackgroundDownloadController()
        val out = c.tick(inputs(now = 1_000, free = false))
        assertTrue(out.actions.isEmpty())
        assertEquals(Phase.Paused, out.status!!.phase)
    }

    @Test
    fun aHeldLinkStopsTheLocatorOnlyWhileItIsStillSending() {
        val c = BackgroundDownloadController()
        c.tick(inputs(now = 1_000, lastFrame = 900))
        // Locator Settings opened while frames still arrive: ask it to stop.
        var out = c.tick(inputs(now = 2_000, linkHeld = true, lastFrame = 1_900))
        assertEquals(listOf(Action.HandBack), out.actions)
        assertEquals(Phase.Paused, out.status!!.phase)
        // Still sending a second later: rate-limited.
        assertTrue(c.tick(inputs(now = 3_000, linkHeld = true, lastFrame = 2_900)).actions.isEmpty())
        // It stopped. The screen may now have put it in another mode (the
        // Flight Profiles list's MetadataRequested): no more DisarmRequests.
        out = c.tick(inputs(now = 8_000, linkHeld = true, lastFrame = 2_900))
        assertTrue(out.actions.isEmpty())
        // Never requests the record while the link is held, even if free.
        assertTrue(c.tick(inputs(now = 9_000, linkHeld = true, free = true, lastFrame = 2_900)).actions.isEmpty())
    }

    @Test
    fun releasingTheLinkResumesWhenTheLocatorIsFree() {
        val c = BackgroundDownloadController()
        c.tick(inputs(now = 1_000, linkHeld = true))
        val out = c.tick(inputs(now = 2_000, free = true, broadcasting = true))
        assertEquals(listOf(Action.RequestRecord(RECORD, LOCATOR)), out.actions)
    }

    @Test
    fun completionWhileAScreenHoldsTheLinkDoesNotHandBack() {
        val c = BackgroundDownloadController()
        c.tick(inputs(now = 1_000, lastFrame = 900))
        val out = c.tick(inputs(now = 2_000, held = false, linkHeld = true, lastFrame = 1_900))
        assertTrue(out.actions.isEmpty())
    }

    @Test
    fun theChartOwnsTheTransfer() {
        val c = BackgroundDownloadController()
        val out = c.tick(inputs(now = 1_000, onChart = true, free = true, lastFrame = 900))
        assertTrue(out.actions.isEmpty())
        assertNull(out.status)
    }

    @Test
    fun stoppingWhileTheLocatorSendsHandsItBack() {
        val c = BackgroundDownloadController()
        c.tick(inputs(now = 1_000, lastFrame = 900))
        c.stop(nowMs = 1_500, lastTransferFrameMs = 1_400)
        val out = c.tick(inputs(now = 2_000, held = false, lastFrame = 1_400))
        assertEquals(listOf(Action.HandBack), out.actions)
    }

    @Test
    fun aSendFailureAllowsTheNextTickToRetry() {
        val c = BackgroundDownloadController()
        c.tick(inputs(now = 1_000, free = true, broadcasting = true))
        c.requestNotSent()
        val out = c.tick(inputs(now = 2_000, free = true, broadcasting = true))
        assertEquals(listOf(Action.RequestRecord(RECORD, LOCATOR)), out.actions)
    }
}
