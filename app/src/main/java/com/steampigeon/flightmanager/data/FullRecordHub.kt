package com.steampigeon.flightmanager.data

import android.content.Context
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.SpLog
import com.steampigeon.flightmanager.data.fullrecord.FullRecordCodec
import com.steampigeon.flightmanager.data.fullrecord.FullRecordStore
import com.steampigeon.flightmanager.data.fullrecord.FullRecordTransfer
import com.steampigeon.flightmanager.data.fullrecord.FullRecordTransfer.Status
import com.steampigeon.flightmanager.data.fullrecord.SerialFrames
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
 * Two paths (ADR decision 6c).  While the locator's USB cable is in and the
 * locator answers over it, the cable drives the transfer: its periodic frames
 * pace it, its requests and ACKs go down the cable, and the radio's periodic
 * frames are ignored for it.  The flight list still comes over the radio,
 * because only the full-record frames work over the cable.  When the cable goes
 * the radio takes over where it was, with nothing restarted.
 *
 * An object, not ViewModel state: the transfer must not restart because the
 * Activity was recreated, and there must never be two of it.  Everything is
 * called on the main thread (the packet collector and the UI), so it needs no
 * locking; the transfer itself is pure Kotlin and JVM-tested in
 * `app/src/test/.../fullrecord`.
 */
object FullRecordHub : FullRecordTransfer.Listener, UsbLocatorLink.Sink {

    @Volatile var service: BluetoothService? = null
    /** Firebase upload, when configured (google-services.json present and signed in). */
    @Volatile var uploader: FlightUploader? = null

    private var appContext: Context? = null
    private var store: FullRecordStore? = null
    private var transfer: FullRecordTransfer? = null
    private var lastNotifyMs = 0L
    private var cable: UsbLocatorLink? = null
    /** The locator on the cable, once it has answered there. */
    private var cableLocatorId: Long? = null

    private val _onCable = MutableStateFlow(false)
    /** True while the transfer runs over the USB cable. */
    val onCable: StateFlow<Boolean> = _onCable.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _pending = MutableStateFlow<Int?>(null)
    /** Records on the connected locator not yet on the phone; null until its flight list is known. */
    val pending: StateFlow<Int?> = _pending.asStateFlow()

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
        cable = UsbLocatorLink(context, this).also { it.start() }
    }

    val settings: FullRecordTransfer.Settings? get() = transfer?.settings
    val active: Boolean get() = transfer?.active == true

    // ---- inputs from the packet collector ---------------------------------------

    fun onPeriodic(nowMs: Long, locatorId: Long, armed: Boolean, flightState: Int, rssi: Int, pickedUp: Boolean,
                   simulated: Boolean = false) {
        if (cableLocatorId != null) return             // the cable paces the transfer
        transfer?.onPeriodic(nowMs, locatorId, armed, flightState, rssi, pickedUp, simulated)
    }

    /** When the last full-record frame arrived, for [radioTransferLive]. */
    @Volatile private var lastFrameMs = 0L

    fun onFrame(frame: ByteArray) {
        lastFrameMs = System.currentTimeMillis()
        transfer?.onFrame(frame)
    }

    /**
     * True while a transfer is running over the radio and the locator was heard
     * from recently, by its periodic frame or a record frame.  The locator then
     * sends its periodic frame once per burst cycle, about every 5.5 s, not once
     * a second, so a 2-3 s silence is the transfer and not a lost locator.
     */
    fun radioTransferLive(nowMs: Long, lastPeriodicMs: Long): Boolean {
        if (_onCable.value || _status.value !is Status.Transferring) return false
        return nowMs - maxOf(lastFrameMs, lastPeriodicMs) < RADIO_TRANSFER_SILENCE_MS
    }

    /** Longer than one burst cycle, with one cycle's loss to spare. */
    private const val RADIO_TRANSFER_SILENCE_MS = 12_000L

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

    // ---- UsbLocatorLink.Sink -------------------------------------------------------

    override fun onCableFrame(frame: ByteArray) {
        val t = transfer ?: return
        if (SerialFrames.isFullRecord(frame)) { t.onFrame(frame); return }
        val p = SerialFrames.periodic(frame) ?: return
        val first = cableLocatorId == null
        cableLocatorId = p.locatorId
        _onCable.value = true
        // No RSSI on a cable; the lowest value keeps the signal trigger out of it.
        t.onPeriodic(System.currentTimeMillis(), p.locatorId, p.armed, p.flightState, Int.MIN_VALUE, p.pickedUp)
        if (first) { log("locator ${"%08X".format(p.locatorId)} on the USB cable"); t.onCable() }
    }

    override fun onCableLost() {
        if (cableLocatorId == null) return
        log("USB cable gone; the transfer continues over the radio")
        cableLocatorId = null
        _onCable.value = false
    }

    // ---- FullRecordTransfer.Listener ------------------------------------------------

    override fun onPending(count: Int?) { _pending.value = count }

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
                svc.showTransferProgress(
                    ctx.getString(R.string.full_record_notification_progress,
                        status.recordIndex, status.recordCount, status.percent),
                    status.decoded, status.totalSamples)
            }
            is Status.Listing, is Status.Waiting ->
                svc.showTransferProgress(ctx.getString(R.string.full_record_notification_waiting))
            is Status.Finished, Status.Idle -> { lastNotifyMs = 0L; svc.showTransferProgress(null) }
        }
    }

    private val link = object : FullRecordTransfer.Link {
        override fun sendRequest(record: Int, page: Int): Boolean {
            val id = cableLocatorId
            if (id != null) return cable?.send(SerialFrames.request(id, record, page)) == true
            return service?.sendFullRecordRequest(record, page) == true
        }
        override fun sendAck(recordCrc: Long, page: Int, packetCount: Int, bitmap: ByteArray): Boolean {
            val id = cableLocatorId
            if (id != null) return cable?.send(SerialFrames.ack(id, recordCrc, page, packetCount, bitmap)) == true
            return service?.sendFullRecordAck(recordCrc, page, packetCount, bitmap) == true
        }
        // The flight list and leaving it are radio commands, whichever path the pages take.
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
