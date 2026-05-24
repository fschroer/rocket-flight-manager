package com.steampigeon.flightmanager

import android.Manifest
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
import androidx.annotation.RequiresPermission
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
 * HARDWARE CONFIGURATION — VG6328A
 * ---------------------------------------------------------------------------
 *
 * Device discovery:
 *   The VG6328A does not support 128-bit service UUID advertisement.
 *   Discovery uses an unfiltered BLE scan and matches on the first two bytes
 *   of the MAC address ([MAC_PREFIX], currently "D8:67"). All devices whose
 *   address starts with this prefix are collected and presented to the user.
 *
 * GATT characteristics — must match VG6328A configuration:
 *   CHAR_UUID_TX  — characteristic the VG6328A notifies on (device → phone).
 *                   Must have NOTIFY property enabled on the hardware.
 *   CHAR_UUID_RX  — characteristic the phone writes to (phone → device).
 *                   Must have WRITE or WRITE_WITHOUT_RESPONSE on the hardware.
 *
 * If the VG6328A uses the Nordic UART Service layout these are typically:
 *   TX  6e400003-b5a3-f393-e0a9-e50e24dcca9e  (notify, device→phone)
 *   RX  6e400002-b5a3-f393-e0a9-e50e24dcca9e  (write,  phone→device)
 * ---------------------------------------------------------------------------
 *
 * MTU: [REQUESTED_MTU] (247) is requested immediately after connection.
 * Usable payload per write/notification = [negotiatedMtu] - 3 (ATT overhead).
 *
 * Data flow:
 *   Inbound  → onCharacteristicChanged → [onDataReceived] callback
 *              → BluetoothService accumulator → extractPackets → _packets flow
 *   Outbound → [sendData] → writeCharacteristic on CHAR_UUID_RX
 *
 * Call [cleanup] from onDestroy / DisposableEffect.onDispose.
 */
class BluetoothManager(private val appContext: Context) {

    private val tag = "BluetoothManager"

    // -------------------------------------------------------------------------
    // Hardware identification
    // -------------------------------------------------------------------------

    // First two bytes of the VG6328A MAC address, upper-case, colon-separated.
    // All BLE devices whose address starts with this prefix are treated as
    // candidates. Adjust if your hardware uses a different OUI.
    private val macPrefix = "D8:67"

    // -------------------------------------------------------------------------
    // GATT UUIDs — adjust to match VG6328A characteristic configuration
    // -------------------------------------------------------------------------

    // Device → phone (notifications) — VG6328A FFE2
    private val txCharUuid = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")

    // Phone → device (write / write-no-response) — VG6328A FFE1
    private val rxCharUuid = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    // Standard CCCD descriptor — confirmed present on TX characteristic
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Service UUID — VG6328A FFE0 service
    private val serviceUuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        private const val SCAN_DURATION_MS        = 8_000L
        // Request 247 — gives 244-byte ATT payload (247 - 3 byte ATT header).
        // Actual negotiated value depends on peripheral; stored in negotiatedMtu.
        const val REQUESTED_MTU                   = 247
        private const val MAX_RECONNECT_ATTEMPTS  = 5
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
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
    private var reconnectAttempts = 0

    private val discoveredDevices = mutableMapOf<String, BluetoothDevice>()

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    var negotiatedMtu: Int = 23   // updated in onMtuChanged; default ATT MTU is 23
        private set

    /**
     * Set by BluetoothService after construction.
     * Called on a binder/callback thread with raw bytes from the TX characteristic.
     * BluetoothService feeds these into its existing packet accumulator.
     */
    var onDataReceived: ((ByteArray) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Bluetooth enable
    // -------------------------------------------------------------------------

    @SuppressWarnings("MissingPermission")
    fun enableBluetooth(): Boolean? {
        return when (bluetoothAdapter?.isEnabled) {
            true  -> { emit(BluetoothConnectionState.Enabled);     true  }
            false -> { emit(BluetoothConnectionState.Enabling);    false }
            null  -> { emit(BluetoothConnectionState.NotSupported); null  }
        }.also { Log.d(tag, "enableBluetooth → $it") }
    }

    fun buildEnableBluetoothIntent(): Intent =
        Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)

    // -------------------------------------------------------------------------
    // BLE scan — unfiltered, matched on MAC address prefix
    //
    // ScanFilter.setDeviceAddress() requires a full exact address, so prefix
    // matching must be done manually in the callback. The scan runs unfiltered
    // for the full SCAN_DURATION_MS window, collecting every device whose
    // address starts with [macPrefix]. Everything else is silently ignored.
    // -------------------------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        if (!preflightScanChecks()) return

        discoveredDevices.clear()
        BluetoothManagerRepository.updateScannedDevices(emptyList())

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        Log.d(tag, "Starting unfiltered scan — collecting MAC prefix \"$macPrefix\" " +
                "for ${SCAN_DURATION_MS}ms")
        // Empty filter list = no hardware filtering; all advertising devices are reported.
        bleScanner!!.startScan(emptyList(), settings, scanCallback)
        emit(BluetoothConnectionState.AssociateStart)

        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_DURATION_MS)
            stopScan()
            val found = discoveredDevices.values.toList()
            Log.d(tag, "Scan window closed — ${found.size} device(s) found")
            if (found.isEmpty()) {
                emit(BluetoothConnectionState.NoDevicesAvailable)
            } else {
                BluetoothManagerRepository.updateScannedDevices(found)
                emit(BluetoothConnectionState.DevicesFound)
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    fun stopScan() {
        scanTimeoutJob?.cancel()
        bleScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressWarnings("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address ?: return
            // Reject devices that don't start with the VG6328A MAC prefix.
            if (!address.startsWith(macPrefix, ignoreCase = true)) return
            // Deduplicate — scan window may report the same device multiple times.
            if (discoveredDevices.containsKey(address)) return
            Log.d(tag, "MAC prefix match: $address  name: ${device.name}  RSSI: ${result.rssi}")
            discoveredDevices[address] = device
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "Scan failed: ${scanErrorString(errorCode)}")
            emit(BluetoothConnectionState.PairingFailed)
        }
    }

    // -------------------------------------------------------------------------
    // Device selection (called by UI after user picks from DevicesFound list)
    // -------------------------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun selectDevice(device: BluetoothDevice) {
        Log.d(tag, "User selected: ${device.address}  name: ${device.name}")
        BluetoothManagerRepository.updateLocatorDevice(device)
        connectGatt(device)
    }

    // -------------------------------------------------------------------------
    // GATT connection
    // -------------------------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectGatt(device: BluetoothDevice) {
        Log.d(tag, "Connecting GATT to ${device.address}")
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(
            appContext,
            /*autoConnect=*/ false,
            gattCallback
        )
    }

    @SuppressWarnings("MissingPermission")
    fun disconnectGatt() {
        cancelReconnect()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        // Step 1 — connected: request MTU immediately before service discovery.
        // Requesting MTU first avoids the race condition where discovery completes
        // with the default 23-byte MTU and large writes get fragmented unexpectedly.
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    reconnectAttempts = 0
                    Log.d(tag, "GATT connected to ${gatt.device.address} — requesting MTU $REQUESTED_MTU")
                    emit(BluetoothConnectionState.Connected)
                    gatt.requestMtu(REQUESTED_MTU)
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(tag, "GATT disconnected from ${gatt.device.address} (status $status)")
                    // Delegate to onAclDisconnected so the same cleanup + reconnect
                    // path runs regardless of whether the disconnect was detected here
                    // or via the ACTION_ACL_DISCONNECTED broadcast.
                    onAclDisconnected(gatt.device, source = "GATT callback")
                }
                else -> {
                    Log.e(tag, "GATT connection error: status=$status newState=$newState")
                    onAclDisconnected(gatt.device, source = "GATT error")
                }
            }
        }

        // Step 2 — MTU negotiated: now discover services.
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            val payload = mtu - 3
            Log.d(tag, "MTU negotiated: $mtu (usable payload: $payload bytes)")
            if (payload < REQUESTED_MTU - 3) {
                Log.w(tag, "Negotiated MTU payload ($payload) is below requested " +
                        "${REQUESTED_MTU - 3} — peripheral may not support larger MTU")
            }
            gatt.discoverServices()
        }

        // Step 3 — services discovered: log the full GATT table, then find
        // RX and TX characteristics and enable notifications.
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(tag, "Service discovery failed: $status")
                return
            }

            // Log every service → characteristic → descriptor so the actual
            // UUIDs exposed by the VG6328A are visible in logcat. This makes
            // it straightforward to correct txCharUuid / rxCharUuid / serviceUuid
            // if any of them don't match the hardware configuration.
            Log.d(tag, "=== GATT table for ${gatt.device.address} ===")
            for (svc in gatt.services) {
                Log.d(tag, "  SERVICE ${svc.uuid}")
                for (char in svc.characteristics) {
                    val props = buildString {
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                            append("NOTIFY ")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                            append("INDICATE ")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                            append("WRITE ")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                            append("WRITE_NO_RESP ")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0)
                            append("READ ")
                    }.trim()
                    Log.d(tag, "    CHAR ${char.uuid}  [$props]")
                    for (desc in char.descriptors) {
                        Log.d(tag, "      DESC ${desc.uuid}")
                    }
                }
            }
            Log.d(tag, "=== end GATT table ===")

            // Locate the service. If serviceUuid doesn't match, the log above
            // will show the correct UUID to use.
            val service = gatt.getService(serviceUuid) ?: run {
                Log.e(tag, "Service $serviceUuid not found — see GATT table above for actual UUIDs")
                return
            }

            // Locate RX characteristic (phone → device).
            rxCharacteristic = service.getCharacteristic(rxCharUuid) ?: run {
                Log.e(tag, "RX char $rxCharUuid not found — see GATT table above")
                null
            }

            // Locate TX characteristic (device → phone, must have NOTIFY).
            val txChar = service.getCharacteristic(txCharUuid) ?: run {
                Log.e(tag, "TX char $txCharUuid not found — see GATT table above")
                return
            }

            enableNotifications(gatt, txChar)
        }

        // Step 4 — notification descriptor written: GATT setup complete.
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == cccdUuid) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(tag, "TX notifications enabled — GATT ready")
                    emit(BluetoothConnectionState.Ready)
                } else {
                    Log.e(tag, "Failed to enable TX notifications: $status")
                }
            }
        }

        // Inbound data — fires for every notification from the TX characteristic.
        // Passes raw bytes to BluetoothService via the [onDataReceived] callback.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == txCharUuid) {
                val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33+: value passed directly to the override below
                    characteristic.value  // fallback; real data arrives in the new override
                } else {
                    characteristic.value
                }
                bytes?.let { onDataReceived?.invoke(it) }
            }
        }

        // API 33+ override receives the value directly (avoids deprecated characteristic.value)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == txCharUuid) {
                onDataReceived?.invoke(value)
            }
        }

        // Confirmation that a write to the RX characteristic completed.
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(tag, "Write to RX characteristic failed: $status")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification setup helper
    // -------------------------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        // Verify the characteristic actually supports notifications.
        val supportsNotify = characteristic.properties and
                BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndicate = characteristic.properties and
                BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!supportsNotify && !supportsIndicate) {
            Log.e(tag, "TX char ${characteristic.uuid} has neither NOTIFY nor INDICATE — " +
                    "check hardware configuration")
            return
        }

        gatt.setCharacteristicNotification(characteristic, true)

        // Look up CCCD by the standard UUID first, then fall back to iterating
        // all descriptors. Some peripheral stacks expose the CCCD under a
        // short-form UUID that the Android stack may not normalise correctly.
        val cccd = characteristic.getDescriptor(cccdUuid)
            ?: characteristic.descriptors.firstOrNull().also { fallback ->
                if (fallback != null) {
                    Log.w(tag, "CCCD not found by UUID $cccdUuid — " +
                            "using first available descriptor ${fallback.uuid}")
                } else {
                    Log.e(tag, "No descriptors at all on TX char ${characteristic.uuid}. " +
                            "Attempting setCharacteristicNotification only (no CCCD write). " +
                            "Notifications may still arrive on some peripherals.")
                    // Some devices honour setCharacteristicNotification without a
                    // CCCD write. Emit Ready optimistically; if data never arrives
                    // the TX/RX UUIDs or service UUID need correcting.
                    emit(BluetoothConnectionState.Ready)
                    return
                }
            }

        val enableValue = if (supportsIndicate)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeDescriptor(cccd!!, enableValue)
            Log.d(tag, "writeDescriptor result: $result")
        } else {
            @Suppress("DEPRECATION")
            cccd!!.value = enableValue
            @Suppress("DEPRECATION")
            val result = gatt.writeDescriptor(cccd)
            Log.d(tag, "writeDescriptor result: $result")
        }
        Log.d(tag, "Writing CCCD ${cccd.uuid} to enable notifications on TX char")
    }

    // -------------------------------------------------------------------------
    // Outbound data
    // -------------------------------------------------------------------------

    /**
     * Sends [data] to the device by writing to the RX characteristic.
     *
     * Automatically fragments into [negotiatedMtu] - 3 byte chunks if the
     * payload exceeds the negotiated MTU, though in practice the protocol
     * messages should fit within a single MTU once 247 is negotiated.
     *
     * @return true if the write was accepted by the stack, false otherwise.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendData(data: ByteArray): Boolean {
        val gatt = bluetoothGatt
        val rxChar = rxCharacteristic
        if (gatt == null || rxChar == null) {
            Log.e(tag, "sendData: GATT not ready (gatt=$gatt, rxChar=$rxChar)")
            return false
        }

        val maxPayload = negotiatedMtu - 3
        return if (data.size <= maxPayload) {
            writeCharacteristic(gatt, rxChar, data)
        } else {
            // Fragment — send chunks sequentially.
            // Note: in a production implementation you should wait for each
            // onCharacteristicWrite callback before sending the next chunk.
            Log.w(tag, "Fragmenting ${data.size} bytes into ${maxPayload}-byte chunks")
            var offset = 0
            var success = true
            while (offset < data.size && success) {
                val end = minOf(offset + maxPayload, data.size)
                success = writeCharacteristic(gatt, rxChar, data.copyOfRange(offset, end))
                offset = end
            }
            success
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            result == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    // -------------------------------------------------------------------------
    // Disconnect handling — single entry point for all disconnect sources
    // -------------------------------------------------------------------------

    /**
     * Called from both the GATT callback and [BluetoothService.BondStateReceiver]
     * whenever the connection is lost, regardless of which layer detected it first.
     *
     * Guards against double-execution: if [bluetoothGatt] is already null the
     * device was already cleaned up by the other source, so only
     * [scheduleReconnect] is called (idempotent — cancels any existing job first).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun onAclDisconnected(
        device: BluetoothDevice? = BluetoothManagerRepository.locatorDevice.value,
        source: String = "ACL broadcast"
    ) {
        Log.w(tag, "Disconnect from $source — device: ${device?.address}")

        // Close GATT if still open. If the GATT callback fired first it will
        // already be null here, which is fine.
        if (bluetoothGatt != null) {
            bluetoothGatt?.close()
            bluetoothGatt = null
            rxCharacteristic = null
        }

        emit(BluetoothConnectionState.Disconnected)

        val target = device ?: run {
            Log.w(tag, "No known device — cannot reconnect, returning to scan")
            emit(BluetoothConnectionState.Enabled)
            return
        }
        scheduleReconnect(target)
    }

    // -------------------------------------------------------------------------
    // Reconnection — exponential backoff
    // -------------------------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun scheduleReconnect(device: BluetoothDevice) {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(tag, "Max reconnect attempts reached — falling back to scan")
            reconnectAttempts = 0
            emit(BluetoothConnectionState.Enabled)
            return
        }
        val delayMs = BASE_RECONNECT_DELAY_MS * (1L shl reconnectAttempts)
        reconnectAttempts++
        Log.d(tag, "Reconnect attempt $reconnectAttempts in ${delayMs}ms → ${device.address}")
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

    // -------------------------------------------------------------------------
    // State-machine dispatcher (called from RocketApp LaunchedEffect)
    // -------------------------------------------------------------------------

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun handleConnectionState(state: BluetoothConnectionState) {
        when (state) {
            BluetoothConnectionState.Starting ->
                enableBluetooth()

            BluetoothConnectionState.Enabled,
            BluetoothConnectionState.NoDevicesAvailable -> {
                val knownDevice = BluetoothManagerRepository.locatorDevice.value
                if (knownDevice == null) startScan() else connectGatt(knownDevice)
            }

            // DevicesFound → UI shows picker → calls selectDevice() → connectGatt()
            // Connected   → MTU + service discovery driven by gattCallback
            // Ready       → normal operating state, data flowing
            else -> { }
        }
    }

    // -------------------------------------------------------------------------
    // Unpairing
    // -------------------------------------------------------------------------

    fun unpairDevice() {
        stopScan()
        disconnectGatt()
        BluetoothManagerRepository.locatorDevice.value?.let { device ->
            try {
                device.javaClass.getMethod("removeBond").invoke(device)
                Log.d(tag, "Bond removed for ${device.address}")
            } catch (e: Exception) {
                Log.e(tag, "removeBond failed", e)
            }
        }
        BluetoothManagerRepository.updateLocatorDevice(null)
        BluetoothManagerRepository.updateScannedDevices(emptyList())
        emit(BluetoothConnectionState.Idle)
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    @SuppressWarnings("MissingPermission")
    fun cleanup() {
        stopScan()
        disconnectGatt()
        scope.coroutineContext[Job]?.cancel()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun emit(state: BluetoothConnectionState) =
        BluetoothManagerRepository.updateBluetoothConnectionState(state)

    @SuppressWarnings("MissingPermission")
    private fun preflightScanChecks(): Boolean {
        if (bleScanner == null) {
            Log.e(tag, "BLE scanner unavailable"); emit(BluetoothConnectionState.PairingFailed); return false
        }
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) {
            Log.e(tag, "Location services off — BLE scan will return nothing"); return false
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