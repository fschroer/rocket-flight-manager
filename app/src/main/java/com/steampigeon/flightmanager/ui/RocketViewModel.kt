package com.steampigeon.flightmanager.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.RocketState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.experimental.and
import kotlin.math.sqrt
import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.data.LocatorConfig

/**
 * [RocketViewModel] holds rocket locator status
 */

class RocketViewModel() : ViewModel() {
    companion object {
        private const val ALTIMETER_SCALE = 10
        private const val ACCELEROMETER_SCALE = 2048
        const val SAMPLES_PER_SECOND = 20
    }
    /**
     * Display state
     */
    private val _rocketState = MutableStateFlow(RocketState())
    val rocketState: StateFlow<RocketState> = _rocketState.asStateFlow()

    private val _remoteLocatorConfig = MutableStateFlow<LocatorConfig>(LocatorConfig())
    val remoteLocatorConfig: StateFlow<LocatorConfig> = _remoteLocatorConfig.asStateFlow()

    private val _stagedLocatorConfig = MutableStateFlow<LocatorConfig>(LocatorConfig())
    val stagedLocatorConfig: StateFlow<LocatorConfig> = _stagedLocatorConfig.asStateFlow()

    fun updateRemoteLocatorConfig(newDeployMode: DeployMode? = null,
                                  newLaunchDetectAltitude: Int? = null,
                                  newDroguePrimaryDeployDelay: Int? = null,
                                  newDrogueBackupDeployDelay: Int? = null,
                                  newMainPrimaryDeployAltitude: Int? = null,
                                  newMainBackupDeployAltitude: Int? = null,
                                  newDeploySignalDuration: Int? = null,
                                  newDeviceName: String? = null) {
        _remoteLocatorConfig.update { currentState ->
            currentState.copy(
                deployMode = newDeployMode ?: currentState.deployMode,
                launchDetectAltitude = newLaunchDetectAltitude ?: currentState.launchDetectAltitude,
                droguePrimaryDeployDelay = newDroguePrimaryDeployDelay ?: currentState.droguePrimaryDeployDelay,
                drogueBackupDeployDelay = newDrogueBackupDeployDelay ?: currentState.drogueBackupDeployDelay,
                mainPrimaryDeployAltitude = newMainPrimaryDeployAltitude ?: currentState.mainPrimaryDeployAltitude,
                mainBackupDeployAltitude = newMainBackupDeployAltitude ?: currentState.mainBackupDeployAltitude,
                deploySignalDuration = newDeploySignalDuration ?: currentState.deploySignalDuration,
                deviceName = newDeviceName ?: currentState.deviceName,
            )
        }
    }

    fun updateStagedLocatorConfig(newDeployMode: DeployMode? = null,
                                  newLaunchDetectAltitude: Int? = null,
                                  newDroguePrimaryDeployDelay: Int? = null,
                                  newDrogueBackupDeployDelay: Int? = null,
                                  newMainPrimaryDeployAltitude: Int? = null,
                                  newMainBackupDeployAltitude: Int? = null,
                                  newDeploySignalDuration: Int? = null,
                                  newDeviceName: String? = null) {
        _stagedLocatorConfig.update { currentState ->
            currentState.copy(
                deployMode = newDeployMode ?: currentState.deployMode,
                launchDetectAltitude = newLaunchDetectAltitude ?: currentState.launchDetectAltitude,
                droguePrimaryDeployDelay = newDroguePrimaryDeployDelay ?: currentState.droguePrimaryDeployDelay,
                drogueBackupDeployDelay = newDrogueBackupDeployDelay ?: currentState.drogueBackupDeployDelay,
                mainPrimaryDeployAltitude = newMainPrimaryDeployAltitude ?: currentState.mainPrimaryDeployAltitude,
                mainBackupDeployAltitude = newMainBackupDeployAltitude ?: currentState.mainBackupDeployAltitude,
                deploySignalDuration = newDeploySignalDuration ?: currentState.deploySignalDuration,
                deviceName = newDeviceName ?: currentState.deviceName,
            )
        }
    }

    fun checkData() {
        Log.d("Test", "Latitude: ${_rocketState.value.latitude}")
    }

    fun collectLocatorData(service: BluetoothService) {
        viewModelScope.launch {
            service.data.collect { locatorMessage ->
                val currentTime = System.currentTimeMillis()
                _rocketState.update { currentState -> currentState.copy(lastMessageTime = currentTime) }
                _remoteLocatorConfig.update { currentState -> currentState.copy(lastMessageTime = currentTime) }
                if (locatorMessage.copyOfRange(0, 3)
                        .contentEquals(BluetoothService.prelaunchMessageHeader)
                ) {
                    val rawGForce = sqrt(
                        (_rocketState.value.accelerometer.x * _rocketState.value.accelerometer.x
                                + _rocketState.value.accelerometer.y * _rocketState.value.accelerometer.y
                                + _rocketState.value.accelerometer.z * _rocketState.value.accelerometer.z).toFloat()
                    )
                    _rocketState.update { currentState ->
                        currentState.copy(
                            latitude = gpsCoord(locatorMessage, 11),
                            longitude = gpsCoord(locatorMessage, 19),
                            hdop = byteArrayToFloat(locatorMessage, 29),
                            altimeterStatus = (locatorMessage[40].and(8)
                                .toInt() ushr 3) == 1,
                            accelerometerStatus = (locatorMessage[40].and(4)
                                .toInt() ushr 2) == 1,
                            deployChannel1Armed = (locatorMessage[40].and(2)
                                .toInt() ushr 1) == 1,
                            deployChannel2Armed = (locatorMessage[40].and(1).toInt()) == 1,
                            altitudeAboveGroundLevel = byteArrayToUShort(
                                locatorMessage,
                                41
                            ).toFloat() / ALTIMETER_SCALE,
                            accelerometer = RocketState.Accelerometer(
                                byteArrayToShort(locatorMessage, 43),
                                byteArrayToShort(locatorMessage, 45),
                                byteArrayToShort(locatorMessage, 47)
                            ),
                            gForce = rawGForce / ACCELEROMETER_SCALE,
                            orientation =
                            when {
                                _rocketState.value.accelerometer.x.toFloat() / rawGForce / ACCELEROMETER_SCALE < -0.5 -> "up"
                                _rocketState.value.accelerometer.x.toFloat() / rawGForce / ACCELEROMETER_SCALE > 0.5 -> "down"
                                else -> "side"
                            },
                            batteryVoltage = byteArrayToUShort(locatorMessage, 71),
                        )
                    }
                    _remoteLocatorConfig.update { currentState ->
                        currentState.copy(
                            deployMode = DeployMode.fromUByte(locatorMessage[49].toUByte()),
                            launchDetectAltitude = byteArrayToInt(locatorMessage, 50),
                            droguePrimaryDeployDelay = locatorMessage[52].toInt(),
                            drogueBackupDeployDelay = locatorMessage[53].toInt(),
                            mainPrimaryDeployAltitude = byteArrayToInt(locatorMessage, 54),
                            mainBackupDeployAltitude = byteArrayToInt(locatorMessage, 56),
                            deploySignalDuration = locatorMessage[58].toInt(),
                            deviceName = String(
                                locatorMessage.copyOfRange(59, 71),
                                Charsets.UTF_8
                            ),
                        )
                    }
                } else if (locatorMessage.copyOfRange(0, 3)
                        .contentEquals(BluetoothService.telemetryMessageHeader)
                ) {
                    _rocketState.update { currentState ->
                        currentState.copy(
                            latitude = gpsCoord(locatorMessage, 11),
                            longitude = gpsCoord(locatorMessage, 19),
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
    fun byteArrayToInt(byteArray: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 2 <= byteArray.size) { "Invalid offset or length" }
        return (byteArray[offset].toInt() + byteArray[offset + 1].toInt() * 256)
    }

}