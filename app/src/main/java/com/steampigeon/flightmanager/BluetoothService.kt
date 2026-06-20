package com.steampigeon.flightmanager

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.FLIGHT_DATA_ACK_SIZE
import com.steampigeon.flightmanager.data.FLIGHT_DATA_PARITY_SIZE
import com.steampigeon.flightmanager.data.FLIGHT_METADATA_PAYLOAD_SIZE
import com.steampigeon.flightmanager.data.flightDataPacketLength
import com.steampigeon.flightmanager.data.FlightDataRepository
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.MsgType
import com.steampigeon.flightmanager.data.PacketHeader
import com.steampigeon.flightmanager.data.Protocol
import com.steampigeon.flightmanager.data.ReceiverConfig
import com.steampigeon.flightmanager.data.toBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import androidx.core.content.IntentCompat

private const val TAG = "BluetoothService"
private const val CHANNEL_ID = "BluetoothService"
private const val POLY = 0xA001
private const val INIT = 0xFFFF

enum class MessageType(val messageType: UByte) {
    None(0u),
    Prelaunch(1u),
    Telemetry(2u),
    ReceiverConfig(3u),
    FlightProfileMetadata(4u),
    FlightProfileData(5u),
    DeploymentTest(6u);

    companion object {
        fun fromUByte(value: UByte) =
            entries.firstOrNull { it.messageType == value }
                ?: throw IllegalArgumentException("Invalid type: $value")
    }
}

class BluetoothService : Service() {

    // -------------------------------------------------------------------------
    // BluetoothManager — single instance, owns all GATT operations.
    // Exposed via binder so RocketApp never creates a second instance.
    // -------------------------------------------------------------------------
    lateinit var btManager: BluetoothManager
        private set

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Packet flow consumed by RocketViewModel
    private val _packets = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val packets: SharedFlow<ByteArray> = _packets.asSharedFlow()

    private var serviceStarted = false

//    @Suppress("DEPRECATION")
    private val bondStateReceiver = BondStateReceiver()
    private val binder = LocalBinder()

    // Packet accumulator — same logic as before, now fed from GATT notifications
    // instead of an RFCOMM input stream.
    private var accumulator = ByteArray(0)
    private var inboundMessageBuffer = ByteArray(Protocol.MESSAGE_BUFFER_SIZE)
    private var numBytes = 0
    private var messageReceived = false
    private var currentMessageSize = 0
    private var messageType = MessageType.None

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        btManager = BluetoothManager(applicationContext)

        // Wire GATT data notifications into the packet accumulator.
        // onDataReceived is called on a GATT callback thread (not main thread).
        btManager.onDataReceived = { bytes ->
            btManager.recordDataReceived()
            processInboundBytes(bytes)
        }

        createNotificationChannel()
        startForeground(1, getNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!serviceStarted) {
            serviceStarted = true
            registerReceiver()

            // Reset to Starting whenever the singleton repository holds a stale
            // state from a previous service instance.  Idle is the normal first-run
            // case; Disconnected / Connected / Ready can be left behind when the
            // service was destroyed between sessions (the repository is a Kotlin
            // object that outlives the service).
            when (BluetoothManagerRepository.bluetoothConnectionState.value) {
                BluetoothConnectionState.Idle,
                BluetoothConnectionState.Disconnected,
                BluetoothConnectionState.Connected,
                BluetoothConnectionState.Ready ->
                    BluetoothManagerRepository.updateBluetoothConnectionState(
                        BluetoothConnectionState.Starting
                    )
                else -> { }
            }

            // Drive the connection state machine from the service scope.
            // The service has android:foregroundServiceType="connectedDevice"
            // which makes it Doze-exempt, so this collector always runs even
            // when the device is idle and the UI's LaunchedEffect coroutines
            // are throttled by Doze.
            serviceScope.launch {
                BluetoothManagerRepository.bluetoothConnectionState.collect { state ->
                    if (state == BluetoothConnectionState.Ready) {
                        BluetoothManagerRepository.updateLocatorArmedMessageState(
                            LocatorMessageState.Idle
                        )
                    }
                    if (state == BluetoothConnectionState.Disconnected) {
                        accumulator = ByteArray(0)
                    }
                    btManager.handleConnectionState(state)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        unregisterReceiver()
        btManager.cleanup()
        serviceScope.cancel()
    }

    // -------------------------------------------------------------------------
    // Inbound data
    // -------------------------------------------------------------------------

    private fun processInboundBytes(bytes: ByteArray) {
        accumulator += bytes
        val (packets, remaining) = extractPackets(accumulator)
        accumulator = remaining
        for (packet in packets) {
            _packets.tryEmit(packet)
        }
        if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorMessageState.SendRequested)
            changeLocatorArmedState(!BluetoothManagerRepository.armedState.value)
        if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorMessageState.NotAcknowledged)
            changeLocatorArmedState(BluetoothManagerRepository.armedState.value)
    }

    // -------------------------------------------------------------------------
    // Packet framing
    // -------------------------------------------------------------------------

    private fun extractPackets(accumulator: ByteArray): Pair<List<ByteArray>, ByteArray> {
        var buffer = accumulator
        val packets = mutableListOf<ByteArray>()
        while (true) {
            if (buffer.size < Protocol.HEADER_SIZE) break
            if (buffer[0] != Protocol.SYSTEM_ID) { buffer = buffer.drop(1).toByteArray(); continue }
            val expected = computeExpectedPacketLength(buffer)
            if (expected < Protocol.HEADER_SIZE || expected > Protocol.MAX_PACKET_SIZE) {
                buffer = buffer.drop(1).toByteArray(); continue
            }
            if (buffer.size < expected) break
            val packet = buffer.copyOfRange(0, expected)
            if (!verifyMessageCrc(packet)) { buffer = buffer.drop(1).toByteArray(); continue }
            packets += packet
            buffer = buffer.drop(expected).toByteArray()
        }
        return packets to buffer
    }

    private fun computeExpectedPacketLength(bytes: ByteArray): Int {
        if (bytes.size < Protocol.HEADER_SIZE) return Int.MAX_VALUE
        val msgType = MsgType.fromUByte(bytes[1].toUByte())

        // Side-effect: track armed state from message type (unchanged from original)
        when (msgType) {
            MsgType.PreLaunchData -> {
                if (BluetoothManagerRepository.armedState.value) {
                    BluetoothManagerRepository.updateArmedState(false)
                    BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorMessageState.AckUpdated)
                }
            }
            MsgType.TelemetryData -> {
                if (!BluetoothManagerRepository.armedState.value) {
                    BluetoothManagerRepository.updateArmedState(true)
                    BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorMessageState.AckUpdated)
                }
            }
            else -> {}
        }

        // FlightData and FlightDataParity are variable-length up to MAX_PACKET_SIZE.
        // We accept anything up to the protocol maximum and let the CRC gate validity.
        val payloadSize = when (msgType) {
            MsgType.PreLaunchData    -> Protocol.PRELAUNCH_MESSAGE_PAYLOAD_SIZE
            MsgType.TelemetryData    -> Protocol.TELEMETRY_MESSAGE_PAYLOAD_SIZE
            MsgType.DeploymentTest   -> Protocol.DEPLOYMENT_TEST_MESSAGE_PAYLOAD_SIZE
            MsgType.FlightMetadata   -> FLIGHT_METADATA_PAYLOAD_SIZE
            MsgType.ReceiverInfo     -> Protocol.RECEIVER_INFO_PAYLOAD_SIZE
            MsgType.VersionInfo      -> Protocol.VERSION_INFO_PAYLOAD_SIZE
            MsgType.FlightData -> {
                // Variable-length: compute the EXACT on-wire length from the packet
                // header so the framer delimits each packet precisely. Consuming the
                // whole buffer breaks when packets are bursted/concatenated or arrive
                // fragmented across BLE notifications, because the CRC is computed over
                // the full frame length and would never match.
                // null = header not fully buffered yet → wait for more bytes.
                return flightDataPacketLength(bytes) ?: Protocol.MAX_PACKET_SIZE
            }
            MsgType.FlightDataParity -> {
                // Parity frames always carry the full payload buffer → fixed size.
                return FLIGHT_DATA_PARITY_SIZE
            }
            else -> return Int.MAX_VALUE  // unknown type — skip
        }
        return Protocol.HEADER_SIZE + payloadSize
    }

    // -------------------------------------------------------------------------
    // Outbound messages
    // -------------------------------------------------------------------------

    private fun changeLocatorArmedState(armedState: Boolean) {
        if (sendMessage(if (armedState) MsgType.ArmRequest else MsgType.DisarmRequest, null))
            BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorMessageState.Sent)
        else
            BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorMessageState.SendFailure)
    }

    fun changeLocatorConfig(locatorConfig: LocatorConfig): Boolean {
        val configMessage = concatBytes(
            byteArrayOf(
                (locatorConfig.deploymentChannel1Mode ?: DeployMode.DroguePrimary).deployMode.toByte(),
                (locatorConfig.deploymentChannel2Mode ?: DeployMode.DrogueBackup).deployMode.toByte(),
                (locatorConfig.deploymentChannel3Mode ?: DeployMode.MainPrimary).deployMode.toByte(),
                (locatorConfig.deploymentChannel4Mode ?: DeployMode.MainBackup).deployMode.toByte(),
                locatorConfig.launchDetectAltitude.toByte(),
                (locatorConfig.launchDetectAltitude / 256).toByte(),
                locatorConfig.droguePrimaryDeployDelay.toByte(),
                locatorConfig.drogueBackupDeployDelay.toByte(),
                locatorConfig.mainPrimaryDeployAltitude.toByte(),
                (locatorConfig.mainPrimaryDeployAltitude / 256).toByte(),
                locatorConfig.mainBackupDeployAltitude.toByte(),
                (locatorConfig.mainBackupDeployAltitude / 256).toByte(),
                locatorConfig.deploySignalDuration.toByte(),
                locatorConfig.loraChannel.toByte(),
            ), fillFixed(Protocol.DEVICE_NAME_LENGTH, locatorConfig.deviceName))
        return sendMessage(MsgType.LocatorCfgChgRequest, configMessage)
    }

    fun changeReceiverConfig(receiverConfig: ReceiverConfig): Boolean =
        sendMessage(
            MsgType.ReceiverCfgChgRequest,
            concatBytes(
                byteArrayOf(receiverConfig.channel.toByte()),
                fillFixed(Protocol.DEVICE_NAME_LENGTH, receiverConfig.deviceName)
            )
        )

    fun requestFlightProfileMetadata(): Boolean =
        sendMessage(MsgType.FlightMetadataRequest, null)

    /**
     * Signal to the locator that the user has exited the flight profile screen.
     * The locator will return to Disarmed state and resume sending PreLaunchData.
     */
    fun exitFlightProfileMode(): Boolean =
        sendMessage(MsgType.DisarmRequest, null)

    /**
     * Request flight data for one archive record.
     * The C++ FlightDataRequest struct carries a single `record` byte — the
     * old `packet` byte is gone now that the locator manages chunking internally.
     */
    fun requestFlightProfileData(archivePosition: Int): Boolean {
        FlightDataRepository.beginTransfer()
        return sendMessage(MsgType.FlightDataRequest, byteArrayOf(archivePosition.toByte()))
    }

    /**
     * Send a FlightDataAck packet built by [FlightDataRepository.buildAck].
     * Recomputes the CRC before transmitting.
     */
    fun sendFlightDataAck(ackFrame: ByteArray): Boolean {
        if (ackFrame.size != FLIGHT_DATA_ACK_SIZE) {
            Log.w(TAG, "sendFlightDataAck: unexpected size ${ackFrame.size}")
            return false
        }
        // Recompute CRC over the frame with the crc field zeroed (already 0
        // from buildAck), then write it back into bytes [4..5].
        val crc = computeMessageCrc(ackFrame)
        val frame = ackFrame.copyOf()
        frame[4] = (crc and 0xFF).toByte()
        frame[5] = ((crc shr 8) and 0xFF).toByte()
        return btManager.sendData(frame)
    }

    fun deploymentTest(deploymentChannel: Int): Boolean =
        sendMessage(MsgType.DeploymentTestRequest, byteArrayOf(deploymentChannel.toByte()))

    /** Ask the receiver for its current LoRa channel and device name.
     *  Used when no locator PreLaunchData has been received recently. */
    fun requestReceiverInfo(): Boolean =
        sendMessage(MsgType.ReceiverInfoRequest, null)

    /** Ask the locator (via the receiver) for firmware version strings.
     *  The receiver forwards the request to the locator, which responds with its
     *  version; the receiver appends its own version before relaying to the app. */
    fun requestVersionInfo(): Boolean =
        sendMessage(MsgType.VersionRequest, null)

    @SuppressLint("MissingPermission")
    private fun sendMessage(msgType: MsgType, payload: ByteArray?): Boolean =
        btManager.sendData(buildMessage(msgType, payload))

    private fun buildMessage(msgType: MsgType, payload: ByteArray?): ByteArray {
        val payloadBytes = payload ?: ByteArray(0)
        val header0 = PacketHeader(Protocol.SYSTEM_ID.toUByte(), msgType, 0u, 0u)
        val crc = computeMessageCrc(concatBytes(header0.toBytes(), payloadBytes)).toUShort()
        return concatBytes(header0.copy(crc = crc).toBytes(), payloadBytes)
    }

    // -------------------------------------------------------------------------
    // BroadcastReceiver
    // -------------------------------------------------------------------------

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(bondStateReceiver, filter, RECEIVER_EXPORTED)
    }

    private fun unregisterReceiver() {
        try { unregisterReceiver(bondStateReceiver) }
        catch (_: IllegalArgumentException) { Log.w(TAG, "Receiver already unregistered") }
    }

    inner class BondStateReceiver : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_OFF ->
                            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.NotEnabled)
                        BluetoothAdapter.STATE_ON ->
                            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Enabled)
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    btManager.onAclDisconnected(
                        device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java),
                        source = "ACL broadcast"
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private fun concatBytes(a: ByteArray, b: ByteArray?): ByteArray {
        if (b == null || b.isEmpty()) return a.copyOf()
        val out = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }

    fun fillFixed(bufferSize: Int, text: String): ByteArray {
        val out = ByteArray(bufferSize)
        val bytes = text.encodeToByteArray()
        System.arraycopy(bytes, 0, out, 0, minOf(bytes.size, bufferSize))
        return out
    }

    fun logByteArrayAsChars(byteArray: ByteArray, numChars: Int): String =
        byteArray.take(numChars).joinToString("") { byte ->
            if (byte in 32..126) byte.toInt().toChar().toString() else "?"
        }

    private fun clearMessage() {
        inboundMessageBuffer.fill(0); numBytes = 0
        currentMessageSize = 0; messageReceived = false; messageType = MessageType.None
    }

    private fun update(crcIn: Int, data: Int): Int {
        var crc = crcIn xor (data and 0xFF)
        repeat(8) { crc = if ((crc and 1) != 0) (crc ushr 1) xor POLY else crc ushr 1 }
        return crc
    }

    fun computeMessageCrc(frame: ByteArray): Int {
        var crc = INIT
        for (i in 0 until 4) crc = update(crc, frame[i].toInt())
        for (i in 6 until frame.size) crc = update(crc, frame[i].toInt())
        return crc and 0xFFFF
    }

    fun verifyMessageCrc(frame: ByteArray): Boolean {
        if (frame.size < 6) return false
        val received = (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)
        return received == computeMessageCrc(frame)
    }

    // -------------------------------------------------------------------------
    // Binder / notification
    // -------------------------------------------------------------------------

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }

    override fun onBind(intent: Intent): IBinder = binder

    // Return false so Android does not keep the service in a "rebindable" state.
    // Returning true would conflict with the stopSelf() call: the system sees
    // "wants rebinding" and defers the stop, leaving the GATT handle open on
    // every closure after the first.  With false, stopSelf() + no remaining
    // bindings → onDestroy() fires consistently and cleanup() releases the GATT.
    override fun onUnbind(intent: Intent): Boolean { stopSelf(); return false }

    private fun createNotificationChannel() {
        val mChannel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH
        ).also { it.description = getString(R.string.channel_description) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(mChannel)
    }

    private fun getNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rocket Location Service")
            .setContentText("Capturing rocket location data")
            .setSmallIcon(R.drawable.rocket)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }.build()
}