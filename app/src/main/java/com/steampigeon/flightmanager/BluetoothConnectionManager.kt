package com.steampigeon.flightmanager

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import java.util.regex.Pattern
/*
private const val REQUEST_CODE_ASSOCIATION = 0
private const val TAG = "BluetoothConnectionManager"

enum class BluetoothConnectionState (val bluetoothConnectionState: UByte) {
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
    Disconnected(11u);

    companion object {
        fun fromUByte(value: UByte) = entries.firstOrNull { it.bluetoothConnectionState == value } ?: throw IllegalArgumentException("Invalid type: $value")
    }

}

class BluetoothConnectionManager() {

    private lateinit var launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val bondStateReceiver = BondStateReceiver()
    var locatorDevice: BluetoothDevice? = null
    var bluetoothConnectionState: BluetoothConnectionState = BluetoothConnectionState.NotStarted
    var receiverRegistered = false

    @SuppressLint("MissingPermission")
    @Composable
    fun InitializeCompanionDeviceManager() {
        launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Handle successful pairing
                locatorDevice = result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
                locatorDevice?.createBond()
                bluetoothConnectionState = BluetoothConnectionState.Paired
            } else {
                // Handle pairing failure
                bluetoothConnectionState = BluetoothConnectionState.PairingFailed
            }
        }
    }

    fun RegisterReceiver(context: Context) {
        // Register the receiver
        val filter = IntentFilter("CONFIRM_RECEIVER_REGISTERED")
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        filter.addAction(BluetoothDevice.ACTION_FOUND)
        context.registerReceiver(
            bondStateReceiver,
            filter,
            Context.RECEIVER_EXPORTED
        )
    }

    fun UnregisterReceiver(context: Context){
        context.unregisterReceiver(bondStateReceiver)
    }

    fun sendBroadcast(context: Context) {
        val intent = Intent("CONFIRM_RECEIVER_REGISTERED")
        //intent.putExtra("message", "Hello from Broadcast!")
        context.sendBroadcast(intent)
    }

    @SuppressLint("MissingPermission")
    fun enableBluetooth(context: Context) : Boolean? {
        val bluetoothManager= context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        when (bluetoothAdapter?.isEnabled) {
            true -> bluetoothConnectionState = BluetoothConnectionState.Enabled
            false -> {
                val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                context.startActivity(enableBluetoothIntent)
                bluetoothConnectionState = BluetoothConnectionState.Enabling
            }
            null ->
                bluetoothConnectionState = BluetoothConnectionState.NotSupported
        }
        return bluetoothAdapter?.isEnabled
    }

    fun selectBluetoothDevice(context: Context) : Boolean {
        val deviceManager = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

        // Create an association request
            if (bluetoothConnectionState == BluetoothConnectionState.Enabled) {
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
                        launcher.launch(
                            IntentSenderRequest.Builder(chooserLauncher).build()
                        )
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
                        bluetoothConnectionState = BluetoothConnectionState.PairingFailed
                        Log.d(TAG, "onFailure: $error")
                    }
                }, null)
                bluetoothConnectionState = BluetoothConnectionState.SelectingDevices
                /*var lastExecutionTime = System.currentTimeMillis()
                var elapsedSeconds = 0L
                while (elapsedSeconds < 5000) {
                    elapsedSeconds = System.currentTimeMillis() - lastExecutionTime
                    delay(100)
                }
                if (bluetoothConnectionState != BluetoothConnectionState.Paired)
                    bluetoothConnectionState = BluetoothConnectionState.NotStarted*/
            }
        return (locatorDevice != null)
    }

    @SuppressLint("MissingPermission")
    suspend fun maintainLocatorDevicePairing(context: Context) {
        //if (bluetoothConnectionManager.receiverRegistered) {
        when (bluetoothConnectionState) {
            BluetoothConnectionState.NotStarted ->
                enableBluetooth(context)
            BluetoothConnectionState.Enabled -> {
                if (locatorDevice?.bondState != BluetoothDevice.BOND_BONDED) {
                    if (locatorDevice != null) {
                        locatorDevice?.createBond()
                        bluetoothConnectionState = BluetoothConnectionState.Pairing
                    } else {
                        selectBluetoothDevice(context)
                    }
                }
                else
                    bluetoothConnectionState = BluetoothConnectionState.Paired
            }
            BluetoothConnectionState.Pairing -> {
                bluetoothConnectionState = BluetoothConnectionState.Enabled
            }
            BluetoothConnectionState.SelectingDevices -> {
                delay(5000)
                if (bluetoothAdapter?.isDiscovering == false)
                    bluetoothConnectionState = BluetoothConnectionState.PairingFailed
            }
            BluetoothConnectionState.Paired -> {
                if (bluetoothAdapter?.isEnabled == true) {
                    if (locatorDevice?.bondState != BluetoothDevice.BOND_BONDED) {
                        bluetoothConnectionState = BluetoothConnectionState.Enabled
                    }
                }
                else {
                    bluetoothConnectionState = BluetoothConnectionState.NotStarted
                }
            }
            BluetoothConnectionState.PairingFailed -> {
                if (bluetoothAdapter?.isDiscovering == true)
                    bluetoothAdapter!!.cancelDiscovery()
                delay(5000)
                bluetoothConnectionState = BluetoothConnectionState.Enabled
            }
            else -> {}
            //}
            //else {
            //    bluetoothConnectionManager.RegisterReceiver(context)
            //}
        }
    }

    fun unpairBluetoothDevice() {
        if (locatorDevice != null) {
            try {
                val removeBondMethod = locatorDevice?.javaClass?.getMethod("removeBond")
                removeBondMethod?.invoke(locatorDevice)
            } catch (e: Exception) {
                e.printStackTrace()
                // Handle the exception, maybe log it or show a message to the user
            }
        }
        bluetoothConnectionState = BluetoothConnectionState.NotStarted
    }

    inner class BondStateReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                "CONFIRM_RECEIVER_REGISTERED" ->
                    receiverRegistered = true
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    bluetoothConnectionState = BluetoothConnectionState.Connected
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.d(TAG, "Bluetooth device disconnected")
                    bluetoothConnectionState = BluetoothConnectionState.Disconnected
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
                            bluetoothConnectionState = BluetoothConnectionState.Paired
                        }

                        BluetoothDevice.BOND_BONDING -> {
                            // Bonding process in progress
                            bluetoothConnectionState = BluetoothConnectionState.Pairing
                        }

                        BluetoothDevice.BOND_NONE -> {
                            // Device is no longer bonded
                            bluetoothConnectionState = BluetoothConnectionState.PairingFailed
                        }
                    }
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_OFF -> {
                            bluetoothConnectionState = BluetoothConnectionState.NotEnabled
                        }
                        BluetoothAdapter.STATE_ON -> {
                            bluetoothConnectionState = BluetoothConnectionState.Enabled
                        }
                    }
                }
            }
        }
    }
}

class EnablingActivity : AppCompatActivity() {

    private val requestBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            bluetoothConnectionManager.bluetoothConnectionState = BluetoothConnectionState.Enabled
        } else {
            bluetoothConnectionManager.bluetoothConnectionState = BluetoothConnectionState.NotEnabled
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initiate enabling process
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_TURNING_ON)
        }
        requestBluetooth.launch(intent)
    }
}

class PairingActivity : AppCompatActivity() {

    private val requestBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            //locatorDevice = result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
            //locatorDevice?.createBond()
            bluetoothConnectionManager.bluetoothConnectionState = BluetoothConnectionState.Paired
        } else {
            // Pairing failed
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val device: BluetoothDevice? = intent.getParcelableExtra("device")
        device?.let {
            // Initiate pairing process with the device
            val intent = Intent(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
                putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            }
            requestBluetooth.launch(intent)
        }
    }
}

 */