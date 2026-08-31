package com.steampigeon.flightmanager.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.steampigeon.flightmanager.SpLog
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Where app flight logs live on disk, and everything done to them afterwards.
 *
 * App-private storage (`filesDir/flight_logs`), not shared `Documents/`. The logs
 * are then deletable from exactly one place — the App Flight Logs screen — so the
 * list on that screen is the truth about what exists. Shared storage would make the
 * files visible over USB without an export step, but at the cost of a list that goes
 * stale whenever someone tidies a folder on their laptop, and of nothing to say when
 * a log the user is looking at vanishes underneath them. Export covers the same need
 * deliberately instead of incidentally.
 */
class FlightLogStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    // ── Listing, reading, deleting ───────────────────────────────────────────

    /**
     * Every stored log, newest first.
     *
     * Ordered by the timestamp in the name rather than by file mtime: mtime moves
     * when a log is appended to, so an old flight still open during a long recovery
     * would sort above a newer one that had already closed.
     */
    fun list(): List<FlightLogFile> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(FlightLog.FILE_EXTENSION) } ?: emptyArray())
            .map { FlightLogFile(it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.name }

    /**
     * A log's rows for on-screen viewing, capped at [maxRows].
     *
     * The cap is a display limit, never a transfer limit — [uriFor] always hands over
     * the whole file. A log left open through a long recovery can reach tens of
     * thousands of rows, and rendering all of them into a Compose list would stall
     * the screen to show what nobody scrolls to. The truncation is reported so the
     * screen can say the file has more in it rather than quietly ending early.
     */
    fun read(name: String, maxRows: Int = MAX_VIEW_ROWS): FlightLogContents {
        val file = fileFor(name) ?: return FlightLogContents(emptyList(), 0, false)
        return try {
            file.bufferedReader().use { reader ->
                val rows = ArrayList<String>(minOf(maxRows, 1024))
                var total = 0
                reader.forEachLine { line ->
                    total++
                    if (rows.size < maxRows) rows.add(line)
                }
                FlightLogContents(rows, total, total > rows.size)
            }
        } catch (e: Exception) {
            SpLog.d(TAG, "read $name failed: $e")
            FlightLogContents(emptyList(), 0, false)
        }
    }

    fun delete(name: String): Boolean = fileFor(name)?.delete() ?: false

    /**
     * A shareable URI for one log.
     *
     * Goes out through [FileProvider] because the file is app-private: a `file://`
     * URI would be refused by the receiving app on every Android since 7, and the
     * share sheet is the whole export mechanism. The authority matches the
     * `<provider>` in the manifest.
     */
    fun uriFor(name: String): Uri? {
        val file = fileFor(name) ?: return null
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            SpLog.d(TAG, "uriFor $name failed: $e")
            null
        }
    }

    /**
     * Resolves a name to a file inside [dir], refusing anything that escapes it.
     *
     * The names this store issues are safe by construction, but they round-trip
     * through screen state and an intent extra before coming back here, and a path
     * check at the point of use costs nothing next to being wrong about that.
     */
    private fun fileFor(name: String): File? {
        val file = File(dir, name)
        if (file.parentFile?.canonicalPath != dir.canonicalPath) return null
        return file.takeIf { it.isFile }
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /**
     * A [FlightLogRecorder.Sink] backed by a real file.
     *
     * Appends run on a single background thread. The recorder is driven from the
     * packet collector, which runs on the main dispatcher, and a write per second
     * from there is main-thread I/O however small it is. One thread rather than a
     * pool because rows must land in the order they were offered, and a pool would
     * reorder them under exactly the load that makes a log worth having.
     *
     * [open] is deliberately synchronous: it happens once per flight, and its result
     * decides whether the recorder believes it is recording at all. That verdict
     * cannot be delivered later without the recorder having already acted on a guess.
     */
    fun sink(): FlightLogRecorder.Sink = FileSink()

    private inner class FileSink : FlightLogRecorder.Sink {
        private var writer: BufferedWriter? = null
        private var io: ExecutorService? = null

        override fun open(fileName: String): Boolean = try {
            closeQuietly()
            val file = File(dir, fileName)
            writer = file.bufferedWriter().also {
                it.write(FlightLog.CSV_HEADER)
                it.newLine()
                it.flush()
            }
            io = Executors.newSingleThreadExecutor()
            SpLog.d(TAG, "opened $fileName")
            true
        } catch (e: Exception) {
            SpLog.d(TAG, "open $fileName failed: $e")
            writer = null
            false
        }

        /**
         * Flushed on every batch, which is once per second.
         *
         * A buffered writer left unflushed loses whatever it holds if the app is
         * killed — and an app killed mid-flight is not a hypothetical: it is the
         * background-Doze case this log is most wanted for, and losing the tail
         * would lose the part nobody saw. One flush per second is nothing next to
         * that.
         */
        override fun append(rows: List<String>) {
            val w = writer ?: return
            io?.execute {
                try {
                    rows.forEach { w.write(it); w.newLine() }
                    w.flush()
                } catch (e: Exception) {
                    SpLog.d(TAG, "append failed: $e")
                }
            }
        }

        override fun close() {
            val ex = io
            val w = writer
            writer = null
            io = null
            // Queued behind any pending appends, so the last rows offered before the
            // close reach the file rather than racing it.
            ex?.execute { try { w?.flush(); w?.close() } catch (_: Exception) {} }
            ex?.shutdown()
        }

        private fun closeQuietly() {
            try { writer?.flush(); writer?.close() } catch (_: Exception) {}
            io?.shutdown()
            writer = null
            io = null
        }
    }

    companion object {
        private const val TAG = "FlightLogStore"
        const val DIR_NAME = "flight_logs"
        const val MAX_VIEW_ROWS = 5_000
    }
}

/** One stored log, as the list screen needs it. */
data class FlightLogFile(
    val name: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
) {
    /**
     * The locator name and launch time, recovered from the filename.
     *
     * Parsed rather than stored alongside because the filename is the only thing
     * guaranteed to exist for every log — a sidecar index would need to stay in step
     * with a directory that the recorder writes to from another thread, and be
     * rebuilt whenever it did not.
     */
    val locatorName: String
        get() = name.substringBeforeLast(FlightLog.FILE_EXTENSION)
            .substringBeforeLast('_')          // time
            .substringBeforeLast('_')          // date
            .ifEmpty { FlightLog.UNNAMED_LOCATOR }

    /** `YYYY-MM-DD HH:MM:SS`, or the raw name if it does not parse. */
    val capturedAt: String
        get() {
            val stem = name.substringBeforeLast(FlightLog.FILE_EXTENSION)
            val time = stem.substringAfterLast('_')
            val date = stem.substringBeforeLast('_').substringAfterLast('_')
            if (date.length != 10 || time.length != 6) return stem
            return "$date ${time.substring(0, 2)}:${time.substring(2, 4)}:${time.substring(4, 6)}"
        }
}

/** A log's rows, and whether the screen is seeing all of them. */
data class FlightLogContents(
    val rows: List<String>,
    val totalRows: Int,
    val truncated: Boolean,
)
