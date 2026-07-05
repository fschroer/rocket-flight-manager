package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.FLIGHT_DATA_ACK_SIZE
import com.steampigeon.flightmanager.data.FLIGHT_METADATA_PAYLOAD_SIZE
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

    // PreLaunchData: C++ sizeof 115 → payload 109 (101 + locator_id 4 + auth_tag 4);
    //                + channel 1 + recv battery 2 + recv name 20 + rssi 2 = 134
    @Test fun prelaunchPayloadSize() = assertEquals(134, Protocol.PRELAUNCH_MESSAGE_PAYLOAD_SIZE)
    @Test fun prelaunchBaseStructSize() = assertEquals(115, Protocol.PRELAUNCH_BASE_STRUCT_SIZE)

    // TelemetryData: C++ sizeof 68 → payload 62; + rssi 2 = 64
    @Test fun telemetryPayloadSize() = assertEquals(64, Protocol.TELEMETRY_MESSAGE_PAYLOAD_SIZE)

    // VersionInfo: locator 64 + receiver 64 = 128
    @Test fun versionInfoPayloadSize() = assertEquals(128, Protocol.VERSION_INFO_PAYLOAD_SIZE)

    // FlightDataAck: C++ sizeof 42 (header 6 + transfer_id 2 + packet_count 2 + bitmap 32)
    @Test fun flightDataAckSize() = assertEquals(42, FLIGHT_DATA_ACK_SIZE)

    // FlightMetadata: 10 records × 10 bytes = 100 payload
    @Test fun flightMetadataPayloadSize() = assertEquals(100, FLIGHT_METADATA_PAYLOAD_SIZE)

    // FlightDataPacket: max LoRa frame = 256 on the app side
    @Test fun maxPacketSize() = assertEquals(256, Protocol.MAX_PACKET_SIZE)
}
