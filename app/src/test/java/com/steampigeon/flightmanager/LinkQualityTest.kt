package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.LinkQuality
import com.steampigeon.flightmanager.data.LinkQuality.Verdict
import org.junit.Assert.assertEquals
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
            LinkQuality.classify(-60, 9, elevated, quiet, gapMs = LinkQuality.LOSSY_GAP_MS),
        )
    }

    @Test
    fun `loss on a quiet channel is not blamed on interference`() {
        // A locator switched off or walked out of range produces gaps too, but it
        // does not raise the noise floor. Requiring both is what keeps this honest.
        assertEquals(Verdict.Normal, LinkQuality.classify(-60, 9, quiet, quiet, gapMs = 60_000L))
        assertEquals(Verdict.Normal, LinkQuality.classify(-118, -7, quiet, quiet, gapMs = 60_000L))
    }

    @Test
    fun `a single on-time broadcast on a busy channel stays congested`() {
        val elevated = quiet + LinkQuality.ELEVATED_FLOOR_MARGIN_DB
        assertEquals(
            Verdict.Congested,
            LinkQuality.classify(-60, 9, elevated, quiet, gapMs = LinkQuality.LOSSY_GAP_MS - 1),
        )
        // Nominal 1 Hz cadence with jitter must not read as loss.
        assertEquals(Verdict.Congested, LinkQuality.classify(-60, 9, elevated, quiet, gapMs = 1_100L))
    }

    @Test
    fun `first broadcast of a session is not a gap`() {
        val elevated = quiet + LinkQuality.ELEVATED_FLOOR_MARGIN_DB
        assertEquals(Verdict.Congested, LinkQuality.classify(-60, 9, elevated, quiet, gapMs = 0L))
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
