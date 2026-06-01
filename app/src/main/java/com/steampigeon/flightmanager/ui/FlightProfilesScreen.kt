package com.steampigeon.flightmanager.ui

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Divider
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
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.FlightDataRepository
import com.steampigeon.flightmanager.data.FlightEventData
import com.steampigeon.flightmanager.data.FlightSample
import com.steampigeon.flightmanager.data.LocatorMessageState
import java.math.RoundingMode
import java.text.DateFormat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

private const val TAG = "FlightProfiles"

// ============================================================================
//  Charting safety limits
//
//  Any value outside these bounds is treated as corrupt / out-of-range and
//  the sample is excluded from the chart rather than crashing or producing an
//  unintelligible draw call.
// ============================================================================

private const val MAX_SANE_ALTITUDE_M    = 30_000f   // ~100 kft — well above any amateur rocket
private const val MIN_SANE_ALTITUDE_M    = -500f     // allow slight below-pad readings
private const val MAX_SANE_ACCEL_MPS2    = 3_000f    // ~300 g — covers extreme high-power motors
private const val MIN_SANE_ACCEL_MPS2    = -3_000f
private const val MAX_SANE_TIMESTAMP_MS  = 600_000L  // 10 minutes — more than any flight
private const val MIN_SAMPLES_TO_CHART   = 2         // need at least two points to draw a line

/**
 * Validate a single FlightSample before using it in chart calculations.
 * Returns false if any field is NaN, infinite, or outside sane flight bounds.
 */
private fun FlightSample.isSane(): Boolean {
    if (altitudeM.isNaN() || altitudeM.isInfinite()) return false
    if (altitudeM < MIN_SANE_ALTITUDE_M || altitudeM > MAX_SANE_ALTITUDE_M) return false
    if (accel.x.isNaN() || accel.x.isInfinite()) return false
    if (accel.y.isNaN() || accel.y.isInfinite()) return false
    if (accel.z.isNaN() || accel.z.isInfinite()) return false
    if (accel.x < MIN_SANE_ACCEL_MPS2 || accel.x > MAX_SANE_ACCEL_MPS2) return false
    if (accel.y < MIN_SANE_ACCEL_MPS2 || accel.y > MAX_SANE_ACCEL_MPS2) return false
    if (accel.z < MIN_SANE_ACCEL_MPS2 || accel.z > MAX_SANE_ACCEL_MPS2) return false
    if (timestampMs < 0L || timestampMs > MAX_SANE_TIMESTAMP_MS) return false
    return true
}

/**
 * Clamp a canvas Y coordinate so it never escapes the drawable area.
 * Prevents drawLine from receiving out-of-bounds values when a data spike
 * would otherwise project off-screen.
 */
private fun Float.clampY(canvasHeight: Float) = coerceIn(0f, canvasHeight)

/**
 * Clamp a canvas X coordinate to the drawable area.
 */
private fun Float.clampX(chartMarginX: Float, canvasWidth: Float) =
    coerceIn(chartMarginX, canvasWidth + chartMarginX)

// ============================================================================
//  Screen
// ============================================================================

@Composable
fun FlightProfilesScreen(
    viewModel: RocketViewModel = viewModel(),
    service: BluetoothService?,
    onCancelButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val flightProfileMetadataMessageState by viewModel.flightProfileMetadataMessageState.collectAsState()
    val flightProfileMetadata             by viewModel.flightProfileMetadata.collectAsState()
    val flightProfileDataMessageState     by viewModel.flightProfileDataMessageState.collectAsState()
    val flightProfileDataDisplayState     by viewModel.flightProfileDataDisplayState.collectAsState()
    val flightEventData                   by viewModel.flightEventData.collectAsState()
    val flightProfileArchivePosition      by viewModel.flightProfileArchivePosition.collectAsState()
    val locatorConfig                     by viewModel.remoteLocatorConfig.collectAsState()

    // Observe samples and progress directly from the repository so the chart
    // reacts to partial data arriving during a live transfer.
    val samples   by FlightDataRepository.samples.collectAsState()
    val progress  by FlightDataRepository.progress.collectAsState()

    // Filter to sane samples only — done once here so every downstream
    // calculation works from a validated list.
    val saneSamples = remember(samples) { samples.filter { it.isSane() } }

    LaunchedEffect(Unit) {
        if (flightProfileMetadataMessageState == LocatorMessageState.Idle) {
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
        } else {
            onCancelButtonClicked()
        }
    }

    if (!flightProfileDataDisplayState) {
        // ── Flight record list ────────────────────────────────────────────
        Column(
            modifier = modifier
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            Column(
                modifier = modifier.weight(11f),
                verticalArrangement = Arrangement.SpaceAround
            ) {
                when {
                    flightProfileMetadataMessageState != LocatorMessageState.AckUpdated ->
                        Text("Fetching flight data from locator ${locatorConfig.deviceName}")
                    flightProfileMetadata.isEmpty() ->
                        Text("No flights recorded on locator ${locatorConfig.deviceName}")
                    else ->
                        flightProfileMetadata.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.u_turn_right),
                                    contentDescription = stringResource(R.string.flight_profiles)
                                )
                                Text("${item.position}")
                                Spacer(modifier = modifier.weight(1f))
                                Column(
                                    modifier = Modifier
                                        .weight(5f)
                                        .clickable {
                                            if (item.apogee > 0) {
                                                viewModel.updateFlightProfileArchivePosition(item.position)
                                                if (service != null)
                                                    viewModel.getFlightProfileData(service)
                                            }
                                        },
                                ) {
                                    if (item.apogee > 0) {
                                        item.date?.format(
                                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                        )?.let { Text(it) }
                                        Text(
                                            "Apogee: ${
                                                item.apogee.toBigDecimal()
                                                    .setScale(1, RoundingMode.UP).toFloat()
                                            }m"
                                        )
                                    } else {
                                        Text("No flight data")
                                        Text("")
                                    }
                                }
                            }
                            Divider(modifier = modifier.weight(1f))
                        }
                }
                Spacer(modifier = modifier.weight(1f))
            }
            Row(
                modifier = modifier,
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
    } else {
        // ── Chart view ───────────────────────────────────────────────────

        // Safety: guard against stale / invalid archive position
        val metadataIndex = flightProfileMetadata.indexOfFirst { it.position == flightProfileArchivePosition }
        if (metadataIndex < 0 || flightProfileMetadata.isEmpty()) {
            // Metadata not ready — show a loading message and bail out of the
            // chart branch entirely rather than indexing with -1.
            Column(
                modifier = modifier.fillMaxHeight().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Loading flight data…")
                if (progress.packetCount > 0) {
                    Text("${progress.receivedCount} / ${progress.packetCount} packets received")
                }
            }
            return
        }
        val currentMetadata = flightProfileMetadata[metadataIndex]

        // ── Y-axis (altitude) range ───────────────────────────────────────
        // Prefer the metadata apogee as the ceiling; fall back to the maximum
        // observed sample altitude if metadata is missing or suspiciously small.
        val observedMaxAlt = saneSamples.maxOfOrNull { it.altitudeM } ?: 0f
        val rawApogee = max(currentMetadata.apogee, observedMaxAlt).takeIf { it > 0f } ?: 1f

        val targetGridCount = 5
        val rawInterval = rawApogee / targetGridCount
        val exponent    = floor(log10(rawInterval.toDouble()))
        val fraction    = rawInterval / 10.0.pow(exponent)
        val niceFraction = when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 5.0 -> 5.0
            else            -> 10.0
        }
        val interval   = (niceFraction * 10.0.pow(exponent)).toFloat()
        // Safety: interval must be positive and finite
        val safeInterval = if (interval.isFinite() && interval > 0f) interval else rawApogee / targetGridCount
        val targetGrids  = ceil(rawApogee / safeInterval).toInt().coerceAtLeast(1)
        val maxAgl       = (safeInterval * targetGrids).coerceAtLeast(1f)
        val gridHeight   = (maxAgl / targetGrids).coerceAtLeast(1f)

        // ── X-axis (time) range ───────────────────────────────────────────
        // Derive the X range from actual sample timestamps rather than relying
        // on the metadata timeToDrogue field, which uses the old protocol's
        // timing model (hardcoded 0.05s/sample ascending, 1.0s/sample descending).
        // With the new codec, timestampMs is the authoritative time source.
        val firstTimestampMs = saneSamples.firstOrNull()?.timestampMs ?: 0L
        val lastTimestampMs  = saneSamples.lastOrNull()?.timestampMs  ?: 0L
        val totalFlightMs    = (lastTimestampMs - firstTimestampMs).coerceAtLeast(1L)

        // Find the apogee sample index by the highest altitude reading
        val apogeeSampleIndex = saneSamples.indices.maxByOrNull { saneSamples[it].altitudeM } ?: 0
        val apogeeTimestampMs = saneSamples.getOrNull(apogeeSampleIndex)?.timestampMs ?: firstTimestampMs

        var showRecovery by remember { mutableStateOf(true) }

        val displaySampleCount = if (showRecovery) saneSamples.size else (apogeeSampleIndex + 1)
        val displaySamples     = saneSamples.take(displaySampleCount)

        val maxChartMs = if (showRecovery) totalFlightMs else (apogeeTimestampMs - firstTimestampMs)
        // Safety: chart width must be positive
        val safeChartMs = maxChartMs.coerceAtLeast(1L).toFloat()

        // ── Accelerometer range ───────────────────────────────────────────
        val accelValues = displaySamples.flatMap {
            listOf(it.accel.x, it.accel.y, it.accel.z)
        }.filter { it.isFinite() }

        val rawMaxAccel = accelValues.maxOrNull() ?: 0f
        val rawMinAccel = accelValues.minOrNull() ?: 0f
        // Safety: prevent zero-range accelerometer scale
        val accelRange  = (rawMaxAccel - rawMinAccel).let { if (it < 0.001f) 1f else it }
        val maxAccel    = rawMaxAccel
        val minAccel    = rawMinAccel

        // ── Colours ───────────────────────────────────────────────────────
        val gridColor        = MaterialTheme.colorScheme.onPrimary
        val aglColor         = MaterialTheme.colorScheme.primary
        val legendColor      = MaterialTheme.colorScheme.secondaryContainer
        val chartBodyColor   = MaterialTheme.colorScheme.secondaryContainer
        val drogueColor      = Color(0x80808000)
        val mainColor        = Color(0x80008000)

        var displayAltitude by remember { mutableStateOf(true) }
        var displayAccelX   by remember { mutableStateOf(true) }
        var displayAccelY   by remember { mutableStateOf(true) }
        var displayAccelZ   by remember { mutableStateOf(true) }

        Column(
            modifier = modifier
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            // Transfer progress indicator — visible until transfer is complete
            if (!progress.complete && progress.packetCount > 0) {
                Text(
                    text = "Receiving: ${progress.receivedCount} / ${progress.packetCount} packets",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Canvas(
                modifier = modifier
                    .weight(11f)
                    .fillMaxWidth()
            ) {
                val chartMarginX     = 64f
                val chartMarginY     = 32f
                val chartAxisTextSize = 32f
                val chartBodyTextSize = 24f
                val canvasHeight     = size.height - chartMarginY
                val canvasWidth      = size.width - chartMarginX

                // Safety: if canvas is too small to draw in, bail early
                if (canvasHeight <= 0f || canvasWidth <= 0f) return@Canvas
                if (displaySamples.size < MIN_SAMPLES_TO_CHART) return@Canvas

                val scaleFactorX = canvasWidth  / safeChartMs
                val scaleFactorY = canvasHeight / maxAgl

                // ── Vertical grid lines (time axis) ───────────────────────
                val xGridIntervalMs = (safeChartMs / targetGridCount).coerceAtLeast(1f)
                var gridTimeMs = 0f
                while (gridTimeMs <= safeChartMs + xGridIntervalMs / 2f) {
                    val gx = (gridTimeMs * scaleFactorX + chartMarginX)
                        .clampX(chartMarginX, canvasWidth)
                    drawLine(
                        color = gridColor,
                        strokeWidth = 1.dp.toPx(),
                        start = Offset(gx, 0f),
                        end   = Offset(gx, canvasHeight)
                    )
                    drawIntoCanvas { canvas ->
                        val paint = TextPaint().apply {
                            color    = legendColor.toArgb()
                            textSize = chartAxisTextSize
                        }
                        // Show time in seconds, one decimal place
                        val label = "${(gridTimeMs / 1000f).toBigDecimal().setScale(1, RoundingMode.HALF_UP)}s"
                        val tx = gx - paint.measureText(label) / 2f
                        val ty = canvasHeight + chartAxisTextSize
                        canvas.nativeCanvas.drawText(label, tx, ty, paint)
                    }
                    gridTimeMs += xGridIntervalMs
                }

                // ── Horizontal grid lines (altitude axis) ─────────────────
                var gridAlt = 0f
                while (gridAlt <= maxAgl + gridHeight / 2f) {
                    val gy = (canvasHeight - gridAlt * scaleFactorY).clampY(canvasHeight)
                    drawLine(
                        color = gridColor,
                        strokeWidth = 1.dp.toPx(),
                        start = Offset(chartMarginX, gy),
                        end   = Offset(canvasWidth + chartMarginX, gy)
                    )
                    if (displayAltitude && gridAlt > 0f) {
                        drawIntoCanvas { canvas ->
                            val paint = TextPaint().apply {
                                color    = legendColor.toArgb()
                                textSize = chartAxisTextSize
                            }
                            val label = "${gridAlt.toInt()}m"
                            val tx = chartMarginX - paint.measureText(label) - 8f
                            val ty = gy + chartAxisTextSize / 2f
                            canvas.nativeCanvas.drawText(label, tx, ty, paint)
                        }
                    }
                    gridAlt += gridHeight
                }

                // ── Altitude trace ────────────────────────────────────────
                if (displayAltitude) {
                    var lastX = chartMarginX
                    var lastY = canvasHeight
                    var firstPoint = true

                    displaySamples.forEachIndexed { idx, sample ->
                        val tMs  = (sample.timestampMs - firstTimestampMs).toFloat()
                        val newX = (tMs * scaleFactorX + chartMarginX)
                            .clampX(chartMarginX, canvasWidth)
                        val newY = (canvasHeight - sample.altitudeM * scaleFactorY)
                            .clampY(canvasHeight)

                        // ── Event markers ─────────────────────────────────
                        if (idx == apogeeSampleIndex) {
                            drawIntoCanvas { canvas ->
                                val paint = TextPaint().apply {
                                    color    = chartBodyColor.toArgb()
                                    textSize = chartBodyTextSize
                                }
                                val label = "${sample.altitudeM.toBigDecimal().setScale(1, RoundingMode.HALF_UP)}m"
                                canvas.nativeCanvas.drawText(label, newX - 24f, newY - 16f, paint)
                            }
                            drawCircle(
                                color  = Color(0xFF0000FF),
                                radius = 4.dp.toPx(),
                                center = Offset(newX, newY)
                            )
                        }
                        if (idx == flightEventData.burnoutSampleIndex) {
                            chartEvent(
                                chartBodyColor, chartBodyTextSize,
                                "Burnout: ${sample.altitudeM.toBigDecimal().setScale(1, RoundingMode.HALF_UP)}m",
                                flightEventData.burnoutAltitude - flightEventData.launchDetectAltitude,
                                maxAgl, newX, newY, canvasWidth
                            )
                        }
                        if (idx == flightEventData.droguePrimaryDeploySampleIndex &&
                            (flightEventData.channel1Mode == DeployMode.DroguePrimary ||
                                    flightEventData.channel2Mode == DeployMode.DroguePrimary)
                        ) {
                            chartDeploymentEvent(
                                chartBodyColor, chartBodyTextSize,
                                if (flightEventData.channel1Mode == DeployMode.DroguePrimary)
                                    "Ch 1 Drogue Primary Event" else "Ch 2 Drogue Primary Event",
                                flightEventData, DeployMode.DroguePrimary, newX, newY, canvasWidth
                            )
                        }
                        if (idx == flightEventData.drogueBackupDeploySampleIndex &&
                            (flightEventData.channel1Mode == DeployMode.DrogueBackup ||
                                    flightEventData.channel2Mode == DeployMode.DrogueBackup)
                        ) {
                            chartDeploymentEvent(
                                chartBodyColor, chartBodyTextSize,
                                if (flightEventData.channel1Mode == DeployMode.DrogueBackup)
                                    "Ch 1 Drogue Backup Event" else "Ch 2 Drogue Backup Event",
                                flightEventData, DeployMode.DrogueBackup, newX, newY, canvasWidth
                            )
                        }
                        if (idx == flightEventData.mainPrimaryDeploySampleIndex &&
                            (flightEventData.channel1Mode == DeployMode.MainPrimary ||
                                    flightEventData.channel2Mode == DeployMode.MainPrimary)
                        ) {
                            chartDeploymentEvent(
                                chartBodyColor, chartBodyTextSize,
                                if (flightEventData.channel1Mode == DeployMode.MainPrimary)
                                    "Ch 1 Main Primary Event" else "Ch 2 Main Primary Event",
                                flightEventData, DeployMode.MainPrimary, newX, newY, canvasWidth
                            )
                        }
                        if (idx == flightEventData.mainBackupDeploySampleIndex &&
                            (flightEventData.channel1Mode == DeployMode.MainBackup ||
                                    flightEventData.channel2Mode == DeployMode.MainBackup)
                        ) {
                            chartDeploymentEvent(
                                chartBodyColor, chartBodyTextSize,
                                if (flightEventData.channel1Mode == DeployMode.MainBackup)
                                    "Ch 1 Main Backup Event" else "Ch 2 Main Backup Event",
                                flightEventData, DeployMode.MainBackup, newX, newY, canvasWidth
                            )
                        }
                        if (idx == flightEventData.drogueVelocityThresholdSampleIndex) {
                            chartEvent(
                                drogueColor, chartBodyTextSize, "Drogue Deploy",
                                flightEventData.droguePrimaryDeployAltitude - flightEventData.drogueVelocityThresholdAltitude,
                                maxAgl, newX, newY, canvasWidth
                            )
                        }
                        if (idx == flightEventData.mainVelocityThresholdSampleIndex) {
                            chartEvent(
                                mainColor, chartBodyTextSize, "Main Deploy",
                                flightEventData.mainPrimaryDeployAltitude - flightEventData.mainVelocityThresholdAltitude,
                                maxAgl, newX, newY, canvasWidth
                            )
                        }

                        // ── Line segment ──────────────────────────────────
                        if (!firstPoint) {
                            drawLine(
                                color       = aglColor,
                                strokeWidth = 1.dp.toPx(),
                                start       = Offset(lastX, lastY),
                                end         = Offset(newX,  newY)
                            )
                        }
                        lastX      = newX
                        lastY      = newY
                        firstPoint = false
                    }
                }

                // ── Accelerometer traces ──────────────────────────────────
                // Only draw if there is meaningful range in the data
                if (accelRange > 0.001f && (displayAccelX || displayAccelY || displayAccelZ)) {
                    val scaleFactorAccY = canvasHeight / accelRange

                    // Right-hand accelerometer axis labels
                    val accelGridStep = (accelRange / targetGridCount).coerceAtLeast(0.001f)
                    var accelGrid = minAccel
                    while (accelGrid <= maxAccel + accelGridStep / 2f) {
                        val gy = (canvasHeight - (accelGrid - minAccel) * scaleFactorAccY)
                            .clampY(canvasHeight)
                        drawIntoCanvas { canvas ->
                            val paint = TextPaint().apply {
                                color    = legendColor.toArgb()
                                textSize = chartAxisTextSize
                            }
                            // Display in g (divide by 9.80665)
                            val gVal = accelGrid / RocketViewModel.G_FORCE_MS2.toFloat()
                            val label = "${gVal.toBigDecimal().setScale(1, RoundingMode.HALF_UP)}g"
                            canvas.nativeCanvas.drawText(label, canvasWidth + 4f, gy + chartAxisTextSize / 2f, paint)
                        }
                        accelGrid += accelGridStep
                    }

                    fun drawAccelTrace(color: Color, getValue: (FlightSample) -> Float) {
                        var lastX = chartMarginX
                        var lastY = canvasHeight - ((getValue(displaySamples.first()) - minAccel) * scaleFactorAccY)
                        var firstPoint = true
                        displaySamples.forEach { sample ->
                            val v = getValue(sample)
                            // Skip any remaining insane individual values that slipped
                            // through the per-sample isSane() check (e.g. a single axis spike)
                            if (!v.isFinite() || v < MIN_SANE_ACCEL_MPS2 || v > MAX_SANE_ACCEL_MPS2) {
                                firstPoint = true  // lift the pen
                                return@forEach
                            }
                            val tMs  = (sample.timestampMs - firstTimestampMs).toFloat()
                            val newX = (tMs * scaleFactorX + chartMarginX)
                                .clampX(chartMarginX, canvasWidth)
                            val newY = (canvasHeight - (v - minAccel) * scaleFactorAccY)
                                .clampY(canvasHeight)
                            if (!firstPoint) {
                                drawLine(
                                    color       = color,
                                    strokeWidth = 1.dp.toPx(),
                                    start       = Offset(lastX, lastY),
                                    end         = Offset(newX,  newY)
                                )
                            }
                            lastX      = newX
                            lastY      = newY
                            firstPoint = false
                        }
                    }

                    if (displayAccelX) drawAccelTrace(Color(0xffff0000)) { it.accel.x }
                    if (displayAccelY) drawAccelTrace(Color(0xffffff00)) { it.accel.y }
                    if (displayAccelZ) drawAccelTrace(Color(0xff00ff00)) { it.accel.z }
                }
            }

            Spacer(modifier = modifier.weight(1f))

            // ── Controls row ──────────────────────────────────────────────
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

        // ── Legend / toggles (overlaid column, unchanged layout) ─────────
        Column {
            Spacer(modifier = modifier.weight(2f))
            Row {
                Spacer(modifier = modifier.weight(2f))
                Column(
                    modifier = modifier
                        .wrapContentHeight()
                        .weight(4f),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    val textSize    = 12.sp
                    val textPadding = 4.dp
                    val checkBoxSize = 28.dp

                    @Composable
                    fun LegendCheckbox(label: String, color: Color, checked: Boolean, onChecked: (Boolean) -> Unit) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = color, fontSize = textSize,
                                modifier = modifier.padding(start = textPadding))
                            Checkbox(
                                checked = checked,
                                onCheckedChange = onChecked,
                                colors = CheckboxDefaults.colors(
                                    checkedColor   = MaterialTheme.colorScheme.primary,
                                    uncheckedColor = MaterialTheme.colorScheme.primary,
                                    checkmarkColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.size(checkBoxSize)
                            )
                        }
                    }

                    LegendCheckbox("Descent",  MaterialTheme.colorScheme.onSurface, showRecovery)  { showRecovery  = it }
                    LegendCheckbox("Altitude", aglColor,          displayAltitude) { displayAltitude = it }
                    LegendCheckbox("Accel X",  Color(0xffff0000), displayAccelX)   { displayAccelX   = it }
                    LegendCheckbox("Accel Y",  Color(0xffffff00), displayAccelY)   { displayAccelY   = it }
                    LegendCheckbox("Accel Z",  Color(0xff00ff00), displayAccelZ)   { displayAccelZ   = it }
                }
            }
            Spacer(modifier = modifier.weight(3f))
        }
    }
}

// ============================================================================
//  Chart drawing helpers (unchanged signatures, hardened internals)
// ============================================================================

fun DrawScope.chartDeploymentEvent(
    chartTextColor: Color, chartTextSize: Float, chartText: String,
    flightEventData: FlightEventData, deployMode: DeployMode,
    x: Float, y: Float, canvasWidth: Float
) {
    drawIntoCanvas { canvas ->
        val paint = TextPaint().apply {
            color    = chartTextColor.toArgb()
            textSize = chartTextSize
        }
        val textWidth = paint.measureText(chartText)
        val xOffset = if (x + textWidth < canvasWidth) 0 else -textWidth.toInt() - 48
        canvas.nativeCanvas.drawText(chartText, x + 48 + xOffset, y + 6, paint)
    }
    val continuity1 = flightEventData.channel1PreFireContinuity
    val continuity2 = flightEventData.channel2PreFireContinuity
    val fired1      = flightEventData.channel1Fired
    val fired2      = flightEventData.channel2Fired
    val postCont1   = flightEventData.channel1PostFireContinuity
    val postCont2   = flightEventData.channel2PostFireContinuity
    val useChannel1 = flightEventData.channel1Mode == deployMode

    drawCircle(color = Color(0xFF808080), radius = 4.dp.toPx(), center = Offset(x + 8,  y),
        style = if (if (useChannel1) continuity1 else continuity2) Fill else Stroke(width = 1f))
    drawCircle(color = Color(0xFF808080), radius = 4.dp.toPx(), center = Offset(x + 24, y),
        style = if (if (useChannel1) fired1 else fired2) Fill else Stroke(width = 1f))
    drawCircle(color = Color(0xFF808080), radius = 4.dp.toPx(), center = Offset(x + 40, y),
        style = if (if (useChannel1) postCont1 else postCont2) Fill else Stroke(width = 1f))
}

fun DrawScope.chartEvent(
    chartColor: Color, chartTextSize: Float, chartText: String,
    delta: Float, maxAgl: Float,
    x: Float, y: Float, canvasWidth: Float
) {
    // Safety: guard against NaN/infinite delta or maxAgl
    val safeDelta  = if (delta.isFinite())  delta  else 0f
    val safeMaxAgl = if (maxAgl.isFinite() && maxAgl > 0f) maxAgl else 1f
    val yOffset = if (abs(safeDelta) < safeMaxAgl / 20f) 24 else 0

    drawIntoCanvas { canvas ->
        val paint = TextPaint().apply {
            color    = chartColor.toArgb()
            textSize = chartTextSize
        }
        val textWidth = paint.measureText(chartText)
        val xOffset = if (x + textWidth < canvasWidth) 0 else -textWidth.toInt() - 32
        canvas.nativeCanvas.drawText(chartText, x + 32 + xOffset, y + 6 + yOffset, paint)
    }
    drawCircle(color = chartColor, radius = 4.dp.toPx(), center = Offset(x, y))
}

fun floatDownTo(start: Float, end: Float, step: Float, block: (Float) -> Unit) {
    if (step <= 0f) return  // guard against infinite loop
    var v = start
    while (v >= end) {
        block(v)
        v -= step
    }
}