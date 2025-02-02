package com.steampigeon.flightmanager.data

import android.bluetooth.BluetoothDevice
import com.steampigeon.flightmanager.data.RocketState.Accelerometer
import com.steampigeon.flightmanager.ui.RocketViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Data class that represents the current rocket locator state]
 */
data class RocketState(
    val lastPreLaunchMessageTime: Long = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val qInd: UByte = 0.toUByte(),
    val satellites: UByte = 0.toUByte(),
    val hdop: Float = 0f,
    val altimeterStatus: Boolean = false,
    val accelerometerStatus: Boolean = false,
    val deployChannel1Armed: Boolean = false,
    val deployChannel2Armed: Boolean = false,
    val altitudeAboveGroundLevel: Float = 0f,
    val accelerometer: Accelerometer = Accelerometer(0, 0, 0),
    val gForce: Float = 0f,
    val orientation: String = "",
    val velocity: Float = 0f,
    val batteryLevel: Int = 0,
    val flightState: FlightStates? = null,
) {
    data class Accelerometer(
        val x: Short = 0,
        val y: Short = 0,
        val z: Short = 0
    )
}

data class LocatorConfig(
    val deployMode: DeployMode? = null,
    val launchDetectAltitude: Int = 0,
    val droguePrimaryDeployDelay: Int = 0,
    val drogueBackupDeployDelay: Int = 0,
    val mainPrimaryDeployAltitude: Int = 0,
    val mainBackupDeployAltitude: Int = 0,
    val deploySignalDuration: Int = 0,
    val deviceName: String = "",
)

data class ReceiverConfig(
    val channel: Int = 0,
)

enum class DeployMode (val deployMode: UByte) {
    DroguePrimaryDrogueBackup(0u),
    MainPrimaryMainBackup(1u),
    DroguePrimaryMainPrimary(2u),
    DrogueBackupMainBackup(3u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.deployMode == value }
    }
}

enum class DeploymentTestOption (val deploymentTestOption: UByte) {
    None(0u),
    Channel1(1u),
    Channel2(2u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.deploymentTestOption == value }
    }
}

enum class FlightStates (val flightStates: UByte) {
    WaitingForLaunch(0u),
    Launched(1u),
    Burnout(2u),
    Noseover(3u),
    DroguePrimaryDeployed(4u),
    DrogueBackupDeployed(5u),
    MainPrimaryDeployed(6u),
    MainBackupDeployed(7u),
    Landed(8u),
    NoSignal(9u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.flightStates == value }
    }
}

enum class BluetoothConnectionState (val bluetoothConnectionState: UByte) {
    Idle(0u),
    Starting(1u),
    Enabling(2u),
    NotEnabled(3u),
    NotSupported(4u),
    Enabled(5u),
    AssociateStart(6u),
    AssociateWait(7u),
    NoDevicesAvailable(8u),
    Pairing(9u),
    PairingFailed(10u),
    Paired(11u),
    Connected(12u),
    Disconnected(13u);

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

enum class ConfigMessageState (val locatorConfigMessageState: UByte) {
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
    private val _bluetoothConnectionState = MutableStateFlow<BluetoothConnectionState>(BluetoothConnectionState.Idle)
    val bluetoothConnectionState: StateFlow<BluetoothConnectionState> = _bluetoothConnectionState.asStateFlow()

    private val _locatorDevice = MutableStateFlow<BluetoothDevice?>(null)
    val locatorDevice: StateFlow<BluetoothDevice?> = _locatorDevice.asStateFlow()

    private val _armedState = MutableStateFlow<Boolean>(false)
    val armedState: StateFlow<Boolean> = _armedState.asStateFlow()

    private val _locatorArmedMessageState = MutableStateFlow<LocatorArmedMessageState>(LocatorArmedMessageState.Idle)
    val locatorArmedMessageState: StateFlow<LocatorArmedMessageState> = _locatorArmedMessageState.asStateFlow()

    fun updateBluetoothConnectionState(newBluetoothConnectionState: BluetoothConnectionState) {
        _bluetoothConnectionState.value = newBluetoothConnectionState
    }

    fun updateLocatorDevice(newLocatorDevice: BluetoothDevice?) {
        _locatorDevice.value = newLocatorDevice
    }

    fun updateArmedState(newArmedState: Boolean) {
        _armedState.value = newArmedState
    }

    fun updateLocatorArmedMessageState(newArmedMessageState: LocatorArmedMessageState) {
        _locatorArmedMessageState.value = newArmedMessageState
    }
}