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
import java.io.IOException

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

    @Suppress("DEPRECATION")
    private val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
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
            processInboundBytes(bytes)
        }

        createNotificationChannel()
        startForeground(1, getNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!serviceStarted) {
            serviceStarted = true
            registerReceiver()

            // Kick off the state machine — Idle → Starting is the entry point.
            if (BluetoothManagerRepository.bluetoothConnectionState.value
                == BluetoothConnectionState.Idle
            ) {
                BluetoothManagerRepository.updateBluetoothConnectionState(
                    BluetoothConnectionState.Starting
                )
            }

            // Observe Ready state to handle armed state messaging now that
            // the GATT channel is fully open (notifications enabled).
            serviceScope.launch {
                BluetoothManagerRepository.bluetoothConnectionState.collect { state ->
                    if (state == BluetoothConnectionState.Ready) {
                        BluetoothManagerRepository.updateLocatorArmedMessageState(
                            LocatorMessageState.Idle
                        )
                    }
                    if (state == BluetoothConnectionState.Disconnected) {
                        accumulator = ByteArray(0) // discard any partial packet bytes
                    }
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
    // Inbound data — replaces startReading() / RFCOMM stream loop
    //
    // Called from btManager.onDataReceived on a GATT callback thread.
    // Appends bytes to the accumulator and extracts complete packets,
    // identical to the previous stream-based approach.
    // -------------------------------------------------------------------------

    private fun processInboundBytes(bytes: ByteArray) {
        accumulator += bytes
        val (packets, remaining) = extractPackets(accumulator)
        accumulator = remaining
        for (packet in packets) {
            _packets.tryEmit(packet)
        }
        // Armed state messaging — same logic as before
        if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorMessageState.SendRequested)
            changeLocatorArmedState(!BluetoothManagerRepository.armedState.value)
        if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorMessageState.NotAcknowledged)
            changeLocatorArmedState(BluetoothManagerRepository.armedState.value)
    }

    // -------------------------------------------------------------------------
    // Packet parsing (unchanged from RFCOMM version)
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
        val payloadSize = when (msgType) {
            MsgType.PreLaunchData  -> Protocol.PRELAUNCH_MESSAGE_PAYLOAD_SIZE
            MsgType.TelemetryData  -> Protocol.TELEMETRY_MESSAGE_PAYLOAD_SIZE
            MsgType.DeploymentTest -> Protocol.DEPLOYMENT_TEST_MESSAGE_PAYLOAD_SIZE
            else                   -> 0
        }
        return Protocol.HEADER_SIZE + payloadSize
    }

    // -------------------------------------------------------------------------
    // Outbound messages
    //
    // sendMessage() now routes through btManager.sendData() instead of writing
    // to a socket OutputStream. Everything else is identical.
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
        sendMessage(MsgType.ReceiverCfgChgRequest, byteArrayOf(receiverConfig.channel.toByte()))
    fun requestFlightProfileMetadata(): Boolean =
        sendMessage(MsgType.FlightMetadataRequest, null)
    fun requestFlightProfileData(archivePosition: Int, packet: Byte): Boolean =
        sendMessage(MsgType.FlightDataRequest, byteArrayOf(archivePosition.toByte(), packet))
    fun deploymentTest(deploymentChannel: Int): Boolean =
        sendMessage(MsgType.DeploymentTestRequest, byteArrayOf(deploymentChannel.toByte()))

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
    // BroadcastReceiver — adapter state changes only.
    // Bond state is no longer relevant on the GATT path (GATT handles its own
    // pairing/encryption negotiation internally during connectGatt).
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
        catch (e: IllegalArgumentException) { Log.w(TAG, "Receiver already unregistered") }
    }

    inner class BondStateReceiver : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_OFF -> {
                            Log.d(TAG, "Adapter turned off")
                            BluetoothManagerRepository.updateBluetoothConnectionState(
                                BluetoothConnectionState.NotEnabled
                            )
                        }
                        BluetoothAdapter.STATE_ON -> {
                            Log.d(TAG, "Adapter turned on → Enabled")
                            BluetoothManagerRepository.updateBluetoothConnectionState(
                                BluetoothConnectionState.Enabled
                            )
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    // Delegate entirely to BluetoothManager — it closes GATT,
                    // emits Disconnected state, and schedules reconnection.
                    // This fires whether or not the GATT callback already handled
                    // the disconnect; onAclDisconnected() is idempotent.
                    Log.d(TAG, "ACL disconnected broadcast received")
                    btManager.onAclDisconnected(
                        device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE),
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
    override fun onUnbind(intent: Intent): Boolean { stopSelf(); return true }

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