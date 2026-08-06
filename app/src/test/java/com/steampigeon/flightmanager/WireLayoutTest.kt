package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.FLIGHT_DATA_ACK_SIZE
import com.steampigeon.flightmanager.data.FLIGHT_METADATA_PAYLOAD_SIZE
import com.steampigeon.flightmanager.data.FlightEventIndex
import com.steampigeon.flightmanager.data.LinkQuality
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
    // rssi 2 + snr 1 + noise_floor 2. The receiver pins the extended struct sizes
    // (143 / 81) with its own static_asserts.
    private val linkTrailer = 5

    // PreLaunchData: C++ sizeof 115 → payload 109 (101 + locator_id 4 + auth_tag 4);
    //                + channel 1 + recv battery 2 + recv name 20 + link trailer 5 = 137
    @Test fun prelaunchPayloadSize() = assertEquals(137, Protocol.PRELAUNCH_MESSAGE_PAYLOAD_SIZE)
    @Test fun prelaunchBaseStructSize() = assertEquals(115, Protocol.PRELAUNCH_BASE_STRUCT_SIZE)

    // TelemetryData: C++ sizeof 76 → payload 70 (62 + locator_id 4 + auth_tag 4);
    //                + link trailer 5 = 75
    @Test fun telemetryPayloadSize() = assertEquals(75, Protocol.TELEMETRY_MESSAGE_PAYLOAD_SIZE)
    @Test fun telemetryBaseStructSize() = assertEquals(76, Protocol.TELEMETRY_BASE_STRUCT_SIZE)

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

    // VersionInfo: locator 64 + receiver 64 = 128
    @Test fun versionInfoPayloadSize() = assertEquals(128, Protocol.VERSION_INFO_PAYLOAD_SIZE)

    // ChannelSurveyResponse: C++ sizeof 73 → payload 67
    // (status 1 + channel_count 1 + home_channel 1 + level[64]).
    @Test fun channelSurveyPayloadSize() = assertEquals(67, Protocol.CHANNEL_SURVEY_PAYLOAD_SIZE)
    @Test fun surveyChannelCount() = assertEquals(64, Protocol.SURVEY_CHANNEL_COUNT)
    @Test fun channelSurveyPayloadIsCountPlusThreeHeaderBytes() =
        assertEquals(
            Protocol.CHANNEL_SURVEY_PAYLOAD_SIZE,
            Protocol.SURVEY_CHANNEL_COUNT + 3,
        )

    // FlightDataAck: C++ sizeof 42 (header 6 + transfer_id 2 + packet_count 2 + bitmap 32)
    @Test fun flightDataAckSize() = assertEquals(42, FLIGHT_DATA_ACK_SIZE)

    // FlightMetadata: 10 records × 10 bytes = 100 payload
    @Test fun flightMetadataPayloadSize() = assertEquals(100, FLIGHT_METADATA_PAYLOAD_SIZE)

    // FlightEvents: C++ sizeof 66 → payload 60 (record 1 + reserved 1 +
    // present_mask 2 + flight_timestamp_s 4 + event_timestamp_ms[11] 44 +
    // max_altitude_m 4 + deployment_ch_stats[4] 4)
    @Test fun flightEventsPayloadSize() = assertEquals(60, Protocol.FLIGHT_EVENTS_PAYLOAD_SIZE)

    // The event count is baked into the payload size above and into the wire
    // order shared with the firmware's Communication::FlightEvent enum.
    @Test fun flightEventCount() = assertEquals(11, FlightEventIndex.entries.size)

    // FlightDataPacket: max LoRa frame = 256 on the app side
    @Test fun maxPacketSize() = assertEquals(256, Protocol.MAX_PACKET_SIZE)
}
