package com.steampigeon.flightmanager.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.GeomagneticField
import android.hardware.SensorManager
import android.location.Location
import android.util.Log
import com.steampigeon.flightmanager.SpLog
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.maplibre.android.geometry.LatLng
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.KnownLocator
import com.steampigeon.flightmanager.UserPreferences
import com.steampigeon.flightmanager.data.ChannelMove
import com.steampigeon.flightmanager.data.ChannelMoveRunner
import com.steampigeon.flightmanager.data.ChannelSurvey as ChannelSurveyData
import com.steampigeon.flightmanager.data.LocatorSearch as LocatorSearchData
import com.steampigeon.flightmanager.data.LinkQuality
import com.steampigeon.flightmanager.data.LocatorAuth
import com.steampigeon.flightmanager.data.LocatorConnection
import com.steampigeon.flightmanager.data.LocatorNames
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.DeploymentTestParsed
import com.steampigeon.flightmanager.data.FlightDataRepository
import com.steampigeon.flightmanager.data.DeployChannelStats
import com.steampigeon.flightmanager.data.FlightEventIndex
// Aliased: ParsedMessage.FlightEvents (the wire message) would otherwise shadow
// the data class inside the sealed-class scope.
import com.steampigeon.flightmanager.data.FlightEvents as FlightEventsData
import com.steampigeon.flightmanager.data.FlightProfileMetadata
import com.steampigeon.flightmanager.data.FlightSample
import com.steampigeon.flightmanager.data.FlightLog
import com.steampigeon.flightmanager.data.FlightLogContents
import com.steampigeon.flightmanager.data.FlightLogFile
import com.steampigeon.flightmanager.data.FlightLogRecord
import com.steampigeon.flightmanager.data.FlightLogRecorder
import com.steampigeon.flightmanager.data.FlightLogStore
import com.steampigeon.flightmanager.data.LogCloseReason
import com.steampigeon.flightmanager.data.LogEvent
import com.steampigeon.flightmanager.data.LogSource
import com.steampigeon.flightmanager.data.RocketState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.math.sqrt
import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.MsgType
import com.steampigeon.flightmanager.data.NoseAxis
import com.steampigeon.flightmanager.data.PadAlertState
import com.steampigeon.flightmanager.data.Quaternionf
import com.steampigeon.flightmanager.data.PacketHeader
import com.steampigeon.flightmanager.data.PrelaunchParsed
import com.steampigeon.flightmanager.data.Protocol
import com.steampigeon.flightmanager.data.ReceiverConfig
import com.steampigeon.flightmanager.data.ReceiverInfoParsed
import com.steampigeon.flightmanager.data.SensorHealth
import com.steampigeon.flightmanager.data.VersionInfoParsed
import com.steampigeon.flightmanager.data.TelemetryParsed
import com.steampigeon.flightmanager.data.UserPreferencesSerializer
import com.steampigeon.flightmanager.data.Vec3f
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs

private const val TAG = "RocketViewModel"

/**
 * How far the phone must move before the magnetic declination is recomputed.
 *
 * Declination gradients run around 1° per 100 km in the continental US, so 10 km
 * buys well under a tenth of a degree — far below what the compass itself can
 * resolve. Large enough that a stationary phone reporting a fix every three
 * seconds never re-runs the field model, small enough that the drive to a launch
 * site does.
 */
private const val declinationRefreshM = 10_000f

/**
 * How long a degraded magnetometer accuracy reading is held before the published
 * value is allowed to recover — see `updateCompassAccuracy`.
 *
 * Long enough to bridge the 30 ms chatter measured under a magnet, short enough
 * that walking away from a source clears the warning while the user still
 * associates the two.
 */
private const val compassAccuracyHoldMs = 3_000L

/**
 * Spacing between field-magnitude heartbeat lines — see `logRestingMagnitude`.
 * Slow enough to sit under a logcat session for minutes without burying anything.
 */
private const val magnitudeLogIntervalMs = 5_000L

sealed class ParsedMessage {
    data class Prelaunch(val msg: PrelaunchParsed)           : ParsedMessage()
    data class Telemetry(val msg: TelemetryParsed)           : ParsedMessage()
    data class DeploymentTest(val msg: DeploymentTestParsed) : ParsedMessage()
    data class FlightMetadata(val frame: ByteArray)          : ParsedMessage()
    data class FlightData(val frame: ByteArray)              : ParsedMessage()
    data class FlightDataParity(val frame: ByteArray)        : ParsedMessage()
    data class ReceiverInfo(val msg: ReceiverInfoParsed)     : ParsedMessage()
    data class VersionInfo(val msg: VersionInfoParsed)       : ParsedMessage()
    data class FlightEvents(val msg: FlightEventsData)       : ParsedMessage()
    data class ChannelSurvey(val msg: ChannelSurveyParsed)   : ParsedMessage()
    data class LocatorSearch(val msg: LocatorSearchParsed)   : ParsedMessage()
}

/** Decoded ChannelSurveyResponse (ADR-0019 tier 3). Levels are index-by-channel. */
data class ChannelSurveyParsed(
    val status: ChannelSurveyData.Status,
    val homeChannel: Int,
    val levels: List<Int>,      // dBm, index == channel; empty when refused
    // Channels that received the long confirmation dwell. Only these are evidence
    // of a free channel — a coarse reading routinely misses a 1 Hz emitter.
    val confirmed: List<Int>,
    /** Locator frames decoded on each confirmed channel, index-aligned to [confirmed]. */
    val confirmedFrames: List<Int>,
    /** Who sent the first of them, index-aligned to [confirmed]; 0 = nobody, or a
     *  frame type carrying no id. Claimed identity, never authenticated. */
    val confirmedLocatorIds: List<Long>,
)

/** One streamed LocatorSearchResult: a channel's outcome, or a terminator. */
data class LocatorSearchParsed(
    val status: LocatorSearchData.Status,
    val channel: Int,
    val searched: Int,
    val total: Int,
    val found: Boolean,
    val armed: Boolean,
    val rssi: Int,
    val snr: Int,
    val locatorId: Long,
    val deviceName: String,
)

data class Vector(val distance: Int, val azimuth: Float, val ordinal: String, val elevation: Float)

val Context.userPreferencesDataStore: DataStore<UserPreferences> by dataStore(
    fileName = "user_prefs.pb",
    serializer = UserPreferencesSerializer
)

/**
 * [RocketViewModel] holds rocket locator status
 */

class RocketViewModel(application: Application) : AndroidViewModel(application) {
    override fun onCleared() {
        super.onCleared()
        // Before the service goes: an open log is closed and flushed here or not at
        // all, and "the app stopped" is itself the answer to why a log ends where it
        // does.
        closeFlightLog(LogCloseReason.AppStopped)
        stopService()
    }

    companion object {
        // Spacing given to points restored from a pre-timestamp recording. It is
        // a placeholder to keep the axis monotonic, not a claim about the
        // original cadence — those points are flagged synthetic and no marker
        // reads their time.
        const val LEGACY_PLACEHOLDER_INTERVAL_MS = 200L
        const val SAMPLES_PER_SECOND = 20
        const val HDOP_SCALE = 10
        const val ALTIMETER_SCALE = 10
        const val ACCELEROMETER_SCALE = 2048
        private const val BATTERY_SCALE = 8.0 / 4096
        const val FLIGHT_DATA_MESSAGE_SAMPLES = 30
        const val G_FORCE_MS2 = 9.80665

        // How long the connection to a locator survives its silence before another
        // authorized locator may take it.  Broadcasts arrive at 1 Hz, so this is
        // ~15 consecutive misses — deliberately longer than the 5 s "link up" test
        // used elsewhere in this file, because the two failures are asymmetric:
        // holding too long costs a few seconds after a genuine power-down, while
        // releasing too early puts another rocket's data on screen mid-flight.
        const val CONNECTION_HOLD_MS = 15_000L

        // How long the conflicting-traffic banner survives the other locator's
        // silence.  Long enough to read and act on (the Connect action needs a
        // deliberate tap), short enough that it clears within a few seconds of the
        // other locator being switched off.
        const val CONFLICT_HOLD_MS = 8_000L

        // A sweep is a ~0.8 s coarse pass plus a full broadcast period on each of the
        // five shortlisted channels — about 7 s — plus the BLE round trip. Generous
        // enough that a slow link never trips it, short enough that a receiver which
        // will never answer (firmware predating the survey) reports back promptly
        // instead of hanging. Must be raised with kSurveyConfirmDwellMs.
        const val SURVEY_TIMEOUT_MS = 15_000L

        // A search streams, so this is not "how long the whole run takes" — it is
        // how long a *gap* in the stream may last before the run is presumed dead.
        // One dwell is 1.4 s, so this is several missed results, and it restarts on
        // every message. A whole-band run is up to ~90 s and never trips it while it
        // is making progress.
        const val SEARCH_SILENCE_TIMEOUT_MS = 8_000L

        // How long to wait for a locator channel change to be confirmed by a
        // PreLaunchData on the new channel.  Re-based, not merely started, by the
        // receiver's transmit receipt: the clock used to start at the BLE write,
        // but the receiver cannot forward until it sees a PreLaunchData and is
        // 50-700 ms past it, so on the lossy channel that motivated the move the
        // whole window was spent waiting for a forwarding window (ADR-0011).
        const val CONFIG_CONFIRM_WINDOW_MS = 5_000L

        // Ceiling on the ADR-0011 probe — two 1.4 s dwells plus BLE round trips and
        // the receiver's own refusal replies.  Not the expected duration (~2.8 s):
        // it is the point past which the run is presumed never to answer, and the
        // search's own 8 s silence timeout normally ends things first.
        const val CHANNEL_PROBE_TIMEOUT_MS = 20_000L

        // How long to wait before re-asking a probe the receiver REFUSED.
        //
        // Sized against the receiver's kPendingTxStaleMs (10 s), and the coupling is
        // deliberate rather than incidental: the thing being waited out is usually
        // our OWN undelivered LocatorCfgChgRequest, which the receiver counts as an
        // operator command and which blocks a search until it goes stale. The first
        // probe lands ~5 s after the move, so this puts the retry past the 10 s drop.
        // If that firmware constant moves, this must move with it.
        const val CHANNEL_PROBE_REFUSED_RETRY_MS = 6_000L

        // How often the link verdict re-evaluates itself with no packet to trigger
        // it. Fast enough that the note and the red marker change together.
        const val LINK_LIVENESS_TICK_MS = 500L

        // How often to ask the receiver for a channel reading, once polling starts.
        // Comfortably inside LinkQuality.STALE_MEASUREMENT_MS so the floor it
        // returns is still live when the next one replaces it — a poll slower than
        // the freshness window leaves gaps the note visibly flickers through.
        const val CHANNEL_WATCH_TICK_MS = 2_000L

        // How long the locator must be unheard before polling starts at all.
        //
        // Deliberately LONGER than LOSSY_GAP_MS, and deliberately not the same
        // constant.  A distant rocket routinely drops one or two 1 Hz broadcasts,
        // which is a 2-3 s gap — long enough to count as loss and redden the
        // marker, but not a reason to start polling, because the locator is still
        // transmitting and every packet that survives carries the floor anyway.
        //
        // Polling through those routine gaps was not merely redundant, it was
        // corrosive.  The receiver's noise floor is a PEAK-since-last-report that
        // every reader drains, so an extra reader shortens the window each report
        // covers, and a peak over a shorter window is never higher than one over a
        // longer window.  Those systematically lower readings then feed
        // updateQuietestFloor, which keeps the MINIMUM — so polling during flight
        // would have walked the session baseline down and made the 12 dB "risen"
        // test creep toward firing on its own.
        //
        // 5 s sits above normal in-flight loss and far below any real locator-off
        // or receiver-off case, which are indefinite.
        const val CHANNEL_WATCH_SILENCE_MS = 5_000L

        // FlightMetadataRequest retry backoff.  The first wait must comfortably
        // exceed a normal round trip — the locator holds the response ~50 ms and
        // the receiver may sit on the forward until its next safe window — so a
        // healthy fetch answers on attempt 1 and never retries.  The cap keeps
        // retries under the locator's 30 s metadata-idle timeout, so a link that
        // recovers is picked back up instead of having dropped to Disarmed.
        const val METADATA_RETRY_INITIAL_MS = 3_000L
        const val METADATA_RETRY_MAX_MS     = 12_000L

        // How long the locator's deployment-test countdown must go quiet before
        // the app concludes that no test is running.
        //
        // The countdown arrives at 1 Hz while a test is live and simply stops
        // when it ends — fired or canceled — so silence is the only completion
        // signal the protocol offers.  3 s absorbs two consecutive lost frames
        // without declaring a live charge safe, which is the direction this has
        // to fail in: a countdown shown a second too long costs nothing, while
        // one cleared a second too early tells the operator a charge is dead
        // while it is still counting down to firing.
        const val DEPLOYMENT_TEST_SILENCE_MS = 3_000L
        const val RAD2DEG = 57.295779513082320876
    }

    //private val _userPreferences = MutableStateFlow(UserPreferences.getDefaultInstance())
    //val userPreferences: StateFlow<UserPreferences> = _userPreferences.asStateFlow()

    init {
        viewModelScope.launch {
            application.userPreferencesDataStore.data.collect { preferences ->
                //_userPreferences.value = preferences
                _locatorStatisticsOffset.value = IntOffset(preferences.locatorStatisticsOffsetX, preferences.locatorStatisticsOffsetY)
                _voiceEnabled.value = preferences.voiceEnabled
                // Left null when absent so resolveMapMaxZoom applies the default;
                // proto3 would otherwise hand us a 0 indistinguishable from a real
                // stored level.
                _mapMaxZoom.value =
                    if (preferences.hasMapMaxZoom()) preferences.mapMaxZoom else null
                _voiceName.value = preferences.voiceName
                _remoteReceiverConfig.update { it.copy(deviceName = preferences.receiverName) }
                _knownLocators.value = preferences.knownLocatorsMap.mapKeys { it.key.toLong() and 0xFFFFFFFFL }
                knownLocatorsLoaded = true
            }
        }
        // Reset receiver config whenever the user selects a *different* receiver so
        // that stale name/channel from the previous device is never shown.
        viewModelScope.launch {
            var prevAddress: String? = null
            BluetoothManagerRepository.receiverDevice.collect { device ->
                val addr = device?.address
                if (addr != null && addr != prevAddress && prevAddress != null) {
                    _remoteReceiverConfig.value = ReceiverConfig()
                    _receiverConfigChanged.value = false
                }
                prevAddress = addr
            }
        }
    }

    val currentContext = application
    suspend fun saveUserPreferences() {
        currentContext.userPreferencesDataStore.updateData { userPreferences ->
            userPreferences.toBuilder()
                .setLocatorStatisticsOffsetX(_locatorStatisticsOffset.value.x)
                .setLocatorStatisticsOffsetY(_locatorStatisticsOffset.value.y)
                .setVoiceEnabled(_voiceEnabled.value)
                // Written only once the user has actually chosen. saveUserPreferences
                // is called from several unrelated places (the stats-panel drag, for
                // one), so writing it unconditionally would stamp the current default
                // onto every install that merely moved a panel — and a later change
                // to that default would then never reach them.
                .apply { _mapMaxZoom.value?.let { setMapMaxZoom(it) } }
                .setVoiceName(_voiceName.value)
                .setReceiverName(_remoteReceiverConfig.value.deviceName)
                .build()
        }
    }

    private val _voiceEnabled = MutableStateFlow<Boolean>(false)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    fun updateVoiceEnabled(newVoiceEnabled: Boolean) {
        _voiceEnabled.value = newVoiceEnabled
    }

    /**
     * The closest zoom the live map's auto-zoom may frame to, or null when the
     * user has not chosen one.  Null rather than a default value because the
     * default and the valid range belong with the map — see [resolveMapMaxZoom],
     * which every reader goes through.
     */
    private val _mapMaxZoom = MutableStateFlow<Int?>(null)
    val mapMaxZoom: StateFlow<Int?> = _mapMaxZoom.asStateFlow()

    fun updateMapMaxZoom(newMaxZoom: Int) {
        _mapMaxZoom.value = newMaxZoom
    }

    private val _voiceName = MutableStateFlow<String>("us-x-iob-local")
    val voiceName: StateFlow<String> = _voiceName.asStateFlow()

    fun updateVoiceName(newVoiceName: String) {
        _voiceName.value = newVoiceName
    }

    private val _locatorStatisticsOffset = MutableStateFlow<IntOffset>(IntOffset(0,0))
    val locatorStatisticsOffset: StateFlow<IntOffset> = _locatorStatisticsOffset.asStateFlow()

    fun updateLocatorStatisticsOffset(newOffset: IntOffset) {
        _locatorStatisticsOffset.value = newOffset
    }

    // Degrees to add to a magnetic heading to get a true one; east is positive.
    // TYPE_ROTATION_VECTOR reports azimuth against MAGNETIC north, while
    // locatorVector() computes a great-circle bearing against TRUE north. Comparing
    // the two without this term leaves the declination as a standing error in the
    // AR overlay and in the map rotation — measured at +15.0° where this is flown —
    // and it does not average out: it is a bias, not noise, which is why it read as
    // "the compass is a bit off" rather than as a bug. See ADR-0023.
    //
    // The device side is what gets corrected, not the locator bearing. MapLibre's
    // camera bearing is true-north referenced too, so pushing everything to magnetic
    // would fix the overlay and leave the rotated map wrong by the same angle.
    private val _magneticDeclination = MutableStateFlow(0f)
    // Where the declination in force was evaluated, so a 3-second location update
    // does not re-run the WMM. Declination moves ~1° per 100 km at mid-latitudes,
    // so anything short of a long drive changes nothing a compass rose can show.
    private var declinationAnchor: Location? = null

    /**
     * How much to trust [handheldDeviceAzimuth], as one of the
     * `SensorManager.SENSOR_STATUS_*` constants.
     *
     * Fed by **three** sources, because no one of them is reliably available:
     *
     *  - Pixel 9 Pro XL: the magnetometer accuracy flag reports and responds to a
     *    magnet within milliseconds; the rotation vector never fires at all.
     *  - Moto G 5S: the rotation vector fires once at `HIGH` and is pinned there
     *    under a magnet held against the case; the magnetometer never fires once.
     *    **Neither flag is usable on this device.**
     *
     * Two devices, two opposite failures, and on the second one no vendor flag works
     * at all — a field pinned at `HIGH` being indistinguishable from a healthy
     * compass. So the third source is [updateFieldMagnitude], which asks the physics
     * instead of the vendor: a total field outside what the Earth can produce means
     * interference, whatever the flags claim.
     *
     * The worst of whichever sources have actually spoken is published — see
     * [recomputeCompassAccuracy].
     *
     * Starts at [SensorManager.SENSOR_STATUS_ACCURACY_HIGH] rather than at
     * `UNRELIABLE`: a device whose compass is fine may never deliver an accuracy
     * callback at all, and opening on a warning that no sensor event will ever
     * clear is worse than opening on a claim the first callback can withdraw.
     */
    private val _compassAccuracy = MutableStateFlow(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
    val compassAccuracy: StateFlow<Int> = _compassAccuracy.asStateFlow()
    // Null means "this source has never spoken", which is distinct from HIGH and
    // cannot be represented by the reading alone. Telling those apart is the whole
    // diagnosis: a source that never speaks must not hold the verdict at HIGH and
    // mask a source that does.
    private var magnetometerAccuracy: Int? = null
    private var fusedAccuracy: Int? = null
    private var fieldMagnitudeAccuracy: Int? = null
    // Worst of the reported sources, before the hold below is applied.
    private var rawCompassAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private var compassAccuracyReleaseJob: Job? = null

    /** Raw `TYPE_MAGNETIC_FIELD` accuracy. Live on the Pixel, silent on the Moto G. */
    fun updateCompassAccuracy(accuracy: Int) {
        if (magnetometerAccuracy != accuracy) {
            magnetometerAccuracy = accuracy
            SpLog.d("Compass", "magnetometer accuracy → ${accuracyName(accuracy)}")
            recomputeCompassAccuracy()
        }
    }

    /** Fused `TYPE_ROTATION_VECTOR` accuracy. Live on the Moto G, silent on the Pixel. */
    fun updateFusedAccuracy(accuracy: Int) {
        if (fusedAccuracy != accuracy) {
            fusedAccuracy = accuracy
            SpLog.d("Compass", "rotation-vector accuracy → ${accuracyName(accuracy)}")
            recomputeCompassAccuracy()
        }
    }

    /**
     * Total field strength, classified against what the Earth can account for.
     *
     * The third source, and the only one that does not depend on the OEM telling
     * the truth. Both accuracy flags can be — and on a Moto G 5S both are —
     * permanently silent or pinned at `HIGH`, which is indistinguishable from a
     * healthy compass and leaves the warning unreachable. The field magnitude is
     * arithmetic on readings the app already receives and was previously throwing
     * away, and a magnet cannot hide from it.
     *
     * **What this does and does not detect.** It detects *interference* — a magnet,
     * a truck bed, a laptop — by the field being too strong or too weak to be the
     * Earth's. It does **not** detect *miscalibration*: a stale hard-iron offset can
     * rotate the heading badly while the magnitude stays perfectly plausible. So it
     * is a third opinion feeding the same worst-of verdict, never a replacement for
     * the accuracy flags on devices where those work.
     *
     * Classification changes are rare by nature, so [recomputeCompassAccuracy] runs
     * on a threshold crossing rather than on every sample — which also keeps a
     * steady out-of-range reading from restarting the hold once a second.
     */
    fun updateFieldMagnitude(values: FloatArray) {
        if (values.size < 3) return
        val magnitudeUt = fieldMagnitudeUt(values)
        val classified = classifyFieldMagnitude(magnitudeUt)
        if (fieldMagnitudeAccuracy != classified) {
            fieldMagnitudeAccuracy = classified
            SpLog.d(
                "Compass",
                "field magnitude %.1f µT → %s".format(magnitudeUt, accuracyName(classified))
            )
            recomputeCompassAccuracy()
        }
        logRestingMagnitude(magnitudeUt)
    }

    private var lastMagnitudeLogMs = 0L

    /**
     * Debug-only heartbeat for the field magnitude, so a value that is not changing
     * band is still observable.
     *
     * Logging only on a threshold crossing makes a steady reading invisible, which
     * is exactly what is needed to judge whether the thresholds are right for a
     * device: "inside 20-70 µT" does not say whether a phone rests at 48 or at 68,
     * and only one of those has any margin before it starts crying wolf. The same
     * blind spot — a signal that says nothing when nothing changes — has now hidden
     * two separate problems in this feature.
     *
     * Guarded as a block, not per line: this runs on every magnetometer sample, and
     * despite the 1 s registration those arrive every 85-100 ms, so the format call
     * would otherwise build ~11 strings a second in release. That is the case
     * [SpLog] calls out.
     */
    private fun logRestingMagnitude(magnitudeUt: Float) {
        if (!SpLog.enabled) return
        val now = System.currentTimeMillis()
        if (now - lastMagnitudeLogMs < magnitudeLogIntervalMs) return
        lastMagnitudeLogMs = now
        // "heartbeat", not "resting": this fires on a timer regardless of what the
        // phone is near, and labelling a reading taken next to a magnet as resting
        // is how a baseline gets misread.
        SpLog.d("Compass", "field magnitude %.1f µT (heartbeat)".format(magnitudeUt))
    }

    /**
     * Publish the worst accuracy any live source reports, held over a trailing window.
     *
     * **Worst-of, not average or preferred.** Both fields are attempts to say the same
     * thing, and on every device measured so far only one of them says anything at all,
     * so in practice this reduces to "whichever works". Where both report, the
     * pessimistic reading wins: a missed warning costs someone walking a wrong bearing
     * through brush, a spurious one costs an unnecessary figure-eight. That trade is
     * not symmetric. It is also the case not yet observed on real hardware — a device
     * whose two sources disagree persistently would show the prompt for as long as the
     * gloomier one is unhappy.
     *
     * **The hold.** Under real interference the signal does not degrade and stay
     * degraded, it chatters: measured on a Pixel 9 Pro XL with a magnet swept around
     * the case, UNRELIABLE↔LOW and UNRELIABLE↔MEDIUM transitions with dwell times as
     * short as 30 ms, sustained for seconds. Fed straight to the UI that is a warning
     * that strobes and an AR marker that blinks — unreadable, and it under-reports the
     * problem by spending half its time looking fine. So a degraded reading takes
     * effect at once and is then held for [compassAccuracyHoldMs] past the last bad
     * reading. Recovery is what waits; the warning never does. The same phone at rest
     * produced zero transitions in 25 s, so the hold only engages when something is
     * genuinely disturbing the field.
     */
    private fun recomputeCompassAccuracy() {
        // listOfNotNull, not a default: a source that has never reported contributes
        // nothing rather than contributing HIGH, which would let a silent sensor
        // outvote a live one saying UNRELIABLE.
        val worst = listOfNotNull(magnetometerAccuracy, fusedAccuracy, fieldMagnitudeAccuracy)
            .minOrNull() ?: SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        rawCompassAccuracy = worst

        if (worst <= _compassAccuracy.value) {
            // Worse than what is published (or equal, which re-arms the hold).
            _compassAccuracy.value = worst
            compassAccuracyReleaseJob?.cancel()
            compassAccuracyReleaseJob = viewModelScope.launch {
                delay(compassAccuracyHoldMs)
                // Whatever the sensors settled on, not the reading that opened the
                // window — a burst that ends better than it started recovers to
                // where it actually ended.
                _compassAccuracy.value = rawCompassAccuracy
            }
        } else if (compassAccuracyReleaseJob?.isActive != true) {
            // An improvement outside any hold window applies immediately; inside
            // one it is ignored, which is what stops the chatter from surfacing.
            _compassAccuracy.value = worst
        }
    }

    private fun accuracyName(accuracy: Int): String = when (accuracy) {
        SensorManager.SENSOR_STATUS_NO_CONTACT      -> "NO_CONTACT"
        SensorManager.SENSOR_STATUS_UNRELIABLE      -> "UNRELIABLE"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW    -> "LOW"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH   -> "HIGH"
        else                                        -> "UNKNOWN($accuracy)"
    }

    private val _handheldDeviceAzimuth = MutableStateFlow<Float>(0f)
    val handheldDeviceAzimuth: StateFlow<Float> = _handheldDeviceAzimuth.asStateFlow()
    private val _lastHandheldDeviceAzimuth = MutableStateFlow<Float>(0f)
    val lastHandheldDeviceAzimuth: StateFlow<Float> = _lastHandheldDeviceAzimuth.asStateFlow()
    fun updateLastHandheldDeviceAzimuth(newLastHandheldDeviceAzimuth: Float) {
        _lastHandheldDeviceAzimuth.value = newLastHandheldDeviceAzimuth
    }
    // Elevation of the device's screen-normal axis, in signed degrees (−90..+90) — NOT
    // wrapped to 0..360. Both writers must keep this convention: the AR overlay consumes it
    // through a wrapping delta and can't tell the difference, but the map's tilt-follow mode
    // reads it raw and would flip hard at the sign boundary.
    private val _handheldDevicePitch = MutableStateFlow<Float>(0f)
    val handheldDevicePitch: StateFlow<Float> = _handheldDevicePitch.asStateFlow()
    // Camera azimuth: direction the back camera is pointing (landscape-remapped Z axis).
    // Distinct from handheldDeviceAzimuth (direction of phone top / Y axis) which is
    // used for the map bearing and is 90° off in landscape.
    private val _handheldCameraAzimuth = MutableStateFlow<Float>(0f)
    val handheldCameraAzimuth: StateFlow<Float> = _handheldCameraAzimuth.asStateFlow()
    private val _locatorDistance = MutableStateFlow<Int>(0)
    val locatorDistance: StateFlow<Int> = _locatorDistance.asStateFlow()
    private val _locatorAzimuth = MutableStateFlow<Float>(0f)
    val locatorAzimuth: StateFlow<Float> = _locatorAzimuth.asStateFlow()
    private val _locatorOrdinal = MutableStateFlow<String>("")
    val locatorOrdinal: StateFlow<String> = _locatorOrdinal.asStateFlow()
    private val _locatorElevation = MutableStateFlow<Float>(0f)
    val locatorElevation: StateFlow<Float> = _locatorElevation.asStateFlow()
    /**
     * False while [locatorDistance] holds a figure the locator cannot be at — see
     * [distanceIsPlausible].  The distance is still published: this says whether
     * to quote it, and the display reads "Unknown" instead.
     */
    private val _locatorDistancePlausible = MutableStateFlow(true)
    val locatorDistancePlausible: StateFlow<Boolean> = _locatorDistancePlausible.asStateFlow()
    // The last distance measured while the locator actually had a fix. The anchor
    // for the jump test, so the envelope of believable movement always grows from
    // a real measurement rather than from another unverified reading.
    private var lastFixDistanceM: Int? = null
    // How far the rocket could have traveled since that fix, integrated a step at
    // a time at the bound for the phase it was in — a fixless stretch that starts
    // under canopy and ends on the ground is charged descent rates and then ground
    // rates, not one or the other for the whole gap.
    private var travelBudgetM = 0.0
    private var lastVectorAtMs = 0L

    fun updateLocatorVector(newLocatorVector: Vector) {
        _locatorDistance.value = newLocatorVector.distance
        _locatorAzimuth.value = newLocatorVector.azimuth
        _locatorOrdinal.value = newLocatorVector.ordinal
        _locatorElevation.value = newLocatorVector.elevation

        val state = _rocketState.value
        val hasFix = locatorHasFix(state.satellites.toInt(), state.gpsStatus)
        val now = System.currentTimeMillis()
        // First call has no interval behind it, so it opens no budget.
        val elapsedMs = if (lastVectorAtMs == 0L) 0L else now - lastVectorAtMs
        lastVectorAtMs = now
        if (!hasFix) travelBudgetM += phaseTravelM(state.flightState, elapsedMs)

        _locatorDistancePlausible.value = distanceIsPlausible(
            distanceM = newLocatorVector.distance,
            locatorHasFix = hasFix,
            lastFixDistanceM = lastFixDistanceM,
            travelBudgetM = travelBudgetM,
        )
        // Re-anchored only on a real fix, and only on one that passed: a reading
        // over the range ceiling is wrong whatever the satellite count says, and
        // adopting it would move the anchor to a place the rocket has never been.
        if (hasFix && _locatorDistancePlausible.value) {
            lastFixDistanceM = newLocatorVector.distance
            travelBudgetM = 0.0
        }
    }

    /**
     * Display state
     */
    private val _rocketState = MutableStateFlow(RocketState())
    val rocketState: StateFlow<RocketState> = _rocketState.asStateFlow()

    // The handheld device's own GPS fix. Held here rather than in FlightMapScreen so it
    // survives navigation: a composable-scoped `remember` is discarded on the way to the
    // flight profiles screen, and the map would come back with no tracker position until
    // the next fix arrived — re-framing the camera, and (before the bounds guard in
    // MapCameraController) crashing on a one-point LatLngBounds.
    // Null means "no fix yet"; callers substitute a 0,0 stand-in that reads as absent.
    private val _trackerLocation = MutableStateFlow<Location?>(null)
    val trackerLocation: StateFlow<Location?> = _trackerLocation.asStateFlow()
    fun updateTrackerLocation(newTrackerLocation: Location) {
        _trackerLocation.value = newTrackerLocation
        refreshDeclination(newTrackerLocation)
    }

    /**
     * Re-evaluate [_magneticDeclination] for a new fix, if the fix has moved far
     * enough from the one behind the current value to matter.
     *
     * The threshold is distance, not time: the field model is quoted for an epoch
     * and drifts by a fraction of a degree per year, so nothing about sitting still
     * invalidates it, while driving to a launch site can.
     */
    private fun refreshDeclination(location: Location) {
        val anchor = declinationAnchor
        if (anchor != null && anchor.distanceTo(location) < declinationRefreshM) return
        declinationAnchor = location
        _magneticDeclination.value = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis(),
        ).declination
        SpLog.d("Compass", "declination → %.2f°".format(_magneticDeclination.value))
    }

    private val _remoteLocatorConfig = MutableStateFlow<LocatorConfig>(LocatorConfig())
    val remoteLocatorConfig: StateFlow<LocatorConfig> = _remoteLocatorConfig.asStateFlow()

    private val _remoteReceiverConfig = MutableStateFlow<ReceiverConfig>(ReceiverConfig())
    val remoteReceiverConfig: StateFlow<ReceiverConfig> = _remoteReceiverConfig.asStateFlow()

    // -------------------------------------------------------------------------
    // Locator authorization / connection
    //
    // Two different questions, deliberately kept apart:
    //
    //   authorized — do we hold a password key that verifies this locator's
    //                auth_tag (or is it open, key 0)?  This is a *set*: any number
    //                of locators can be authorized at once, and someone who owns
    //                two normally has both.
    //   connected  — which single locator are we displaying and commanding?  One
    //                element of that set, claimed once and held until it goes
    //                silent or the user switches deliberately.
    //
    // Collapsing the two is what let a second authorized locator seize the display
    // packet by packet (see ADR-0006, "One connection at a time").  An arriving
    // frame may never reassign the connection on its own.
    // -------------------------------------------------------------------------
    private val _knownLocators = MutableStateFlow<Map<Long, KnownLocator>>(emptyMap())
    /** Read-only view, so a scan result can put a name against an id and the search
     *  can offer "which of my locators am I looking for". */
    val knownLocators: StateFlow<Map<Long, KnownLocator>> = _knownLocators.asStateFlow()

    // The locator_id the app is currently connected to (null = none → sending gated off).
    private val _connectedLocatorId = MutableStateFlow<Long?>(null)
    val connectedLocatorId: StateFlow<Long?> = _connectedLocatorId.asStateFlow()
    val locatorConnected: StateFlow<Boolean> =
        _connectedLocatorId.map { it != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Session baseline for the idle-channel noise floor (ADR-0019).  Relative, not
    // absolute: SX126x RSSI near the floor is uncalibrated and varies unit to unit,
    // so "elevated" is only meaningful against the quietest reading this receiver
    // has actually produced.  Reset on a channel change — a different channel's
    // floor is a different measurement, and carrying the old baseline over would
    // either mask a busy channel or slander a quiet one.
    @Volatile private var quietestNoiseFloor = LinkQuality.NOISE_FLOOR_UNKNOWN

    // The same baseline for floors polled while the locator is silent, kept apart
    // from the one above because they are not the same measurement.  The receiver
    // samples inside the post-broadcast safe window while the locator is up and
    // continuously once it goes overdue, so the silent regime reports the peak of
    // several times as many samples and reads systematically higher.
    //
    // Sharing one baseline latched the alert on permanently: a minimum-keeping
    // baseline built in the quieter regime can never rise to meet the busier one,
    // so every polled reading looked "risen" and stayed that way for the session.
    @Volatile private var quietestPolledFloor = LinkQuality.NOISE_FLOOR_UNKNOWN

    // Whether the floor currently in rocketState came from a poll rather than from
    // a broadcast.  Selects which baseline it is judged against, and whether the
    // absolute BUSY_FLOOR_DBM test applies at all.
    @Volatile private var floorFromPoll = false

    // Wall clock of the last missed broadcast.  Loss has to be remembered rather
    // than judged per packet: the classifier only runs when a packet ARRIVES, and
    // an arriving packet has just ended the gap, so an instantaneous test reported
    // the link as healthy for every packet the user could actually see.
    @Volatile private var lastLossMs = 0L

    // Wall clock of the last broadcast received from a locator that is NOT the
    // connected one. Deliberately separate from lastConflictFrameMs, which drives
    // the banner and is suppressed once the user dismisses it: this is the RF fact,
    // and dismissing a banner must not hide the interference from the classifier.
    @Volatile private var lastForeignBroadcastMs = 0L

    // When rssi/snr were last measured, and when the noise floor was last measured.
    //
    // Separate clocks because the two no longer share a source.  rssi/snr can only
    // come from a packet that arrived; the floor also arrives on ReceiverInfo,
    // which the receiver answers with no locator involved.  During a dropout that
    // makes the floor live and the packet pair stale, and collapsing them into one
    // timestamp would throw away the only measurement still being taken.
    //
    // Both exist because the classifier runs on a timer as well as on receipt, and
    // a timer tick has no measurement of its own — see LinkQuality.STALE_MEASUREMENT_MS
    // for what re-deriving a verdict from frozen samples did.
    @Volatile private var lastPacketMeasurementMs = 0L
    @Volatile private var lastFloorMeasurementMs = 0L

    /**
     * Classify the link for a just-accepted broadcast.  Call this *before* the
     * rocketState update, not inside it: it mutates [quietestNoiseFloor], and an
     * update lambda may re-run under contention.  It also needs the previous
     * message time, which the update is about to overwrite.
     */
    private fun classifyLink(rssi: Int, snr: Int, noiseFloor: Int, badFrames: Int, currentTime: Long): LinkQuality.Verdict {
        quietestNoiseFloor = LinkQuality.updateQuietestFloor(quietestNoiseFloor, noiseFloor)
        // A broadcast carries both kinds of measurement, so it refreshes both clocks.
        lastPacketMeasurementMs = currentTime
        if (noiseFloor != LinkQuality.NOISE_FLOOR_UNKNOWN) {
            lastFloorMeasurementMs = currentTime
            // Sampled in the safe window, so the absolute threshold applies again.
            floorFromPoll = false
        }
        val previous = _rocketState.value.lastMessageTime
        // No previous broadcast (fresh session) is not a gap.
        val gapMs = if (previous == 0L) 0L else currentTime - previous
        // A corrupted frame is loss we can SEE, not loss inferred from a gap, and it
        // is available on the very packet that reports it rather than one period
        // later. Both feed the same memory.
        lastLossMs = LinkQuality.updateLastLoss(lastLossMs, currentTime, gapMs, badFrames)
        return LinkQuality.classify(
            rssi, snr, noiseFloor, quietestNoiseFloor,
            lossy = LinkQuality.isLossy(lastLossMs, currentTime),
            // Another locator decoded on our channel. Reuses the loss window since
            // it describes the same thing — how recently the channel was contested.
            foreignLocator = LinkQuality.isLossy(lastForeignBroadcastMs, currentTime),
            // Both measurements were taken by the packet being classified, so this
            // path is fresh by construction. The timer path is where it matters.
            packetFresh = true,
            floorFresh = noiseFloor != LinkQuality.NOISE_FLOOR_UNKNOWN,
        )
    }

    // Wall clock of the last frame accepted from the connected locator.  Another
    // authorized locator may take the connection only once this has gone stale by
    // [CONNECTION_HOLD_MS] — that is what keeps a momentary fade from handing the
    // display to whoever else happens to be audible.
    @Volatile private var lastConnectedFrameMs = 0L

    // Wall clock of the last ReceiverInfo whose channel matched a move in flight —
    // the ADR-0011 transmit receipt.  The receiver arms its follow only after
    // Send() and applies it only after TxDone, so this message cannot exist unless
    // the forward actually went on air; its arrival is the first moment the confirm
    // window is measuring anything real.
    //
    // Used ONLY to re-base that window, deliberately never to short-circuit.  Its
    // absence is ambiguous — a receiver predating this change never sends one — and
    // reading absence as "the command was never transmitted" would leave a genuine
    // split unrepaired against older firmware.  The probe resolves that case
    // correctly anyway, by hearing the locator on the old channel.
    @Volatile private var channelMoveReceiptMs = 0L

    // The locator the pending move was addressed to, captured when it was sent.
    // The probe attributes its hits with this: an unattributed hit cannot be
    // evidence, and a *different* locator's hit on the new channel must never read
    // as confirmation.
    @Volatile private var channelMoveLocatorId: Long? = null

    // Wall clock of the last frame heard from the conflicting locator.  The banner
    // is held for [CONFLICT_HOLD_MS] after that rather than being cleared by the
    // next good packet: with two locators sharing a channel the broadcasts
    // interleave, and clearing on every good packet made the banner flash faster
    // than it could be read, let alone acted on.
    @Volatile private var lastConflictFrameMs = 0L

    // A locator_id heard on the air that is not the connected one — either
    // unauthorized, or authorized but not the connection holder. Drives a
    // non-blocking warning banner; cleared on connect, dismiss, or channel change.
    private val _conflictLocatorId = MutableStateFlow<Long?>(null)
    val conflictLocatorId: StateFlow<Long?> = _conflictLocatorId.asStateFlow()

    // Active password challenge, shown app-wide. Raised either by a receiver channel
    // change that landed on an unknown locator (previousChannel != null → cancel
    // reverts the channel), or passively on first contact with an unknown locator
    // while not connected (previousChannel == null → cancel just dismisses).
    data class LocatorChallenge(val locatorId: Long, val deviceName: String, val previousChannel: Int?)
    private val _challenge = MutableStateFlow<LocatorChallenge?>(null)
    val challenge: StateFlow<LocatorChallenge?> = _challenge.asStateFlow()

    // True after a wrong password; the dialog stays open to retry.
    private val _challengeError = MutableStateFlow(false)
    val challengeError: StateFlow<Boolean> = _challengeError.asStateFlow()

    // PreLaunchData frame from the challenged locator, refreshed while the dialog is
    // open, so a typed password is verified against that locator's auth_tag.
    @Volatile private var challengeFrame: ByteArray? = null
    // Latest PreLaunchData frame (any locator), so the conflict banner's Connect
    // action can re-raise a challenge after a dismiss.
    @Volatile private var lastPrelaunchFrame: ByteArray? = null
    private var lastPrelaunchLocatorId: Long? = null
    private var lastPrelaunchDeviceName: String = ""
    // Passive challenges the user dismissed this session (don't auto-reprompt).
    private val declinedLocatorIds = mutableSetOf<Long>()
    // Conflict banners the user dismissed. Distinct from declinedLocatorIds, which
    // suppresses the password *dialog*; this suppresses the *banner*. Cleared on
    // re-entering Receiver Settings.
    private val dismissedConflictIds = mutableSetOf<Long>()
    // True once the known-locator store has loaded, so the first PreLaunchData does not
    // passively prompt for an already-known locator before the store is read.
    @Volatile private var knownLocatorsLoaded = false
    private var awaitingChannelRecognition = false
    private var channelChangePreviousChannel = 0

    // ── Channel survey (ADR-0019 tier 3) ──────────────────────────────────────
    // null = never run this session. Sweeping is on demand only: it costs ~1 s of
    // deafness, and the decision it informs (which channel to use) is made once,
    // on the ground.
    private val _channelSurvey = MutableStateFlow<ChannelSurveyData.Result?>(null)
    val channelSurvey: StateFlow<ChannelSurveyData.Result?> = _channelSurvey.asStateFlow()

    private val _surveyInProgress = MutableStateFlow(false)
    val surveyInProgress: StateFlow<Boolean> = _surveyInProgress.asStateFlow()

    private var surveyTimeoutJob: Job? = null

    /** Ask the receiver to sweep. The receiver independently refuses while armed;
     *  this only avoids sending a request we already know it will reject.
     *
     *  Always armed with a timeout. There is no other way out of the in-progress
     *  state, so anything that swallows the reply — a receiver whose firmware
     *  predates the survey and simply never answers, a dropped BLE notification, a
     *  reset mid-sweep — would otherwise leave the button disabled and the UI
     *  showing "Scanning…" until the app was restarted. */
    fun requestChannelSurvey(service: BluetoothService?) {
        if (_surveyInProgress.value) return
        _channelSurvey.value = null
        surveyTimeoutJob?.cancel()
        if (service?.requestChannelSurvey() != true) {
            _surveyInProgress.value = false
            _channelSurvey.value = surveyFailed()
            return
        }
        _surveyInProgress.value = true
        surveyTimeoutJob = viewModelScope.launch {
            delay(SURVEY_TIMEOUT_MS)
            if (_surveyInProgress.value) {
                _surveyInProgress.value = false
                _channelSurvey.value = surveyFailed()
            }
        }
    }

    private fun surveyFailed() = ChannelSurveyData.analyze(
        ChannelSurveyData.Status.Unknown, emptyList(), _remoteReceiverConfig.value.channel,
    )

    // ── Locator search (#33 follow-up) ────────────────────────────────────────
    // null = never run this session.
    private val _locatorSearch = MutableStateFlow<LocatorSearchData.Run?>(null)
    val locatorSearch: StateFlow<LocatorSearchData.Run?> = _locatorSearch.asStateFlow()

    private var searchTimeoutJob: Job? = null

    /**
     * Channels worth trying for [targetLocatorId], or for anything at all when it
     * is null.
     *
     * Exposed so the UI can say what it is about to do before it does it — a
     * search is seconds of deafness per channel, and "I am going to try 4, 12 and
     * 0" is a very different proposition from an unexplained progress bar.
     */
    fun searchCandidates(targetLocatorId: Long? = null): List<Int> {
        val known = _knownLocators.value
        return LocatorSearchData.candidates(
            currentChannel = _remoteReceiverConfig.value.channel,
            targetChannel = targetLocatorId?.let { known[it]?.lastChannelOrNull() },
            // Every other locator this receiver has been tuned to. A receiver shared
            // across several rockets has been on each of their channels at some
            // point, and that history is the whole reason the short list usually wins.
            // Sorted by id, because `known` is built from a protobuf map whose
            // iteration order is unspecified. The load-bearing positions are fixed
            // either way — target first, default and current last — but WHICH of
            // several remembered channels survive the 16-channel cap, and in what
            // order, could differ between two runs with identical stored state. With
            // more than 14 remembered locators that changes which channels are
            // actually searched. Ported from iOS `LinkViewModel.searchCandidates`.
            knownChannels = known
                .filterKeys { it != targetLocatorId }
                .toSortedMap()
                .values.mapNotNull { it.lastChannelOrNull() },
            attemptedChannel = _pendingChannelMove.value ?: channelChangePreviousChannel
                .takeIf { awaitingChannelRecognition },
        )
    }

    /** A stored channel, or null when this locator has never been heard — which is
     *  not the same as channel 0, the factory default (ADR-0025). */
    private fun KnownLocator.lastChannelOrNull(): Int? =
        if (hasLastChannel()) lastChannel else null

    /**
     * Start a search over [channels], or over the whole band when it is empty.
     *
     * [targetLocatorId] stops the run on the first frame from that locator; 0 makes
     * it a census of everything on the listed channels. The receiver enforces its
     * own refusals (armed, in flight, radio busy) — this only avoids sending a
     * request we already know will be rejected.
     */
    fun startLocatorSearch(
        service: BluetoothService?,
        channels: List<Int>,
        targetLocatorId: Long = 0L,
    ) {
        if (_locatorSearch.value?.running == true) return
        val wholeBand = channels.isEmpty()
        if (service?.requestLocatorSearch(channels, targetLocatorId) != true) {
            _locatorSearch.value = LocatorSearchData.Run(
                running = false, status = LocatorSearchData.Status.Unknown, wholeBand = wholeBand,
            )
            return
        }
        _locatorSearch.value = LocatorSearchData.Run(
            running = true,
            total = if (wholeBand) Protocol.SURVEY_CHANNEL_COUNT else channels.size,
            wholeBand = wholeBand,
            targetLocatorId = targetLocatorId,
        )
        armSearchTimeout()
    }

    /** Ask the receiver to stop. It answers with a Cancelled terminator, so the UI
     *  settles through the same path as a normal ending rather than a local guess. */
    fun cancelLocatorSearch(service: BluetoothService?) {
        if (_locatorSearch.value?.running != true) return
        if (service?.cancelLocatorSearch() != true) {
            // The request did not even go out, so no terminator is coming.
            searchTimeoutJob?.cancel()
            _locatorSearch.update {
                it?.copy(running = false, status = LocatorSearchData.Status.Cancelled)
            }
        }
    }

    /**
     * Point the receiver at [channel] now.
     *
     * The single apply path for a receiver-only channel change: the search's "Point
     * receiver", the survey's pick when no locator is connected, and the manual
     * field's Update all land here. They were three call sites doing the same four
     * steps, which is how one of them came to *stage* the change and leave the user
     * hunting for an Update button in another section to finish a decision they had
     * already made by choosing a channel from a list.
     *
     * Recognition is armed first, so the next PreLaunchData on the new channel is
     * recognized, challenged for a password, or reverted (ADR-0011). That is what
     * makes applying immediately safe rather than reckless: pointing at a locator
     * the app does not know still has to get past the password prompt, and a channel
     * with nothing on it reverts.
     *
     * A no-op when the receiver is already there, so a button press cannot start a
     * confirm cycle that has nothing to confirm.
     */
    fun pointReceiverAtChannel(service: BluetoothService?, channel: Int): Boolean {
        val remote = _remoteReceiverConfig.value
        if (channel == remote.channel) return false
        if (_receiverConfigMessageState.value != LocatorMessageState.Idle) return false
        beginChannelChangeRecognition(remote.channel)
        updateReceiverConfigMessageState(LocatorMessageState.SendRequested)
        val target = remote.copy(channel = channel)
        updateReceiverConfigMessageState(
            if (service?.changeReceiverConfig(target) == true) LocatorMessageState.Sent
            else LocatorMessageState.SendFailure
        )
        updateReceiverConfigState(target)
        return true
    }

    /**
     * Drop what the previous visit left on the Communication screen — **except** a
     * scan that is still running.
     *
     * Both halves were learned the hard way. Keeping the results meant re-entering
     * the screen showed a run from minutes ago as current, and offered the
     * whole-band sweep on the strength of it; and a stale refusal ("the locator is
     * armed") sat there after the locator had been disarmed, describing a state that
     * had since gone away.
     *
     * Clearing unconditionally was worse. [onLocatorSearchResult] drops every message
     * that arrives while the run is null, so wiping a run in flight orphaned it: the
     * receiver went on sweeping — deaf, for up to ~90 s — while the app ignored the
     * stream and the terminator alike, and the search simply appeared to die on
     * leaving the screen. Anything still running is therefore left exactly as it is.
     */
    fun clearScansForNewVisit() {
        if (_locatorSearch.value?.running != true) clearLocatorSearch()
        if (!_surveyInProgress.value) clearChannelSurvey()
    }

    fun clearLocatorSearch() {
        searchTimeoutJob?.cancel()
        _locatorSearch.value = null
    }

    /**
     * Restart the timeout on every streamed message rather than running one for the
     * whole sweep. A whole-band run is up to ~90 s — far longer than any fixed
     * timeout that would still catch a receiver going quiet — but it reports every
     * 1.4 s, so silence is the thing actually worth watching.
     */
    private fun armSearchTimeout() {
        searchTimeoutJob?.cancel()
        searchTimeoutJob = viewModelScope.launch {
            delay(SEARCH_SILENCE_TIMEOUT_MS)
            _locatorSearch.update { run ->
                if (run?.running != true) run
                else run.copy(running = false, status = LocatorSearchData.Status.Unknown)
            }
        }
    }

    private fun onLocatorSearchResult(msg: LocatorSearchParsed) {
        val run = _locatorSearch.value ?: return
        if (msg.status == LocatorSearchData.Status.Progress) {
            armSearchTimeout()
            _locatorSearch.value = run.copy(
                searched = msg.searched,
                // Trust the receiver's denominator over the app's: the firmware
                // dedupes and range-checks the list, so it may search fewer channels
                // than were asked for.
                total = if (msg.total > 0) msg.total else run.total,
                hits = if (!msg.found) run.hits else run.hits + LocatorSearchData.Hit(
                    channel = msg.channel,
                    locatorId = msg.locatorId,
                    deviceName = msg.deviceName,
                    rssi = msg.rssi,
                    snr = msg.snr,
                    armed = msg.armed,
                ),
            )
            return
        }
        // Terminator: the run is over however it ended.
        searchTimeoutJob?.cancel()
        _locatorSearch.value = run.copy(running = false, status = msg.status)
    }

    fun clearChannelSurvey() {
        _channelSurvey.value = null
    }

    /**
     * Move the whole system to [channel] after a survey.
     *
     * This sends a **locator** channel change, not a receiver one. "Find a clean
     * channel" means "move my rocket and my receiver there", and per
     * [ADR-0011](docs/adr/0011) invariant 1 that is exactly one message: the locator
     * moves and the receiver follows after forwarding it. Staging it as a
     * *receiver-only* change instead — which is what the Receiver Settings channel
     * field does — points the receiver at an empty channel and kills the link,
     * leaving the locator behind on the old one.
     *
     * Reuses [updateLocatorConfigState] so the confirm-by-inference and
     * revert-and-retry recovery of ADR-0011 invariants 3 and 4 apply here too,
     * rather than a second, untested path to the same place.
     */
    // Channel a survey pick is currently moving the system to, so the UI can name it
    // while the ADR-0011 confirm/recover cycle runs.  That cycle can take several
    // seconds (it waits for PreLaunchData to resume on the new channel, and may
    // revert and retry once), which is far too long to leave with no feedback.
    private val _pendingChannelMove = MutableStateFlow<Int?>(null)
    val pendingChannelMove: StateFlow<Int?> = _pendingChannelMove.asStateFlow()

    // The channel the BANNER is describing, which is deliberately not
    // [_pendingChannelMove].  That one is the ADR-0029 search-candidate memory and is
    // cleared when a move is confirmed; this one drives the message and is cleared
    // only by Dismiss or by the next move.  Conflating them meant a SUCCESSFUL move
    // cleared the candidate and took its own "Now on channel N" off screen in the
    // same instant, before it could be read.
    private val _channelMoveBannerChannel = MutableStateFlow<Int?>(null)
    val channelMoveBannerChannel: StateFlow<Int?> = _channelMoveBannerChannel.asStateFlow()

    // The move's terminal state, held after [_locatorConfigMessageState] returns to
    // Idle two seconds later.  Without it the outcome of a cycle that can run ~23 s
    // was legible for two — reported from the bench as "a very quick message at the
    // top indicating an issue, but it went away before I could read it".
    private val _channelMoveResult = MutableStateFlow<LocatorMessageState?>(null)
    val channelMoveResult: StateFlow<LocatorMessageState?> = _channelMoveResult.asStateFlow()

    fun dismissChannelMoveBanner() {
        _channelMoveBannerChannel.value = null
        _channelMoveResult.value = null
    }

    // How an unconfirmed move ended, so the failure message can say something true.
    // Both failure paths land on NotAcknowledged, but they leave the hardware in
    // opposite states: after a LocatorStayed verdict the receiver has been put back
    // and both devices are on the old channel, while after NoEvidence the receiver is
    // on the NEW channel and where the locator is, is exactly what nobody knows.  One
    // sentence cannot describe both, and the one that shipped described only the first.
    private val _channelMoveOutcome = MutableStateFlow<ChannelMove.Verdict?>(null)
    val channelMoveOutcome: StateFlow<ChannelMove.Verdict?> = _channelMoveOutcome.asStateFlow()

    fun moveLocatorToChannel(service: BluetoothService?, channel: Int) {
        _pendingChannelMove.value = channel
        _channelMoveBannerChannel.value = channel
        _channelMoveResult.value = null
        _channelMoveOutcome.value = null
        channelMoveReceiptMs = 0L
        channelMoveLocatorId = _connectedLocatorId.value
        val target = _remoteLocatorConfig.value.copy(loraChannel = channel)
        _locatorConfigMessageState.value = LocatorMessageState.SendRequested
        _locatorConfigMessageState.value =
            if (service?.changeLocatorConfig(target) == true) LocatorMessageState.Sent
            else LocatorMessageState.SendFailure
        updateLocatorConfigState(target, service)
    }

    private val _receiverConfigChanged = MutableStateFlow<Boolean>(false)
    val receiverConfigChanged: StateFlow<Boolean> = _receiverConfigChanged.asStateFlow()

    fun updateReceiverConfigChanged(newReceiverConfigChanged: Boolean) {
        _receiverConfigChanged.value = newReceiverConfigChanged
    }

    /**
     * Evaluate an incoming authenticated broadcast [frame] whose base struct is
     * [baseSize] bytes — PreLaunchData while the locator is disarmed, TelemetryData
     * while it is armed.  Called for every one of them: claims the connection for a
     * known/open locator when the slot is free, raises the channel-change password
     * challenge when a deliberate channel change lands on an unknown locator, and
     * flags conflicting traffic otherwise.
     *
     * Being authorized does **not** entitle a locator to the connection.  A second
     * authorized locator — the common case for anyone who owns two — is reported as
     * conflicting traffic while the connected one is still live, and takes over only
     * on an explicit [requestConnectToConflict] or after [CONNECTION_HOLD_MS] of
     * silence.  This is reachable at close range *even when the two are on different
     * LoRa channels*: 200 kHz spacing against a 125 kHz bandwidth gives the receiver
     * no hope of rejecting a 22 dBm locator a few feet away, and the receiver stamps
     * its own channel on everything it relays, so nothing downstream can tell the
     * frame arrived off-channel.
     *
     * [challengeable] is false for TelemetryData.  An armed locator can be
     * authenticated — that is the point of carrying the tag there — but it cannot be
     * *connected to* from cold: the password dialog needs a device name to show,
     * and config/arming is a disarmed-state activity anyway.  An unknown armed
     * locator therefore raises the conflict warning and waits for it to disarm.
     */
    private fun evaluateRecognition(
        frame: ByteArray,
        locatorId: Long,
        deviceName: String,
        baseSize: Int = Protocol.PRELAUNCH_BASE_STRUCT_SIZE,
        challengeable: Boolean = true,
        // The channel THIS frame arrived on, straight out of the receiver's own
        // trailer. Passed in rather than read from _remoteReceiverConfig because
        // that flow is updated from the same frame further down, so reading it here
        // yields the previous broadcast's channel. Usually identical and harmless —
        // except immediately after a channel change, which is exactly when the
        // locator may broadcast once and go quiet, leaving the search pointed at
        // the channel it just left. Null where the message carries no channel
        // (TelemetryData has no room for one), which falls back to the flow.
        receiverChannel: Int? = null,
    ) {
        if (challengeable) {
            lastPrelaunchFrame = frame
            lastPrelaunchLocatorId = locatorId
            lastPrelaunchDeviceName = deviceName
        }
        // Any broadcast from a locator that is not the connected one means the
        // channel is shared, whatever we go on to decide about authorization or
        // banners. Recorded first so no later branch can lose it.
        val connectedNow = _connectedLocatorId.value
        if (connectedNow != null && connectedNow != locatorId)
            lastForeignBroadcastMs = System.currentTimeMillis()

        val knownKey = _knownLocators.value[locatorId]?.passwordKey?.toLong()?.and(0xFFFFFFFFL)
        val authorized =
            (knownKey != null && LocatorAuth.verifyFrame(frame, knownKey, baseSize)) ||
                    LocatorAuth.verifyFrame(frame, 0L, baseSize)   // open locator (no password)

        // Keep the challenge frame fresh while a dialog for this locator is open.
        if (challengeable && _challenge.value?.locatorId == locatorId) challengeFrame = frame

        if (authorized) {
            // Remember what this locator calls itself, so the status panel can name
            // it later when it is armed and sending no name at all.  Stored for
            // every authorized locator, not just password-protected ones: an open
            // locator is authorized without ever being challenged, so rememberLocator
            // never runs for it — and open is the default state, which made a blank
            // locator row the common case rather than the edge one.
            noteLocatorName(locatorId, deviceName)
            // And where it was heard. This is the memory the locator search runs
            // on: with several rockets and one receiver, "which channel was that
            // one on again" is the question, and the app has already answered it
            // every time it heard from each of them.
            noteLocatorChannel(locatorId, receiverChannel)

            // ...but it does NOT get the connection back just because we opened the
            // slot on the way somewhere else. A receiver-only move releases the
            // connection before the change goes out, and the locator we are leaving
            // goes on broadcasting into that empty slot at 1 Hz until the receiver
            // actually retunes. Admitting one of those frames re-adopts the old
            // locator AND clears awaitingChannelRecognition, so the challenge armed
            // for the new channel never fires — the reported "no auth popup from a
            // search result, but the conflict banner's Connect works". See
            // LocatorConnection.isFromChannelBeingLeft for the full account.
            //
            // The name and channel above are still worth keeping: they are true facts
            // about a locator we are authorized for, and the search runs on them.
            if (LocatorConnection.isFromChannelBeingLeft(
                    frameChannel = receiverChannel,
                    previousChannel = channelChangePreviousChannel,
                    awaitingRecognition = awaitingChannelRecognition,
                    moveInFlight = _receiverConfigMessageState.value != LocatorMessageState.Idle,
                )
            ) return

            val mayConnect = LocatorConnection.mayConnect(
                connected = _connectedLocatorId.value,
                sender = locatorId,
                ageMs = System.currentTimeMillis() - lastConnectedFrameMs,
                holdMs = CONNECTION_HOLD_MS,
            )
            if (!mayConnect) {
                // A different authorized locator, heard while ours is still live.
                // Warn, but leave the connection where it is — switching is the
                // user's call (the banner's Connect action), not this packet's.
                if (locatorId !in dismissedConflictIds) { _conflictLocatorId.value = locatorId; lastConflictFrameMs = System.currentTimeMillis() }
                return
            }
            _connectedLocatorId.value = locatorId
            lastConnectedFrameMs = System.currentTimeMillis()
            // Do NOT clear the conflict just because a good packet arrived.  With two
            // locators on one channel the broadcasts interleave, so an unconditional
            // clear here made the banner flash on and off at the broadcast rate —
            // visible, but gone again before Connect could be pressed.  The conflict
            // belongs to the *other* locator and expires on its own silence.
            if (_conflictLocatorId.value == locatorId ||
                System.currentTimeMillis() - lastConflictFrameMs >= CONFLICT_HOLD_MS) {
                _conflictLocatorId.value = null
            }
            awaitingChannelRecognition = false
            if (_challenge.value?.locatorId == locatorId) _challenge.value = null
            // An armed locator authenticates but carries no config, so the status
            // panel would have nothing to name it with.  Fall back to the label
            // stored when this locator was authorized; the first PreLaunchData
            // overwrites it with the live value as soon as it disarms.
            if (_remoteLocatorConfig.value.deviceName.isEmpty()) {
                _knownLocators.value[locatorId]?.label
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { label -> _remoteLocatorConfig.update { it.copy(deviceName = label) } }
            }
            return
        }
        // Unauthorized.
        //
        // **The locator we believe we are connected to has stopped authenticating** —
        // its password was changed on the device. Reported from the phone and
        // reproduced on iOS 2026-08-29: nothing further was admitted, no prompt could
        // be raised (the passive branch below refuses while anything is connected),
        // and the conflict banner called this very locator "another locator" over a
        // panel reading "No Locator". There was no way out short of dropping the BLE
        // link.
        //
        // The connection is RELEASED first, because it is a stale belief rather than a
        // live connection to protect: the evidence it rested on is this locator's own
        // authenticated broadcasts, and those have stopped verifying. Releasing it is
        // also what lets the prompt through, and what stops the banner describing the
        // holder as somebody else. Asked even if this locator was declined before — a
        // decline was about a locator we had no business displaying, not about the one
        // already on screen.
        //
        // Checked BEFORE the !challengeable return, matching iOS: an armed locator
        // sends TelemetryData, which carries no device name, so there is nothing to
        // title a prompt with — but the stale connection is just as wrong and the
        // banner just as misleading. That case releases and stays silent.
        //
        // Identity here is the frame's CLEARTEXT locator_id, which ADR-0006 Decision 5
        // scopes to accidental cross-connection rather than to an attacker; a locator
        // claiming to be ours can therefore drop the connection it claims. That is the
        // declared threat model, noted because this is the first place identity gates a
        // state change rather than only a label.
        if (locatorId == _connectedLocatorId.value) {
            _connectedLocatorId.value = null
            lastConnectedFrameMs = 0L
            if (challengeable && _challenge.value == null) {
                challengeFrame = frame
                _challengeError.value = false
                _challenge.value = LocatorChallenge(locatorId, deviceName, null)
            }
            return
        }
        // Never disturbs a standing connection otherwise — an armed stranger on the
        // channel must not knock out the locator we are connected to.
        if (!challengeable) {
            if (locatorId !in dismissedConflictIds) { _conflictLocatorId.value = locatorId; lastConflictFrameMs = System.currentTimeMillis() }
            return
        }
        if (awaitingChannelRecognition) {
            // Deliberate channel change landed on an unknown locator → challenge (cancel reverts).
            awaitingChannelRecognition = false
            challengeFrame = frame
            _challengeError.value = false
            _challenge.value = LocatorChallenge(locatorId, deviceName, channelChangePreviousChannel)
            return
        }
        // Passive: unrecognized traffic on the current channel — warn, and (if we are
        // not already connected) prompt to connect on first contact with this locator.
        if (locatorId !in dismissedConflictIds) { _conflictLocatorId.value = locatorId; lastConflictFrameMs = System.currentTimeMillis() }
        if (knownLocatorsLoaded && _connectedLocatorId.value == null && _challenge.value == null &&
            locatorId !in declinedLocatorIds) {
            challengeFrame = frame
            _challengeError.value = false
            _challenge.value = LocatorChallenge(locatorId, deviceName, null)
        }
    }

    /**
     * Drop what describes a locator we can no longer hear, when the **BLE link** to
     * the receiver goes away.
     *
     * The same rule [beginChannelChangeRecognition] applies to a deliberate release
     * (ADR-0011): the configuration goes with the connection. A dropped link is the
     * other way to lose one, and it was the case nobody had walked through — a
     * disconnect set `versionInfoStale` and left every locator readout standing, so
     * the Communication screen went on showing the channel of a locator the app had
     * no path to at all, on the screen whose whole job is "which channel am I
     * talking to". It corrected only when a `PreLaunchData` from that locator was
     * admitted again, which never happens if the user reconnects to a different
     * receiver — or does not reconnect.
     *
     * **The connection goes too, not just the configuration.** Blanking the config
     * alone would leave the Locator channel section on screen reading 0, and channel
     * 0 is the factory default (ADR-0025) — a plausible-looking value where the truth
     * is "nothing is connected", which is the failure ADR-0029 already recorded once
     * for this very field. Releasing it hides the section instead, which is what iOS
     * does (`clearLiveReadouts`, which clears `connectedLocatorId` with the rest).
     *
     * Deliberately narrow. Telemetry staleness is already handled by the 5 s liveness
     * rule, the scans settle through their own timeouts, and `_remoteReceiverConfig`
     * is **left alone**: unlike iOS's `receiverInfo` it is seeded from and saved to
     * user preferences, so clearing it would blank the Receiver channel field on
     * every drop and re-raise the "reads 0, looks plausible" hazard on the other
     * field. Firmware versions are kept for the same reason iOS keeps them — they
     * are a property of the hardware, not of this link.
     */
    private fun releaseLocatorOnLinkLoss() {
        _connectedLocatorId.value = null
        lastConnectedFrameMs = 0L
        _remoteLocatorConfig.value = LocatorConfig()
        // The banner names a locator sharing a channel we are no longer listening on.
        _conflictLocatorId.value = null
        lastConflictFrameMs = 0L
    }

    /** Arm the channel-change flow: the next PreLaunchData on the new channel decides
     *  recognition, or raises a password challenge (cancel reverts to [previousChannel]).
     *  Releases the connection outright — the point of the change is to go somewhere
     *  else, so the first authorized locator heard on the new channel should claim it
     *  without waiting out [CONNECTION_HOLD_MS]. */
    fun beginChannelChangeRecognition(previousChannel: Int) {
        channelChangePreviousChannel = previousChannel
        awaitingChannelRecognition = true
        _connectedLocatorId.value = null
        lastConnectedFrameMs = 0L
        // The configuration goes with the connection. Left behind, the Locator
        // channel field goes on describing the locator we just let go of — reported
        // 2026-08-29 with the receiver reading 48 and the field reading 34, two real
        // locators on two real channels — and it corrects only when a PreLaunchData
        // from the NEW locator is admitted, so a locator that is never admitted
        // leaves it wrong indefinitely.
        //
        // iOS clears this on a BLE link drop too, in clearLiveReadouts. This app has
        // no equivalent — a disconnect leaves the whole readout standing — so the gap
        // is closed here only for the deliberate release. See ADR-0011.
        _remoteLocatorConfig.value = LocatorConfig()
        quietestNoiseFloor = LinkQuality.NOISE_FLOOR_UNKNOWN
        quietestPolledFloor = LinkQuality.NOISE_FLOOR_UNKNOWN
        lastLossMs = 0L
        lastForeignBroadcastMs = 0L
        // Measurements of the OLD channel say nothing about the new one, so they
        // must not be allowed to age out gracefully — they are wrong immediately.
        lastPacketMeasurementMs = 0L
        lastFloorMeasurementMs = 0L
        floorFromPoll = false
        lastConflictFrameMs = 0L
        _conflictLocatorId.value = null
        _challenge.value = null
        _challengeError.value = false
    }

    /** Submit a password for the active challenge. Correct → remember + recognize +
     *  close. Wrong → keep the dialog open with an error so the user can retry. */
    suspend fun submitPassword(password: String): Boolean {
        val challenge = _challenge.value ?: return false
        val frame = challengeFrame ?: return false
        val key = LocatorAuth.deriveKey(password)
        val ok = LocatorAuth.verifyFrame(frame, key)
        if (ok) {
            rememberLocator(challenge.locatorId, key, challenge.deviceName)
            _connectedLocatorId.value = challenge.locatorId
            lastConnectedFrameMs = System.currentTimeMillis()
            _conflictLocatorId.value = null
            declinedLocatorIds.remove(challenge.locatorId)
            _challenge.value = null
            _challengeError.value = false
        } else {
            _challengeError.value = true
        }
        return ok
    }

    /** Dismiss the active challenge. A channel-change challenge reverts the receiver to
     *  the previous channel (and resets the Receiver Settings state so the UI reflects
     *  it); a passive challenge is remembered as declined so it does not re-prompt. */
    fun cancelChallenge(service: BluetoothService?) {
        val challenge = _challenge.value ?: return
        val prev = challenge.previousChannel
        if (prev != null) {
            service?.changeReceiverConfig(_remoteReceiverConfig.value.copy(channel = prev))
            _receiverConfigChanged.value = false
            updateReceiverConfigMessageState(LocatorMessageState.Idle)
        } else {
            declinedLocatorIds.add(challenge.locatorId)
        }
        _challenge.value = null
        _challengeError.value = false
    }

    fun clearChallengeError() { _challengeError.value = false }

    /** Connect to the locator named in the conflict banner.
     *
     *  Two cases.  If we are **already authorized** for it (we hold its password, or
     *  it is open) this is a deliberate *switch*, and the only way to make one while
     *  the current locator is still transmitting — the arriving-packet path will not
     *  move the connection on its own.  Otherwise it re-raises the password prompt,
     *  letting the user connect after having dismissed the automatic one. */
    fun requestConnectToConflict() {
        val id = _conflictLocatorId.value ?: return
        if (_connectedLocatorId.value == id) return
        // Only act when we hold a PreLaunchData frame from THIS locator to verify
        // against.  Otherwise challengeFrame would still hold some other locator's
        // frame, and the typed password would be checked against the wrong tag —
        // meaningless at best, a false accept at worst.  Reachable since armed
        // locators raise conflicts too: an armed stranger is only connectable once
        // it disarms and broadcasts its identity and device name, which is also the
        // only state in which connecting is useful.
        val frame = lastPrelaunchFrame ?: return
        if (lastPrelaunchLocatorId != id) return
        declinedLocatorIds.remove(id)
        val knownKey = _knownLocators.value[id]?.passwordKey?.toLong()?.and(0xFFFFFFFFL)
        val authorized =
            (knownKey != null && LocatorAuth.verifyFrame(frame, knownKey)) ||
                    LocatorAuth.verifyFrame(frame, 0L)
        if (authorized) {
            // Switch now.  The displayed config/telemetry still belongs to the old
            // locator until this one's next broadcast lands, which is at most one
            // 1 Hz period away.
            _connectedLocatorId.value = id
            lastConnectedFrameMs = System.currentTimeMillis()
            _conflictLocatorId.value = null
            return
        }
        challengeFrame = frame
        _challengeError.value = false
        _challenge.value = LocatorChallenge(id, lastPrelaunchDeviceName, null)
    }

    /** No PreLaunchData arrived after a channel change — stop waiting (no locator found). */
    fun channelChangeRecognitionTimedOut() {
        awaitingChannelRecognition = false
    }

    /** Dismiss the conflicting-traffic banner and keep it dismissed.
     *
     *  The conflicting locator keeps broadcasting at 1 Hz, so simply clearing the id
     *  put the banner straight back on the next packet — dismiss did nothing. The id
     *  is remembered instead, and only [resetConflictDismissals] (on re-entering
     *  Receiver Settings) brings it back. */
    fun dismissConflict() {
        _conflictLocatorId.value?.let { dismissedConflictIds.add(it) }
        _conflictLocatorId.value = null
    }

    /** Called when Receiver Settings is entered, so a previously dismissed conflict
     *  is shown again — re-entering the screen is the user asking to see it. */
    fun resetConflictDismissals() {
        dismissedConflictIds.clear()
    }

    // Names already persisted this session.  PreLaunchData arrives at 1 Hz and the
    // stored map is refreshed through a DataStore round-trip, so without this the
    // "only when it changes" test below would still be reading the pre-write value
    // for the first few broadcasts and re-persist the same name several times.
    private val notedLocatorNames = mutableMapOf<Long, String>()

    /**
     * Record [deviceName] as the name of [locatorId], for display when the locator
     * is armed and no longer sending one.
     *
     * An empty name never overwrites a stored one, which is what the armed case
     * depends on: the TelemetryData branch of [evaluateRecognition] passes an empty
     * deviceName because that message has no name field.  The stored name is only
     * ever *read* when the live one is empty, so the first PreLaunchData after a
     * disarm still wins.
     */
    private fun noteLocatorName(locatorId: Long, deviceName: String) {
        // Before the stored map has loaded, "not in the map" is indistinguishable
        // from "nothing stored", so a write here would be churn at best.
        if (!knownLocatorsLoaded) return
        val known = notedLocatorNames[locatorId] ?: _knownLocators.value[locatorId]?.label
        if (!LocatorNames.isNewName(known, deviceName)) return
        notedLocatorNames[locatorId] = deviceName
        viewModelScope.launch { rememberLocatorName(locatorId, deviceName) }
    }

    /** Channels already written this session, so a 1 Hz broadcast does not mean a
     *  DataStore write every second. */
    private val notedLocatorChannels = mutableMapOf<Long, Int>()

    /**
     * Record the receiver's current channel as where [locatorId] was last heard.
     *
     * The receiver's channel, not the locator's configured one: they are the same
     * thing whenever a broadcast actually arrives, and this one is a fact about the
     * frame in hand rather than a setting that might not have taken effect.
     *
     * Written only for authorized locators, from the same branch as the name. An
     * unauthorized broadcast is somebody else's rocket, and seeding your search
     * with their channel would spend a dwell looking in a place you have no reason
     * to look.
     */
    private fun noteLocatorChannel(locatorId: Long, receiverChannel: Int? = null) {
        if (!knownLocatorsLoaded) return
        val channel = receiverChannel ?: _remoteReceiverConfig.value.channel
        if (notedLocatorChannels[locatorId] == channel) return
        notedLocatorChannels[locatorId] = channel
        viewModelScope.launch { updateKnownLocator(locatorId) { it.setLastChannel(channel) } }
    }

    /** Persist [label] for [locatorId], keeping any password key already held.
     *  An id may carry a name with no key — the two are independent. */
    private suspend fun rememberLocatorName(locatorId: Long, label: String) =
        updateKnownLocator(locatorId) { it.setLabel(label) }

    private suspend fun rememberLocator(locatorId: Long, passwordKey: Long, label: String) =
        updateKnownLocator(locatorId) { it.setPasswordKey(passwordKey.toInt()).setLabel(label) }

    /**
     * Merge [change] into the stored entry for [locatorId], creating it if absent.
     *
     * Every writer goes through here so none of them can drop a field it does not
     * care about. Each used to rebuild the message from scratch and hand-copy the
     * one other field that existed, which worked only while there were two: adding
     * a third (last_channel) would have made every name update silently erase the
     * locator's remembered channel, and the failure would have shown up as a
     * search that had forgotten where to look.
     */
    private suspend fun updateKnownLocator(
        locatorId: Long,
        change: (KnownLocator.Builder) -> KnownLocator.Builder,
    ) {
        currentContext.userPreferencesDataStore.updateData { prefs ->
            val existing = prefs.knownLocatorsMap[locatorId.toInt()]
            val builder = existing?.toBuilder()
                ?: KnownLocator.newBuilder().setId(locatorId.toInt())
            prefs.toBuilder()
                .putKnownLocators(locatorId.toInt(), change(builder).build())
                .build()
        }
    }

    private val _locatorConfigChanged = MutableStateFlow<Boolean>(false)
    val locatorConfigChanged: StateFlow<Boolean> = _locatorConfigChanged.asStateFlow()

    fun updateLocatorConfigChanged(newLocatorConfigChanged: Boolean) {
        _locatorConfigChanged.value = newLocatorConfigChanged
    }

    private val _locatorConfigMessageState = MutableStateFlow<LocatorMessageState>(LocatorMessageState.Idle)
    val locatorConfigMessageState: StateFlow<LocatorMessageState> = _locatorConfigMessageState.asStateFlow()

    fun updateLocatorConfigMessageState(newLocatorConfigMessageState: LocatorMessageState) {
        _locatorConfigMessageState.value = newLocatorConfigMessageState
    }

    private val _receiverConfigMessageState = MutableStateFlow<LocatorMessageState>(LocatorMessageState.Idle)
    val receiverConfigMessageState: StateFlow<LocatorMessageState> = _receiverConfigMessageState.asStateFlow()

    fun updateReceiverConfigMessageState(newReceiverConfigMessageState: LocatorMessageState) {
        _receiverConfigMessageState.value = newReceiverConfigMessageState
    }

    private val _requestFlightProfileMetadata = MutableStateFlow(true)
    val requestFlightProfileMetadata: StateFlow<Boolean> = _requestFlightProfileMetadata.asStateFlow()

    fun updateRequestFlightProfileMetadata(newRequestFlightProfileMetadata: Boolean) {
        _requestFlightProfileMetadata.value = newRequestFlightProfileMetadata
    }

    private val _flightProfileMetadataMessageState = MutableStateFlow<LocatorMessageState>(LocatorMessageState.Idle)
    val flightProfileMetadataMessageState: StateFlow<LocatorMessageState> = _flightProfileMetadataMessageState.asStateFlow()

    fun updateFlightProfileMetadataMessageState(newFlightProfileMetadataMessageState: LocatorMessageState) {
        _flightProfileMetadataMessageState.value = newFlightProfileMetadataMessageState
    }

    private val _flightProfileMetadata = MutableStateFlow<List<FlightProfileMetadata>>(emptyList())
    val flightProfileMetadata: StateFlow<List<FlightProfileMetadata>> = _flightProfileMetadata.asStateFlow()

    fun clearFlightProfileMetadata() {
        _flightProfileMetadata.value = emptyList()
        _flightProfileMetadataAttempt.value = 0
    }

    // How many times the current fetch has asked the locator for the record list.
    // Surfaced so a slow fetch reads as "still trying", not as a frozen screen.
    private val _flightProfileMetadataAttempt = MutableStateFlow(0)
    val flightProfileMetadataAttempt: StateFlow<Int> = _flightProfileMetadataAttempt.asStateFlow()

    private val _flightProfileArchivePosition = MutableStateFlow<Int>(0)
    val flightProfileArchivePosition: StateFlow<Int> = _flightProfileArchivePosition.asStateFlow()

    fun updateFlightProfileArchivePosition(newFlightProfileArchivePosition: Int) {
        _flightProfileArchivePosition.value = newFlightProfileArchivePosition
    }

    private val _flightProfileDataMessageState = MutableStateFlow<LocatorMessageState>(LocatorMessageState.Idle)
    val flightProfileDataMessageState: StateFlow<LocatorMessageState> = _flightProfileDataMessageState.asStateFlow()

    fun updateFlightProfileDataMessageState(newFlightProfileDataMessageState: LocatorMessageState) {
        _flightProfileDataMessageState.value = newFlightProfileDataMessageState
    }

    private val _flightProfileDataDisplayState = MutableStateFlow<Boolean>(false)
    val flightProfileDataDisplayState: StateFlow<Boolean> = _flightProfileDataDisplayState.asStateFlow()

    fun updateFlightProfileDataDisplayState(newFlightProfileDataDisplayState: Boolean) {
        _flightProfileDataDisplayState.value = newFlightProfileDataDisplayState
    }

    // Per-record event summary for the flight profile currently being viewed.
    // Arrives as its own MsgType.FlightEvents frame just ahead of the sample
    // burst; cleared whenever a new record is requested so the chart never draws
    // one record's markers over another's data.
    private val _flightEvents = MutableStateFlow(FlightEventsData())
    val flightEvents: StateFlow<FlightEventsData> = _flightEvents.asStateFlow()

    private val _flightProfileAglData = MutableStateFlow<List<UShort>>(emptyList())
    val flightProfileAglData: StateFlow<List<UShort>> = _flightProfileAglData.asStateFlow()

    private val _flightProfileAccelerometerData = MutableStateFlow<List<Vec3f>>(emptyList())
    val flightProfileAccelerometerData: StateFlow<List<Vec3f>> = _flightProfileAccelerometerData.asStateFlow()

    /**
     * The downloaded archive record as a map path — the fused, GPS-disciplined
     * track described on [archivedPathPoints].  Empty until a record is
     * transferred, which is what gates the map's source control.
     *
     * This is a *snapshot*, deliberately not derived from
     * `FlightDataRepository.samples`.  That flow is the transfer assembly buffer:
     * `beginTransfer()` empties it, and `clearFlightProfileData()` reaches it via
     * `cancelTransfer()` when you navigate back from the chart.  Deriving from it
     * meant the path was always empty by the time the map was on screen — the
     * chart only survives the same trip because it is copied out too.
     *
     * So this outlives the chart on purpose.  The point of the feature is to load
     * a record and then go look at it on the map, which cannot work if leaving
     * the profile screen discards it.  It is replaced when the next record
     * arrives, and cleared with [resetFlightPath].
     */
    private val _archivedFlightPath = MutableStateFlow<List<PathPoint>>(emptyList())
    val archivedFlightPath: StateFlow<List<PathPoint>> = _archivedFlightPath.asStateFlow()

    /** Which track the map draws.  Live is raw GPS; archived is the EKF solution. */
    private val _showArchivedPath = MutableStateFlow(false)
    val showArchivedPath: StateFlow<Boolean> = _showArchivedPath.asStateFlow()

    fun toggleArchivedPath() { _showArchivedPath.value = !_showArchivedPath.value }

    /**
     * Snapshots [samples] as the archived map path.
     *
     * Logs when a record arrives but yields no drawable point, which is the one
     * failure this feature can suffer silently: the control simply never appears,
     * and "the record has no position" is indistinguishable from "the plumbing is
     * broken" without it.
     */
    private fun publishArchivedPath(samples: List<FlightSample>) {
        val points = archivedPathPoints(samples)
        if (points.isEmpty() && samples.isNotEmpty()) {
            Log.w("ArchivedPath",
                "${samples.size} archived samples carried no usable position " +
                "(all zero or non-finite lat/lon) — no archived path to draw")
        }
        _archivedFlightPath.value = points
    }

    fun clearFlightProfileData() {
        _flightProfileAglData.value = emptyList()
        _flightProfileAccelerometerData.value = emptyList()
        _flightEvents.value = FlightEventsData()
        // The archived path is NOT cleared here — see _archivedFlightPath. This
        // runs when you navigate back from the chart, which is exactly the moment
        // you would be heading to the map to look at the track.
        FlightDataRepository.cancelTransfer()
    }

    private val _flightPath = MutableStateFlow<List<PathPoint>>(emptyList())
    val flightPath: StateFlow<List<PathPoint>> = _flightPath.asStateFlow()
    private var _previousFlightState = FlightStates.WaitingLaunch
    // False until a telemetry packet has actually been seen, so the WaitingLaunch
    // that _previousFlightState is *initialized* to is never mistaken for an
    // observed ground state.  Without it, an app restarted mid-flight reads its
    // first packet as a launch and erases the path of the flight it just rejoined.
    private var flightStateObserved = false
    // Set once the flight is over as far as the app can tell — see
    // landingConcluded. The path is frozen from then on, except for the locator's
    // own first Landed fix.
    private var landingConcludedThisFlight = false
    // Set once the locator has actually reported Landed, which ends the recording
    // for this flight outright.
    //
    // Both are cleared by the next launch and by resetFlightPath, and by nothing
    // else: neither a landing nor its confirmation is walked back mid-flight.
    private var landedStatusReceived = false

    private val _isFlightPathRecording = MutableStateFlow(true)
    val isFlightPathRecording: StateFlow<Boolean> = _isFlightPathRecording.asStateFlow()

    private val flightPathFile: File
        get() = File(getApplication<Application>().filesDir, "flight_path.csv")

    init {
        loadFlightPath()
        startFlightLogWatchers()
        refreshFlightLogs()
    }

    /**
     * Restores the last recorded path.  Rows are `lat,lng,agl,timestampMs`.
     *
     * Three-column rows predate capture times.  They are kept, not dropped: the
     * ground track and altitude profile in them are real, and that is the part
     * worth having — it is where the rocket went.  Only the timestamp is
     * invented, and it is flagged [PathPoint.timeSynthetic] so the map suppresses
     * one-second markers over those points rather than standing posts at times
     * the rocket was never at.
     *
     * The missing fourth column *is* the flag, on disk as well as in memory, so
     * this round-trips through [saveFlightPath] with no format version to carry.
     */
    private fun loadFlightPath() {
        val file = flightPathFile
        if (!file.exists()) return
        try {
            val points = file.readLines().mapIndexedNotNull { index, line ->
                val parts = line.split(",")
                when (parts.size) {
                    4 -> PathPoint(
                        parts[0].toDouble(),
                        parts[1].toDouble(),
                        parts[2].toFloat(),
                        parts[3].toLong(),
                    )
                    // Placeholder time: kept monotonic so it can't look like a
                    // clock that ran backwards, but nothing reads it — the
                    // markers step over synthetic points entirely.
                    3 -> PathPoint(
                        parts[0].toDouble(),
                        parts[1].toDouble(),
                        parts[2].toFloat(),
                        index * LEGACY_PLACEHOLDER_INTERVAL_MS,
                        timeSynthetic = true,
                    )
                    else -> null
                }
            }
            if (points.isNotEmpty()) _flightPath.value = points
        } catch (_: Exception) {}
    }

    private fun saveFlightPath() {
        viewModelScope.launch {
            try {
                flightPathFile.writeText(
                    _flightPath.value.joinToString("\n") {
                        // A synthetic time is written back as the three-column row
                        // it came from, so a restored path never gets promoted to
                        // looking like it carries real capture times.
                        if (it.timeSynthetic)
                            "${it.latitude},${it.longitude},${it.altitudeM}"
                        else
                            "${it.latitude},${it.longitude},${it.altitudeM},${it.timestampMs}"
                    }
                )
            } catch (_: Exception) {}
        }
    }

    private fun repeatsLastPathPoint(msg: TelemetryParsed): Boolean =
        repeatsFix(_flightPath.value.lastOrNull(), msg.latitude, msg.longitude, msg.agl)

    // -- App flight log (App Flight Logs screen) ------------------------------
    //
    // Distinct from the flight PATH above and from the locator's downloadable
    // archive.  The path is where the rocket went; the archive is what the locator
    // measured at 20 Hz.  This is what the PHONE saw -- the same 1 Hz frames plus
    // the receiver's RSSI/SNR/noise-floor reading of each one, plus what the app
    // decided and said out loud about them.  None of that survives the flight
    // anywhere else.

    val flightLogStore = FlightLogStore(application)
    private val flightLogRecorder = FlightLogRecorder(flightLogStore.sink())

    private val _flightLogs = MutableStateFlow<List<FlightLogFile>>(emptyList())
    val flightLogs: StateFlow<List<FlightLogFile>> = _flightLogs.asStateFlow()

    // The file currently open, or null.  A boolean would have been enough for the
    // banner and not enough for the list: a log still being written can be shared,
    // and saying so on the wrong row would be worse than not saying it.
    private val _flightLogRecordingName = MutableStateFlow<String?>(null)
    val flightLogRecordingName: StateFlow<String?> = _flightLogRecordingName.asStateFlow()

    // Last values written as events, so each is reported on its edge rather than on
    // every frame that repeats it.  A 1 Hz stream would otherwise carry
    // "link quality: Normal" once a second and bury the transition that matters.
    private var loggedFlightState: FlightStates? = null
    private var loggedLinkQuality: LinkQuality.Verdict? = null
    private var loggedLandingThisFlight = false

    fun refreshFlightLogs() {
        viewModelScope.launch { _flightLogs.value = flightLogStore.list() }
    }

    fun deleteFlightLog(name: String) {
        viewModelScope.launch {
            flightLogStore.delete(name)
            _flightLogs.value = flightLogStore.list()
        }
    }

    fun readFlightLog(name: String): FlightLogContents = flightLogStore.read(name)

    /**
     * Records something the app said aloud.  Wired to [Announcer], which calls this
     * only when speech actually reached the engine.
     */
    fun logAnnouncement(text: String) = logFlightEvent(LogEvent.Announcement, text)

    private fun logFlightEvent(
        event: LogEvent,
        detail: String = "",
        timeMs: Long = System.currentTimeMillis(),
    ) {
        flightLogRecorder.offer(FlightLogRecord.Event(timeMs, event, detail))
    }

    /**
     * Opens a log for a launch just detected.
     *
     * Named for the locator that flew, taken from its own broadcast name: the file
     * has to identify the airframe months later, and a name held anywhere else can
     * be a locator the app is no longer connected to.
     */
    private fun openFlightLog(timeMs: Long) {
        val locatorName = _remoteLocatorConfig.value.deviceName
        val header = buildString {
            append("Steam Pigeon app flight log")
            append("; locator=").append(locatorName.ifEmpty { "unknown" })
            append("; locator_id=").append(_connectedLocatorId.value ?: 0L)
            append("; receiver=").append(_remoteReceiverConfig.value.deviceName)
            append("; receiver_channel=").append(_remoteReceiverConfig.value.channel)
            append("; app_version=").append(
                getApplication<Application>()
                    .getString(com.steampigeon.flightmanager.R.string.git_version)
            )
        }
        loggedLandingThisFlight = false
        if (flightLogRecorder.onLaunch(timeMs, locatorName, header)) {
            _flightLogRecordingName.value = FlightLog.fileName(locatorName, timeMs, ZoneId.systemDefault())
            refreshFlightLogs()
        }
    }

    private fun closeFlightLog(
        reason: LogCloseReason,
        timeMs: Long = System.currentTimeMillis(),
    ) {
        if (!flightLogRecorder.isRecording) return
        flightLogRecorder.close(timeMs, reason)
        _flightLogRecordingName.value = null
        refreshFlightLogs()
    }

    /**
     * The signals that end a log, watched once for the life of the ViewModel.
     *
     * Started from [init] rather than from collectInboundMessageData, which cancels
     * and restarts its collectors on every Activity recreation -- a theme switch
     * would otherwise drop these for as long as it takes to rebind, which is time
     * enough for a locator to be disarmed.
     *
     * Every close is edge-triggered on a value that was previously something else.
     * Level-triggering the disarm would close a log the instant it opened on the
     * disarmed flights ADR-0021 exists to allow.
     */
    private fun startFlightLogWatchers() {
        // The BLE link, which no longer ends a log but still explains a gap in one.
        viewModelScope.launch {
            var wasReady = BluetoothManagerRepository.bluetoothConnectionState.value ==
                    BluetoothConnectionState.Ready
            BluetoothManagerRepository.bluetoothConnectionState.collect { state ->
                val ready = state == BluetoothConnectionState.Ready
                if (ready != wasReady) {
                    logFlightEvent(LogEvent.ConnectionChanged, state.name)
                    wasReady = ready
                }
            }
        }
        viewModelScope.launch {
            var wasArmed = BluetoothManagerRepository.armedState.value
            BluetoothManagerRepository.armedState.collect { armed ->
                if (armed != wasArmed) {
                    logFlightEvent(LogEvent.ArmedStateChanged, if (armed) "armed" else "disarmed")
                    // Disarming is how a flight is signed off at the pad, and the
                    // rows after it are a locator sitting in a box.
                    if (!armed) closeFlightLog(LogCloseReason.Disarmed)
                }
                wasArmed = armed
            }
        }
        viewModelScope.launch {
            var last = _remoteReceiverConfig.value.channel
            _remoteReceiverConfig.collect { cfg ->
                if (cfg.channel != last) {
                    logFlightEvent(LogEvent.ReceiverChannelChanged, "$last -> ${cfg.channel}")
                    // Past this point the rows describe a different piece of sky.
                    closeFlightLog(LogCloseReason.ReceiverChannelChanged)
                    flightLogRecorder.discardPreRoll()
                    last = cfg.channel
                }
            }
        }
        // Tracks the last locator actually CONNECTED, not the last value of the flow.
        //
        // The flow goes null on a dropped BLE link as well as on a deliberate
        // switch, and those must not be treated alike: a dropout mid-recovery is
        // the case this log exists to capture, and ending the file on one would
        // discard the evidence of the thing being investigated.  A release is
        // therefore ignored, a reconnect to the same locator resumes the same file,
        // and only a DIFFERENT locator ends it -- which is the only transition
        // after which the rows would describe another airframe.
        viewModelScope.launch {
            var lastConnected = _connectedLocatorId.value
            _connectedLocatorId.collect { id ->
                if (id == null) return@collect
                if (lastConnected != null && id != lastConnected) {
                    logFlightEvent(LogEvent.LocatorChanged, "$lastConnected -> $id")
                    closeFlightLog(LogCloseReason.LocatorChanged)
                    // Whatever is buffered belongs to the locator being let go of.
                    flightLogRecorder.discardPreRoll()
                }
                lastConnected = id
            }
        }
    }

    /**
     * Logs a pre-launch frame.  Called only for the connected locator: a bystander's
     * broadcasts are not this rocket's flight, and mixing them in would put two
     * airframes in one file with nothing to tell them apart.
     */
    private fun logPrelaunchFrame(
        msg: PrelaunchParsed,
        timeMs: Long,
        verdict: LinkQuality.Verdict,
    ) {
        noteLinkQuality(verdict, timeMs)
        flightLogRecorder.offer(
            FlightLogRecord.Sample(
                timestampMs = timeMs,
                source = LogSource.Prelaunch,
                latitude = msg.latitude,
                longitude = msg.longitude,
                aglM = msg.agl,
                accel = msg.accel,
                gyro = msg.gyro,
                satellites = msg.satellites,
                haccM = msg.hacc,
                rssi = msg.rssi,
                snr = msg.snr,
                noiseFloor = msg.noiseFloor,
                badFrames = msg.badFrames,
                linkQuality = verdict,
                armed = msg.armed,
                deployArmedMask = msg.deployStatus,
                padAlert = msg.padAlert,
                locatorBatteryMv = msg.locatorBatteryMv,
                receiverBatteryMv = msg.receiverBatteryMv,
                receiverChannel = msg.receiverChannel,
                locatorId = msg.locatorId,
            )
        )
    }

    /** Logs a telemetry frame, and the state transitions it carries. */
    private fun logTelemetryFrame(
        msg: TelemetryParsed,
        timeMs: Long,
        verdict: LinkQuality.Verdict,
    ) {
        noteLinkQuality(verdict, timeMs)
        if (loggedFlightState != msg.flightState) {
            logFlightEvent(
                LogEvent.FlightStateChanged,
                "${loggedFlightState?.name ?: "unknown"} -> ${msg.flightState.name}",
                timeMs,
            )
            loggedFlightState = msg.flightState
        }
        // Landing is an EVENT, not a close.  The walk-in to find the rocket is when
        // link quality matters most and is precisely the window nobody can watch, so
        // the log runs on until the locator is disarmed or something else ends it.
        if (!loggedLandingThisFlight && msg.flightState == FlightStates.Landed) {
            loggedLandingThisFlight = true
            logFlightEvent(LogEvent.LandingDetected, "locator reported Landed", timeMs)
        }
        val firedMask = (if (msg.deploymentCh1Stats.and(4) == 4) 1 else 0) or
                (if (msg.deploymentCh2Stats.and(4) == 4) 2 else 0) or
                (if (msg.deploymentCh3Stats.and(4) == 4) 4 else 0) or
                (if (msg.deploymentCh4Stats.and(4) == 4) 8 else 0)
        val armedMask = (if (msg.deploymentCh1Stats.and(32) == 32) 1 else 0) or
                (if (msg.deploymentCh2Stats.and(32) == 32) 2 else 0) or
                (if (msg.deploymentCh3Stats.and(32) == 32) 4 else 0) or
                (if (msg.deploymentCh4Stats.and(32) == 32) 8 else 0)
        flightLogRecorder.offer(
            FlightLogRecord.Sample(
                timestampMs = timeMs,
                source = LogSource.Telemetry,
                flightState = msg.flightState,
                latitude = msg.latitude,
                longitude = msg.longitude,
                aglM = msg.agl,
                velNed = msg.velNed,
                attitude = msg.attitude,
                satellites = msg.satellites,
                haccM = msg.hacc,
                rssi = msg.rssi,
                snr = msg.snr,
                noiseFloor = msg.noiseFloor,
                badFrames = msg.badFrames,
                linkQuality = verdict,
                armed = msg.armed,
                deployArmedMask = armedMask,
                deployFiredMask = firedMask,
                drogueDetected = msg.physicalDeploymentStats.and(1) == 1,
                mainDetected = msg.physicalDeploymentStats.and(2) == 2,
                locatorId = msg.locatorId,
            )
        )
    }

    /**
     * Logs a ReceiverInfo poll.
     *
     * Worth a row precisely because it arrives when nothing else does: it is the
     * receiver measuring the channel with the locator silent (ADR-0019), so it is
     * the only evidence of what a dropout looked like from this end.  A gap in the
     * telemetry rows with these still ticking through it says the channel was quiet;
     * a gap with a raised noise floor says something else was on it.
     */
    private fun logReceiverInfoFrame(msg: ReceiverInfoParsed, timeMs: Long) {
        flightLogRecorder.offer(
            FlightLogRecord.Sample(
                timestampMs = timeMs,
                source = LogSource.ReceiverInfo,
                noiseFloor = msg.noiseFloor,
                badFrames = msg.badFrames,
                receiverChannel = msg.channel,
            )
        )
    }

    private fun noteLinkQuality(verdict: LinkQuality.Verdict, timeMs: Long) {
        if (loggedLinkQuality == verdict) return
        logFlightEvent(
            LogEvent.LinkQualityChanged,
            "${loggedLinkQuality?.name ?: "unknown"} -> ${verdict.name}",
            timeMs,
        )
        loggedLinkQuality = verdict
    }


    fun startFlightPathRecording() { _isFlightPathRecording.value = true }
    fun stopFlightPathRecording() { _isFlightPathRecording.value = false }
    fun resetFlightPath() {
        _flightPath.value = emptyList()
        // Start clean means recording again, even mid-descent or with the rocket
        // already down: a cleared path that then refused to draw would look
        // broken. A rocket still transmitting Landed re-marks its own position,
        // once, and stops there again.
        landingConcludedThisFlight = false
        landedStatusReceived = false
        // Clear the archived track too, and fall back to live. Reset is the map's
        // "start clean" control, and leaving a downloaded track drawn after it
        // would look like the reset had failed.
        _archivedFlightPath.value = emptyList()
        _showArchivedPath.value = false
        viewModelScope.launch {
            try { flightPathFile.delete() } catch (_: Exception) {}
        }
    }

    // ── Deployment test ──────────────────────────────────────────────────────
    // The rule here is that the DISPLAY follows the locator, never the app's own
    // hope.  Pressing cancel used to clear deploymentTestActive immediately,
    // which gated the countdown handler below and made the app deaf to the very
    // countdown that was still running: the button went back to reading "start"
    // while the locator counted down and fired.  Nothing on screen disagreed.
    //
    // A cancel is one unacknowledged LoRa frame.  It is a request, and the only
    // evidence it was honored is the countdown going quiet.
    private val _deploymentTestActive = MutableStateFlow<Boolean>(false)
    val deploymentTestActive: StateFlow<Boolean> = _deploymentTestActive.asStateFlow()

    fun updateDeploymentTestActive(newDeploymentTestActive: Boolean) {
        _deploymentTestActive.value = newDeploymentTestActive
        if (newDeploymentTestActive) {
            // A start frame can be lost too; without this the screen would sit
            // "active" forever waiting for a countdown that is never coming.
            armDeploymentTestSilenceWatchdog()
        } else {
            deploymentTestSilenceJob?.cancel()
            deploymentTestSilenceJob = null
            _deploymentTestCancelPending.value = false
            _deploymentTestCountdown.value = 0
        }
    }

    // No public setter: the countdown is written only by the locator's messages
    // and by the silence watchdog.  The screen used to zero it on cancel, which
    // is the bug above — letting a caller assert a countdown that the locator has
    // not agreed to is the whole failure mode, so the way to do it is gone.
    private val _deploymentTestCountdown = MutableStateFlow<Int>(0)
    val deploymentTestCountdown: StateFlow<Int> = _deploymentTestCountdown.asStateFlow()

    // True from the moment a cancel frame is sent until the countdown stops.
    // Drives the "STOPPING" label: it says the request is out and unanswered,
    // which is exactly the state the operator needs to see rather than a button
    // that has already returned to normal.
    private val _deploymentTestCancelPending = MutableStateFlow(false)
    val deploymentTestCancelPending: StateFlow<Boolean> = _deploymentTestCancelPending.asStateFlow()

    private var deploymentTestSilenceJob: Job? = null

    /**
     * Record that a cancel frame has just been handed to the radio.  Deliberately
     * changes nothing about the countdown: the locator decides when the test is
     * over, and this app finds out by the countdown stopping.
     */
    fun noteDeploymentTestCancelSent() {
        if (!_deploymentTestActive.value) return
        _deploymentTestCancelPending.value = true
        armDeploymentTestSilenceWatchdog()
    }

    // Restarted by every countdown message, so it only fires once the locator has
    // genuinely gone quiet.  One rule covers all three endings — canceled, fired,
    // link lost — because from here they are indistinguishable, and all three mean
    // the same thing for the screen.
    private fun armDeploymentTestSilenceWatchdog() {
        deploymentTestSilenceJob?.cancel()
        deploymentTestSilenceJob = viewModelScope.launch {
            delay(DEPLOYMENT_TEST_SILENCE_MS)
            _deploymentTestCancelPending.value = false
            _deploymentTestCountdown.value = 0
            _deploymentTestActive.value = false
        }
    }

    private val _locatorVersion = MutableStateFlow("")
    val locatorVersion: StateFlow<String> = _locatorVersion.asStateFlow()

    private val _receiverVersion = MutableStateFlow("")
    val receiverVersion: StateFlow<String> = _receiverVersion.asStateFlow()

    /**
     * Whether the cached firmware stamps are still trustworthy.
     *
     * A peer that drops off the link and comes back may have been reflashed in
     * between, so both reconnect paths set this: the LoRa link returning (the
     * locator power-cycled or was reflashed) and a Bluetooth disconnect (the
     * receiver did).  Cleared when a fresh VersionInfo lands.
     *
     * Deliberately separate from the version strings themselves — blanking those
     * on every brief LoRa dropout would flicker the settings screens, since both
     * hide the row while empty.  The stale stamp stays on screen until a newer
     * one replaces it.
     */
    private val versionInfoStale = MutableStateFlow(true)

    fun startService() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, BluetoothService()::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopService() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, BluetoothService()::class.java)
        context.stopService(intent)
    }

    /**
     * Collectors started by the most recent [collectInboundMessageData] call, held
     * so a later call can cancel them.
     */
    private var inboundJobs: List<Job> = emptyList()

    @OptIn(ExperimentalUnsignedTypes::class)
    fun collectInboundMessageData(service: BluetoothService) {
        // Cancel the previous collectors before starting new ones.
        //
        // This runs from onServiceConnected, which fires again on every Activity
        // recreation: BindBluetoothService's DisposableEffect unbinds and rebinds,
        // while the ViewModel deliberately survives.  Rotation is covered by
        // MainActivity's configChanges, but uiMode is NOT — a light/dark switch
        // alone recreates the Activity, as does a locale or font-scale change.
        //
        // Without this cancel every recreation left another live collector behind,
        // and each one handles every packet: N duplicate flight-path points, N
        // evaluateRecognition calls, and N runs of the windowed FlightData
        // transfer/ACK handling.  Measured on a Pixel 9 Pro XL — four theme
        // toggles produced four collectors on one ViewModel instance, handling
        // 29/28/23/19 packets concurrently.
        inboundJobs.forEach { it.cancel() }

        // Keep the service's send gate in sync with the connection, so only the
        // locator we are connected to can be commanded.  BluetoothService.locatorConnected
        // carries the reason it is *connected* and not merely authorized.
        val sendGateJob = viewModelScope.launch {
            // Carries the id, not a flag: since ADR-0020 that same id addresses every
            // locator-directed command, so authorizing and addressing a send are one
            // value and cannot disagree.
            connectedLocatorId.collect { service.connectedLocatorId = it }
        }
        // The verdict has to be able to change while NOTHING is arriving.
        //
        // Classification runs on packet receipt, so during a dropout it simply does
        // not run: the note kept showing whatever the last good packet decided,
        // which was "busy but clean" because no loss had been recorded yet. The
        // marker went red beside a note saying the link was fine. Recording loss
        // needs a packet, and a dropout is the absence of packets — so the first
        // dropout of any epoch could never describe itself.
        val linkLivenessJob = viewModelScope.launch {
            while (true) {
                delay(LINK_LIVENESS_TICK_MS)
                val st = _rocketState.value
                val now = System.currentTimeMillis()
                val heardLocator = st.lastMessageTime != 0L
                // Never having heard the locator used to end the tick here, because
                // nothing was known about the channel until a broadcast described
                // it. ReceiverInfo now describes it without one, so the app can say
                // "your channel is occupied" to someone who has switched on and is
                // hearing nothing — which is the most useful moment to say it.
                if (heardLocator) {
                    if (now - st.lastMessageTime < LinkQuality.LOSSY_GAP_MS) continue
                } else {
                    if (!LinkQuality.isMeasurementFresh(lastFloorMeasurementMs, now)) continue
                }
                // We are in a dropout right now, not remembering an old one.
                //
                // Only counted as loss when there was a cadence to miss. With no
                // broadcast ever received there is no expected arrival, so calling
                // the silence "loss" would invent the very evidence the conjunction
                // in classify() exists to demand — bad frames from ReceiverInfo are
                // then the only thing that can establish it, and they are real.
                if (heardLocator)
                    lastLossMs = LinkQuality.updateLastLoss(lastLossMs, now, now - st.lastMessageTime)
                // The measurements below are whatever the last message left behind,
                // and this tick took none of its own. Two corrections apply, and
                // both were needed before a switched-off locator stopped being
                // reported as a jammed channel:
                //
                // Age them, so that once they lapse the only things that can still
                // assert Interference are live — a decoded foreign broadcast, or a
                // floor the receiver has just re-read for us on ReceiverInfo.
                //
                // And judge a polled floor against a baseline from its OWN sampling
                // regime, with the absolute test dropped: that test is calibrated
                // for the safe-window statistic, and a continuously-sampled peak
                // clears it on a channel with nothing on it whatsoever.
                val fromPoll = floorFromPoll
                val verdict = LinkQuality.classify(
                    st.rssi, st.snr, st.noiseFloor,
                    if (fromPoll) quietestPolledFloor else quietestNoiseFloor,
                    lossy = LinkQuality.isLossy(lastLossMs, now),
                    foreignLocator = LinkQuality.isLossy(lastForeignBroadcastMs, now),
                    packetFresh = LinkQuality.isMeasurementFresh(lastPacketMeasurementMs, now),
                    floorFresh = LinkQuality.isMeasurementFresh(lastFloorMeasurementMs, now),
                    absoluteFloorTrusted = !fromPoll,
                )
                if (verdict != st.linkQuality)
                    _rocketState.update { it.copy(linkQuality = verdict) }
            }
        }
        // Keeps a live channel measurement coming while the locator is silent.
        //
        // The health watchdog already asks for ReceiverInfo during silence, but on
        // its own 10 s cadence — four times longer than a measurement stays fresh,
        // so a floor sourced from it would be expired for most of its life and the
        // note would blink on and off between probes. This polls at a rate the
        // freshness window can actually keep up with.
        //
        // Deliberately narrow, and NOT started at the first missed broadcast — see
        // CHANNEL_WATCH_SILENCE_MS. While the locator is alive at all, the packets
        // that do arrive carry the floor themselves, so polling on top of them adds
        // no information and costs a measurement window.
        //
        // It cannot mask a dead link: the health watchdog measures answers, not
        // requests, so an unanswered poll leaves lastDataTime stale exactly as
        // before and the phantom-connection check still fires.
        val channelWatchJob = viewModelScope.launch {
            while (true) {
                delay(CHANNEL_WATCH_TICK_MS)
                if (BluetoothManagerRepository.bluetoothConnectionState.value
                    != BluetoothConnectionState.Ready) continue
                val st = _rocketState.value
                val silent = st.lastMessageTime == 0L ||
                        System.currentTimeMillis() - st.lastMessageTime >= CHANNEL_WATCH_SILENCE_MS
                if (silent) service.requestReceiverInfo()
            }
        }
        val packetJob = viewModelScope.launch {
            service.packets.collect { locatorMessage ->
//                Log.d("Collector", "Received packet size=${locatorMessage.size} bytes")
                val currentTime = System.currentTimeMillis()
                try {
                    when (val parsed = parseIncoming(locatorMessage)) {
                        is ParsedMessage.Prelaunch -> {
                            // Bad frames describe the CHANNEL, not the sender. The
                            // receiver's counter is drained by whichever broadcast is
                            // forwarded first, which during contention is usually the
                            // interfering locator's — so recording them only inside
                            // the connected-locator gate threw away most of them.
                            if (parsed.msg.badFrames > 0) lastLossMs = currentTime
                            // Gate BEFORE display: identify + authenticate the sender first,
                            // and surface its telemetry/config only if THIS locator is the
                            // connected one. Dismissing the password prompt leaves the sender
                            // unauthorized, so its data is never shown (no bypass); an
                            // authorized-but-not-connected sender is likewise not shown.
                            evaluateRecognition(
                                locatorMessage, parsed.msg.locatorId, parsed.msg.deviceName,
                                receiverChannel = parsed.msg.receiverChannel,
                            )
                            if (_connectedLocatorId.value == parsed.msg.locatorId) {
                            // Arm state is READ from the locator's stated flag, never
                            // inferred from which message arrived (ADR-0021 Decision 3,
                            // FR-A10, #35). Still derived here rather than in the framer,
                            // so another locator on the channel cannot flip it (ADR-0020).
                            if (BluetoothManagerRepository.armedState.value != parsed.msg.armed) {
                                BluetoothManagerRepository.updateArmedState(parsed.msg.armed)
                                BluetoothManagerRepository.updateLocatorArmedMessageState(
                                    LocatorMessageState.AckUpdated)
                            }
                            // Prepped-and-disarmed verdict (#37), inside the same
                            // connected-locator gate so a bystander cannot raise it.
                            BluetoothManagerRepository.updatePadAlert(parsed.msg.padAlert)
                            BluetoothManagerRepository.updatePadAlertSnoozeMinutes(
                                parsed.msg.padAlertSnoozeMinutes)
                            val verdict = classifyLink(
                                parsed.msg.rssi, parsed.msg.snr, parsed.msg.noiseFloor, parsed.msg.badFrames, currentTime)
                            _rocketState.update { currentState ->
                                currentState.copy(
                                    lastMessageTime = currentTime,
                                    // Only the pre-launch branch stamps this, which
                                    // is what makes it usable for ageing the fields
                                    // only this message carries.
                                    lastPreLaunchDataTime = currentTime,
                                    latitude = parsed.msg.latitude,
                                    longitude = parsed.msg.longitude,
                                    rawLatitude = parsed.msg.rawLatitude,
                                    rawLongitude = parsed.msg.rawLongitude,
                                    satellites = parsed.msg.satellites.toUByte(),
                                    hacc = parsed.msg.hacc,
                                    baroStatus = parsed.msg.baroStatus,
                                    imuStatus = parsed.msg.imuStatus,
                                    gpsStatus = parsed.msg.gpsStatus,
                                    deployChannel1Armed = parsed.msg.deployStatus.and(1) == 1,
                                    deployChannel2Armed = parsed.msg.deployStatus.and(2) == 2,
                                    deployChannel3Armed = parsed.msg.deployStatus.and(4) == 4,
                                    deployChannel4Armed = parsed.msg.deployStatus.and(8) == 8,
                                    // Guard against a non-finite AGL ever entering UI state:
                                    // it would render as "NaN" and feed NaN into tilt/descent math.
                                    altitudeAboveGroundLevel = parsed.msg.agl.takeIf { it.isFinite() }
                                        ?: currentState.altitudeAboveGroundLevel,
                                    accelerometer = parsed.msg.accel,
                                    gForce = sqrt(parsed.msg.accel.x * parsed.msg.accel.x + parsed.msg.accel.y * parsed.msg.accel.y +
                                            parsed.msg.accel.z * parsed.msg.accel.z),
                                    orientation =
                                        when {
                                            _rocketState.value.accelerometer.x.toFloat() < -0.5 -> "up"
                                            _rocketState.value.accelerometer.x.toFloat() > 0.5 -> "down"
                                            else -> "side"
                                        },
                                    gyro = parsed.msg.gyro,
                                    locatorBatteryLevel = ((parsed.msg.locatorBatteryMv - 3700) / 400.0f * 8).toInt(),
                                    receiverBatteryLevel = ((parsed.msg.receiverBatteryMv - 3700) / 400.0f * 8).toInt(),
                                    rssi = parsed.msg.rssi,
                                    snr = parsed.msg.snr,
                                    noiseFloor = parsed.msg.noiseFloor,
                                    linkQuality = verdict,
                                )
                            }
                            _remoteLocatorConfig.update { currentState ->
                                currentState.copy(
                                    deploymentChannel1Mode = parsed.msg.deployCh1Mode,
                                    deploymentChannel2Mode = parsed.msg.deployCh2Mode,
                                    deploymentChannel3Mode = parsed.msg.deployCh3Mode,
                                    deploymentChannel4Mode = parsed.msg.deployCh4Mode,
                                    droguePrimaryDeployDelay = parsed.msg.droguePrimaryDelay,
                                    drogueBackupDeployDelay = parsed.msg.drogueBackupDelay,
                                    mainPrimaryDeployAltitude = parsed.msg.mainPrimaryAltitude,
                                    mainBackupDeployAltitude = parsed.msg.mainBackupAltitude,
                                    // launch_detect_altitude and deploy_signal_duration used to be
                                    // rebuilt here with hardcoded firmware defaults, because neither
                                    // rides in PreLaunchData.  They are gone from LocatorConfig
                                    // entirely now: the app no longer sets either, so it no longer
                                    // has to pretend to know them for the confirmation comparison.
                                    // A received PreLaunchData proves the locator and
                                    // receiver share a channel, and the receiver appends
                                    // that channel as receiverChannel.  Use it as the
                                    // locator's current LoRa channel so Locator Settings
                                    // shows the true value and channel changes can be
                                    // confirmed by whole-object equality below.
                                    loraChannel = parsed.msg.receiverChannel,
                                    deviceName = parsed.msg.deviceName,
                                    // Taken from the broadcast, not remembered locally.
                                    // changeLocatorConfig sends the WHOLE struct, so a
                                    // setting the app did not read back would be reset
                                    // to Auto the next time any other field is edited.
                                    noseAxis = parsed.msg.noseAxis,
                                )
                            }
                            logPrelaunchFrame(parsed.msg, currentTime, verdict)
                            } // end recognized-locator gate
                            // Receiver metadata (channel/name) is the user's own receiver,
                            // not the locator — reflect it regardless of recognition so the
                            // Receiver Settings channel display and challenge flow still work.
                            _remoteReceiverConfig.update { currentState ->
                                currentState.copy(
                                    channel = parsed.msg.receiverChannel,
                                    deviceName = if (parsed.msg.receiverName.isNotEmpty())
                                        parsed.msg.receiverName
                                    else
                                        currentState.deviceName,
                                )
                            }
                        }
                        is ParsedMessage.Telemetry -> {
                            if (parsed.msg.badFrames > 0) lastLossMs = currentTime
                            // Telemetry carries the same identity + auth_tag pair as
                            // PreLaunchData, so an ARMED locator authenticates itself
                            // exactly like a disarmed one — which is what lets the app
                            // start up mid-flight, or with the rocket already on the pad
                            // armed, and show its telemetry (ADR-0006).
                            evaluateRecognition(
                                locatorMessage, parsed.msg.locatorId,
                                deviceName = "",
                                baseSize = Protocol.TELEMETRY_BASE_STRUCT_SIZE,
                                challengeable = false,
                            )
                            if (_connectedLocatorId.value == parsed.msg.locatorId) {
                            // Read from the stated flag, same as the PreLaunchData side.
                            // This site is the one that mattered: "TelemetryData ⇒ armed"
                            // was true only while a disarmed locator never broadcast in
                            // flight. Once arming gates pyro only (#36) it stops being
                            // true, and the old reading would have shown an unarmed
                            // ballistic flight as ARMED — hiding the one thing the
                            // operator needed to see (ADR-0021 Decision 3, FR-A10).
                            if (BluetoothManagerRepository.armedState.value != parsed.msg.armed) {
                                BluetoothManagerRepository.updateArmedState(parsed.msg.armed)
                                BluetoothManagerRepository.updateLocatorArmedMessageState(
                                    LocatorMessageState.AckUpdated)
                            }
                            // TelemetryData carries no pad_alert — it is an on-pad
                            // condition and this message means armed or in flight.
                            // Cleared explicitly because PreLaunchData stops arriving
                            // at that point, so a set flag would otherwise latch on
                            // stale data and keep warning through the whole flight.
                            BluetoothManagerRepository.updatePadAlert(PadAlertState.Quiet)
                            BluetoothManagerRepository.updatePadAlertSnoozeMinutes(0)
                            val verdict = classifyLink(
                                parsed.msg.rssi, parsed.msg.snr, parsed.msg.noiseFloor, parsed.msg.badFrames, currentTime)
                            _rocketState.update { currentState ->
                                currentState.copy(
                                    lastMessageTime = currentTime,
                                    latitude = parsed.msg.latitude,
                                    longitude = parsed.msg.longitude,
                                    satellites = parsed.msg.satellites.toUByte(),
                                    hacc = parsed.msg.hacc,
                                    baroStatus = parsed.msg.baroStatus,
                                    imuStatus = parsed.msg.imuStatus,
                                    gpsStatus = parsed.msg.gpsStatus,
                                    deployChannel1Armed = parsed.msg.deploymentCh1Stats.and(32) == 32,
                                    deployChannel2Armed = parsed.msg.deploymentCh2Stats.and(32) == 32,
                                    deployChannel3Armed = parsed.msg.deploymentCh3Stats.and(32) == 32,
                                    deployChannel4Armed = parsed.msg.deploymentCh4Stats.and(32) == 32,
                                    channel1Fired = parsed.msg.deploymentCh1Stats.and(4) == 4,
                                    channel2Fired = parsed.msg.deploymentCh2Stats.and(4) == 4,
                                    channel3Fired = parsed.msg.deploymentCh3Stats.and(4) == 4,
                                    channel4Fired = parsed.msg.deploymentCh4Stats.and(4) == 4,
                                    drogueDeployDetected = parsed.msg.physicalDeploymentStats.and(1) == 1,
                                    mainDeployDetected = parsed.msg.physicalDeploymentStats.and(2) == 2,
                                    // Guard against a non-finite AGL ever entering UI state:
                                    // it would render as "NaN" and feed NaN into tilt/descent math.
                                    altitudeAboveGroundLevel = parsed.msg.agl.takeIf { it.isFinite() }
                                        ?: currentState.altitudeAboveGroundLevel,
                                    velNed = parsed.msg.velNed,
                                    velocity = sqrt(parsed.msg.velNed.x * parsed.msg.velNed.x +
                                            parsed.msg.velNed.y * parsed.msg.velNed.y +
                                            parsed.msg.velNed.z * parsed.msg.velNed.z),
                                    attitude = parsed.msg.attitude,
                                    flightState = parsed.msg.flightState,
                                    rssi = parsed.msg.rssi,
                                    snr = parsed.msg.snr,
                                    noiseFloor = parsed.msg.noiseFloor,
                                    linkQuality = verdict,
                                )
                            }
                            // Reset path on new launch; accumulate during flight.
                            // See startsNewFlight for why the trigger is any
                            // grounded → airborne transition.
                            val newFlightState = parsed.msg.flightState
                            if (flightStateObserved &&
                                startsNewFlight(_previousFlightState, newFlightState)) {
                                _flightPath.value = emptyList()
                                saveFlightPath()
                                landingConcludedThisFlight = false
                                landedStatusReceived = false
                                // Fall back to the live track. An archived record substitutes
                                // for the live path while displayed, so a new flight left on
                                // the archived source would draw the old record and none of
                                // the flight now in the air.  The download itself is kept.
                                _showArchivedPath.value = false
                                // Opened BEFORE this frame is logged, so the frame that
                                // proved the launch lands after the launch marker rather
                                // than in the pre-roll ahead of it.
                                openFlightLog(currentTime)
                            }
                            // Whether this fix is drawn is decided against what was
                            // known BEFORE it arrived, so the fixes that end the
                            // flight still land on the path.  The flags are then
                            // set from the fix itself — on receipt, not on it being
                            // drawn: a Landed fix that the de-duplicator drops still
                            // ends the recording.
                            val records = recordsPathPoint(
                                newFlightState,
                                landingConcludedThisFlight,
                                landedStatusReceived,
                            )
                            if (landingConcluded(newFlightState, parsed.msg.agl, parsed.msg.velNed.z))
                                landingConcludedThisFlight = true
                            if (newFlightState == FlightStates.Landed) landedStatusReceived = true
                            if (_isFlightPathRecording.value &&
                                records &&
                                (parsed.msg.latitude != 0.0 || parsed.msg.longitude != 0.0) &&
                                !repeatsLastPathPoint(parsed.msg)) {
                                // Wall-clock, not elapsedRealtime: the path is
                                // persisted and reloaded across process restarts,
                                // where a monotonic clock's zero has moved.
                                _flightPath.value = _flightPath.value + PathPoint(
                                    parsed.msg.latitude,
                                    parsed.msg.longitude,
                                    parsed.msg.agl,
                                    System.currentTimeMillis(),
                                )
                                saveFlightPath()
                            }
                            _previousFlightState = newFlightState
                            flightStateObserved = true
                            logTelemetryFrame(parsed.msg, currentTime, verdict)
                            } // end recognized-locator gate
                        }
                        is ParsedMessage.DeploymentTest -> {
                            val deploymentTestCountdown = parsed.msg.count
                            if (_deploymentTestActive.value) {
                                _deploymentTestCountdown.value = deploymentTestCountdown
                                // The locator is still counting, so whatever the
                                // app believes, the charge is live.  Note this
                                // is armed even while a cancel is pending: a
                                // frame that crosses the cancel in flight must
                                // not be mistaken for the cancel being refused,
                                // and the countdown stopping is what settles it.
                                armDeploymentTestSilenceWatchdog()
                            }
                        }
                        is ParsedMessage.ReceiverInfo -> {
                            logReceiverInfoFrame(parsed.msg, currentTime)
                            _remoteReceiverConfig.update { currentState ->
                                currentState.copy(
                                    channel    = parsed.msg.channel,
                                    deviceName = if (parsed.msg.deviceName.isNotEmpty())
                                        parsed.msg.deviceName
                                    else
                                        currentState.deviceName,
                                )
                            }
                            // The ADR-0011 transmit receipt.  The receiver answers a
                            // ReceiverCfgChgRequest with one of these too, so the
                            // channel is the discriminator: only a report matching the
                            // move in flight says "your locator change is on air".  A
                            // recovery revert reports the OLD channel and correctly
                            // does not re-base anything.
                            //
                            // LATCHED — only the FIRST match re-bases, and this is
                            // load-bearing.  ReceiverInfo is not only the unsolicited
                            // receipt: `channelWatchJob` polls for one every 2 s once
                            // the locator has been silent for 5 s, which is precisely
                            // the state an unconfirmed move is in.  Every reply carries
                            // the new channel and so matched this test, each one pushed
                            // the confirm deadline out by another 5 s, and 2 s < 5 s
                            // meant the window NEVER closed: the banner sat on "Moving
                            // to channel N…" forever, the probe never ran, and the
                            // receiver was left on the new channel with the locator on
                            // the old one.  That is a lost locator, and it is the bug
                            // the bench found on 2026-08-30.
                            if (channelMoveReceiptMs == 0L &&
                                parsed.msg.channel == _pendingChannelMove.value)
                                channelMoveReceiptMs = System.currentTimeMillis()
                            // The only channel measurement that does not need a
                            // locator. This is what separates "the locator is off"
                            // from "something is sitting on our channel" — during a
                            // dropout it is the sole live evidence, and without it
                            // the classifier can only extrapolate from whatever the
                            // last surviving broadcast happened to report.
                            //
                            // Bad frames counted here are loss we can SEE with no
                            // locator transmitting at all: something else is on the
                            // channel and being destroyed, which is the case the
                            // gap-based test cannot distinguish from silence.
                            if (parsed.msg.badFrames > 0) lastLossMs = currentTime
                            if (parsed.msg.noiseFloor != LinkQuality.NOISE_FLOOR_UNKNOWN) {
                                // Its OWN baseline, never the broadcast one. These
                                // readings come from the continuous-sampling regime
                                // and read higher; feeding them to a shared
                                // minimum-keeping baseline made every one of them
                                // look elevated, permanently.
                                quietestPolledFloor = LinkQuality.updateQuietestFloor(
                                    quietestPolledFloor, parsed.msg.noiseFloor)
                                lastFloorMeasurementMs = currentTime
                                floorFromPoll = true
                                // Published so the liveness tick reclassifies against
                                // this reading rather than the pre-dropout one. rssi
                                // and snr are deliberately left alone: no packet
                                // arrived, so there is nothing new to say about them,
                                // and they age out on their own clock.
                                _rocketState.update { it.copy(noiseFloor = parsed.msg.noiseFloor) }
                                SpLog.d("LinkQuality",
                                    "Channel poll: floor=${parsed.msg.noiseFloor} dBm " +
                                            "quietestPolled=$quietestPolledFloor " +
                                            "badFrames=${parsed.msg.badFrames}")
                            }
                        }
                        is ParsedMessage.VersionInfo -> {
                            _locatorVersion.value = parsed.msg.locatorVersion
                            _receiverVersion.value = parsed.msg.receiverVersion
                            versionInfoStale.value = false
                        }
                        is ParsedMessage.ChannelSurvey -> {
                            surveyTimeoutJob?.cancel()
                            _surveyInProgress.value = false
                            _channelSurvey.value = ChannelSurveyData.analyze(
                                parsed.msg.status, parsed.msg.levels, parsed.msg.homeChannel,
                                parsed.msg.confirmed, parsed.msg.confirmedFrames,
                                parsed.msg.confirmedLocatorIds,
                            )
                            // The sweep left the home channel for ~1 s, so the noise-floor
                            // baseline built from before it describes a stale picture of a
                            // band we now know more about. Start it over.
                            quietestNoiseFloor = LinkQuality.NOISE_FLOOR_UNKNOWN
                            quietestPolledFloor = LinkQuality.NOISE_FLOOR_UNKNOWN
                            // Same for the last floor reading: it was taken before the
                            // radio wandered off, and the receiver suppresses sampling
                            // during a survey, so nothing measured the home channel
                            // meanwhile. Expire it rather than let it stand in.
                            lastFloorMeasurementMs = 0L
                        }
                        is ParsedMessage.LocatorSearch -> onLocatorSearchResult(parsed.msg)
                        is ParsedMessage.FlightMetadata -> {
                            val ok = FlightDataRepository.onFlightMetadata(parsed.frame)
                            if (ok) {
                                _flightProfileMetadataMessageState.value = LocatorMessageState.AckUpdated
                                // Expose parsed metadata to the UI via the existing _flightProfileMetadata flow.
                                // FlightDataRepository.metadata maps directly to FlightProfileMetadata used by the UI.
                                _flightProfileMetadata.value = FlightDataRepository.metadata.value.map { record ->
                                    FlightProfileMetadata(
                                        position    = record.position,
                                        date        = if (record.timestampS > 0L)
                                                          java.time.Instant.ofEpochSecond(record.timestampS)
                                                              .atZone(java.time.ZoneId.systemDefault())
                                                      else null,
                                        apogee      = record.apogeeM,
                                        timeToDrogue = record.flightTimeMs / 1000f,
                                    )
                                }
                            }
                        }

                        is ParsedMessage.FlightEvents -> {
                            // Only adopt the summary for the record the user is
                            // actually viewing.  The locator repeats this frame,
                            // and a late one from a previously-selected record
                            // would otherwise mislabel the current chart.
                            if (parsed.msg.record == _flightProfileArchivePosition.value)
                                _flightEvents.value = parsed.msg
                        }

                        is ParsedMessage.FlightData -> {
                            val ackFrame = FlightDataRepository.onFlightData(parsed.frame)
                            if (ackFrame != null) {
                                service.sendFlightDataAck(ackFrame)
                                val progress = FlightDataRepository.progress.value
                                _flightProfileDataMessageState.value =
                                    if (progress.complete) LocatorMessageState.AckUpdated else LocatorMessageState.Sent

                                // Publish samples to the existing AGL / accelerometer flows as they arrive.
                                // This gives the UI a live partial view during a long transfer.
                                if (progress.complete || FlightDataRepository.samples.value.isNotEmpty()) {
                                    val samples = FlightDataRepository.samples.value
                                    _flightProfileAglData.value    = samples.map { (it.altitudeM * 10).toInt().toUShort() }
                                    _flightProfileAccelerometerData.value = samples.map { it.accel }
                                    publishArchivedPath(samples)
                                }
                            }
                        }

                        is ParsedMessage.FlightDataParity -> {
                            val ackFrame = FlightDataRepository.onFlightDataParity(parsed.frame)
                            if (ackFrame != null) {
                                service.sendFlightDataAck(ackFrame)
                                // Publish any samples recovered via parity
                                val samples = FlightDataRepository.samples.value
                                if (samples.isNotEmpty()) {
                                    _flightProfileAglData.value           = samples.map { (it.altitudeM * 10).toInt().toUShort() }
                                    _flightProfileAccelerometerData.value = samples.map { it.accel }
                                    publishArchivedPath(samples)
                                }
                            }
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    Log.e("Parser", "Error parsing inbound packet", e)
                }
            }
        }

        // Re-request version info whenever the cached stamps go stale.  Only sends
        // when the locator is actively sending PreLaunchData (i.e. the LoRa link is
        // up), so the VersionRequest can be timed around prelaunch messages by the
        // receiver.
        //
        // The loop runs for the collector's lifetime rather than exiting on first
        // success: a locator reflashed mid-session would otherwise keep reporting
        // the version it booted with until the app was restarted.  A rising edge on
        // the link (silent -> sending again) is the reflash signal, since flashing
        // takes the locator off the air.  It still transmits only while stale, so
        // the steady state is silent.
        val versionJob = viewModelScope.launch {
            // Seed from the live link rather than false: this function re-runs on
            // every Activity recreation, and a false seed would read the already-up
            // link as a rising edge and re-request on each theme or locale change.
            var linkWasUp =
                System.currentTimeMillis() - _rocketState.value.lastMessageTime < 5_000L
            while (true) {
                delay(1_000L)
                val age = System.currentTimeMillis() - _rocketState.value.lastMessageTime
                val linkUp = age < 5_000L
                if (linkUp && !linkWasUp) versionInfoStale.value = true
                linkWasUp = linkUp
                if (linkUp && versionInfoStale.value) {
                    service.requestVersionInfo()
                    delay(5_000L)
                }
            }
        }

        // The receiver can only be reflashed across a Bluetooth drop, which the LoRa
        // edge above cannot see — the locator may keep transmitting throughout.
        val connectionJob = viewModelScope.launch {
            BluetoothManagerRepository.bluetoothConnectionState.collect { state ->
                if (state == BluetoothConnectionState.Disconnected) {
                    versionInfoStale.value = true
                    releaseLocatorOnLinkLoss()
                }
            }
        }

        // All six are canceled together on the next call.  The two that transmit
        // matter as much as the packet collector: a leaked version loop is a
        // redundant VersionRequest every few seconds forever, and a leaked channel
        // watch is a redundant ReceiverInfoRequest every 2 s on top of it.
        inboundJobs = listOf(sendGateJob, packetJob, versionJob, connectionJob, linkLivenessJob,
            channelWatchJob)
    }

/*
//                when (parsed) {
//                    locatorMessageHeader.contentEquals(BluetoothService.receiverConfigMessageHeader) -> {
//                        _remoteReceiverConfig.update { currentState ->
//                            currentState.copy(channel = locatorMessage[3].toInt())
//                        }
//                    }
//                    locatorMessageHeader.contentEquals(BluetoothService.flightProfileMetadataMessageHeader) -> {
//                        _flightProfileMetadataMessageState.value = LocatorMessageState.AckUpdated
//                        var archivePosition = 0
//                        var messagePosition = locatorMessageHeader.size
//                        while (messagePosition < locatorMessage.size// && !(locatorMessage[messagePosition] == 0.toByte() &&
//                                    //locatorMessage[messagePosition + 1] == 0.toByte() && locatorMessage[messagePosition + 2] == 0.toByte())
//                            )
//                        {
//                            val flightProfileMetadataItem = FlightProfileMetadata(
//                                archivePosition,
//                                byteArrayToDate(locatorMessage, messagePosition),
//                                byteArrayToFloat(locatorMessage, messagePosition + 8),
//                                byteArrayToFloat(locatorMessage, messagePosition + 12)
//                            )
//                            if (flightProfileMetadataItem.date != null)
//                                _flightProfileMetadata.value += flightProfileMetadataItem
//                            archivePosition++
//                            messagePosition += 16
//                        }
//                    }
//                    locatorMessageHeader.contentEquals(BluetoothService.flightProfileDataMessageHeader) -> {
//                        var archiveSample = 0
//                        val packetIndex = locatorMessage[locatorMessageHeader.size].and(0x7f)
//                        Log.d(TAG, "Received flight profile data packet $packetIndex")
//                        if (packetIndex == 0x7f.toByte()) {
//                            _flightEventData.update { currentState ->
//                                currentState.copy(
//                                launchDate = byteArrayToDate(locatorMessage, 4),
//                                maxAltitude = byteArrayToFloat(locatorMessage, 12),
//                                maxAltitudeSampleIndex = byteArrayToInt(locatorMessage, 16),
//                                launchDetectAltitude = byteArrayToFloat(locatorMessage, 20),
//                                launchDetectSampleIndex = byteArrayToInt(locatorMessage, 24),
//                                burnoutAltitude = byteArrayToFloat(locatorMessage, 28),
//                                burnoutSampleIndex = byteArrayToInt(locatorMessage, 32),
//                                noseOverAltitude = byteArrayToFloat(locatorMessage, 36),
//                                noseOverSampleIndex = byteArrayToInt(locatorMessage, 40),
//                                droguePrimaryDeployAltitude = byteArrayToFloat(locatorMessage, 44),
//                                droguePrimaryDeploySampleIndex = byteArrayToInt(locatorMessage, 48),
//                                drogueBackupDeployAltitude = byteArrayToFloat(locatorMessage, 52),
//                                drogueBackupDeploySampleIndex = byteArrayToInt(locatorMessage, 56),
//                                drogueVelocityThresholdAltitude = byteArrayToFloat(locatorMessage, 60),
//                                drogueVelocityThresholdSampleIndex = byteArrayToInt(locatorMessage, 64),
//                                mainPrimaryDeployAltitude = byteArrayToFloat(locatorMessage, 68),
//                                mainPrimaryDeploySampleIndex = byteArrayToInt(locatorMessage, 72),
//                                mainBackupDeployAltitude = byteArrayToFloat(locatorMessage, 76),
//                                mainBackupDeploySampleIndex = byteArrayToInt(locatorMessage, 80),
//                                mainVelocityThresholdAltitude = byteArrayToFloat(locatorMessage, 84),
//                                mainVelocityThresholdSampleIndex = byteArrayToInt(locatorMessage, 88),
//                                landingAltitude = byteArrayToFloat(locatorMessage, 92),
//                                landingSampleIndex = byteArrayToInt(locatorMessage, 96),
//                                channel1Mode = DeployMode.fromUByte(locatorMessage[100].and(0x03).toUByte()),
//                                channel2Mode = DeployMode.fromUByte((locatorMessage[100].and(0x0C).toInt() ushr 2).toUByte()),
//                                channel1Fired = locatorMessage[100].and(0x10).toInt() != 0,
//                                channel2Fired = locatorMessage[100].and(0x20).toInt() != 0,
//                                channel1PreFireContinuity = locatorMessage[100].and(0x40).toInt() != 0,
//                                channel2PreFireContinuity = locatorMessage[100].and(0x80.toByte()).toInt() != 0,
//                                channel1PostFireContinuity = locatorMessage[101].and(0x01).toInt() != 0,
//                                channel2PostFireContinuity = locatorMessage[101].and(0x02).toInt() != 0,
//                                gRangeScale = byteArrayToFloat(locatorMessage, 104),
//                                )
//                            }
//                        }
//                        else {
//                            if (packetIndex == 0.toByte()) {
//                                clearFlightProfileData()                            }
//                            if (locatorMessage[locatorMessageHeader.size] < 0)
//                                packetsRemaining = false
//                            var messagePosition = locatorMessageHeader.size + 1
//                            while (messagePosition < locatorMessage.size && !(locatorMessage[messagePosition] == 0xff.toByte() && locatorMessage[messagePosition + 1] == 0xff.toByte())) {
//                                _flightProfileAglData.value += byteArrayToUShort(locatorMessage, messagePosition)
//                                messagePosition += 2
//                                if (sampleIndex <= _flightEventData.value.droguePrimaryDeploySampleIndex) {
//                                    _flightProfileAccelerometerData.value +=
//                                        Accelerometer(
//                                            byteArrayToShort(locatorMessage, messagePosition),
//                                            byteArrayToShort(locatorMessage, messagePosition + 2),
//                                            byteArrayToShort(locatorMessage, messagePosition + 4)
//                                        )
//                                    messagePosition += 6
//                                }
//                                archiveSample++
//                                sampleIndex++
//                            }
//                        }
//                        _flightProfileDataMessageState.value = LocatorMessageState.AckUpdated
//                    }
//                    locatorMessageHeader.contentEquals(BluetoothService.deploymentTestMessageHeader) -> {
//                        val deploymentTestCountdown = locatorMessage[3].toInt()
//                        if (_deploymentTestActive.value)
//                            _deploymentTestCountdown.value = deploymentTestCountdown
//                    }
//                }
*/

    private fun gpsCoord(byteArray: ByteArray, offset: Int): Double {
        require(offset >= 0 && offset + 8 <= byteArray.size) { "Invalid offset or length" }
        val doubleByteArray = byteArray.copyOfRange(offset, offset + 8).reversedArray()
        val doubleValue = ByteBuffer.wrap(doubleByteArray).getDouble()
        return doubleValue.toInt() / 100 + (doubleValue - (doubleValue.toInt() / 100 * 100)) / 60
    }

    private fun byteArrayToFloat(byteArray: ByteArray, offset: Int): Float {
        require(offset >= 0 && offset + 4 <= byteArray.size) { "Invalid offset or length" }
        val floatByteArray = byteArray.copyOfRange(offset, offset + 4).reversedArray()
        return ByteBuffer.wrap(floatByteArray).getFloat()
    }
    private fun byteArrayToUShort(byteArray: ByteArray, offset: Int): UShort {
        require(offset >= 0 && offset + 2 <= byteArray.size) { "Invalid offset or length" }
        return (byteArray[offset].toUByte() + byteArray[offset + 1].toUByte() * 0x100u).toUShort()
    }
    private fun byteArrayToShort(byteArray: ByteArray, offset: Int): Short {
        require(offset >= 0 && offset + 2 <= byteArray.size) { "Invalid offset or length" }
        return (byteArray[offset].toUByte() + byteArray[offset + 1].toUByte() * 0x100u).toShort()
    }
    private fun byteArrayToInt(byteArray: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 4 <= byteArray.size) { "Invalid offset or length" }
        return (byteArray[offset].toUByte() + byteArray[offset + 1].toUByte() * 0x100u + byteArray[offset + 2].toUByte() * 0x10000u + byteArray[offset + 3].toUByte() * 0x1000000u).toInt()
    }
    private fun byteArrayToDate(byteArray: ByteArray, offset: Int): ZonedDateTime? {
        require(offset >= 0 && offset + 8 <= byteArray.size) { "Invalid offset or length" }
        val datePartByteArray = byteArray.copyOfRange(offset, offset + 4).reversedArray()
        val timePartByteArray = byteArray.copyOfRange(offset + 4, offset + 8).reversedArray()
        val datePart = ByteBuffer.wrap(datePartByteArray, 0, 4).int
        val timePart = ByteBuffer.wrap(timePartByteArray, 0, 4).int
        return try {
            ZonedDateTime.of(LocalDateTime.of(2000 + (datePart % 100), (datePart / 100) % 100, datePart / 10000,
                timePart / 10000, (timePart / 100) % 100, timePart % 100), ZoneId.of("America/Los_Angeles"))
        } catch (e: Exception) {
            null
        }
    }

    fun updateOrientation(values: FloatArray) {
        val rotationMatrix  = FloatArray(9)
        val landscapeMatrix = FloatArray(9)
        val orientation     = FloatArray(3)

        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)

        // Magnetic → true. Everything downstream of updateOrientation is compared
        // against true-north references (the great-circle bearing from
        // locatorVector, MapLibre's camera bearing), so the conversion belongs
        // here, once, rather than at each consumer.
        val declination = _magneticDeclination.value

        // Azimuth: derive from the un-remapped portrait frame.
        SensorManager.getOrientation(rotationMatrix, orientation)
        val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val az = (azimuthDeg + declination + 360f) % 360f

        // Apply Google‑Maps‑style smoothing for the map bearing.
        val delta   = ((az - _lastHandheldDeviceAzimuth.value + 540f) % 360f) - 180f
        val eased   = easeAngle(delta)
        val smoothed = (_lastHandheldDeviceAzimuth.value + eased + 360f) % 360f
        _lastHandheldDeviceAzimuth.value = smoothed
        _handheldDeviceAzimuth.value = smoothed

        // Pitch for the landscape AR overlay: remap so that the new Y axis aligns
        // with the camera direction (old Z axis).  Negating orientation[1] makes
        // positive pitch mean the camera is pointing upward, matching the sign
        // convention expected by the elevation delta formula in CameraPreviewScreen.
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_X, SensorManager.AXIS_Z,
            landscapeMatrix
        )
        SensorManager.getOrientation(landscapeMatrix, orientation)
        // After the remap the new Y axis is the old Z axis (camera direction), so
        // orientation[0] is the compass bearing the camera is actually pointing —
        // this is the correct azimuth for the landscape AR overlay. Declination
        // applies here for the same reason it applies above: this is the value the
        // AR overlay differences against a true-north bearing.
        _handheldCameraAzimuth.value =
            (Math.toDegrees(orientation[0].toDouble()).toFloat() + declination + 360f) % 360f
        _handheldDevicePitch.value   = Math.toDegrees(-orientation[1].toDouble()).toFloat()
    }

    private fun easeAngle(delta: Float): Float {
        val absDelta = abs(delta)
        val factor = when {
            absDelta < 2f  -> 0.1f
            absDelta < 10f -> 0.2f
            absDelta < 45f -> 0.35f
            else           -> 0.55f
        }
        return delta * factor
    }

    fun locatorVector(latLng1: LatLng, latLng2: LatLng): Vector {
        val earthRadius = 6371000 // in meters

        val lat1Rad = Math.toRadians(latLng1.latitude)
        val lat2Rad = Math.toRadians(latLng2.latitude)
        val dLat = lat2Rad - lat1Rad
        val dLon = Math.toRadians(latLng2.longitude - latLng1.longitude)

        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val distance = (earthRadius * c).toInt()

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val azimuth = ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
        val ordinal = when {
            azimuth.toInt() in (0..22) -> "north"
            azimuth.toInt() in (23..67) -> "northeast"
            azimuth.toInt() in (68..112) -> "east"
            azimuth.toInt() in (113..157) -> "southeast"
            azimuth.toInt() in (158..202) -> "south"
            azimuth.toInt() in (203..247) -> "southwest"
            azimuth.toInt() in (248..292) -> "west"
            azimuth.toInt() in (293..337) -> "northwest"
            azimuth.toInt() in (338..359) -> "north"
            else -> ""
        }
        val elevation = ((Math.toDegrees(atan2(_rocketState.value.altitudeAboveGroundLevel, distance.toFloat()).toDouble()) + 360) % 360).toFloat()
        return Vector(distance, azimuth, ordinal, elevation)
    }

    fun updateArmedState() {
        viewModelScope.launch {
            for (i in 1..50) {
                delay(100)
                if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorMessageState.AckUpdated ||
                    BluetoothManagerRepository.locatorArmedMessageState.value == LocatorMessageState.SendFailure)
                    break
            }
            if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorMessageState.SendRequested ||
                BluetoothManagerRepository.locatorArmedMessageState.value == LocatorMessageState.Sent) {
                BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorMessageState.NotAcknowledged)
            }
            delay(2000)
            BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorMessageState.Idle)
        }
    }

    fun updateReceiverConfigState(stagedReceiverConfig: ReceiverConfig) {
        viewModelScope.launch {
            for (i in 1..50) {
                delay(100)
                // The receiver echoes its channel back via PreLaunchData but never its name,
                // so compare only the channel for acknowledgment.  The name is accepted
                // optimistically once the channel is confirmed.
                if (_remoteReceiverConfig.value.channel == stagedReceiverConfig.channel) {
                    _remoteReceiverConfig.update { it.copy(deviceName = stagedReceiverConfig.deviceName) }
                    _receiverConfigMessageState.value = LocatorMessageState.AckUpdated
                    saveUserPreferences()
                    break
                }
                else if (_receiverConfigMessageState.value == LocatorMessageState.SendFailure)
                    break
            }
            if (_receiverConfigMessageState.value == LocatorMessageState.SendRequested ||
                _receiverConfigMessageState.value == LocatorMessageState.Sent) {
                _receiverConfigMessageState.value = LocatorMessageState.NotAcknowledged
            }
            if (_receiverConfigMessageState.value == LocatorMessageState.AckUpdated)
                _receiverConfigChanged.value = false
            delay(2000)
            _receiverConfigMessageState.value = LocatorMessageState.Idle
        }
    }

    fun updateLocatorConfigState(
        stagedLocatorConfig: LocatorConfig,
        service: BluetoothService? = null,
    ) {
        viewModelScope.launch {
            // Channel to fall back to if the locator never confirms the change.
            // Captured before polling, while remoteLocatorConfig still reflects the
            // last channel PreLaunchData arrived on (i.e. the old channel).
            val oldChannel = _remoteLocatorConfig.value.loraChannel
            val channelChanged = stagedLocatorConfig.loraChannel != oldChannel

            if (waitForLocatorConfig(stagedLocatorConfig)) {
                _locatorConfigMessageState.value = LocatorMessageState.AckUpdated
            } else if (_locatorConfigMessageState.value == LocatorMessageState.SendFailure) {
                // The BLE send itself failed: nothing left the phone, so the receiver
                // never switched and there is nothing to recover.  Leave SendFailure.
            } else if (channelChanged && service != null) {
                // Nothing arrived on the new channel — which is NOT a diagnosis.
                // Two opposite states produce that silence: the locator missed the
                // command and stayed behind while the receiver followed (a real
                // split), or everything moved and the confirmation was merely late.
                // This branch used to assume the first and pull the receiver back,
                // which in the second case MANUFACTURES the split it was written to
                // repair — and strands the rocket, because the locator's move is
                // flash-persistent.  So look before acting (ADR-0011 amendment).
                _locatorConfigMessageState.value =
                    if (resolveChannelMove(stagedLocatorConfig, oldChannel, service))
                        LocatorMessageState.AckUpdated
                    else
                        LocatorMessageState.NotAcknowledged
            } else if (_locatorConfigMessageState.value == LocatorMessageState.SendRequested ||
                _locatorConfigMessageState.value == LocatorMessageState.Sent) {
                _locatorConfigMessageState.value = LocatorMessageState.NotAcknowledged
            }

            if (_locatorConfigMessageState.value == LocatorMessageState.AckUpdated) {
                _locatorConfigChanged.value = false
                // Confirmed, so it is no longer a move "staged but never confirmed"
                // and has no business in the ADR-0029 search candidates.  An
                // unconfirmed one is deliberately kept — that is the channel the
                // locator may have taken alone, and it is the whole reason a search
                // after a failed move looks in the right place.
                if (channelChanged) _pendingChannelMove.value = null
            }
            // Held for the banner, which must outlive the Idle reset below.
            if (channelChanged) _channelMoveResult.value = _locatorConfigMessageState.value
            delay(2000)
            _locatorConfigMessageState.value = LocatorMessageState.Idle
        }
    }

    // Poll for the locator config to be echoed back (via PreLaunchData).
    // Returns true on confirmation, false on timeout or an explicit send failure.
    //
    // The window is RE-BASED by the receiver's transmit receipt rather than merely
    // started at the BLE write.  The receiver cannot forward a command until it sees
    // a PreLaunchData and is 50-700 ms past it, so on a channel dropping broadcasts —
    // the channel that motivates a move in the first place — the old fixed window was
    // spent waiting for the command to be transmitted at all, and expired before the
    // locator had a chance to answer.  The noise that justifies the move was starving
    // its confirmation.  See ADR-0011.
    private suspend fun waitForLocatorConfig(stagedLocatorConfig: LocatorConfig): Boolean {
        val started = System.currentTimeMillis()
        // Absolute ceiling, independent of any re-base.  Belt and braces after the
        // repeated-receipt hang: a wait that can be extended must also be one that
        // cannot be extended forever, whatever future code starts feeding it.
        val hardDeadline = started + 2 * CONFIG_CONFIRM_WINDOW_MS
        var deadline = started + CONFIG_CONFIRM_WINDOW_MS
        while (System.currentTimeMillis() < deadline) {
            delay(100)
            if (_remoteLocatorConfig.value == stagedLocatorConfig)
                return true
            if (_locatorConfigMessageState.value == LocatorMessageState.SendFailure)
                return false
            deadline = ChannelMove.confirmDeadline(
                startedMs = started,
                deadlineMs = deadline,
                receiptMs = channelMoveReceiptMs,
                windowMs = CONFIG_CONFIRM_WINDOW_MS,
                hardDeadlineMs = hardDeadline,
            )
        }
        return false
    }

    /**
     * Work out what actually happened to an unconfirmed channel move, and act only
     * on what the receiver can hear (ADR-0011, "revert on evidence, not on silence").
     * Returns true if the locator ended up on the staged channel.
     *
     * The **sequence** lives in [ChannelMoveRunner] and is pinned by
     * `ChannelMoveRunnerTest`; this supplies the side effects. It was moved out
     * because every defect in that sequence — the lost retry, the refusal read as
     * silence, the single look at silence — was found by hand on a bench, for want of
     * any way to reach it from a test while it sat in here with a service in scope.
     */
    private suspend fun resolveChannelMove(
        stagedLocatorConfig: LocatorConfig,
        oldChannel: Int,
        service: BluetoothService,
    ): Boolean =
        ChannelMoveRunner(
            ops = channelMoveOps(stagedLocatorConfig, service),
            refusedRetryMs = CHANNEL_PROBE_REFUSED_RETRY_MS,
        ).resolve(stagedLocatorConfig.loraChannel, oldChannel)

    /**
     * The live side of a channel-move resolution: searches, BLE writes, and the two
     * waits.  Everything here touches a flow, a service or the clock, which is exactly
     * what the runner is kept free of.
     */
    private fun channelMoveOps(
        stagedLocatorConfig: LocatorConfig,
        service: BluetoothService,
    ) = object : ChannelMoveRunner.Ops {

        // A run already going is somebody else's; its hits are not an answer to our
        // question, and startLocatorSearch would no-op rather than replace it.
        override fun probeInProgress(): Boolean = _locatorSearch.value?.running == true

        /**
         * A **census** over both channels, never a targeted run that stops on the
         * first hit: a locator a few feet from the receiver decodes on channels it is
         * nowhere near and the artifact reads as strong (ADR-0029), so the decision
         * has to compare two dwells rather than trust one.  This probe runs while the
         * user is configuring a locator, which is exactly the range that produces it.
         *
         * Reuses the ordinary search flow, so the run is visible in the search section
         * and cancellable by the same button, and inherits the receiver's own refusals.
         */
        override suspend fun runProbe(newChannel: Int, oldChannel: Int): ChannelMoveRunner.ProbeRun? {
            startLocatorSearch(service, ChannelMove.probeChannels(newChannel, oldChannel))
            val run = withTimeoutOrNull(CHANNEL_PROBE_TIMEOUT_MS) {
                locatorSearch.first { it != null && !it.running }
            } ?: return null
            return ChannelMoveRunner.ProbeRun(
                completed = run.status == LocatorSearchData.Status.Done,
                verdict = ChannelMove.verdict(
                    hits = run.hits,
                    locatorId = channelMoveLocatorId,
                    newChannel = newChannel,
                    oldChannel = oldChannel,
                ),
            )
        }

        override suspend fun pause(ms: Long) = delay(ms)

        override fun nowMs(): Long = System.currentTimeMillis()

        override suspend fun pointReceiverAt(channel: Int) {
            service.changeReceiverConfig(
                ReceiverConfig(channel = channel, deviceName = _remoteReceiverConfig.value.deviceName)
            )
        }

        // Wait for the link to come back on the old channel — on EVIDENCE that a frame
        // was admitted after we asked, not merely on two readings that say `oldChannel`.
        // Both of those readings are updated only by a relayed PreLaunchData, so after a
        // move whose confirmation never arrived they were BOTH still reading the old
        // channel: a test on the two alone passed on its first 100 ms poll having
        // verified nothing, and the retry then went out to a channel with nothing on it.
        override suspend fun awaitRelink(oldChannel: Int, sinceMs: Long): Boolean {
            for (i in 1..50) {
                delay(100)
                if (ChannelMove.relinked(
                        receiverChannel = _remoteReceiverConfig.value.channel,
                        locatorChannel = _remoteLocatorConfig.value.loraChannel,
                        oldChannel = oldChannel,
                        lastFrameMs = lastConnectedFrameMs,
                        askedAtMs = sinceMs,
                    )
                ) return true
            }
            return false
        }

        override suspend fun resendLocatorConfig(): Boolean {
            if (service.changeLocatorConfig(stagedLocatorConfig) != true) return false
            _locatorConfigMessageState.value = LocatorMessageState.Sent
            // The retry is a fresh transmission, so it earns a fresh receipt.
            channelMoveReceiptMs = 0L
            return true
        }

        override suspend fun awaitConfirmation(): Boolean =
            waitForLocatorConfig(stagedLocatorConfig)

        override fun onVerdict(verdict: ChannelMove.Verdict) {
            _channelMoveOutcome.value = verdict
        }
    }

    /**
     * Fetch the flight-record list, re-requesting with exponential backoff until
     * the locator answers.
     *
     * A `FlightMetadataRequest` is a single unacknowledged LoRa frame, and the
     * app used to send exactly one.  Losing it left the screen on "Fetching
     * flight data…" forever with nothing to retry — most reliably when the
     * request went out immediately behind a `DisarmRequest`, while the locator
     * was still transitioning back to its PreLaunchData cycle.
     *
     * Call from a composition-scoped coroutine: cancellation (the user leaving
     * the screen) is what ends the loop.  It also returns on its own once the
     * list arrives, or once a record is opened.
     */
    suspend fun fetchFlightProfileMetadata(service: BluetoothService) {
        var backoffMs = METADATA_RETRY_INITIAL_MS
        var attempt = 0

        // Exits by cancellation, or by an explicit return below.
        while (true) {
            // Opening a record takes over the link.  A FlightMetadataRequest now
            // would put the locator back in MetadataRequested and abort the very
            // transfer the user just started, so stop retrying.
            if (_flightProfileDataDisplayState.value) return

            attempt++
            _flightProfileMetadataAttempt.value = attempt
            _flightProfileMetadataMessageState.value = LocatorMessageState.SendRequested
            val sent = service.requestFlightProfileMetadata()
            // Don't clobber a response that landed while we were sending.
            _flightProfileMetadataMessageState.update { current ->
                when {
                    !sent -> LocatorMessageState.SendFailure
                    current == LocatorMessageState.SendRequested -> LocatorMessageState.Sent
                    else -> current
                }
            }

            val answered = withTimeoutOrNull(backoffMs) {
                flightProfileMetadataMessageState.first { it == LocatorMessageState.AckUpdated }
            } != null
            if (answered) {
                SpLog.d(TAG, "Flight metadata received on attempt $attempt")
                return
            }

            SpLog.d(TAG, "Flight metadata attempt $attempt unanswered after ${backoffMs}ms — retrying")
            _flightProfileMetadataMessageState.update { current ->
                if (current == LocatorMessageState.Sent) LocatorMessageState.NotAcknowledged
                else current
            }
            // Capped so a long wait still refreshes the locator's 30 s
            // metadata-idle timeout rather than letting it drop to Disarmed.
            backoffMs = (backoffMs * 2).coerceAtMost(METADATA_RETRY_MAX_MS)
        }
    }

    fun getFlightProfileData(service: BluetoothService) {
        _flightProfileDataDisplayState.value = true
        _flightProfileDataMessageState.value = LocatorMessageState.SendRequested
        SpLog.d(TAG, "Requesting flight data for archive position ${_flightProfileArchivePosition.value}")
        if (service.requestFlightProfileData(_flightProfileArchivePosition.value)) {
            updateFlightProfileDataMessageState(LocatorMessageState.Sent)
        } else {
            updateFlightProfileDataMessageState(LocatorMessageState.SendFailure)
        }
    }

    private fun parseIncoming(frame: ByteArray): ParsedMessage? {
        if (frame.size < 6) return null
        val msgHeader = parseHeader(frame) ?: return null
        return when (msgHeader.msgType) {
            MsgType.PreLaunchData    -> ParsedMessage.Prelaunch(parsePrelaunch(frame))
            MsgType.TelemetryData    -> ParsedMessage.Telemetry(parseTelemetry(frame))
            MsgType.DeploymentTest   -> ParsedMessage.DeploymentTest(parseDeploymentTest(frame))
            MsgType.ReceiverInfo     -> ParsedMessage.ReceiverInfo(parseReceiverInfo(frame))
            MsgType.VersionInfo      -> ParsedMessage.VersionInfo(parseVersionInfo(frame))
            MsgType.FlightMetadata   -> ParsedMessage.FlightMetadata(frame)
            MsgType.FlightEvents     ->
                FlightEventsData.parse(frame)?.let { ParsedMessage.FlightEvents(it) }
            MsgType.FlightData       -> ParsedMessage.FlightData(frame)
            MsgType.FlightDataParity -> ParsedMessage.FlightDataParity(frame)
            MsgType.ChannelSurvey    -> ParsedMessage.ChannelSurvey(parseChannelSurvey(frame))
            MsgType.LocatorSearchResult -> ParsedMessage.LocatorSearch(parseLocatorSearch(frame))
            else                     -> null
        }
    }

    private fun parseHeader(bytes: ByteArray): PacketHeader? {
        if (bytes.size < 6) return null

        val systemId = bytes[0]
        val msgType = MsgType.fromUByte(bytes[1].toUByte()) ?: return null
        val msgCount = ((bytes[3].toUInt() shl 8) or bytes[2].toUInt()).toUShort()
        val crc = ((bytes[5].toUInt() shl 8) or bytes[4].toUInt()).toUShort()

        return PacketHeader(systemId.toUByte(), msgType, msgCount, crc)
    }

    private fun parsePrelaunch(frame: ByteArray): PrelaunchParsed {
        var o = 6 // start after PacketHeader

        val latitude = Bytes.f64(frame, o); o += 8
        val longitude = Bytes.f64(frame, o); o += 8
        val rawLatitude = Bytes.f64(frame, o); o += 8
        val rawLongitude = Bytes.f64(frame, o); o += 8
        val satellites = Bytes.u8(frame[o]); o += 1
        val hacc = Bytes.f32(frame, o); o += 4

        val imuStatus = SensorHealth.fromUByte(frame[o].toUByte()); o += 1
        val baroStatus = SensorHealth.fromUByte(frame[o].toUByte()); o += 1
        val gpsStatus = SensorHealth.fromUByte(frame[o].toUByte()); o += 1

        val deployStatus = Bytes.u8(frame[o]); o += 1
        val agl = Bytes.f32(frame, o); o += 4

        val accel = parseVec3f(frame, o); o += 12
        val gyro = parseVec3f(frame, o); o += 12

        val deployCh1 = DeployMode.fromUByte(frame[o].toUByte()); o += 1
        val deployCh2 = DeployMode.fromUByte(frame[o].toUByte()); o += 1
        val deployCh3 = DeployMode.fromUByte(frame[o].toUByte()); o += 1
        val deployCh4 = DeployMode.fromUByte(frame[o].toUByte()); o += 1

        val droguePrimary = Bytes.u8(frame[o]); o += 1
        val drogueBackup = Bytes.u8(frame[o]); o += 1

        val mainPrimary = Bytes.u16(frame, o); o += 2
        val mainBackup = Bytes.u16(frame, o); o += 2

        val nameBytes = frame.copyOfRange(o, o + Protocol.DEVICE_NAME_LENGTH)
        val deviceName = nameBytes.takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
        o += Protocol.DEVICE_NAME_LENGTH

        val locatorBatteryMv = Bytes.u16(frame, o); o += 2
        val noseAxis = NoseAxis.fromUByte(frame[o].toUByte()); o += 1  // mounting config (#36)
        val armed = frame[o] != 0.toByte(); o += 1       // stated arm state (ADR-0021, #35)
        val padAlertRaw = frame[o].toUByte()
        val padAlert = PadAlertState.fromUByte(padAlertRaw)
        val padAlertSnoozeMinutes = PadAlertState.snoozeMinutesFromUByte(padAlertRaw); o += 1
        val locatorId = Bytes.u32(frame, o); o += 4      // last base fields, before receiver-appended metadata
        val authTag = Bytes.u32(frame, o); o += 4
        val channel = Bytes.u8(frame[o]); o += 1
        val receiverBatteryMv = Bytes.u16(frame, o); o += 2
        val receiverNameBytes = frame.copyOfRange(o, o + Protocol.DEVICE_NAME_LENGTH)
        val receiverName = receiverNameBytes.takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
        o += Protocol.DEVICE_NAME_LENGTH
        val rssi = Bytes.i16(frame, o); o += 2
        val snr = Bytes.i8(frame[o]); o += 1
        val noiseFloor = Bytes.i16(frame, o); o += 2
        val badFrames = Bytes.u8(frame[o])

        return PrelaunchParsed(
            latitude, longitude, rawLatitude, rawLongitude, satellites, hacc,
            imuStatus, baroStatus, gpsStatus,
            deployStatus, agl,
            accel, gyro,
            deployCh1, deployCh2, deployCh3, deployCh4,
            droguePrimary, drogueBackup,
            mainPrimary, mainBackup,
            deviceName, locatorBatteryMv, noseAxis, armed, padAlert, padAlertSnoozeMinutes,
            locatorId, authTag,
            channel, receiverBatteryMv,
            receiverName, rssi, snr, noiseFloor, badFrames
        )
    }

    fun parseTelemetry(frame: ByteArray): TelemetryParsed {
        var o = 6

        val latitude = Bytes.f64(frame, o); o += 8
        val longitude = Bytes.f64(frame, o); o += 8
        val satellites = Bytes.u8(frame[o]); o += 1
        val hacc = Bytes.f32(frame, o); o += 4

        val imuStatus = SensorHealth.fromUByte(frame[o].toUByte()); o += 1
        val baroStatus = SensorHealth.fromUByte(frame[o].toUByte()); o += 1
        val gpsStatus = SensorHealth.fromUByte(frame[o].toUByte()); o += 1

        val deploymentCh1Stats = Bytes.u8(frame[o]); o += 1
        val deploymentCh2Stats = Bytes.u8(frame[o]); o += 1
        val deploymentCh3Stats = Bytes.u8(frame[o]); o += 1
        val deploymentCh4Stats = Bytes.u8(frame[o]); o += 1
        val physicalDeploymentStats = Bytes.u8(frame[o]); o += 1
        val agl = Bytes.f32(frame, o); o += 4

        val velNed = parseVec3f(frame, o); o += 12
        val qW = Bytes.f32(frame, o); o += 4
        val qX = Bytes.f32(frame, o); o += 4
        val qY = Bytes.f32(frame, o); o += 4
        val qZ = Bytes.f32(frame, o); o += 4
        val attitude = Quaternionf(qW, qX, qY, qZ)

        val flightState = FlightStates.fromUByte(frame[o].toUByte()); o += 1
        val armed = frame[o] != 0.toByte(); o += 1      // stated arm state (ADR-0021, #35)
        val locatorId = Bytes.u32(frame, o); o += 4     // last base fields, before receiver-appended metadata
        val authTag = Bytes.u32(frame, o); o += 4
        val rssi = Bytes.i16(frame, o); o += 2
        val snr = Bytes.i8(frame[o]); o += 1
        val noiseFloor = Bytes.i16(frame, o); o += 2
        val badFrames = Bytes.u8(frame[o])

        return TelemetryParsed(
            latitude, longitude, satellites, hacc,
            imuStatus, baroStatus, gpsStatus,
            deploymentCh1Stats, deploymentCh2Stats,
            deploymentCh3Stats, deploymentCh4Stats,
            physicalDeploymentStats, agl,
            velNed, attitude,
            flightState, armed, locatorId, authTag, rssi, snr, noiseFloor, badFrames
        )
    }

    /** ChannelSurveyResponse: status (1) + channel_count (1) + home_channel (1) + level[64]. */
    fun parseChannelSurvey(frame: ByteArray): ChannelSurveyParsed {
        var o = 6
        val status = ChannelSurveyData.Status.fromByte(Bytes.u8(frame[o])); o += 1
        val count = Bytes.u8(frame[o]); o += 1
        val home = Bytes.u8(frame[o]); o += 1
        // Trust the frame's own count, but never past the buffer: a short or
        // corrupt frame must not throw inside the packet collector.
        val available = ((frame.size - o).coerceAtLeast(0)).coerceAtMost(Protocol.SURVEY_CHANNEL_COUNT)
        val levels = (0 until count.coerceAtMost(available)).map { Bytes.i8(frame[o + it]) }
        o += Protocol.SURVEY_CHANNEL_COUNT
        // Confirmed list, bounded against the frame like the levels above.
        var confirmed = emptyList<Int>()
        var confirmedFrames = emptyList<Int>()
        var confirmedLocatorIds = emptyList<Long>()
        if (o < frame.size) {
            val confirmedCount = Bytes.u8(frame[o]); o += 1
            val room = ((frame.size - o).coerceAtLeast(0)).coerceAtMost(Protocol.SURVEY_CONFIRM_COUNT)
            val n = confirmedCount.coerceAtMost(room)
            confirmed = (0 until n).map { Bytes.u8(frame[o + it]) }
            o += Protocol.SURVEY_CONFIRM_COUNT
            val frameRoom = ((frame.size - o).coerceAtLeast(0)).coerceAtMost(Protocol.SURVEY_CONFIRM_COUNT)
            confirmedFrames = (0 until n.coerceAtMost(frameRoom)).map { Bytes.u8(frame[o + it]) }
            o += Protocol.SURVEY_CONFIRM_COUNT
            // Identity per confirmed channel. Bounded like everything above it: a
            // receiver running firmware from before this field simply ends the frame
            // here, and the ids come back empty rather than throwing.
            val idRoom = ((frame.size - o).coerceAtLeast(0)) / 4
            confirmedLocatorIds = (0 until n.coerceAtMost(idRoom)).map { Bytes.u32(frame, o + it * 4) }
        }
        return ChannelSurveyParsed(status, home, levels, confirmed, confirmedFrames, confirmedLocatorIds)
    }

    fun parseLocatorSearch(frame: ByteArray): LocatorSearchParsed {
        var o = 6
        val status = LocatorSearchData.Status.fromByte(Bytes.u8(frame[o])); o += 1
        val channel = Bytes.u8(frame[o]); o += 1
        val searched = Bytes.u8(frame[o]); o += 1
        val total = Bytes.u8(frame[o]); o += 1
        val found = Bytes.u8(frame[o]) != 0; o += 1
        val armed = Bytes.u8(frame[o]) != 0; o += 1
        val rssi = Bytes.i16(frame, o); o += 2
        val snr = Bytes.i8(frame[o]); o += 1
        val locatorId = Bytes.u32(frame, o); o += 4
        val nameBytes = frame.copyOfRange(o, o + Protocol.DEVICE_NAME_LENGTH)
        val deviceName = nameBytes.takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
        return LocatorSearchParsed(
            status, channel, searched, total, found, armed, rssi, snr, locatorId, deviceName,
        )
    }

    fun parseDeploymentTest (frame: ByteArray): DeploymentTestParsed {
        var o = 6

        val count = Bytes.u8(frame[o]); o += 1

        return DeploymentTestParsed(count)
    }

    private fun parseReceiverInfo(frame: ByteArray): ReceiverInfoParsed {
        var o = 6 // start after PacketHeader

        val channel = Bytes.u8(frame[o]); o += 1

        val nameBytes = frame.copyOfRange(o, o + Protocol.DEVICE_NAME_LENGTH)
        val deviceName = nameBytes.takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
        o += Protocol.DEVICE_NAME_LENGTH

        val noiseFloor = Bytes.i16(frame, o); o += 2
        val badFrames = Bytes.u8(frame[o])

        return ReceiverInfoParsed(channel, deviceName, noiseFloor, badFrames)
    }

    private fun parseVersionInfo(frame: ByteArray): VersionInfoParsed {
        var o = 6 // start after PacketHeader

        val locatorBytes = frame.copyOfRange(o, o + 64)
        val locatorVersion = locatorBytes.takeWhile { it != 0.toByte() }
            .toByteArray().toString(Charsets.UTF_8)
        o += 64

        val receiverBytes = frame.copyOfRange(o, o + 64)
        val receiverVersion = receiverBytes.takeWhile { it != 0.toByte() }
            .toByteArray().toString(Charsets.UTF_8)

        return VersionInfoParsed(locatorVersion, receiverVersion)
    }

    object Bytes {
        fun u8(b: Byte) = b.toInt() and 0xFF

        // Byte is already signed in Kotlin; named for symmetry with the u8/i16 pair
        // so the wire type is visible at the call site.
        fun i8(b: Byte) = b.toInt()

        fun u16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        fun i16(bytes: ByteArray, offset: Int): Int =
            (((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                    (bytes[offset].toInt() and 0xFF)).toShort().toInt()

        fun u32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFF) or
                    ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                    ((bytes[offset + 3].toLong() and 0xFF) shl 24)

        fun f32(bytes: ByteArray, offset: Int): Float =
            java.nio.ByteBuffer.wrap(bytes, offset, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .float

        fun f64(bytes: ByteArray, offset: Int): Double =
            java.nio.ByteBuffer.wrap(bytes, offset, 8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .double
    }

    fun parseVec3f(bytes: ByteArray, offset: Int): Vec3f {
        val x = Bytes.f32(bytes, offset)
        val y = Bytes.f32(bytes, offset + 4)
        val z = Bytes.f32(bytes, offset + 8)

        return Vec3f(x, y, z)
    }

}

/**
 * True when an incoming fix repeats the one [last] already holds, and so adds
 * nothing to the recorded path.
 *
 * In flight the locator transmits at ~5 Hz while its position/altitude payload
 * refreshes at ~1 Hz, so roughly five consecutive frames carry one fix
 * bit-for-bit.  Recording all five stored a path five times larger than the
 * information in it, rewrote the whole CSV five times a second, and made the map
 * rebuild the curtain from five times the points.
 *
 * Dropping the repeats loses nothing.  A repeated vertex adds no shape to a
 * polyline, and the point that is kept carries the timestamp of when the fix was
 * *first* seen — the moment the rocket was actually there.  It makes the curtain
 * more faithful rather than less: an interval now interpolates smoothly across
 * the second instead of drawing flat and then jumping at its end.
 *
 * Exact equality is the right test here.  These are copies of a single payload,
 * not independent measurements that happen to agree, so there is no sensor noise
 * for a tolerance to absorb — and a tolerance would silently discard real slow
 * movement, like a rocket drifting under canopy.
 */
/**
 * Converts downloaded archive samples into map path points.
 *
 * Three differences from the live path are worth knowing before reading one.
 *
 * 1. **This is the fused track, not raw GPS.**  The locator fills a sample's
 *    position from `nav_solution.pos` — the EKF's solution, which is
 *    dead-reckoned through powered flight (steam-pigeon-locator#27).  The live
 *    path uses the raw GPS fix.  ADR-0005 retired the EKF from the authoritative
 *    path and ADR-0013 keeps it observational, so this track is an estimate and
 *    is least validated over boost, which is the part most worth looking at.
 * 2. **Timestamps are real flight time.**  The archive clock is GPS-disciplined
 *    (ADR-0007) and counts from the start of the record, so the one-second
 *    markers on an archived path mean what they say — unlike the live path,
 *    whose points are stamped on arrival at the phone.
 * 3. **Altitude is raw baro AGL**, the signal deployment decisions were made on
 *    (ADR-0003), matching the flight-profile chart.
 *
 * Samples without a usable position are dropped rather than plotted: the record
 * starts before GPS necessarily has a fix, and a zero or non-finite coordinate
 * would otherwise run the path to null island and blow up the map's bounds.
 */
internal fun archivedPathPoints(samples: List<FlightSample>): List<PathPoint> =
    samples.mapNotNull { s ->
        val lat = s.latRad * 180.0 / PI
        val lon = s.lonRad * 180.0 / PI
        val usable = lat.isFinite() && lon.isFinite() &&
            abs(lat) <= 90.0 && abs(lon) <= 180.0 &&
            (lat != 0.0 || lon != 0.0)
        if (usable) PathPoint(lat, lon, s.altitudeM, s.timestampMs) else null
    }

internal fun repeatsFix(last: PathPoint?, latitude: Double, longitude: Double, altitudeM: Float): Boolean {
    if (last == null) return false
    return last.latitude == latitude &&
        last.longitude == longitude &&
        last.altitudeM == altitudeM
}

/**
 * On the ground: waiting for a launch, or landed from the last one.
 *
 * NoSignal is deliberately NOT grounded.  [FlightStates.fromUByte] falls back to
 * it for any state byte the app does not recognize, so a state added to the
 * firmware later would decode as NoSignal on an older app — and treating that as
 * "on the ground" would make the next real packet look like a fresh launch and
 * wipe the track of the flight still in the air.
 */
internal fun FlightStates.isGrounded() =
    this == FlightStates.WaitingLaunch || this == FlightStates.Landed

/** In the air: anywhere between launch detection and landing detection. */
internal fun FlightStates.isAirborne() =
    this >= FlightStates.Launched && this <= FlightStates.MainBackupEvent

/**
 * True when the state pair marks the start of a flight, and so the moment to
 * clear the recorded path and its altitude curtain.
 *
 * Any grounded → airborne transition, rather than the WaitingLaunch → Launched
 * edge specifically.  Note what this is *not*: the narrower rule was not defeated
 * by losing a packet or two, because the locator reports Launched for the whole
 * boost — several packets at 5 Hz — and any one of them fires the reset.  It
 * takes losing that entire window, which is a bad-but-possible way for boost to
 * go.  This is hardening for that case; the flight path surviving into the next
 * flight in ordinary use was a symptom of the recognition gate dropping all
 * telemetry (see restoreProvisionalRecognition), not of this rule.
 */
internal fun startsNewFlight(previous: FlightStates, current: FlightStates) =
    previous.isGrounded() && current.isAirborne()

/**
 * True when the flight is over as far as this frame can tell.
 *
 * Two ways to know.  The locator's own Landed state is the authority.  Short of
 * that, telemetry on the way down reaches the point where touchdown is a second
 * or two away — [landingImminent], the same test the landing callout speaks on —
 * and what the locator sends after that is a rocket settling in the grass and a
 * GPS fix wandering around it, not flight.  Drawing those turns the end of the
 * track into a scribble over the one place the user is trying to walk to.
 *
 * Deliberately WITHOUT the blackout arm the callout carries
 * ([landedThroughBlackout]).  That arm exists to announce a landing nothing was
 * heard from, and silence records no path points to begin with — there is nothing
 * for it to stop.  Meanwhile a frame that arrives after a long gap still
 * reporting descent is a rocket that is still descending, and where it says it is
 * belongs on the path.
 */
internal fun landingConcluded(state: FlightStates, aglM: Float, descentRateMs: Float): Boolean =
    state == FlightStates.Landed ||
        (state > FlightStates.Noseover && state.isAirborne() &&
            landingImminent(aglM, descentRateMs))

/**
 * Whether an arriving fix is added to the recorded path.
 *
 * Both flags are the values from *before* this fix was examined, which is what
 * lets the two fixes that end a flight be drawn rather than suppressed by the
 * conclusion they themselves cause:
 *
 * - The fix the app infers the landing from is the lowest, last-known position,
 *   and the most useful point on the whole track.
 * - The first fix carrying Landed status is the locator's own account of where
 *   the rocket is lying.  It outranks anything the app inferred — an inference
 *   made from the last fix before a dropout ends the track short of where the
 *   rocket actually came down — so it draws even though the path was already
 *   frozen.
 *
 * That Landed fix is the end of it: the flight is over, the locator has said so,
 * and the hours of fixes it goes on sending from a field are not flight.  Nothing
 * resumes recording for this flight but the next launch or a manual reset.
 */
internal fun recordsPathPoint(
    state: FlightStates,
    landingConcluded: Boolean,
    landedStatusReceived: Boolean,
): Boolean = when {
    landedStatusReceived -> false
    state == FlightStates.Landed -> true
    // Down as far as the app can tell, but the locator has not said so yet: draw
    // nothing and wait for it to.
    landingConcluded -> false
    else -> state > FlightStates.WaitingLaunch
}

// ── Distance plausibility ────────────────────────────────────────────────────

/**
 * A distance the locator cannot be at, because we are hearing from it.
 *
 * Telemetry reaches the app as LoRa to the receiver and BLE from the receiver to
 * the phone, and BLE puts the receiver in the user's hand — so a packet arriving
 * at all means the locator is within LoRa range of where the distance is measured
 * from.  Practical range is 10–20 km line of sight; 100 km is several times that
 * and still an order of magnitude below the readings this exists to reject, so it
 * cannot fire on a real flight.
 */
private const val maxRadioRangeM = 100_000

/**
 * Whether a distance to the locator is one we could be hearing from at all.  The
 * check every quoted distance passes, spoken or displayed, whatever the locator
 * claims about its own fix — see [maxRadioRangeM].
 */
internal fun distanceWithinRadioRange(distanceM: Int) =
    distanceM in 0..maxRadioRangeM

/**
 * Ceiling on the rocket's **ground speed**, by flight phase.
 *
 * Ground speed, not airspeed, and the distinction is the whole calibration.  The
 * figure being judged comes out of [RocketViewModel.locatorVector], a haversine
 * over latitude and longitude with no altitude term at all — so it moves only
 * with the rocket's ground track.  A Mach 5 boost is Mach 5 *vertically*; it adds
 * almost nothing here, and sizing the bound against it would leave the test
 * limp through the phase it least needs to be.
 *
 * One number for the whole flight had to be the boost number, which left it
 * uselessly loose everywhere else — a rocket sitting in a field was allowed to
 * have moved kilometers between reports.
 *
 * - **Boost and coast (Launched, Burnout):** 400 m/s.  Not the airframe's speed
 *   but the horizontal component of it, which is small on a vertical flight and
 *   still covered here for a badly weathercocked one.
 * - **Descent (Noseover onward):** 200 m/s.  Far past wind drift under canopy;
 *   sized instead for a failed deployment, where the rocket keeps the horizontal
 *   momentum it had at apogee and comes down ballistic.
 * - **On the ground:** walking pace, for a rocket being carried back and for
 *   drift in the reported fix.
 *
 * Every value errs loose on purpose.  Falsely rejecting a distance during a real
 * recovery takes away the number the user is walking toward, which is a far worse
 * failure than showing one bad reading a moment longer.  The boost figure is the
 * least load-bearing of the three: boost lasts seconds, so it opens a couple of
 * km of budget at most, where the descent and ground phases run for minutes.
 */
internal fun maxGroundSpeedMs(state: FlightStates): Double = when {
    state == FlightStates.Launched || state == FlightStates.Burnout -> 400.0
    state.isAirborne() -> 200.0
    state.isGrounded() -> 5.0
    // NoSignal, which is also what any state byte the app does not recognize
    // decodes to. Unknown phase: be permissive rather than blank a distance on
    // the strength of a state we failed to understand.
    else -> 400.0
}

/** Slack for GPS noise, so a stationary rocket is never judged to have jumped. */
private const val positionNoiseMarginM = 100

/**
 * How far the rocket could have got during [elapsedMs] spent in [state].
 *
 * Accumulated a step at a time rather than measured from the last fix, because a
 * gap between fixes spans phases: a rocket that loses its fix under canopy and is
 * next heard from on the ground would be judged against the walking-pace bound
 * for the whole descent, and the 2 km it genuinely flew would read as a jump.
 * Integrating phase by phase charges each stretch at its own rate.
 */
internal fun phaseTravelM(state: FlightStates, elapsedMs: Long): Double =
    maxGroundSpeedMs(state) * elapsedMs.coerceAtLeast(0) / 1000.0

/**
 * Bounds on the total magnetic field strength, in µT, that the Earth alone can
 * account for.
 *
 * The geomagnetic field runs about 22 µT (the South Atlantic minimum) to 67 µT
 * (near the poles) anywhere on the surface, so a reading outside a slightly
 * widened envelope is not the Earth: something local is adding to it, or
 * something is shielding it.
 *
 * The gross pair is where the reading stops being arguable. A fridge magnet at a
 * few centimetres reads in the hundreds or thousands of µT, so that band is not a
 * close call in practice.
 */
internal const val earthFieldMinUt = 20f
internal const val earthFieldMaxUt = 70f
internal const val grossFieldMinUt = 10f
internal const val grossFieldMaxUt = 100f

/** Total field strength from a `TYPE_MAGNETIC_FIELD` sample, in µT. */
internal fun fieldMagnitudeUt(values: FloatArray): Float =
    sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])

/**
 * Judge a field magnitude as one of the `SensorManager.SENSOR_STATUS_*` levels.
 *
 * The one interference test that needs no cooperation from the OEM, which is what
 * makes it worth having: a device can pin its accuracy flags at `HIGH` forever —
 * a Moto G 5S pins both — but it cannot make a magnet disappear from the
 * arithmetic. Feeds the same worst-of verdict as the two vendor flags.
 *
 * Detects **interference**, not **miscalibration**: a stale hard-iron offset can
 * rotate the heading badly while the magnitude stays perfectly plausible. Do not
 * read a `HIGH` here as "the compass is trustworthy" — only as "nothing local is
 * obviously swamping it".
 */
internal fun classifyFieldMagnitude(magnitudeUt: Float): Int = when {
    magnitudeUt in earthFieldMinUt..earthFieldMaxUt ->
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    magnitudeUt < grossFieldMinUt || magnitudeUt > grossFieldMaxUt ->
        SensorManager.SENSOR_STATUS_UNRELIABLE
    // Outside the Earth's envelope but not by much: enough to raise the prompt,
    // not enough to take the AR overlay away.
    else -> SensorManager.SENSOR_STATUS_ACCURACY_LOW
}

/**
 * At least four satellites, which is what a 3D fix takes.  Fewer cannot have
 * produced the position being reported, whether the count means satellites used
 * or satellites in view.
 */
internal fun locatorHasFix(satellites: Int, gpsStatus: SensorHealth) =
    satellites >= 4 && gpsStatus == SensorHealth.Ok

/**
 * Whether a computed distance to the locator is worth showing.
 *
 * Two ways for it to be nonsense.  It can be impossible on its face — a locator
 * 779 km away that we are receiving telemetry from, which is the reading that
 * prompted this.  Or it can be a position the locator had no way to measure: with
 * no fix, the coordinates in the packet are whatever the GPS module last held, or
 * garbage, and the great-circle distance to them is a plausible-looking number
 * with nothing behind it.
 *
 * The second test is a jump, not a value, and that is the point.  A locator that
 * loses its fix on the ground goes on reporting the last position it *did*
 * measure, and that stale distance is the number the user walks toward — blanking
 * it would take away the only thing left to aim at, which is the same reasoning
 * that grays a degraded fix on the map rather than hiding it (ADR-0017).  So a
 * fixless reading is rejected only when it has moved further from the last real
 * fix than the rocket could physically have traveled since.
 *
 * [lastFixDistanceM] is the distance when the locator last had a fix, null before
 * it ever has: with nothing to compare against, only the range ceiling applies.
 * [travelBudgetM] is how far the rocket could have moved since that fix,
 * accumulated phase by phase — see [phaseTravelM].
 */
internal fun distanceIsPlausible(
    distanceM: Int,
    locatorHasFix: Boolean,
    lastFixDistanceM: Int?,
    travelBudgetM: Double,
): Boolean = when {
    !distanceWithinRadioRange(distanceM) -> false
    locatorHasFix -> true
    lastFixDistanceM == null -> true
    else -> abs(distanceM - lastFixDistanceM) <= positionNoiseMarginM + travelBudgetM
}