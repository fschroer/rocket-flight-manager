package com.steampigeon.flightmanager.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.steampigeon.flightmanager.SpLog
import com.steampigeon.flightmanager.data.fullrecord.SerialFrames
import java.util.concurrent.Executors

private const val TAG = "UsbLocatorLink"
private const val ACTION_USB_PERMISSION = "com.steampigeon.flightmanager.USB_PERMISSION"

/**
 * The locator's USB-C cable, for the full-record transfer (locator ADR "The
 * radio download becomes the primary data path", decision 6c).  Android only:
 * iOS gives an app no generic USB serial device without MFi.
 *
 * The locator's USB port is a CH340N USB-UART bridge on its console UART, so
 * this is usb-serial-for-android's CH34x driver at the console rate.  Opened
 * when a CH340 appears (and the user grants the permission Android asks for),
 * it puts the console in binary mode with [SerialFrames.HELLO] once a second and
 * hands every frame the locator sends to [FullRecordHub].  The locator answers
 * each hello with its periodic frame, which is how a rate is known to work.  The console rate is a
 * locator setting (ADR-0024, 921,600 baud by default), so until a frame arrives
 * the rates are tried from the fastest down: a link set faster than the locator
 * cannot disturb it (ConsoleBaud.hpp), so going down never changes its setting.
 *
 * Frames come in on usb-serial's reader thread and are handed to the main
 * thread, where FullRecordHub lives.
 */
class UsbLocatorLink(context: Context, private val sink: Sink) {

    interface Sink {
        /** On the main thread: a CRC-checked frame from the locator on the cable. */
        fun onCableFrame(frame: ByteArray)
        /** On the main thread: the cable is gone, or stopped answering. */
        fun onCableLost()
    }

    private val ctx = context.applicationContext
    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private val framer = SerialFrames.Framer()
    private var rateIndex = 0
    private var rateSinceMs = 0L
    private var lastFrameMs = 0L
    @Volatile var connected = false
        private set

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        usb.deviceList.values.firstOrNull(::isLocator)?.let(::attach)
    }

    /** Queued on the writer thread; false only when no cable is open. */
    fun send(frame: ByteArray): Boolean {
        val p = port ?: return false
        writer.execute {
            try { p.write(frame, WRITE_TIMEOUT_MS) } catch (e: Exception) { SpLog.d(TAG, "write failed: ${e.message}") }
        }
        return true
    }

    private fun isLocator(d: UsbDevice) = d.vendorId == CH34X_VENDOR_ID

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = device(i) ?: return
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (isLocator(d)) attach(d)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (port != null && isLocator(d)) close("unplugged")
                ACTION_USB_PERMISSION ->
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(d)
                    else SpLog.d(TAG, "USB permission refused")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun device(i: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun attach(d: UsbDevice) {
        if (port != null) return
        if (usb.hasPermission(d)) { open(d); return }
        // Mutable: the system adds the device and the grant to the intent.
        val pi = PendingIntent.getBroadcast(ctx, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        usb.requestPermission(d, pi)
    }

    private fun open(d: UsbDevice) {
        if (port != null) return
        val driver = UsbSerialProber.getDefaultProber().probeDevice(d) ?: run {
            SpLog.d(TAG, "no serial driver for ${d.deviceName}"); return
        }
        val conn = usb.openDevice(d) ?: run { SpLog.d(TAG, "could not open ${d.deviceName}"); return }
        val p = driver.ports.first()
        try {
            p.open(conn)
            rateIndex = 0
            setRate(p)
        } catch (e: Exception) {
            SpLog.d(TAG, "open failed: ${e.message}")
            runCatching { p.close() }
            return
        }
        port = p
        synchronized(framer) { framer.reset() }
        io = SerialInputOutputManager(p, ioListener).also { it.start() }
        lastFrameMs = 0L
        SpLog.d(TAG, "cable open at ${RATES[rateIndex]} baud")
        main.post(tick)
    }

    private fun setRate(p: UsbSerialPort) {
        p.setParameters(RATES[rateIndex], 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        rateSinceMs = System.currentTimeMillis()
    }

    private fun close(why: String) {
        main.removeCallbacks(tick)
        io?.stop(); io = null
        port?.let { runCatching { it.close() } }
        port = null
        val was = connected
        connected = false
        SpLog.d(TAG, "cable closed: $why")
        if (was) sink.onCableLost()
    }

    // Once a second: the hello that holds binary mode, the rate search until the
    // locator answers, and the silence check after it has.
    private val tick = object : Runnable {
        override fun run() {
            val p = port ?: return
            val now = System.currentTimeMillis()
            send(byteArrayOf(SerialFrames.HELLO))
            if (connected && now - lastFrameMs > SILENCE_MS) {
                connected = false
                sink.onCableLost()
                rateIndex = 0
                runCatching { setRate(p) }
            } else if (!connected && now - rateSinceMs > RATE_TRY_MS) {
                rateIndex = (rateIndex + 1) % RATES.size
                runCatching { setRate(p) }
                SpLog.d(TAG, "no answer; trying ${RATES[rateIndex]} baud")
            }
            main.postDelayed(this, SerialFrames.HELLO_INTERVAL_MS)
        }
    }

    private val ioListener = object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            val frames = synchronized(framer) { framer.feed(data) }
            if (frames.isEmpty()) return
            main.post {
                if (port == null) return@post
                lastFrameMs = System.currentTimeMillis()
                if (!connected) { connected = true; SpLog.d(TAG, "locator answering at ${RATES[rateIndex]} baud") }
                frames.forEach(sink::onCableFrame)
            }
        }
        override fun onRunError(e: Exception) { main.post { close("read error: ${e.message}") } }
    }

    companion object {
        /** WCH, the CH340's maker; the locator has no other USB device. */
        const val CH34X_VENDOR_ID = 0x1A86
        /** ConsoleBaudRates::kStandardRates, fastest (the default) first. */
        val RATES = intArrayOf(921_600, 460_800, 230_400, 115_200, 57_600, 38_400, 19_200, 9_600)
        const val RATE_TRY_MS = 2_500L
        /** The locator answers every hello with its periodic frame, so 5 s of nothing is a lost link. */
        const val SILENCE_MS = 5_000L
        const val WRITE_TIMEOUT_MS = 200
    }
}
