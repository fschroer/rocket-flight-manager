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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.BluetoothService
//import com.hoho.android.usbserial.driver.UsbSerialPort
//import com.hoho.android.usbserial.driver.UsbSerialProber
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.FlightEventData
import com.steampigeon.flightmanager.data.LocatorMessageState
import java.math.RoundingMode
import java.text.DateFormat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

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
    val flightProfileDataDisplayState = viewModel.flightProfileDataDisplayState.collectAsState().value
    val flightEventData = viewModel.flightEventData.collectAsState().value
    val flightProfileAglData = viewModel.flightProfileAglData.collectAsState().value
    val flightProfileAccelerometerData = viewModel.flightProfileAccelerometerData.collectAsState().value
    val flightProfileArchivePosition = viewModel.flightProfileArchivePosition.collectAsState().value
    val locatorConfig by viewModel.remoteLocatorConfig.collectAsState()
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

    LaunchedEffect(Unit) {
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
        if (flightProfileDataDisplayState) {
            viewModel.clearFlightProfileData()
            viewModel.updateFlightProfileDataMessageState(LocatorMessageState.Idle)
            viewModel.updateFlightProfileDataDisplayState(false)
        }
        else
            onCancelButtonClicked()
    }
    if (!flightProfileDataDisplayState) { // To do: Add check for existence of flight data.
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
                if (flightProfileMetadataMessageState != LocatorMessageState.AckUpdated)
                    Text("Fetching flight data from locator ${locatorConfig.deviceName}")
                else if (flightProfileMetadata.isEmpty())
                    Text("No flights recorded on locator ${locatorConfig.deviceName}")
                else
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
                                            if (service != null) {
                                                viewModel.getFlightProfileData(service)
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
        var maxAccelerometer: Float = 0f
        var minAccelerometer: Float = 0f
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
        val chartBodyColor = MaterialTheme.colorScheme.secondaryContainer
        val drogueColor = Color(0x80808000)
        val mainColor = Color(0x80008000)
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
                val chartAxisTextSize = 32f
                val chartBodyTextSize = 24f
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
                            textSize = chartAxisTextSize
                        }
                        val text = "$gridX"
                        val x = gridX * scaleFactorX + chartMarginX - paint.measureText(text) / 2
                        val y = canvasHeight + chartAxisTextSize
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
                                textSize = chartAxisTextSize
                            }
                            val text = "$gridY"
                            val x = chartMarginX - paint.measureText(text) - 8
                            val y = canvasHeight - (gridY * scaleFactorY - chartAxisTextSize / 2) - 8
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
                        if (sampleID == flightEventData.burnoutSampleIndex) {
                            chartEvent(chartBodyColor,
                                chartBodyTextSize,
                                "Burnout: ${aglSample.toFloat() / RocketViewModel.ALTIMETER_SCALE}",
                                flightEventData.burnoutAltitude - flightEventData.launchDetectAltitude,
                                maxAgl,
                                newX,
                                newY,
                                canvasWidth
                            )
                        }
                        if (sampleID == flightEventData.maxAltitudeSampleIndex) {
                            drawIntoCanvas { canvas ->
                                val paint = TextPaint().apply {
                                    color = chartBodyColor.toArgb()
                                    textSize = chartBodyTextSize
                                }
                                val text = "${aglSample.toFloat() / RocketViewModel.ALTIMETER_SCALE}"
                                canvas.nativeCanvas.drawText(text, newX - 24, newY - 16, paint)
                            }
                            drawCircle(
                                color = Color(0xFF0000FF),
                                radius = 4.dp.toPx(),
                                center = Offset(newX, newY)
                            )
                        }
                        if (sampleID == flightEventData.droguePrimaryDeploySampleIndex) {
                            if (flightEventData.channel1Mode == DeployMode.DroguePrimary || flightEventData.channel2Mode == DeployMode.DroguePrimary) {
                                chartDeploymentEvent(chartBodyColor,
                                    chartBodyTextSize,
                                    if (flightEventData.channel1Mode == DeployMode.DroguePrimary) "Ch 1 Drogue Primary Event" else "Ch 2 Drogue Primary Event",
                                    flightEventData,
                                    DeployMode.DroguePrimary,
                                    newX,
                                    newY,
                                    canvasWidth
                                )
                            }
                        }
                        if (sampleID == flightEventData.drogueBackupDeploySampleIndex) {
                            if (flightEventData.channel1Mode == DeployMode.DrogueBackup || flightEventData.channel2Mode == DeployMode.DrogueBackup) {
                                chartDeploymentEvent(chartBodyColor,
                                    chartBodyTextSize,
                                    if (flightEventData.channel1Mode == DeployMode.DrogueBackup) "Ch 1 Drogue Backup Event" else "Ch 2 Drogue Backup Event",
                                    flightEventData,
                                    DeployMode.DrogueBackup,
                                    newX,
                                    newY,
                                    canvasWidth
                                )
                            }
                        }
                        if (sampleID == flightEventData.mainPrimaryDeploySampleIndex) {
                            if (flightEventData.channel1Mode == DeployMode.MainPrimary || flightEventData.channel2Mode == DeployMode.MainPrimary) {
                                chartDeploymentEvent(chartBodyColor,
                                    chartBodyTextSize,
                                    if (flightEventData.channel1Mode == DeployMode.MainPrimary) "Ch 1 Main Primary Event" else "Ch 2 Main Primary Event",
                                    flightEventData,
                                    DeployMode.MainPrimary,
                                    newX,
                                    newY,
                                    canvasWidth
                                )
                            }
                        }
                        if (sampleID == flightEventData.mainBackupDeploySampleIndex) {
                            if (flightEventData.channel1Mode == DeployMode.MainBackup || flightEventData.channel2Mode == DeployMode.MainBackup) {
                                chartDeploymentEvent(chartBodyColor,
                                    chartBodyTextSize,
                                    if (flightEventData.channel1Mode == DeployMode.MainBackup) "Ch 1 Main Backup Event" else "Ch 2 Main Backup Event",
                                    flightEventData,
                                    DeployMode.MainBackup,
                                    newX,
                                    newY,
                                    canvasWidth
                                )
                            }
                        }
                        if (sampleID == flightEventData.drogueVelocityThresholdSampleIndex) {
                            chartEvent(drogueColor,
                                chartBodyTextSize,
                                "Drogue Deploy",
                                flightEventData.droguePrimaryDeployAltitude - flightEventData.drogueVelocityThresholdAltitude,
                                maxAgl,
                                newX,
                                newY,
                                canvasWidth
                            )
                        }
                        if (sampleID == flightEventData.mainVelocityThresholdSampleIndex) {
                            chartEvent(mainColor,
                                chartBodyTextSize,
                                "Main Deploy",
                                flightEventData.mainPrimaryDeployAltitude - flightEventData.mainVelocityThresholdAltitude,
                                maxAgl,
                                newX,
                                newY,
                                canvasWidth
                            )
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
                if (maxAccelerometer != 0f || minAccelerometer != 0f) {
                    val scaleFactorAccY = canvasHeight / (maxAccelerometer - minAccelerometer)
                    // Draw accelerometer vertical axis
                    if (displayAccelX || displayAccelY || displayAccelZ) {
                        val accelerometerGridHeight = ((maxAccelerometer - minAccelerometer) / targetGrids)
                        floatDownTo(maxAccelerometer, minAccelerometer, accelerometerGridHeight) { gridY ->
//                        for (gridY in maxAccelerometer downTo minAccelerometer step accelerometerGridHeight) {
                            drawIntoCanvas { canvas ->
                                val paint = TextPaint().apply {
                                    color = legendColor.toArgb()
                                    textSize = chartAxisTextSize
                                }
                                val text = "${(gridY * 10 / RocketViewModel.ACCELEROMETER_SCALE).toFloat() / 10}"
                                val y = canvasHeight - ((gridY - minAccelerometer) * scaleFactorAccY - chartAxisTextSize / 2) - 8
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
                        viewModel.clearFlightProfileData()
                        viewModel.updateFlightProfileDataMessageState(LocatorMessageState.Idle)
                        viewModel.updateFlightProfileDataDisplayState(false)
                    }
                ) {
                    Text(stringResource(R.string.return_to_main))
                }
            }
        }
        Column() {
            Spacer(modifier = modifier.weight(2f))
            Row(
            ) {
                Spacer(modifier = modifier.weight(2f))
                Column(
                    modifier = modifier
                        .wrapContentHeight()
                        .weight(4f),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    val textSize = 12.sp
                    val textPadding = 4.dp
                    val checkBoxSize = 28.dp
                    //Text(flightProfileDataMessageState.toString())
                    //Text("Samples received: ${flightProfileAglData.size}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Descent",
                            fontSize = textSize,
                            modifier = modifier.padding(start = textPadding)
                        )
                        Checkbox(
                            checked = showRecovery,
                            onCheckedChange = { showRecovery = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary, // Background when checked
                                uncheckedColor = MaterialTheme.colorScheme.primary, // Background when unchecked
                                checkmarkColor = MaterialTheme.colorScheme.onPrimary // Checkmark color
                            ),
                            modifier = Modifier.size(checkBoxSize) // smaller box
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Altitude",
                            fontSize = textSize,
                            modifier = modifier.padding(start = textPadding)
                        )
                        Checkbox(
                            checked = displayAltitude,
                            onCheckedChange = { displayAltitude = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary, // Background when checked
                                uncheckedColor = MaterialTheme.colorScheme.primary, // Background when unchecked
                                checkmarkColor = MaterialTheme.colorScheme.onPrimary // Checkmark color
                            ),
                            modifier = Modifier.size(checkBoxSize) // smaller box

                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            " Accel X",
                            color = Color(0xffff0000),
                            fontSize = textSize,
                            modifier = modifier.padding(start = textPadding)
                        )
                        Checkbox(
                            checked = displayAccelX,
                            onCheckedChange = { displayAccelX = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary, // Background when checked
                                uncheckedColor = MaterialTheme.colorScheme.primary, // Background when unchecked
                                checkmarkColor = MaterialTheme.colorScheme.onPrimary // Checkmark color
                            ),
                            modifier = Modifier.size(checkBoxSize) // smaller box

                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            " Accel Y",
                            color = Color(0xffffff00),
                            fontSize = textSize,
                            modifier = modifier.padding(start = textPadding)
                        )
                        Checkbox(
                            checked = displayAccelY,
                            onCheckedChange = { displayAccelY = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary, // Background when checked
                                uncheckedColor = MaterialTheme.colorScheme.primary, // Background when unchecked
                                checkmarkColor = MaterialTheme.colorScheme.onPrimary // Checkmark color
                            ),
                            modifier = Modifier.size(checkBoxSize) // smaller box

                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            " Accel Z",
                            color = Color(0xff00ff00),
                            fontSize = textSize,
                            modifier = modifier.padding(start = textPadding)
                        )
                        Checkbox(
                            checked = displayAccelZ,
                            onCheckedChange = { displayAccelZ = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary, // Background when checked
                                uncheckedColor = MaterialTheme.colorScheme.primary, // Background when unchecked
                                checkmarkColor = MaterialTheme.colorScheme.onPrimary // Checkmark color
                            ),
                            modifier = Modifier.size(checkBoxSize) // smaller box

                        )
                    }
                }
                Spacer(modifier = modifier.weight(1f))
            }
            Spacer(modifier = modifier.weight(3f))
        }
    }
}

fun DrawScope.chartDeploymentEvent(chartTextColor: Color, chartTextSize: Float, chartText: String, flightEventData: FlightEventData, deployMode: DeployMode, x: Float, y: Float, canvasWidth: Float)
{
    drawIntoCanvas { canvas ->
        val paint = TextPaint().apply {
            color = chartTextColor.toArgb()
            textSize = chartTextSize
        }
        val xOffset = if (x + paint.measureText(chartText) < canvasWidth) 0
        else -paint.measureText(chartText).toInt() - 48
        canvas.nativeCanvas.drawText(chartText, x + 48 + xOffset, y + 6, paint)
    }
    drawCircle(
        color = Color(0xFF808080),
        radius = 4.dp.toPx(),
        center = Offset(x + 8, y),
        style = if (flightEventData.channel1Mode == deployMode)
            if (flightEventData.channel1PreFireContinuity) Fill else Stroke(width = 1f)
        else
            if (flightEventData.channel2PreFireContinuity) Fill else Stroke(width = 1f),
    )
    drawCircle(
        color = Color(0xFF808080),
        radius = 4.dp.toPx(),
        center = Offset(x + 24, y),
        style = if (flightEventData.channel1Mode == deployMode)
            if (flightEventData.channel1Fired) Fill else Stroke(width = 1f)
        else
            if (flightEventData.channel2Fired) Fill else Stroke(width = 1f),
    )
    drawCircle(
        color = Color(0xFF808080),
        radius = 4.dp.toPx(),
        center = Offset(x + 40, y),
        style = if (flightEventData.channel1Mode == deployMode)
            if (flightEventData.channel1PostFireContinuity) Fill else Stroke(width = 1f)
        else
            if (flightEventData.channel2PostFireContinuity) Fill else Stroke(width = 1f),
    )
}

fun DrawScope.chartEvent(chartColor: Color, chartTextSize: Float, chartText: String, delta: Float, maxAgl: Float, x: Float, y: Float, canvasWidth: Float)
{
    val yOffset = if (delta < maxAgl / 20) 24 else 0
    drawIntoCanvas { canvas ->
        val paint = TextPaint().apply {
            color = chartColor.toArgb()
            textSize = chartTextSize
        }
        val xOffset = if (x + paint.measureText(chartText) < canvasWidth) 0
            else -paint.measureText(chartText).toInt() - 32
        canvas.nativeCanvas.drawText(chartText, x + 32 + xOffset, y + 6 + yOffset, paint)
    }
    drawCircle(
        color = chartColor,
        radius = 4.dp.toPx(),
        center = Offset(x, y)
    )
}

fun floatDownTo(start: Float, end: Float, step: Float, block: (Float) -> Unit) {
    var v = start
    while (v >= end) {
        block(v)
        v -= step
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