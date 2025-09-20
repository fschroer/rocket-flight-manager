package com.steampigeon.flightmanager.ui

//import android.app.PendingIntent
//import android.content.IntentFilter
//import android.os.Build
//import androidx.annotation.RequiresApi
import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.LocatorMessageState
import java.math.RoundingMode
import java.text.DateFormat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

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
    val flightEventData = viewModel.flightEventData.collectAsState().value
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
    if (flightProfileDataMessageState == LocatorMessageState.Idle) { // Add check for existence of flight data. Also need to clear flight data lists when exiting chart.
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
                        Text("${flightProfileMetadataItem.position}")
                        Spacer(modifier = modifier.weight(1f))
                        Column(
                            modifier = Modifier
                                .weight(5f)
                                .clickable {
                                    if (flightProfileMetadataItem.apogee > 0) {
                                        viewModel.updateFlightProfileArchivePosition(flightProfileMetadataItem.position)
                                        if (flightProfileDataMessageState == LocatorMessageState.Idle) {
                                            if (service != null) {
                                                viewModel.getFlightProfileData(service)
                                            }
                                        }
                                    }
                                },
                        ) {
                            if (flightProfileMetadataItem.apogee > 0) {
                                flightProfileMetadataItem.date?.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))?.let { Text(it) }
                                Text("Apogee: ${flightProfileMetadataItem.apogee.toBigDecimal().setScale(1, RoundingMode.UP).toFloat()}m")
                            }
                            else {
                                Text("No flight data")
                                Text("")
                            }
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
        val targetCount = 5
        val rawInterval = flightProfileMetadata[flightProfileArchivePosition].apogee / targetCount
        val exponent = floor(log10(rawInterval)).toDouble()
        val fraction = rawInterval / 10.0.pow(exponent)
        val niceFraction = when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 5.0 -> 5.0
            else -> 10.0
        }
        val interval = niceFraction * 10.0.pow(exponent)
        val targetGrids = ceil(flightProfileMetadata[flightProfileArchivePosition].apogee / interval).toInt()

        val maxAgl = (interval * targetGrids).toFloat()
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
        var apogeeSample = 0
        flightProfileAglData.forEachIndexed { sampleID, aglSample ->
            if (aglSample.toInt() == (flightProfileMetadata[flightProfileArchivePosition].apogee * RocketViewModel.ALTIMETER_SCALE).toInt())
                if (apogeeSample == 0)
                    apogeeSample = sampleID + 9
        }
        var showRecovery by remember { mutableStateOf(true) }
        val maxChartWidth = flightProfileMetadata[flightProfileArchivePosition].timeToDrogue + (
                if (showRecovery && flightProfileAglData.size > apogeeSample)
                    (flightProfileAglData.size - apogeeSample).toFloat()
                else 0f)
        val displaySamples = if(showRecovery) flightProfileAglData.size else apogeeSample
        val gridColor = MaterialTheme.colorScheme.onPrimary
        val aglColor = MaterialTheme.colorScheme.primary
        val legendColor = MaterialTheme.colorScheme.secondaryContainer
        var displayAltitude by remember { mutableStateOf(true) }
        var displayAccelX by remember { mutableStateOf(true) }
        var displayAccelY by remember { mutableStateOf(true) }
        var displayAccelZ by remember { mutableStateOf(true) }
        Column (
            modifier = modifier
                .fillMaxHeight()
                .padding(16.dp)
        ) {
//            val scale = remember { mutableFloatStateOf(1f) }
//            val offset = remember { mutableStateOf(Offset.Zero) }
            Canvas (modifier = modifier
                .weight(11f)
                .fillMaxWidth()
//                .pointerInput(Unit) {
//                    detectTransformGestures { _, pan, zoom, _ ->
//                        if (scale.floatValue * zoom >= 1) {
//                            scale.floatValue *= zoom
//                            offset.value += pan
//                        } else {
//                            scale.floatValue = 1f
//                            offset.value = Offset.Zero
//                        }
//                    }
//                }
//                .graphicsLayer(
//                    scaleX = scale.floatValue,
//                    scaleY = scale.floatValue,
//                    translationX = offset.value.x,
//                    translationY = offset.value.y
//                )
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
                        val paint = TextPaint().apply {
                            color = legendColor.toArgb()
                            textSize = chartMarginY
                        }
                        val text = "$gridX"
                        val x = gridX * scaleFactorX + chartMarginX - paint.measureText(text) / 2
                        val y = canvasHeight + chartMarginY
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
                    if (displayAltitude && gridY != 0) {
                        drawIntoCanvas { canvas ->
                            val paint = TextPaint().apply {
                                color = legendColor.toArgb()
                                textSize = chartMarginY
                            }
                            val text = "$gridY"
                            val x = chartMarginX - paint.measureText(text) - 8
                            val y = canvasHeight - (gridY * scaleFactorY - chartMarginY / 2) - 8
                            canvas.nativeCanvas.drawText(text, x, y, paint)
                        }
                    }
                }
                // Draw altitude
                var lastX = chartMarginX
                var lastY = canvasHeight
                if (displayAltitude) {
                    flightProfileAglData.take(displaySamples).forEachIndexed { sampleID, aglSample ->
                        val newX = flightTime * scaleFactorX + chartMarginX
                        val newY = (maxAgl - aglSample.toFloat() / RocketViewModel.ALTIMETER_SCALE) * scaleFactorY
                        if (sampleID == flightEventData.launchDetectSampleIndex) {
                            drawIntoCanvas { canvas ->
                                val paint = TextPaint().apply {
                                    color = legendColor.toArgb()
                                    textSize = chartMarginY
                                }
                                val text = "Ch 1"
                                canvas.nativeCanvas.drawText(text, newX + 48, newY - 32, paint)
                            }
                            drawCircle(
                                color = Color(0xFF808080),
                                radius = 4.dp.toPx(),
                                center = Offset(newX + 128, newY - 40),
                                style = if (flightEventData.channel1PreFireContinuity) Fill else Stroke(width = 1f),
                            )
                            drawIntoCanvas { canvas ->
                                val paint = TextPaint().apply {
                                    color = legendColor.toArgb()
                                    textSize = chartMarginY
                                }
                                val text = "Ch 2"
                                canvas.nativeCanvas.drawText(text, newX + 48, newY, paint)
                            }
                            drawCircle(
                                color = Color(0xFF808080),
                                radius = 4.dp.toPx(),
                                center = Offset(newX + 128, newY - 8),
                                style = if (flightEventData.channel2PreFireContinuity) Fill else Stroke(width = 1f),
                            )
                        }
//                        if (sampleID == flightEventData.burnoutSampleIndex) {
//                            drawIntoCanvas { canvas ->
//                                val paint = TextPaint().apply {
//                                    color = legendColor.toArgb()
//                                    textSize = chartMarginY
//                                }
//                                val text = "Burnout"
//                                canvas.nativeCanvas.drawText(text, newX + 10, newY - 5, paint)
//                            }
//                            drawCircle(
//                                color = Color(0xFFFFFF00),
//                                radius = 2.dp.toPx(),
//                                center = Offset(newX, newY)
//                            )
//                        }
                        if (sampleID == flightEventData.maxAltitudeSampleIndex) {
                            drawIntoCanvas { canvas ->
                                val paint = TextPaint().apply {
                                    color = legendColor.toArgb()
                                    textSize = chartMarginY
                                }
                                val text = "${aglSample.toFloat() / RocketViewModel.ALTIMETER_SCALE}"
                                canvas.nativeCanvas.drawText(text, newX, newY, paint)
                            }
                            drawCircle(
                                color = Color(0xFFFF0000),
                                radius = 2.dp.toPx(),
                                center = Offset(newX, newY)
                            )
                        }
                        if (sampleID == flightEventData.droguePrimaryDeploySampleIndex) {
                            if (flightEventData.channel1Mode == DeployMode.DroguePrimary || flightEventData.channel2Mode == DeployMode.DroguePrimary) {
                                drawIntoCanvas { canvas ->
                                    val paint = TextPaint().apply {
                                        color = legendColor.toArgb()
                                        textSize = chartMarginY
                                    }
                                    val text = if (flightEventData.channel1Mode == DeployMode.DroguePrimary) "Drogue Primary Ch 1" else "Drogue Primary Ch 2"
                                    canvas.nativeCanvas.drawText(text, newX + 48, newY + 10, paint)
                                }
                                drawCircle(
                                    color = Color(0xFF808080),
                                    radius = 4.dp.toPx(),
                                    center = Offset(newX, newY),
                                    style = if (flightEventData.channel1Mode == DeployMode.DroguePrimary)
                                        if (flightEventData.channel1Fired) Fill else Stroke(width = 1f)
                                    else
                                        if (flightEventData.channel2Fired) Fill else Stroke(width = 1f),
                                )
                                drawCircle(
                                    color = Color(0xFF808080),
                                    radius = 4.dp.toPx(),
                                    center = Offset(newX + 24, newY),
                                    style = if (flightEventData.channel1Mode == DeployMode.DroguePrimary)
                                        if (flightEventData.channel1PostFireContinuity) Fill else Stroke(width = 1f)
                                    else
                                        if (flightEventData.channel2PostFireContinuity) Fill else Stroke(width = 1f),
                                )
                            }
                        }
                        if (sampleID == flightEventData.drogueBackupDeploySampleIndex) {
                            if (flightEventData.channel1Mode == DeployMode.DrogueBackup || flightEventData.channel2Mode == DeployMode.DrogueBackup) {
                                drawIntoCanvas { canvas ->
                                    val paint = TextPaint().apply {
                                        color = legendColor.toArgb()
                                        textSize = chartMarginY
                                    }
                                    val text = if (flightEventData.channel1Mode == DeployMode.DrogueBackup) "Drogue Backup Ch 1" else "Drogue Backup Ch 2"
                                    canvas.nativeCanvas.drawText(text, newX + 48, newY + 10, paint)
                                }
                                drawCircle(
                                    color = Color(0xFF808080),
                                    radius = 4.dp.toPx(),
                                    center = Offset(newX, newY),
                                    style = if (flightEventData.channel1Mode == DeployMode.DrogueBackup)
                                        if (flightEventData.channel1Fired) Fill else Stroke(width = 1f)
                                    else
                                        if (flightEventData.channel2Fired) Fill else Stroke(width = 1f),
                                )
                                drawCircle(
                                    color = Color(0xFF808080),
                                    radius = 4.dp.toPx(),
                                    center = Offset(newX + 24, newY),
                                    style = if (flightEventData.channel1Mode == DeployMode.DrogueBackup)
                                        if (flightEventData.channel1PostFireContinuity) Fill else Stroke(width = 1f)
                                    else
                                        if (flightEventData.channel2PostFireContinuity) Fill else Stroke(width = 1f),
                                )
                            }
                        }
                        if (sampleID == flightEventData.mainPrimaryDeploySampleIndex) {
                            if (flightEventData.channel1Mode == DeployMode.MainPrimary || flightEventData.channel2Mode == DeployMode.MainPrimary) {
                                drawIntoCanvas { canvas ->
                                    val paint = TextPaint().apply {
                                        color = legendColor.toArgb()
                                        textSize = chartMarginY
                                    }
                                    val text = if (flightEventData.channel1Mode == DeployMode.MainPrimary) "Main Primary Ch 1" else "Main Primary Ch 2"
                                    canvas.nativeCanvas.drawText(text, newX + 48, newY + 10, paint)
                                }
                                drawCircle(
                                    color = Color(0xFF808080),
                                    radius = 4.dp.toPx(),
                                    center = Offset(newX, newY),
                                    style = if (flightEventData.channel1Mode == DeployMode.MainPrimary)
                                        if (flightEventData.channel1Fired) Fill else Stroke(width = 1f)
                                    else
                                        if (flightEventData.channel2Fired) Fill else Stroke(width = 1f),
                                )
                                drawCircle(
                                    color = Color(0xFF808080),
                                    radius = 4.dp.toPx(),
                                    center = Offset(newX + 24, newY),
                                    style = if (flightEventData.channel1Mode == DeployMode.MainPrimary)
                                        if (flightEventData.channel1PostFireContinuity) Fill else Stroke(width = 1f)
                                    else
                                        if (flightEventData.channel2PostFireContinuity) Fill else Stroke(width = 1f),
                                )
                            }
                        }
                        if (sampleID == flightEventData.mainBackupDeploySampleIndex) {
                            if (flightEventData.channel1Mode == DeployMode.MainBackup || flightEventData.channel2Mode == DeployMode.MainBackup) {
                                drawIntoCanvas { canvas ->
                                    val paint = TextPaint().apply {
                                        color = legendColor.toArgb()
                                        textSize = chartMarginY
                                    }
                                    val text = if (flightEventData.channel1Mode == DeployMode.MainBackup) "Main Backup Ch 1" else "Main Backup Ch 2"
                                    canvas.nativeCanvas.drawText(text, newX + 48, newY + 10, paint)
                                }
                                drawCircle(
                                    color = Color(0xFF808080),
                                    radius = 4.dp.toPx(),
                                    center = Offset(newX, newY),
                                    style = if (flightEventData.channel1Mode == DeployMode.MainBackup)
                                        if (flightEventData.channel1Fired) Fill else Stroke(width = 1f)
                                    else
                                        if (flightEventData.channel2Fired) Fill else Stroke(width = 1f),
                                )
                                drawCircle(
                                    color = Color(0xFF808080),
                                    radius = 4.dp.toPx(),
                                    center = Offset(newX + 24, newY),
                                    style = if (flightEventData.channel1Mode == DeployMode.MainBackup)
                                        if (flightEventData.channel1PostFireContinuity) Fill else Stroke(width = 1f)
                                    else
                                        if (flightEventData.channel2PostFireContinuity) Fill else Stroke(width = 1f),
                                )
                            }
                        }
                        drawLine(
                            color = aglColor,
                            strokeWidth = 1.dp.toPx(),
                            start = Offset(lastX, lastY),
                            end = Offset(newX, newY)
                        )
                        lastX = newX
                        lastY = newY
                        flightTime += if (sampleID <= apogeeSample) 0.05f else 1.0f
                    }
                }
                if (maxAccelerometer != 0.toShort() || minAccelerometer != 0.toShort()) {
                    val scaleFactorAccY = canvasHeight / (maxAccelerometer - minAccelerometer)
                    // Draw accelerometer vertical axis
                    if (displayAccelX || displayAccelY || displayAccelZ) {
                        val accelerometerGridHeight = ((maxAccelerometer - minAccelerometer) / targetGrids).toInt()
                        for (gridY in maxAccelerometer downTo minAccelerometer step accelerometerGridHeight) {
                            drawIntoCanvas { canvas ->
                                val paint = TextPaint().apply {
                                    color = legendColor.toArgb()
                                    textSize = chartMarginY
                                }
                                val text = "${(gridY * 10 / RocketViewModel.ACCELEROMETER_SCALE).toFloat() / 10}"
                                val y = canvasHeight - ((gridY - minAccelerometer) * scaleFactorAccY - chartMarginY / 2) - 8
                                canvas.nativeCanvas.drawText(text, canvasWidth, y, paint)
                            }
                        }
                    }
                    // Draw accelerometer X axis stats
                    if (displayAccelX) {
                        flightTime = 0f
                        lastX = chartMarginX
                        lastY = canvasHeight
                        flightProfileAccelerometerData.forEachIndexed { sampleID, accelerometerSample ->
                            val newX = flightTime * scaleFactorX + chartMarginX
                            val newY = (maxAccelerometer - accelerometerSample.x).toFloat() * scaleFactorAccY
                            drawLine(
                                color = Color(0xffff0000),
                                strokeWidth = 1.dp.toPx(),
                                start = Offset(lastX, lastY),
                                end = Offset(newX, newY)
                            )
                            lastX = newX
                            lastY = newY
                            flightTime += 0.05f
                        }
                    }
                    // Draw accelerometer Y axis stats
                    if (displayAccelY) {
                        flightTime = 0f
                        lastX = chartMarginX
                        lastY = canvasHeight
                        flightProfileAccelerometerData.forEachIndexed { sampleID, accelerometerSample ->
                            val newX = flightTime * scaleFactorX + chartMarginX
                            val newY = (maxAccelerometer - accelerometerSample.y).toFloat() * scaleFactorAccY
                            drawLine(
                                color = Color(0xffffff00),
                                strokeWidth = 1.dp.toPx(),
                                start = Offset(lastX, lastY),
                                end = Offset(newX, newY)
                            )
                            lastX = newX
                            lastY = newY
                            flightTime += 0.05f
                        }
                    }
                    // Draw accelerometer Z axis stats
                    if (displayAccelZ) {
                        flightTime = 0f
                        lastX = chartMarginX
                        lastY = canvasHeight
                        flightProfileAccelerometerData.forEachIndexed { sampleID, accelerometerSample ->
                            val newX = flightTime * scaleFactorX + chartMarginX
                            val newY = (maxAccelerometer - accelerometerSample.z).toFloat() * scaleFactorAccY
                            drawLine(
                                color = Color(0xff00ff00),
                                strokeWidth = 1.dp.toPx(),
                                start = Offset(lastX, lastY),
                                end = Offset(newX, newY)
                            )
                            lastX = newX
                            lastY = newY
                            flightTime += 0.05f
                        }
                    }
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
            Spacer (modifier = modifier.weight(5f))
            Column (
                modifier = modifier
                    .fillMaxHeight()
                    .padding(16.dp)
                    .weight(4f),
                horizontalAlignment = Alignment.End
            ) {
                //Text(flightProfileDataMessageState.toString())
                //Text("Samples received: ${flightProfileAglData.size}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Descent")
                    Checkbox(
                        checked = showRecovery,
                        onCheckedChange = { showRecovery = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary, // Background when checked
                            uncheckedColor = MaterialTheme.colorScheme.primary, // Background when unchecked
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary // Checkmark color
                        )
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Altitude")
                    Checkbox(
                        checked = displayAltitude,
                        onCheckedChange = { displayAltitude = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary, // Background when checked
                            uncheckedColor = MaterialTheme.colorScheme.primary, // Background when unchecked
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary // Checkmark color
                        )
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(" Accel X",
                        color = Color(0xffff0000)
                    )
                    Checkbox(
                        checked = displayAccelX,
                        onCheckedChange = { displayAccelX = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary, // Background when checked
                            uncheckedColor = MaterialTheme.colorScheme.primary, // Background when unchecked
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary // Checkmark color
                        )
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(" Accel Y",
                        color = Color(0xffffff00)
                    )
                    Checkbox(
                        checked = displayAccelY,
                        onCheckedChange = { displayAccelY = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary, // Background when checked
                            uncheckedColor = MaterialTheme.colorScheme.primary, // Background when unchecked
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary // Checkmark color
                        )
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(" Accel Z",
                        color = Color(0xff00ff00)
                    )
                    Checkbox(
                        checked = displayAccelZ,
                        onCheckedChange = { displayAccelZ = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary, // Background when checked
                            uncheckedColor = MaterialTheme.colorScheme.primary, // Background when unchecked
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary // Checkmark color
                        )
                    )
                }
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