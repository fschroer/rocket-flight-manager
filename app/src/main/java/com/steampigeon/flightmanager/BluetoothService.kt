package com.steampigeon.flightmanager

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.MsgType
import com.steampigeon.flightmanager.data.PacketHeader
import com.steampigeon.flightmanager.data.ReceiverConfig
import com.steampigeon.flightmanager.data.Protocol
import com.steampigeon.flightmanager.data.toBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "BluetoothService"
private const val CHANNEL_ID = "BluetoothService"
private val EOT = "\u0003\u0019\u0004".toByteArray()
private const val POLY = 0xA001
private const val INIT = 0xFFFF

enum class MessageType (val messageType: UByte) {
    None(0u),
    Prelaunch(1u),
    Telemetry(2u),
    ReceiverConfig(3u),
    FlightProfileMetadata(4u),
    FlightProfileData(5u),
    DeploymentTest(6u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.messageType == value } ?: throw IllegalArgumentException("Invalid type: $value")
    }
}

class BluetoothService() : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _packets = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val packets: SharedFlow<ByteArray> = _packets.asSharedFlow()

    private var serviceStarted = false
    private val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private val bondStateReceiver = BondStateReceiver()
    var receiverRegistered = false
    private val binder = LocalBinder() // Service binder given to clients
    private var locatorSocket: BluetoothSocket? = null
    private lateinit var locatorInputStream: InputStream
    private lateinit var locatorOutputStream: OutputStream
    private var inboundMessageBuffer = ByteArray(Protocol.MESSAGE_BUFFER_SIZE) // buffer store for the stream
    private var numBytes = 0 // bytes returned from read()
    private var messageReceived = false
    private var currentMessageSize = 0
    private var messageType = MessageType.None

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, getNotification())
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!serviceStarted) {
            serviceStarted = true
            //bluetoothManagerRepository = intent?.getSerializableExtra("bluetoothManagerRepository") as BluetoothManagerRepository
            //val device = intent?.getParcelableExtra<BluetoothDevice>("device")

            // Register broadcast receiver for bluetooth status events
            if (!receiverRegistered) {
                registerReceiver()
                val intent = Intent("CONFIRM_RECEIVER_REGISTERED")
                sendBroadcast(intent)
            }
        }

        serviceScope.launch {
            while (isActive) {
                try {
                    maintainLocatorDevicePairing()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in pairing state machine", e)
                }

                delay(200) // small delay to avoid busy loop
            }
        }

        return START_STICKY
    }

    private fun startReading(input: InputStream) {
        serviceScope.launch {
            val tempBuffer = ByteArray(1024)
            var accumulator = ByteArray(0)

            // Keep listening to the InputStream until an exception occurs.
            while (true) {
                try {
                    // Read raw bytes from Bluetooth
                    val count = input.read(tempBuffer)
                    if (count < 0) {
                        // Bluetooth disconnected or EOF
                        BluetoothManagerRepository.updateBluetoothConnectionState(
                            BluetoothConnectionState.Disconnected
                        )
                        break
                    }
                    if (count == 0)
                        break

                    // Append new bytes to accumulator
                    accumulator += tempBuffer.copyOfRange(0, count)

                    // Extract packets + update accumulator
                    val (packets, remaining) = extractPackets(accumulator)
                    accumulator = remaining

                    // Emit packets
                    for (packet in packets) {
                        val success = _packets.tryEmit(packet)
//                        Log.d("BT", "Sent packet size=${packet.size} bytes, $success")
                    }

                } catch (e: IOException) {
                    Log.e("BT", "Input stream disconnected", e)
                    BluetoothManagerRepository.updateBluetoothConnectionState(
                        BluetoothConnectionState.Disconnected
                    )
                    break
                }
                if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorMessageState.SendRequested)
                    changeLocatorArmedState(!BluetoothManagerRepository.armedState.value)
                if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorMessageState.NotAcknowledged)
                    changeLocatorArmedState(BluetoothManagerRepository.armedState.value)
            }
//                while (true) {
//                    if (BluetoothManagerRepository.bluetoothConnectionState.value == BluetoothConnectionState.Connected) {
//                        try { // Read from the InputStream.
//                            Log.d("BT", "offset=$currentMessageSize length=${Protocol.MESSAGE_BUFFER_SIZE - currentMessageSize} bufferSize=${inboundMessageBuffer.size}")
//                            numBytes = locatorInputStream.read(
//                                inboundMessageBuffer,
//                                currentMessageSize,
//                                Protocol.MESSAGE_BUFFER_SIZE - currentMessageSize
//                            )
//                            if (numBytes <= 0) {
//                                // -1 means disconnected, 0 means no data
//                                Log.d(TAG, "Input stream returned $numBytes, stopping read loop")
//                                BluetoothManagerRepository.updateBluetoothConnectionState(
//                                    BluetoothConnectionState.Disconnected
//                                )
//                                break
//                            }
//                            currentMessageSize += numBytes
//                        } catch (e: IOException) {
//                            Log.d(TAG, "Input stream was disconnected", e)
//                            BluetoothManagerRepository.updateBluetoothConnectionState(
//                                BluetoothConnectionState.Disconnected
//                            )
//                        }
//                        // Emit received message for viewmodel collection, update armed state, reset for next message
//                        if (currentMessageSize >= 0) {
//                            //Log.d(TAG, logByteArrayAsChars(inboundMessageBuffer, currentMessageSize))
//
//                            // 1. Ensure first byte is a valid system ID
//                            if (inboundMessageBuffer[0] != Protocol.SYSTEM_ID) {
//                                inboundMessageBuffer = inboundMessageBuffer.drop(1).toByteArray()
//                                continue
//                            }
//                            // 2. Ensure we have enough for a header
//                            if (inboundMessageBuffer.size < Protocol.HEADER_SIZE) break
//                            // 3. Compute expected packet length
//                            val expected = computeExpectedPacketLength(inboundMessageBuffer)
//                            // Sanity check: if expected is invalid, resync
//                            if (expected < Protocol.HEADER_SIZE || expected > Protocol.MAX_MESSAGE_SIZE) {
//                                inboundMessageBuffer = inboundMessageBuffer.drop(1).toByteArray()
//                                continue
//                            }
//                            // 4. If we don't have enough bytes yet, wait
//                            if (inboundMessageBuffer.size < expected) break
//                            // 5. Extract full packet
//                            val data = inboundMessageBuffer.copyOfRange(0, expected)
//                            // 6. Validate CRC
//                            if (!Crc16Ibm.verifyMessageCrc(data)) {
//                                // Bad packet → drop first byte and resync
//                                inboundMessageBuffer = inboundMessageBuffer.drop(1).toByteArray()
//                                continue
//                            }
//                            val hexString = inboundMessageBuffer.joinToString(" ") { "%02X".format(it) }
//                            Log.d(TAG, hexString.toString())
//                            // Update message type
//
//                            _packets.tryEmit(data)
//                            inboundMessageBuffer = inboundMessageBuffer.drop(data.size).toByteArray()
////                            val messageHeader = inboundMessageBuffer.copyOfRange(0, 3)
////                            when {
////                                messageHeader.contentEquals(receiverConfigMessageHeader) && currentMessageSize >= Protocol.RECEIVER_CONFIG_PAYLOAD_MESSAGE_SIZE -> {
////                                    //Log.d(TAG, "Receiver config message detected, $currentMessageSize")
////                                    _data.emit(inboundMessageBuffer.copyOfRange(0, Protocol.RECEIVER_CONFIG_PAYLOAD_MESSAGE_SIZE))
////                                    clearMessage()
////                                }
////
////                                messageHeader.contentEquals(flightProfileMetadataMessageHeader) && currentMessageSize >= Protocol.FLIGHT_PROFILE_METADATA_PAYLOAD_MESSAGE_SIZE -> {
////                                    //Log.d(TAG, "Flight profile metadata message detected, $currentMessageSize")
////                                    _data.emit(inboundMessageBuffer.copyOfRange(0, currentMessageSize))
////                                    clearMessage()
////                                }
////
////                                messageHeader.contentEquals(flightProfileDataMessageHeader)// && currentMessageSize >= Protocol.FLIGHT_PROFILE_DATA_PAYLOAD_MESSAGE_SIZE
////                                    -> {
////                                    //Log.d(TAG, "Flight profile data message detected, $currentMessageSize")
////                                    _data.emit(inboundMessageBuffer.copyOfRange(0, currentMessageSize))
////                                    clearMessage()
////                                }
////
////                                messageHeader.contentEquals(deploymentTestMessageHeader) && currentMessageSize >= Protocol.DEPLOYMENT_TEST_MESSAGE_PAYLOAD_SIZE -> {
////                                    //Log.d(TAG, "Deployment test message detected, $currentMessageSize")
////                                    _data.emit(inboundMessageBuffer.copyOfRange(0, Protocol.DEPLOYMENT_TEST_MESSAGE_PAYLOAD_SIZE))
////                                    clearMessage()
////                                }
////
////                                currentMessageSize > Protocol.TELEMETRY_MESSAGE_PAYLOAD_SIZE  -> {
////                                    Log.d(TAG, "Overflow detected, $currentMessageSize")
//////                                    clearMessage()
////                                }
////                            }
//                        }
//                        if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorMessageState.SendRequested)
//                            changeLocatorArmedState(!BluetoothManagerRepository.armedState.value)
//                        if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorMessageState.NotAcknowledged)
//                            changeLocatorArmedState(BluetoothManagerRepository.armedState.value)
//                    } else {
//                        clearMessage()
//                        maintainLocatorDevicePairing()
//                    }
//                    delay(10)
//                }
        }
    }

    private fun extractPackets(accumulator: ByteArray): Pair<List<ByteArray>, ByteArray> {
        var buffer = accumulator
        val packets = mutableListOf<ByteArray>()

        while (true) {

            // Need at least a header
            if (buffer.size < Protocol.HEADER_SIZE) break

            // 1. Check system ID
            if (buffer[0] != Protocol.SYSTEM_ID) {
                buffer = buffer.drop(1).toByteArray()
                continue
            }

            // 2. Compute expected packet length
            val expected = computeExpectedPacketLength(buffer)

            // Sanity checks
            if (expected < Protocol.HEADER_SIZE ||
                expected > Protocol.MAX_PACKET_SIZE) {
                buffer = buffer.drop(1).toByteArray()
                continue
            }

            // 3. Not enough bytes yet
            if (buffer.size < expected) break

            // 4. Extract packet
            val packet = buffer.copyOfRange(0, expected)

            // 5. Validate CRC
            if (!verifyMessageCrc(packet)) {
                buffer = buffer.drop(1).toByteArray()
                continue
            }

            // 6. Packet is good
            packets += packet

            // 7. Remove packet from buffer
            buffer = buffer.drop(expected).toByteArray()
        }

        return packets to buffer
    }

    private fun computeExpectedPacketLength(bytes: ByteArray): Int {
        // Ensure we have at least a full header
        if (bytes.size < Protocol.HEADER_SIZE) return Int.MAX_VALUE

        // Parse header fields
        val systemId = bytes[0].toUByte()
        val msgType  = MsgType.fromUByte(bytes[1].toUByte())
        val msgCount = ((bytes[2].toUByte().toInt() shl 8) or
                (bytes[3].toUByte().toInt())).toUShort()

        val crc = ((bytes[4].toUByte().toInt() shl 8) or
                (bytes[5].toUByte().toInt())).toUShort()

        when (msgType) {
            MsgType.PreLaunchData -> {
                if (BluetoothManagerRepository.armedState.value) { //Disarm request acknowledged by locator, by transitioning to prelaunch messages
                    BluetoothManagerRepository.updateArmedState(false)
                    BluetoothManagerRepository.updateLocatorArmedMessageState(
                        LocatorMessageState.AckUpdated
                    )
                }
            }
            MsgType.TelemetryData -> {
                if (!BluetoothManagerRepository.armedState.value) { //Arm request acknowledged by locator, by transitioning to telemetry messages
                    BluetoothManagerRepository.updateArmedState(true)
                    BluetoothManagerRepository.updateLocatorArmedMessageState(
                        LocatorMessageState.AckUpdated
                    )
                }
            }
            else -> {}
        }
        // Now compute payload size based on message type
        val payloadSize = when (msgType) {
            MsgType.PreLaunchData -> Protocol.PRELAUNCH_MESSAGE_PAYLOAD_SIZE
            MsgType.TelemetryData -> Protocol.TELEMETRY_MESSAGE_PAYLOAD_SIZE
            MsgType.DeploymentTest -> Protocol.DEPLOYMENT_TEST_MESSAGE_PAYLOAD_SIZE
            else              -> 0
        }

        return Protocol.HEADER_SIZE + payloadSize
    }

    private fun clearMessage(){
        inboundMessageBuffer.fill(0)
        numBytes = 0
        currentMessageSize = 0
        messageReceived = false
        messageType = MessageType.None
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel.
        val name = getString(R.string.channel_name)
        val descriptionText = getString(R.string.channel_description)
        val importance = NotificationManager.IMPORTANCE_HIGH
        val mChannel = NotificationChannel(CHANNEL_ID, name, importance)
        mChannel.description = descriptionText
        // Register the channel with the system. You can't change the importance
        // or other notification behaviors after this.
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(mChannel)
    }

    private fun getNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rocket Location Service")
            .setContentText("Capturing rocket location data")
            .setSmallIcon(R.drawable.rocket)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        unregisterReceiver()
        if (BluetoothManagerRepository.bluetoothConnectionState.value == BluetoothConnectionState.Connected)
            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Paired)
        cancelLocatorBluetoothSocket(locatorSocket)
        serviceScope.cancel()
    }

    @SuppressLint("MissingPermission")
    suspend fun maintainLocatorDevicePairing() {
        when (BluetoothManagerRepository.bluetoothConnectionState.value) {
            BluetoothConnectionState.Idle -> {
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Starting)
            }
            BluetoothConnectionState.Pairing -> {
                Log.d(TAG, "Changing state from Pairing to Enabled")
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Enabled)
            }
            BluetoothConnectionState.AssociateStart -> {
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.AssociateWait)
                Log.d(TAG, "Changing state from AssociateStart to AssociateWait")
            }
            BluetoothConnectionState.Paired -> {
                if (bluetoothAdapter.isEnabled) {
                    BluetoothManagerRepository.locatorDevice.value?.let { locatorDevice ->
                        if (locatorDevice.bondState == BluetoothDevice.BOND_BONDED) {
                            connectLocator()
                        } else {
                            Log.d(TAG, "Changing state from Paired to Enabled")
                            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Enabled)
                        }
                    }
                }
                else {
                    Log.d(TAG, "Changing state to Idle")
                    BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Idle)
                }
            }
            BluetoothConnectionState.PairingFailed -> {
                if (bluetoothAdapter.isDiscovering)
                    bluetoothAdapter.cancelDiscovery()
                Log.d(TAG, "PairingFailed delay start")
                delay(500)
                Log.d(TAG, "PairingFailed delay end")
                Log.d(TAG, "Changing state from PairingFailed to Enabled")
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Enabled)
            }
            BluetoothConnectionState.Disconnected -> {
                cancelLocatorBluetoothSocket(locatorSocket)
                Log.d(TAG, "Disconnected delay start")
                delay(500)
                Log.d(TAG, "Disconnected delay end")
                Log.d(TAG, "Changing state from Disconnected to Paired")
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Paired)
            }
            else -> {}
        }
    }

    @SuppressLint("MissingPermission")
    fun connectLocator() {
        BluetoothManagerRepository.locatorDevice.value.let {
            if (it != null && it.uuids != null) {
                locatorSocket = it.createRfcommSocketToServiceRecord(it.uuids[0].uuid)
                // Cancel device discovery since locator has been selected.
                if (bluetoothAdapter.isDiscovering)
                    bluetoothAdapter.cancelDiscovery()
                // Attempt to connect to locator
                try {
                    locatorSocket!!.connect()
                    locatorInputStream = locatorSocket!!.inputStream
                    startReading(locatorInputStream)
                    locatorOutputStream = locatorSocket!!.outputStream
                    BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Connected)
                    BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorMessageState.Idle)
                } catch (e: IOException) {
                    BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Disconnected)
                }
            }
            else {
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.NoDevicesAvailable)
            }
        }
    }

    fun findHeader(messageBuffer: ByteArray, header: ByteArray): Int {
        for (i in messageBuffer.size - header.size downTo 0) {
            if (messageBuffer.sliceArray(i until i + header.size).contentEquals(header)) {
                return i
            }
        }
        return -1 // Not found
    }

    fun logByteArrayAsChars(byteArray: ByteArray, numChars: Int): String {
        var message = ""
        byteArray.take(numChars).forEach { byte ->
            if (byte in 32..126)
                message += byte.toInt().toChar()
            else
                message = "$message?"
        }
        return message
    }

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

    fun changeReceiverConfig(receiverConfig: ReceiverConfig): Boolean {
        return sendMessage(MsgType.ReceiverCfgChgRequest, byteArrayOf(receiverConfig.channel.toByte()))
    }

    fun requestFlightProfileMetadata(): Boolean {
        return sendMessage(MsgType.FlightMetadataRequest, null)
    }

    fun requestFlightProfileData(archivePosition: Int, packet: Byte): Boolean {
        return sendMessage(MsgType.FlightDataRequest, byteArrayOf(archivePosition.toByte()) + byteArrayOf(packet))
    }

    fun deploymentTest(deploymentChannel: Int): Boolean {
        return sendMessage(MsgType.DeploymentTestRequest, byteArrayOf(deploymentChannel.toByte()))
    }

    private fun sendMessage(msgType: MsgType, payload: ByteArray?) : Boolean {
        val message = buildMessage(msgType, payload)
        return try {
            locatorOutputStream.write(message)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error occurred when sending data", e)
            false
        }
    }

    private fun buildMessage(msgType: MsgType, payload: ByteArray?): ByteArray {
        val payloadBytes = payload ?: ByteArray(0)

        val header0 = PacketHeader(
            systemId = Protocol.SYSTEM_ID.toUByte(),
            msgType = msgType,
            msgCount = 0u,
            crc = 0u
        )

        val headerBytes0 = header0.toBytes()
        val crc = computeMessageCrc(concatBytes(headerBytes0, payloadBytes)).toUShort()

        val headerFinal = header0.copy(crc = crc)
        val headerBytesFinal = headerFinal.toBytes()

        return concatBytes(headerBytesFinal, payloadBytes)
    }

    private fun concatBytes(a: ByteArray, b: ByteArray?): ByteArray {
        if (b == null || b.isEmpty()) return a.copyOf()
        val out = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }

    fun fillFixed(bufferSize: Int, text: String): ByteArray {
        val out = ByteArray(bufferSize) // zero‑filled by default
        val bytes = text.encodeToByteArray()
        val len = minOf(bytes.size, bufferSize)
        System.arraycopy(bytes, 0, out, 0, len)
        return out
    }

    // Call this method from the main activity to shut down the connection.
    private fun cancelLocatorBluetoothSocket(locatorSocket: BluetoothSocket?) {
        if (locatorSocket != null) {
        try {
            locatorSocket.close()
        } catch (e: IOException) {
            Log.e(TAG, "Could not close the connect socket", e)
        }
            }
    }

    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods.
        fun getService(): BluetoothService = this@BluetoothService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        // Stop the service when all clients have unbinded
        stopSelf() // If this is not called, multiple service instances are retained
        return true
    }

    private fun registerReceiver() {
        // Register the receiver
        val filter = IntentFilter("CONFIRM_RECEIVER_REGISTERED")
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        filter.addAction(BluetoothDevice.ACTION_FOUND)
        registerReceiver(
            bondStateReceiver,
            filter,
            RECEIVER_EXPORTED
        )
    }

    private fun unregisterReceiver(){
        unregisterReceiver(bondStateReceiver)
    }

    inner class BondStateReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                "CONFIRM_RECEIVER_REGISTERED" ->
                    receiverRegistered = true
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    //BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Connected)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.d(TAG, "Bluetooth device disconnected")
                    BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Disconnected)
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device =
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val bondState =
                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val previousBondState = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.BOND_NONE
                    )

                    // Handle bond state changes here
                    when (bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            // Bonding succeeded
                            //connectLocator()
                            //BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Paired)
                        }

                        BluetoothDevice.BOND_BONDING -> {
                            // Bonding process in progress
                            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Pairing)
                        }

                        BluetoothDevice.BOND_NONE -> {
                            // Device is no longer bonded
                            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.PairingFailed)
                        }
                    }
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_OFF -> {
                            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.NotEnabled)
                        }
                        BluetoothAdapter.STATE_ON -> {
                            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Enabled)
                        }
                    }
                }
            }
        }
    }

//    object Crc16Ibm {
//        private const val POLY = 0xA001
//        private const val INIT = 0xFFFF

        private fun update(crcIn: Int, data: Int): Int {
            var crc = crcIn xor (data and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) {
                    (crc ushr 1) xor POLY
                } else {
                    crc ushr 1
                }
            }
            return crc
        }

        fun computeMessageCrc(frame: ByteArray): Int {
            var crc = INIT

            // header[0..3]
            for (i in 0 until 4) {
                crc = update(crc, frame[i].toInt())
            }

            // skip header[4..5] (CRC field)

            // payload starting at byte 6
            for (i in 6 until frame.size) {
                crc = update(crc, frame[i].toInt())
            }

            return crc and 0xFFFF
        }

        fun verifyMessageCrc(frame: ByteArray): Boolean {
            if (frame.size < 6) return false

            val received =
                (frame[4].toInt() and 0xFF) or
                        ((frame[5].toInt() and 0xFF) shl 8)

            val computed = computeMessageCrc(frame)

            return received == computed
        }
//    }

}