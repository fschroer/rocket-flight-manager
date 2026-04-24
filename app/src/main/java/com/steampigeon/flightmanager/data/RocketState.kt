package com.steampigeon.flightmanager.data

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZonedDateTime

object Protocol {
    const val HEADER_SIZE =
        UByte.SIZE_BYTES +   // systemId
                UByte.SIZE_BYTES +   // msgType
                UShort.SIZE_BYTES +  // msgCount
                UShort.SIZE_BYTES    // crc

    const val MESSAGE_BUFFER_SIZE = 52 * 256 // Up to 46 packets during ascent, 6 packets during descent * maximum message size
    const val SYSTEM_ID : Byte = 0x44
    const val PRELAUNCH_MESSAGE_PAYLOAD_SIZE = 80 // LoRa message size (83) + channel (1) + receiver battery level (2) = 86
    const val TELEMETRY_MESSAGE_PAYLOAD_SIZE = 62 // LoRa message size (68)
    const val RECEIVER_CONFIG_PAYLOAD_MESSAGE_SIZE = 1
    const val FLIGHT_PROFILE_METADATA_PAYLOAD_MESSAGE_SIZE = 128
    const val FLIGHT_PROFILE_DATA_PAYLOAD_MESSAGE_SIZE = 241
    const val DEPLOYMENT_TEST_MESSAGE_PAYLOAD_SIZE = 1
    const val MAX_PACKET_SIZE = 255
    const val DEVICE_NAME_LENGTH = 12
}

/**
 * Data class that represents the current rocket locator state]
 */
data class RocketState(
    val lastPreLaunchMessageTime: Long = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val satellites: UByte = 0.toUByte(),
    val hdop: Float = 0f,
    val baroStatus: SensorHealth = SensorHealth.Ok,
    val imuStatus: SensorHealth = SensorHealth.Ok,
    val gpsStatus: SensorHealth = SensorHealth.Ok,
    val deployChannel1Armed: Boolean = false,
    val deployChannel2Armed: Boolean = false,
    val deployChannel3Armed: Boolean = false,
    val deployChannel4Armed: Boolean = false,
    val channel1Fired: Boolean = false,
    val channel2Fired: Boolean = false,
    val channel3Fired: Boolean = false,
    val channel4Fired: Boolean = false,
    val drogueDeployDetected: Boolean = false,
    val mainDeployDetected: Boolean = false,
    val altitudeAboveGroundLevel: Float = 0f,
    val accelerometer: Vec3f = Vec3f(0f, 0f, 0f),
    val gyro: Vec3f = Vec3f(0f, 0f, 0f),
    val gForce: Float = 0f,
    val orientation: String = "",
    val velocity: Float = 0f,
    val locatorBatteryLevel: Int = 0,
    val receiverBatteryLevel: Int = 0,
    val flightState: FlightStates = FlightStates.WaitingForLaunch,
)

data class LocatorConfig(
    val deploymentChannel1Mode: DeployMode? = null,
    val deploymentChannel2Mode: DeployMode? = null,
    val deploymentChannel3Mode: DeployMode? = null,
    val deploymentChannel4Mode: DeployMode? = null,
    val launchDetectAltitude: Int = 0,
    val droguePrimaryDeployDelay: Int = 0,
    val drogueBackupDeployDelay: Int = 0,
    val mainPrimaryDeployAltitude: Int = 0,
    val mainBackupDeployAltitude: Int = 0,
    val deploySignalDuration: Int = 0,
    val loraChannel: Int = 0,
    val deviceName: String = "",
)

data class ReceiverConfig(
    val channel: Int = 0,
)

data class FlightProfileMetadata(
    val position: Int,
    val date: ZonedDateTime?,
    val apogee: Float,
    val timeToDrogue: Float,
)

data class FlightEventData(
    val launchDate: ZonedDateTime? = null,
    val launchTime: Int = 0,
    val maxAltitude: Float = 0f,
    val maxAltitudeSampleIndex: Int = 0,
    val launchDetectAltitude: Float = 0f,
    val launchDetectSampleIndex: Int = 0,
    val burnoutAltitude: Float = 0f,
    val burnoutSampleIndex: Int = 0,
    val noseOverAltitude: Float = 0f,
    val noseOverSampleIndex: Int = 0,
    val droguePrimaryDeployAltitude: Float = 0f,
    val droguePrimaryDeploySampleIndex: Int = 0,
    val drogueBackupDeployAltitude: Float = 0f,
    val drogueBackupDeploySampleIndex: Int = 0,
    val drogueVelocityThresholdAltitude: Float = 0f,
    val drogueVelocityThresholdSampleIndex: Int = 0,
    val mainPrimaryDeployAltitude: Float = 0f,
    val mainPrimaryDeploySampleIndex: Int = 0,
    val mainBackupDeployAltitude: Float = 0f,
    val mainBackupDeploySampleIndex: Int = 0,
    val mainVelocityThresholdAltitude: Float = 0f,
    val mainVelocityThresholdSampleIndex: Int = 0,
    val landingAltitude: Float = 0f,
    val landingSampleIndex: Int = 0,
    val channel1Mode: DeployMode? = DeployMode.DroguePrimary,
    val channel2Mode: DeployMode? = DeployMode.DrogueBackup,
    val channel3Mode: DeployMode? = DeployMode.MainPrimary,
    val channel4Mode: DeployMode? = DeployMode.MainBackup,
    val channel1Fired: Boolean = false,
    val channel2Fired: Boolean = false,
    val channel3Fired: Boolean = false,
    val channel4Fired: Boolean = false,
    val channel1PreFireContinuity: Boolean = false,
    val channel2PreFireContinuity: Boolean = false,
    val channel3PreFireContinuity: Boolean = false,
    val channel4PreFireContinuity: Boolean = false,
    val channel1PostFireContinuity: Boolean = false,
    val channel2PostFireContinuity: Boolean = false,
    val channel3PostFireContinuity: Boolean = false,
    val channel4PostFireContinuity: Boolean = false,
    val gRangeScale: Float = 0f,
)

enum class DeployMode (val deployMode: UByte) {
    DroguePrimary(0u),
    DrogueBackup(1u),
    MainPrimary(2u),
    MainBackup(3u),
    Unused(7u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.deployMode == value } ?: Unused
    }
}

enum class DeploymentTestOption (val deploymentTestOption: UByte) {
    None(0u),
    Channel1(1u),
    Channel2(2u),
    Channel3(3u),
    Channel4(4u);

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
        fun fromUByte(value: UByte) = entries.firstOrNull { it.flightStates == value } ?: NoSignal
    }
}

data class Vec3f (
    val x: Float,
    val y: Float,
    val z: Float
)

enum class MsgType(val value: UByte) {
    LocatorCfgChgRequest(1u),   // Request to update locator configuration sent from the app via the receiver.
    ReceiverCfgChgRequest(2u),  // Request to update receiver configuration sent from the app via the receiver.
    ArmRequest(3u),             // Request to arm the locator sent from the app via the receiver.
    DisarmRequest(4u),          // Request to disarm the locator sent from the app via the receiver.
    PreLaunchData(5u),          // Unsolicited locator status sent from the locator while in an unarmed state.
    TelemetryData(6u),          // Unsolicited locator status sent from the locator while in an armed state.
    FlightMetadataRequest(7u),  // Request from the app, via the receiver, for high-level information necessary to identify each flight profile record archived by the locator.
    FlightMetadata(8u),         // Flight profile metadata response from the locator to the app via the receiver.
    FlightDataRequest(9u),      // Request from the app, via the receiver, for the data in one flight profile.
    FlightData(10u),            // Flight profile data response from the locator to the app via the receiver consisting of multiple packets, which the app acknowledges via the receiver.
    FlightDataParity(11u),      // Parity packet to allow the app to reconstruct profile data if one packet is lost.
    FlightDataAck(12u),         // Profile data acknowledgement sent from the app via the receiver.
    DeploymentTestRequest(13u), // Request from the app, via the receiver, for the locator to execute a deployment test.
    DeploymentTest(14u);        // Deployment test countdown sent from the locator to the app via the receiver.

    companion object {
        fun fromUByte(v: UByte) = entries.firstOrNull { it.value == v }
    }
}

enum class SensorHealth(val value: UByte) {
    Off(0u), Initializing(1u), Ok(2u), Warning(3u), Error(4u), Stale(5u);

    companion object {
        fun fromUByte(v: UByte): SensorHealth =
            entries.firstOrNull { it.value == v } ?: Stale
    }}

data class PacketHeader(
    val systemId: UByte,
    val msgType: MsgType,
    val msgCount: UShort,
    val crc: UShort
) {
    companion object {
        val SIZE =
            UByte.SIZE_BYTES +
                    UByte.SIZE_BYTES +
                    UShort.SIZE_BYTES +
                    UShort.SIZE_BYTES
    }
}


fun PacketHeader.toBytes(): ByteArray {
    val out = ByteArray(PacketHeader.SIZE)
    var i = 0

    out[i++] = systemId.toByte()
    out[i++] = msgType.value.toByte()

    out[i++] = (msgCount.toInt() and 0xFF).toByte()
    out[i++] = ((msgCount.toInt() shr 8) and 0xFF).toByte()

    out[i++] = (crc.toInt() and 0xFF).toByte()
    out[i] = ((crc.toInt() shr 8) and 0xFF).toByte()

    return out
}

data class PrelaunchParsed(
    val latitude: Double,
    val longitude: Double,
    val satellites: Int,          // uint8_t
    val hacc: Float,              // float
    val imuStatus: SensorHealth,  // uint8_t enum
    val baroStatus: SensorHealth, // uint8_t enum
    val gpsStatus: SensorHealth,  // uint8_t enum
    val deployStatus: Int,        // uint8_t
    val agl: Float,               // float
    val accel: Vec3f,             // Vec3f (3 x float)
    val gyro: Vec3f,              // Vec3f (3 x float)
    val deployCh1Mode: DeployMode, // uint8_t enum
    val deployCh2Mode: DeployMode, // uint8_t enum
    val deployCh3Mode: DeployMode, // uint8_t enum
    val deployCh4Mode: DeployMode, // uint8_t enum
    val droguePrimaryDelay: Int,  // uint8_t
    val drogueBackupDelay: Int,   // uint8_t
    val mainPrimaryAltitude: Int, // uint16_t
    val mainBackupAltitude: Int,  // uint16_t
    val deviceName: String,       // char[device_name_length]
    val locatorBatteryMv: Int,    // uint16_t
    val receiverChannel: Int,     // uint8_t
    val receiverBatteryMv: Int    // uint16_t
)

data class TelemetryParsed(
    val latitude: Double,
    val longitude: Double,
    val satellites: Int,     // uint8_t
    val hacc: Float,         // float
    val imuStatus: SensorHealth,      // uint8_t enum
    val baroStatus: SensorHealth,     // uint8_t enum
    val gpsStatus: SensorHealth,      // uint8_t enum
    val deploymentCh1Stats: Int,   // uint8_t
    val deploymentCh2Stats: Int,   // uint8_t
    val deploymentCh3Stats: Int,   // uint8_t
    val deploymentCh4Stats: Int,   // uint8_t
    val physicalDeploymentStats: Int,   // uint8_t
    val agl: Float,          // float
    val accel: Vec3f,   // Vec3f
    val gyro: Vec3f,    // Vec3f
    val velocity: Float,     // float
    val flightState: FlightStates     // uint8_t enum
)

data class DeploymentTestParsed(
    val count: Int
)

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

enum class LocatorMessageState (val locatorMessageState: UByte) {
    Idle(0u),
    SendRequested(1u),
    Sent(2u),
    AckUpdated(3u),
    SendFailure(4u),
    NotAcknowledged(5u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.locatorMessageState == value } ?: throw IllegalArgumentException("Invalid type: $value")
    }

}

object BluetoothManagerRepository {
    private val _bluetoothConnectionState = MutableStateFlow<BluetoothConnectionState>(BluetoothConnectionState.Idle)
    val bluetoothConnectionState: StateFlow<BluetoothConnectionState> = _bluetoothConnectionState.asStateFlow()

    private val _locatorDevice = MutableStateFlow<BluetoothDevice?>(null)
    val locatorDevice: StateFlow<BluetoothDevice?> = _locatorDevice.asStateFlow()

    private val _armedState = MutableStateFlow<Boolean>(false)
    val armedState: StateFlow<Boolean> = _armedState.asStateFlow()

    private val _locatorArmedMessageState = MutableStateFlow<LocatorMessageState>(LocatorMessageState.Idle)
    val locatorArmedMessageState: StateFlow<LocatorMessageState> = _locatorArmedMessageState.asStateFlow()

    fun updateBluetoothConnectionState(newBluetoothConnectionState: BluetoothConnectionState) {
        _bluetoothConnectionState.value = newBluetoothConnectionState
    }

    fun updateLocatorDevice(newLocatorDevice: BluetoothDevice?) {
        _locatorDevice.value = newLocatorDevice
    }

    fun updateArmedState(newArmedState: Boolean) {
        _armedState.value = newArmedState
    }

    fun updateLocatorArmedMessageState(newArmedMessageState: LocatorMessageState) {
        _locatorArmedMessageState.value = newArmedMessageState
    }
}