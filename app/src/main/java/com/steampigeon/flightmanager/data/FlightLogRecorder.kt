package com.steampigeon.flightmanager.data

/**
 * Decides what reaches a flight log and when, holding no Android types, no clock
 * and no coroutines.
 *
 * Extracted the way [ChannelMoveRunner] was, and for the same reason: the sequencing
 * is the part that can be wrong — what is kept, what is discarded, which signal ends
 * a file — and none of it is testable while it sits inside `RocketViewModel` with a
 * `BluetoothService` in scope. Everything with a side effect goes through [Sink].
 *
 * ## The pre-roll, and why nothing is written before a launch
 *
 * Records offered before a launch go into a ring pruned to [preRollMs], and the ring
 * lives in memory. A session spent connecting, arming, changing channels and
 * disarming again therefore leaves *nothing* on disk — not a file that is deleted
 * afterwards, but no file at all — which is the requirement stated as "if a locator
 * is connected, armed, configured, or other activity but not flown, any pre-flight
 * log data should be discarded". A launch is the only thing that opens a file, and
 * the ring is what makes the two seconds before it available once one does.
 *
 * ## What ends a log
 *
 * Landing does not. It is recorded as an event and the file stays open, because the
 * walk-in to recover the rocket is when link quality matters most and is exactly the
 * window the operator cannot watch. A log ends on [LogCloseReason]: the locator
 * disarmed, the receiver or locator changed under it, the app stopping, or the next
 * launch — each of which either ends the flight's relevance or makes the following
 * rows describe something else.
 */
class FlightLogRecorder(
    private val sink: Sink,
    private val preRollMs: Long = DEFAULT_PRE_ROLL_MS,
) {

    /** Where rows go. Implemented against real files by `FlightLogStore`. */
    interface Sink {
        /**
         * Begins a file and writes [FlightLog.CSV_HEADER].
         *
         * Returns false if it could not — a full disk, a revoked directory. The
         * recorder then stays idle rather than believing it is recording, so the
         * next launch tries again instead of dropping rows into nothing.
         */
        fun open(fileName: String): Boolean
        fun append(rows: List<String>)
        fun close()
    }

    private val preRoll = ArrayDeque<FlightLogRecord>()
    private var openLaunchMs: Long? = null

    /** True while a file is open and rows are reaching it. */
    val isRecording: Boolean get() = openLaunchMs != null

    /**
     * Offer a record. Buffered while idle, written while recording.
     *
     * The ring is pruned against the newest record offered rather than a wall clock,
     * so it holds the last [preRollMs] *of received data*. During a dropout it
     * therefore keeps the last frames heard instead of ageing them out into an empty
     * buffer — the frames before a signal was lost being the ones worth having.
     */
    fun offer(record: FlightLogRecord) {
        if (isRecording) {
            sink.append(listOf(FlightLog.row(record, openLaunchMs!!, zone)))
            return
        }
        preRoll.addLast(record)
        while (preRoll.isNotEmpty() &&
            record.timestampMs - preRoll.first().timestampMs > preRollMs
        ) preRoll.removeFirst()
    }

    /**
     * A launch was detected: open a file and flush the pre-roll into it.
     *
     * A launch while a log is already open closes that one first. Two flights cannot
     * share a file — the second would read as the first continuing, and `elapsed_s`
     * would be measured from the wrong zero.
     */
    fun onLaunch(timestampMs: Long, locatorName: String, header: String): Boolean {
        if (isRecording) close(timestampMs, LogCloseReason.NewLaunch)
        val name = FlightLog.fileName(locatorName, timestampMs, zone)
        if (!sink.open(name)) {
            // Keep the pre-roll: the next launch, or a retry, still has it.
            return false
        }
        openLaunchMs = timestampMs
        // Stamped at the oldest row the file will contain so the timestamps are
        // monotonic from the first line — a reader sorting by time must not have to
        // special-case the header row sitting two seconds in the future.
        val openedAt = preRoll.firstOrNull()?.timestampMs ?: timestampMs
        val rows = mutableListOf(
            FlightLog.row(
                FlightLogRecord.Event(openedAt, LogEvent.SessionOpened, header),
                timestampMs, zone,
            )
        )
        preRoll.mapTo(rows) { FlightLog.row(it, timestampMs, zone) }
        rows.add(
            FlightLog.row(
                FlightLogRecord.Event(timestampMs, LogEvent.LaunchDetected, locatorName),
                timestampMs, zone,
            )
        )
        preRoll.clear()
        sink.append(rows)
        return true
    }

    /** Ends an open log. A no-op when nothing is open, so callers need no guard. */
    fun close(timestampMs: Long, reason: LogCloseReason) {
        val t0 = openLaunchMs ?: return
        sink.append(
            listOf(
                FlightLog.row(
                    FlightLogRecord.Event(timestampMs, LogEvent.SessionClosed, reason.label),
                    t0, zone,
                )
            )
        )
        sink.close()
        openLaunchMs = null
    }

    /**
     * Drop buffered pre-roll without writing it.
     *
     * For the moment the data stops describing the same thing — a different locator
     * connected, the receiver retuned — where carrying the previous subject's frames
     * into the next launch's pre-roll would put two rockets in one file. Does not
     * touch an open log; that is [close]'s job.
     */
    fun discardPreRoll() = preRoll.clear()

    /** Test seam only; the zone is fixed for the life of a recorder. */
    var zone: java.time.ZoneId = java.time.ZoneId.systemDefault()

    companion object {
        /**
         * Two seconds, as asked, which at 1 Hz is the two frames before the launch
         * frame. Enough to carry the last on-pad reading of RSSI, noise floor and
         * pad-alert state into the file — the state the rocket left the pad in.
         */
        const val DEFAULT_PRE_ROLL_MS = 2_000L
    }
}
