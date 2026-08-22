package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.FLIGHT_DATA_ACK_SIZE
import com.steampigeon.flightmanager.data.FLIGHT_METADATA_PAYLOAD_SIZE
import com.steampigeon.flightmanager.data.FlightEventIndex
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.LinkQuality
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.LocatorConfigWire
import com.steampigeon.flightmanager.data.NoseAxis
import com.steampigeon.flightmanager.data.Protocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wire-layout cross-check (issue #4).
 *
 * The expected values below MUST equal the C++ `static_assert`s in
 * MessageProtocol.hpp / FlightProfileCodec.hpp. If a firmware struct changes, its
 * static_assert fails the firmware build; update the literal there AND the matching
 * value here. If an app constant drifts without updating the firmware, this test
 * fails. Together the two sides keep the hand-written wire format in sync.
 *
 *   app payload size = sizeof(C++ struct) − header (6) [+ receiver-appended bytes]
 */
class WireLayoutTest {

    @Test fun headerSize() = assertEquals(6, Protocol.HEADER_SIZE)

    // Receiver-appended link-quality trailer, present on both broadcasts (ADR-0019):
    // rssi 2 + snr 1 + noise_floor 2 + bad_frames 1. The receiver pins the extended
    // struct sizes (147 / 83) with its own static_asserts.
    private val linkTrailer = 6

    // PreLaunchData: C++ sizeof 118 → payload 112 (101 + nose_axis 1 + armed 1
    //                + pad_alert 1 + locator_id 4 + auth_tag 4);
    //                + channel 1 + recv battery 2 + recv name 20 + link trailer 6 = 141
    @Test fun prelaunchPayloadSize() = assertEquals(141, Protocol.PRELAUNCH_MESSAGE_PAYLOAD_SIZE)
    @Test fun prelaunchBaseStructSize() = assertEquals(118, Protocol.PRELAUNCH_BASE_STRUCT_SIZE)

    // TelemetryData: C++ sizeof 77 → payload 71 (62 + armed 1 + locator_id 4 + auth_tag 4);
    //                + link trailer 6 = 77
    @Test fun telemetryPayloadSize() = assertEquals(77, Protocol.TELEMETRY_MESSAGE_PAYLOAD_SIZE)
    @Test fun telemetryBaseStructSize() = assertEquals(77, Protocol.TELEMETRY_BASE_STRUCT_SIZE)

    // Both authenticated broadcasts put auth_tag last, and LocatorAuth locates it
    // by offset from the end of the base struct — so the base size must be exactly
    // the payload minus whatever the receiver appends after it. Getting this wrong
    // does not fail to parse; it silently authenticates the wrong bytes.
    @Test fun telemetryBaseIsPayloadLessAppendedTrailer() =
        assertEquals(
            Protocol.TELEMETRY_BASE_STRUCT_SIZE,
            Protocol.HEADER_SIZE + Protocol.TELEMETRY_MESSAGE_PAYLOAD_SIZE - linkTrailer,
        )

    // noise_floor is an int16_t, so the firmware's kNoiseFloorUnknown (INT16_MIN)
    // arrives as -32768. Comparing it against Kotlin's Int.MIN_VALUE silently never
    // matched, so "no sample" was read as a real floor and poisoned the baseline.
    @Test fun noiseFloorUnknownMatchesInt16Min() =
        assertEquals(Short.MIN_VALUE.toInt(), LinkQuality.NOISE_FLOOR_UNKNOWN)

    // ReceiverInfo: channel 1 + name 20 + noise_floor 2 + bad_frames 1 = 24
    // (C++ sizeof(ReceiverInfoMessage) 30, asserted in the receiver's
    // MessageProtocol.hpp).  The trailing channel status is the only noise-floor
    // reading that reaches the app without a locator broadcast to ride on.
    //
    // The app frames this message by exact length BEFORE checking its CRC, so a
    // drift desynchronises the framer instead of failing a check: it waits for
    // bytes that never come, the health probe goes unanswered, and the watchdog
    // declares a phantom connection and reconnects in a loop.
    @Test fun receiverInfoPayloadSize() = assertEquals(24, Protocol.RECEIVER_INFO_PAYLOAD_SIZE)
    @Test fun receiverInfoPayloadIsItsParts() =
        assertEquals(
            Protocol.RECEIVER_INFO_PAYLOAD_SIZE,
            1 + Protocol.DEVICE_NAME_LENGTH + 2 + 1,
        )

    // VersionInfo: locator 64 + receiver 64 = 128
    @Test fun versionInfoPayloadSize() = assertEquals(128, Protocol.VERSION_INFO_PAYLOAD_SIZE)

    // ChannelSurveyResponse: C++ sizeof 84 → payload 78 (status 1 + channel_count 1
    // + home_channel 1 + level[64] + confirmed_count 1 + confirmed_channel[5]
    // + confirmed_frames[5]).
    @Test fun channelSurveyPayloadSize() = assertEquals(78, Protocol.CHANNEL_SURVEY_PAYLOAD_SIZE)
    @Test fun surveyChannelCount() = assertEquals(64, Protocol.SURVEY_CHANNEL_COUNT)
    @Test fun surveyConfirmCount() = assertEquals(5, Protocol.SURVEY_CONFIRM_COUNT)
    @Test fun channelSurveyPayloadIsItsParts() =
        assertEquals(
            Protocol.CHANNEL_SURVEY_PAYLOAD_SIZE,
            3 + Protocol.SURVEY_CHANNEL_COUNT + 1 + 2 * Protocol.SURVEY_CONFIRM_COUNT,
        )

    // ── Addressed app→locator commands (ADR-0020) ───────────────────────────────
    // Every command carries target_locator_id right after the header. The locator
    // discards anything not addressed to its UID, so a size mismatch here does not
    // merely garble a command — it makes every command silently do nothing.
    private val targetIdSize = 4

    // FlightDataAck: buildAck still produces the 42-byte body (header 6 +
    // transfer_id 2 + packet_count 2 + bitmap 32); sendFlightDataAck splices the
    // target in, so the C++ sizeof is 46.
    @Test fun flightDataAckSize() = assertEquals(42, FLIGHT_DATA_ACK_SIZE)
    @Test fun flightDataAckOnWireCarriesTheTarget() =
        assertEquals(46, FLIGHT_DATA_ACK_SIZE + targetIdSize)

    // Formerly header-only, now header + target: C++ sizeof(TargetedRequest) == 10.
    // Covers ArmRequest, DisarmRequest, FlightMetadataRequest, VersionRequest.
    @Test fun targetedRequestSize() = assertEquals(10, Protocol.HEADER_SIZE + targetIdSize)

    // FlightDataRequest and DeploymentTestRequest: header + target + one byte == 11.
    @Test fun onePayloadByteCommandSize() = assertEquals(11, Protocol.HEADER_SIZE + targetIdSize + 1)

    // FlightMetadata: 9 records × 10 bytes = 90 payload (C++ sizeof 96).
    // 9, was 10, since locator ARCHIVE_VERSION 6 (#38) — see METADATA_RECORD_COUNT.
    @Test fun flightMetadataPayloadSize() = assertEquals(90, FLIGHT_METADATA_PAYLOAD_SIZE)

    // FlightEvents: C++ sizeof 66 → payload 60 (record 1 + reserved 1 +
    // present_mask 2 + flight_timestamp_s 4 + event_timestamp_ms[11] 44 +
    // max_altitude_m 4 + deployment_ch_stats[4] 4)
    @Test fun flightEventsPayloadSize() = assertEquals(60, Protocol.FLIGHT_EVENTS_PAYLOAD_SIZE)

    // The event count is baked into the payload size above and into the wire
    // order shared with the firmware's Communication::FlightEvent enum.
    @Test fun flightEventCount() = assertEquals(11, FlightEventIndex.entries.size)

    // FlightDataPacket: max LoRa frame = 256 on the app side
    @Test fun maxPacketSize() = assertEquals(256, Protocol.MAX_PACKET_SIZE)

    // ---------------------------------------------------------------------
    //  LocatorCfgChgRequest body — the one message the app builds byte by byte
    // ---------------------------------------------------------------------
    //
    // C++ sizeof(LocatorSettings) == 45 = header 6 + target 4 +
    // sizeof(RocketPersistentSettings) 35. The receiver ALSO pins 45, on its own
    // LocatorRocketSettings, and length-validates the relay against it — a drift
    // silently drops every config change the app sends, which is
    // indistinguishable from the command never arriving.

    private val cfg = LocatorConfig(
        deploymentChannel1Mode = DeployMode.DroguePrimary,
        deploymentChannel2Mode = DeployMode.DrogueBackup,
        deploymentChannel3Mode = DeployMode.MainPrimary,
        deploymentChannel4Mode = DeployMode.MainBackup,
        droguePrimaryDeployDelay = 3,
        drogueBackupDeployDelay = 20,
        mainPrimaryDeployAltitude = 130,
        mainBackupDeployAltitude = 100,
        loraChannel = 7,
        deviceName = "Pigeon 1",
        noseAxis = NoseAxis.X,
    )

    @Test fun locatorConfigBodySize() =
        assertEquals(35, LocatorConfigWire.payload(cfg).size)

    @Test fun locatorConfigOnWireMatchesTheFirmwareStruct() =
        assertEquals(
            45,
            Protocol.HEADER_SIZE + targetIdSize + LocatorConfigWire.payload(cfg).size,
        )

    // The receiver reads this ONE byte out of the relayed frame, by offsetof, to
    // follow the locator onto a new channel (ADR-0011 invariant 5). If it moves,
    // the receiver retunes to whatever byte now sits there and the link splits —
    // with the locator out of reach by definition.
    @Test fun loraChannelSitsWhereTheReceiverReadsIt() =
        assertEquals(7, LocatorConfigWire.payload(cfg)[LocatorConfigWire.LORA_CHANNEL_OFFSET].toInt())

    // launch_detect_altitude (u16 @4) and deploy_signal_duration (u8 @12) are
    // reserved: the app does not set them and the locator keeps its own. They
    // carry the firmware defaults rather than zeros, because a locator running
    // firmware from before that change still adopts whatever arrives here, and
    // for it zero means launch detected at 0 m AGL and a pyro signal held for 0 s.
    @Test fun reservedSlotsCarryTheFirmwareDefaultsNotZero() {
        val body = LocatorConfigWire.payload(cfg)
        assertEquals(30, (body[4].toInt() and 0xFF) or ((body[5].toInt() and 0xFF) shl 8))
        assertEquals(10, body[12].toInt())
    }

    // The whole body, so any field moving is caught rather than only the two
    // offsets named above.
    @Test fun locatorConfigBodyLayout() {
        val body = LocatorConfigWire.payload(cfg)
        val expected = intArrayOf(
            0, 1, 2, 3,        // deployment_ch1..4_mode
            30, 0,             // launch_detect_altitude — reserved (u16 le)
            3,                 // drogue_primary_deploy_delay
            20,                // drogue_backup_deploy_delay
            130, 0,            // main_primary_deploy_altitude (u16 le)
            100, 0,            // main_backup_deploy_altitude (u16 le)
            10,                // deploy_signal_duration — reserved
            7,                 // lora_channel
        ) + "Pigeon 1".map { it.code }.toIntArray() + IntArray(12) +  // device_name[20]
            intArrayOf(1)      // nose_axis = X
        assertEquals(35, expected.size)
        assertEquals(
            expected.joinToString(",") { it.toString() },
            body.joinToString(",") { (it.toInt() and 0xFF).toString() },
        )
    }
}
