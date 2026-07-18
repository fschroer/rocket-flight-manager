package com.steampigeon.flightmanager.ui

import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.DeployChannelStats
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.FlightDataRepository
import com.steampigeon.flightmanager.data.FlightEventIndex
import com.steampigeon.flightmanager.data.FlightEvents
import com.steampigeon.flightmanager.data.FlightSample
import com.steampigeon.flightmanager.data.LocatorMessageState
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

// ============================================================================
//  Chart geometry
//
//  Shared by the draw pass and the pinch/pan gesture handler, which has to do
//  the same plot-area arithmetic to clamp panning.
// ============================================================================

private const val CHART_MARGIN_X      = 64f   // left gutter for altitude labels
private const val CHART_MARGIN_Y      = 32f   // bottom gutter for time labels
private const val CHART_AXIS_TEXT_SIZE = 32f
private const val CHART_BODY_TEXT_SIZE = 24f
private const val CHART_GRID_COUNT     = 5    // target gridlines per axis

// Event annotations grow as you zoom in, where there is room for them.  Growth
// is by sqrt(zoom) rather than zoom so it stays gentle, and caps at 2x the base
// size (reached at 4x zoom) — beyond that the labels would crowd out the trace
// they are annotating.  Axis labels deliberately do NOT scale: they are chart
// furniture, not content.
private const val MAX_EVENT_TEXT_SCALE = 2f

// Upper bound on pinch zoom.  At 25x a 60 s flight shows ~2.4 s across the
// plot, which is ~48 samples at the 20 Hz archive cadence — past that the
// trace is just line segments between adjacent samples.
private const val MAX_CHART_ZOOM = 25f

/**
 * Pan/zoom state for the chart, and the single definition of how data
 * coordinates map to canvas pixels.  Traces, gridlines and event markers all
 * project through [screenXOfMs] / [screenYOfValue], so they cannot drift apart.
 *
 * Identity ([zoom] 1, [pan] zero) fits the whole flight in the plot area.
 * [pan] is in canvas pixels; both axes share [zoom].
 */
internal data class ChartViewport(
    val zoom: Float = 1f,
    val pan:  Offset = Offset.Zero,
) {
    /**
     * Apply one pinch/drag gesture, holding the data under [centroid] still.
     *
     * [plotW] / [plotH] are the plot area, i.e. the canvas minus the axis
     * gutters. Panning is clamped so the data can never be dragged off-screen,
     * which also means zooming back out to 1 restores the exact original fit.
     */
    fun transform(
        centroid:   Offset,
        panChange:  Offset,
        zoomChange: Float,
        plotW:      Float,
        plotH:      Float,
    ): ChartViewport {
        if (plotW <= 0f || plotH <= 0f) return this

        val newZoom = (zoom * zoomChange).coerceIn(1f, MAX_CHART_ZOOM)
        // The factor actually applied after clamping — using zoomChange directly
        // would drift the viewport when pinching past either limit.
        val k = newZoom / zoom

        // X grows rightward from the gutter; Y is inverted (altitude grows up
        // from the baseline at plotH), hence the different arrangement.
        val cx = centroid.x - CHART_MARGIN_X
        val newPanX = cx - (cx - pan.x) * k + panChange.x
        val newPanY = (centroid.y - plotH) + (plotH + pan.y - centroid.y) * k + panChange.y

        return ChartViewport(
            zoom = newZoom,
            pan  = Offset(
                newPanX.coerceIn(-(newZoom - 1f) * plotW, 0f),
                newPanY.coerceIn(0f, (newZoom - 1f) * plotH),
            ),
        )
    }

    /** Canvas X for a time offset, given the full-flight duration [totalMs]. */
    fun screenXOfMs(tMs: Float, plotW: Float, totalMs: Float): Float =
        CHART_MARGIN_X + pan.x + tMs * (plotW / totalMs * zoom)

    /**
     * Canvas Y for a value on an axis spanning [axisMin]..[axisMax] over the
     * plot height. Used for both altitude (0..maxAgl) and acceleration.
     */
    fun screenYOfValue(value: Float, plotH: Float, axisMin: Float, axisMax: Float): Float =
        plotH + pan.y - (value - axisMin) * (plotH / (axisMax - axisMin) * zoom)

    /** Inverse of [screenXOfMs] at the plot's left and right edges. */
    fun visibleMsRange(plotW: Float, totalMs: Float): ClosedFloatingPointRange<Float> {
        val scale = plotW / totalMs * zoom
        return (-pan.x / scale)..((plotW - pan.x) / scale)
    }

    /** Inverse of [screenYOfValue] at the plot's bottom and top edges. */
    fun visibleValueRange(plotH: Float, axisMin: Float, axisMax: Float): ClosedFloatingPointRange<Float> {
        val scale = plotH / (axisMax - axisMin) * zoom
        return (axisMin + pan.y / scale)..(axisMin + (plotH + pan.y) / scale)
    }
}

/**
 * Pick a human-friendly grid interval — 1, 2 or 5 × 10ⁿ — covering [range] in
 * roughly [targetCount] steps.  Always returns a positive, finite value so the
 * gridline loops that divide by it can't spin.
 */
private fun niceInterval(range: Float, targetCount: Int = CHART_GRID_COUNT): Float {
    val fallback = 1f
    if (!range.isFinite() || range <= 0f || targetCount <= 0) return fallback
    val raw = range / targetCount
    if (!raw.isFinite() || raw <= 0f) return fallback
    val exponent = floor(log10(raw.toDouble()))
    val fraction = raw / 10.0.pow(exponent)
    val nice = when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 5.0 -> 5.0
        else            -> 10.0
    }
    val interval = (nice * 10.0.pow(exponent)).toFloat()
    return if (interval.isFinite() && interval > 0f) interval else fallback
}

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

// Note: the old clampX/clampY helpers are gone.  Now that the chart zooms and
// pans, pinning an off-screen point to the plot edge would draw a false line
// along the border; traces are wrapped in clipRect instead, and markers outside
// the plot area are skipped.

// ============================================================================
//  Flight events
//
//  The locator sends event *times* only (MsgType.FlightEvents); the altitude at
//  each event is inferred here from the profile samples for the same record, so
//  a marker always lands exactly on the plotted trace.
// ============================================================================

// How far an event timestamp may sit from the nearest sample before the event is
// dropped as unplottable.  Samples are 50 ms apart, so the nearest is normally
// within 25 ms; the slack covers a gap left by packets still in flight (one lost
// packet = 8 samples = 400 ms).
private const val EVENT_MATCH_TOLERANCE_MS = 1_000L

/** Deployment events carry per-channel fired / continuity indicators. */
private val DEPLOYMENT_EVENT_MODES = mapOf(
    FlightEventIndex.DroguePrimaryDeploy to DeployMode.DroguePrimary,
    FlightEventIndex.DrogueBackupDeploy  to DeployMode.DrogueBackup,
    FlightEventIndex.MainPrimaryDeploy   to DeployMode.MainPrimary,
    FlightEventIndex.MainBackupDeploy    to DeployMode.MainBackup,
)

/** An event placed against the profile data: time from the locator, altitude from the samples. */
internal data class ResolvedEvent(
    val event:       FlightEventIndex,
    val label:       String,
    val timestampMs: Long,
    val sampleIndex: Int,
    val altitudeM:   Float,
    val stats:       DeployChannelStats?,   // non-null for deployment events
)

/**
 * Match each recorded event time to the nearest flight sample.
 *
 * Returns events in chronological order.  Events the locator did not record
 * (absent from the present mask) and events with no sample near their timestamp
 * are omitted rather than collapsed onto sample 0 — drawing them at the origin
 * is what made every marker pile up on the launch pad.
 */
internal fun resolveEvents(
    samples: List<FlightSample>,
    events:  FlightEvents,
): List<ResolvedEvent> {
    if (samples.isEmpty() || events.isEmpty) return emptyList()

    return FlightEventIndex.entries.mapNotNull { event ->
        val eventMs = events.timestampMs(event) ?: return@mapNotNull null
        val index   = samples.indices.minByOrNull { abs(samples[it].timestampMs - eventMs) }
            ?: return@mapNotNull null
        if (abs(samples[index].timestampMs - eventMs) > EVENT_MATCH_TOLERANCE_MS)
            return@mapNotNull null

        val mode    = DEPLOYMENT_EVENT_MODES[event]
        val channel = mode?.let { events.channelFor(it) }
        // A deployment event whose mode isn't assigned to any channel can't be
        // attributed — skip it rather than draw indicators for a channel that
        // was never configured.
        if (mode != null && channel == null) return@mapNotNull null

        ResolvedEvent(
            event       = event,
            label       = if (channel != null) "Ch $channel ${event.label}" else event.label,
            timestampMs = eventMs,
            sampleIndex = index,
            altitudeM   = samples[index].altitudeM,
            stats       = channel?.let { events.channelStats.getOrNull(it - 1) },
        )
    }.sortedBy { it.timestampMs }
}

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
    val metadataAttempt                   by viewModel.flightProfileMetadataAttempt.collectAsState()
    val flightProfileDataMessageState     by viewModel.flightProfileDataMessageState.collectAsState()
    val flightProfileDataDisplayState     by viewModel.flightProfileDataDisplayState.collectAsState()
    val flightEvents                      by viewModel.flightEvents.collectAsState()
    val flightProfileArchivePosition      by viewModel.flightProfileArchivePosition.collectAsState()
    val locatorConfig                     by viewModel.remoteLocatorConfig.collectAsState()

    // Observe samples and progress directly from the repository so the chart
    // reacts to partial data arriving during a live transfer.
    val samples   by FlightDataRepository.samples.collectAsState()
    val progress  by FlightDataRepository.progress.collectAsState()

    // Filter to sane samples only — done once here so every downstream
    // calculation works from a validated list.
    val saneSamples = remember(samples) { samples.filter { it.isSane() } }

    // Placed events, recomputed as samples stream in so markers appear as soon
    // as the packets covering their timestamps arrive.
    val resolvedEvents = remember(saneSamples, flightEvents) {
        resolveEvents(saneSamples, flightEvents)
    }

    // Reset the data-display state when leaving so re-entry starts at the list,
    // not a stale chart.  Metadata state is NOT reset here — the LaunchedEffect
    // below sends a fresh request unconditionally on every entry, so there is no
    // Idle-gate race to worry about.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.updateFlightProfileDataDisplayState(false)
            // Tell the locator to return to Disarmed so it resumes PreLaunchData.
            service?.exitFlightProfileMode()
        }
    }

    // Request metadata on entry, keyed on `service` rather than Unit.
    //
    // BluetoothService binds asynchronously, so on a freshly-created Activity
    // (process death, or a configuration change the manifest doesn't absorb)
    // this composes while `service` is still null.  Keyed on Unit the request
    // went nowhere, recorded SendFailure, and — because Unit never changes —
    // was never retried, leaving the screen on "Fetching flight data…" forever.
    // Keying on `service` re-runs it the moment the binding lands.
    LaunchedEffect(service) {
        val svc = service ?: return@LaunchedEffect
        // Resuming into an already-loaded chart: a FlightMetadataRequest would
        // send the locator back to MetadataRequested and abort the transfer the
        // user is waiting on, so leave an in-progress load alone.
        if (flightProfileDataDisplayState) return@LaunchedEffect

        viewModel.updateFlightProfileDataMessageState(LocatorMessageState.Idle)
        viewModel.clearFlightProfileMetadata()
        // Suspends here, re-requesting with backoff until the locator answers.
        // Leaving the screen cancels this coroutine, which ends the retries.
        viewModel.fetchFlightProfileMetadata(svc)
    }

    BackHandler(enabled = true) {
        if (flightProfileDataDisplayState) {
            viewModel.clearFlightProfileData()
            viewModel.updateFlightProfileDataMessageState(LocatorMessageState.Idle)
            viewModel.updateFlightProfileDataDisplayState(false)
            // Tell the locator we are back at the list so it aborts the in-flight
            // transfer immediately (and stays quiet) instead of bursting until it
            // times out.
            service?.requestFlightProfileMetadata()
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
                    flightProfileMetadata.isNotEmpty() ->
                        // Always show the list while metadata is populated, regardless of
                        // message state, so a background state change never hides the list.
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
                            HorizontalDivider(modifier = modifier.weight(1f))
                        }
                    flightProfileMetadataMessageState == LocatorMessageState.AckUpdated ->
                        Text("No flights recorded on locator ${locatorConfig.deviceName}")
                    else ->
                        // Show the attempt count once we're retrying, so a lossy
                        // link reads as "still trying" rather than a frozen screen.
                        Text(
                            "Fetching flight data from locator ${locatorConfig.deviceName}" +
                                    if (metadataAttempt > 1) " (attempt $metadataAttempt)" else ""
                        )
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

        // The locator advertised a zero-length transfer: this record has no
        // sample data.  Show a clear message instead of a perpetual loading
        // chart, and let the user return to the list.
        if (progress.noData) {
            Column(
                modifier = modifier.fillMaxHeight().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No flight data for this record")
                Spacer(modifier = modifier.size(8.dp))
                OutlinedButton(onClick = {
                    viewModel.clearFlightProfileData()
                    viewModel.updateFlightProfileDataMessageState(LocatorMessageState.Idle)
                    viewModel.updateFlightProfileDataDisplayState(false)
                    service?.requestFlightProfileMetadata()
                }) {
                    Text(stringResource(R.string.return_to_main))
                }
            }
            return
        }

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

        // Round the apogee up to a whole number of nice intervals: this is the
        // altitude the chart is scaled to fit at zoom 1.  Gridlines themselves
        // are derived from the *visible* range further down, so they stay
        // sensible at any zoom level.
        val baseInterval = niceInterval(rawApogee)
        val maxAgl       = (baseInterval * ceil(rawApogee / baseInterval).toInt().coerceAtLeast(1))
            .coerceAtLeast(1f)

        // ── X-axis (time) range ───────────────────────────────────────────
        // Derive the X range from actual sample timestamps rather than relying
        // on the metadata timeToDrogue field, which uses the old protocol's
        // timing model (hardcoded 0.05s/sample ascending, 1.0s/sample descending).
        // With the new codec, timestampMs is the authoritative time source.
        val firstTimestampMs = saneSamples.firstOrNull()?.timestampMs ?: 0L
        val lastTimestampMs  = saneSamples.lastOrNull()?.timestampMs  ?: 0L
        val totalFlightMs    = (lastTimestampMs - firstTimestampMs).coerceAtLeast(1L)

        // Find the apogee sample index by the highest altitude reading
        // The whole flight is always plotted.  The old "Descent" checkbox
        // truncated the chart at apogee to make the ascent readable; pinch-zoom
        // does that better (and without hiding data), so the toggle is gone.
        val displaySamples = saneSamples

        // Safety: chart width must be positive
        val safeChartMs = totalFlightMs.coerceAtLeast(1L).toFloat()

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
        val apogeeColor      = Color(0xFF0000FF)

        var displayAltitude by remember { mutableStateOf(true) }
        var displayAccelX   by remember { mutableStateOf(true) }
        var displayAccelY   by remember { mutableStateOf(true) }
        var displayAccelZ   by remember { mutableStateOf(true) }

        // ── Pinch-zoom viewport ───────────────────────────────────────────
        var viewport by remember { mutableStateOf(ChartViewport()) }

        // Re-fit whenever the plotted range changes (samples arriving during a
        // live transfer) — a viewport computed against the old range would
        // otherwise be left pointing somewhere meaningless.
        LaunchedEffect(safeChartMs, maxAgl) {
            viewport = ChartViewport()
        }

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

            // Only shown while zoomed — it doubles as the discovery hint for
            // double-tap-to-reset, which is otherwise invisible.
            if (viewport.zoom > 1.01f) {
                Text(
                    text = "${viewport.zoom.toBigDecimal().setScale(1, RoundingMode.HALF_UP)}× — double-tap to reset",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Canvas(
                modifier = modifier
                    .weight(11f)
                    .fillMaxWidth()
                    // Pinch to zoom about the centroid, drag to pan.
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, panChange, zoomChange, _ ->
                            viewport = viewport.transform(
                                centroid   = centroid,
                                panChange  = panChange,
                                zoomChange = zoomChange,
                                plotW      = size.width.toFloat()  - CHART_MARGIN_X,
                                plotH      = size.height.toFloat() - CHART_MARGIN_Y,
                            )
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { viewport = ChartViewport() })
                    }
            ) {
                val chartMarginX      = CHART_MARGIN_X
                val chartAxisTextSize = CHART_AXIS_TEXT_SIZE
                val targetGridCount   = CHART_GRID_COUNT
                // Event annotations grow with zoom; axis furniture stays fixed.
                val eventScale        = sqrt(viewport.zoom).coerceAtMost(MAX_EVENT_TEXT_SCALE)
                val chartBodyTextSize = CHART_BODY_TEXT_SIZE * eventScale
                val canvasHeight      = size.height - CHART_MARGIN_Y
                val canvasWidth       = size.width - chartMarginX

                // Safety: if canvas is too small to draw in, bail early
                if (canvasHeight <= 0f || canvasWidth <= 0f) return@Canvas
                if (displaySamples.size < MIN_SAMPLES_TO_CHART) return@Canvas

                // Zoom-aware projection — see ChartViewport.
                fun xOfMs(tMs: Float)  = viewport.screenXOfMs(tMs, canvasWidth, safeChartMs)
                fun yOfAlt(alt: Float) = viewport.screenYOfValue(alt, canvasHeight, 0f, maxAgl)

                // Data range currently on screen, from the inverse projection.
                val visibleMs  = viewport.visibleMsRange(canvasWidth, safeChartMs)
                val visibleAlt = viewport.visibleValueRange(canvasHeight, 0f, maxAgl)
                val visibleMsStart = visibleMs.start
                val visibleMsEnd   = visibleMs.endInclusive
                val visibleAltLow  = visibleAlt.start
                val visibleAltHigh = visibleAlt.endInclusive

                // ── Vertical grid lines (time axis) ───────────────────────
                // Stepped over the visible window rather than the whole flight,
                // so the gridline count stays constant as you zoom in.
                val xGridIntervalMs = niceInterval(visibleMsEnd - visibleMsStart, targetGridCount)
                val timeDecimals = if (xGridIntervalMs < 100f) 2 else 1
                var gridTimeMs = floor(visibleMsStart / xGridIntervalMs) * xGridIntervalMs
                while (gridTimeMs <= visibleMsEnd) {
                    val gx = xOfMs(gridTimeMs)
                    if (gx >= chartMarginX - 0.5f && gx <= canvasWidth + chartMarginX + 0.5f) {
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
                            val label = "${
                                (gridTimeMs / 1000f).toBigDecimal()
                                    .setScale(timeDecimals, RoundingMode.HALF_UP)
                            }s"
                            val tx = gx - paint.measureText(label) / 2f
                            val ty = canvasHeight + chartAxisTextSize
                            canvas.nativeCanvas.drawText(label, tx, ty, paint)
                        }
                    }
                    gridTimeMs += xGridIntervalMs
                }

                // ── Horizontal grid lines (altitude axis) ─────────────────
                val yGridInterval = niceInterval(visibleAltHigh - visibleAltLow, targetGridCount)
                var gridAlt = floor(visibleAltLow / yGridInterval) * yGridInterval
                while (gridAlt <= visibleAltHigh) {
                    val gy = yOfAlt(gridAlt)
                    if (gy >= -0.5f && gy <= canvasHeight + 0.5f) {
                        drawLine(
                            color = gridColor,
                            strokeWidth = 1.dp.toPx(),
                            start = Offset(chartMarginX, gy),
                            end   = Offset(canvasWidth + chartMarginX, gy)
                        )
                        if (displayAltitude) {
                            drawIntoCanvas { canvas ->
                                val paint = TextPaint().apply {
                                    color    = legendColor.toArgb()
                                    textSize = chartAxisTextSize
                                }
                                // Sub-metre gridlines need a decimal, or a zoomed
                                // axis reads as several identical labels.
                                val label = if (yGridInterval < 1f)
                                    "${gridAlt.toBigDecimal().setScale(1, RoundingMode.HALF_UP)}m"
                                else "${gridAlt.roundToInt()}m"
                                val tx = chartMarginX - paint.measureText(label) - 8f
                                val ty = gy + chartAxisTextSize / 2f
                                canvas.nativeCanvas.drawText(label, tx, ty, paint)
                            }
                        }
                    }
                    gridAlt += yGridInterval
                }

                // ── Altitude trace ────────────────────────────────────────
                // Clipped rather than clamped: clamping a zoomed-out-of-view
                // point to the edge would draw a false line along the border.
                clipRect(
                    left   = chartMarginX,
                    top    = 0f,
                    right  = canvasWidth + chartMarginX,
                    bottom = canvasHeight,
                ) {
                    if (displayAltitude) {
                        var lastX = 0f
                        var lastY = 0f
                        var firstPoint = true

                        displaySamples.forEach { sample ->
                            val tMs  = (sample.timestampMs - firstTimestampMs).toFloat()
                            val newX = xOfMs(tMs)
                            val newY = yOfAlt(sample.altitudeM)

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
                }

                // ── Accelerometer traces ──────────────────────────────────
                // Only draw if there is meaningful range in the data
                if (accelRange > 0.001f && (displayAccelX || displayAccelY || displayAccelZ)) {
                    val gForce = RocketViewModel.G_FORCE_MS2.toFloat()
                    val accelAxisMax = minAccel + accelRange

                    fun yOfAccel(v: Float) =
                        viewport.screenYOfValue(v, canvasHeight, minAccel, accelAxisMax)

                    // Right-hand axis, stepped in whole g over the visible span so
                    // the labels stay round numbers at every zoom level.
                    val visibleAccel     = viewport.visibleValueRange(canvasHeight, minAccel, accelAxisMax)
                    val visibleAccelLow  = visibleAccel.start
                    val visibleAccelHigh = visibleAccel.endInclusive
                    val gInterval = niceInterval(
                        (visibleAccelHigh - visibleAccelLow) / gForce, targetGridCount
                    )
                    val gDecimals = if (gInterval < 1f) 1 else 0
                    var gridG = floor(visibleAccelLow / gForce / gInterval) * gInterval
                    while (gridG <= visibleAccelHigh / gForce) {
                        val gy = yOfAccel(gridG * gForce)
                        if (gy >= -0.5f && gy <= canvasHeight + 0.5f) {
                            drawIntoCanvas { canvas ->
                                val paint = TextPaint().apply {
                                    color    = legendColor.toArgb()
                                    textSize = chartAxisTextSize
                                }
                                val label = "${
                                    gridG.toBigDecimal().setScale(gDecimals, RoundingMode.HALF_UP)
                                }g"
                                canvas.nativeCanvas.drawText(
                                    label, canvasWidth + 4f, gy + chartAxisTextSize / 2f, paint
                                )
                            }
                        }
                        gridG += gInterval
                    }

                    clipRect(
                        left   = chartMarginX,
                        top    = 0f,
                        right  = canvasWidth + chartMarginX,
                        bottom = canvasHeight,
                    ) {
                        fun drawAccelTrace(color: Color, getValue: (FlightSample) -> Float) {
                            var lastX = 0f
                            var lastY = 0f
                            var firstPoint = true
                            displaySamples.forEach { sample ->
                                val v = getValue(sample)
                                // Skip any remaining insane individual values that slipped
                                // through the per-sample isSane() check (e.g. a single axis spike)
                                if (!v.isFinite() || v < MIN_SANE_ACCEL_MPS2 || v > MAX_SANE_ACCEL_MPS2) {
                                    firstPoint = true  // lift the pen
                                    return@forEach
                                }
                                val newX = xOfMs((sample.timestampMs - firstTimestampMs).toFloat())
                                val newY = yOfAccel(v)
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

                // ── Event markers ─────────────────────────────────────────
                // Drawn last so labels sit above every trace.  Each annotation
                // is offset into a free row so events sharing a timestamp (a
                // primary and its backup firing together, say) stay legible.
                if (displayAltitude) {
                    // Rows already claimed, as (x, right edge) per row index.
                    val occupiedRows = mutableListOf<MutableList<ClosedFloatingPointRange<Float>>>()
                    val rowHeight = chartBodyTextSize + 8f

                    resolvedEvents.forEach { event ->
                        val tMs = (event.timestampMs - firstTimestampMs).toFloat()
                        val x = xOfMs(tMs)
                        val y = yOfAlt(event.altitudeM)
                        // Panned/zoomed out of the plot area — skip it entirely
                        // rather than pinning a misleading marker to the edge.
                        if (x < chartMarginX || x > canvasWidth + chartMarginX) return@forEach
                        if (y < 0f || y > canvasHeight) return@forEach

                        val color = when (event.event) {
                            FlightEventIndex.Apogee                  -> apogeeColor
                            FlightEventIndex.DrogueVelocityThreshold -> drogueColor
                            FlightEventIndex.MainVelocityThreshold   -> mainColor
                            else                                     -> chartBodyColor
                        }
                        val label = "${event.label}: " +
                                "${event.altitudeM.toBigDecimal().setScale(1, RoundingMode.HALF_UP)}m"

                        // Indicator circles precede the text for deployment events,
                        // and scale with it so the annotation reads as one unit.
                        val indicatorWidth = if (event.stats != null) 48f * eventScale else 0f
                        val textWidth = measureChartText(label, chartBodyTextSize)
                        val annotationWidth = indicatorWidth + textWidth + 8f

                        // Flip the annotation to the left of the point when it
                        // would otherwise run off the right edge, and keep it
                        // clear of the altitude axis either way.
                        val flip = x + annotationWidth > canvasWidth + chartMarginX
                        val startX = (if (flip) x - annotationWidth - 8f else x + 8f)
                            .coerceAtLeast(chartMarginX)
                        val span = startX..(startX + annotationWidth)

                        // First row whose existing annotations don't overlap this one.
                        var row = occupiedRows.indexOfFirst { claimed ->
                            claimed.none { it.start <= span.endInclusive && span.start <= it.endInclusive }
                        }
                        if (row < 0) {
                            occupiedRows.add(mutableListOf())
                            row = occupiedRows.lastIndex
                        }
                        occupiedRows[row].add(span)

                        // Keep the stack inside the chart: below the point when
                        // there is room, above it when the point sits low.  The
                        // final clamp matters for a tall stack at apogee, where
                        // both directions can overshoot.  The lower bound is
                        // itself capped at canvasHeight — on a very short canvas
                        // an unguarded coerceIn(min > max) throws.
                        val stackOffset = (row + 1) * rowHeight
                        val annotationY = (if (y + stackOffset < canvasHeight) y + stackOffset
                                           else y - stackOffset)
                            .coerceIn(minOf(chartBodyTextSize, canvasHeight), canvasHeight)

                        // A dot at the true location — never shifted — plus a
                        // leader to its annotation row so the pairing is clear
                        // even when several events stack up.
                        drawCircle(color = color, radius = 3.dp.toPx() * eventScale, center = Offset(x, y))
                        drawLine(
                            color = color,
                            strokeWidth = 1.dp.toPx(),
                            start = Offset(x, y),
                            end   = Offset(startX + if (flip) annotationWidth else 0f, annotationY)
                        )

                        event.stats?.let { stats ->
                            chartDeploymentIndicators(stats, startX, annotationY, eventScale)
                        }
                        drawIntoCanvas { canvas ->
                            val paint = TextPaint().apply {
                                this.color = color.toArgb()
                                textSize   = chartBodyTextSize
                            }
                            canvas.nativeCanvas.drawText(
                                label, startX + indicatorWidth, annotationY + chartBodyTextSize / 3f, paint
                            )
                        }
                    }
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
                        // Abort the locator's transfer and return it to the list.
                        service?.requestFlightProfileMetadata()
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

/** Width of [text] at [textSize], for laying an annotation out before drawing it. */
private fun measureChartText(text: String, textSize: Float): Float =
    TextPaint().apply { this.textSize = textSize }.measureText(text)

/**
 * The three deployment indicator circles: pre-fire continuity, fired, post-fire
 * continuity.  Filled = true, outlined = false — an outlined "fired" circle next
 * to a filled pre-fire continuity means the charge had continuity but never got
 * a fire command.
 */
private fun DrawScope.chartDeploymentIndicators(
    stats: DeployChannelStats,
    x: Float,
    y: Float,
    scale: Float = 1f,
) {
    fun indicator(offset: Float, on: Boolean) = drawCircle(
        color  = Color(0xFF808080),
        radius = 4.dp.toPx() * scale,
        center = Offset(x + offset * scale, y),
        style  = if (on) Fill else Stroke(width = 1f * scale),
    )
    indicator(8f,  stats.preFireContinuity)
    indicator(24f, stats.fired)
    indicator(40f, stats.postFireContinuity)
}