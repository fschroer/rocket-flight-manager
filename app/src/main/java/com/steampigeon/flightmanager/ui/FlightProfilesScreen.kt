package com.steampigeon.flightmanager.ui

//import android.app.PendingIntent
//import android.content.IntentFilter
//import android.os.Build
//import androidx.annotation.RequiresApi
import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
//import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.BluetoothService
//import com.hoho.android.usbserial.driver.UsbSerialPort
//import com.hoho.android.usbserial.driver.UsbSerialProber
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.LocatorMessageState
import java.math.RoundingMode
import java.text.DateFormat

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
    val flightProfileDataMessageState = viewModel.flightProfileDataMessageState.collectAsState().value
    val flightProfileAglData = viewModel.flightProfileAglData.collectAsState().value
    val flightProfileAccelerometerData = viewModel.flightProfileAccelerometerData.collectAsState().value
    val flightProfileArchivePosition = viewModel.flightProfileArchivePosition.collectAsState().value
    //val requestFlightProfileMetadata = viewModel.requestFlightProfileMetadata.collectAsState().value
    //var localRequestFlightProfileMetadata by remember { mutableStateOf(requestFlightProfileMetadata) }

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
            //viewModel.updateRequestFlightProfileMetadata(false)
            //localRequestFlightProfileMetadata = false
            //viewModel.updateFlightProfileMetadataMessageState(LocatorMessageState.Idle)
            viewModel.updateFlightProfileDataMessageState(LocatorMessageState.Idle)
            viewModel.clearFlightProfileMetadata()
            viewModel.updateFlightProfileMetadataMessageState(LocatorMessageState.SendRequested)
            if (service?.requestFlightProfileMetadata() == true)
                viewModel.updateFlightProfileMetadataMessageState(LocatorMessageState.Sent)
            else
                viewModel.updateFlightProfileMetadataMessageState(LocatorMessageState.SendFailure)
            viewModel.updateFlightMetadataState()
        }
    }
    BackHandler(enabled = true) {
        viewModel.updateFlightProfileMetadataMessageState(LocatorMessageState.Idle)
    }
    if (flightProfileDataMessageState != LocatorMessageState.AckUpdated) {
        Column (
            modifier = modifier
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            Column(
                modifier = modifier.weight(11f),
                verticalArrangement = Arrangement.SpaceAround
            ) {
                val dateTimeFormat = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG)
                flightProfileMetadata.forEach { flightProfileMetadataItem ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.u_turn_right),
                            contentDescription = stringResource(R.string.flight_profiles)
                        )
                        Spacer(modifier = modifier.weight(1f))
                        Column(
                            modifier = Modifier
                                .weight(5f)
                                .clickable {
                                    viewModel.updateFlightProfileArchivePosition(flightProfileMetadataItem.position)
                                    if (flightProfileDataMessageState == LocatorMessageState.Idle) {
                                        viewModel.updateFlightProfileDataMessageState(LocatorMessageState.SendRequested)
                                        if (service?.requestFlightProfileData(flightProfileMetadataItem.position) == true)
                                            viewModel.updateFlightProfileDataMessageState(LocatorMessageState.Sent)
                                        else
                                            viewModel.updateFlightProfileDataMessageState(LocatorMessageState.SendFailure)
                                        viewModel.updateFlightDataState()
                                    }
                                },
                        ) {
                            Text(dateTimeFormat.format(flightProfileMetadataItem.date))
                            Text("Apogee: ${flightProfileMetadataItem.apogee.toBigDecimal().setScale(1, RoundingMode.UP).toFloat()}m")
                        }
                    }
                    Divider(modifier = modifier.weight(1f))
                }
            }
            Spacer(modifier = modifier.weight(1f))
            Row(
                modifier = modifier,
                //.fillMaxWidth()
                //.padding(dimensionResource(R.dimen.padding_medium)),
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_medium)),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onCancelButtonClicked
                ) {
                    Text(stringResource(R.string.return_to_main))
                }
            }
        }
    }
    else {
        val maxAgl = flightProfileMetadata[flightProfileArchivePosition].apogee
        var maxAccelerometer: Short = 0
        var minAccelerometer: Short = 0
        flightProfileAccelerometerData.forEach { accelerometerSample ->
            if (accelerometerSample.x > maxAccelerometer)
                maxAccelerometer = accelerometerSample.x
            if (accelerometerSample.x < minAccelerometer)
                minAccelerometer = accelerometerSample.x
            if (accelerometerSample.y > maxAccelerometer)
                maxAccelerometer = accelerometerSample.y
            if (accelerometerSample.y < minAccelerometer)
                minAccelerometer = accelerometerSample.y
            if (accelerometerSample.z > maxAccelerometer)
                maxAccelerometer = accelerometerSample.z
            if (accelerometerSample.z < minAccelerometer)
                minAccelerometer = accelerometerSample.z
        }
        val maxChartWidth = flightProfileMetadata[flightProfileArchivePosition].timeToDrogue + (
                if (flightProfileAglData.size > flightProfileMetadata[flightProfileArchivePosition].timeToDrogue * RocketViewModel.SAMPLES_PER_SECOND)
                    flightProfileMetadata[flightProfileArchivePosition].timeToDrogue + (flightProfileAglData.size - flightProfileMetadata[flightProfileArchivePosition].timeToDrogue * RocketViewModel.SAMPLES_PER_SECOND)
                else 0f)
        val gridColor = MaterialTheme.colorScheme.onPrimary
        val aglColor = MaterialTheme.colorScheme.primary
        val legendColor = MaterialTheme.colorScheme.secondaryContainer
        val targetGrids = 5
        Column (
            modifier = modifier
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            val scale = remember { mutableFloatStateOf(1f) }
            val offset = remember { mutableStateOf(Offset.Zero) }
            Canvas (modifier = modifier
                .weight(11f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale.value *= zoom
                        offset.value += pan
                    }
                }
                .graphicsLayer(
                    scaleX = scale.floatValue,
                    scaleY = scale.floatValue,
                    translationX = offset.value.x,
                    translationY = offset.value.y
                )
            ) {
                val chartMarginX = 64f
                val chartMarginY = 32f
                val canvasHeight = size.height - chartMarginY
                val canvasWidth = size.width - chartMarginX
                val scaleFactorX = canvasWidth / maxChartWidth
                val scaleFactorY = canvasHeight / maxAgl
                var flightTime = 0f
                val gridWidth = (maxChartWidth / targetGrids).toInt()
                val gridHeight = (maxAgl / targetGrids).toInt()
                // Draw vertical grid lines
                for (gridX in 0..maxChartWidth.toInt() step gridWidth) {
                    drawLine(
                        color = gridColor,
                        strokeWidth = 1.dp.toPx(),
                        start = Offset(gridX * scaleFactorX + chartMarginX, 0f),
                        end = Offset(gridX * scaleFactorX + chartMarginX, canvasHeight)
                    )
                    drawIntoCanvas { canvas ->
                        val textHeight = chartMarginY
                        val paint = TextPaint().apply {
                            color = legendColor.toArgb()
                            textSize = textHeight
                        }
                        val text = "$gridX"
                        val x = gridX * scaleFactorX + chartMarginX - paint.measureText(text) / 2
                        val y = canvasHeight + textHeight
                        canvas.nativeCanvas.drawText(text, x, y, paint)
                    }
                }
                // Draw horizontal grid lines
                for (gridY in maxAgl.toInt() downTo 0 step gridHeight) {
                    drawLine(
                        color = gridColor,
                        strokeWidth = 1.dp.toPx(),
                        start = Offset(chartMarginX, gridY * scaleFactorY),
                        end = Offset(canvasWidth + chartMarginX, gridY * scaleFactorY)
                    )
                    if (gridY != 0) {
                        drawIntoCanvas { canvas ->
                            val textHeight = chartMarginY
                            val paint = TextPaint().apply {
                                color = legendColor.toArgb()
                                textSize = textHeight
                            }
                            val text = "$gridY"
                            val x = chartMarginX - paint.measureText(text) - 8
                            val y = canvasHeight - (gridY * scaleFactorY - textHeight / 2) - 8
                            canvas.nativeCanvas.drawText(text, x, y, paint)
                        }
                    }
                }
                // Draw altitude
                var lastX = chartMarginX
                var lastY = canvasHeight
                flightProfileAglData.forEachIndexed { sampleID, aglSample ->
                    val newX = flightTime * scaleFactorX + chartMarginX
                    val newY = (maxAgl - aglSample.toFloat() / RocketViewModel.ALTIMETER_SCALE) * scaleFactorY
                    if (aglSample.toInt() == (flightProfileMetadata[flightProfileArchivePosition].apogee * RocketViewModel.ALTIMETER_SCALE).toInt()) {
                        drawIntoCanvas { canvas ->
                            val textHeight = chartMarginY
                            val paint = TextPaint().apply {
                                color = legendColor.toArgb()
                                textSize = textHeight
                            }
                            val text = "${aglSample.toFloat() / RocketViewModel.ALTIMETER_SCALE}"
                            val x = newX
                            val y = newY
                            canvas.nativeCanvas.drawText(text, x, y, paint)
                        }
                    }
                    if (sampleID == (flightProfileMetadata[flightProfileArchivePosition].timeToDrogue * RocketViewModel.SAMPLES_PER_SECOND).toInt() - 2) {
                        drawCircle(
                            color = Color(0xFFFF0000),
                            radius = 2.dp.toPx(),
                            center = Offset(newX, newY)
                        )
                    }
                    drawLine(color = aglColor,
                        strokeWidth = 1.dp.toPx(),
                        start = Offset(lastX, lastY),
                        end = Offset(newX, newY)
                    )
                    lastX = newX
                    lastY = newY
                    flightTime += if (sampleID < flightProfileMetadata[flightProfileArchivePosition].timeToDrogue * RocketViewModel.SAMPLES_PER_SECOND - 2) 0.05f else 1.0f
                }
                val scaleFactorAccY = canvasHeight / (maxAccelerometer - minAccelerometer)
                // Draw accelerometer vertical axis
                val accelerometerGridHeight = ((maxAccelerometer - minAccelerometer) / targetGrids).toInt()
                for (gridY in maxAccelerometer downTo minAccelerometer step accelerometerGridHeight) {
                    drawIntoCanvas { canvas ->
                        val textHeight = chartMarginY
                        val paint = TextPaint().apply {
                            color = legendColor.toArgb()
                            textSize = textHeight
                        }
                        val text = "${(gridY * 10 / RocketViewModel.ACCELEROMETER_SCALE).toFloat() / 10}"
                        val x = canvasWidth
                        val y = canvasHeight - ((gridY - minAccelerometer) * scaleFactorAccY - textHeight / 2) - 8
                        canvas.nativeCanvas.drawText(text, x, y, paint)
                    }
                }
                // Draw accelerometer X axis stats
                flightTime = 0f
                lastX = chartMarginX
                lastY = canvasHeight
                flightProfileAccelerometerData.forEachIndexed { sampleID, accelerometerSample ->
                    val newX = flightTime * scaleFactorX + chartMarginX
                    val newY = (maxAccelerometer - accelerometerSample.x).toFloat() * scaleFactorAccY
                    drawLine(color = Color(0xffff0000),
                        strokeWidth = 1.dp.toPx(),
                        start = Offset(lastX, lastY),
                        end = Offset(newX, newY)
                    )
                    lastX = newX
                    lastY = newY
                    flightTime += 0.05f
                }
                // Draw accelerometer Y axis stats
                flightTime = 0f
                lastX = chartMarginX
                lastY = canvasHeight
                flightProfileAccelerometerData.forEachIndexed { sampleID, accelerometerSample ->
                    val newX = flightTime * scaleFactorX + chartMarginX
                    val newY = (maxAccelerometer - accelerometerSample.y).toFloat() * scaleFactorAccY
                    drawLine(color = Color(0xffffff00),
                        strokeWidth = 1.dp.toPx(),
                        start = Offset(lastX, lastY),
                        end = Offset(newX, newY)
                    )
                    lastX = newX
                    lastY = newY
                    flightTime += 0.05f
                }
                // Draw accelerometer Z axis stats
                flightTime = 0f
                lastX = chartMarginX
                lastY = canvasHeight
                flightProfileAccelerometerData.forEachIndexed { sampleID, accelerometerSample ->
                    val newX = flightTime * scaleFactorX + chartMarginX
                    val newY = (maxAccelerometer - accelerometerSample.z).toFloat() * scaleFactorAccY
                    drawLine(color = Color(0xff00ff00),
                        strokeWidth = 1.dp.toPx(),
                        start = Offset(lastX, lastY),
                        end = Offset(newX, newY)
                    )
                    lastX = newX
                    lastY = newY
                    flightTime += 0.05f
                }
            }
            Spacer (modifier = modifier.weight(1f))
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_medium)),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.updateFlightProfileDataMessageState(LocatorMessageState.Idle)
                    }
                ) {
                    Text(stringResource(R.string.return_to_main))
                }
            }
        }
        Row (

        ) {
            Spacer (modifier = modifier.weight(3f))
            Column (
                modifier = modifier
                    .fillMaxHeight()
                    .padding(16.dp)
                    .weight(4f)
            ) {
                Text(flightProfileDataMessageState.toString())
                Text("Samples received: ${flightProfileAglData.size}")
            }
            Spacer (modifier = modifier.weight(1f))
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