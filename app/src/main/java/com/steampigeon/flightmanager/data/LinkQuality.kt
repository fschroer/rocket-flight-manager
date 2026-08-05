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

    /** Receiver reports this when it took no idle sample in the interval. Mirrors
     *  the firmware's `kNoiseFloorUnknown` (INT16_MIN). */
    const val NOISE_FLOOR_UNKNOWN = Int.MIN_VALUE

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
     * @param rssi        packet RSSI in dBm
     * @param snr         packet SNR in dB
     * @param noiseFloor  peak idle-channel RSSI in dBm, or [NOISE_FLOOR_UNKNOWN]
     * @param quietestFloor quietest floor observed this session, or
     *                      [NOISE_FLOOR_UNKNOWN] before any sample has arrived
     */
    fun classify(
        rssi: Int,
        snr: Int,
        noiseFloor: Int,
        quietestFloor: Int,
    ): Verdict {
        val degraded = rssi > STRONG_RSSI_DBM && snr < POOR_SNR_DB
        val floorKnown = noiseFloor != NOISE_FLOOR_UNKNOWN && quietestFloor != NOISE_FLOOR_UNKNOWN
        val elevated = floorKnown && noiseFloor - quietestFloor >= ELEVATED_FLOOR_MARGIN_DB

        return when {
            // Loud but dirty. Reported whether or not the floor corroborates: a
            // continuous interferer raises the floor, but one that transmits only
            // in bursts can wreck packets while the sampled floor still looks
            // quiet, and that case must not go unreported.
            degraded -> Verdict.Interference
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
