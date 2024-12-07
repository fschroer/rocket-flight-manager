package com.steampigeon.flightmanager

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.regex.Pattern

private const val TAG = "BluetoothService"
private const val messageBufferSize = 256
private const val prelaunchMessageSize = 74
private const val telemetryMessageSize = 84

enum class BluetoothState (val bluetoothState: UByte) {
    NotStarted(0u),
    Enabling(1u),
    NotEnabled(2u),
    NotSupported(3u),
    Enabled(4u),
    SelectingDevices(5u),
    NoDevicesAvailable(6u),
    Pairing(7u),
    PairingFailed(8u),
    Paired(9u),
    Connected(10u),
    ConnectionFailed(11u),
    LostConnection(12u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.bluetoothState == value } ?: throw IllegalArgumentException("Invalid type: $value")
    }

}

enum class MessageType (val messageType: UByte) {
    none(0u),
    prelaunch(1u),
    telemetry(2u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.messageType == value } ?: throw IllegalArgumentException("Invalid type: $value")
    }
}

class BluetoothService : Service() {
    companion object {
        val prelaunchMessageHeader: ByteArray = byteArrayOf(0x50, 0x52, 0x45) // PRE
        val telemetryMessageHeader: ByteArray = byteArrayOf(0x54, 0x4C, 0x4D) // TLM
    }
    // Binder given to clients.
    private val binder = LocalBinder()
    private val _data = MutableSharedFlow<ByteArray>()
    val data: SharedFlow<ByteArray> = _data.asSharedFlow()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var bluetoothState: BluetoothState = BluetoothState.NotStarted

    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val locatorInputStream: InputStream = locatorSocket!!.inputStream
    private val locatorOutputStream: OutputStream = locatorSocket!!.outputStream
    //val bluetoothManager= context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    //bluetoothAdapter = bluetoothManager.adapter
    private lateinit var enableBluetoothLauncher: ActivityResultLauncher<Intent>

    override fun onCreate() {
        super.onCreate()

    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //val device = intent?.getParcelableExtra<BluetoothDevice>("device")
        serviceScope.launch {
            // Keep listening to the InputStream until an exception occurs.
            while (true) {
                when (bluetoothState) {
                    BluetoothState.Connected -> {
                        readReceiverData()
                    }
                    BluetoothState.LostConnection -> {
                        connectLocator(locatorSocket)
                    }
                    BluetoothState.ConnectionFailed -> {
                        delay (1000)
                        connectLocator(locatorSocket)
                    }
                    BluetoothState.Enabled -> {
                        if (locatorDevice?.bondState != BluetoothDevice.BOND_BONDED) {
                            if (locatorDevice != null) {
                                locatorDevice?.createBond()
                                bluetoothState = BluetoothState.Pairing
                            } else {
                                selectBluetoothDevice(context)
                            }
                        }
                        else
                            bluetoothState = BluetoothState.Paired
                    }
                    BluetoothState.Pairing -> {
                        bluetoothState = BluetoothState.Enabled
                    }
                    BluetoothState.SelectingDevices -> {
                        if (bluetoothAdapter?.isDiscovering == false)
                            bluetoothState = BluetoothState.Enabled
                    }
                    BluetoothState.Paired -> {
                        if (bluetoothAdapter?.isEnabled == true) {
                            if (locatorDevice?.bondState != BluetoothDevice.BOND_BONDED) {
                                bluetoothState = BluetoothState.Enabled
                            }
                        }
                        else {
                            bluetoothState = BluetoothState.NotStarted
                        }
                    }
                    BluetoothState.PairingFailed -> {
                        bluetoothState = BluetoothState.Enabled
                    }
                    BluetoothState.NotStarted ->
                        enableBluetooth()
                    else -> {}
                    //}
                    //else {
                    //    bluetoothConnectionManager.RegisterReceiver(context)
                    //}
                }
                delay(100)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        //serviceScope.cancel()
    }

    fun enableBluetooth() {
        when (bluetoothAdapter?.isEnabled) {
            true -> bluetoothState = BluetoothState.Enabled
            false -> {
                val intent = Intent(this, EnablingActivity::class.java).apply { }
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                bluetoothState = BluetoothState.Enabling
            }
            null ->
                bluetoothState = BluetoothState.NotSupported
        }
    }

    fun selectBluetoothDevice(context: Context) {
        val deviceManager = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

        // Create an association request
        val deviceFilter: BluetoothDeviceFilter = BluetoothDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("RocketReceiver"))
            .build()
        val pairingRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        // Start pairing
        deviceManager.associate(pairingRequest, object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                //launcher.launch(
                //    IntentSenderRequest.Builder(chooserLauncher).build()
                //)
                pairDevice()
                //bluetoothConnectionState = BluetoothConnectionState.Pairing
                Log.d(TAG, "onDeviceFound: ${chooserLauncher.toString()}")
            }

            override fun onAssociationPending(intentSender: IntentSender) {
                super.onAssociationPending(intentSender)
                Log.d(TAG, "onAssociationPending: ${intentSender.toString()}")
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                super.onAssociationCreated(associationInfo)
                Log.d(TAG, "onAssociationCreated: ${associationInfo.toString()}")
            }

            override fun onFailure(error: CharSequence?) {
                // Handle no devices found or "don't allow" user selection
                bluetoothState = BluetoothState.PairingFailed
                Log.d(TAG, "onFailure: $error")
            }
        }, null)
        bluetoothState = BluetoothState.SelectingDevices
        /*var lastExecutionTime = System.currentTimeMillis()
        var elapsedSeconds = 0L
        while (elapsedSeconds < 5000) {
            elapsedSeconds = System.currentTimeMillis() - lastExecutionTime
            delay(100)
        }
        if (bluetoothConnectionState != BluetoothConnectionState.Paired)
            bluetoothConnectionState = BluetoothConnectionState.NotStarted*/
    }

    fun pairDevice() {
        val intent = Intent(this, PairingActivity::class.java).apply {
            //putExtra("device", device)
        }
        startActivity(intent)
    }

    @SuppressLint("MissingPermission")
    fun connectLocator(locatorSocket: BluetoothSocket?) {
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        // Cancel device discovery since locator has been selected.
        if (bluetoothAdapter?.isDiscovering == true)
            bluetoothAdapter.cancelDiscovery()
        // Attempt to connect to locator
        if (device != null && device.uuids != null) {
            val locatorSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
                device.createRfcommSocketToServiceRecord(device.uuids[0].uuid)
            }
            connectLocator(locatorSocket)
        }
        else {
            bluetoothState = BluetoothState.NoDevicesAvailable
        }
        try {
            locatorSocket?.connect()
            bluetoothState = BluetoothState.Connected
        } catch (e: IOException) {
            bluetoothState = BluetoothState.ConnectionFailed
        }
    }

    fun readReceiverData() {
        val locatorInputBuffer: ByteArray = ByteArray(messageBufferSize) // buffer store for the stream
        var numBytes = 0 // bytes returned from read()
        var headerReceived = false
        var currentMessageSize = 0
        var messageType = MessageType.none
        // Read from the InputStream.
        if (!headerReceived) {
            try {
                numBytes = locatorInputStream.read(locatorInputBuffer)
            } catch (e: IOException) {
                Log.d(TAG, "Input stream was disconnected", e)
                bluetoothState = BluetoothState.LostConnection
                numBytes = 0
            }
            when {
                locatorInputBuffer.copyOfRange(0, 3)
                    .contentEquals(prelaunchMessageHeader) ->
                    messageType = MessageType.prelaunch

                locatorInputBuffer.copyOfRange(0, 3)
                    .contentEquals(telemetryMessageHeader) ->
                    messageType = MessageType.telemetry
            }
            if (messageType != MessageType.none) {
                headerReceived = true
                currentMessageSize = numBytes
            }
            //else
            //    locatorInputBuffer.fill(0)
        } else {
            try {
                numBytes = locatorInputStream.read(
                    locatorInputBuffer,
                    currentMessageSize,
                    messageBufferSize - currentMessageSize
                )
                currentMessageSize += numBytes
            } catch (e: IOException) {
                Log.d(TAG, "Input stream was disconnected", e)
                bluetoothState = BluetoothState.LostConnection
                numBytes = 0
            }
        }
        if (numBytes != 0) {
            Log.d(TAG, logByteArrayAsChars(locatorInputBuffer, currentMessageSize))
            //Log.d(TAG, locatorInputBuffer.joinToString(", "))
        }
        when {
            messageType == MessageType.prelaunch && currentMessageSize == prelaunchMessageSize -> {
                Log.d(TAG, "Prelaunch message detected, $currentMessageSize")
                _data.emit(locatorInputBuffer.copyOfRange(0, prelaunchMessageSize))
                numBytes = 0
                currentMessageSize = 0
                headerReceived = false
                messageType = MessageType.none
            }

            messageType == MessageType.telemetry && currentMessageSize == telemetryMessageSize -> {
                Log.d(TAG, "Telemetry message detected, $currentMessageSize")
                //_data.emit(locatorInputBuffer.copyOfRange(0, telemetryMessageSize))
                numBytes = 0
                currentMessageSize = 0
                headerReceived = false
                messageType = MessageType.none
            }

            currentMessageSize > telemetryMessageSize -> {
                Log.d(TAG, "Overflow detected, $currentMessageSize")
                //locatorInputBuffer.fill(0)
                numBytes = 0
                currentMessageSize = 0
                headerReceived = false
                messageType = MessageType.none
            }
        }
        //viewModel.updateData(mmBuffer)
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
            if (byte >= 32 && byte <127)
                message += byte.toInt().toChar()
            else
                message = "$message?"
        }
        return message
    }

    // Call this from the main activity to send data to the remote device.
/*        fun write(bytes: ByteArray) {
        try {
            mmOutStream.write(bytes)
        } catch (e: IOException) {
            Log.e(TAG, "Error occurred when sending data", e)

            // Send a failure message back to the activity.
            val writeErrorMsg = handler.obtainMessage(MESSAGE_TOAST)
            val bundle = Bundle().apply {
                putString("toast", "Couldn't send data to the other device")
            }
            writeErrorMsg.data = bundle
            handler.sendMessage(writeErrorMsg)
            return
        }

        // Share the sent message with the UI activity.
        val writtenMsg = handler.obtainMessage(
            MESSAGE_WRITE, -1, -1, mmBuffer)
        writtenMsg.sendToTarget()
    }

    // Call this method from the main activity to shut down the connection.
    fun cancel() {
        try {
            mmSocket.close()
        } catch (e: IOException) {
            Log.e(TAG, "Could not close the connect socket", e)
        }
    }*/
    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods.
        fun getService(): BluetoothService = this@BluetoothService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        // Stop the service when all clients have unbinded
        stopSelf()
        return true
    }
}