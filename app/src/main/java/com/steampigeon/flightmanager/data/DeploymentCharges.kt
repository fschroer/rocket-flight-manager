package com.steampigeon.flightmanager.data

/**
 * Did the charge wired for [DeployMode] actually fire?
 *
 * The locator carries **four** deployment channels and the app must ask about
 * all four.  It asked about two, and the two it skipped are where the stock
 * wiring puts main: ch1 DroguePrimary, ch2 DrogueBackup, **ch3 MainPrimary**,
 * ch4 MainBackup — the defaults [LocatorConfigWire.payload] writes when a mode
 * is null.  So on a rocket nobody had reconfigured, "Main charge." and "Main
 * backup charge." could not be spoken at all: the flight-state transition that
 * gates them arrived, the channel that fired was 3, and channels 1 and 2 were
 * the only ones consulted.  The drogue callouts worked, which is why this
 * survived being flown — the flight announced its first half and went quiet
 * for the half that happens closer to the ground.
 *
 * Found 2026-09-02 on the iOS port (`FlightAnnouncer.fired(_:_:)`, which zips
 * across every channel) and recorded as row 5 of `UI_PARITY.md`'s "ANDROID
 * OWES THIS" table.
 *
 * Mode is matched, not channel number: a channel is identified by the job it
 * was assigned, so a rocket wired main on 1 and drogue on 3 reads correctly
 * without this knowing anything about convention.  [DeployMode.Unused]
 * channels never match a callout, since no callout asks for that mode.
 *
 * Pure, and takes the two data classes rather than a ViewModel, so the unit
 * suite can drive it — the callout site in `FlightMapScreen` cannot be reached
 * from a test on this platform.
 */
object DeploymentCharges {

    /**
     * True when some channel is configured for [mode] **and** that same
     * channel reports fired.
     *
     * Both halves matter and they are per channel, not aggregated: a rocket
     * with drogue on 1 and main on 3 that fires only drogue must not announce
     * main because *a* channel fired, and a channel that fired must not
     * announce a mode some *other* channel was wired for.
     */
    fun fired(config: LocatorConfig, state: RocketState, mode: DeployMode): Boolean =
        channels(config, state).any { (channelMode, channelFired) ->
            channelMode == mode && channelFired
        }

    /** The four channels as (configured mode, fired) pairs, in channel order.
     *  A null mode is one the locator has not reported yet — not a default:
     *  guessing here would announce a charge on the strength of a convention
     *  rather than on what the locator said. */
    private fun channels(config: LocatorConfig, state: RocketState): List<Pair<DeployMode?, Boolean>> =
        listOf(
            config.deploymentChannel1Mode to state.channel1Fired,
            config.deploymentChannel2Mode to state.channel2Fired,
            config.deploymentChannel3Mode to state.channel3Fired,
            config.deploymentChannel4Mode to state.channel4Fired,
        )
}
