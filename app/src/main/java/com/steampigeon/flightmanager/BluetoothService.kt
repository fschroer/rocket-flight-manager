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
import com.steampigeon.flightmanager.data.LocatorConfigWire
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

        // Wire the health watchdog's liveness probe. The receiver answers a
        // ReceiverInfoRequest even when no locator is transmitting, so a probe
        // response keeps an idle-but-healthy connection alive instead of letting
        // the watchdog mistake locator silence for a dead (phantom) link.
        btManager.onHealthProbe = { requestReceiverInfo() }

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
        SpLog.d(TAG, "onDestroy")
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
        // Pad-alert snooze (#37). Fire-and-forget: the locator reports the
        // resulting state back in PreLaunchData.pad_alert, so a lost request
        // simply means the alert keeps sounding — the safe direction.
        val snoozeMinutes = BluetoothManagerRepository.padAlertSnoozeRequest.value
        if (snoozeMinutes > 0) {
            BluetoothManagerRepository.clearPadAlertSnoozeRequest()
            snoozePadAlert(snoozeMinutes)
        }
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

        // Armed state used to be tracked here, from the message type alone. It is now
        // derived in RocketViewModel, inside the connected-locator gate.
        //
        // This function frames bytes; it runs before parsing, before authentication,
        // and before anything knows WHOSE packet it is. Deriving state here meant any
        // locator on the channel could set it: with two powered, the armed one's
        // TelemetryData and the disarmed one's PreLaunchData flipped the flag against
        // each other at the broadcast rate, while the name and telemetry on screen —
        // which ARE gated — stayed correctly on the connected locator.
        //
        // Unsolicited broadcasts are the distinguishing case. Every other message the
        // app reacts to is a response to an addressed request (ADR-0020), so it can
        // only come from the connected locator. PreLaunchData and TelemetryData arrive
        // from everyone, so anything derived from them has to be gated on the sender.

        // FlightData and FlightDataParity are variable-length up to MAX_PACKET_SIZE.
        // We accept anything up to the protocol maximum and let the CRC gate validity.
        val payloadSize = when (msgType) {
            MsgType.PreLaunchData    -> Protocol.PRELAUNCH_MESSAGE_PAYLOAD_SIZE
            MsgType.TelemetryData    -> Protocol.TELEMETRY_MESSAGE_PAYLOAD_SIZE
            MsgType.DeploymentTest   -> Protocol.DEPLOYMENT_TEST_MESSAGE_PAYLOAD_SIZE
            MsgType.FlightMetadata   -> FLIGHT_METADATA_PAYLOAD_SIZE
            MsgType.FlightEvents     -> Protocol.FLIGHT_EVENTS_PAYLOAD_SIZE
            MsgType.ReceiverInfo     -> Protocol.RECEIVER_INFO_PAYLOAD_SIZE
            MsgType.VersionInfo      -> Protocol.VERSION_INFO_PAYLOAD_SIZE
            MsgType.ChannelSurvey    -> Protocol.CHANNEL_SURVEY_PAYLOAD_SIZE
            MsgType.LocatorSearchResult -> Protocol.LOCATOR_SEARCH_RESULT_PAYLOAD_SIZE
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

    /**
     * Suppress the prepped-and-disarmed alert for [minutes] (0 cancels).
     *
     * A rocket assembled vertically with charges wired is physically identical
     * to one standing on the pad, so no sensor separates them — this is the
     * operator saying "still prepping" (ADR-0021 Decision 5, #37).
     *
     * Bounded on purpose, and the locator clamps it again on receipt: a snooze
     * that could be made indefinite is an off switch, and would hand back the
     * forgotten arm the alert exists to catch.
     */
    fun snoozePadAlert(minutes: Int): Boolean =
        sendMessage(MsgType.PadAlertSnoozeRequest, byteArrayOf(minutes.toByte()))

    private fun changeLocatorArmedState(armedState: Boolean) {
        if (sendMessage(if (armedState) MsgType.ArmRequest else MsgType.DisarmRequest, null))
            BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorMessageState.Sent)
        else
            BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorMessageState.SendFailure)
    }

    fun changeLocatorConfig(locatorConfig: LocatorConfig): Boolean =
        sendMessage(MsgType.LocatorCfgChgRequest, LocatorConfigWire.payload(locatorConfig))

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
        // This path builds its own frame instead of going through sendMessage, so
        // it needs the gate and the addressing applied by hand. It previously had
        // NEITHER — an ack went out unconditionally and unaddressed, so a transfer
        // could be acknowledged with no locator connected, and every locator on the
        // channel saw the ack.
        val target = connectedLocatorId ?: return false
        // Splice target_locator_id in after the header (ADR-0020): 42 -> 46 bytes.
        val frame = concatBytes(
            concatBytes(ackFrame.copyOfRange(0, Protocol.HEADER_SIZE), u32le(target)),
            ackFrame.copyOfRange(Protocol.HEADER_SIZE, ackFrame.size),
        )
        // Recompute CRC over the final frame with the crc field zeroed (already 0
        // from buildAck), then write it back into bytes [4..5].
        val crc = computeMessageCrc(frame)
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

    /** Ask the receiver to sweep channels 0-63 and report per-channel occupancy.
     *  The receiver refuses while the locator is armed — the sweep is deaf to it
     *  for about a second (ADR-0019). */
    fun requestChannelSurvey(): Boolean =
        sendMessage(MsgType.ChannelSurveyRequest, null)

    /** Ask the receiver to listen for locators on [channels], or on the whole band
     *  when [channels] is empty.
     *
     *  [targetLocatorId] stops the run as soon as that locator is decoded. Pass 0
     *  to report every hit instead — the only useful behaviour when the app has
     *  never seen the locator before, since a borrowed one has no id to name.
     *
     *  The receiver answers with a stream of [MsgType.LocatorSearchResult]: one per
     *  channel as it finishes, then a terminator. It refuses while the locator is
     *  armed or flying — a whole-band run is up to ~90 s of deafness. */
    fun requestLocatorSearch(channels: List<Int>, targetLocatorId: Long = 0L): Boolean {
        val payload = ByteArray(Protocol.LOCATOR_SEARCH_REQUEST_PAYLOAD_SIZE)
        val listed = channels.take(Protocol.LOCATOR_SEARCH_MAX_CHANNELS)
        payload[0] = 0                       // flags
        payload[1] = listed.size.toByte()    // 0 = whole band
        payload[2] = (targetLocatorId and 0xFF).toByte()
        payload[3] = ((targetLocatorId shr 8) and 0xFF).toByte()
        payload[4] = ((targetLocatorId shr 16) and 0xFF).toByte()
        payload[5] = ((targetLocatorId shr 24) and 0xFF).toByte()
        listed.forEachIndexed { i, ch -> payload[6 + i] = ch.toByte() }
        return sendMessage(MsgType.LocatorSearchRequest, payload)
    }

    /** Stop a search in progress. Answered with a Cancelled terminator even when
     *  nothing was running, so the app never waits on silence. */
    fun cancelLocatorSearch(): Boolean {
        val payload = ByteArray(Protocol.LOCATOR_SEARCH_REQUEST_PAYLOAD_SIZE)
        payload[0] = Protocol.LOCATOR_SEARCH_FLAG_CANCEL.toByte()
        return sendMessage(MsgType.LocatorSearchRequest, payload)
    }

    /** Ask the locator (via the receiver) for firmware version strings.
     *  The receiver forwards the request to the locator, which responds with its
     *  version; the receiver appends its own version before relaying to the app. */
    fun requestVersionInfo(): Boolean =
        sendMessage(MsgType.VersionRequest, null)

    // Send gate: set by the ViewModel from its connection state. Until the app is
    // connected to a locator, only receiver-directed messages are sent — so a locator
    // the app is not connected to cannot be armed, configured, deployment-tested, or
    // have its flight data pulled from the standard app.
    //
    // Gated on *connected*, not merely authorized (ADR-0006, "One connection at a
    // time"). The app can be authorized for several locators at once, and these
    // messages reach whichever locator the receiver is relaying to. The weaker
    // condition would let an Arm land on the wrong rocket.
    //
    // Holds the connected locator's id rather than a flag, because since ADR-0020
    // that id is ALSO what addresses the command. The thing that authorizes a send
    // and the thing that addresses it are now the same value, so they cannot drift
    // apart — a command can never go out authorized but unaddressed.
    @Volatile var connectedLocatorId: Long? = null

    /** Receiver-directed messages go point-to-point over BLE and are never relayed,
     *  so they need no locator and carry no target (ADR-0020 Decision 3). They stay
     *  open with no locator connected so the user can still find and switch
     *  channels — hunting for a clean channel is exactly what you do when nothing
     *  is coming through. */
    private fun isReceiverDirected(msgType: MsgType) =
        msgType == MsgType.ReceiverCfgChgRequest ||
                msgType == MsgType.ReceiverInfoRequest ||
                msgType == MsgType.ChannelSurveyRequest ||
                // Same reasoning, and more load-bearing: a search is started
                // precisely when no locator is connected, so gating it on a
                // connection would disable it in the only state it is for.
                msgType == MsgType.LocatorSearchRequest

    @SuppressLint("MissingPermission")
    private fun sendMessage(msgType: MsgType, payload: ByteArray?): Boolean {
        if (isReceiverDirected(msgType)) {
            return btManager.sendData(buildMessage(msgType, payload))
        }
        // Locator-directed: refuse unless we know WHICH locator, because that id is
        // what stops every other locator on the channel from obeying (ADR-0020).
        val target = connectedLocatorId ?: return false
        return btManager.sendData(buildMessage(msgType, payload, target))
    }

    /** [target] is the locator's id for locator-directed commands (prepended to the
     *  payload, immediately after the header), or null for receiver-directed ones. */
    private fun buildMessage(msgType: MsgType, payload: ByteArray?, target: Long? = null): ByteArray {
        val body = payload ?: ByteArray(0)
        val payloadBytes = if (target == null) body else concatBytes(u32le(target), body)
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

    /** uint32_t little-endian, matching the firmware's packed structs. */
    private fun u32le(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    // Variadic since #36: the locator config payload is now three parts (fixed
    // fields, device name, nose axis). Two-argument calls are unaffected.
    private fun concatBytes(vararg parts: ByteArray?): ByteArray {
        val total = parts.sumOf { it?.size ?: 0 }
        val out = ByteArray(total)
        var offset = 0
        for (part in parts) {
            if (part == null || part.isEmpty()) continue
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
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