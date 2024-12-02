package com.steampigeon.flightmanager.data

/**
 * Data class that represents the current rocket locator state]
 */
data class RocketUiState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val hdop: Float = 0f,
    val altimeterStatus: Boolean = false,
    val accelerometerStatus: Boolean = false,
    val deployChannel1Armed: Boolean = false,
    val deployChannel2Armed: Boolean = false,
    val altitudeAboveGroundLevel: Float = 0f,
    val accelerometer: Accelerometer = Accelerometer(0, 0, 0),
    val deployMode: DeployMode = DeployMode.kDroguePrimaryDrogueBackup,
    val launchDetectAltitude: UShort = 0u,
    val droguePrimaryDeployDelay: UByte = 0u,
    val drogueBackupDeployDelay: UByte = 0u,
    val mainPrimaryDeployAltitude: UShort = 0u,
    val mainBackupDeployAltitude: UShort = 0u,
    val deploySignalDuration: Float = 0f,
    val deviceName: String = "",
    val batteryVoltage: UShort = 0u,
    //val locatorDetected: Boolean = false,
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
        fun fromUByte(value: UByte) = entries.firstOrNull { it.deployMode == value } ?: throw IllegalArgumentException("Invalid type: $value")
    }
}
