package com.steampigeon.flightmanager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager as SystemBluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.location.LocationManagerCompat
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manages BLE discovery, connection, MTU negotiation, and GATT data transfer.
 * No Compose dependencies.
 *
 * ---------------------------------------------------------------------------
 * PERMISSION NOTE
 * ---------------------------------------------------------------------------
 * This class is annotated @SuppressLint("MissingPermission") because all
 * required Bluetooth permissions (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, and
 * ACCESS_FINE_LOCATION) are verified by RocketApp before BluetoothService
 * starts, which is before any method in this class is called. Centralising
 * the permission check at the entry point avoids propagating
 * @RequiresPermission annotations through every method in the call chain
 * without hiding or skipping the actual check.
 *
 * ---------------------------------------------------------------------------
 * HARDWARE — VG6328A
 * ---------------------------------------------------------------------------
 * Discovery: unfiltered BLE scan filtered in-callback by [macPrefix] (first
 * two bytes of MAC address). The VG6328A does not advertise a service UUID.
 *
 * GATT layout (confirmed via onServicesDiscovered log):
 *   Service  0000ffe0  FFE0
 *   RX char  0000ffe1  WRITE + WRITE_NO_RESP  (phone → device)
 *   TX char  0000ffe2  NOTIFY                 (device → phone)
 *     CCCD   00002902
 *
 * MTU [REQUESTED_MTU] = 247 → 244-byte usable payload (247 − 3 ATT overhead).
 *
 * Data flow:
 *   Inbound  → onCharacteristicChanged → [onDataReceived] callback
 *              → BluetoothService accumulator → extractPackets → _packets
 *   Outbound → [sendData] → writeCharacteristic on FFE1
 *
 * Call [cleanup] from onDestroy / DisposableEffect.onDispose.
 */
@SuppressLint("MissingPermission")
class BluetoothManager(private val appContext: Context) {

    private val tag = "BluetoothManager"

    // -------------------------------------------------------------------------
    // Hardware identification
    // -------------------------------------------------------------------------

    private val macPrefix = "D8:67"

    // -------------------------------------------------------------------------
    // GATT UUIDs
    // -------------------------------------------------------------------------

    private val txCharUuid = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
    private val rxCharUuid = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val cccdUuid   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val serviceUuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        private const val SCAN_DURATION_MS        = 3_000L
        const val REQUESTED_MTU                   = 247
        private const val MAX_RECONNECT_ATTEMPTS  = 5
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
        private const val DATA_TIMEOUT_MS         = 10_000L  // phantom-connection watchdog
        private const val MAX_MISSED_HEALTH_PROBES = 3       // probes before declaring phantom

        // ── Outbound write queue ──────────────────────────────────────────────
        /** Ceiling on queued-but-unsent writes. A link that has stopped accepting
         *  them must not grow this without bound; past the ceiling the newest
         *  write is refused, which is at least reported to the caller. */
        private const val MAX_QUEUED_WRITES       = 32
        /** How long to wait for [BluetoothGattCallback.onCharacteristicWrite]
         *  before assuming the callback is never coming. Android BLE stacks do drop
         *  them, and without this a single lost callback would wedge the queue for
         *  the life of the connection — strictly worse than the unserialized writes
         *  this replaces. Generous: a write is normally acknowledged within a
         *  connection interval or two. */
        private const val WRITE_TIMEOUT_MS        = 2_000L
        /** Backoff before re-offering a write the stack refused outright. One
         *  connection interval is the natural unit; the stack is telling us it is
         *  busy with something this class does not own. */
        private const val WRITE_RETRY_DELAY_MS    = 20L
        private const val MAX_WRITE_RETRIES       = 5
    }

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    private val systemBtManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as SystemBluetoothManager
    private val bluetoothAdapter get() = systemBtManager.adapter
    private val bleScanner       get() = bluetoothAdapter?.bluetoothLeScanner

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectionHealthJob: Job? = null
    private var reconnectAttempts = 0

    // Timestamp of the last byte received from any GATT characteristic notification.
    // Updated by [recordDataReceived]; read by the health watchdog.
    private var lastDataTime: Long = 0L

    private val discoveredDevices = mutableMapOf<String, BluetoothDevice>()

    // Every BluetoothGatt handle this instance has ever opened, keyed by device
    // address. This is the authoritative record of open connections — NOT
    // systemBtManager.getConnectedDevices(), which only covers the GATT server
    // role and misses client connections initiated by this app.
    private val activeGattHandles = mutableMapOf<String, BluetoothGatt>()

    // Outbound writes, in the order they were handed to [sendData], one on the air
    // at a time.
    //
    // Android permits exactly ONE outstanding GATT operation per connection. A
    // write issued while another is still in flight is not queued by the stack —
    // it is refused on the spot, returning ERROR_GATT_WRITE_REQUEST_BUSY on API 33+
    // and false below it. Without this queue that refusal reached the caller as a
    // send failure, and the app reported it as a fault of the DEVICE: pressing
    // "Search N channels" while the 2 s ReceiverInfo poll happened to have a write
    // outstanding produced "No response from the receiver" instantly, with nothing
    // ever transmitted. The poll only runs while the locator is silent — which is
    // the state a search exists for — so the collision was concentrated precisely
    // where it was most confusing, and pressing the button again worked.
    //
    // Everything below is guarded by [writeLock]; the GATT call itself is made
    // outside it, so a binder call never happens with the lock held.
    private val writeLock = Any()
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var writeRetries = 0
    // Identifies the write the timeout job below is watching. A callback that
    // arrives before the job is even armed would otherwise leave a watchdog running
    // against a write that has already completed, and it would go on to drop
    // whichever write happened to be in flight when it fired.
    private var writeSeq = 0L
    private var writeWatchdogJob: Job? = null

    // The handle for the currently intended target device.
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    var negotiatedMtu: Int = 23
        private set

    /** Wired by BluetoothService. Called on GATT callback thread with inbound bytes. */
    var onDataReceived: ((ByteArray) -> Unit)? = null

    /**
     * Wired by BluetoothService. Invoked by the health watchdog to send a
     * receiver-info request — a message the receiver itself answers even when no
     * locator is transmitting. Its response lets the watchdog tell a live-but-idle
     * receiver (rocket powered off / on the pad / out of radio range) from a
     * phantom (BLE-stack-cached) connection that delivers nothing at all.
     */
    var onHealthProbe: (() -> Unit)? = null

    // -------------------------------------------------------------------------
    // Bluetooth enable
    // -------------------------------------------------------------------------

    fun enableBluetooth(): Boolean? {
        return when (bluetoothAdapter?.isEnabled) {
            true  -> { emit(BluetoothConnectionState.Enabled);      true  }
            false -> { emit(BluetoothConnectionState.Enabling);     false }
            null  -> { emit(BluetoothConnectionState.NotSupported); null  }
        }.also { SpLog.d(tag, "enableBluetooth → $it") }
    }

    fun buildEnableBluetoothIntent(): Intent =
        Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)

    /**
     * Called by [BluetoothService] whenever bytes arrive from the GATT
     * characteristic notification. Used by the health watchdog to distinguish
     * a live connection from a phantom (OS-cached) one.
     */
    fun recordDataReceived() {
        lastDataTime = System.currentTimeMillis()
    }

    /**
     * Immediately reconnect to the known device (or fall back to a scan if
     * no device is recorded). Called from the UI layer when the app is reopened
     * while the connection state is stale (e.g. [BluetoothConnectionState.Disconnected]
     * or [BluetoothConnectionState.Connected] with the activity having been
     * destroyed). Cancels any pending reconnect backoff so the attempt is
     * immediate rather than waiting for the next scheduled retry.
     */
    fun forceReconnect() {
        cancelReconnect()
        val knownDevice = BluetoothManagerRepository.receiverDevice.value
        SpLog.d(tag, "forceReconnect() — device: ${knownDevice?.address}")
        if (knownDevice != null) {
            disconnectGatt()
            connectGatt(knownDevice)
        } else {
            emit(BluetoothConnectionState.Enabled)
        }
    }

    // -------------------------------------------------------------------------
    // BLE scan
    // -------------------------------------------------------------------------

    fun startScan() {
        // Guard against duplicate calls: if the timeout job is still active a scan
        // is already running.  Letting a second bleScanner.startScan() through would
        // cause SCAN_FAILED_ALREADY_STARTED → onScanFailed → spurious PairingFailed.
        if (scanTimeoutJob?.isActive == true) {
            SpLog.d(tag, "startScan: scan already in progress — ignoring duplicate call")
            return
        }
        if (!preflightScanChecks()) return

        discoveredDevices.clear()
        BluetoothManagerRepository.updateScannedDevices(emptyList())

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        SpLog.d(tag, "Starting unfiltered scan — MAC prefix \"$macPrefix\" for ${SCAN_DURATION_MS}ms")
        bleScanner!!.startScan(emptyList(), settings, scanCallback)
        emit(BluetoothConnectionState.AssociateStart)

        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_DURATION_MS)
            stopScan()
            val found = discoveredDevices.values.toList()
            SpLog.d(tag, "Scan window closed — ${found.size} device(s) found")
            if (found.isEmpty()) emit(BluetoothConnectionState.NoDevicesAvailable)
            else {
                BluetoothManagerRepository.updateScannedDevices(found)
                emit(BluetoothConnectionState.DevicesFound)
            }
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        bleScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address ?: return
            if (!address.startsWith(macPrefix, ignoreCase = true)) return
            if (discoveredDevices.containsKey(address)) return
            SpLog.d(tag, "MAC prefix match: $address  name: ${device.name}  RSSI: ${result.rssi}")
            discoveredDevices[address] = device
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "Scan failed: ${scanErrorString(errorCode)}")
            // SCAN_FAILED_ALREADY_STARTED means a scan is already running — not a
            // real failure.  The in-progress scan will complete normally, so suppress
            // the state change to avoid a spurious PairingFailed flash in the UI.
            if (errorCode != ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
                emit(BluetoothConnectionState.PairingFailed)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Device selection
    // -------------------------------------------------------------------------

    fun selectDevice(device: BluetoothDevice) {
        SpLog.d(tag, "User selected: ${device.address}  name: ${device.name}")
        BluetoothManagerRepository.updateReceiverDevice(device)
        connectGatt(device)
    }

    // -------------------------------------------------------------------------
    // GATT connection
    //
    // activeGattHandles tracks every handle this instance has opened so that
    // no connection is ever orphaned. closeAllExceptTarget() is called before
    // every new connectGatt() to ensure only one connection exists at a time,
    // regardless of how many device switches or reconnects have occurred and
    // regardless of whether a previous app session left stale connections.
    //
    // Why not use systemBtManager.getConnectedDevices()?
    //   That API returns devices connected to the local GATT *server*, not
    //   connections initiated by this app as a GATT *client*. It does not see
    //   client-side connections and cannot be used for this purpose.
    // -------------------------------------------------------------------------

    fun connectGatt(device: BluetoothDevice) {
        SpLog.d(tag, "connectGatt → ${device.address}  (active handles: ${activeGattHandles.keys})")
        closeAllExceptTarget(device.address)
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback)
        activeGattHandles[device.address] = bluetoothGatt!!
        rxCharacteristic = null
    }

    /**
     * Closes and removes every handle in [activeGattHandles] whose address
     * does not match [targetAddress]. Called before every [connectGatt] so
     * there is always at most one live connection.
     */
    private fun closeAllExceptTarget(targetAddress: String) {
        val toClose = activeGattHandles.filter { it.key != targetAddress }
        for ((address, gatt) in toClose) {
            SpLog.d(tag, "Closing stale handle for $address")
            gatt.disconnect()
            gatt.close()
            activeGattHandles.remove(address)
        }
        // Also close the tracked primary handle if it points to a different device.
        bluetoothGatt?.let { current ->
            if (current.device.address != targetAddress) {
                SpLog.d(tag, "Closing primary handle for ${current.device.address}")
                current.disconnect()
                current.close()
                bluetoothGatt = null
                rxCharacteristic = null
            }
        }
    }

    fun disconnectGatt() {
        cancelReconnect()
        closeAllExceptTarget("")   // "" matches nothing → closes every handle
        bluetoothGatt = null
        rxCharacteristic = null
        clearWriteQueue()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        // Step 1: request MTU before service discovery to avoid fragmentation.
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED
                        && status == BluetoothGatt.GATT_SUCCESS -> {
                    reconnectAttempts = 0
                    SpLog.d(tag, "GATT connected to ${gatt.device.address} — requesting MTU $REQUESTED_MTU")
                    emit(BluetoothConnectionState.Connected)
                    gatt.requestMtu(REQUESTED_MTU)
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(tag, "GATT disconnected from ${gatt.device.address} (status $status)")
                    onAclDisconnected(gatt.device, source = "GATT callback")
                }
                else -> {
                    Log.e(tag, "GATT error: status=$status newState=$newState on ${gatt.device.address}")
                    onAclDisconnected(gatt.device, source = "GATT error")
                }
            }
        }

        // Step 2: MTU negotiated — discover services.
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            SpLog.d(tag, "MTU negotiated: $mtu (payload: ${mtu - 3} bytes)")
            if (mtu - 3 < REQUESTED_MTU - 3)
                Log.w(tag, "MTU below requested — peripheral may not support $REQUESTED_MTU")
            gatt.discoverServices()
        }

        // Step 3: log full GATT table, locate RX and TX characteristics.
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(tag, "Service discovery failed: $status"); return
            }
            // Gated as a BLOCK, not per-line: this walks every service,
            // characteristic and descriptor on the peripheral and builds a string
            // for each one. Left ungated, a release build did all of that work —
            // and published the peripheral's entire GATT layout to logcat — on
            // every single connect, including every automatic reconnect.
            if (SpLog.enabled) {
                SpLog.d(tag, "=== GATT table for ${gatt.device.address} ===")
                for (svc in gatt.services) {
                    SpLog.d(tag, "  SERVICE ${svc.uuid}")
                    for (char in svc.characteristics) {
                        val props = buildString {
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)       append("NOTIFY ")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)     append("INDICATE ")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)        append("WRITE ")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("WRITE_NO_RESP ")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0)         append("READ ")
                        }.trim()
                        SpLog.d(tag, "    CHAR ${char.uuid}  [$props]")
                        for (desc in char.descriptors) SpLog.d(tag, "      DESC ${desc.uuid}")
                    }
                }
                SpLog.d(tag, "=== end GATT table ===")
            }

            val service = gatt.getService(serviceUuid) ?: run {
                Log.e(tag, "Service $serviceUuid not found — see GATT table above"); return
            }
            rxCharacteristic = service.getCharacteristic(rxCharUuid) ?: run {
                Log.e(tag, "RX char $rxCharUuid not found — see GATT table above"); null
            }
            val txChar = service.getCharacteristic(txCharUuid) ?: run {
                Log.e(tag, "TX char $txCharUuid not found — see GATT table above"); return
            }
            enableNotifications(gatt, txChar)
        }

        // Step 4: CCCD written — GATT fully configured, data can flow.
        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            if (descriptor.uuid == cccdUuid) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    SpLog.d(tag, "TX notifications enabled — GATT ready")
                    emit(BluetoothConnectionState.Ready)
                    startHealthWatchdog()
                } else {
                    Log.e(tag, "Failed to enable TX notifications: $status")
                }
            }
        }

        // Inbound data (API < 33)
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == txCharUuid)
                characteristic.value?.let { onDataReceived?.invoke(it) }
        }

        // Inbound data (API 33+)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (characteristic.uuid == txCharUuid) onDataReceived?.invoke(value)
        }

        // The signal that releases the next queued write. Until this arrived the
        // stack refuses anything else on this connection, which is the whole reason
        // [writeQueue] exists — this callback is what advances it.
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (characteristic.uuid == rxCharUuid) onWriteComplete(status)
            else if (status != BluetoothGatt.GATT_SUCCESS)
                Log.e(tag, "Write to ${characteristic.uuid} failed: $status")
        }
    }

    // -------------------------------------------------------------------------
    // Notification setup
    // -------------------------------------------------------------------------

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val supportsNotify   = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY   != 0
        val supportsIndicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!supportsNotify && !supportsIndicate) {
            Log.e(tag, "TX char ${characteristic.uuid} has neither NOTIFY nor INDICATE"); return
        }
        gatt.setCharacteristicNotification(characteristic, true)

        val cccd = characteristic.getDescriptor(cccdUuid)
            ?: characteristic.descriptors.firstOrNull().also { fallback ->
                if (fallback != null)
                    Log.w(tag, "CCCD not found by UUID — using descriptor ${fallback.uuid}")
                else {
                    Log.e(tag, "No descriptors on TX char — attempting without CCCD write")
                    emit(BluetoothConnectionState.Ready)
                    return
                }
            }

        val enableValue = if (supportsIndicate)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd!!, enableValue)
        } else {
            @Suppress("DEPRECATION")
            cccd!!.value = enableValue
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
        SpLog.d(tag, "Writing CCCD ${cccd.uuid} on TX char")
    }

    // -------------------------------------------------------------------------
    // Outbound data
    // -------------------------------------------------------------------------

    /**
     * Hand [data] to the receiver.
     *
     * Returns whether the write was ACCEPTED FOR TRANSMISSION, not whether it has
     * been transmitted — the two cannot be the same thing on a link that carries
     * one operation at a time. Callers already treat the result this way: it maps
     * to [com.steampigeon.flightmanager.data.LocatorMessageState.Sent], and every
     * flow that needs to know a message actually landed confirms it by reading
     * something back (ADR-0011's recognition cycle, the search's own terminator).
     *
     * False now means only what it says: there is no connection to write to, or the
     * queue is full because the link has stopped draining. It no longer means "the
     * stack was momentarily busy", which is a condition the caller could do nothing
     * useful with and which the queue below absorbs.
     */
    fun sendData(data: ByteArray): Boolean {
        if (bluetoothGatt == null) { Log.e(tag, "sendData: not connected"); return false }
        if (rxCharacteristic == null) { Log.e(tag, "sendData: RX char not ready"); return false }
        val maxPayload = negotiatedMtu - 3
        // Fragments are enqueued individually and so go out in order, each waiting
        // for the previous one's acknowledgment. Written back to back as they were,
        // every chunk after the first hit the one-operation limit and was refused —
        // a fragmented message could not have arrived intact.
        if (data.size > maxPayload)
            Log.w(tag, "Fragmenting ${data.size} bytes into ${maxPayload}-byte chunks")
        // All of it or none of it. Enqueuing fragment by fragment and stopping when
        // the queue filled would put the first half of a message on the wire and
        // drop the rest, which reaches the receiver as a malformed frame rather than
        // as the absence of one.
        val chunks = (data.size + maxPayload - 1) / maxPayload
        synchronized(writeLock) {
            if (writeQueue.size + chunks > MAX_QUEUED_WRITES) {
                Log.e(tag, "Write queue full (${writeQueue.size}/$MAX_QUEUED_WRITES) — " +
                        "dropping ${data.size} bytes")
                return false
            }
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + maxPayload, data.size)
                writeQueue.addLast(data.copyOfRange(offset, end))
                offset = end
            }
        }
        pumpWrites()
        return true
    }

    /**
     * Issue the head of the queue if nothing is outstanding.
     *
     * Called from [sendData] on whatever thread the caller is on, and from the
     * write callback on the GATT callback thread. The in-flight flag is claimed
     * under the lock, so only one of them can be issuing at a time.
     */
    private fun pumpWrites() {
        val chunk: ByteArray
        val seq: Long
        synchronized(writeLock) {
            if (writeInFlight) return
            chunk = writeQueue.firstOrNull() ?: return
            writeInFlight = true
            seq = ++writeSeq
        }
        val gatt = bluetoothGatt
        val rxChar = rxCharacteristic
        if (gatt == null || rxChar == null) {
            // The connection went away underneath the queue. Its contents were
            // addressed to a link that no longer exists; carrying them into the
            // next one is the late-delivery hazard ADR-0011 documents.
            Log.w(tag, "Write pump: connection gone — discarding queued writes")
            clearWriteQueue()
            return
        }
        // Armed BEFORE the write, so a callback that beats us back cannot find an
        // unarmed watchdog and leave the queue unguarded.
        armWriteWatchdog(seq)
        if (writeCharacteristic(gatt, rxChar, chunk)) {
            synchronized(writeLock) { writeRetries = 0 }
            return
        }
        // Refused with nothing of ours outstanding: something outside this class
        // owns the stack for the moment. Keep the write and re-offer it rather than
        // reporting a failure the caller cannot act on.
        val giveUp = synchronized(writeLock) {
            writeWatchdogJob?.cancel()
            writeInFlight = false
            if (++writeRetries > MAX_WRITE_RETRIES) {
                writeQueue.removeFirstOrNull()
                writeRetries = 0
                true
            } else false
        }
        if (giveUp)
            Log.e(tag, "Write refused $MAX_WRITE_RETRIES times — dropping ${chunk.size} bytes")
        scope.launch { delay(WRITE_RETRY_DELAY_MS); pumpWrites() }
    }

    /** Completion — success or failure, both of which retire the write. A write the
     *  peripheral rejected is not re-offered: the stack delivered it and got an
     *  error back, so repeating it would only produce the same error. */
    private fun onWriteComplete(status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS)
            Log.e(tag, "Write to RX char failed: $status")
        synchronized(writeLock) {
            // Nothing outstanding means this callback belongs to a write already
            // retired by the watchdog, or to one discarded with the connection.
            // Retiring the head on the strength of it would drop a queued message
            // that has not been sent at all.
            if (!writeInFlight) return
            writeWatchdogJob?.cancel()
            writeQueue.removeFirstOrNull()
            writeInFlight = false
            writeRetries = 0
        }
        pumpWrites()
    }

    private fun armWriteWatchdog(seq: Long) = synchronized(writeLock) {
        writeWatchdogJob?.cancel()
        writeWatchdogJob = scope.launch {
            delay(WRITE_TIMEOUT_MS)
            val dropped = synchronized(writeLock) {
                // Someone else's write by now — this job is stale and has nothing
                // to say about it.
                if (!writeInFlight || writeSeq != seq) return@launch
                val head = writeQueue.removeFirstOrNull()
                writeInFlight = false
                writeRetries = 0
                head
            }
            Log.e(tag, "No write acknowledgment in ${WRITE_TIMEOUT_MS}ms — " +
                    "dropping ${dropped?.size ?: 0} bytes and continuing")
            pumpWrites()
        }
    }

    /** Drop everything outstanding. Called when the link goes away: a queued write
     *  belongs to the connection it was made on, and delivering it across a
     *  reconnect would fire it out of the flow that queued it. */
    private fun clearWriteQueue() {
        synchronized(writeLock) {
            writeWatchdogJob?.cancel()
            if (writeQueue.isNotEmpty())
                Log.w(tag, "Discarding ${writeQueue.size} queued write(s)")
            writeQueue.clear()
            writeInFlight = false
            writeRetries = 0
        }
    }

    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic, data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    // -------------------------------------------------------------------------
    // Disconnect / reconnect
    // -------------------------------------------------------------------------

    /**
     * Single entry point for all disconnect sources (GATT callback and
     * ACTION_ACL_DISCONNECTED broadcast). Idempotent — if the handle was
     * already closed by the other source the map lookup is a no-op.
     */
    fun onAclDisconnected(
        device: BluetoothDevice? = BluetoothManagerRepository.receiverDevice.value,
        source: String = "ACL broadcast"
    ) {
        Log.w(tag, "Disconnect [$source] — device: ${device?.address}")

        // Clean up the GATT handle for the disconnecting device regardless of
        // whether it is still the selected receiver.
        device?.address?.let { address ->
            activeGattHandles[address]?.let { gatt ->
                gatt.close()
                activeGattHandles.remove(address)
            }
        }
        if (bluetoothGatt?.device?.address == device?.address) {
            bluetoothGatt = null
            rxCharacteristic = null
            clearWriteQueue()
        }

        // If the disconnecting device is no longer the selected receiver (the user
        // switched to a new device while this one was being torn down), suppress
        // the Disconnected state emission and skip reconnect entirely.  Emitting
        // Disconnected here would clobber the new connection's Ready state, leaving
        // the app stuck disconnected even though the new device is fully connected.
        val selectedDevice = BluetoothManagerRepository.receiverDevice.value
        if (device != null && selectedDevice != null &&
            device.address != selectedDevice.address) {
            SpLog.d(tag, "Disconnect of superseded device ${device.address} — suppressing state change")
            return
        }

        connectionHealthJob?.cancel()
        emit(BluetoothConnectionState.Disconnected)
        val target = device ?: run {
            Log.w(tag, "No device to reconnect to — returning to scan")
            emit(BluetoothConnectionState.Enabled)
            return
        }
        // If receiverDevice was explicitly cleared, a fresh scan is already in progress.
        if (selectedDevice == null) {
            SpLog.d(tag, "receiverDevice is null — skipping reconnect, scan in progress")
            return
        }
        scheduleReconnect(target)
    }

    private fun scheduleReconnect(device: BluetoothDevice) {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(tag, "Max reconnect attempts — falling back to scan")
            reconnectAttempts = 0
            emit(BluetoothConnectionState.Enabled)
            return
        }
        val delayMs = BASE_RECONNECT_DELAY_MS * (1L shl reconnectAttempts)
        reconnectAttempts++
        SpLog.d(tag, "Reconnect attempt $reconnectAttempts in ${delayMs}ms → ${device.address}")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (bluetoothAdapter?.isEnabled == true) connectGatt(device)
            else emit(BluetoothConnectionState.Enabling)
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
    }

    /**
     * Starts a repeating watchdog that, every [DATA_TIMEOUT_MS] after the
     * connection reaches [BluetoothConnectionState.Ready], checks whether any
     * GATT notification has arrived since the previous check.
     *
     * A silent window does NOT immediately mean a dead link: a healthy receiver
     * has nothing to relay when no locator is transmitting (rocket powered off,
     * on the pad, or out of radio range). To tell that apart from a phantom
     * (BLE-stack-cached) connection, each silent window triggers [onHealthProbe],
     * which asks the receiver for its own info — a message a live receiver answers
     * regardless of locator activity. Only after [MAX_MISSED_HEALTH_PROBES]
     * consecutive probes go unanswered is the link treated as phantom and
     * [onAclDisconnected] called to force a reconnect.
     */
    private fun startHealthWatchdog() {
        connectionHealthJob?.cancel()
        connectionHealthJob = scope.launch {
            var missedProbes = 0
            var lastCheck = System.currentTimeMillis()
            while (true) {
                delay(DATA_TIMEOUT_MS)
                if (lastDataTime >= lastCheck) {
                    // Locator relay data or a probe response arrived — link is live.
                    //
                    // Deliberately silent. This used to log every tick, which is
                    // one line every DATA_TIMEOUT_MS forever while connected,
                    // carrying no information beyond "nothing is wrong" — enough
                    // to bury the lines that do mean something. The watchdog
                    // speaks only when it misses.
                    missedProbes = 0
                } else {
                    missedProbes++
                    if (missedProbes >= MAX_MISSED_HEALTH_PROBES) {
                        Log.w(tag, "Health watchdog: $missedProbes probes unanswered — " +
                                "probable phantom connection, forcing reconnect")
                        onAclDisconnected(bluetoothGatt?.device, source = "health watchdog")
                        return@launch
                    }
                    SpLog.d(tag, "Health watchdog: silent for ${DATA_TIMEOUT_MS}ms — " +
                            "probing receiver (miss $missedProbes/$MAX_MISSED_HEALTH_PROBES)")
                    onHealthProbe?.invoke()
                }
                lastCheck = System.currentTimeMillis()
            }
        }
    }

    // -------------------------------------------------------------------------
    // State-machine dispatcher
    // -------------------------------------------------------------------------

    fun handleConnectionState(state: BluetoothConnectionState) {
        when (state) {
            BluetoothConnectionState.Starting ->
                enableBluetooth()
            BluetoothConnectionState.Enabled,
            BluetoothConnectionState.NoDevicesAvailable,
            BluetoothConnectionState.LocationDisabled -> {
                val knownDevice = BluetoothManagerRepository.receiverDevice.value
                if (knownDevice == null) startScan() else connectGatt(knownDevice)
            }
            else -> { }
        }
    }

    // -------------------------------------------------------------------------
    // Unpairing
    // -------------------------------------------------------------------------

    fun unpairDevice() {
        stopScan()
        disconnectGatt()
        BluetoothManagerRepository.receiverDevice.value?.let { device ->
            try {
                device.javaClass.getMethod("removeBond").invoke(device)
                SpLog.d(tag, "Bond removed for ${device.address}")
            } catch (e: Exception) {
                Log.e(tag, "removeBond failed", e)
            }
        }
        BluetoothManagerRepository.updateReceiverDevice(null)
        BluetoothManagerRepository.updateScannedDevices(emptyList())
        emit(BluetoothConnectionState.Idle)
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    fun cleanup() {
        connectionHealthJob?.cancel()
        stopScan()
        disconnectGatt()
        scope.coroutineContext[Job]?.cancel()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun emit(state: BluetoothConnectionState) =
        BluetoothManagerRepository.updateBluetoothConnectionState(state)

    private fun preflightScanChecks(): Boolean {
        if (bleScanner == null) {
            Log.e(tag, "BLE scanner unavailable"); emit(BluetoothConnectionState.PairingFailed); return false
        }
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) {
            Log.e(tag, "Location services off — BLE scan will return nothing")
            emit(BluetoothConnectionState.LocationDisabled)
            return false
        }
        return true
    }

    private fun scanErrorString(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED                -> "ALREADY_STARTED"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED            -> "FEATURE_UNSUPPORTED"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR                 -> "INTERNAL_ERROR"
        5                                                        -> "OUT_OF_HARDWARE_RESOURCES"
        6                                                        -> "SCANNING_TOO_FREQUENTLY"
        else                                                     -> "UNKNOWN ($code)"
    }
}