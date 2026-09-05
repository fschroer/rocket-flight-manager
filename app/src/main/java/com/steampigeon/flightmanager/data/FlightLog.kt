package com.steampigeon.flightmanager.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The app-side flight log: what the phone saw, as opposed to what the locator
 * archived.
 *
 * The locator's own archive is the authority on the flight — 20 Hz, GPS-disciplined,
 * and downloadable afterwards (see [FlightDataRepository]).  It cannot answer a
 * different class of question, because it does not know any of it: what the RSSI and
 * SNR were at the phone, when the app decided the link had degraded, what it
 * announced out loud and when.  Those live only here, they are gone the moment the
 * app is closed, and during a flight nobody can watch them go past.
 *
 * So this is deliberately NOT a second copy of the telemetry.  It is the received
 * frame *plus* the receiver's measurement of how it arrived *plus* what the app did
 * about it, on one timeline, which is the only place those three can be compared.
 *
 * ## Rate
 *
 * The locator transmits exactly once per second — a 20 Hz superloop
 * (`SAMPLES_PER_SECOND`) whose `case 2` is the only branch that reaches the radio,
 * so one frame per second, pre-launch or telemetry, armed or not.  Every received
 * frame is logged; at 1 Hz there is nothing to downsample and no reason to.
 *
 * ## Format
 *
 * A pure CSV with one wide schema: [CSV_HEADER].  Sample rows leave the event
 * columns blank, event rows leave the telemetry columns blank, and a column a given
 * message type does not carry is blank rather than zero — 0 m AGL is a real reading
 * and must not be confused with "this message has no altitude".
 *
 * There is no `#` metadata preamble.  Everything about the file that is not a
 * measurement rides in [LogEvent.SessionOpened] as an ordinary row, so the file
 * opens in Excel, `pandas.read_csv` and `csvkit` with no options and no dialect.
 */
object FlightLog {

    /**
     * Written with [Locale.US] throughout, on purpose and not as a default.
     *
     * A phone set to a locale with a comma decimal separator would otherwise render
     * `-27,5` for an SNR and put a field break in the middle of a number, silently,
     * on the device of whoever is least likely to notice.  The file is a data
     * interchange format, so its number format is fixed rather than the user's.
     */
    private val LOCALE = Locale.US

    /** Wall-clock stamp, ISO-8601 with offset so a log is placeable without a note. */
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", LOCALE)

    /** The date-time half of a log's filename — see [fileName]. */
    private val FILENAME_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", LOCALE)

    const val FILE_EXTENSION = ".csv"

    val CSV_HEADER: String = listOf(
        "timestamp", "elapsed_s", "source", "event", "detail",
        "flight_state", "lat", "lon", "agl_m",
        "vel_n_ms", "vel_e_ms", "vel_d_ms",
        "accel_x", "accel_y", "accel_z",
        "gyro_x", "gyro_y", "gyro_z",
        "q_w", "q_x", "q_y", "q_z",
        "satellites", "hacc_m",
        "rssi_dbm", "snr_db", "noise_floor_dbm", "bad_frames", "link_quality",
        "armed", "deploy_armed_mask", "deploy_fired_mask",
        "drogue_detected", "main_detected", "pad_alert",
        "locator_batt_mv", "receiver_batt_mv", "receiver_channel", "locator_id",
    ).joinToString(",")

    /** Number of columns in [CSV_HEADER]; every row renders exactly this many. */
    val COLUMN_COUNT: Int = CSV_HEADER.count { it == ',' } + 1

    // ── Naming ───────────────────────────────────────────────────────────────

    /**
     * `<locator>_YYYY-MM-DD_HHmmss.csv`, in the phone's own zone.
     *
     * Local time rather than UTC because the name is read by a person deciding
     * which of six logs was the flight after lunch, and a launch at 14:20 local
     * filed under 21:20 is the wrong answer to that question.  The rows carry the
     * offset, so nothing is lost.
     *
     * The locator name is sanitised to `[A-Za-z0-9._-]` and truncated: it comes off
     * the wire as an arbitrary 20-byte field the user typed, and a `/` in it would
     * otherwise name a directory that does not exist.  An empty or entirely
     * unusable name falls back to [UNNAMED_LOCATOR] rather than producing a file
     * that begins with the separator.
     */
    const val UNNAMED_LOCATOR = "locator"

    fun fileName(locatorName: String, epochMs: Long, zone: ZoneId): String {
        val safe = locatorName
            .map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .take(MAX_NAME_CHARS)
            .ifEmpty { UNNAMED_LOCATOR }
        val stamp = Instant.ofEpochMilli(epochMs).atZone(zone).format(FILENAME_TIME_FORMAT)
        return "${safe}_$stamp$FILE_EXTENSION"
    }

    private const val MAX_NAME_CHARS = 24

    // ── Session header ───────────────────────────────────────────────────────

    /**
     * The `detail` of the [LogEvent.SessionOpened] row: everything about the file
     * that is not a measurement.
     *
     * ## Why the batteries are here and not in a column
     *
     * `locator_batt_mv` and `receiver_batt_mv` are columns, but on an ARMED flight
     * they are always blank, and that is structural rather than a fault. The
     * locator sends `PreLaunchData` only while **disarmed and WaitingLaunch**
     * (`Factory.cpp`: `send_telemetry = Armed || flight_state != WaitingLaunch`),
     * the batteries ride only on that message, and a flight is armed before it
     * launches — so the two-second pre-roll sits entirely inside the armed window
     * and never contains a pre-launch frame. Confirmed on a real log,
     * 2026-09-04: 341 telemetry rows, 285 receiver-info rows, zero pre-launch.
     *
     * The values are still *known* — the app heard them before arming — so they go
     * in the header, where an armed flight can carry them too.
     *
     * ## The age is not optional
     *
     * [batteryAgeMs] is how long before the launch that reading arrived. Without
     * it the number is a claim about an unknown moment: it could be from ten
     * seconds before arming or from an hour before, and on a locator that has sat
     * powered on the pad those are very different readings. Reported in seconds,
     * and the whole battery clause reads `unknown` when nothing was ever heard —
     * which is what an app started after arming genuinely knows.
     */
    fun sessionHeader(
        locatorName: String,
        locatorId: Long?,
        receiverName: String,
        receiverChannel: Int,
        appVersion: String,
        locatorBatteryMv: Int?,
        receiverBatteryMv: Int?,
        batteryAgeMs: Long?,
    ): String = buildString {
        append("Steam Pigeon app flight log")
        append("; locator=").append(locatorName.ifEmpty { "unknown" })
        append("; locator_id=").append(locatorId ?: 0L)
        append("; receiver=").append(receiverName.ifEmpty { "unknown" })
        append("; receiver_channel=").append(receiverChannel)
        append("; app_version=").append(appVersion)
        if (locatorBatteryMv != null && receiverBatteryMv != null) {
            append("; locator_batt_mv=").append(locatorBatteryMv)
            append("; receiver_batt_mv=").append(receiverBatteryMv)
            // Negative would mean a reading stamped after the launch it precedes,
            // which is a clock going backwards rather than a battery age.
            append("; batt_age_s=").append(
                if (batteryAgeMs == null || batteryAgeMs < 0) "unknown"
                else String.format(LOCALE, "%.1f", batteryAgeMs / 1000.0)
            )
        } else {
            // Said out loud rather than omitted: a missing clause reads as an
            // older app that never wrote one, and this is a real fact about the
            // flight — the app never heard the locator while it was disarmed.
            append("; batteries=unknown (no pre-launch frame heard)")
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /**
     * One CSV row.
     *
     * [t0Ms] is the launch-detect instant, so `elapsed_s` runs negative through the
     * pre-roll and crosses zero at the launch — which is what makes "two seconds
     * before launch detect" checkable by looking at the file rather than trusting
     * the code that wrote it.
     */
    fun row(record: FlightLogRecord, t0Ms: Long, zone: ZoneId): String {
        val cells = MutableList(COLUMN_COUNT) { "" }
        cells[0] = Instant.ofEpochMilli(record.timestampMs).atZone(zone).format(TIMESTAMP_FORMAT)
        cells[1] = num((record.timestampMs - t0Ms) / 1000.0, 3)
        cells[2] = record.source.label
        when (record) {
            is FlightLogRecord.Event -> {
                cells[3] = record.event.label
                cells[4] = escape(record.detail)
            }
            is FlightLogRecord.Sample -> {
                val s = record
                cells[5] = s.flightState?.name ?: ""
                cells[6] = num(s.latitude, 7)
                cells[7] = num(s.longitude, 7)
                cells[8] = num(s.aglM, 2)
                cells[9] = num(s.velNed?.x, 2)
                cells[10] = num(s.velNed?.y, 2)
                cells[11] = num(s.velNed?.z, 2)
                cells[12] = num(s.accel?.x, 3)
                cells[13] = num(s.accel?.y, 3)
                cells[14] = num(s.accel?.z, 3)
                cells[15] = num(s.gyro?.x, 3)
                cells[16] = num(s.gyro?.y, 3)
                cells[17] = num(s.gyro?.z, 3)
                cells[18] = num(s.attitude?.w, 5)
                cells[19] = num(s.attitude?.x, 5)
                cells[20] = num(s.attitude?.y, 5)
                cells[21] = num(s.attitude?.z, 5)
                cells[22] = s.satellites?.toString() ?: ""
                cells[23] = num(s.haccM, 2)
                cells[24] = s.rssi?.toString() ?: ""
                cells[25] = s.snr?.toString() ?: ""
                // The receiver reports "unknown" as a sentinel rather than a
                // reading. Writing the sentinel would put a plausible-looking
                // -128 dBm floor in the data, so it goes out blank.
                cells[26] = s.noiseFloor
                    ?.takeIf { it != LinkQuality.NOISE_FLOOR_UNKNOWN }?.toString() ?: ""
                cells[27] = s.badFrames?.toString() ?: ""
                cells[28] = s.linkQuality?.name ?: ""
                cells[29] = s.armed?.let { if (it) "1" else "0" } ?: ""
                cells[30] = s.deployArmedMask?.toString() ?: ""
                cells[31] = s.deployFiredMask?.toString() ?: ""
                cells[32] = s.drogueDetected?.let { if (it) "1" else "0" } ?: ""
                cells[33] = s.mainDetected?.let { if (it) "1" else "0" } ?: ""
                cells[34] = s.padAlert?.name ?: ""
                cells[35] = s.locatorBatteryMv?.toString() ?: ""
                cells[36] = s.receiverBatteryMv?.toString() ?: ""
                cells[37] = s.receiverChannel?.toString() ?: ""
                cells[38] = s.locatorId?.toString() ?: ""
            }
        }
        return cells.joinToString(",")
    }

    /**
     * A float that is absent, infinite or NaN renders blank rather than as text.
     *
     * `NaN` in a numeric column stops most readers dead or, worse, is read as a
     * category and turns the whole column into strings. AGL arrives non-finite
     * often enough that the live path guards against it too.
     */
    private fun num(v: Double?, decimals: Int): String =
        if (v == null || !v.isFinite()) "" else String.format(LOCALE, "%.${decimals}f", v)

    private fun num(v: Float?, decimals: Int): String = num(v?.toDouble(), decimals)

    /** Minimal RFC 4180 quoting, for the one column that carries free text. */
    private fun escape(text: String): String =
        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + text.replace("\"", "\"\"") + "\""
        else text
}

/** Which stream a row came from — the message type, or the app itself. */
enum class LogSource(val label: String) {
    Prelaunch("prelaunch"),
    Telemetry("telemetry"),
    ReceiverInfo("receiver_info"),
    App("app"),
}

/**
 * App-side occurrences worth a row.
 *
 * The list is short on purpose. Everything here is either a decision the app made
 * that is invisible afterwards ([LinkQualityChanged], [Announcement]) or a boundary
 * that explains a discontinuity in the rows around it ([ReceiverChannelChanged],
 * [ConnectionChanged]) — without which a gap in a log is indistinguishable from a
 * radio dropout, which is exactly the thing the log exists to measure.
 *
 * Nothing here restates a column. The pad-alert verdict and the per-channel fired
 * bits ride on every frame as `pad_alert` and `deploy_fired_mask`, so an event for
 * either would be a second, lower-rate copy of a fact the rows already carry —
 * and two sources for one fact is two things that can disagree.
 */
enum class LogEvent(val label: String) {
    /** First row of every file: locator, app version, why the log opened. */
    SessionOpened("session_opened"),
    /** Last row of every file, carrying the close reason. */
    SessionClosed("session_closed"),
    /** The grounded → airborne transition this log is named for. */
    LaunchDetected("launch_detected"),
    /** The locator said Landed, or the app concluded it. Does not end the log. */
    LandingDetected("landing_detected"),
    FlightStateChanged("flight_state_changed"),
    /** Spoken aloud. [FlightLogRecord.Event.detail] is the exact text. */
    Announcement("announcement"),
    LinkQualityChanged("link_quality_changed"),
    /**
     * The BLE link to the receiver came up or went down.
     *
     * Load-bearing since a dropped link stopped closing the log: a gap in the rows
     * is otherwise ambiguous between "the rocket went quiet" — which is about the
     * LoRa link and is what the log exists to measure — and "the phone lost the
     * receiver in your pocket", which is about neither.
     */
    ConnectionChanged("connection_changed"),
    ArmedStateChanged("armed_state_changed"),
    ReceiverChannelChanged("receiver_channel_changed"),
    LocatorChanged("locator_changed"),
}

/** Why an open log stopped — the [LogEvent.SessionClosed] detail. */
enum class LogCloseReason(val label: String) {
    Disarmed("locator disarmed"),
    ReceiverChannelChanged("receiver channel changed"),
    LocatorChanged("connected locator changed"),
    NewLaunch("a new launch was detected"),
    AppStopped("app stopped"),
}

/** One line of a log: a received frame, or something the app did. */
sealed class FlightLogRecord {
    abstract val timestampMs: Long
    abstract val source: LogSource

    /**
     * A received frame.
     *
     * Every field is nullable because the two message types are near-disjoint —
     * `PreLaunchData` carries the IMU and the batteries, `TelemetryData` carries
     * velocity, attitude and flight state, and neither carries the other's. Null
     * means "this message type has no such field", which the renderer writes as
     * blank; it is never a zero.
     */
    data class Sample(
        override val timestampMs: Long,
        override val source: LogSource,
        val flightState: FlightStates? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val aglM: Float? = null,
        val velNed: Vec3f? = null,
        val accel: Vec3f? = null,
        val gyro: Vec3f? = null,
        val attitude: Quaternionf? = null,
        val satellites: Int? = null,
        val haccM: Float? = null,
        val rssi: Int? = null,
        val snr: Int? = null,
        val noiseFloor: Int? = null,
        val badFrames: Int? = null,
        val linkQuality: LinkQuality.Verdict? = null,
        val armed: Boolean? = null,
        val deployArmedMask: Int? = null,
        val deployFiredMask: Int? = null,
        val drogueDetected: Boolean? = null,
        val mainDetected: Boolean? = null,
        val padAlert: PadAlertState? = null,
        val locatorBatteryMv: Int? = null,
        val receiverBatteryMv: Int? = null,
        val receiverChannel: Int? = null,
        val locatorId: Long? = null,
    ) : FlightLogRecord()

    data class Event(
        override val timestampMs: Long,
        val event: LogEvent,
        val detail: String = "",
        override val source: LogSource = LogSource.App,
    ) : FlightLogRecord()
}
