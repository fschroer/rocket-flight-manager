package com.steampigeon.flightmanager.data

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.atan2

object Protocol {
    const val HEADER_SIZE =
        UByte.SIZE_BYTES +   // systemId
                UByte.SIZE_BYTES +   // msgType
                UShort.SIZE_BYTES +  // msgCount
                UShort.SIZE_BYTES    // crc

    const val MESSAGE_BUFFER_SIZE = 52 * 256 // Up to 46 packets during ascent, 6 packets during descent * maximum message size
    const val SYSTEM_ID : Byte = 0x44
    const val PRELAUNCH_MESSAGE_PAYLOAD_SIZE = 141 // PreLaunchData payload (112 = 101 + nose_axis 1 + armed 1 + pad_alert 1 + locator_id 4 + auth_tag 4) + channel (1) + receiver battery level (2) + receiver name (20) + rssi (2) + snr (1) + noise floor (2) + bad frames (1) = 141
    // On-wire size of the locator's PreLaunchData struct (header 6 + payload 112).
    // The password auth_tag is computed over exactly these bytes (with crc and
    // auth_tag zeroed) — receiver-appended metadata sits after and is excluded.
    // The armed byte is inside this region, so it is authenticated (ADR-0021 #35).
    const val PRELAUNCH_BASE_STRUCT_SIZE = 118
    const val TELEMETRY_MESSAGE_PAYLOAD_SIZE = 77 // TelemetryData payload (71 = 62 + armed 1 + locator_id 4 + auth_tag 4) + rssi (2) + snr (1) + noise floor (2) + bad frames (1) = 77
    // On-wire size of the locator's TelemetryData struct (header 6 + payload 71).
    // The auth_tag is computed over exactly these bytes (with crc and auth_tag
    // zeroed); the receiver-appended RSSI/SNR/noise floor sit after and are excluded.
    const val TELEMETRY_BASE_STRUCT_SIZE = 77
    // ChannelSurveyResponse: C++ sizeof 84 → payload 78 (status 1 + channel_count 1
    // + home_channel 1 + level[64] + confirmed_count 1 + confirmed_channel[5]
    // + confirmed_frames[5]).
    // Receiver-only message; the locator reserves the MsgType values but never sends it.
    const val CHANNEL_SURVEY_PAYLOAD_SIZE = 78
    const val SURVEY_CHANNEL_COUNT = 64
    const val SURVEY_CONFIRM_COUNT = 5
    const val RECEIVER_CONFIG_PAYLOAD_MESSAGE_SIZE = 1
    const val RECEIVER_INFO_PAYLOAD_SIZE = 21 // channel (1) + name (20)
    const val VERSION_INFO_PAYLOAD_SIZE = 128 // locator version (64) + receiver version (64)
    const val FLIGHT_PROFILE_METADATA_PAYLOAD_MESSAGE_SIZE = 128
    // FlightEvents payload: record (1) + reserved (1) + present_mask (2) +
    // flight_timestamp_s (4) + event_timestamp_ms[11] (44) + max_altitude_m (4)
    // + deployment_ch_stats[4] (4) = 60.  On-wire struct is 66 with the header.
    const val FLIGHT_EVENTS_PAYLOAD_SIZE = 60
    const val FLIGHT_PROFILE_DATA_PAYLOAD_MESSAGE_SIZE = 241
    const val DEPLOYMENT_TEST_MESSAGE_PAYLOAD_SIZE = 1
    const val MAX_PACKET_SIZE = 256
    const val DEVICE_NAME_LENGTH = 20
}

/**
 * Data class that represents the current rocket locator state]
 */
data class RocketState(
    /**
     * When the last message of ANY kind arrived — pre-launch or telemetry.
     * Link liveness, which is what nearly everything asking "are we still
     * hearing the rocket" wants.
     *
     * It is NOT the right clock for a field only one message type carries.  See
     * [lastPreLaunchDataTime].
     *
     * Was `lastPreLaunchMessageTime` until 2026-08-08, which read as a promise
     * it never kept: the telemetry handler stamps it too.  The name is the whole
     * reason the battery icons stayed lit through a flight.
     */
    val lastMessageTime: Long = 0,
    /**
     * When the last **pre-launch** message arrived, and nothing else.
     *
     * Battery levels ride only on that message — the locator stops sending them
     * the moment it switches to telemetry — so anything read out of a pre-launch
     * payload must be aged against this rather than [lastMessageTime].
     */
    val lastPreLaunchDataTime: Long = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val rawLatitude: Double = 0.0,
    val rawLongitude: Double = 0.0,
    val satellites: UByte = 0.toUByte(),
    val hacc: Float = 0f,
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
    val flightState: FlightStates = FlightStates.WaitingLaunch,
    val rssi: Int = -120,
    // Link quality / interference (ADR-0019). snr and noiseFloor are the raw
    // receiver measurements; linkQuality is the classified verdict.
    val snr: Int = 0,
    val noiseFloor: Int = LinkQuality.NOISE_FLOOR_UNKNOWN,
    val linkQuality: LinkQuality.Verdict = LinkQuality.Verdict.Normal,
    val velNed: Vec3f = Vec3f(0f, 0f, 0f),
    val attitude: Quaternionf = Quaternionf.IDENTITY,
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
    // Which raw sensor axis points at the rocket's nose (ADR-0021 Decision 6, #36).
    // Last on the wire, after deviceName, matching the firmware struct.
    val noseAxis: NoseAxis = NoseAxis.Auto,
)

/**
 * Which raw sensor axis the rocket's long axis lies along — how the locator is
 * physically mounted in the airframe (ADR-0021 Decision 6, #36).
 *
 * The locator cannot infer the AXIS: mounting calibration finds which axis
 * gravity lies along and calls it "up", which is only the rocket axis if the
 * rocket is vertical at the time. Stating it makes tilt-from-vertical
 * measurable whenever the locator is powered, which is what the disarmed-rocket
 * alert (#37) needs.
 *
 * Deliberately UNSIGNED. The sign is measurable — calibration only commits with
 * the rocket vertical, where gravity along the stated axis is a full ±1 g — so
 * asking the operator which way up the locator is bolted would be asking them
 * to supply something the firmware can read, and to get it right. Both
 * polarities count as vertical for the alert.
 *
 * Auto keeps the pre-#36 detect-on-arm behavior. Values mirror the firmware
 * NoseAxis enum — keep in sync.
 */
enum class NoseAxis(val value: UByte) {
    Auto(0u),
    X(1u),
    Y(2u),
    Z(3u);

    companion object {
        fun fromUByte(v: UByte): NoseAxis = entries.firstOrNull { it.value == v } ?: Auto
    }
}

/**
 * The locator's prepped-and-disarmed verdict (ADR-0021 Decision 5, #37).
 *
 * [Snoozed] is reported distinctly rather than collapsed into [Quiet] on
 * purpose: a silenced locator that looks identical to a healthy one is the
 * exact failure this whole ADR started from. The operator should be able to see
 * that the system is still watching and merely holding its tongue.
 */
enum class PadAlertState {
    Quiet,
    Alerting,
    Snoozed;

    companion object {
        /**
         * Wire encoding: 0 quiet, 1 alerting, 2+n snoozed with n minutes left.
         *
         * Anything unrecognized resolves to a warning, never to [Quiet] — a
         * value this build cannot interpret must not present as "nothing wrong".
         */
        fun fromUByte(v: UByte): PadAlertState = when (v.toInt()) {
            0 -> Quiet
            1 -> Alerting
            else -> Snoozed
        }

        /** Minutes of snooze left, 0 unless the byte encodes a snooze. */
        fun snoozeMinutesFromUByte(v: UByte): Int =
            if (v.toInt() >= SNOOZE_BASE) v.toInt() - SNOOZE_BASE else 0

        private const val SNOOZE_BASE = 2

        /** Ceiling the locator clamps total remaining snooze to. */
        const val SNOOZE_CEILING_MINUTES = 15

        /** Added per tap; the locator accumulates and clamps to the ceiling. */
        const val SNOOZE_STEP_MINUTES = 5
    }
}

data class ReceiverConfig(
    val channel: Int = 0,
    val deviceName: String = "",
)

data class FlightProfileMetadata(
    val position: Int,
    val date: ZonedDateTime?,
    val apogee: Float,
    val timeToDrogue: Float,
)

/**
 * Flight events the locator records per archived flight, in the wire order of
 * FlightEventsMessage.event_timestamp_ms.  MUST match the firmware's
 * Communication::FlightEvent enum (MessageProtocol.hpp) on both the locator and
 * the receiver.
 */
enum class FlightEventIndex(val label: String) {
    Launch("Launch"),
    Burnout("Burnout"),
    Apogee("Apogee"),
    Noseover("Noseover"),
    DroguePrimaryDeploy("Drogue Primary"),
    DrogueBackupDeploy("Drogue Backup"),
    DrogueVelocityThreshold("Drogue Deploy"),
    MainPrimaryDeploy("Main Primary"),
    MainBackupDeploy("Main Backup"),
    MainVelocityThreshold("Main Deploy"),
    Landing("Landing");
}

/** Decoded per-channel deployment stat byte from a FlightEvents message. */
data class DeployChannelStats(
    val mode: DeployMode = DeployMode.Unused,
    val fired: Boolean = false,
    val preFireContinuity: Boolean = false,
    val postFireContinuity: Boolean = false,
) {
    companion object {
        // Bit layout mirrors the locator's Constants.hpp bit_shift_* values.
        fun fromByte(raw: Int) = DeployChannelStats(
            mode               = DeployMode.fromUByte((raw and 0x07).toUByte()),
            fired              = raw and (1 shl 3) != 0,
            preFireContinuity  = raw and (1 shl 4) != 0,
            postFireContinuity = raw and (1 shl 5) != 0,
        )
    }
}

/**
 * Per-record flight event summary (MsgType.FlightEvents), sent by the locator
 * alongside the flight profile data for the record being viewed.
 *
 * Only event *times* cross the wire.  Altitudes are resolved by the chart
 * against the profile samples for the same record — see [resolveEvents] — so a
 * marker always sits exactly on the plotted trace.
 */
data class FlightEvents(
    val record: Int = -1,
    val presentMask: Int = 0,
    val flightTimestampS: Long = 0L,
    val eventTimestampMs: List<Long> = emptyList(),
    val maxAltitudeM: Float = 0f,
    val channelStats: List<DeployChannelStats> = emptyList(),
) {
    /** Launch wall-clock time, or null if the locator had no GPS fix. */
    val launchDate: ZonedDateTime?
        get() = if (flightTimestampS > 0L)
            java.time.Instant.ofEpochSecond(flightTimestampS)
                .atZone(java.time.ZoneId.systemDefault())
        else null

    /** Timestamp for [event], or null when the locator did not record it. */
    fun timestampMs(event: FlightEventIndex): Long? =
        if (presentMask and (1 shl event.ordinal) != 0)
            eventTimestampMs.getOrNull(event.ordinal)
        else null

    /** The channel assigned to [mode], or null if no channel was configured for it. */
    fun channelFor(mode: DeployMode): Int? =
        channelStats.indexOfFirst { it.mode == mode }.takeIf { it >= 0 }?.plus(1)

    val isEmpty: Boolean get() = record < 0 || presentMask == 0

    companion object {
        /**
         * Decode a FlightEvents frame (MsgType 19).  Field order and offsets
         * mirror the firmware's Communication::FlightEventsMessage exactly; the
         * total is pinned by Protocol.FLIGHT_EVENTS_PAYLOAD_SIZE and
         * WireLayoutTest.
         *
         * Returns null if the frame is short — a truncated frame would
         * otherwise decode as garbage event times and scatter markers across
         * the chart.
         */
        fun parse(frame: ByteArray): FlightEvents? {
            val minSize = Protocol.HEADER_SIZE + Protocol.FLIGHT_EVENTS_PAYLOAD_SIZE
            if (frame.size < minSize) return null

            var o = Protocol.HEADER_SIZE

            val record      = frame[o].toInt() and 0xFF;  o += 1
            o += 1                                        // reserved
            val presentMask = le16(frame, o);             o += 2
            val flightTsS   = le32(frame, o);             o += 4

            val timestamps = List(FlightEventIndex.entries.size) { le32(frame, o + it * 4) }
            o += FlightEventIndex.entries.size * 4

            val maxAltitude = java.nio.ByteBuffer.wrap(frame, o, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).float
            o += 4
            val channelStats = List(4) { DeployChannelStats.fromByte(frame[o + it].toInt() and 0xFF) }

            return FlightEvents(
                record           = record,
                presentMask      = presentMask,
                flightTimestampS = flightTsS,
                eventTimestampMs = timestamps,
                maxAltitudeM     = maxAltitude,
                channelStats     = channelStats,
            )
        }

        private fun le16(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        private fun le32(b: ByteArray, o: Int): Long =
            (b[o].toLong() and 0xFF) or
                    ((b[o + 1].toLong() and 0xFF) shl 8) or
                    ((b[o + 2].toLong() and 0xFF) shl 16) or
                    ((b[o + 3].toLong() and 0xFF) shl 24)
    }
}

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
    WaitingLaunch(0u),
    Launched(1u),
    Burnout(2u),
    Noseover(3u),
    DroguePrimaryEvent(4u),
    DrogueBackupEvent(5u),
    MainPrimaryEvent(6u),
    MainBackupEvent(7u),
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

data class Quaternionf(
    val w: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
) {
    // Rotate the body +x axis (rocket nose) into NED — first column of R(q)
    fun noseNED(): Triple<Float, Float, Float> {
        val n = 1f - 2f * (y * y + z * z)
        val e = 2f * (x * y + w * z)
        val d = 2f * (x * z - w * y)
        return Triple(n, e, d)
    }

    // Angle from vertical in degrees (0 = straight up, 90 = horizontal)
    fun inclinationDeg(): Float {
        val (_, _, d) = noseNED()
        return Math.toDegrees(acos((-d).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    // Compass bearing of the nose projected onto the horizontal plane (0–360°)
    fun headingDeg(): Float {
        val (n, e, _) = noseNED()
        val h = Math.toDegrees(atan2(e.toDouble(), n.toDouble())).toFloat()
        return if (h < 0f) h + 360f else h
    }

    // Total speed magnitude from NED velocity vector
    companion object {
        val IDENTITY = Quaternionf(1f, 0f, 0f, 0f)
    }
}

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
    FlightDataAck(12u),         // Profile data acknowledgment sent from the app via the receiver.
    DeploymentTestRequest(13u), // Request from the app, via the receiver, for the locator to execute a deployment test.
    DeploymentTest(14u),        // Deployment test countdown sent from the locator to the app via the receiver.
    ReceiverInfoRequest(15u),   // Request from the app to the receiver for its current channel and name.
    ReceiverInfo(16u),          // Response from the receiver with its current LoRa channel and device name.
    VersionRequest(17u),        // Request from the app, via the receiver, for both firmware versions.
    VersionInfo(18u),           // Response: locator version forwarded through receiver, which appends its own version.
    FlightEvents(19u),          // Per-record flight event summary sent alongside a FlightData transfer.
    ChannelSurveyRequest(20u),  // Request from the app to the receiver to sweep the band (no locator involved).
    ChannelSurvey(21u),         // Response from the receiver with per-channel occupancy.
    PadAlertSnoozeRequest(22u); // App→locator: suppress the prepped-and-disarmed alert for N minutes (#37).

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
    val rawLatitude: Double,      // raw SAM-M10Q latitude (deg)
    val rawLongitude: Double,     // raw SAM-M10Q longitude (deg)
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
    val noseAxis: NoseAxis,       // uint8_t enum — mounting config (ADR-0021, #36)
    val armed: Boolean,           // uint8_t — stated arm state (ADR-0021, #35)
    // uint8_t — locator's prepped-and-disarmed verdict (ADR-0021 Decision 5, #37).
    // A verdict, not raw inputs: the locator judges it at 20 Hz, this message
    // arrives at 1 Hz, and re-deriving quiescence from that would be guesswork.
    val padAlert: PadAlertState,
    val padAlertSnoozeMinutes: Int,   // uint8_t, decoded from the same byte
    val locatorId: Long,          // uint32_t — cleartext STM MPU UID
    val authTag: Long,            // uint32_t — password-seeded checksum from the locator
    val receiverChannel: Int,     // uint8_t
    val receiverBatteryMv: Int,   // uint16_t
    val receiverName: String,     // char[device_name_length]
    val rssi: Int,                // int16_t
    val snr: Int,                 // int8_t  — LoRa SNR of this packet, dB (ADR-0019)
    val noiseFloor: Int,          // int16_t — peak idle-channel RSSI, dBm
    val badFrames: Int,           // uint8_t — frames that arrived and failed to parse
)

data class TelemetryParsed(
    val latitude: Double,
    val longitude: Double,
    val satellites: Int,              // uint8_t
    val hacc: Float,                  // float
    val imuStatus: SensorHealth,      // uint8_t enum
    val baroStatus: SensorHealth,     // uint8_t enum
    val gpsStatus: SensorHealth,      // uint8_t enum
    val deploymentCh1Stats: Int,      // uint8_t
    val deploymentCh2Stats: Int,      // uint8_t
    val deploymentCh3Stats: Int,      // uint8_t
    val deploymentCh4Stats: Int,      // uint8_t
    val physicalDeploymentStats: Int, // uint8_t
    val agl: Float,                   // float
    val velNed: Vec3f,                // Vec3f — fused NED velocity m/s
    val attitude: Quaternionf,        // Quaternionf — body-to-NED quaternion
    val flightState: FlightStates,    // uint8_t enum
    val armed: Boolean,               // uint8_t — stated arm state (ADR-0021, #35)
    val locatorId: Long,              // uint32_t — cleartext STM MPU UID
    val authTag: Long,                // uint32_t — password-seeded checksum from the locator
    val rssi: Int,                    // int16_t
    val snr: Int,                     // int8_t  — LoRa SNR of this packet, dB (ADR-0019)
    val noiseFloor: Int,              // int16_t — peak idle-channel RSSI, dBm
    val badFrames: Int,               // uint8_t — frames that arrived and failed to parse
)

data class DeploymentTestParsed(
    val count: Int
)

data class ReceiverInfoParsed(
    val channel: Int,     // uint8_t
    val deviceName: String // char[device_name_length]
)

data class VersionInfoParsed(
    val locatorVersion: String,  // uint8_t[64] null-terminated string
    val receiverVersion: String  // uint8_t[64] null-terminated string
)

enum class BluetoothConnectionState (val bluetoothConnectionState: UByte) {
    Idle(0u),
    Starting(1u),
    Enabling(2u),
    NotEnabled(3u),
    NotSupported(4u),
    Enabled(5u),
    AssociateStart(6u),
    DevicesFound(7u),
    NoDevicesAvailable(8u),
    PairingFailed(9u),
    Connected(10u),
    Ready(11u),
    Disconnected(12u),
    LocationDisabled(13u);

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

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices.asStateFlow()

    private val _receiverDevice = MutableStateFlow<BluetoothDevice?>(null)
    val receiverDevice: StateFlow<BluetoothDevice?> = _receiverDevice.asStateFlow()

    private val _armedState = MutableStateFlow<Boolean>(false)
    val armedState: StateFlow<Boolean> = _armedState.asStateFlow()

    // The locator's prepped-and-disarmed verdict (ADR-0021 Decision 5, #37).
    // Set only from the connected locator's broadcast, like armedState — a
    // bystander locator on the channel must not be able to raise this.
    private val _padAlert = MutableStateFlow(PadAlertState.Quiet)
    val padAlert: StateFlow<PadAlertState> = _padAlert.asStateFlow()

    // Minutes of snooze the locator reports remaining. Read back rather than
    // tracked locally so repeated taps show real accumulated time, and so an
    // app restart mid-snooze still shows the truth.
    private val _padAlertSnoozeMinutes = MutableStateFlow(0)
    val padAlertSnoozeMinutes: StateFlow<Int> = _padAlertSnoozeMinutes.asStateFlow()

    fun updatePadAlertSnoozeMinutes(minutes: Int) {
        _padAlertSnoozeMinutes.value = minutes
    }

    private val _locatorArmedMessageState = MutableStateFlow<LocatorMessageState>(LocatorMessageState.Idle)
    val locatorArmedMessageState: StateFlow<LocatorMessageState> = _locatorArmedMessageState.asStateFlow()

    fun updateBluetoothConnectionState(newBluetoothConnectionState: BluetoothConnectionState) {
        _bluetoothConnectionState.value = newBluetoothConnectionState
    }

    fun updateScannedDevices(devices: List<BluetoothDevice>) {
        _scannedDevices.value = devices
    }

    fun updateReceiverDevice(newReceiverDevice: BluetoothDevice?) {
        _receiverDevice.value = newReceiverDevice
    }

    // Pending snooze request, in minutes; drained by BluetoothService on the next
    // inbound packet the same way an arm request is (#37).  The UI cannot send
    // directly — only the service holds the transport.
    private val _padAlertSnoozeRequest = MutableStateFlow(0)
    val padAlertSnoozeRequest: StateFlow<Int> = _padAlertSnoozeRequest.asStateFlow()

    fun requestPadAlertSnooze(minutes: Int) {
        _padAlertSnoozeRequest.value = minutes
    }

    fun clearPadAlertSnoozeRequest() {
        _padAlertSnoozeRequest.value = 0
    }

    fun updatePadAlert(newPadAlert: PadAlertState) {
        _padAlert.value = newPadAlert
    }

    fun updateArmedState(newArmedState: Boolean) {
        _armedState.value = newArmedState
    }

    fun updateLocatorArmedMessageState(newArmedMessageState: LocatorMessageState) {
        _locatorArmedMessageState.value = newArmedMessageState
    }
}