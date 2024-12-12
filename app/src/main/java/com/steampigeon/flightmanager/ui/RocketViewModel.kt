package com.steampigeon.flightmanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.RocketUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.experimental.and
import kotlin.math.sqrt
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import kotlinx.coroutines.delay
import java.util.regex.Pattern

/**
 * [RocketViewModel] holds rocket locator status
 */

class RocketViewModel() : ViewModel() {
    companion object {
        private const val ALTIMETER_SCALE = 10
        private const val ACCELEROMETER_SCALE = 2048
    }
    /**
     * Display state
     */
    private val _uiState = MutableStateFlow(RocketUiState())
    val uiState: StateFlow<RocketUiState> = _uiState.asStateFlow()

    private val _locatorMessage = MutableStateFlow<ByteArray>(ByteArray(256))
    val data: StateFlow<ByteArray> = _locatorMessage.asStateFlow()

    private var lastExecutionTime = System.currentTimeMillis()
    var locatorDetected = false

    var gForce = 0f
    var locatorOrientation = ""

    fun collectLocatorData(service: BluetoothService) {
        var elapsedSeconds = 0.0
        elapsedSeconds = (System.currentTimeMillis() - lastExecutionTime) / 1000.0
        locatorDetected = (elapsedSeconds < 5)
        viewModelScope.launch {
            service.data.onStart {
            }.collect { data ->
                _locatorMessage.value = data
                lastExecutionTime = System.currentTimeMillis()
            }
        }
        if (!locatorDetected) {
            val test = true
        }
        if (_locatorMessage.value.copyOfRange(0, 3).contentEquals(BluetoothService.prelaunchMessageHeader)) {
            _uiState.update { currentState ->
                currentState.copy(
                    latitude = gpsCoord(_locatorMessage.value, 11),
                    longitude = gpsCoord(_locatorMessage.value, 19),
                    hdop = byteArrayToFloat(_locatorMessage.value, 29),
                    altimeterStatus = (_locatorMessage.value[40].and(8).toInt() ushr 3) == 1,
                    accelerometerStatus = (_locatorMessage.value[40].and(4).toInt() ushr 2) == 1,
                    deployChannel1Armed = (_locatorMessage.value[40].and(2).toInt() ushr 1) == 1,
                    deployChannel2Armed = (_locatorMessage.value[40].and(1).toInt()) == 1,
                    altitudeAboveGroundLevel = byteArrayToUShort(_locatorMessage.value, 41).toFloat() / ALTIMETER_SCALE,
                    accelerometer = RocketUiState.Accelerometer(
                        byteArrayToShort(_locatorMessage.value, 43),
                        byteArrayToShort(_locatorMessage.value, 45),
                        byteArrayToShort(_locatorMessage.value, 47)
                    ),
                    deployMode = DeployMode.fromUByte(_locatorMessage.value[49].toUByte()),
                    launchDetectAltitude = byteArrayToUShort(_locatorMessage.value, 50),
                    droguePrimaryDeployDelay = _locatorMessage.value[52].toUByte(),
                    drogueBackupDeployDelay = _locatorMessage.value[53].toUByte(),
                    mainPrimaryDeployAltitude = byteArrayToUShort(_locatorMessage.value, 54),
                    mainBackupDeployAltitude = byteArrayToUShort(_locatorMessage.value, 56),
                    deploySignalDuration = _locatorMessage.value[58].toFloat(),
                    deviceName = String(_locatorMessage.value.copyOfRange(59, 71), Charsets.UTF_8),
                    batteryVoltage = byteArrayToUShort(_locatorMessage.value, 71),
                )
            }
            gForce = sqrt((_uiState.value.accelerometer.x * _uiState.value.accelerometer.x + _uiState.value.accelerometer.y * _uiState.value.accelerometer.y + _uiState.value.accelerometer.z * _uiState.value.accelerometer.z).toFloat()) / ACCELEROMETER_SCALE
            locatorOrientation =
            when {
                _uiState.value.accelerometer.x.toFloat() / ACCELEROMETER_SCALE / gForce < -0.5 ->
                    "up"
                _uiState.value.accelerometer.x.toFloat() / ACCELEROMETER_SCALE / gForce > 0.5 ->
                    "down"
                else -> "side"
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

}