package com.steampigeon.flightmanager.data

/**
 * Classifies the LoRa link from the receiver-appended RSSI, SNR and idle-channel
 * noise floor (ADR-0019).
 *
 * The question users actually ask when telemetry gets patchy is "am I too far
 * away, or is something jamming me?" — and RSSI alone cannot answer it, because
 * distance and interference both show up as poor reception.
 *
 * **SNR alone cannot answer it either, and keying an alert on low SNR would be
 * actively harmful.** At SF7 the LoRa demodulator works down to about -7.5 dB SNR,
 * and a healthy flight *ends* near that floor: apogee at several miles is exactly
 * when SNR is worst. An SNR-only trigger fires on every good flight, which trains
 * the user to dismiss the banner — worse than showing nothing.
 *
 * The discriminator is the pair. In a clean channel SNR tracks `RSSI - noiseFloor`
 * until it saturates at the modem ceiling, so a packet arriving *loud but dirty*
 * is the interference signature:
 *
 * | RSSI   | SNR  | Meaning                          | Verdict       |
 * |--------|------|----------------------------------|---------------|
 * | Weak   | Low  | Far away — normal at apogee      | [Normal]      |
 * | Weak   | High | Clean, quiet, distant            | [Normal]      |
 * | Strong | High | Healthy close link               | [Normal]      |
 * | Strong | Low  | Another emitter is in the channel| [Interference]|
 *
 * The noise floor adds the case packets cannot report at all: a channel busy with
 * traffic we are winning against ([Congested] — informational, no call to action).
 */
object LinkQuality {

    enum class Verdict {
        /** Nothing to report, or not enough information yet. */
        Normal,

        /** Channel is occupied but our packets are still clean. Informational. */
        Congested,

        /** Loud packets arriving dirty: something is degrading the link. Actionable. */
        Interference,
    }

    /**
     * Receiver reports this when it took no idle sample in the interval.
     *
     * **Must equal the firmware's `kNoiseFloorUnknown`, which is `INT16_MIN`.** The
     * field is an `int16_t` on the wire, so the app sees −32768 — not Kotlin's
     * `Int.MIN_VALUE`. Using the latter meant the sentinel never matched: "no
     * sample" was read as a real −32768 dBm floor, the session baseline latched
     * onto it, and every subsequent reading looked 32000 dB "elevated". Pinned by
     * `WireLayoutTest`.
     */
    const val NOISE_FLOOR_UNKNOWN = -32768

    /**
     * Above this the packet is arriving comfortably — roughly 30 dB above the
     * SF7/BW125 sensitivity floor. Below it, poor SNR is adequately explained by
     * distance and must not raise an alert.
     */
    const val STRONG_RSSI_DBM = -90

    /**
     * A packet this loud should have SNR far above the -7.5 dB demod floor. Not
     * reaching this margin while arriving strong means power in the channel that
     * is not our signal.
     */
    const val POOR_SNR_DB = 3

    /**
     * How far the idle floor must rise above the quietest floor seen this session
     * before the channel counts as occupied. Relative, not absolute: SX126x RSSI
     * near the noise floor is uncalibrated and varies unit to unit, so a hardcoded
     * dBm threshold would mean different things on different hardware.
     */
    const val ELEVATED_FLOOR_MARGIN_DB = 12

    /**
     * A floor at or above this is occupied on its own evidence, whatever the
     * session baseline says.
     *
     * The relative test alone has a hole: it assumes the channel was quiet at some
     * point this session, so the baseline is a fair reference. If the channel is
     * already busy when the app starts — the normal case for someone investigating
     * interference — the baseline absorbs the interferer and nothing ever looks
     * elevated. That is the same failure as the receiver sampling only when packets
     * arrive: the mechanism defeated by the condition it detects.
     *
     * −100 dBm is ~20 dB above the receiver's thermal floor at BW125 and well
     * inside the range where SX126x RSSI is trustworthy, so it does not reintroduce
     * the calibration problem that made an absolute threshold unattractive for the
     * *rise* test.
     */
    const val BUSY_FLOOR_DBM = -100

    /**
     * Gap since the previous accepted broadcast that means we missed at least one.
     *
     * This exists because **a co-channel LoRa peer destroys packets by collision,
     * not by degradation.** The packets that survive a collision arrive pristine,
     * so RSSI and SNR both look perfect and the strong-but-noisy rule never fires —
     * while the user watches the locator drop out. Loss is the only observable.
     *
     * Deliberately equal to the map's `messageTimeout`, so that "the rocket marker
     * went red" and "the app counts this as loss" mean the same thing. At 2500 ms
     * they disagreed: a single missed 1 Hz broadcast reddens the marker at ~2.1 s
     * but fell short of the threshold, producing exactly the reported symptom — a
     * red rocket with no explanation next to it.
     */
    const val LOSSY_GAP_MS = 2_000L

    /**
     * How long an observed loss keeps counting against the link.
     *
     * Without this the verdict is useless for the case it was built for. The
     * classification only runs when a packet ARRIVES, and an arriving packet has by
     * definition just ended the gap — so `Interference` showed for a single
     * broadcast period and the next clean packet flipped it back to `Congested`,
     * while the stretch the user was actually staring at (marker red, nothing being
     * received) recomputed nothing at all.
     *
     * Two locators sharing a channel drift in and out of phase, so collisions come
     * in epochs rather than evenly. 10 s bridges the clean packets inside a bad
     * patch without outlasting the patch itself.
     */
    const val LOSS_MEMORY_MS = 10_000L

    /**
     * Wall clock of the most recent observed loss, or [current] if nothing was lost.
     *
     * Two independent kinds of evidence, and [badFrames] is the better one. A gap
     * is loss *inferred from absence* — it needs a whole missed period to appear,
     * and a locator switched off produces the same thing. A frame that arrived and
     * failed to parse is loss we can *see*: something transmitted and was
     * destroyed. It also shows up on the very packet that reports it, rather than
     * one period later.
     *
     * The receiver has always detected these — it lights the red LED — and then
     * discarded them, exactly as it did with the SNR before ADR-0019. Collision
     * garbage reaches that path because the driver no longer drops hardware CRC
     * mismatches (#16).
     */
    fun updateLastLoss(current: Long, nowMs: Long, gapMs: Long, badFrames: Int = 0): Long =
        if (gapMs >= LOSSY_GAP_MS || badFrames > 0) nowMs else current

    /** Whether a loss is recent enough to still count. */
    fun isLossy(lastLossMs: Long, nowMs: Long): Boolean =
        lastLossMs != 0L && nowMs - lastLossMs <= LOSS_MEMORY_MS

    /**
     * @param rssi        packet RSSI in dBm
     * @param snr         packet SNR in dB
     * @param noiseFloor  peak idle-channel RSSI in dBm, or [NOISE_FLOOR_UNKNOWN]
     * @param quietestFloor quietest floor observed this session, or
     *                      [NOISE_FLOOR_UNKNOWN] before any sample has arrived
     * @param lossy       whether a broadcast has been missed recently — see
     *                    [isLossy], which is what keeps this from being a
     *                    single-packet judgement
     */
    fun classify(
        rssi: Int,
        snr: Int,
        noiseFloor: Int,
        quietestFloor: Int,
        lossy: Boolean = false,
    ): Verdict {
        val degraded = rssi > STRONG_RSSI_DBM && snr < POOR_SNR_DB
        val haveFloor = noiseFloor != NOISE_FLOOR_UNKNOWN
        val haveBaseline = quietestFloor != NOISE_FLOOR_UNKNOWN
        // Two independent ways to be occupied: risen materially above this session's
        // quietest reading, or loud enough that no baseline is needed to say so.
        val risen = haveFloor && haveBaseline &&
                noiseFloor - quietestFloor >= ELEVATED_FLOOR_MARGIN_DB
        val loud = haveFloor && noiseFloor >= BUSY_FLOOR_DBM
        val elevated = risen || loud
        return when {
            // Loud but dirty. Reported whether or not the floor corroborates: a
            // continuous interferer raises the floor, but one that transmits only
            // in bursts can wreck packets while the sampled floor still looks
            // quiet, and that case must not go unreported.
            degraded -> Verdict.Interference

            // Busy channel that is *costing us packets*. This is the co-channel
            // case — another LoRa transmitter on our frequency collides with our
            // broadcasts rather than degrading them, so the survivors look perfect
            // and `degraded` never fires while the locator visibly drops out.
            //
            // The conjunction is what keeps it honest. Loss alone is ambiguous (a
            // locator that was switched off or walked out of range also produces
            // gaps), but a locator that went away does not raise the noise floor.
            // Requiring both means only an occupied channel can trigger it.
            elevated && lossy -> Verdict.Interference

            // Occupied, but we are winning: no packets missed.
            elevated -> Verdict.Congested

            else -> Verdict.Normal
        }
    }

    /** Running quietest-floor baseline. [current] may be [NOISE_FLOOR_UNKNOWN]. */
    fun updateQuietestFloor(current: Int, sample: Int): Int = when {
        sample == NOISE_FLOOR_UNKNOWN -> current
        current == NOISE_FLOOR_UNKNOWN -> sample
        else -> minOf(current, sample)
    }
}
