package com.steampigeon.flightmanager.data

import android.content.Context
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.SpLog
import com.steampigeon.flightmanager.data.fullrecord.FullRecordCodec
import com.steampigeon.flightmanager.data.fullrecord.FullRecordStore
import com.steampigeon.flightmanager.data.fullrecord.FullRecordTransfer
import com.steampigeon.flightmanager.data.fullrecord.FullRecordTransfer.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private const val TAG = "FullRecordHub"

/**
 * The Android side of the background full-record transfer (ADR "The radio
 * download becomes the primary data path").  Owns the one [FullRecordTransfer]
 * and its [FullRecordStore], gives the transfer its [FullRecordTransfer.Link]
 * over BluetoothService, and publishes its state for the UI and the service's
 * notification.
 *
 * An object, not ViewModel state: the transfer must not restart because the
 * Activity was recreated, and there must never be two of it.  Everything is
 * called on the main thread (the packet collector and the UI), so it needs no
 * locking; the transfer itself is pure Kotlin and JVM-tested in
 * `app/src/test/.../fullrecord`.
 */
object FullRecordHub : FullRecordTransfer.Listener {

    @Volatile var service: BluetoothService? = null
    /** Firebase upload, when configured (google-services.json present and signed in). */
    @Volatile var uploader: FlightUploader? = null

    private var appContext: Context? = null
    private var store: FullRecordStore? = null
    private var transfer: FullRecordTransfer? = null
    private var lastNotifyMs = 0L

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _finished = MutableStateFlow<List<FullRecordStore.Finished>>(emptyList())
    /** Every complete, CRC-verified record on the phone, newest first. */
    val finished: StateFlow<List<FullRecordStore.Finished>> = _finished.asStateFlow()

    fun init(context: Context) {
        if (transfer != null) return
        appContext = context.applicationContext
        val s = FullRecordStore(File(context.filesDir, "full_records"))
        store = s
        transfer = FullRecordTransfer(s, link, this)
        _finished.value = s.finished()
    }

    val settings: FullRecordTransfer.Settings? get() = transfer?.settings
    val active: Boolean get() = transfer?.active == true

    // ---- inputs from the packet collector ---------------------------------------

    fun onPeriodic(nowMs: Long, locatorId: Long, armed: Boolean, flightState: Int, rssi: Int, pickedUp: Boolean) =
        transfer?.onPeriodic(nowMs, locatorId, armed, flightState, rssi, pickedUp)

    fun onFrame(frame: ByteArray) = transfer?.onFrame(frame)

    fun onListing(locatorId: Long, records: List<FlightRecordMetadata>) =
        transfer?.onListing(locatorId, records.map {
            FullRecordTransfer.Slot(it.position, it.timestampS, it.apogeeM)
        })

    // ---- the button ---------------------------------------------------------------

    fun start() = transfer?.start(FullRecordTransfer.Trigger.Button)
    fun stop() = transfer?.stop()

    /** The record being transferred and how much of it has arrived (ADR decision 6b). */
    fun partial(locatorId: Long, recordTs: Long): Pair<Int, ByteArray>? {
        val (slot, asm) = transfer?.partialView() ?: return null
        if (slot.timestampS != recordTs) return null
        return asm.decoded to asm.data
    }

    fun finishedRecord(locatorId: Long, recordTs: Long): FullRecordStore.Finished? =
        _finished.value.firstOrNull { it.key.locatorId == locatorId && it.key.recordTs == recordTs }

    fun bytes(record: FullRecordStore.Finished): ByteArray? = store?.bytes(record.key)

    /** A record fetched back from the web store (ADR decision 6b): keep it like a transferred one. */
    fun adopt(k: FullRecordStore.Key, slot: Int, apogeeM: Float, bytes: ByteArray, uploaded: Boolean): FullRecordStore.Finished? {
        val f = store?.adopt(k, slot, apogeeM, bytes, uploaded) ?: return null
        _finished.value = store?.finished() ?: _finished.value
        return f
    }

    fun storeDir(): File? = appContext?.let { File(it.filesDir, "full_records") }

    fun markUploaded(k: FullRecordStore.Key, bin: Boolean? = null, csv: Boolean? = null) {
        store?.markUploaded(k, bin, csv)
        _finished.value = store?.finished() ?: _finished.value
    }

    // ---- FullRecordTransfer.Listener ------------------------------------------------

    override fun onStatus(status: Status) {
        _status.value = status
        notify(status)
    }

    override fun onRecordFinished(record: FullRecordStore.Finished) {
        _finished.value = store?.finished() ?: _finished.value
        uploader?.enqueue(record)
    }

    override fun log(msg: String) { SpLog.d(TAG, msg) }

    private fun notify(status: Status) {
        val svc = service ?: return
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        when (status) {
            is Status.Transferring -> {
                // The status changes with every packet; the notification need not.
                if (now - lastNotifyMs < 2_000L) return
                lastNotifyMs = now
                val pct = if (status.totalSamples > 0) status.decoded * 100 / status.totalSamples else 0
                svc.showTransferProgress(
                    ctx.getString(R.string.full_record_notification_progress,
                        status.recordIndex, status.recordCount, pct),
                    status.decoded, status.totalSamples)
            }
            is Status.Listing, is Status.Waiting ->
                svc.showTransferProgress(ctx.getString(R.string.full_record_notification_waiting))
            is Status.Finished, Status.Idle -> { lastNotifyMs = 0L; svc.showTransferProgress(null) }
        }
    }

    private val link = object : FullRecordTransfer.Link {
        override fun sendRequest(record: Int, page: Int) = service?.sendFullRecordRequest(record, page) == true
        override fun sendAck(recordCrc: Long, page: Int, packetCount: Int, bitmap: ByteArray) =
            service?.sendFullRecordAck(recordCrc, page, packetCount, bitmap) == true
        override fun requestListing() = service?.requestFlightProfileMetadata() == true
        override fun exitListing() = service?.exitFlightProfileMode() == true
    }

    /** Raw v8 bytes as the quick-look [FlightSample]s the chart and map draw. */
    fun toProfileSamples(raw: ByteArray, count: Int): List<FlightSample> {
        val n = minOf(count, raw.size / FullRecordCodec.SAMPLE_SIZE)
        return List(n) { i ->
            val o = i * FullRecordCodec.SAMPLE_SIZE
            fun i32(at: Int) = (raw[o + at].toInt() and 0xFF) or ((raw[o + at + 1].toInt() and 0xFF) shl 8) or
                ((raw[o + at + 2].toInt() and 0xFF) shl 16) or ((raw[o + at + 3].toInt() and 0xFF) shl 24)
            fun fixed(at: Int, scale: Float) = i32(at).let { if (it == Int.MIN_VALUE) Float.NaN else it / scale }
            val g = 9.80665f
            FlightSample(
                timestampMs = i32(0).toLong() and 0xFFFFFFFFL,
                altitudeM = fixed(4, 100f),                       // raw baro AGL, as the quick-look sends
                accel = Vec3f(fixed(20, 1000f) * g, fixed(24, 1000f) * g, fixed(28, 1000f) * g),
                gyro = Vec3f(fixed(32, 100f), fixed(36, 100f), fixed(40, 100f)),
                latRad = Math.toRadians(i32(44) * 1e-7),
                lonRad = Math.toRadians(i32(48) * 1e-7),
            )
        }
    }
}

/** Uploads finished records to the web store (ADR decision 8). */
interface FlightUploader {
    fun enqueue(record: FullRecordStore.Finished)
}
