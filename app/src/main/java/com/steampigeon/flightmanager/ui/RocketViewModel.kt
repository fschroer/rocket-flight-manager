package com.steampigeon.flightmanager.ui

import android.app.Application
import android.content.Context
import android.hardware.SensorManager
import android.util.Log
import androidx.compose.ui.unit.IntOffset
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.mutualmobile.composesensors.AccelerometerSensorState
import com.mutualmobile.composesensors.MagneticFieldSensorState
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.UserPreferences
import com.steampigeon.flightmanager.data.Accelerometer
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.FlightProfileMetadata
import com.steampigeon.flightmanager.data.RocketState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.experimental.and
import kotlin.math.sqrt
import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.ReceiverConfig
import com.steampigeon.flightmanager.data.UserPreferencesSerializer
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "RocketViewModel"

val Context.userPreferencesDataStore: DataStore<UserPreferences> by dataStore(
    fileName = "user_prefs.pb",
    serializer = UserPreferencesSerializer
)

/**
 * [RocketViewModel] holds rocket locator status
 */

class RocketViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val SAMPLES_PER_SECOND = 20
        const val ALTIMETER_SCALE = 10
        const val ACCELEROMETER_SCALE = 2048
        private const val BATTERY_SCALE = 8.0 / 4096
        const val DEVICE_NAME_LENGTH = 12
        const val FLIGHT_DATA_MESSAGE_SAMPLES = 30
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

    private val _voiceEnabled = MutableStateFlow<Boolean>(true)
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

    private val _flightProfileAglData = MutableStateFlow<List<UShort>>(emptyList())
    val flightProfileAglData: StateFlow<List<UShort>> = _flightProfileAglData.asStateFlow()

    private val _flightProfileAccelerometerData = MutableStateFlow<List<Accelerometer>>(emptyList())
    val flightProfileAccelerometerData: StateFlow<List<Accelerometer>> = _flightProfileAccelerometerData.asStateFlow()

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

    fun collectInboundMessageData(service: BluetoothService) {
        viewModelScope.launch {
            service.data.collect { locatorMessage ->
                val currentTime = System.currentTimeMillis()
                val locatorMessageHeader = locatorMessage.copyOfRange(0, 3)
                when {
                    locatorMessageHeader.contentEquals(BluetoothService.prelaunchMessageHeader) -> {
                        val accelerometer = Accelerometer(
                            byteArrayToShort(locatorMessage, 43),
                            byteArrayToShort(locatorMessage, 45),
                            byteArrayToShort(locatorMessage, 47)
                        )
                        val rawGForce = sqrt((accelerometer.x * accelerometer.x + accelerometer.y * accelerometer.y + accelerometer.z * accelerometer.z).toFloat())
                        _rocketState.update { currentState ->
                            currentState.copy(
                                lastPreLaunchMessageTime = currentTime,
                                latitude = gpsCoord(locatorMessage, 11),
                                longitude = gpsCoord(locatorMessage, 19),
                                qInd = (locatorMessage[27] - 48).toUByte(),
                                satellites = locatorMessage[28].toUByte(),
                                hdop = byteArrayToFloat(locatorMessage, 29),
                                altimeterStatus = (locatorMessage[40].and(8).toInt() ushr 3) == 1,
                                accelerometerStatus = (locatorMessage[40].and(4).toInt() ushr 2) == 1,
                                deployChannel1Armed = (locatorMessage[40].and(2).toInt() ushr 1) == 1,
                                deployChannel2Armed = (locatorMessage[40].and(1).toInt()) == 1,
                                altitudeAboveGroundLevel = byteArrayToUShort(locatorMessage, 41).toFloat() / ALTIMETER_SCALE,
                                accelerometer = accelerometer,
                                gForce = rawGForce / ACCELEROMETER_SCALE,
                                orientation =
                                when {
                                    _rocketState.value.accelerometer.x.toFloat() / rawGForce < -0.5 -> "up"
                                    _rocketState.value.accelerometer.x.toFloat() / rawGForce > 0.5 -> "down"
                                    else -> "side"
                                },
                                locatorBatteryLevel = ((byteArrayToShort(locatorMessage, 72) - 3686.4) / 409.6 * 8).toInt(),
                                receiverBatteryLevel = (byteArrayToShort(locatorMessage, 75) / 12.5).toInt(),
                            )
                        }
                        _remoteLocatorConfig.update { currentState ->
                            currentState.copy(
                                deployMode = DeployMode.fromUByte(locatorMessage[49].toUByte()),
                                launchDetectAltitude = byteArrayToUShort(locatorMessage, 50).toInt(),
                                droguePrimaryDeployDelay = locatorMessage[52].toInt(),
                                drogueBackupDeployDelay = locatorMessage[53].toInt(),
                                mainPrimaryDeployAltitude = byteArrayToUShort(locatorMessage, 54).toInt(),
                                mainBackupDeployAltitude = byteArrayToUShort(locatorMessage, 56).toInt(),
                                deploySignalDuration = locatorMessage[58].toInt(),
                                deviceName = String(locatorMessage.copyOfRange(59, 71), Charsets.UTF_8).trimEnd('\u0000'),
                            )
                        }
                        _remoteReceiverConfig.update { currentState ->
                            currentState.copy(
                                channel = locatorMessage[74].toInt(),
                            )
                        }
                    }

                    locatorMessageHeader.contentEquals(BluetoothService.telemetryMessageHeader) -> {
                        val accelerometer = Accelerometer(
                            byteArrayToShort(locatorMessage, 43),
                            byteArrayToShort(locatorMessage, 45),
                            byteArrayToShort(locatorMessage, 47)
                        )
                        val rawGForce = sqrt((accelerometer.x * accelerometer.x + accelerometer.y * accelerometer.y + accelerometer.z * accelerometer.z).toFloat())
                        _rocketState.update { currentState ->
                            currentState.copy(
                                lastPreLaunchMessageTime = currentTime,
                                latitude = gpsCoord(locatorMessage, 11),
                                longitude = gpsCoord(locatorMessage, 19),
                                qInd = locatorMessage[27].toUByte(),
                                satellites = locatorMessage[28].toUByte(),
                                hdop = byteArrayToFloat(locatorMessage, 29),
                                altimeterStatus = (locatorMessage[40].and(8).toInt() ushr 3) == 1,
                                accelerometerStatus = (locatorMessage[40].and(4).toInt() ushr 2) == 1,
                                deployChannel1Armed = (locatorMessage[40].and(2).toInt() ushr 1) == 1,
                                deployChannel2Armed = (locatorMessage[40].and(1).toInt()) == 1,
                                altitudeAboveGroundLevel = byteArrayToUShort(locatorMessage, 41).toFloat() / ALTIMETER_SCALE,
                                accelerometer = accelerometer,
                                gForce = rawGForce / ACCELEROMETER_SCALE,
                                orientation =
                                when {
                                    _rocketState.value.accelerometer.x.toFloat() / rawGForce < -0.5 -> "up"
                                    _rocketState.value.accelerometer.x.toFloat() / rawGForce > 0.5 -> "down"
                                    else -> "side"
                                },
                                velocity = byteArrayToFloat(locatorMessage, 49),
                                flightState = FlightStates.fromUByte(locatorMessage[53].toUByte()) ?: currentState.flightState,
                                //altitudeAboveGroundLevel = byteArrayToUShort(locatorMessage, 52).toFloat() / ALTIMETER_SCALE,
                            )
                        }
//                        val inFlight = (_rocketState.value.flightState
//                            ?: FlightStates.WaitingForLaunch) > FlightStates.Launched && (_rocketState.value.flightState
//                            ?: FlightStates.WaitingForLaunch) < FlightStates.Landed
//                        for (i in 0..(if (inFlight) SAMPLES_PER_SECOND else 1) - 1) {
//                            _rocketState.value.agl[i] =
//                                byteArrayToFloat(locatorMessage, 41 + i * 4) / ALTIMETER_SCALE
//                        }
                    }
                    locatorMessageHeader.contentEquals(BluetoothService.receiverConfigMessageHeader) -> {
                        _remoteReceiverConfig.update { currentState ->
                            currentState.copy(channel = locatorMessage[3].toInt())
                        }
                    }
                    locatorMessageHeader.contentEquals(BluetoothService.flightProfileMetadataMessageHeader) -> {
                        _flightProfileMetadataMessageState.value = LocatorMessageState.AckUpdated
                        var archivePosition = 0
                        var messagePosition = locatorMessageHeader.size
                        while (messagePosition < locatorMessage.size && !(locatorMessage[messagePosition] == 0.toByte() &&
                                    locatorMessage[messagePosition + 1] == 0.toByte() && locatorMessage[messagePosition + 2] == 0.toByte())) {
                            val flightProfileMetadataItem = FlightProfileMetadata(
                                archivePosition,
                                byteArrayToDate(locatorMessage, messagePosition),
                                byteArrayToFloat(locatorMessage, messagePosition + 8),
                                byteArrayToFloat(locatorMessage, messagePosition + 12)
                            )
                            _flightProfileMetadata.value += flightProfileMetadataItem
                            archivePosition++
                            messagePosition += 16
                        }
                    }
                    locatorMessageHeader.contentEquals(BluetoothService.flightProfileDataMessageHeader) -> {
                        _flightProfileDataMessageState.value = LocatorMessageState.AckUpdated
                        var archiveSample = 0
                        val packetIndex = locatorMessage[locatorMessageHeader.size]
                        Log.d(TAG, "Received flight profile data packet $packetIndex")
                        if (packetIndex == 0.toByte()) {
                            _flightProfileAglData.value = emptyList()
                            _flightProfileAccelerometerData.value = emptyList()
                        }
                        var messagePosition = locatorMessageHeader.size + 1
                        while (messagePosition < locatorMessage.size && !(locatorMessage[messagePosition] == 0.toByte() && locatorMessage[messagePosition + 1] == 0.toByte())) {
                            _flightProfileAglData.value += byteArrayToUShort(locatorMessage, messagePosition)
                            messagePosition += 2
                            if (packetIndex * FLIGHT_DATA_MESSAGE_SAMPLES + archiveSample < _flightProfileMetadata.value[_flightProfileArchivePosition.value].timeToDrogue * RocketViewModel.SAMPLES_PER_SECOND) {
                                _flightProfileAccelerometerData.value +=
                                    Accelerometer(
                                    byteArrayToShort(locatorMessage, messagePosition),
                                    byteArrayToShort(locatorMessage, messagePosition + 2),
                                    byteArrayToShort(locatorMessage, messagePosition + 4)
                                    )
                                messagePosition += 6
                            }
                            archiveSample++
                        }
                    }
                    locatorMessageHeader.contentEquals(BluetoothService.deploymentTestMessageHeader) -> {
                        val deploymentTestCountdown = locatorMessage[3].toInt()
                        if (_deploymentTestActive.value)
                            _deploymentTestCountdown.value = deploymentTestCountdown
                    }
                }
            }
        }
    }

    fun gpsCoord(byteArray: ByteArray, offset: Int): Double {
        require(offset >= 0 && offset + 8 <= byteArray.size) { "Invalid offset or length" }
        val doubleByteArray = byteArray.copyOfRange(offset, offset + 8).reversedArray()
        val doubleValue = ByteBuffer.wrap(doubleByteArray).getDouble()
        return doubleValue.toInt() / 100 + (doubleValue - (doubleValue.toInt() / 100 * 100)) / 60
    }

    fun byteArrayToFloat(byteArray: ByteArray, offset: Int): Float {
        require(offset >= 0 && offset + 4 <= byteArray.size) { "Invalid offset or length" }
        val floatByteArray = byteArray.copyOfRange(offset, offset + 4).reversedArray()
        return ByteBuffer.wrap(floatByteArray).getFloat()
    }
    fun byteArrayToUShort(byteArray: ByteArray, offset: Int): UShort {
        require(offset >= 0 && offset + 2 <= byteArray.size) { "Invalid offset or length" }
        return (byteArray[offset].toUByte() + byteArray[offset + 1].toUByte() * 0x100u).toUShort()
    }
    fun byteArrayToShort(byteArray: ByteArray, offset: Int): Short {
        require(offset >= 0 && offset + 2 <= byteArray.size) { "Invalid offset or length" }
        return (byteArray[offset].toUByte() + byteArray[offset + 1].toUByte() * 0x100u).toShort()
    }
    fun byteArrayToDate(byteArray: ByteArray, offset: Int): Date {
        require(offset >= 0 && offset + 8 <= byteArray.size) { "Invalid offset or length" }
        val datePartByteArray = byteArray.copyOfRange(offset, offset + 4).reversedArray()
        val timePartByteArray = byteArray.copyOfRange(offset + 4, offset + 8).reversedArray()
        val datePart = ByteBuffer.wrap(datePartByteArray, 0, 4).int
        val timePart = ByteBuffer.wrap(timePartByteArray, 0, 4).int
        return Date(100 + (datePart % 100), (datePart / 100) % 100 - 1, datePart / 10000, timePart / 10000, (timePart / 100) % 100, timePart % 100)
    }

    fun handheldDeviceOrientation(accelerometerState: AccelerometerSensorState, magneticFieldState: MagneticFieldSensorState, landscapeOrientation: Boolean) {
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
        _lastHandheldDeviceAzimuth.value = _handheldDeviceAzimuth.value
        _handheldDeviceAzimuth.value = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat()
        _handheldDevicePitch.value = ((Math.toDegrees(-orientation[1].toDouble()) + 360) % 360).toFloat()
    }

    fun locatorVector(latLng1: LatLng, latLng2: LatLng) {
        val earthRadius = 6371000 // in meters

        val lat1Rad = Math.toRadians(latLng1.latitude)
        val lat2Rad = Math.toRadians(latLng2.latitude)
        val dLat = lat2Rad - lat1Rad
        val dLon = Math.toRadians(latLng2.longitude - latLng1.longitude)

        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        _locatorDistance.value = (earthRadius * c).toInt()

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        _locatorAzimuth.value = ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
        _locatorOrdinal.value = when {
            _locatorAzimuth.value.toInt() in (0..22) -> "north"
            _locatorAzimuth.value.toInt() in (23..67) -> "northeast"
            _locatorAzimuth.value.toInt() in (68..112) -> "east"
            _locatorAzimuth.value.toInt() in (113..157) -> "southeast"
            _locatorAzimuth.value.toInt() in (158..202) -> "south"
            _locatorAzimuth.value.toInt() in (203..247) -> "southwest"
            _locatorAzimuth.value.toInt() in (248..292) -> "west"
            _locatorAzimuth.value.toInt() in (293..337) -> "northwest"
            _locatorAzimuth.value.toInt() in (338..359) -> "north"
            else -> ""
        }
        _locatorElevation.value = ((Math.toDegrees(atan2(_rocketState.value.altitudeAboveGroundLevel, _locatorDistance.value.toFloat()).toDouble()) + 360) % 360).toFloat()
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

    fun updateFlightDataState() {
        viewModelScope.launch {
            for (i in 1..50) {
                delay(100)
                if (_flightProfileDataMessageState.value == LocatorMessageState.AckUpdated ||
                    _flightProfileDataMessageState.value == LocatorMessageState.SendFailure)
                    break
            }
            if (_flightProfileDataMessageState.value == LocatorMessageState.SendRequested ||
                _flightProfileDataMessageState.value == LocatorMessageState.Sent) {
                _flightProfileDataMessageState.value = LocatorMessageState.NotAcknowledged
            }
        }
    }
}