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
import com.steampigeon.flightmanager.data.LocatorArmedMessageState
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.LocatorConfigMessageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "BluetoothService"
private const val messageBufferSize = 256
private const val prelaunchMessageSize = 74
private const val telemetryMessageSize = 90
private const val CHANNEL_ID = "BluetoothService"

enum class MessageType (val messageType: UByte) {
    none(0u),
    prelaunch(1u),
    telemetry(2u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.messageType == value } ?: throw IllegalArgumentException("Invalid type: $value")
    }
}

class BluetoothService() : Service() {
    companion object {
        val prelaunchMessageHeader: ByteArray = byteArrayOf(0x50, 0x52, 0x45) // PRE
        val telemetryMessageHeader: ByteArray = byteArrayOf(0x54, 0x4C, 0x4D) // TLM
    }
    private var serviceStarted = false
    private val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private val bondStateReceiver = BondStateReceiver()
    var receiverRegistered = false
    //private val application: BluetoothManagerRepository = applicationContext as BluetoothManagerRepository
    //lateinit var bluetoothManagerRepository: BluetoothManagerRepository
    // Binder given to clients.
    private val binder = LocalBinder()
    private val _data = MutableSharedFlow<ByteArray>()
    val data: SharedFlow<ByteArray> = _data.asSharedFlow()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var locatorSocket: BluetoothSocket? = null
    private lateinit var locatorInputStream: InputStream
    private lateinit var locatorOutputStream: OutputStream
    private var armedStateChangeWaitCount = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, getNotification())
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!serviceStarted) {
            serviceStarted = true
            val locatorInputBuffer: ByteArray =
                ByteArray(messageBufferSize) // buffer store for the stream
            var numBytes = 0 // bytes returned from read()
            var headerReceived = false
            var currentMessageSize = 0
            var messageType = MessageType.none
            //bluetoothManagerRepository = intent?.getSerializableExtra("bluetoothManagerRepository") as BluetoothManagerRepository
            //val device = intent?.getParcelableExtra<BluetoothDevice>("device")

            // Register broadcast receiver for bluetooth status events
            if (!receiverRegistered) {
                registerReceiver()
                val intent = Intent("CONFIRM_RECEIVER_REGISTERED")
                sendBroadcast(intent)
            }
            serviceScope.launch {
                // Keep listening to the InputStream until an exception occurs.
                var loopCount = 0
                while (true) {
                    Log.d(TAG, "Call maintainLocatorDevicePairing: $loopCount")
                    maintainLocatorDevicePairing()
                    if (BluetoothManagerRepository.bluetoothConnectionState.value == BluetoothConnectionState.Connected) {
                        // Read from the InputStream.
                        if (!headerReceived) {
                            try {
                                numBytes = locatorInputStream.read(locatorInputBuffer)
                            } catch (e: IOException) {
                                Log.d(TAG, "Input stream was disconnected", e)
                                BluetoothManagerRepository.updateBluetoothConnectionState(
                                    BluetoothConnectionState.Disconnected
                                )
                            }
                            when {
                                locatorInputBuffer.copyOfRange(0, 3)
                                    .contentEquals(prelaunchMessageHeader) -> {
                                    messageType = MessageType.prelaunch
                                    if (BluetoothManagerRepository.armedState.value) { //Disarm request acknowledged by locator
                                        BluetoothManagerRepository.updateArmedState(false)
                                        BluetoothManagerRepository.updateLocatorArmedMessageState(
                                            LocatorArmedMessageState.Idle
                                        )
                                        armedStateChangeWaitCount = 0
                                    } else {
                                        if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorArmedMessageState.Sent) { //Waiting for locator acknowledgement of arm request
                                            armedStateChangeWaitCount++
                                            if (armedStateChangeWaitCount >= 5) {
                                                BluetoothManagerRepository.updateLocatorArmedMessageState(
                                                    LocatorArmedMessageState.Idle
                                                )
                                            }
                                        }
                                    }
                                }

                                locatorInputBuffer.copyOfRange(0, 3)
                                    .contentEquals(telemetryMessageHeader) -> {
                                    messageType = MessageType.telemetry
                                    if (!BluetoothManagerRepository.armedState.value) { //Arm request acknowledged by locator
                                        BluetoothManagerRepository.updateArmedState(true)
                                        BluetoothManagerRepository.updateLocatorArmedMessageState(
                                            LocatorArmedMessageState.Idle
                                        )
                                        armedStateChangeWaitCount = 0
                                    } else {
                                        if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorArmedMessageState.Sent) { //Waiting for locator acknowledgement of disarm request
                                            armedStateChangeWaitCount++
                                            if (armedStateChangeWaitCount >= 5) {
                                                BluetoothManagerRepository.updateLocatorArmedMessageState(
                                                    LocatorArmedMessageState.Idle
                                                )
                                            }
                                        }
                                    }
                                }
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
                                BluetoothManagerRepository.updateBluetoothConnectionState(
                                    BluetoothConnectionState.Disconnected
                                )
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
                                _data.emit(locatorInputBuffer.copyOfRange(0, telemetryMessageSize))
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
                        if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorArmedMessageState.SendRequested)
                            changeLocatorArmedState(!BluetoothManagerRepository.armedState.value)
                    } else {
                        numBytes = 0
                        if (BluetoothManagerRepository.bluetoothConnectionState.value == BluetoothConnectionState.Paired)
                            connectLocator()
                    }
                    delay(100)
                    loopCount++
                }
            }
        }
        return START_STICKY
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
    fun maintainLocatorDevicePairing() {
        //if (bluetoothConnectionManager.receiverRegistered) {
        when (BluetoothManagerRepository.bluetoothConnectionState.value) {
            BluetoothConnectionState.Pairing -> {
                Log.d(TAG, "Changing state from Pairing to Enabled")
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Enabled)
            }
            BluetoothConnectionState.AssociateStart -> {
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.AssociateWait)
                Log.d(TAG, "SelectingDevices delay start. Discovering: " + bluetoothAdapter.isDiscovering)
                while (bluetoothAdapter.isDiscovering != false) { Thread.sleep(100) }
                Log.d(TAG, "Changing state from SelectingDevices to PairingFailed")
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.PairingFailed)
            }
            BluetoothConnectionState.Paired -> {
                if (bluetoothAdapter.isEnabled == true) {
                    if (BluetoothManagerRepository.locatorDevice.value!!.bondState != BluetoothDevice.BOND_BONDED) {
                        Log.d(TAG, "Changing state from Paired to Enabled")
                        BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Enabled)
                    }
                }
                else {
                    Log.d(TAG, "Changing state to NotStarted")
                    BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.NotStarted)
                }
            }
            BluetoothConnectionState.PairingFailed -> {
                if (bluetoothAdapter.isDiscovering == true)
                    bluetoothAdapter.cancelDiscovery()
                Log.d(TAG, "PairingFailed delay start")
                Thread.sleep(500)
                Log.d(TAG, "PairingFailed delay end")
                Log.d(TAG, "Changing state from PairingFailed to Enabled")
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Enabled)
            }
            BluetoothConnectionState.Disconnected -> {
                cancelLocatorBluetoothSocket(locatorSocket)
                Log.d(TAG, "Disconnected delay start")
                Thread.sleep(500)
                Log.d(TAG, "Disconnected delay end")
                Log.d(TAG, "Changing state from Disconnected to Paired")
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Paired)
            }
            else -> {}
            //}
            //else {
            //    bluetoothConnectionManager.RegisterReceiver(context)
            //}
        }
    }

    @SuppressLint("MissingPermission")
    fun connectLocator() {
        if (BluetoothManagerRepository.locatorDevice.value != null && BluetoothManagerRepository.locatorDevice.value!!.uuids != null) {
            locatorSocket = BluetoothManagerRepository.locatorDevice.value!!.createRfcommSocketToServiceRecord(BluetoothManagerRepository.locatorDevice.value!!.uuids[0].uuid)
            locatorInputStream = locatorSocket!!.inputStream
            locatorOutputStream = locatorSocket!!.outputStream
            // Cancel device discovery since locator has been selected.
            if (bluetoothAdapter.isDiscovering == true)
                bluetoothAdapter.cancelDiscovery()
            // Attempt to connect to locator
            try {
                locatorSocket?.connect()
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Connected)
                BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorArmedMessageState.Idle)
            } catch (e: IOException) {
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Disconnected)
            }
        }
        else {
            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.NoDevicesAvailable)
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
            if (byte >= 32 && byte <127)
                message += byte.toInt().toChar()
            else
                message = "$message?"
        }
        return message
    }

    fun changeLocatorArmedState(armedState: Boolean) {
        val armCommand = "Run\u0004".toByteArray()
        val disarmCommand = "Stop\u0004".toByteArray()
        try {
            locatorOutputStream.write(when (armedState) {
                true -> armCommand
                false -> disarmCommand
            } )
        } catch (e: IOException) {
            Log.e(TAG, "Error occurred when sending data", e)
            BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorArmedMessageState.SendFailure)
            return
        }
        BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorArmedMessageState.Sent)
    }

    fun changeLocatorConfig(locatorConfig: LocatorConfig) {
        val configMessage = "Config\u0004".toByteArray() +
            byteArrayOf((locatorConfig.deployMode ?: DeployMode.DroguePrimaryMainPrimary).deployMode.toByte(),
            locatorConfig.launchDetectAltitude.toByte(),
            (locatorConfig.launchDetectAltitude / 256).toByte(),
            locatorConfig.droguePrimaryDeployDelay.toByte(),
            locatorConfig.drogueBackupDeployDelay.toByte(),
            locatorConfig.mainPrimaryDeployAltitude.toByte(),
            (locatorConfig.mainPrimaryDeployAltitude / 256).toByte(),
            locatorConfig.mainBackupDeployAltitude.toByte(),
            (locatorConfig.mainBackupDeployAltitude / 256).toByte(),
            locatorConfig.deploySignalDuration.toByte()
        ) + locatorConfig.deviceName.toByteArray()

        try {
            locatorOutputStream.write(configMessage)
        } catch (e: IOException) {
            Log.e(TAG, "Error occurred when sending data", e)
            BluetoothManagerRepository.updateLocatorConfigMessageState(LocatorConfigMessageState.SendFailure)
            return
        }
        BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorArmedMessageState.Sent)
    }

    // Call this method from the main activity to shut down the connection.
    fun cancelLocatorBluetoothSocket(locatorSocket: BluetoothSocket?) {
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
        //stopSelf()
        return true
    }

    fun registerReceiver() {
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

    fun unregisterReceiver(){
        unregisterReceiver(bondStateReceiver)
    }

    inner class BondStateReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                "CONFIRM_RECEIVER_REGISTERED" ->
                    receiverRegistered = true
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Connected)
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
                            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Paired)
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
}