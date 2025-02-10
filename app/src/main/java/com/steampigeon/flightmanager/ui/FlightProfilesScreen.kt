package com.steampigeon.flightmanager.ui

//import android.app.PendingIntent
//import android.content.IntentFilter
//import android.os.Build
//import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
//import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.BluetoothService
//import com.hoho.android.usbserial.driver.UsbSerialPort
//import com.hoho.android.usbserial.driver.UsbSerialProber
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.FlightProfileMetadata
import com.steampigeon.flightmanager.data.LocatorMessageState

//private const val WRITE_WAIT_MILLIS = 2000
//private const val ACTION_USB_PERMISSION = "com.steampigeon.flightmanager.USB_PERMISSION"
private const val TAG = "FlightProfiles"

/**
 * Composable that displays map download options,
 * [onCancelButtonClicked] lambda that cancels receiver settings when user clicks cancel
 */
@Composable
fun FlightProfilesScreen(
    viewModel: RocketViewModel = viewModel(),
    service: BluetoothService?,
    onCancelButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val flightProfileMetadataMessageState = viewModel.flightProfileMetadataMessageState.collectAsState().value
    val flightProfileMetadata = viewModel.flightProfileMetadata.collectAsState().value

//    val context = LocalContext.current
//    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
//    val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
//    val filter = IntentFilter(ACTION_USB_PERMISSION)
//    context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
//    val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
//    val driver = drivers[0]
//    usbManager.requestPermission(driver.device, permissionIntent)
//    val connection = usbManager.openDevice(driver.device)
//    val port = driver.ports[0]
//    port.open(connection)
//    port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
//    val serialManager = SerialManager(port);
//
//    var locatorSerialReadBuffer = serialManager.locatorSerialReadBuffer.collectAsState().value
//
//    LaunchedEffect(locatorSerialReadBuffer) {
//        if (locatorSerialReadBuffer.isNotEmpty()) {
//        }
//    }
//

    LaunchedEffect(flightProfileMetadataMessageState) {
        if (flightProfileMetadataMessageState == LocatorMessageState.Idle) {
            viewModel.updateFlightProfileMetadataMessageState(LocatorMessageState.SendRequested)
            if (service?.requestFlightProfileMetadata() == true)
                viewModel.updateFlightProfileMetadataMessageState(LocatorMessageState.Sent)
            else
                viewModel.updateFlightProfileMetadataMessageState(LocatorMessageState.SendFailure)
            viewModel.updateFlightMetadataState()
        }
    }
    Column (
        modifier = modifier.fillMaxHeight().padding(16.dp)
    ) {
        Column(
            modifier = modifier.weight(11f),
            verticalArrangement = Arrangement.SpaceAround
        ) {
            for (flightProfileMetadataItem in flightProfileMetadata) {
                OutlinedButton(
                    modifier = Modifier.weight(5f),
                    // the button is enabled when the user makes a selection
                    onClick = {
                    }
                ) {
                    Column {
                        Text(flightProfileMetadataItem.date.toString())
                        Text(flightProfileMetadataItem.apogee.toString())
                        Text(flightProfileMetadataItem.timeToApogee.toString())
                    }
                }
                Spacer (modifier = modifier.weight(1f))
            }
        }
        Spacer (modifier = modifier.weight(1f))
        Row(
            modifier = modifier,
            //.fillMaxWidth()
            //.padding(dimensionResource(R.dimen.padding_medium)),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_medium)),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
//                    port.close()
                    onCancelButtonClicked
                }
            ) {
                Text(stringResource(R.string.return_to_main))
            }
        }
    }
}

//private val usbReceiver = object : BroadcastReceiver() {
//
//    override fun onReceive(context: Context, intent: Intent) {
//        if (ACTION_USB_PERMISSION == intent.action) {
//            synchronized(this) {
//                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
//
//                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
//                    device?.apply {
//                        // call method to set up device communication
//                    }
//                } else {
//                    Log.d(TAG, "permission denied for device $device")
//                }
//            }
//        }
//    }
//}