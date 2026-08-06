package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.LinkQuality
import com.steampigeon.flightmanager.data.LinkQuality.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the RSSI x SNR discriminator from ADR-0019.
 *
 * The regression that matters most is [distant rocket at apogee stays silent] --
 * an SNR-only trigger fires there on every healthy flight, and a banner that cries
 * wolf at apogee is worse than no banner at all.
 */
class LinkQualityTest {

    private val quiet = -120   // a clean idle floor
    private val unknown = LinkQuality.NOISE_FLOOR_UNKNOWN

    @Test
    fun `healthy close link is normal`() {
        assertEquals(Verdict.Normal, LinkQuality.classify(-60, 9, quiet, quiet))
    }

    @Test
    fun `distant rocket at apogee stays silent`() {
        // Weak and near the SF7 demod floor: this is what a good flight looks like
        // at several miles. Must never alert.
        assertEquals(Verdict.Normal, LinkQuality.classify(-118, -7, quiet, quiet))
        assertEquals(Verdict.Normal, LinkQuality.classify(-110, -5, quiet, quiet))
        assertEquals(Verdict.Normal, LinkQuality.classify(-100, 0, quiet, quiet))
    }

    @Test
    fun `loud but dirty is interference`() {
        assertEquals(Verdict.Interference, LinkQuality.classify(-60, 0, quiet, quiet))
        assertEquals(Verdict.Interference, LinkQuality.classify(-75, -3, quiet, quiet))
    }

    @Test
    fun `bursty interferer is caught even when the sampled floor looks quiet`() {
        // An interferer that transmits only in bursts can wreck packets while the
        // idle samples still land in the gaps.
        assertEquals(Verdict.Interference, LinkQuality.classify(-70, -2, quiet, quiet))
    }

    @Test
    fun `busy channel we are winning against is informational only`() {
        val elevated = quiet + LinkQuality.ELEVATED_FLOOR_MARGIN_DB
        assertEquals(Verdict.Congested, LinkQuality.classify(-60, 9, elevated, quiet))
    }

    // ── Co-channel collisions (found by bench test, two locators on one channel) ──
    //
    // A LoRa peer on our own frequency destroys packets by collision, not by
    // degradation: the survivors arrive pristine, so RSSI and SNR both look
    // perfect. Before this case existed the app reported the reassuring
    // "channel is busy, but your link is clean" while the locator was visibly
    // dropping in and out.

    @Test
    fun `busy channel that is costing packets is interference, not merely congested`() {
        val elevated = quiet + LinkQuality.ELEVATED_FLOOR_MARGIN_DB
        // Pristine packet — the collision survivors always are — but a missed cycle.
        assertEquals(
            Verdict.Interference,
            LinkQuality.classify(-60, 9, elevated, quiet, lossy = true),
        )
    }

    @Test
    fun `loss on a quiet channel is not blamed on interference`() {
        // A locator switched off or walked out of range produces gaps too, but it
        // does not raise the noise floor. Requiring both is what keeps this honest.
        assertEquals(Verdict.Normal, LinkQuality.classify(-60, 9, quiet, quiet, lossy = true))
        assertEquals(Verdict.Normal, LinkQuality.classify(-118, -7, quiet, quiet, lossy = true))
    }

    @Test
    fun `a busy channel with no recent loss stays congested`() {
        val elevated = quiet + LinkQuality.ELEVATED_FLOOR_MARGIN_DB
        assertEquals(Verdict.Congested, LinkQuality.classify(-60, 9, elevated, quiet, lossy = false))
    }

    // ── Loss is remembered, not judged per packet ────────────────────────────────
    //
    // The classifier only runs when a packet ARRIVES, and an arriving packet has by
    // definition just ended the gap. Judged instantaneously, Interference showed for
    // one broadcast period and the next clean packet flipped it back to Congested --
    // so the stretch the user was actually looking at, marker red and nothing being
    // received, never showed anything. Bench report: "when the rocket is red I don't
    // get an interference message; sometimes it says the link is clean".

    @Test
    fun `a gap at or over the threshold records a loss`() {
        assertEquals(5_000L, LinkQuality.updateLastLoss(0L, 5_000L, LinkQuality.LOSSY_GAP_MS))
        assertEquals(5_000L, LinkQuality.updateLastLoss(0L, 5_000L, 9_999L))
    }

    @Test
    fun `a corrupted frame counts as loss on the packet that reports it`() {
        // Better evidence than a gap, and a whole period earlier. A gap needs a
        // missed broadcast to appear and a switched-off locator produces the same
        // thing; a frame that failed to parse proves something transmitted and was
        // destroyed. The receiver always detected these and threw them away.
        assertEquals(5_000L, LinkQuality.updateLastLoss(0L, 5_000L, gapMs = 1_000L, badFrames = 1))
        assertEquals(0L, LinkQuality.updateLastLoss(0L, 5_000L, gapMs = 1_000L, badFrames = 0))
    }

    @Test
    fun `a collision is reported without waiting for a gap to open`() {
        // The bench case: the spare overtakes the recognized locator and clobbers
        // it. Frames arrive corrupted while the cadence still looks nominal, so the
        // gap test alone would say nothing until a whole broadcast went missing.
        val elevated = quiet + LinkQuality.ELEVATED_FLOOR_MARGIN_DB
        val lastLoss = LinkQuality.updateLastLoss(0L, 1_000L, gapMs = 1_000L, badFrames = 3)
        assertEquals(
            Verdict.Interference,
            LinkQuality.classify(-60, 9, elevated, quiet,
                lossy = LinkQuality.isLossy(lastLoss, 1_000L)),
        )
    }

    @Test
    fun `a clean gap leaves the recorded loss untouched`() {
        // Nominal 1 Hz cadence with jitter is not loss, and must not clear an
        // earlier one either -- that is what keeps the verdict stable across the
        // clean packets between collisions.
        assertEquals(1_000L, LinkQuality.updateLastLoss(1_000L, 5_000L, 1_100L))
        assertEquals(0L, LinkQuality.updateLastLoss(0L, 5_000L, 1_100L))
    }

    @Test
    fun `a loss keeps counting for the memory window and then clears`() {
        val lossAt = 10_000L
        assertTrue(LinkQuality.isLossy(lossAt, lossAt))
        assertTrue("must bridge the clean packets inside a bad patch",
            LinkQuality.isLossy(lossAt, lossAt + LinkQuality.LOSS_MEMORY_MS))
        assertFalse("and clear once the link has been clean for the window",
            LinkQuality.isLossy(lossAt, lossAt + LinkQuality.LOSS_MEMORY_MS + 1))
    }

    @Test
    fun `no loss ever seen is not lossy`() {
        assertFalse(LinkQuality.isLossy(0L, 60_000L))
    }

    @Test
    fun `the reported bench sequence now shows interference throughout`() {
        // Two locators drifting through phase: a collision, then clean packets, then
        // another collision. Every one of these moments must read as Interference,
        // including the clean packets between them -- those are the ones that used to
        // reset the verdict to "busy but clean" while the marker was still red.
        val elevated = quiet + LinkQuality.ELEVATED_FLOOR_MARGIN_DB
        var lastLoss = 0L
        var now = 0L
        fun tick(gap: Long): Verdict {
            now += gap
            lastLoss = LinkQuality.updateLastLoss(lastLoss, now, gap)
            return LinkQuality.classify(-60, 9, elevated, quiet,
                lossy = LinkQuality.isLossy(lastLoss, now))
        }
        assertEquals(Verdict.Congested, tick(1_000L))      // healthy
        assertEquals(Verdict.Interference, tick(3_000L))   // collision: two missed
        assertEquals(Verdict.Interference, tick(1_000L))   // clean packet, still bad patch
        assertEquals(Verdict.Interference, tick(1_000L))
        assertEquals(Verdict.Interference, tick(2_500L))   // another collision
        // Locators drift apart; after a clean run the verdict relaxes on its own.
        repeat(11) { tick(1_000L) }
        assertEquals(Verdict.Congested, tick(1_000L))
    }

    // ── Holes found by the second bench run ──────────────────────────────────────

    @Test
    fun `unknown floor sentinel matches the int16 the firmware actually sends`() {
        // The firmware's kNoiseFloorUnknown is INT16_MIN and the field is an int16_t,
        // so it arrives as -32768. Comparing against Int.MIN_VALUE never matched:
        // "no sample" was read as a real floor, the baseline latched onto it, and
        // every later reading looked ~32000 dB elevated -- so the app reported
        // "channel is busy" essentially always.
        assertEquals(Short.MIN_VALUE.toInt(), LinkQuality.NOISE_FLOOR_UNKNOWN)
        assertEquals(
            Verdict.Normal,
            LinkQuality.classify(-60, 9, LinkQuality.NOISE_FLOOR_UNKNOWN, quiet),
        )
        // ...and it must not become the session baseline.
        assertEquals(
            quiet,
            LinkQuality.updateQuietestFloor(quiet, LinkQuality.NOISE_FLOOR_UNKNOWN),
        )
    }

    @Test
    fun `a channel busy since startup is still detected without a quiet baseline`() {
        // The relative test assumes the channel was quiet at some point this session.
        // When the interference is already present at startup the baseline absorbs
        // it and nothing looks "elevated" -- the same class of failure as the
        // receiver only sampling when packets arrive.
        val busy = LinkQuality.BUSY_FLOOR_DBM
        assertEquals(Verdict.Congested, LinkQuality.classify(-60, 9, busy, busy))
        assertEquals(
            Verdict.Interference,
            LinkQuality.classify(-60, 9, busy, busy, lossy = true),
        )
    }

    @Test
    fun `a quiet floor is not called busy on absolute grounds`() {
        val justQuiet = LinkQuality.BUSY_FLOOR_DBM - 1
        assertEquals(Verdict.Normal, LinkQuality.classify(-60, 9, justQuiet, justQuiet))
    }

    @Test
    fun `loss threshold agrees with the map's staleness timeout`() {
        // messageTimeout in FlightMapScreen is 2000 ms. When these disagreed a single
        // missed 1 Hz broadcast reddened the rocket at ~2.1 s without counting as
        // loss, which is a red marker with no explanation beside it.
        assertEquals(2_000L, LinkQuality.LOSSY_GAP_MS)
    }

    @Test
    fun `floor just under the margin is not congested`() {
        val marginal = quiet + LinkQuality.ELEVATED_FLOOR_MARGIN_DB - 1
        assertEquals(Verdict.Normal, LinkQuality.classify(-60, 9, marginal, quiet))
    }

    @Test
    fun `unknown floor never fabricates a verdict`() {
        assertEquals(Verdict.Normal, LinkQuality.classify(-60, 9, unknown, quiet))
        assertEquals(Verdict.Normal, LinkQuality.classify(-60, 9, quiet, unknown))
        // ...but a degraded packet still reports, since that path ignores the floor.
        assertEquals(Verdict.Interference, LinkQuality.classify(-60, 0, unknown, unknown))
    }

    @Test
    fun `strong-rssi boundary is exclusive`() {
        // Exactly at the threshold counts as weak, so poor SNR there is attributed
        // to distance rather than raising an alert.
        assertEquals(Verdict.Normal, LinkQuality.classify(LinkQuality.STRONG_RSSI_DBM, -5, quiet, quiet))
        assertEquals(Verdict.Interference, LinkQuality.classify(LinkQuality.STRONG_RSSI_DBM + 1, -5, quiet, quiet))
    }

    @Test
    fun `poor-snr boundary is exclusive`() {
        assertEquals(Verdict.Normal, LinkQuality.classify(-60, LinkQuality.POOR_SNR_DB, quiet, quiet))
        assertEquals(Verdict.Interference, LinkQuality.classify(-60, LinkQuality.POOR_SNR_DB - 1, quiet, quiet))
    }

    @Test
    fun `quietest floor tracks the minimum and ignores unknowns`() {
        var floor = unknown
        floor = LinkQuality.updateQuietestFloor(floor, unknown)
        assertEquals(unknown, floor)
        floor = LinkQuality.updateQuietestFloor(floor, -110)
        assertEquals(-110, floor)
        floor = LinkQuality.updateQuietestFloor(floor, -95)   // noisier, ignored
        assertEquals(-110, floor)
        floor = LinkQuality.updateQuietestFloor(floor, -122)  // quieter, adopted
        assertEquals(-122, floor)
        floor = LinkQuality.updateQuietestFloor(floor, unknown)
        assertEquals(-122, floor)
    }
}
