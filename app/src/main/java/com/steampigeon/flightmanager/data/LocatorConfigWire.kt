package com.steampigeon.flightmanager.data

/**
 * The `LocatorCfgChgRequest` body — the 35 bytes of `RocketPersistentSettings`
 * that follow the header and the target locator id.
 *
 * Hand-synced with two firmware structs (`RocketSettings.hpp` in both the Locator
 * and Receiver repos) and pinned by `WireLayoutTest`. Extracted from
 * `BluetoothService` so the layout can be tested at all: a drift here does not
 * fail to build, it silently writes the wrong field on a locator.
 *
 * **Two of these fields are reserved.** `launch_detect_altitude` and
 * `deploy_signal_duration` still occupy their slots — removing them would move
 * `lora_channel`, which the receiver reads by `offsetof` to follow a channel
 * change (ADR-0011) — but the locator no longer adopts either. It keeps its own,
 * so a config change from the app no longer writes a launch threshold and a pyro
 * firing time that the app invented and could never read back.
 */
object LocatorConfigWire {

    /** Bytes of `RocketPersistentSettings`, i.e. `sizeof(LocatorSettings) - 6 - 4`. */
    const val PAYLOAD_SIZE = 35

    /**
     * Filler for the two reserved slots: the firmware's own defaults, and byte for
     * byte what the app already sent before it stopped setting them.
     *
     * Deliberately NOT zero. The wire layout is unchanged, so a locator running
     * firmware from before this change still adopts whatever arrives here — and
     * for it, zero means launch detected at 0 m AGL (true on the pad) and a pyro
     * signal held for 0 s (a charge that never fires). The defaults leave such a
     * locator exactly where it is today, which is what makes this change safe to
     * ship to the app before the locator is reflashed. They can become zeros once
     * no locator predating this change is in service.
     */
    const val RESERVED_LAUNCH_DETECT_ALTITUDE_M = 30
    const val RESERVED_DEPLOY_SIGNAL_DURATION_TENTHS = 10

    /** Offset of `lora_channel` within the payload. The receiver reads the byte at
     *  this offset out of the relayed frame to follow a channel change (ADR-0011),
     *  so it may not move while that `offsetof` stands. */
    const val LORA_CHANNEL_OFFSET = 13

    /** Build the config body for [config]. Field order is the firmware struct's. */
    fun payload(config: LocatorConfig): ByteArray {
        val out = ByteArray(PAYLOAD_SIZE)
        var o = 0
        fun u8(v: Int) { out[o++] = v.toByte() }
        fun u16(v: Int) { out[o++] = v.toByte(); out[o++] = (v / 256).toByte() }

        u8((config.deploymentChannel1Mode ?: DeployMode.DroguePrimary).deployMode.toInt())
        u8((config.deploymentChannel2Mode ?: DeployMode.DrogueBackup).deployMode.toInt())
        u8((config.deploymentChannel3Mode ?: DeployMode.MainPrimary).deployMode.toInt())
        u8((config.deploymentChannel4Mode ?: DeployMode.MainBackup).deployMode.toInt())
        u16(RESERVED_LAUNCH_DETECT_ALTITUDE_M)          // reserved — locator keeps its own
        u8(config.droguePrimaryDeployDelay)
        u8(config.drogueBackupDeployDelay)
        u16(config.mainPrimaryDeployAltitude)
        u16(config.mainBackupDeployAltitude)
        u8(RESERVED_DEPLOY_SIGNAL_DURATION_TENTHS)      // reserved — locator keeps its own
        u8(config.loraChannel)

        val name = config.deviceName.encodeToByteArray()
        System.arraycopy(name, 0, out, o, minOf(name.size, Protocol.DEVICE_NAME_LENGTH))
        o += Protocol.DEVICE_NAME_LENGTH

        // noseAxis is last, after deviceName — the firmware appends it there so
        // every existing field keeps its offset.
        u8(config.noseAxis.value.toInt())
        check(o == PAYLOAD_SIZE) { "LocatorCfgChgRequest body is $o bytes, expected $PAYLOAD_SIZE" }
        return out
    }
}
