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

    /** The ladder, in the order the locator walks it. Each callout is floored by
     *  the flight state that can first produce it. */
    val LADDER: List<Pair<DeployMode, FlightStates>> = listOf(
        DeployMode.DroguePrimary to FlightStates.DroguePrimaryEvent,
        DeployMode.DrogueBackup to FlightStates.DrogueBackupEvent,
        DeployMode.MainPrimary to FlightStates.MainPrimaryEvent,
        DeployMode.MainBackup to FlightStates.MainBackupEvent,
    )

    /**
     * Which charge callouts have come due, latching each so it is spoken once.
     *
     * **The flight state is a floor, not a trigger.** That distinction is the
     * whole point of this class, and getting it wrong cost the drogue callout
     * on a real configuration:
     *
     * `flight_state_` is monotonic in the locator (`AdvanceFlightState` only
     * ever moves forward), and the four deployment blocks are latched
     * *independently* — issue #10, and the firmware says so in as many words:
     * a drogue backup "must still fire after its delay even if a main event has
     * already advanced flight_state_ past it". So a charge can fire seconds
     * after the state that gates its callout has been passed, or after the
     * state has moved beyond it entirely.
     *
     * Worked example, from the bench: ch1 DrogueBackup with a 1.0 s delay,
     * ch2 MainPrimary at 130 m, noseover at 110 m. Main's condition is
     * `agl <= 130` — a test, not a downward crossing — so it is already true at
     * apogee: ch2 fires immediately and the state jumps to MainPrimaryEvent.
     * The drogue fires a second later and `AdvanceFlightState(DrogueBackupEvent)`
     * is a no-op, 5 being less than 6.
     *
     * Latching on the state threshold meant the drogue-backup callout was
     * evaluated at apogee, found `channel1Fired` still false, latched, and never
     * looked again — so the app announced the charge that did nothing and stayed
     * silent on the one that fired. Worse, it was a race: at 1 Hz, a broadcast
     * landing after the delay would have caught it, so the callout came and went
     * between flights.
     *
     * Latching on the **announcement** instead leaves each block open until its
     * charge actually fires. A charge that never fires is never announced, which
     * is what silence should mean. Callers reset at landing, where the flight's
     * guards are cleared.
     *
     * @return the modes to announce now, in ladder order.
     */
    class Latch {
        private val spoken = HashSet<DeployMode>()

        fun reset() = spoken.clear()

        fun due(config: LocatorConfig, state: RocketState): List<DeployMode> =
            LADDER.filter { (mode, floor) ->
                mode !in spoken &&
                        state.flightState >= floor &&
                        fired(config, state, mode)
            }.map { it.first }
                .also { spoken.addAll(it) }
    }
}
