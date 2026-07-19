package com.steampigeon.flightmanager.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import com.mutualmobile.composesensors.SensorDelay
import com.mutualmobile.composesensors.rememberAccelerometerSensorState
import com.mutualmobile.composesensors.rememberMagneticFieldSensorState
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.NavDestination
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.Quaternionf
import com.steampigeon.flightmanager.data.RocketState
import com.steampigeon.flightmanager.data.SensorHealth
import com.steampigeon.flightmanager.ui.RocketViewModel.Companion.G_FORCE_MS2
import com.steampigeon.flightmanager.ui.RocketViewModel.Companion.RAD2DEG
import com.steampigeon.flightmanager.ui.theme.TelemetryTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.sqrt

private const val messageTimeout = 2000
private const val actionPanelCollapseDelay = 5000L   // auto-collapse the Rescan/Arm action panel after this idle time

// Consistent semi-transparent overlay background used for all map UI panels and buttons.
// Derived from secondaryContainerLight (#5D6F96) at 75% opacity.
private val mapOverlayBg = Color(0xC05D6F96)
private const val landingAltitudeThreshold = 30
private const val minimumSpokenAGLVelocity = 2 * 9.8

// Continuous-announcement cadence and descent thresholds. Speech timing is driven from a
// fixed poll interval (not the TTS engine's isSpeaking flag) so behavior is consistent
// across phone hardware.
private const val announcementIntervalMillis = 500L          // poll cadence for continuous callouts
private const val descentWarningIntervalMillis = 4000L       // minimum gap between descent warnings
private const val freefallDescentRate = 30f                  // m/s downward => still in freefall (pre-chute)
private const val minDescentRateForPrediction = 1f           // m/s, floor to avoid div-by-zero / noise
private const val landingLeadTimeSeconds = 3f                // announce landing this long before predicted touchdown
private const val landingLinkLossTimeout = 5000L             // telemetry gap (ms) during descent that triggers the link-loss fallback

// ── Supporting types ──────────────────────────────────────────────────────────

/** A navigation drawer entry binding a label, icon, and destination screen. */
private data class DrawerItem(val labelRes: Int, val iconRes: Int, val screen: NavDestination)

/** Pairs a deployment channel's mode with its armed state for display. */
private data class ChannelConfig(val mode: DeployMode?, val isArmed: Boolean)

// ── Helper functions ──────────────────────────────────────────────────────────

/** Returns the display string for a single deployment channel based on its mode.
 *  Format: "Ch \[n]: [DP|DB|MP|MB|NA] \[value]" */
private fun deployChannelText(channel: Int, mode: DeployMode?, config: LocatorConfig): String {
    val abbr = when (mode) {
        DeployMode.DroguePrimary -> "Drogue Prm "
        DeployMode.DrogueBackup  -> "Drogue Bkp "
        DeployMode.MainPrimary   -> "Main   Prm "
        DeployMode.MainBackup    -> "Main   Bkp "
        else                     -> "Unused"
    }
    val value = when (mode) {
        DeployMode.DroguePrimary ->
            " ${config.droguePrimaryDeployDelay / 10}.${config.droguePrimaryDeployDelay % 10} s"
        DeployMode.DrogueBackup ->
            " ${config.drogueBackupDeployDelay / 10}.${config.drogueBackupDeployDelay % 10} s"
        DeployMode.MainPrimary ->
            " ${config.mainPrimaryDeployAltitude} m"
        DeployMode.MainBackup ->
            " ${config.mainBackupDeployAltitude} m"
        else -> ""
    }
    return "Ch $channel: $abbr$value"
}

// Bounds-fitting is done by MapLibre's own getCameraForLatLngBounds (a pure query) rather
// than hand-rolled Mercator math: replicating the SDK's pixel-density and tile-size
// conventions is easy to get subtly wrong, and wrong framing is invisible until the points
// you were supposed to be tracking slide off screen.

// ── HomeScreen ────────────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    navController: NavHostController,
    viewModel: RocketViewModel = viewModel(),
    permissionsState: MultiplePermissionsState,
    textToSpeech: TextToSpeech?,
    onRescan: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val bluetoothConnectionState = BluetoothManagerRepository.bluetoothConnectionState.collectAsState().value
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var isMapLoaded by remember { mutableStateOf(false) }
    // Satellite imagery + gesture settings are configured inside MapLibreMapView; the
    // old Google MapProperties/MapUiSettings holders are no longer needed.

    val receiverConfig by viewModel.remoteReceiverConfig.collectAsState()
    val locatorConfig by viewModel.remoteLocatorConfig.collectAsState()
    val rocketState by viewModel.rocketState.collectAsState()
    val receiverDeviceName = BluetoothManagerRepository.receiverDevice.collectAsState().value?.name
        ?.takeIf { it.isNotEmpty() } ?: receiverConfig.deviceName
    val armedState = BluetoothManagerRepository.armedState.collectAsState().value
    val locatorArmedMessageState = BluetoothManagerRepository.locatorArmedMessageState.collectAsState().value
    val orientation = LocalConfiguration.current.orientation
    val hasCompass = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_COMPASS)

    // Sensor states reserved for future orientation tracking
    val accelerometerState = rememberAccelerometerSensorState(sensorDelay = SensorDelay.Normal)
    val magneticFieldState = rememberMagneticFieldSensorState(sensorDelay = SensorDelay.Normal)

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    // ViewModel-scoped so the fix survives navigation to the flight profiles screen and back.
    val trackerLocation by viewModel.trackerLocation.collectAsState()
    // Stand-in used until the first fix so the map can render right away; its 0,0
    // coordinates read as "no tracker GPS" everywhere downstream (validLatLng).
    val fallbackTrackerLocation = remember { Location("fallback") }
    val locationPermissionState = permissionsState.permissions
        .find { it.permission == Manifest.permission.ACCESS_FINE_LOCATION }
    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { viewModel.updateTrackerLocation(it) }
            }
        }
    }
    DisposableEffect(locationPermissionState?.hasPermission) {
        if (locationPermissionState?.hasPermission == true) {
            // Seed from the cached fix so the map can render immediately instead of
            // waiting for the first live GPS update (which can take many seconds, or
            // never arrive indoors / with GPS off).
            // Read through the flow, not the captured composable value, so a fix that landed
            // between this call and its callback isn't overwritten by an older cached one.
            fusedLocationClient.lastLocation.addOnSuccessListener { cached ->
                if (viewModel.trackerLocation.value == null && cached != null)
                    viewModel.updateTrackerLocation(cached)
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L).build()
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }
        onDispose { fusedLocationClient.removeLocationUpdates(locationCallback) }
    }

    val locatorLatLng = LatLng(rocketState.latitude, rocketState.longitude)
    var locatorGPSLock by remember { mutableStateOf(false) }
    LaunchedEffect(trackerLocation, locatorLatLng) {
        trackerLocation?.let {
            val vector = viewModel.locatorVector(LatLng(it.latitude, it.longitude), locatorLatLng)
            viewModel.updateLocatorVector(vector)
        }
        locatorGPSLock = locatorLatLng.latitude != 0.0 && locatorLatLng.longitude != 0.0
    }

    val distanceToLocator = viewModel.locatorDistance.collectAsState().value
    val azimuth = viewModel.handheldDeviceAzimuth.collectAsState().value
    val lastAzimuth = viewModel.lastHandheldDeviceAzimuth.collectAsState().value
    val handheldDevicePitch = viewModel.handheldDevicePitch.collectAsState().value
    val handheldCameraAzimuth = viewModel.handheldCameraAzimuth.collectAsState().value
    val locatorElevation = viewModel.locatorElevation.collectAsState().value
    val azimuthToLocator = viewModel.locatorAzimuth.collectAsState().value
    val lastPreLaunchMessageAge = System.currentTimeMillis() - rocketState.lastPreLaunchMessageTime
    val flightPath = viewModel.flightPath.collectAsState().value
    val isFlightPathRecording = viewModel.isFlightPathRecording.collectAsState().value

    // Headless composable: manages all flight-event speech announcements
    FlightSpeechAnnouncer(
        rocketState = rocketState,
        armedState = armedState,
        locatorConfig = locatorConfig,
        locatorLatLng = locatorLatLng,
        viewModel = viewModel,
        textToSpeech = textToSpeech,
    )

    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
        CameraPreviewScreen(
            handheldCameraAzimuth, azimuthToLocator, handheldDevicePitch, locatorElevation,
            rocketSpeed = rocketState.velocity,
            rocketAttitude = rocketState.attitude,
            armedState = armedState,
            lastPreLaunchMessageAge = lastPreLaunchMessageAge,
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = modifier
                        .height(IntrinsicSize.Min)
                        .width(IntrinsicSize.Max),
                    drawerContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    drawerShape = RoundedCornerShape(bottomEnd = 16.dp)
                ) {
                    AppDrawerContent(
                        bluetoothConnectionState = bluetoothConnectionState,
                        armedState = armedState,
                        locatorActive = lastPreLaunchMessageAge < messageTimeout,
                        onNavigate = { screen ->
                            scope.launch { drawerState.close() }
                            navController.navigate(screen.name)
                        }
                    )
                }
            }
        ) {
            var scaffoldSize by remember { mutableStateOf(IntSize(0, 0)) }
            @Suppress("UnusedMaterial3ScaffoldPaddingParameter")
            Scaffold(
                modifier = Modifier.onGloballyPositioned { scaffoldSize = it.size },
                floatingActionButton = {},
            ) {
                // Render the map even before the first GPS fix.  Downstream code
                // treats a 0,0 location as "no tracker GPS" (validLatLng) and simply
                // omits the phone position from auto-zoom until a real fix arrives,
                // so the map (and its controls) appear immediately rather than
                // blocking on location the way an early-return null gate did.
                run {
                    MapWithOverlays(
                        trackerLocation = trackerLocation ?: fallbackTrackerLocation,
                        rocketState = rocketState,
                        armedState = armedState,
                        receiverDeviceName = receiverDeviceName,
                        locatorConfig = locatorConfig,
                        locatorArmedMessageState = locatorArmedMessageState,
                        bluetoothConnectionState = bluetoothConnectionState,
                        locatorGPSLock = locatorGPSLock,
                        isMapLoaded = isMapLoaded,
                        onMapLoaded = { isMapLoaded = true },
                        onRescan = onRescan,
                        hasCompass = hasCompass,
                        azimuth = azimuth,
                        lastAzimuth = lastAzimuth,
                        lastPreLaunchMessageAge = lastPreLaunchMessageAge,
                        distanceToLocator = distanceToLocator,
                        viewModel = viewModel,
                        scaffoldSize = scaffoldSize,
                        textToSpeech = textToSpeech,
                        flightPath = flightPath,
                        onMenuClick = { scope.launch { drawerState.apply { if (isClosed) open() else close() } } },
                        modifier = modifier
                    )
                }
            }
        }
    }
}

// ── Flight speech announcer ───────────────────────────────────────────────────

/**
 * Headless composable that owns all flight-event speech announcement state and logic.
 * Emits no UI; call it once from HomeScreen to register the two announcement effects.
 */
@Composable
private fun FlightSpeechAnnouncer(
    rocketState: RocketState,
    armedState: Boolean,
    locatorConfig: LocatorConfig,
    locatorLatLng: LatLng,
    viewModel: RocketViewModel,
    textToSpeech: TextToSpeech?,
) {
    var previousAGL by remember { mutableIntStateOf(0) }
    var apogeeSpoken by remember { mutableStateOf(false) }
    var launchedState by remember { mutableStateOf(false) }
    var droguePrimaryState by remember { mutableStateOf(false) }
    var drogueBackupState by remember { mutableStateOf(false) }
    var mainPrimaryState by remember { mutableStateOf(false) }
    var mainBackupState by remember { mutableStateOf(false) }
    var drogueDeploySpoken by remember { mutableStateOf(false) }
    var mainDeploySpoken by remember { mutableStateOf(false) }
    var landingSpoken by remember { mutableStateOf(false) }
    var signalLossSpoken by remember { mutableStateOf(false) }
    var receivedState by remember { mutableStateOf(FlightStates.WaitingLaunch) }
    var noseoverTime by remember { mutableLongStateOf(0L) }
    var launchLocation by remember { mutableStateOf(LatLng(0.0, 0.0)) }

    val vectorFromLaunch = viewModel.locatorVector(launchLocation, locatorLatLng)

    // Announces discrete flight state transitions (apogee, drogue/main deploy, landing).
    LaunchedEffect(rocketState.flightState) {
        if (!armedState || rocketState.flightState <= receivedState) return@LaunchedEffect
        receivedState = rocketState.flightState

        if (rocketState.flightState >= FlightStates.Launched && !launchedState) {
            launchedState = true
            launchLocation = LatLng(rocketState.latitude, rocketState.longitude)
        }
        if (rocketState.flightState >= FlightStates.Noseover && noseoverTime == 0L) {
            noseoverTime = System.currentTimeMillis()
            if (!apogeeSpoken) {
                apogeeSpoken = true
                textToSpeech?.speak(
                    "Apogee, ${rocketState.altitudeAboveGroundLevel.toInt()} meters.",
                    TextToSpeech.QUEUE_ADD, null, null
                )
            }
        }
        if (rocketState.flightState >= FlightStates.DroguePrimaryEvent && !droguePrimaryState) {
            droguePrimaryState = true
            if (locatorConfig.deploymentChannel1Mode == DeployMode.DroguePrimary && rocketState.channel1Fired ||
                locatorConfig.deploymentChannel2Mode == DeployMode.DroguePrimary && rocketState.channel2Fired)
                textToSpeech?.speak("Drogue charge.", TextToSpeech.QUEUE_ADD, null, null)
        }
        if (rocketState.flightState >= FlightStates.DrogueBackupEvent && !drogueBackupState) {
            drogueBackupState = true
            if (locatorConfig.deploymentChannel1Mode == DeployMode.DrogueBackup && rocketState.channel1Fired ||
                locatorConfig.deploymentChannel2Mode == DeployMode.DrogueBackup && rocketState.channel2Fired)
                textToSpeech?.speak("Drogue backup charge.", TextToSpeech.QUEUE_ADD, null, null)
        }
        if (rocketState.flightState >= FlightStates.MainPrimaryEvent && !mainPrimaryState) {
            mainPrimaryState = true
            if (locatorConfig.deploymentChannel1Mode == DeployMode.MainPrimary && rocketState.channel1Fired ||
                locatorConfig.deploymentChannel2Mode == DeployMode.MainPrimary && rocketState.channel2Fired)
                textToSpeech?.speak("Main charge.", TextToSpeech.QUEUE_ADD, null, null)
        }
        if (rocketState.flightState >= FlightStates.MainBackupEvent && !mainBackupState) {
            mainBackupState = true
            if (locatorConfig.deploymentChannel1Mode == DeployMode.MainBackup && rocketState.channel1Fired ||
                locatorConfig.deploymentChannel2Mode == DeployMode.MainBackup && rocketState.channel2Fired)
                textToSpeech?.speak("Main backup charge.", TextToSpeech.QUEUE_ADD, null, null)
        }
        if (rocketState.flightState >= FlightStates.Landed) {
            // Reset all announcement guards for the next flight
            previousAGL = 0
            launchedState = false
            apogeeSpoken = false
            droguePrimaryState = false
            drogueBackupState = false
            mainPrimaryState = false
            mainBackupState = false
            drogueDeploySpoken = false
            mainDeploySpoken = false
            landingSpoken = false
            signalLossSpoken = false
            receivedState = FlightStates.WaitingLaunch
            noseoverTime = 0
            launchLocation = LatLng(0.0, 0.0)
        }
    }

    // Continuous ascent/descent callouts, driven from a fixed-cadence poll loop rather than
    // the TTS engine's isSpeaking flag so the announcement rate is identical across phone
    // hardware. rememberUpdatedState keeps the long-running coroutine reading the latest
    // telemetry, and lets the loop detect a stale (lost) LoRa link even when no new messages
    // are arriving to drive a recomposition.
    val currentRocketState by rememberUpdatedState(rocketState)
    val currentVector by rememberUpdatedState(vectorFromLaunch)
    LaunchedEffect(armedState) {
        if (!armedState) return@LaunchedEffect
        var lastDescentWarningTime = 0L
        while (true) {
            delay(announcementIntervalMillis)
            val state = currentRocketState
            val vector = currentVector
            val now = System.currentTimeMillis()
            val messageAge = now - state.lastPreLaunchMessageTime
            val descentRate = state.velNed.z   // NED Down component: positive while descending

            // Ascent altitude callouts every 100 m during coast to apogee.
            if (state.flightState == FlightStates.Burnout) {
                val roundedAGL = (state.altitudeAboveGroundLevel / 100).toInt() * 100
                if (roundedAGL > previousAGL) {
                    if (state.velocity > minimumSpokenAGLVelocity)
                        textToSpeech?.speak("$roundedAGL meters.", TextToSpeech.QUEUE_ADD, null, null)
                    previousAGL = roundedAGL
                }
            }

            // Descent: periodic warnings while in freefall, then exactly one landing announcement.
            // Landing is predicted from the vertical descent rate (time-to-ground), with a floor
            // on AGL and a link-loss fallback so a landing is still reported if the link drops.
            if (state.flightState > FlightStates.Noseover && !landingSpoken) {
                val location =
                    if (state.gpsStatus == SensorHealth.Ok)
                        " ${vector.distance} meters ${vector.ordinal} of launch point."
                    else ", location unknown."
                val predictedTimeToGround =
                    if (descentRate > minDescentRateForPrediction)
                        state.altitudeAboveGroundLevel / descentRate
                    else Float.MAX_VALUE
                val landingImminent =
                    state.altitudeAboveGroundLevel < landingAltitudeThreshold ||
                            predictedTimeToGround < landingLeadTimeSeconds
                val linkStale = messageAge >= landingLinkLossTimeout

                when {
                    // Link lost while the rocket was already near landing — the last-known
                    // position is effectively the landing site, so treat it as a final landing.
                    linkStale && landingImminent -> {
                        landingSpoken = true
                        textToSpeech?.speak(
                            "Landing. Last known$location",
                            TextToSpeech.QUEUE_FLUSH, null, null
                        )
                    }
                    // Link lost well above the ground — likely a transient dropout, not a
                    // landing. Announce it once but do not latch, so descent warnings and the
                    // real landing call resume if the signal comes back.
                    linkStale -> {
                        if (!signalLossSpoken) {
                            signalLossSpoken = true
                            textToSpeech?.speak(
                                "Signal lost. Last known$location",
                                TextToSpeech.QUEUE_FLUSH, null, null
                            )
                        }
                    }
                    // Link healthy: re-arm the dropout notice, then handle landing / warnings.
                    else -> {
                        signalLossSpoken = false
                        when {
                            landingImminent -> {
                                landingSpoken = true
                                textToSpeech?.speak(
                                    "Landing$location",
                                    TextToSpeech.QUEUE_FLUSH, null, null
                                )
                            }
                            descentRate >= freefallDescentRate &&
                                    now - lastDescentWarningTime >= descentWarningIntervalMillis -> {
                                lastDescentWarningTime = now
                                textToSpeech?.speak(
                                    "Descent warning, ${descentRate.toInt()} meters per second${
                                        if (state.gpsStatus == SensorHealth.Ok)
                                            " ${vector.distance} meters ${vector.ordinal} of launch point."
                                        else ""
                                    }",
                                    TextToSpeech.QUEUE_FLUSH, null, null
                                )
                            }
                        }
                    }
                }
            }

            // Physical deployment detections.
            if (state.flightState >= FlightStates.DroguePrimaryEvent && !drogueDeploySpoken && state.drogueDeployDetected) {
                drogueDeploySpoken = true
                textToSpeech?.speak("Drogue deployed.", TextToSpeech.QUEUE_ADD, null, null)
            }
            if (state.flightState >= FlightStates.MainPrimaryEvent && !mainDeploySpoken && state.mainDeployDetected) {
                mainDeploySpoken = true
                drogueDeploySpoken = true
                textToSpeech?.speak("Main deployed.", TextToSpeech.QUEUE_ADD, null, null)
            }
        }
    }
}

// ── Drawer content ────────────────────────────────────────────────────────────

/**
 * Renders the navigation drawer menu items, adapting to Bluetooth connection
 * state, armed state, and whether a locator is active.
 */
@Composable
private fun AppDrawerContent(
    bluetoothConnectionState: BluetoothConnectionState,
    armedState: Boolean,
    locatorActive: Boolean,
    onNavigate: (NavDestination) -> Unit,
) {
    val items = buildList {
        add(DrawerItem(R.string.application_settings, R.drawable.settings_applications, NavDestination.AppSettings))
        add(DrawerItem(R.string.download_map, R.drawable.navigation, NavDestination.DownloadMap))
        if (bluetoothConnectionState == BluetoothConnectionState.Ready)
            add(DrawerItem(R.string.receiver_settings, R.drawable.radio, NavDestination.ReceiverSettings))
        if (locatorActive && !armedState) {
            add(DrawerItem(R.string.locator_settings, R.drawable.navigation, NavDestination.LocatorSettings))
            add(DrawerItem(R.string.flight_profiles, R.drawable.u_turn_right, NavDestination.FlightProfiles))
        }
        if (locatorActive && armedState)
            add(DrawerItem(R.string.deployment_test, R.drawable.bomb, NavDestination.DeploymentTest))
    }

    Column(modifier = Modifier.padding(0.dp)) {
        items.forEach { item ->
            NavigationDrawerItem(
                label = { Text(stringResource(item.labelRes), style = typography.titleLarge) },
                icon = { Icon(painterResource(item.iconRes), contentDescription = stringResource(item.labelRes)) },
                selected = false,
                onClick = { onNavigate(item.screen) }
            )
        }
    }
}

// ── Map with overlays ─────────────────────────────────────────────────────────

/**
 * Renders the satellite map plus all overlaid UI: compass, scale bar, map camera
 * controller, arm/zoom controls, Bluetooth status, GPS lock warnings, and the
 * draggable locator stats panel.
 */
@Composable
private fun MapWithOverlays(
    trackerLocation: Location,
    rocketState: RocketState,
    armedState: Boolean,
    receiverDeviceName: String,
    locatorConfig: LocatorConfig,
    locatorArmedMessageState: LocatorMessageState,
    bluetoothConnectionState: BluetoothConnectionState,
    locatorGPSLock: Boolean,
    onRescan: () -> Unit,
    isMapLoaded: Boolean,
    onMapLoaded: () -> Unit,
    hasCompass: Boolean,
    azimuth: Float,
    lastAzimuth: Float,
    lastPreLaunchMessageAge: Long,
    distanceToLocator: Int,
    viewModel: RocketViewModel,
    scaffoldSize: IntSize,
    textToSpeech: TextToSpeech?,
    flightPath: List<Triple<Double, Double, Float>>,
    onMenuClick: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val isFlightPathRecording = viewModel.isFlightPathRecording.collectAsState().value
        // Password gating: only a recognised locator may be armed from the app.
        val locatorRecognized = viewModel.locatorRecognized.collectAsState().value
        var autoTargetMode by remember { mutableStateOf(true) }
        var autoZoomMode by remember { mutableStateOf(true) }
        var compassEnabled by remember { mutableStateOf(true) }
        var showControls by remember { mutableStateOf(false) }
        // Hoisted so a tap anywhere on the map (onMapClick) can collapse the
        // status-panel action dropdown, not just the timeout or a second panel tap.
        var actionsExpanded by remember { mutableStateOf(false) }
        // Handle used only for pure camera queries (getCameraForLatLngBounds) — never to
        // move the camera from the controller.
        var mapLibre by remember { mutableStateOf<MapLibreMap?>(null) }
        var is3DView by remember { mutableStateOf(false) }

        val context = LocalContext.current
        // Live map uses whichever satellite provider the user selected in the download
        // screen, so downloaded offline regions (same source) render here.
        val styleJson = remember { MapProviderPrefs.get(context).styleJson(context) }
        val locatorLatLng = LatLng(rocketState.latitude, rocketState.longitude)
        val rocketFresh = lastPreLaunchMessageAge < messageTimeout
        val cameraState = remember {
            MapLibreCameraState(
                CamPos(
                    target = LatLng(trackerLocation.latitude, trackerLocation.longitude),
                    zoom = 12f,
                    tilt = 0f,
                    bearing = azimuth,
                )
            )
        }

        // One-shot recenter: the map now renders before the first GPS fix (initial
        // camera falls back to 0,0), and auto-target only kicks in once the rocket
        // has GPS.  So as soon as the phone's own position is known — and while the
        // rocket still has none — snap the camera to the phone once, mirroring the
        // pre-render behaviour without reintroducing the blank-screen wait.
        var didInitialCenter by remember { mutableStateOf(false) }
        LaunchedEffect(isMapLoaded, trackerLocation.latitude, trackerLocation.longitude) {
            val trackerValid = trackerLocation.latitude != 0.0 || trackerLocation.longitude != 0.0
            val rocketValid = rocketState.latitude != 0.0 || rocketState.longitude != 0.0
            if (!didInitialCenter && isMapLoaded && trackerValid && !rocketValid) {
                cameraState.position = CamPos(
                    target = LatLng(trackerLocation.latitude, trackerLocation.longitude),
                    zoom = 12f,
                    tilt = 0f,
                    bearing = azimuth,
                )
                didInitialCenter = true
            }
        }

        MapLibreMapView(
            modifier = Modifier.fillMaxSize(),
            styleJson = styleJson,
            cameraState = cameraState,
            rocketLatLng = locatorLatLng,
            rocketFresh = rocketFresh,
            accuracyRadiusM = rocketState.hacc.toDouble(),
            // Altitude is carried through, not dropped: it drives the 3D
            // altitude curtain under the track.
            flightPath = flightPath.map { (lat, lng, agl) -> PathPoint(lat, lng, agl) },
            userLocation = trackerLocation,
            onMapLoaded = onMapLoaded,
            onMapClick = {
                showControls = !showControls
                actionsExpanded = false
            },
            onMapReady = { mapLibre = it },
            onSizeChanged = { },
        )

        MapCameraController(
            map = mapLibre,
            trackerLocation = trackerLocation,
            rocketState = rocketState,
            cameraState = cameraState,
            isMapLoaded = isMapLoaded,
            hasCompass = hasCompass,
            compassEnabled = compassEnabled,
            azimuth = azimuth,
            lastAzimuth = lastAzimuth,
            autoTargetMode = autoTargetMode,
            autoZoomMode = autoZoomMode,
            is3DView = is3DView,
            onBearingUpdate = { viewModel.updateLastHandheldDeviceAzimuth(it) },
        )

        // Compass: positioned above the scale bar at bottom-left, 8 dp from left edge
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = 8.dp, y = (-96).dp)
                .size(60.dp)
                .background(mapOverlayBg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.compass),
                contentDescription = "Compass",
                modifier = Modifier
                    .size(48.dp)
                    .rotate(-azimuth),
            )
        }
        val density = LocalDensity.current
        val scaleBarMaxWidth = with(density) {
            // Cap at 42 % of scaffold width minus the 8 dp left offset and 8 dp right clearance
            (scaffoldSize.width * 45 / 100 - 8.dp.roundToPx() - 8.dp.roundToPx()).toDp()
                .coerceAtMost(192.dp)
                .coerceAtLeast(48.dp)
        }
        GenericScaleBar(
            cameraState = cameraState,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = 8.dp, y = (-36).dp),
            width = scaleBarMaxWidth,
            barColor = MaterialTheme.colorScheme.primary,
            textColor = MaterialTheme.colorScheme.secondary,
        )

        if (lastPreLaunchMessageAge < messageTimeout) {
            PulsingText(
                modifier = modifier
                    .align(Alignment.Center),
                text = (if (!armedState) "Disarmed" else "") +
                        (if (!armedState && !locatorGPSLock) "\n" else "") +
                        (if (!locatorGPSLock) "No GPS" else ""),
                color = Color.White,
                textAlign = TextAlign.Center,
                style = typography.displayLarge,
            )
        } else if (bluetoothConnectionState == BluetoothConnectionState.Ready) {
            PulsingText(
                modifier = modifier
                    .align(Alignment.Center),
                text = "No Locator",
                color = Color.White,
                textAlign = TextAlign.Center,
                style = typography.displayLarge,
            )
        }

        // Top Row: menu button | status (centered) | view controls — 8 dp border everywhere
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Menu button — primaryContainer background + onPrimaryContainer icon matches
            // the original ExtendedFloatingActionButton appearance.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onMenuClick, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Menu, contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            // Status area — centered in available space
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                MapControlsColumn(
                    bluetoothConnectionState = bluetoothConnectionState,
                    lastPreLaunchMessageAge = lastPreLaunchMessageAge,
                    rocketState = rocketState,
                    receiverDeviceName = receiverDeviceName,
                    locatorConfig = locatorConfig,
                    armedState = armedState,
                    locatorArmedMessageState = locatorArmedMessageState,
                    onToggleArmed = { viewModel.updateArmedState() },
                    onRescan = onRescan,
                    textToSpeech = textToSpeech,
                    locatorRecognized = locatorRecognized,
                    actionsExpanded = actionsExpanded,
                    onActionsExpandedChange = { actionsExpanded = it },
                    modifier = Modifier,
                )
            }
            // View-mode and auto-framing controls
            Column(
                modifier = Modifier
                    .background(mapOverlayBg, RoundedCornerShape(12.dp))
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(
                    onClick = { is3DView = !is3DView },
                    modifier = Modifier.size(48.dp),
                ) {
                    Image(
                        painter = painterResource(
                            id = if (is3DView) R.drawable.ic_view_3d else R.drawable.ic_view_2d
                        ),
                        contentDescription = if (is3DView) "Switch to 2D view" else "Switch to 3D view",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(
                    onClick = { autoTargetMode = !autoTargetMode },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = if (autoTargetMode) "Disable auto-center" else "Enable auto-center",
                        tint = if (autoTargetMode) Color.White else Color.White.copy(alpha = 0.35f),
                    )
                }
                IconButton(
                    onClick = { autoZoomMode = !autoZoomMode },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomOutMap,
                        contentDescription = if (autoZoomMode) "Disable auto-zoom" else "Enable auto-zoom",
                        tint = if (autoZoomMode) Color.White else Color.White.copy(alpha = 0.35f),
                    )
                }
                IconButton(
                    onClick = { compassEnabled = !compassEnabled },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = if (compassEnabled) "Disable magnetic orientation" else "Enable magnetic orientation",
                        tint = if (compassEnabled) Color.White else Color.White.copy(alpha = 0.35f),
                    )
                }
                IconButton(
                    onClick = {
                        if (isFlightPathRecording) viewModel.stopFlightPathRecording()
                        else viewModel.startFlightPathRecording()
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (isFlightPathRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = if (isFlightPathRecording) "Stop recording flight path" else "Start recording flight path",
                        tint = if (isFlightPathRecording) Color.Red else Color.White.copy(alpha = 0.35f),
                    )
                }
                IconButton(
                    onClick = { viewModel.resetFlightPath() },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset flight path",
                        tint = Color.White,
                    )
                }
            }
        }

        if (lastPreLaunchMessageAge < messageTimeout) {
            LocatorStats(
                rocketState = rocketState,
                armedState = armedState,
                distanceToLocator = distanceToLocator,
                locatorConfig = locatorConfig,
                viewModel = viewModel,
                scaffoldSize = scaffoldSize,
                textToSpeech = textToSpeech,
                modifier = modifier,
            )
        }
    }
}

// ── Map camera controller ─────────────────────────────────────────────────────

/**
 * Headless composable that keeps the map camera smoothly framing both the tracker
 * and the rocket using a Kalman filter. Backs off for 5 s after a user gesture
 * so that manual panning is not immediately overridden.
 *
 * In 3D mode:
 * - Tilt is merged into the final CameraPosition so only one native map move occurs per frame.
 * - Tilt resumes immediately after a pan/zoom gesture while target/zoom still defer 5 s.
 * - Zoom is corrected for perspective foreshortening at high tilt.
 * - A separate 5 s window lets the user manually tilt the map without being overridden.
 */
@Composable
private fun MapCameraController(
    map: MapLibreMap?,
    trackerLocation: Location,
    rocketState: RocketState,
    cameraState: MapLibreCameraState,
    isMapLoaded: Boolean,
    hasCompass: Boolean,
    compassEnabled: Boolean,
    azimuth: Float,
    lastAzimuth: Float,
    autoTargetMode: Boolean,
    autoZoomMode: Boolean,
    is3DView: Boolean,
    onBearingUpdate: (Float) -> Unit,
) {
    val kalmanGainTarget  = 0.1f
    val kalmanGainZoom    = 0.05f
    val kalmanGainTilt    = 0.05f
    val kalmanGainBearing = 0.01f

    var lastUserGestureTime by remember { mutableLongStateOf(0L) }
    var lastUserTiltTime by remember { mutableLongStateOf(0L) }
    var smoothedTarget by remember { mutableStateOf(LatLng(0.0, 0.0)) }
    var smoothedZoom by remember { mutableFloatStateOf(12f) }
    var smoothedTilt by remember { mutableFloatStateOf(0f) }
    // Tracks the tilt we last applied programmatically so we can detect user tilt gestures.
    var lastAppliedTilt by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        // Include position so the flow re-emits on every camera frame, not just when the
        // reason first changes. This keeps lastUserGestureTime rolling forward for the
        // entire duration of a continuous gesture (long pan, slow pinch, etc.) so the
        // 5 s recovery window always starts from the last frame of user input.
        // Keyed on isGesturing, not moveStartedReason: our own programmatic
        // moves fire onCameraMoveStarted with REASON_API_ANIMATION and used to
        // overwrite the user's REASON_API_GESTURE within milliseconds, so this
        // collector frequently never saw the gesture at all.
        snapshotFlow { cameraState.isGesturing to cameraState.position }
            .collect { (gesturing, _) ->
                // ONLY isGesturing. moveStartedReason must not be consulted here:
                // it is stale-sticky — a real gesture sets it to REASON_GESTURE and
                // nothing clears it — while this flow re-emits on every camera
                // position change, including our own writes. That let a
                // long-finished gesture re-arm the backoff one frame after it was
                // cleared, so the controller got a single frame of camera motion
                // per 5 s cycle: tilt froze part-way, each isolated write showed
                // as a jump, and auto-zoom crawled. isGesturing is latched on a
                // real gesture and cleared on camera idle, so it cannot go stale.
                if (gesturing) {
                    lastUserGestureTime = System.currentTimeMillis()
                }
            }
    }

    // Tapping a camera control is an explicit command, not something to defer to.
    //
    // The gesture backoff exists so the auto-camera doesn't fight your fingers,
    // but its early-return blocks EVERY camera change — so after a manual
    // pan/zoom/rotate, hitting 3D (or auto-center, auto-zoom, compass) did
    // nothing at all until the 5 s window expired. targetTilt is gated a second
    // time by userTiltRecent, so both windows have to be cleared for the tilt to
    // take effect immediately.
    LaunchedEffect(is3DView, autoTargetMode, autoZoomMode, compassEnabled) {
        lastUserGestureTime = 0L
        lastUserTiltTime = 0L
    }

    // Fix #4: detect a user tilt gesture by watching the native map's tilt diverge from
    // the last value we applied programmatically during an active gesture.
    LaunchedEffect(Unit) {
        snapshotFlow { cameraState.position.tilt }
            .collect { nativeTilt ->
                if (cameraState.isGesturing &&
                    abs(nativeTilt - lastAppliedTilt) > 2f) {
                    lastUserTiltTime = System.currentTimeMillis()
                }
            }
    }

    if (!isMapLoaded) return

    val now = System.currentTimeMillis()
    val userGestureRecent = now - lastUserGestureTime <= 5000
    val userTiltRecent    = now - lastUserTiltTime   <= 5000

    // Compute the target tilt for this frame.
    // Google Maps tilt: 0° = straight down, 67.5° ≈ horizon (SDK maximum at most zoom levels).
    // Start at 60° so the horizon is always in view; rise to 67.5° as the rocket climbs.
    val targetTilt = when {
        is3DView && !userTiltRecent -> (60f + rocketState.altitudeAboveGroundLevel / 30f).coerceIn(60f, 67.5f)
        is3DView -> cameraState.position.tilt  // preserve user's manual tilt
        else -> 0f
    }
    // During any gesture (pan, zoom, or tilt) sync smoothedTilt from the native camera so
    // that Kalman resumes from the actual position when the gesture window expires.
    // Outside gestures, filter smoothedTilt toward targetTilt.
    if (userGestureRecent) {
        smoothedTarget = cameraState.position.target
        smoothedZoom   = cameraState.position.zoom + (smoothedTilt / 90f * 1.5f)
        smoothedTilt   = cameraState.position.tilt
        onBearingUpdate(cameraState.position.bearing)
        return   // leave the camera untouched so gestures work freely in both 2D and 3D
    }
    if (userTiltRecent) {
        smoothedTilt = cameraState.position.tilt
    } else {
        smoothedTilt += (targetTilt - smoothedTilt) * kalmanGainTilt
    }
    // zoomCorrection is driven by smoothedTilt so it fades in/out with the tilt transition
    // rather than snapping to 0 the instant is3DView flips.
    val zoomCorrection = smoothedTilt / 90f * 1.5f

    // No recent gesture — safe to probe/compute ideal bounds.
    // Only include the rocket if it has a valid GPS fix — excluding 0,0 prevents the bounds
    // from spanning to null-island and pulling the Kalman zoom filter toward world level.
    // A coordinate is usable only if finite and in range.  LatLngBounds.build()
    // throws IllegalArgumentException ("NaN > NaN") if any included point is NaN,
    // which crashes Compose during recomposition — so NaN must be filtered here,
    // not just the 0,0 null-island case.
    fun validLatLng(lat: Double, lon: Double) =
        lat.isFinite() && lon.isFinite() &&
        abs(lat) <= 90.0 && abs(lon) <= 180.0 &&
        (lat != 0.0 || lon != 0.0)

    val rocketHasGps  = validLatLng(rocketState.latitude, rocketState.longitude)
    val trackerHasGps = validLatLng(trackerLocation.latitude, trackerLocation.longitude)

    val (autoTarget, autoZoom) = if ((autoTargetMode || autoZoomMode) && rocketHasGps) {
        val rocketLatLng = LatLng(rocketState.latitude, rocketState.longitude)
        // Bounds need BOTH points. MapLibre's LatLngBounds.Builder — unlike the Google Maps
        // builder it replaced — throws InvalidLatLngBoundsException from build() when only one
        // point was included. The tracker has no fix for the first moments after the map is
        // re-created (e.g. returning here from flight profiles), so that is a routine state,
        // not an edge case: with only the rocket known, centre on it and don't touch the zoom.
        //
        // Ask the SDK to compute the framing. getCameraForLatLngBounds is a PURE query — it
        // returns a CameraPosition without touching the map — so unlike the old
        // moveCamera(newLatLngBounds(...)) "probe" it cannot move the camera mid-frame (that
        // was the auto-zoom wobble: two native moves per frame, drawn by the continuously
        // rendering GL thread).
        //
        // It also beats hand-rolled Mercator math, which has to get the pixel-density and
        // tile-size conventions exactly right to land the fit — and silently mis-frames when
        // it doesn't.
        //
        // Fit NORTH-UP and FLAT (bearing 0, tilt 0), matching what Google's newLatLngBounds
        // always did. The default overload fits for the *current* bearing/tilt, which zooms
        // out further — a rotated box needs a bigger viewport, and with the compass on the
        // bearing is arbitrary. Worse, tilt is already compensated below (zoomCorrection),
        // so letting the SDK account for it too corrects twice and over-zooms out.
        // Cached on the inputs. This is a native JNI query, and the controller
        // re-runs every display frame (~120/s measured), but the answer only
        // changes when one of the two positions does — roughly once a second.
        val cam = remember(
            rocketState.latitude, rocketState.longitude,
            trackerLocation.latitude, trackerLocation.longitude,
            trackerHasGps, map,
        ) {
            if (!trackerHasGps) null
            else {
                val bounds = LatLngBounds.Builder()
                    .include(rocketLatLng)
                    .include(LatLng(trackerLocation.latitude, trackerLocation.longitude))
                    .build()
                map?.getCameraForLatLngBounds(bounds, intArrayOf(300, 300, 300, 300), 0.0, 0.0)
            }
        }
        if (trackerHasGps) Pair(cam?.target, cam?.zoom?.toFloat())
        else Pair(rocketLatLng, null)
    } else {
        Pair(null, null)
    }

    // Drive the Kalman state (smoothedTarget / smoothedZoom) toward the ideal auto values.
    // Use the remembered smoothed values as the Kalman base — not cameraPositionState.position
    // — so that the zoom correction applied at the end doesn't feed back into the next frame.
    smoothedTarget = if (autoTargetMode && autoTarget != null)
        LatLng(
            smoothedTarget.latitude  + (autoTarget.latitude  - smoothedTarget.latitude)  * kalmanGainTarget,
            smoothedTarget.longitude + (autoTarget.longitude - smoothedTarget.longitude) * kalmanGainTarget,
        )
    else smoothedTarget

    smoothedZoom = if (autoZoomMode && autoZoom != null)
        smoothedZoom + (autoZoom - smoothedZoom) * kalmanGainZoom
    else smoothedZoom

    val effectiveCompass = hasCompass && compassEnabled
    if (effectiveCompass) {
        val delta = ((azimuth - lastAzimuth + 540f) % 360f) - 180f
        onBearingUpdate((lastAzimuth + delta * kalmanGainBearing + 360f) % 360f)
    }
    val bearing = if (effectiveCompass) lastAzimuth else cameraState.position.bearing

    lastAppliedTilt = smoothedTilt
    cameraState.position = CamPos(smoothedTarget, smoothedZoom - zoomCorrection, smoothedTilt, bearing)
}

// ── Generic scale bar ─────────────────────────────────────────────────────────

/**
 * A map scale bar computed from the camera zoom level and latitude using Mercator math.
 * Picks the largest "nice" distance (1/2/5 × 10^n) that fits within [width].
 */
@Composable
private fun GenericScaleBar(
    cameraState: MapLibreCameraState,
    modifier: Modifier = Modifier,
    width: Dp = 192.dp,
    barColor: Color = MaterialTheme.colorScheme.primary,
    textColor: Color = MaterialTheme.colorScheme.secondary,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { width.toPx() }

    val zoom = cameraState.position.zoom
    val lat  = cameraState.position.target.latitude
    // 78271.516… = half the 256-px-tile constant: MapLibre reports zoom in the 512-px-tile
    // convention, so its metres/pixel at zoom z is half of Google Maps' at the same z.
    val metersPerPx = 78271.51696 * cos(lat * PI / 180.0) / 2.0.pow(zoom.toDouble())
    val totalMeters = metersPerPx * widthPx

    val niceDistances = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500,
        1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000, 500000)
    val niceDistM = niceDistances.lastOrNull { it.toDouble() <= totalMeters } ?: niceDistances.first()
    val barFraction = (niceDistM.toDouble() / totalMeters).toFloat().coerceIn(0.05f, 1f)
    val label = if (niceDistM >= 1000) "${niceDistM / 1000} km" else "$niceDistM m"

    Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
        Canvas(modifier = Modifier.width(width).height(8.dp)) {
            val barW  = size.width * barFraction
            val y     = size.height
            val stroke = 2.dp.toPx()
            drawLine(barColor, Offset(0f, y), Offset(barW, y), stroke)
            drawLine(barColor, Offset(0f, 0f), Offset(0f, y), stroke)
            drawLine(barColor, Offset(barW, 0f), Offset(barW, y), stroke)
        }
    }
}

// ── Map controls ──────────────────────────────────────────────────────────────

/**
 * Overlay column (shown on map tap) containing arm/disarm, auto-center,
 * and auto-zoom toggle buttons.
 */
@Composable
private fun MapControlsColumn(
    bluetoothConnectionState: BluetoothConnectionState,
    lastPreLaunchMessageAge: Long,
    receiverDeviceName: String,
    locatorConfig: LocatorConfig,
    rocketState: RocketState,
    armedState: Boolean,
    locatorArmedMessageState: LocatorMessageState,
    onToggleArmed: () -> Unit,
    onRescan: () -> Unit,
    textToSpeech: TextToSpeech?,
    locatorRecognized: Boolean = true,
    actionsExpanded: Boolean,
    onActionsExpandedChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        val iconSize = 20.dp
        val iconBoxWidth = 40.dp   // wide enough for rocket icon + satellite superscript
        val nameWidth = 190.dp     // fits DEVICE_NAME_LENGTH characters at body size
        val batterySize = 20.dp
        val batteryBoxWidth = 24.dp
        // Total width of a status row; the action buttons match it so revealing the
        // dropdown never widens the panel beyond its collapsed size.
        val panelContentWidth = iconBoxWidth + nameWidth + batteryBoxWidth

        // ── Action panel expand/collapse ──────────────────────────────────────
        // The status rows are small on purpose, so instead of hunting for a fine
        // tap target the user taps anywhere on the panel to drop down large
        // "Rescan" and "Arm"/"Disarm" buttons.  It auto-collapses after a short
        // idle time, and the caller collapses it too when the map is tapped
        // (expanded state is hoisted).
        LaunchedEffect(actionsExpanded) {
            if (actionsExpanded) {
                delay(actionPanelCollapseDelay)
                onActionsExpandedChange(false)
            }
        }

        // ── Arm/disarm feedback state ─────────────────────────────────────────
        // While a command is in flight the rocket icon blinks toward its target
        // colour (green when arming, white when disarming) until armedState
        // reflects the change or the 2 s timeout elapses.
        var armCommandPending by remember { mutableStateOf(false) }
        LaunchedEffect(armCommandPending) {
            if (armCommandPending) { delay(2000L); armCommandPending = false }
        }
        // Announce arm-state changes via TTS and clear the pending blink.
        val armedStateText = if (armedState)
            stringResource(R.string.armed_state_armed)
        else
            stringResource(R.string.armed_state_disarmed)
        LaunchedEffect(armedState) {
            textToSpeech?.speak(armedStateText, TextToSpeech.QUEUE_FLUSH, null, null)
            armCommandPending = false   // acknowledgement received
        }
        // Continuous blink animation — applied only while a command is pending.
        val blinkTransition = rememberInfiniteTransition(label = "rocketBlink")
        val blinkAlpha by blinkTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(450, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "rocketBlinkAlpha",
        )
        val rocketIconTint = when {
            armCommandPending -> if (!armedState) Color.Green else Color.White
            armedState        -> Color.Green
            else              -> Color.White
        }
        val rocketIconAlpha = if (armCommandPending) blinkAlpha else 1f
        // Arm/disarm is only accepted once the previous command has settled and
        // the locator is recognised (password-verified).
        val armActionEnabled = (locatorArmedMessageState == LocatorMessageState.Idle ||
            locatorArmedMessageState == LocatorMessageState.AckUpdated) && locatorRecognized

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(mapOverlayBg)
                .padding(8.dp)
                .clickable { onActionsExpandedChange(!actionsExpanded) }
        ) {
            // Row 1: radio icon | receiver name | receiver battery
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(iconBoxWidth), contentAlignment = Alignment.CenterStart) {
                    Icon(
                        painter = painterResource(R.drawable.radio),
                        contentDescription = null,
                        modifier = Modifier.size(iconSize)
                    )
                }
                Text(
                    text = when (bluetoothConnectionState) {
                        BluetoothConnectionState.Starting,
                        BluetoothConnectionState.Enabling -> "Enabling bluetooth"
                        BluetoothConnectionState.NotEnabled -> "BT not enabled"
                        BluetoothConnectionState.NotSupported -> "BT not supported"
                        BluetoothConnectionState.Enabled -> "Bluetooth enabled"
                        BluetoothConnectionState.AssociateStart -> "Scanning"
                        BluetoothConnectionState.LocationDisabled -> "Enable location"
                        BluetoothConnectionState.PairingFailed -> "Pairing failed"
                        BluetoothConnectionState.NoDevicesAvailable -> "Waiting for receiver"
                        BluetoothConnectionState.DevicesFound -> "Receivers found"
                        BluetoothConnectionState.Connected -> "Receiver connected"
                        BluetoothConnectionState.Ready -> receiverDeviceName
                        BluetoothConnectionState.Disconnected -> "Receiver disconnect"
                        else -> "Undefined state"
                    },
                    modifier = Modifier.width(nameWidth),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                val receiverBattery = rocketState.receiverBatteryLevel.coerceIn(0..7)
                Box(modifier = Modifier.width(batteryBoxWidth), contentAlignment = Alignment.CenterStart) {
                    if (lastPreLaunchMessageAge < messageTimeout) {
                        Icon(
                            painter = painterResource(
                                context.resources.getIdentifier("battery_${receiverBattery}_bar", "drawable", context.packageName)
                            ),
                            contentDescription = stringResource(
                                context.resources.getIdentifier("battery_${receiverBattery}_bar", "string", context.packageName)
                            ),
                            modifier = Modifier.size(batterySize)
                        )
                    }
                }
            }

            // Row 2: rocket icon + satellite count | locator name | locator battery
            // Arm/disarm is handled by double-tapping anywhere on the status panel background.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(iconBoxWidth), contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.rocket_md),
                            contentDescription = stringResource(R.string.locator_satellites),
                            tint = rocketIconTint,
                            modifier = Modifier
                                .size(iconSize)
                                .alpha(rocketIconAlpha)
                        )
                        if (lastPreLaunchMessageAge < messageTimeout) {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(
                                        style = SpanStyle(
                                            fontSize = 10.sp,
                                            baselineShift = BaselineShift.Superscript
                                        )
                                    ) { append(rocketState.satellites.toString()) }
                                }
                            )
                        }
                    }
                }
                Text(
                    text = if (lastPreLaunchMessageAge < messageTimeout) locatorConfig.deviceName else "",
                    modifier = Modifier.width(nameWidth),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                val locatorBattery = rocketState.locatorBatteryLevel.coerceIn(0..7)
                Box(modifier = Modifier.width(batteryBoxWidth), contentAlignment = Alignment.CenterStart) {
                    if (lastPreLaunchMessageAge < messageTimeout) {
                        Icon(
                            painter = painterResource(
                                context.resources.getIdentifier("battery_${locatorBattery}_bar", "drawable", context.packageName)
                            ),
                            contentDescription = stringResource(
                                context.resources.getIdentifier("battery_${locatorBattery}_bar", "string", context.packageName)
                            ),
                            modifier = Modifier.size(batterySize)
                        )
                    }
                }
            }
            // Row 3: signal icon | RSSI value
            if (lastPreLaunchMessageAge < messageTimeout) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.width(iconBoxWidth), contentAlignment = Alignment.CenterStart) {
                        Icon(
                            imageVector = Icons.Default.SignalCellularAlt,
                            contentDescription = "Signal strength",
                            modifier = Modifier.size(iconSize),
                            tint = rssiColor(rocketState.rssi)
                        )
                    }
                    Text(
                        text = "${rocketState.rssi} dBm",
                        modifier = Modifier.width(nameWidth),
                        color = rssiColor(rocketState.rssi),
                        maxLines = 1,
                    )
                }
            }

            // ── Descending action buttons ─────────────────────────────────────
            // Large, clearly labelled touch targets revealed by tapping the panel.
            AnimatedVisibility(
                visible = actionsExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .width(panelContentWidth)
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            onActionsExpandedChange(false)
                            onRescan()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text(stringResource(R.string.action_rescan))
                    }
                    val disarming = armedState
                    Button(
                        onClick = {
                            onActionsExpandedChange(false)
                            // Mirror the locator rule: a disarm is only honoured while
                            // the rocket is waiting for launch or has landed.  Block it
                            // in the app during flight so we don't send a request the
                            // locator would silently ignore, and say why with an
                            // auto-dismissing popup.
                            val inFlight = rocketState.flightState != FlightStates.WaitingLaunch &&
                                rocketState.flightState != FlightStates.Landed
                            if (disarming && inFlight) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.disarm_in_flight_blocked),
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                BluetoothManagerRepository.updateLocatorArmedMessageState(
                                    LocatorMessageState.SendRequested
                                )
                                onToggleArmed()
                                armCommandPending = true
                            }
                        },
                        enabled = armActionEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (disarming)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary,
                            contentColor = if (disarming)
                                MaterialTheme.colorScheme.onError
                            else
                                MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text(
                            if (disarming) stringResource(R.string.action_disarm)
                            else stringResource(R.string.action_arm)
                        )
                    }
                }
            }
            } // end background Column
    }
}

// ── Locator stats overlay ─────────────────────────────────────────────────────

@Composable
fun LocatorStats(
    rocketState: RocketState,
    armedState: Boolean,
    distanceToLocator: Int,
    locatorConfig: LocatorConfig,
    viewModel: RocketViewModel,
    scaffoldSize: IntSize,
    textToSpeech: TextToSpeech?,
    modifier: Modifier
) {
    var columnWidth by remember { mutableStateOf(0) }
    var columnHeight by remember { mutableStateOf(0) }
    var locatorStatisticsOffset by remember { mutableStateOf(IntOffset(0, 0)) }
    // True when no saved position exists; resolved to lower-right once sizes are known.
    var needsDefaultPosition by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val saved = viewModel.locatorStatisticsOffset.value
        if (saved == IntOffset(0, 0)) needsDefaultPosition = true
        else locatorStatisticsOffset = saved
    }
    val density = LocalDensity.current
    // onSizeChanged sits inside padding(8.dp), so columnWidth/columnHeight are content-only.
    // Full visual panel size = content + 2 × panelPaddingPx on each axis.
    val marginPx = with(density) { 8.dp.roundToPx() }
    val panelPaddingPx = 2 * marginPx  // 8 dp padding on each side

    // Resolve the default position to lower-right as soon as both sizes are available.
    LaunchedEffect(needsDefaultPosition, scaffoldSize.width, scaffoldSize.height, columnWidth, columnHeight) {
        if (needsDefaultPosition && scaffoldSize.width > 0 && columnWidth > 0) {
            locatorStatisticsOffset = IntOffset(
                scaffoldSize.width - columnWidth - panelPaddingPx - marginPx,
                scaffoldSize.height - columnHeight - panelPaddingPx - marginPx,
            )
            needsDefaultPosition = false
        }
    }

    Column(
        modifier = modifier
            .offset { locatorStatisticsOffset }
            .clickable {
                if (armedState)
                    textToSpeech?.speak(
                        "${locatorConfig.deviceName}, ${rocketState.flightState}, ${rocketState.altitudeAboveGroundLevel} meters",
                        TextToSpeech.QUEUE_FLUSH, null, null
                    )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // Floor the max bound at marginPx: while the scaffold or column is
                    // being (re)measured the available extent can go negative, and
                    // coerceIn(min, max) throws when max < min (crash on returning to
                    // the map screen — see onSizeChanged below).
                    locatorStatisticsOffset = IntOffset(
                        (locatorStatisticsOffset.x + dragAmount.x.toInt()).coerceIn(marginPx, maxOf(marginPx, scaffoldSize.width - columnWidth - panelPaddingPx - marginPx)),
                        (locatorStatisticsOffset.y + dragAmount.y.toInt()).coerceIn(marginPx, maxOf(marginPx, scaffoldSize.height - columnHeight - panelPaddingPx - marginPx))
                    )
                }
            }
            .clip(RoundedCornerShape(16.dp))
            .background(mapOverlayBg)
            .padding(8.dp)
            .onSizeChanged { size ->
                columnWidth = size.width
                columnHeight = size.height
                if (!needsDefaultPosition) {
                    // Floor the max bound at marginPx so a not-yet-measured scaffold
                    // (width == 0 on return to the map screen) can't produce max < min,
                    // which makes coerceIn throw "Cannot coerce value to an empty range".
                    locatorStatisticsOffset = IntOffset(
                        locatorStatisticsOffset.x.coerceIn(marginPx, maxOf(marginPx, scaffoldSize.width - columnWidth - panelPaddingPx - marginPx)),
                        locatorStatisticsOffset.y.coerceIn(marginPx, maxOf(marginPx, scaffoldSize.height - columnHeight - panelPaddingPx - marginPx))
                    )
                }
            }
            .defaultMinSize(minWidth = 160.dp),
    ) {
        // ── Telemetry rows ────────────────────────────────────────────────────
        val dst = if (rocketState.latitude != 0.0)
            String.format(Locale.US, "%15d", distanceToLocator) + " m"
        else stringResource(R.string.unknown)
        Text(
            text = "Dist: $dst",
            color = if (rocketState.gpsStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
            style = TelemetryTextStyle,
        )
        Text(
            text = "AGL : ${String.format(Locale.US, "%15.1f", rocketState.altitudeAboveGroundLevel)} m",
            style = TelemetryTextStyle,
            color = if (rocketState.baroStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
        )
        if (armedState) {
            Text(
                text = "Spd: ${String.format(Locale.US, "%6.1f", rocketState.velocity)} m/s",
                style = TelemetryTextStyle,
            )
            val inc = rocketState.attitude.inclinationDeg()
            val hdg = rocketState.attitude.headingDeg()
            Text(
                text = "Inc:${String.format(Locale.US, "%5.1f", inc)}° Hdg:${String.format(Locale.US, "%5.1f", hdg)}°",
                style = TelemetryTextStyle,
                color = if (rocketState.imuStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = "Accl: ${String.format(Locale.US, "%5.1f", rocketState.accelerometer.x / G_FORCE_MS2)}" +
                        " ${String.format(Locale.US, "%5.1f", rocketState.accelerometer.y / G_FORCE_MS2)}" +
                        " ${String.format(Locale.US, "%5.1f", rocketState.accelerometer.z / G_FORCE_MS2)}",
                style = TelemetryTextStyle,
                color = if (rocketState.imuStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Gyro: ${String.format(Locale.US, "%5.0f", rocketState.gyro.x * RAD2DEG)}" +
                        " ${String.format(Locale.US, "%5.0f", rocketState.gyro.y * RAD2DEG)}" +
                        " ${String.format(Locale.US, "%5.0f", rocketState.gyro.z * RAD2DEG)}",
                style = TelemetryTextStyle,
                color = if (rocketState.imuStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
        }

        // ── Flight state (armed) or deployment channel config (disarmed) ──────
        if (armedState) {
            Text(
                text = when (rocketState.flightState) {
                    FlightStates.WaitingLaunch -> "Waiting For Launch"
                    FlightStates.Launched -> "Launched"
                    FlightStates.Burnout -> "Burnout"
                    FlightStates.Noseover -> "Noseover"
                    FlightStates.DroguePrimaryEvent -> "Drogue Primary"
                    FlightStates.DrogueBackupEvent -> "Drogue Backup"
                    FlightStates.MainPrimaryEvent -> "Main Primary"
                    FlightStates.MainBackupEvent -> "Main Backup"
                    FlightStates.Landed -> "Landed"
                    else -> ""
                },
                style = TelemetryTextStyle,
            )
        } else {
            listOf(
                ChannelConfig(locatorConfig.deploymentChannel1Mode, rocketState.deployChannel1Armed),
                ChannelConfig(locatorConfig.deploymentChannel2Mode, rocketState.deployChannel2Armed),
                ChannelConfig(locatorConfig.deploymentChannel3Mode, rocketState.deployChannel3Armed),
                ChannelConfig(locatorConfig.deploymentChannel4Mode, rocketState.deployChannel4Armed),
            ).forEachIndexed { index, (mode, isArmed) ->
                Text(
                    text = deployChannelText(index + 1, mode, locatorConfig),
                    style = TelemetryTextStyle,
                    color = if (isArmed) Color.Unspecified else MaterialTheme.colorScheme.error,
                )
            }
        }

        if (rocketState.rawLatitude != 0.0 && rocketState.rawLongitude != 0.0)
            Text(
                modifier = modifier,
                text = "${BigDecimal(rocketState.rawLatitude).setScale(6, RoundingMode.HALF_UP)}," +
                        BigDecimal(rocketState.rawLongitude).setScale(6, RoundingMode.HALF_UP).toString(),
                style = TelemetryTextStyle,
            )
    }

    LaunchedEffect(locatorStatisticsOffset) {
        viewModel.updateLocatorStatisticsOffset(locatorStatisticsOffset)
        viewModel.saveUserPreferences()
    }
}

// ── CameraPreview overlay helpers ────────────────────────────────────────────

private fun DrawScope.drawVelocityGauge(
    speed: Float,
    maxSpeed: Float,
    cx: Float,
    cy: Float,
    radius: Float,
    gaugeColor: Color,
    gaugeBgColor: Color,
    labelPaint: android.graphics.Paint,
    labelPx: Float,
    stroke: Float,
) {
    // Semicircle arc: starts at 210° (lower-left) sweeps 120° clockwise to 330° (lower-right)
    // 0 m/s at 210°, maxSpeed at 330°
    val startDeg = 210f
    val sweepDeg = 120f

    // Background arc
    drawArc(
        color = gaugeBgColor,
        startAngle = startDeg,
        sweepAngle = sweepDeg,
        useCenter = true,
        topLeft = Offset(cx - radius, cy - radius),
        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
    )

    // Tick marks at 0, 100, 200, 300, 400, 500 m/s
    val tickSpeeds = listOf(0f, 100f, 200f, 300f, 400f, 500f)
    for (tickSpeed in tickSpeeds) {
        val frac = (tickSpeed / maxSpeed).coerceIn(0f, 1f)
        val angleDeg = startDeg + frac * sweepDeg
        val angleRad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val innerR = radius * 0.75f
        val outerR = radius * 0.95f
        drawLine(
            color = gaugeColor,
            start = Offset(cx + cos(angleRad) * innerR, cy + sin(angleRad) * innerR),
            end   = Offset(cx + cos(angleRad) * outerR, cy + sin(angleRad) * outerR),
            strokeWidth = stroke * 1.5f,
        )
        val labelR = radius * 0.62f
        drawContext.canvas.nativeCanvas.drawText(
            "${tickSpeed.toInt()}",
            cx + cos(angleRad) * labelR,
            cy + sin(angleRad) * labelR + labelPx / 3f,
            labelPaint,
        )
    }

    // Coloured arc showing current speed
    val speedFrac = (speed / maxSpeed).coerceIn(0f, 1f)
    val speedColor = when {
        speedFrac < 0.5f -> gaugeColor
        speedFrac < 0.8f -> Color(0xFFFF9800)
        else             -> Color(0xFFF44336)
    }
    if (speedFrac > 0f) {
        drawArc(
            color = speedColor,
            startAngle = startDeg,
            sweepAngle = speedFrac * sweepDeg,
            useCenter = false,
            topLeft = Offset(cx - radius * 0.88f, cy - radius * 0.88f),
            size = androidx.compose.ui.geometry.Size(radius * 1.76f, radius * 1.76f),
            style = Stroke(width = stroke * 3f),
        )
    }

    // Needle
    val needleAngleDeg = startDeg + speedFrac * sweepDeg
    val needleRad = Math.toRadians(needleAngleDeg.toDouble()).toFloat()
    drawLine(
        color = Color.White,
        start = Offset(cx, cy),
        end   = Offset(cx + cos(needleRad) * radius * 0.7f, cy + sin(needleRad) * radius * 0.7f),
        strokeWidth = stroke * 1.5f,
    )
    drawCircle(Color.White, radius = stroke * 2.5f, center = Offset(cx, cy))

    // Speed text in centre
    val speedPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = labelPx * 1.4f
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(
        "${speed.toInt()} m/s", cx, cy + radius * 0.35f, speedPaint)
}

private fun DrawScope.drawRocket3D(
    attitude: Quaternionf,
    cx: Float,
    cy: Float,
    scale: Float,
    gaugeBgColor: Color,
    stroke: Float,
) {
    // Background circle
    drawCircle(gaugeBgColor, radius = scale * 1.1f, center = Offset(cx, cy))

    // Rotation matrix from q_bn (body-to-NED)
    val w = attitude.w; val qx = attitude.x; val qy = attitude.y; val qz = attitude.z
    // R columns: each column i is where body axis i lands in NED
    val r00 = 1f - 2f*(qy*qy + qz*qz);  val r01 = 2f*(qx*qy - w*qz);  val r02 = 2f*(qx*qz + w*qy)
    val r10 = 2f*(qx*qy + w*qz);         val r11 = 1f - 2f*(qx*qx + qz*qz); val r12 = 2f*(qy*qz - w*qx)
    val r20 = 2f*(qx*qz - w*qy);         val r21 = 2f*(qy*qz + w*qx);  val r22 = 1f - 2f*(qx*qx + qy*qy)

    // Project NED point (pN, pE, pD) to screen using isometric view
    // Screen X = (pE - pN) * cos30°, Screen Y = (pN + pE) * sin30° + pD
    // (East→right, North→upper-left, Down→down)
    val cos30 = cos(PI.toFloat() / 6f)
    val sin30 = sin(PI.toFloat() / 6f)
    fun project(bx: Float, by: Float, bz: Float): Offset {
        val pN = r00*bx + r01*by + r02*bz
        val pE = r10*bx + r11*by + r12*bz
        val pD = r20*bx + r21*by + r22*bz
        val sx = (pE - pN) * cos30
        val sy = (pN + pE) * sin30 + pD
        return Offset(cx + sx * scale, cy + sy * scale)
    }

    // Rocket geometry in body frame (x = nose axis)
    val nRing = 6
    val bodyR = 0.08f
    val noseX = 1.0f; val noseBaseX = 0.65f
    val tailX = -0.5f
    val finLen = 0.28f; val finChordX = -0.85f

    // Compute ring points
    fun ring(xPos: Float, radius: Float) = Array(nRing) { i ->
        val a = 2f * PI.toFloat() * i / nRing
        floatArrayOf(xPos, radius * cos(a), radius * sin(a))
    }
    val noseRing = ring(noseBaseX, bodyR)
    val tailRing = ring(tailX, bodyR)

    // Depth-sort: compute average NED depth (pD) per segment, draw back-to-front
    data class Seg(val p1: Offset, val p2: Offset, val color: Color, val width: Float, val depth: Float)
    val segs = mutableListOf<Seg>()

    fun addSeg(bx1: Float, by1: Float, bz1: Float, bx2: Float, by2: Float, bz2: Float,
               color: Color, width: Float) {
        val pD1 = r20*bx1 + r21*by1 + r22*bz1
        val pD2 = r20*bx2 + r21*by2 + r22*bz2
        segs.add(Seg(project(bx1, by1, bz1), project(bx2, by2, bz2), color, width, (pD1+pD2)*0.5f))
    }

    val bodyColor = Color(0xFFB0C8E8)
    val noseColor = Color(0xFFFF6060)
    val finColor  = Color(0xFF80A0C0)

    // Nose cone lines (noseRing → tip)
    val noseTip = floatArrayOf(noseX, 0f, 0f)
    for (i in 0 until nRing)
        addSeg(noseRing[i][0], noseRing[i][1], noseRing[i][2],
               noseTip[0], noseTip[1], noseTip[2], noseColor, stroke * 1.5f)

    // Nose ring
    for (i in 0 until nRing) {
        val j = (i + 1) % nRing
        addSeg(noseRing[i][0], noseRing[i][1], noseRing[i][2],
               noseRing[j][0], noseRing[j][1], noseRing[j][2], bodyColor, stroke)
    }

    // Longitudinal body lines
    for (i in 0 until nRing)
        addSeg(noseRing[i][0], noseRing[i][1], noseRing[i][2],
               tailRing[i][0], tailRing[i][1], tailRing[i][2], bodyColor, stroke)

    // Tail ring
    for (i in 0 until nRing) {
        val j = (i + 1) % nRing
        addSeg(tailRing[i][0], tailRing[i][1], tailRing[i][2],
               tailRing[j][0], tailRing[j][1], tailRing[j][2], bodyColor, stroke)
    }

    // 4 fins: ±y and ±z directions
    for ((fy, fz) in listOf(Pair(1f, 0f), Pair(-1f, 0f), Pair(0f, 1f), Pair(0f, -1f))) {
        val tipY = fy * (bodyR + finLen); val tipZ = fz * (bodyR + finLen)
        addSeg(tailX, fy*bodyR, fz*bodyR, finChordX, fy*bodyR, fz*bodyR, finColor, stroke * 1.2f)
        addSeg(finChordX, fy*bodyR, fz*bodyR, tailX, tipY, tipZ,          finColor, stroke * 1.2f)
        addSeg(tailX, tipY, tipZ, tailX, fy*bodyR, fz*bodyR,              finColor, stroke * 1.2f)
    }

    // Draw back-to-front (painter's algorithm)
    segs.sortByDescending { it.depth }
    for (seg in segs) {
        drawLine(color = seg.color, start = seg.p1, end = seg.p2, strokeWidth = seg.width,
            cap = StrokeCap.Round)
    }
}

// ── RSSI signal strength color ────────────────────────────────────────────────

private fun rssiColor(rssi: Int): Color = when {
    rssi >= -80  -> Color(0xFF4CAF50)  // green  — excellent
    rssi >= -100 -> Color(0xFFFFC107)  // amber  — good
    rssi >= -110 -> Color(0xFFFF9800)  // orange — fair
    else         -> Color(0xFFF44336)  // red    — poor
}

// ── Camera preview (landscape mode) ──────────────────────────────────────────

@Composable
fun CameraPreviewScreen(
    handheldDeviceAzimuth: Float,
    locatorAzimuth: Float,
    handheldDevicePitch: Float,
    locatorElevation: Float,
    rocketSpeed: Float = 0f,
    rocketAttitude: Quaternionf = Quaternionf.IDENTITY,
    armedState: Boolean = false,
    lastPreLaunchMessageAge: Long = Long.MAX_VALUE,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context)
        provider.addListener({ cameraProvider = provider.get() }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose { cameraProvider?.unbindAll() }
    }

    if (cameraProvider != null) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    val preview = Preview.Builder().build().also { it.surfaceProvider = surfaceProvider }
                    camera = cameraProvider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                }
            }
        )

        // Angle deltas: positive horizontalDelta = locator is to the right of camera aim;
        // positive verticalDelta = locator is below camera aim (canvas Y grows downward).
        val horizontalDelta = ((locatorAzimuth   - handheldDeviceAzimuth + 540f) % 360f) - 180f
        val verticalDelta   = ((handheldDevicePitch - locatorElevation   + 540f) % 360f) - 180f

        val density   = LocalDensity.current
        val config    = LocalConfiguration.current
        val scrW = with(density) { config.screenWidthDp.dp.toPx() }
        val scrH = with(density) { config.screenHeightDp.dp.toPx() }

        // Colors
        val locatorColor   = Color(0xFFFF6080)   // red-pink for locator marker
        val crosshairColor = Color(0xFFC0FFC0)   // soft green for crosshair
        val gaugeColor     = Color(0xFFFFC040)   // amber for HUD gauges
        val gaugeBgColor   = Color(0x80000000)   // 50 % black gauge background

        // Pre-build native text paints for gauge labels (must be outside DrawScope).
        val labelPx = with(density) { 10.sp.toPx() }
        val hLabelPaint = remember { android.graphics.Paint().apply {
            color = android.graphics.Color.argb(200, 255, 192, 64)
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        } }.also { it.textSize = labelPx }
        val vLabelPaint = remember { android.graphics.Paint().apply {
            color = android.graphics.Color.argb(200, 255, 192, 64)
            textAlign = android.graphics.Paint.Align.RIGHT
            isAntiAlias = true
        } }.also { it.textSize = labelPx }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        zoomRatio = if (zoomRatio == 1f)
                            camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
                        else 1f
                    }
                }
        ) {
            val stroke = 2.dp.toPx()
            val cx = scrW / 2f
            val cy = scrH / 2f

            // ── Crosshair ─────────────────────────────────────────────────────
            val gap = 50.dp.toPx()
            val arm = 25.dp.toPx()
            drawLine(crosshairColor, Offset(cx, cy - gap - arm), Offset(cx, cy - gap), stroke)
            drawLine(crosshairColor, Offset(cx + gap + arm, cy), Offset(cx + gap, cy), stroke)
            drawLine(crosshairColor, Offset(cx, cy + gap + arm), Offset(cx, cy + gap), stroke)
            drawLine(crosshairColor, Offset(cx - gap - arm, cy), Offset(cx - gap, cy), stroke)

            // ── Locator circle (on-screen) or edge arrow (off-screen) ─────────
            val scale  = 10f
            val radius = 50.dp.toPx()
            val lx = cx + horizontalDelta * scale
            val ly = cy + verticalDelta   * scale

            if (lx in -radius..(scrW + radius) && ly in -radius..(scrH + radius)) {
                drawCircle(locatorColor, radius, Offset(lx, ly), style = Stroke(stroke))
            } else {
                // Clamp circle centre to screen edge with a small margin, then
                // draw a triangle arrow pointing from the edge toward the locator.
                val em = 20.dp.toPx()
                val ex = lx.coerceIn(em, scrW - em)
                val ey = ly.coerceIn(em, scrH - em)
                val dx = lx - ex;  val dy = ly - ey
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val nx = dx / len; val ny = dy / len   // unit vector toward locator
                val px = -ny;      val py = nx          // perpendicular
                val arrowSz = 14.dp.toPx()
                val arrowPath = Path().apply {
                    moveTo(ex + nx * arrowSz,              ey + ny * arrowSz)
                    lineTo(ex + px * arrowSz * 0.5f,       ey + py * arrowSz * 0.5f)
                    lineTo(ex - px * arrowSz * 0.5f,       ey - py * arrowSz * 0.5f)
                    close()
                }
                drawPath(arrowPath, locatorColor)
            }

            // ── Horizontal HUD gauge (bottom, shows left/right delta) ─────────
            val gaugeRange  = 45f      // degrees shown each side of centre
            val tickMinor   = 5f       // minor tick every 5°
            val tickMajorMod = 15      // major tick every 15°

            val hGaugeW  = scrW * 0.65f
            val hGaugeH  = 22.dp.toPx()
            val hLeft    = (scrW - hGaugeW) / 2f
            val hBottom  = scrH - 16.dp.toPx()
            val hTop     = hBottom - hGaugeH
            val hMidY    = (hTop + hBottom) / 2f
            val hPpd     = hGaugeW / (2f * gaugeRange)   // pixels per degree

            drawRect(gaugeBgColor, Offset(hLeft, hTop),
                androidx.compose.ui.geometry.Size(hGaugeW, hGaugeH))

            // Tick marks
            var d = -gaugeRange
            while (d <= gaugeRange + 0.01f) {
                val tx = cx + d * hPpd
                if (tx in hLeft..(hLeft + hGaugeW)) {
                    val major = d.toInt() % tickMajorMod == 0
                    val th = if (major) hGaugeH * 0.7f else hGaugeH * 0.35f
                    drawLine(if (major) gaugeColor else gaugeColor.copy(alpha = 0.45f),
                        Offset(tx, hMidY - th / 2f), Offset(tx, hMidY + th / 2f),
                        if (major) stroke else stroke * 0.5f)
                }
                d += tickMinor
            }
            // Centre reference (zero mark)
            drawLine(Color.White, Offset(cx, hTop), Offset(cx, hBottom), stroke * 1.5f)

            // Indicator triangle above the bar, tip pointing down into it
            val hiX    = (cx + horizontalDelta * hPpd).coerceIn(hLeft, hLeft + hGaugeW)
            val hTriH  = hGaugeH * 0.8f
            val hTriPath = Path().apply {
                moveTo(hiX,                  hTop - 1.dp.toPx())
                lineTo(hiX - hTriH * 0.5f,  hTop - hTriH)
                lineTo(hiX + hTriH * 0.5f,  hTop - hTriH)
                close()
            }
            drawPath(hTriPath, locatorColor)

            // Degree labels at ±gaugeRange, ±gaugeRange/2, 0
            listOf(-gaugeRange, -gaugeRange / 2f, 0f, gaugeRange / 2f, gaugeRange).forEach { ld ->
                val lx2 = cx + ld * hPpd
                if (lx2 in hLeft..(hLeft + hGaugeW))
                    drawContext.canvas.nativeCanvas.drawText(
                        "${ld.toInt()}°", lx2, hBottom + labelPx + 2.dp.toPx(), hLabelPaint)
            }

            // ── Vertical HUD gauge (right edge, shows up/down delta) ──────────
            val vGaugeH  = scrH * 0.55f
            val vGaugeW  = 22.dp.toPx()
            val vRight   = scrW - 16.dp.toPx()
            val vLeft    = vRight - vGaugeW
            val vTop     = cy - vGaugeH / 2f
            val vBottom  = cy + vGaugeH / 2f
            val vMidX    = (vLeft + vRight) / 2f
            val vPpd     = vGaugeH / (2f * gaugeRange)

            drawRect(gaugeBgColor, Offset(vLeft, vTop),
                androidx.compose.ui.geometry.Size(vGaugeW, vGaugeH))

            var vd = -gaugeRange
            while (vd <= gaugeRange + 0.01f) {
                val ty = cy + vd * vPpd
                if (ty in vTop..vBottom) {
                    val major = vd.toInt() % tickMajorMod == 0
                    val tw = if (major) vGaugeW * 0.7f else vGaugeW * 0.35f
                    drawLine(if (major) gaugeColor else gaugeColor.copy(alpha = 0.45f),
                        Offset(vMidX - tw / 2f, ty), Offset(vMidX + tw / 2f, ty),
                        if (major) stroke else stroke * 0.5f)
                }
                vd += tickMinor
            }
            drawLine(Color.White, Offset(vLeft, cy), Offset(vRight, cy), stroke * 1.5f)

            // Indicator triangle left of the bar, tip pointing right into it
            val viY    = (cy + verticalDelta * vPpd).coerceIn(vTop, vBottom)
            val vTriW  = vGaugeW * 0.8f
            val vTriPath = Path().apply {
                moveTo(vLeft - 1.dp.toPx(),   viY)
                lineTo(vLeft - vTriW,          viY - vTriW * 0.5f)
                lineTo(vLeft - vTriW,          viY + vTriW * 0.5f)
                close()
            }
            drawPath(vTriPath, locatorColor)

            // Degree labels
            listOf(-gaugeRange, -gaugeRange / 2f, 0f, gaugeRange / 2f, gaugeRange).forEach { ld ->
                val ly2 = cy + ld * vPpd
                if (ly2 in vTop..vBottom)
                    drawContext.canvas.nativeCanvas.drawText(
                        "${ld.toInt()}°", vLeft - 4.dp.toPx(), ly2 + labelPx / 3f, vLabelPaint)
            }

            if (armedState && lastPreLaunchMessageAge < messageTimeout) {
                // ── Velocity arc gauge (top-left) ─────────────────────────────
                drawVelocityGauge(
                    speed = rocketSpeed,
                    maxSpeed = 500f,
                    cx = 100.dp.toPx(),
                    cy = 100.dp.toPx(),
                    radius = 80.dp.toPx(),
                    gaugeColor = gaugeColor,
                    gaugeBgColor = gaugeBgColor,
                    labelPaint = hLabelPaint,
                    labelPx = labelPx,
                    stroke = stroke,
                )

                // ── 3D rocket attitude (top-right) ────────────────────────────
                drawRocket3D(
                    attitude = rocketAttitude,
                    cx = scrW - 100.dp.toPx(),
                    cy = 100.dp.toPx(),
                    scale = 70.dp.toPx(),
                    gaugeBgColor = gaugeBgColor,
                    stroke = stroke,
                )
            }
        }
    }

    LaunchedEffect(zoomRatio) {
        camera?.cameraControl?.setZoomRatio(zoomRatio)
    }
}

// ── Text animation composables ────────────────────────────────────────────────

@Composable
fun PulsingText(
    modifier: Modifier = Modifier,
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    minAlpha: Float = 0f,
    maxAlpha: Float = 1f,
    durationMillis: Int = 500,
) {
    val transition = rememberInfiniteTransition(label = "PulseTransition")
    val alpha by transition.animateFloat(
        initialValue = minAlpha,
        targetValue = maxAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "AlphaPulse"
    )
    Text(text = text, color = color, textAlign = textAlign, style = style,
        modifier = modifier.alpha(alpha))
}

@Composable
fun BlinkingText(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    intervalMillis: Long = 500,
) {
    var visible by remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "BlinkAlpha"
    )
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMillis)
            visible = !visible
        }
    }
    Text(text = text, color = color, textAlign = textAlign, style = style,
        modifier = Modifier.alpha(alpha))
}

// ── Exit button ───────────────────────────────────────────────────────────────

@Composable
fun ExitAppButton(activity: Activity) {
    var showDialog by remember { mutableStateOf(false) }
    Button(onClick = { showDialog = true }) { Text("Exit App") }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Exit App") },
            text = { Text("Are you sure you want to exit the app?") },
            confirmButton = {
                Button(onClick = { showDialog = false; activity.finish() }) { Text("Yes") }
            },
            dismissButton = {
                Button(onClick = { showDialog = false }) { Text("No") }
            }
        )
    }
}