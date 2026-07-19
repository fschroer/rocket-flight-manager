package com.steampigeon.flightmanager.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.GeomagneticField
import android.hardware.SensorManager
import android.location.Location
import android.util.Log
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.maplibre.android.geometry.LatLng
import com.mutualmobile.composesensors.AccelerometerSensorState
import com.mutualmobile.composesensors.MagneticFieldSensorState
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.KnownLocator
import com.steampigeon.flightmanager.UserPreferences
import com.steampigeon.flightmanager.data.LocatorAuth
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs

private const val TAG = "RocketViewModel"

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
}

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
        stopService()
    }

    companion object {
        const val SAMPLES_PER_SECOND = 20
        const val HDOP_SCALE = 10
        const val ALTIMETER_SCALE = 10
        const val ACCELEROMETER_SCALE = 2048
        private const val BATTERY_SCALE = 8.0 / 4096
        const val FLIGHT_DATA_MESSAGE_SAMPLES = 30
        const val G_FORCE_MS2 = 9.80665

        // FlightMetadataRequest retry backoff.  The first wait must comfortably
        // exceed a normal round trip — the locator holds the response ~50 ms and
        // the receiver may sit on the forward until its next safe window — so a
        // healthy fetch answers on attempt 1 and never retries.  The cap keeps
        // retries under the locator's 30 s metadata-idle timeout, so a link that
        // recovers is picked back up instead of having dropped to Disarmed.
        const val METADATA_RETRY_INITIAL_MS = 3_000L
        const val METADATA_RETRY_MAX_MS     = 12_000L
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

    private val _handheldDeviceAzimuth = MutableStateFlow<Float>(0f)
    val handheldDeviceAzimuth: StateFlow<Float> = _handheldDeviceAzimuth.asStateFlow()
    private val _lastHandheldDeviceAzimuth = MutableStateFlow<Float>(0f)
    val lastHandheldDeviceAzimuth: StateFlow<Float> = _lastHandheldDeviceAzimuth.asStateFlow()
    fun updateLastHandheldDeviceAzimuth(newLastHandheldDeviceAzimuth: Float) {
        _lastHandheldDeviceAzimuth.value = newLastHandheldDeviceAzimuth
    }
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
    fun updateLocatorVector(newLocatorVector: Vector) {
        _locatorDistance.value = newLocatorVector.distance
        _locatorAzimuth.value = newLocatorVector.azimuth
        _locatorOrdinal.value = newLocatorVector.ordinal
        _locatorElevation.value = newLocatorVector.elevation
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
    }

    private val _remoteLocatorConfig = MutableStateFlow<LocatorConfig>(LocatorConfig())
    val remoteLocatorConfig: StateFlow<LocatorConfig> = _remoteLocatorConfig.asStateFlow()

    private val _remoteReceiverConfig = MutableStateFlow<ReceiverConfig>(ReceiverConfig())
    val remoteReceiverConfig: StateFlow<ReceiverConfig> = _remoteReceiverConfig.asStateFlow()

    // -------------------------------------------------------------------------
    // Locator recognition / password gating
    //
    // A locator is "recognised" when the app holds a password key that
    // authenticates its PreLaunchData auth_tag (or the locator is open, key 0).
    // Only recognised locators are processed for control and enabled for sending.
    // -------------------------------------------------------------------------
    private val _knownLocators = MutableStateFlow<Map<Long, KnownLocator>>(emptyMap())

    // The locator_id the app currently recognises (null = none → sending gated off).
    private val _recognizedLocatorId = MutableStateFlow<Long?>(null)
    val recognizedLocatorId: StateFlow<Long?> = _recognizedLocatorId.asStateFlow()
    val locatorRecognized: StateFlow<Boolean> =
        _recognizedLocatorId.map { it != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // An unrecognised locator_id currently heard on the channel. Drives a
    // non-blocking warning banner; cleared on recognition, dismiss, or channel change.
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
    // True once the known-locator store has loaded, so the first PreLaunchData does not
    // passively prompt for an already-known locator before the store is read.
    @Volatile private var knownLocatorsLoaded = false
    private var awaitingChannelRecognition = false
    private var channelChangePreviousChannel = 0

    private val _receiverConfigChanged = MutableStateFlow<Boolean>(false)
    val receiverConfigChanged: StateFlow<Boolean> = _receiverConfigChanged.asStateFlow()

    fun updateReceiverConfigChanged(newReceiverConfigChanged: Boolean) {
        _receiverConfigChanged.value = newReceiverConfigChanged
    }

    /**
     * Evaluate recognition for an incoming PreLaunchData [frame].  Called for every
     * PreLaunchData: recognises known/open locators automatically, raises the
     * channel-change password challenge when a deliberate channel change lands on
     * an unrecognised locator, and flags passive conflicting traffic otherwise.
     */
    private fun evaluateRecognition(frame: ByteArray, locatorId: Long, deviceName: String) {
        lastPrelaunchFrame = frame
        lastPrelaunchLocatorId = locatorId
        lastPrelaunchDeviceName = deviceName
        val knownKey = _knownLocators.value[locatorId]?.passwordKey?.toLong()?.and(0xFFFFFFFFL)
        val recognized =
            (knownKey != null && LocatorAuth.verifyFrame(frame, knownKey)) ||
                    LocatorAuth.verifyFrame(frame, 0L)   // open locator (no password)

        // Keep the challenge frame fresh while a dialog for this locator is open.
        if (_challenge.value?.locatorId == locatorId) challengeFrame = frame

        if (recognized) {
            _recognizedLocatorId.value = locatorId
            _conflictLocatorId.value = null
            awaitingChannelRecognition = false
            if (_challenge.value?.locatorId == locatorId) _challenge.value = null
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
        // Passive: unrecognised traffic on the current channel — warn, and (if we are
        // not already connected) prompt to connect on first contact with this locator.
        _conflictLocatorId.value = locatorId
        if (knownLocatorsLoaded && _recognizedLocatorId.value == null && _challenge.value == null &&
            locatorId !in declinedLocatorIds) {
            challengeFrame = frame
            _challengeError.value = false
            _challenge.value = LocatorChallenge(locatorId, deviceName, null)
        }
    }

    /** Arm the channel-change flow: the next PreLaunchData on the new channel decides
     *  recognition, or raises a password challenge (cancel reverts to [previousChannel]). */
    fun beginChannelChangeRecognition(previousChannel: Int) {
        channelChangePreviousChannel = previousChannel
        awaitingChannelRecognition = true
        _recognizedLocatorId.value = null
        _conflictLocatorId.value = null
        _challenge.value = null
        _challengeError.value = false
    }

    /** Submit a password for the active challenge. Correct → remember + recognise +
     *  close. Wrong → keep the dialog open with an error so the user can retry. */
    suspend fun submitPassword(password: String): Boolean {
        val challenge = _challenge.value ?: return false
        val frame = challengeFrame ?: return false
        val key = LocatorAuth.deriveKey(password)
        val ok = LocatorAuth.verifyFrame(frame, key)
        if (ok) {
            rememberLocator(challenge.locatorId, key, challenge.deviceName)
            _recognizedLocatorId.value = challenge.locatorId
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

    /** Re-raise the password prompt for the currently-warned unrecognised locator.
     *  Lets the user connect after having dismissed the automatic prompt. */
    fun requestConnectToConflict() {
        val id = _conflictLocatorId.value ?: return
        if (_recognizedLocatorId.value == id) return
        declinedLocatorIds.remove(id)
        if (lastPrelaunchLocatorId == id) challengeFrame = lastPrelaunchFrame
        _challengeError.value = false
        _challenge.value = LocatorChallenge(id, lastPrelaunchDeviceName, null)
    }

    /** No PreLaunchData arrived after a channel change — stop waiting (no locator found). */
    fun channelChangeRecognitionTimedOut() {
        awaitingChannelRecognition = false
    }

    fun dismissConflict() {
        _conflictLocatorId.value = null
    }

    private suspend fun rememberLocator(locatorId: Long, passwordKey: Long, label: String) {
        currentContext.userPreferencesDataStore.updateData { prefs ->
            prefs.toBuilder()
                .putKnownLocators(
                    locatorId.toInt(),
                    KnownLocator.newBuilder()
                        .setId(locatorId.toInt())
                        .setPasswordKey(passwordKey.toInt())
                        .setLabel(label)
                        .build()
                )
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

    fun clearFlightProfileData() {
        _flightProfileAglData.value = emptyList()
        _flightProfileAccelerometerData.value = emptyList()
        _flightEvents.value = FlightEventsData()
        FlightDataRepository.cancelTransfer()
    }

    private val _flightPath = MutableStateFlow<List<Triple<Double, Double, Float>>>(emptyList())
    val flightPath: StateFlow<List<Triple<Double, Double, Float>>> = _flightPath.asStateFlow()
    private var _previousFlightState = FlightStates.WaitingLaunch

    private val _isFlightPathRecording = MutableStateFlow(true)
    val isFlightPathRecording: StateFlow<Boolean> = _isFlightPathRecording.asStateFlow()

    private val flightPathFile: File
        get() = File(getApplication<Application>().filesDir, "flight_path.csv")

    init {
        loadFlightPath()
    }

    private fun loadFlightPath() {
        val file = flightPathFile
        if (!file.exists()) return
        try {
            val points = file.readLines().mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size == 3) {
                    Triple(parts[0].toDouble(), parts[1].toDouble(), parts[2].toFloat())
                } else null
            }
            if (points.isNotEmpty()) _flightPath.value = points
        } catch (_: Exception) {}
    }

    private fun saveFlightPath() {
        viewModelScope.launch {
            try {
                flightPathFile.writeText(
                    _flightPath.value.joinToString("\n") { (lat, lng, agl) -> "$lat,$lng,$agl" }
                )
            } catch (_: Exception) {}
        }
    }

    fun startFlightPathRecording() { _isFlightPathRecording.value = true }
    fun stopFlightPathRecording() { _isFlightPathRecording.value = false }
    fun resetFlightPath() {
        _flightPath.value = emptyList()
        viewModelScope.launch {
            try { flightPathFile.delete() } catch (_: Exception) {}
        }
    }

    private val _deploymentTestActive = MutableStateFlow<Boolean>(false)
    val deploymentTestActive: StateFlow<Boolean> = _deploymentTestActive.asStateFlow()

    fun updateDeploymentTestActive(newDeploymentTestActive: Boolean) {
        _deploymentTestActive.value = newDeploymentTestActive
    }

    private val _deploymentTestCountdown = MutableStateFlow<Int>(0)
    val deploymentTestCountdown: StateFlow<Int> = _deploymentTestCountdown.asStateFlow()

    fun updateDeploymentTestCountdown(newDeploymentTestCountdown: Int) {
        _deploymentTestCountdown.value = newDeploymentTestCountdown
    }

    private val _locatorVersion = MutableStateFlow("")
    val locatorVersion: StateFlow<String> = _locatorVersion.asStateFlow()

    private val _receiverVersion = MutableStateFlow("")
    val receiverVersion: StateFlow<String> = _receiverVersion.asStateFlow()

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

    @OptIn(ExperimentalUnsignedTypes::class)
    fun collectInboundMessageData(service: BluetoothService) {
        // Keep the service's send gate in sync with recognition so only an authorized
        // (recognised) locator can be commanded.
        viewModelScope.launch {
            recognizedLocatorId.collect { service.locatorAuthorized = it != null }
        }
        viewModelScope.launch {
            service.packets.collect { locatorMessage ->
//                Log.d("Collector", "Received packet size=${locatorMessage.size} bytes")
                val currentTime = System.currentTimeMillis()
                try {
                    when (val parsed = parseIncoming(locatorMessage)) {
                        is ParsedMessage.Prelaunch -> {
                            // Gate BEFORE display: identify + authenticate the sender first,
                            // and surface its telemetry/config only if THIS locator is
                            // recognised. Dismissing the password prompt leaves the sender
                            // unrecognised, so its data is never shown (no bypass).
                            evaluateRecognition(locatorMessage, parsed.msg.locatorId, parsed.msg.deviceName)
                            if (_recognizedLocatorId.value == parsed.msg.locatorId) {
                            _rocketState.update { currentState ->
                                currentState.copy(
                                    lastPreLaunchMessageTime = currentTime,
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
                                )
                            }
                            _remoteLocatorConfig.update { currentState ->
                                currentState.copy(
                                    deploymentChannel1Mode = parsed.msg.deployCh1Mode,
                                    deploymentChannel2Mode = parsed.msg.deployCh2Mode,
                                    deploymentChannel3Mode = parsed.msg.deployCh3Mode,
                                    deploymentChannel4Mode = parsed.msg.deployCh4Mode,
                                    launchDetectAltitude = 30, // To do: remove from UI
                                    droguePrimaryDeployDelay = parsed.msg.droguePrimaryDelay,
                                    drogueBackupDeployDelay = parsed.msg.drogueBackupDelay,
                                    mainPrimaryDeployAltitude = parsed.msg.mainPrimaryAltitude,
                                    mainBackupDeployAltitude = parsed.msg.mainBackupAltitude,
                                    deploySignalDuration = 10, // To do: remove from UI
                                    // A received PreLaunchData proves the locator and
                                    // receiver share a channel, and the receiver appends
                                    // that channel as receiverChannel.  Use it as the
                                    // locator's current LoRa channel so Locator Settings
                                    // shows the true value and channel changes can be
                                    // confirmed by whole-object equality below.
                                    loraChannel = parsed.msg.receiverChannel,
                                    deviceName = parsed.msg.deviceName,
                                )
                            }
                            } // end recognised-locator gate
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
                            // Telemetry carries no locator id (kept minimal for range), so it
                            // is processed only while connected to a recognised locator.
                            if (_recognizedLocatorId.value != null) {
                            _rocketState.update { currentState ->
                                currentState.copy(
                                    lastPreLaunchMessageTime = currentTime,
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
                                )
                            }
                            // Reset path on new launch; accumulate during flight
                            val newFlightState = parsed.msg.flightState
                            if (_previousFlightState == FlightStates.WaitingLaunch &&
                                newFlightState == FlightStates.Launched) {
                                _flightPath.value = emptyList()
                                saveFlightPath()
                            }
                            if (_isFlightPathRecording.value &&
                                newFlightState > FlightStates.WaitingLaunch &&
                                (parsed.msg.latitude != 0.0 || parsed.msg.longitude != 0.0)) {
                                _flightPath.value = _flightPath.value +
                                    Triple(parsed.msg.latitude, parsed.msg.longitude, parsed.msg.agl)
                                saveFlightPath()
                            }
                            _previousFlightState = newFlightState
                            } // end recognised-locator gate
                        }
                        is ParsedMessage.DeploymentTest -> {
                            val deploymentTestCountdown = parsed.msg.count
                            if (_deploymentTestActive.value)
                                _deploymentTestCountdown.value = deploymentTestCountdown
                        }
                        is ParsedMessage.ReceiverInfo -> {
                            _remoteReceiverConfig.update { currentState ->
                                currentState.copy(
                                    channel    = parsed.msg.channel,
                                    deviceName = if (parsed.msg.deviceName.isNotEmpty())
                                        parsed.msg.deviceName
                                    else
                                        currentState.deviceName,
                                )
                            }
                        }
                        is ParsedMessage.VersionInfo -> {
                            _locatorVersion.value = parsed.msg.locatorVersion
                            _receiverVersion.value = parsed.msg.receiverVersion
                        }
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

        // Re-request version info until it is received.  Only sends when the locator
        // is actively sending PreLaunchData (i.e. the LoRa link is up), so the
        // VersionRequest can be timed around prelaunch messages by the receiver.
        viewModelScope.launch {
            while (_locatorVersion.value.isEmpty()) {
                delay(1_000L)
                val age = System.currentTimeMillis() - _rocketState.value.lastPreLaunchMessageTime
                if (age < 5_000L) {
                    service.requestVersionInfo()
                    delay(5_000L)
                }
            }
        }
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

        // Azimuth: derive from the un-remapped portrait frame.
        SensorManager.getOrientation(rotationMatrix, orientation)
        val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val az = (azimuthDeg + 360f) % 360f

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
        // this is the correct azimuth for the landscape AR overlay.
        _handheldCameraAzimuth.value = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
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

    fun handheldDeviceOrientation(accelerometerState: AccelerometerSensorState, magneticFieldState: MagneticFieldSensorState, location: Location?, landscapeOrientation: Boolean) {
        val lastAccelerometer = FloatArray(3)
        lastAccelerometer[0] = accelerometerState.xForce
        lastAccelerometer[1] = accelerometerState.yForce
        lastAccelerometer[2] = accelerometerState.zForce
        val lastMagnetometer = FloatArray(3)
        lastMagnetometer[0] = magneticFieldState.xStrength
        lastMagnetometer[1] = magneticFieldState.yStrength
        lastMagnetometer[2] = magneticFieldState.zStrength
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrix(rotationMatrix, null, lastAccelerometer, lastMagnetometer)
        val rotationMatrixLandscape = FloatArray(9)
        if (landscapeOrientation) {
            SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, rotationMatrixLandscape)
            SensorManager.getOrientation(rotationMatrixLandscape, orientation)
        }
        else
            SensorManager.getOrientation(rotationMatrix, orientation)
        //_lastHandheldDeviceAzimuth.value = _handheldDeviceAzimuth.value
        location?.let {
            val geoField = GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis())
            val declination = geoField.declination  // in degrees
            _handheldDeviceAzimuth.value = ((Math.toDegrees(orientation[0].toDouble()) + declination + 360) % 360).toFloat()
        } ?: run {
            _handheldDeviceAzimuth.value = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat()
        }
        _handheldDevicePitch.value = ((Math.toDegrees(-orientation[1].toDouble()) + 360) % 360).toFloat()
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
                // so compare only the channel for acknowledgement.  The name is accepted
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
                // The locator never appeared on the new channel, so it likely missed
                // the LoRa command and is still on the old channel — but the receiver
                // already followed the command onto the new channel, so the link is
                // split.  Pull the receiver back to the old channel, wait for the link
                // to resume, then retry the locator change once.
                if (recoverLocatorChannel(stagedLocatorConfig, oldChannel, service))
                    _locatorConfigMessageState.value = LocatorMessageState.AckUpdated
                else
                    _locatorConfigMessageState.value = LocatorMessageState.NotAcknowledged
            } else if (_locatorConfigMessageState.value == LocatorMessageState.SendRequested ||
                _locatorConfigMessageState.value == LocatorMessageState.Sent) {
                _locatorConfigMessageState.value = LocatorMessageState.NotAcknowledged
            }

            if (_locatorConfigMessageState.value == LocatorMessageState.AckUpdated)
                _locatorConfigChanged.value = false
            delay(2000)
            _locatorConfigMessageState.value = LocatorMessageState.Idle
        }
    }

    // Poll ~5 s for the locator config to be echoed back (via PreLaunchData).
    // Returns true on confirmation, false on timeout or an explicit send failure.
    private suspend fun waitForLocatorConfig(stagedLocatorConfig: LocatorConfig): Boolean {
        for (i in 1..50) {
            delay(100)
            if (_remoteLocatorConfig.value == stagedLocatorConfig)
                return true
            if (_locatorConfigMessageState.value == LocatorMessageState.SendFailure)
                return false
        }
        return false
    }

    // Recovery for a failed channel change: move the receiver back to the old channel
    // (BLE, always reachable), wait for PreLaunchData to resume, then re-send the
    // locator change once.  Returns true if the retry is confirmed.
    private suspend fun recoverLocatorChannel(
        stagedLocatorConfig: LocatorConfig,
        oldChannel: Int,
        service: BluetoothService,
    ): Boolean {
        service.changeReceiverConfig(
            ReceiverConfig(channel = oldChannel, deviceName = _remoteReceiverConfig.value.deviceName)
        )
        // Wait for the link to come back on the old channel.
        var relinked = false
        for (i in 1..50) {
            delay(100)
            if (_remoteReceiverConfig.value.channel == oldChannel &&
                _remoteLocatorConfig.value.loraChannel == oldChannel) {
                relinked = true
                break
            }
        }
        if (!relinked)
            return false
        // Retry the locator channel change once now that the link is restored.
        if (service.changeLocatorConfig(stagedLocatorConfig) != true)
            return false
        _locatorConfigMessageState.value = LocatorMessageState.Sent
        return waitForLocatorConfig(stagedLocatorConfig)
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
                Log.d(TAG, "Flight metadata received on attempt $attempt")
                return
            }

            Log.d(TAG, "Flight metadata attempt $attempt unanswered after ${backoffMs}ms — retrying")
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
        Log.d(TAG, "Requesting flight data for archive position ${_flightProfileArchivePosition.value}")
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
        val locatorId = Bytes.u32(frame, o); o += 4      // last base fields, before receiver-appended metadata
        val authTag = Bytes.u32(frame, o); o += 4
        val channel = Bytes.u8(frame[o]); o += 1
        val receiverBatteryMv = Bytes.u16(frame, o); o += 2
        val receiverNameBytes = frame.copyOfRange(o, o + Protocol.DEVICE_NAME_LENGTH)
        val receiverName = receiverNameBytes.takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
        o += Protocol.DEVICE_NAME_LENGTH
        val rssi = Bytes.i16(frame, o)

        return PrelaunchParsed(
            latitude, longitude, rawLatitude, rawLongitude, satellites, hacc,
            imuStatus, baroStatus, gpsStatus,
            deployStatus, agl,
            accel, gyro,
            deployCh1, deployCh2, deployCh3, deployCh4,
            droguePrimary, drogueBackup,
            mainPrimary, mainBackup,
            deviceName, locatorBatteryMv,
            locatorId, authTag,
            channel, receiverBatteryMv,
            receiverName, rssi
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
        val rssi = Bytes.i16(frame, o)

        return TelemetryParsed(
            latitude, longitude, satellites, hacc,
            imuStatus, baroStatus, gpsStatus,
            deploymentCh1Stats, deploymentCh2Stats,
            deploymentCh3Stats, deploymentCh4Stats,
            physicalDeploymentStats, agl,
            velNed, attitude,
            flightState, rssi
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

        return ReceiverInfoParsed(channel, deviceName)
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