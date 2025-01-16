package com.steampigeon.flightmanager.ui

import android.hardware.SensorManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.mutualmobile.composesensors.AccelerometerSensorState
import com.mutualmobile.composesensors.MagneticFieldSensorState
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.ConfigMessageState
import com.steampigeon.flightmanager.data.DeployMode
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
import kotlinx.coroutines.delay
import java.lang.Thread.sleep
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "RocketViewModel"

/**
 * [RocketViewModel] holds rocket locator status
 */

class RocketViewModel() : ViewModel() {
    companion object {
        const val SAMPLES_PER_SECOND = 20
        private const val ALTIMETER_SCALE = 10
        private const val ACCELEROMETER_SCALE = 2048
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

    private val _locatorConfigMessageState = MutableStateFlow<ConfigMessageState>(ConfigMessageState.Idle)
    val locatorConfigMessageState: StateFlow<ConfigMessageState> = _locatorConfigMessageState.asStateFlow()

    fun updateLocatorConfigMessageState(newLocatorConfigMessageState: ConfigMessageState) {
        _locatorConfigMessageState.value = newLocatorConfigMessageState
    }

    private val _receiverConfigMessageState = MutableStateFlow<ConfigMessageState>(ConfigMessageState.Idle)
    val receiverConfigMessageState: StateFlow<ConfigMessageState> = _receiverConfigMessageState.asStateFlow()

    fun updateReceiverConfigMessageState(newReceiverConfigMessageState: ConfigMessageState) {
        _receiverConfigMessageState.value = newReceiverConfigMessageState
    }

    private val _deploymentTestCountdown = MutableStateFlow<Int>(0)
    val deploymentTestCountdown: StateFlow<Int> = _deploymentTestCountdown.asStateFlow()

    fun updateDeploymentTestCountdown(newDeploymentTestCountdown: Int) {
        _deploymentTestCountdown.value = newDeploymentTestCountdown
    }

    private val _voiceName = MutableStateFlow<String>("us-x-iob-local")
    val voiceName: StateFlow<String> = _voiceName.asStateFlow()

    fun updateVoiceName(newVoiceName: String) {
        _voiceName.value = newVoiceName
    }

    fun collectInboundMessageData(service: BluetoothService) {
        viewModelScope.launch {
            service.data.collect { locatorMessage ->
                val currentTime = System.currentTimeMillis()
                when {
                    locatorMessage.copyOfRange(0, 3).contentEquals(BluetoothService.prelaunchMessageHeader) -> {
                        val rawGForce = sqrt(
                            (_rocketState.value.accelerometer.x * _rocketState.value.accelerometer.x
                                    + _rocketState.value.accelerometer.y * _rocketState.value.accelerometer.y
                                    + _rocketState.value.accelerometer.z * _rocketState.value.accelerometer.z).toFloat()
                        )
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
                                accelerometer = RocketState.Accelerometer(
                                    byteArrayToShort(locatorMessage, 43),
                                    byteArrayToShort(locatorMessage, 45),
                                    byteArrayToShort(locatorMessage, 47)
                                ),
                                gForce = rawGForce / ACCELEROMETER_SCALE,
                                orientation =
                                when {
                                    _rocketState.value.accelerometer.x.toFloat() / rawGForce < -0.5 -> "up"
                                    _rocketState.value.accelerometer.x.toFloat() / rawGForce > 0.5 -> "down"
                                    else -> "side"
                                },
                                batteryVoltage = byteArrayToUShort(locatorMessage, 71),
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

                    locatorMessage.copyOfRange(0, 3).contentEquals(BluetoothService.telemetryMessageHeader) -> {
                        _rocketState.update { currentState ->
                            currentState.copy(
                                lastPreLaunchMessageTime = currentTime,
                                latitude = gpsCoord(locatorMessage, 11),
                                longitude = gpsCoord(locatorMessage, 19),
                                qInd = locatorMessage[27].toUByte(),
                                satellites = locatorMessage[28].toUByte(),
                                hdop = byteArrayToFloat(locatorMessage, 29),
                                flightState = FlightStates.fromUByte(locatorMessage[40].toUByte())
                                    ?: currentState.flightState,
                            )
                        }
                        val inFlight = (_rocketState.value.flightState
                            ?: FlightStates.WaitingLaunch) > FlightStates.Launched && (_rocketState.value.flightState
                            ?: FlightStates.WaitingLaunch) < FlightStates.Landed
                        for (i in 0..(if (inFlight) SAMPLES_PER_SECOND else 1) - 1) {
                            _rocketState.value.agl[i] =
                                byteArrayToFloat(locatorMessage, 40 + i * 4) / ALTIMETER_SCALE
                        }
                    }
                    locatorMessage.copyOfRange(0, 3).contentEquals(BluetoothService.receiverConfigMessageHeader) -> {
                        _remoteReceiverConfig.update { currentState ->
                            currentState.copy(channel = locatorMessage[3].toInt())
                        }
                    }
                    locatorMessage.copyOfRange(0, 3).contentEquals(BluetoothService.deploymentTestMessageHeader) -> {
                        _deploymentTestCountdown.value = locatorMessage[3].toInt()
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
        return (byteArray[offset].toUByte() + byteArray[offset + 1].toUByte() * 256u).toUShort()
    }
    fun byteArrayToShort(byteArray: ByteArray, offset: Int): Short {
        require(offset >= 0 && offset + 2 <= byteArray.size) { "Invalid offset or length" }
        return (byteArray[offset].toUByte() + byteArray[offset + 1].toUByte() * 256u).toShort()
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

        _locatorElevation.value = ((Math.toDegrees(atan2(_rocketState.value.altitudeAboveGroundLevel, _locatorDistance.value.toFloat()).toDouble()) + 360) % 360).toFloat()
    }

    fun updateReceiverConfigState(stagedReceiverConfig: ReceiverConfig) {
        viewModelScope.launch {
            for (i in 1..50) {
                delay(100)
                if (_remoteReceiverConfig.value == stagedReceiverConfig) {
                    _receiverConfigMessageState.value = ConfigMessageState.AckUpdated
                    break
                }
                else if (_receiverConfigMessageState.value == ConfigMessageState.SendFailure)
                    break
            }
            if (_receiverConfigMessageState.value == ConfigMessageState.SendRequested ||
                _receiverConfigMessageState.value == ConfigMessageState.Sent) {
                _receiverConfigMessageState.value = ConfigMessageState.NotAcknowledged
            }
            if (_receiverConfigMessageState.value == ConfigMessageState.AckUpdated)
                _receiverConfigChanged.value = false
            delay(2000)
            _receiverConfigMessageState.value = ConfigMessageState.Idle
        }
    }

    fun updateLocatorConfigState(stagedLocatorConfig: LocatorConfig) {
        viewModelScope.launch {
            for (i in 1..50) {
                delay(100)
                if (_remoteLocatorConfig.value == stagedLocatorConfig) {
                    _locatorConfigMessageState.value = ConfigMessageState.AckUpdated
                    break
                }
                else if (_locatorConfigMessageState.value == ConfigMessageState.SendFailure)
                    break
            }
            if (_locatorConfigMessageState.value == ConfigMessageState.SendRequested ||
                _locatorConfigMessageState.value == ConfigMessageState.Sent) {
                _locatorConfigMessageState.value = ConfigMessageState.NotAcknowledged
            }
            if (_locatorConfigMessageState.value == ConfigMessageState.AckUpdated)
                _locatorConfigChanged.value = false
            delay(2000)
            _locatorConfigMessageState.value = ConfigMessageState.Idle
        }
    }
}