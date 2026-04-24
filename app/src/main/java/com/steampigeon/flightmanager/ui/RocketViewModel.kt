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
import com.google.android.gms.maps.model.LatLng
import com.mutualmobile.composesensors.AccelerometerSensorState
import com.mutualmobile.composesensors.MagneticFieldSensorState
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.UserPreferences
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.DeploymentTestParsed
import com.steampigeon.flightmanager.data.FlightEventData
import com.steampigeon.flightmanager.data.FlightProfileMetadata
import com.steampigeon.flightmanager.data.RocketState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.math.sqrt
import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.MsgType
import com.steampigeon.flightmanager.data.PacketHeader
import com.steampigeon.flightmanager.data.PrelaunchParsed
import com.steampigeon.flightmanager.data.Protocol
import com.steampigeon.flightmanager.data.ReceiverConfig
import com.steampigeon.flightmanager.data.SensorHealth
import com.steampigeon.flightmanager.data.TelemetryParsed
import com.steampigeon.flightmanager.data.UserPreferencesSerializer
import com.steampigeon.flightmanager.data.Vec3f
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "RocketViewModel"
private var packetResendCount = 0
private var packetsRemaining = true
private var sampleIndex = 0

sealed class ParsedMessage {
    data class Prelaunch(val msg: PrelaunchParsed) : ParsedMessage()
    data class Telemetry(val msg: TelemetryParsed) : ParsedMessage()
    data class DeploymentTest(val msg: DeploymentTestParsed) : ParsedMessage()
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

    private val _remoteLocatorConfig = MutableStateFlow<LocatorConfig>(LocatorConfig())
    val remoteLocatorConfig: StateFlow<LocatorConfig> = _remoteLocatorConfig.asStateFlow()

    private val _remoteReceiverConfig = MutableStateFlow<ReceiverConfig>(ReceiverConfig())
    val remoteReceiverConfig: StateFlow<ReceiverConfig> = _remoteReceiverConfig.asStateFlow()

    private val _receiverConfigChanged = MutableStateFlow<Boolean>(false)
    val receiverConfigChanged: StateFlow<Boolean> = _receiverConfigChanged.asStateFlow()

    fun updateReceiverConfigChanged(newReceiverConfigChanged: Boolean) {
        _receiverConfigChanged.value = newReceiverConfigChanged
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
    }

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

    private val _flightEventData = MutableStateFlow<FlightEventData>(FlightEventData())
    val flightEventData: StateFlow<FlightEventData> = _flightEventData.asStateFlow()

    private val _flightProfileAglData = MutableStateFlow<List<UShort>>(emptyList())
    val flightProfileAglData: StateFlow<List<UShort>> = _flightProfileAglData.asStateFlow()

    private val _flightProfileAccelerometerData = MutableStateFlow<List<Vec3f>>(emptyList())
    val flightProfileAccelerometerData: StateFlow<List<Vec3f>> = _flightProfileAccelerometerData.asStateFlow()

    fun clearFlightProfileData() {
        _flightProfileAglData.value = emptyList()
        _flightProfileAccelerometerData.value = emptyList()
        sampleIndex = 0
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
        viewModelScope.launch {
            service.packets.collect { locatorMessage ->
//                Log.d("Collector", "Received packet size=${locatorMessage.size} bytes")
                val currentTime = System.currentTimeMillis()
                try {
                    when (val parsed = parseIncoming(locatorMessage)) {
                        is ParsedMessage.Prelaunch -> {
                            _rocketState.update { currentState ->
                                currentState.copy(
                                    lastPreLaunchMessageTime = currentTime,
                                    latitude = parsed.msg.latitude,
                                    longitude = parsed.msg.longitude,
                                    satellites = parsed.msg.satellites.toUByte(),
                                    hdop = parsed.msg.hacc,
                                    baroStatus = parsed.msg.baroStatus,
                                    imuStatus = parsed.msg.imuStatus,
                                    gpsStatus = parsed.msg.gpsStatus,
                                    deployChannel1Armed = parsed.msg.deployStatus.and(1) == 1,
                                    deployChannel2Armed = parsed.msg.deployStatus.and(2) == 2,
                                    altitudeAboveGroundLevel = parsed.msg.agl,
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
                                    locatorBatteryLevel = ((parsed.msg.locatorBatteryMv - 3900) / 250.0f * 8).toInt(),
                                    receiverBatteryLevel = ((parsed.msg.receiverBatteryMv) / 12.5).toInt(),
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
                                    deviceName = parsed.msg.deviceName,
                                )
                            }
                            _remoteReceiverConfig.update { currentState ->
                                currentState.copy(
                                    channel = parsed.msg.receiverChannel,
                                )
                            }
                        }
                        is ParsedMessage.Telemetry -> {
                            _rocketState.update { currentState ->
                                currentState.copy(
                                    lastPreLaunchMessageTime = currentTime,
                                    latitude = parsed.msg.latitude,
                                    longitude = parsed.msg.longitude,
                                    satellites = parsed.msg.satellites.toUByte(),
                                    hdop = parsed.msg.hacc,
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
                                    altitudeAboveGroundLevel = parsed.msg.agl,
                                    accelerometer = parsed.msg.accel,
                                    gForce = sqrt(parsed.msg.accel.x * parsed.msg.accel.x + parsed.msg.accel.y * parsed.msg.accel.y +
                                            parsed.msg.accel.z * parsed.msg.accel.z),
                                    orientation =
                                        when {
                                            _rocketState.value.accelerometer.x.toFloat() < -4.9 -> "up"
                                            _rocketState.value.accelerometer.x.toFloat() > 4.9 -> "down"
                                            else -> "side"
                                        },
                                    gyro = parsed.msg.gyro,
                                    velocity = parsed.msg.velocity,
                                    flightState = parsed.msg.flightState,
                                )
                            }
                        }
                        is ParsedMessage.DeploymentTest -> {
                            val deploymentTestCountdown = parsed.msg.count
                            if (_deploymentTestActive.value)
                                _deploymentTestCountdown.value = deploymentTestCountdown
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    Log.e("Parser", "Error parsing inbound packet", e)
                }

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
            }
        }
    }

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
                if (_remoteReceiverConfig.value == stagedReceiverConfig) {
                    _receiverConfigMessageState.value = LocatorMessageState.AckUpdated
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

    fun updateLocatorConfigState(stagedLocatorConfig: LocatorConfig) {
        viewModelScope.launch {
            for (i in 1..50) {
                delay(100)
                if (_remoteLocatorConfig.value == stagedLocatorConfig) {
                    _locatorConfigMessageState.value = LocatorMessageState.AckUpdated
                    break
                }
                else if (_locatorConfigMessageState.value == LocatorMessageState.SendFailure)
                    break
            }
            if (_locatorConfigMessageState.value == LocatorMessageState.SendRequested ||
                _locatorConfigMessageState.value == LocatorMessageState.Sent) {
                _locatorConfigMessageState.value = LocatorMessageState.NotAcknowledged
            }
            if (_locatorConfigMessageState.value == LocatorMessageState.AckUpdated)
                _locatorConfigChanged.value = false
            delay(2000)
            _locatorConfigMessageState.value = LocatorMessageState.Idle
        }
    }

    fun updateFlightMetadataState() {
        viewModelScope.launch {
            for (i in 1..50) {
                delay(100)
                if (_flightProfileMetadataMessageState.value == LocatorMessageState.AckUpdated ||
                    _flightProfileMetadataMessageState.value == LocatorMessageState.SendFailure)
                    break
            }
            if (_flightProfileMetadataMessageState.value == LocatorMessageState.SendRequested ||
                _flightProfileMetadataMessageState.value == LocatorMessageState.Sent) {
                _flightProfileMetadataMessageState.value = LocatorMessageState.NotAcknowledged
            }
        }
    }

    fun getFlightProfileData(service: BluetoothService) {
        packetResendCount = 0
        _flightProfileDataDisplayState.value = true
        packetsRemaining = true
        _flightProfileDataMessageState.value = LocatorMessageState.SendRequested
        viewModelScope.launch {
            var packet : Byte = 0xff.toByte()
            while (packetsRemaining && packetResendCount < 3 && _flightProfileDataMessageState.value != LocatorMessageState.SendFailure) {
                Log.d(TAG, "Sending flight profile request, packet $packet")
                if (service.requestFlightProfileData(_flightProfileArchivePosition.value, packet)) {
                    updateFlightProfileDataMessageState(LocatorMessageState.Sent)
                    for (i in 1..10) {
                        delay(100)
                        if (_flightProfileDataMessageState.value == LocatorMessageState.AckUpdated) {
                            packetResendCount = 0
                            packet++
                            break
                        }
                    }
                    if (_flightProfileDataMessageState.value != LocatorMessageState.AckUpdated)
                        packetResendCount++
                }
                else
                    updateFlightProfileDataMessageState(LocatorMessageState.SendFailure)
                Log.d(TAG, "Sent flight profile message. Packets remaining: $packetsRemaining, packetResendCount: $packetResendCount, messageState: ${_flightProfileDataMessageState.value}")
            }
        }
    }

    private fun parseIncoming(frame: ByteArray): ParsedMessage? {
        if (frame.size < 6) return null

       val msgHeader = parseHeader(frame)

        return when (msgHeader?.msgType) {
            MsgType.PreLaunchData -> ParsedMessage.Prelaunch(parsePrelaunch(frame))
            MsgType.TelemetryData -> ParsedMessage.Telemetry(parseTelemetry(frame))
            MsgType.DeploymentTest -> ParsedMessage.DeploymentTest(parseDeploymentTest(frame))
            else -> null
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
        val channel = Bytes.u8(frame[o]); o += 1
        val receiverBatteryMv = Bytes.u16(frame, o); o += 2

        return PrelaunchParsed(
            latitude, longitude, satellites, hacc,
            imuStatus, baroStatus, gpsStatus,
            deployStatus, agl,
            accel, gyro,
            deployCh1, deployCh2, deployCh3, deployCh4,
            droguePrimary, drogueBackup,
            mainPrimary, mainBackup,
            deviceName, locatorBatteryMv,
            channel, receiverBatteryMv
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

        val accel = parseVec3f(frame, o); o += 12
        val gyro = parseVec3f(frame, o); o += 12

        val velocity = Bytes.f32(frame, o); o += 4
        val flightState = FlightStates.fromUByte(frame[o].toUByte()); o += 1

        return TelemetryParsed(
            latitude, longitude, satellites, hacc,
            imuStatus, baroStatus, gpsStatus,
            deploymentCh1Stats, deploymentCh2Stats,
            deploymentCh3Stats, deploymentCh4Stats,
            physicalDeploymentStats, agl,
            accel, gyro,
            velocity, flightState
        )
    }

    fun parseDeploymentTest (frame: ByteArray): DeploymentTestParsed {
        var o = 6

        val count = Bytes.u8(frame[o]); o += 1

        return DeploymentTestParsed(count)
    }

    object Bytes {
        fun u8(b: Byte) = b.toInt() and 0xFF

        fun u16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8)

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