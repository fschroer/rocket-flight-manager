package com.steampigeon.flightmanager.data

/**
 * What a failed locator sensor takes away — ADR-0038 ("a sensor failure degrades
 * the locator, it does not restart it").
 *
 * The locator used to restart whenever its IMU, barometer or GPS failed the
 * power-on check, so the app never saw a locator with a dead sensor: it saw one
 * that appeared for a few seconds and vanished. It now boots, reports the failed
 * sensor as [SensorHealth.Error] in the health bytes it has always sent, and
 * refuses ArmRequest unless the IMU and the barometer are both up. Nothing new
 * goes on the wire, so this file is the whole of what the app needs: the same
 * rule, read off the same bytes.
 *
 * Mirrors the firmware's `Rocket/Common/Inc/SensorFaults.hpp`. Change both, and
 * the two test tables (`Tests/SensorFaults`, [SensorFaultsTest]) with them.
 */
object SensorFaults {
    /**
     * A sensor that is not running. Warning (one bad read) and Stale (a GPS with no
     * fresh fix) are a sensor that is up and may recover, so they are not failures:
     * counting them would flash a fault on every pad until the first GPS fix.
     */
    fun failed(health: SensorHealth): Boolean =
        health == SensorHealth.Error ||
            health == SensorHealth.Off ||
            health == SensorHealth.Initializing

    /**
     * Arming needs the IMU (launch, burnout) and the barometer (apogee, main,
     * landing). The GPS gates nothing: without it the rocket still flies and
     * deploys, it just cannot be found by position.
     */
    fun armingAllowed(imu: SensorHealth, baro: SensorHealth): Boolean =
        !failed(imu) && !failed(baro)
}

/** The locator's failed sensors, from one broadcast's health bytes. */
data class SensorFaultSummary(
    val imuFailed: Boolean,
    val baroFailed: Boolean,
    val gpsFailed: Boolean,
) {
    val any: Boolean get() = imuFailed || baroFailed || gpsFailed
    val armingAllowed: Boolean get() = !imuFailed && !baroFailed

    companion object {
        fun of(imu: SensorHealth, baro: SensorHealth, gps: SensorHealth) = SensorFaultSummary(
            imuFailed = SensorFaults.failed(imu),
            baroFailed = SensorFaults.failed(baro),
            gpsFailed = SensorFaults.failed(gps),
        )
    }
}

fun RocketState.sensorFaults(): SensorFaultSummary =
    SensorFaultSummary.of(imuStatus, baroStatus, gpsStatus)

/**
 * The failed sensors' names as one English phrase: "IMU", "IMU and barometer",
 * "IMU, barometer and GPS". Names come in as resources; only the joining is here.
 */
fun joinSensorNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}
