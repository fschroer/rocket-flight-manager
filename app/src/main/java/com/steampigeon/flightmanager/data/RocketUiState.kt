package com.steampigeon.flightmanager.data

import android.bluetooth.BluetoothDevice
import com.steampigeon.flightmanager.ui.RocketViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Data class that represents the current rocket locator state]
 */
data class LocatorConfig(
    val deployMode: DeployMode = DeployMode.kDroguePrimaryDrogueBackup,
    val launchDetectAltitude: UShort = 0u,
    val droguePrimaryDeployDelay: UByte = 0u,
    val drogueBackupDeployDelay: UByte = 0u,
    val mainPrimaryDeployAltitude: UShort = 0u,
    val mainBackupDeployAltitude: UShort = 0u,
    val deploySignalDuration: UByte = 0u,
    val deviceName: String = "",
)

data class RocketUiState(
    val lastMessageTime: Long = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val hdop: Float = 0f,
    val altimeterStatus: Boolean = false,
    val accelerometerStatus: Boolean = false,
    val deployChannel1Armed: Boolean = false,
    val deployChannel2Armed: Boolean = false,
    val altitudeAboveGroundLevel: Float = 0f,
    val accelerometer: Accelerometer = Accelerometer(0, 0, 0),
    val batteryVoltage: UShort = 0u,
    //val locatorDetected: Boolean = false,
    val flightState: FlightStates = FlightStates.kWaitingLaunch,
    val agl: FloatArray = FloatArray(RocketViewModel.SAMPLES_PER_SECOND) { 0f },
    ) {
        data class Accelerometer(
            val x: Short = 0,
            val y: Short = 0,
            val z: Short = 0
        )
    }

enum class DeployMode (val deployMode: UByte) {
    kDroguePrimaryDrogueBackup(0u),
    kMainPrimaryMainBackup(1u),
    kDroguePrimaryMainPrimary(2u),
    kDrogueBackupMainBackup(3u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.deployMode == value }
    }
}

enum class FlightStates (val flightStates: UByte) {
    kWaitingLaunch(0u),
    kLaunched(1u),
    kBurnout(2u),
    kNoseover(3u),
    kDroguePrimaryDeployed(4u),
    kDrogueBackupDeployed(5u),
    kMainPrimaryDeployed(6u),
    kMainBackupDeployed(7u),
    kLanded(8u),
    kNoSignal(9u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.flightStates == value }
    }
}

enum class BluetoothConnectionState (val bluetoothConnectionState: UByte) {
    NotStarted(0u),
    Enabling(1u),
    NotEnabled(2u),
    NotSupported(3u),
    Enabled(4u),
    AssociateStart(5u),
    AssociateWait(6u),
    NoDevicesAvailable(7u),
    Pairing(8u),
    PairingFailed(9u),
    Paired(10u),
    Connected(11u),
    Disconnected(12u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.bluetoothConnectionState == value } ?: throw IllegalArgumentException("Invalid type: $value")
    }

}

enum class LocatorArmedMessageState (val locatorArmedMessageState: UByte) {
    Idle(0u),
    SendRequested(1u),
    Sent(2u),
    AckUpdated(3u),
    SendFailure(4u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.locatorArmedMessageState == value } ?: throw IllegalArgumentException("Invalid type: $value")
    }

}

enum class LocatorConfigMessageState (val locatorConfigMessageState: UByte) {
    Idle(0u),
    SendRequested(1u),
    Sent(2u),
    AckUpdated(3u),
    SendFailure(4u),
    NotAcknowledged(5u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.locatorConfigMessageState == value } ?: throw IllegalArgumentException("Invalid type: $value")
    }

}

object BluetoothManagerRepository {
    private val _bluetoothConnectionState = MutableStateFlow<BluetoothConnectionState>(BluetoothConnectionState.NotStarted)
    val bluetoothConnectionState: StateFlow<BluetoothConnectionState> = _bluetoothConnectionState.asStateFlow()

    private val _locatorDevice = MutableStateFlow<BluetoothDevice?>(null)
    val locatorDevice: StateFlow<BluetoothDevice?> = _locatorDevice.asStateFlow()

    private val _armedState = MutableStateFlow<Boolean>(false)
    val armedState: StateFlow<Boolean> = _armedState.asStateFlow()

    private val _locatorConfig = MutableStateFlow<LocatorConfig>(LocatorConfig())
    val locatorConfig: StateFlow<LocatorConfig> = _locatorConfig.asStateFlow()

    private val _locatorArmedMessageState = MutableStateFlow<LocatorArmedMessageState>(LocatorArmedMessageState.Idle)
    val locatorArmedMessageState: StateFlow<LocatorArmedMessageState> = _locatorArmedMessageState.asStateFlow()

    private val _locatorConfigMessageState = MutableStateFlow<LocatorConfigMessageState>(LocatorConfigMessageState.Idle)
    val locatorConfigMessageState: StateFlow<LocatorConfigMessageState> = _locatorConfigMessageState.asStateFlow()

    fun updateBluetoothConnectionState(newBluetoothConnectionState: BluetoothConnectionState) {
        _bluetoothConnectionState.value = newBluetoothConnectionState
    }

    fun updateLocatorDevice(newLocatorDevice: BluetoothDevice?) {
        _locatorDevice.value = newLocatorDevice
    }

    fun updateArmedState(newArmedState: Boolean) {
        _armedState.value = newArmedState
    }

    fun updateLocatorConfig(newLocatorConfig: LocatorConfig) {
        _locatorConfig.value = newLocatorConfig
    }

    fun updateLocatorArmedMessageState(newArmedMessageState: LocatorArmedMessageState) {
        _locatorArmedMessageState.value = newArmedMessageState
    }

    fun updateLocatorConfigMessageState(newConfigMessageState: LocatorConfigMessageState) {
        _locatorConfigMessageState.value = newConfigMessageState
    }
}