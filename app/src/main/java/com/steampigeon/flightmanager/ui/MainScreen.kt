package com.steampigeon.flightmanager.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.google.maps.android.compose.widgets.ScaleBar
import com.mutualmobile.composesensors.SensorDelay
import com.mutualmobile.composesensors.rememberAccelerometerSensorState
import com.mutualmobile.composesensors.rememberMagneticFieldSensorState
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.RocketScreen
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.BluetoothManagerRepository.receiverDevice
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.Protocol
import com.steampigeon.flightmanager.data.ReceiverConfig
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
import kotlin.math.round

private const val messageTimeout = 2000
private const val drogueVelocityThreshold = -30
private const val mainVelocityThreshold = -8
private const val landingAltitudeThreshold = 30
private const val minimumSpokenAGLVelocity = 2 * 9.8

// ── Supporting types ──────────────────────────────────────────────────────────

/** A navigation drawer entry binding a label, icon, and destination screen. */
private data class DrawerItem(val labelRes: Int, val iconRes: Int, val screen: RocketScreen)

/** Pairs a deployment channel's mode with its armed state for display. */
private data class ChannelConfig(val mode: DeployMode?, val isArmed: Boolean)

// ── Helper functions ──────────────────────────────────────────────────────────

/** Returns the display string for a single deployment channel based on its mode. */
private fun deployChannelText(mode: DeployMode?, config: LocatorConfig): String =
    when (mode) {
        DeployMode.DroguePrimary ->
            "Drogue Primary:   ${config.droguePrimaryDeployDelay / 10}.${config.droguePrimaryDeployDelay % 10} s"
        DeployMode.DrogueBackup ->
            "Drogue Backup :   ${config.drogueBackupDeployDelay / 10}.${config.drogueBackupDeployDelay % 10} s"
        DeployMode.MainPrimary ->
            "Main Primary  :   ${config.mainPrimaryDeployAltitude} m"
        DeployMode.MainBackup ->
            "Main Backup   :   ${config.mainBackupDeployAltitude} m"
        else -> ""
    }

// ── HomeScreen ────────────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    navController: NavHostController,
    viewModel: RocketViewModel = viewModel(),
    permissionsState: MultiplePermissionsState,
    textToSpeech: TextToSpeech?,
    modifier: Modifier
) {
    val context = LocalContext.current
    val bluetoothConnectionState = BluetoothManagerRepository.bluetoothConnectionState.collectAsState().value
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var isMapLoaded by remember { mutableStateOf(false) }

    val mapUiSettings = remember {
        MapUiSettings(
            compassEnabled = false,
            indoorLevelPickerEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            tiltGesturesEnabled = false,
            zoomControlsEnabled = false
        )
    }
    val properties by remember {
        mutableStateOf(MapProperties(isMyLocationEnabled = true, mapType = MapType.SATELLITE))
    }

    val receiverConfig by viewModel.remoteReceiverConfig.collectAsState()
    val locatorConfig by viewModel.remoteLocatorConfig.collectAsState()
    val rocketState by viewModel.rocketState.collectAsState()
    val receiverDeviceName = BluetoothManagerRepository.receiverDevice.collectAsState().value?.name
    val armedState = BluetoothManagerRepository.armedState.collectAsState().value
    val locatorArmedMessageState = BluetoothManagerRepository.locatorArmedMessageState.collectAsState().value
    val orientation = LocalConfiguration.current.orientation
    val hasCompass = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_COMPASS)

    // Sensor states reserved for future orientation tracking
    val accelerometerState = rememberAccelerometerSensorState(sensorDelay = SensorDelay.Normal)
    val magneticFieldState = rememberMagneticFieldSensorState(sensorDelay = SensorDelay.Normal)

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    var trackerLocation by remember { mutableStateOf<Location?>(null) }
    val locationPermissionState = permissionsState.permissions
        .find { it.permission == Manifest.permission.ACCESS_FINE_LOCATION }
    if (locationPermissionState?.hasPermission == true) {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            trackerLocation = location
        }
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
    val locatorElevation = viewModel.locatorElevation.collectAsState().value
    val azimuthToLocator = viewModel.locatorAzimuth.collectAsState().value
    val lastPreLaunchMessageAge = System.currentTimeMillis() - rocketState.lastPreLaunchMessageTime

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
        CameraPreviewScreen(azimuth, azimuthToLocator, handheldDevicePitch, locatorElevation)
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
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.onGloballyPositioned { scaffoldSize = it.size },
                    floatingActionButton = {},   // disable Scaffold FAB
                ) {
                    trackerLocation?.let { location ->
                        MapWithOverlays(
                            trackerLocation = location,
                            rocketState = rocketState,
                            armedState = armedState,
                            receiverDeviceName = receiverDeviceName,
                            locatorConfig = locatorConfig,
                            locatorArmedMessageState = locatorArmedMessageState,
                            bluetoothConnectionState = bluetoothConnectionState,
                            locatorGPSLock = locatorGPSLock,
                            isMapLoaded = isMapLoaded,
                            onMapLoaded = { isMapLoaded = true },
                            hasCompass = hasCompass,
                            azimuth = azimuth,
                            lastAzimuth = lastAzimuth,
                            lastPreLaunchMessageAge = lastPreLaunchMessageAge,
                            distanceToLocator = distanceToLocator,
                            properties = properties,
                            mapUiSettings = mapUiSettings,
                            viewModel = viewModel,
                            scaffoldSize = scaffoldSize,
                            textToSpeech = textToSpeech,
                            modifier = modifier
                        )
                    }
                }
                ExtendedFloatingActionButton(
                    text = { Text("Menu") },
                    icon = { Icon(Icons.Filled.Menu, contentDescription = null) },
                    expanded = false,
                    onClick = {
                        scope.launch {
                            drawerState.apply { if (isClosed) open() else close() }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                )
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
    var receivedState by remember { mutableStateOf(FlightStates.WaitingForLaunch) }
    var noseoverTime by remember { mutableLongStateOf(0L) }
    var launchLocation by remember { mutableStateOf(LatLng(0.0, 0.0)) }

    val lastPreLaunchMessageAge = System.currentTimeMillis() - rocketState.lastPreLaunchMessageTime
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
        if (rocketState.flightState >= FlightStates.DroguePrimaryDeployed && !droguePrimaryState) {
            droguePrimaryState = true
            if (locatorConfig.deploymentChannel1Mode == DeployMode.DroguePrimary && rocketState.channel1Fired ||
                locatorConfig.deploymentChannel2Mode == DeployMode.DroguePrimary && rocketState.channel2Fired)
                textToSpeech?.speak("Drogue charge.", TextToSpeech.QUEUE_ADD, null, null)
        }
        if (rocketState.flightState >= FlightStates.DrogueBackupDeployed && !drogueBackupState) {
            drogueBackupState = true
            if (locatorConfig.deploymentChannel1Mode == DeployMode.DrogueBackup && rocketState.channel1Fired ||
                locatorConfig.deploymentChannel2Mode == DeployMode.DrogueBackup && rocketState.channel2Fired)
                textToSpeech?.speak("Drogue backup charge.", TextToSpeech.QUEUE_ADD, null, null)
        }
        if (rocketState.flightState >= FlightStates.MainPrimaryDeployed && !mainPrimaryState) {
            mainPrimaryState = true
            if (locatorConfig.deploymentChannel1Mode == DeployMode.MainPrimary && rocketState.channel1Fired ||
                locatorConfig.deploymentChannel2Mode == DeployMode.MainPrimary && rocketState.channel2Fired)
                textToSpeech?.speak("Main charge.", TextToSpeech.QUEUE_ADD, null, null)
        }
        if (rocketState.flightState >= FlightStates.MainBackupDeployed && !mainBackupState) {
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
            receivedState = FlightStates.WaitingForLaunch
            noseoverTime = 0
            launchLocation = LatLng(0.0, 0.0)
        }
    }

    // Announces continuous altitude updates during ascent, descent warnings, and chute deployment.
    LaunchedEffect(rocketState.altitudeAboveGroundLevel) {
        if (!armedState) return@LaunchedEffect
        textToSpeech?.isSpeaking?.let { isSpeaking ->
            if (rocketState.flightState == FlightStates.Burnout) {
                val roundedAGL = (rocketState.altitudeAboveGroundLevel / 100).toInt() * 100
                if (roundedAGL > previousAGL) {
                    if (!isSpeaking && rocketState.velocity > minimumSpokenAGLVelocity)
                        textToSpeech.speak("$roundedAGL meters. . . . . .", TextToSpeech.QUEUE_ADD, null, null)
                    previousAGL = roundedAGL
                }
            }
            if (rocketState.flightState > FlightStates.Noseover) {
                if (lastPreLaunchMessageAge < messageTimeout && rocketState.velocity <= drogueVelocityThreshold && !isSpeaking && !landingSpoken) {
                    textToSpeech.speak(
                        "Descent warning, ${-rocketState.velocity.toInt()} meters per second ${
                            if (rocketState.gpsStatus == SensorHealth.Ok)
                                "${vectorFromLaunch.distance} meters ${vectorFromLaunch.ordinal} of launch point. . . . . ."
                            else ""
                        }",
                        TextToSpeech.QUEUE_ADD, null, null
                    )
                }
                if (!landingSpoken && rocketState.altitudeAboveGroundLevel < landingAltitudeThreshold) {
                    landingSpoken = true
                    textToSpeech.speak(
                        "Landing${
                            if (rocketState.gpsStatus == SensorHealth.Ok)
                                " ${vectorFromLaunch.distance} meters ${vectorFromLaunch.ordinal} of launch point."
                            else ", location unknown."
                        },",
                        TextToSpeech.QUEUE_ADD, null, null
                    )
                }
            }
        }
        if (rocketState.flightState >= FlightStates.DroguePrimaryDeployed && !drogueDeploySpoken && rocketState.drogueDeployDetected) {
            drogueDeploySpoken = true
            textToSpeech?.speak("Drogue deployed.", TextToSpeech.QUEUE_ADD, null, null)
        }
        if (rocketState.flightState >= FlightStates.MainPrimaryDeployed && !mainDeploySpoken && rocketState.mainDeployDetected) {
            mainDeploySpoken = true
            drogueDeploySpoken = true
            textToSpeech?.speak("Main deployed.", TextToSpeech.QUEUE_ADD, null, null)
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
    onNavigate: (RocketScreen) -> Unit,
) {
    val items = buildList {
        add(DrawerItem(R.string.application_settings, R.drawable.settings_applications, RocketScreen.AppSettings))
        if (bluetoothConnectionState == BluetoothConnectionState.Ready)
            add(DrawerItem(R.string.receiver_settings, R.drawable.radio, RocketScreen.ReceiverSettings))
        if (locatorActive && !armedState) {
            add(DrawerItem(R.string.locator_settings, R.drawable.navigation, RocketScreen.LocatorSettings))
            add(DrawerItem(R.string.flight_profiles, R.drawable.u_turn_right, RocketScreen.FlightProfiles))
        }
        if (locatorActive && armedState)
            add(DrawerItem(R.string.deployment_test, R.drawable.bomb, RocketScreen.DeploymentTest))
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
@OptIn(MapsComposeExperimentalApi::class)
@Composable
private fun MapWithOverlays(
    trackerLocation: Location,
    rocketState: RocketState,
    armedState: Boolean,
    receiverDeviceName: String?,
    locatorConfig: LocatorConfig,
    locatorArmedMessageState: LocatorMessageState,
    bluetoothConnectionState: BluetoothConnectionState,
    locatorGPSLock: Boolean,
    isMapLoaded: Boolean,
    onMapLoaded: () -> Unit,
    hasCompass: Boolean,
    azimuth: Float,
    lastAzimuth: Float,
    lastPreLaunchMessageAge: Long,
    distanceToLocator: Int,
    properties: MapProperties,
    mapUiSettings: MapUiSettings,
    viewModel: RocketViewModel,
    scaffoldSize: IntSize,
    textToSpeech: TextToSpeech?,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        var autoTargetMode by remember { mutableStateOf(true) }
        var autoZoomMode by remember { mutableStateOf(true) }
        var showControls by remember { mutableStateOf(false) }
        var googleMap by remember { mutableStateOf<GoogleMap?>(null) }

        val locatorLatLng = LatLng(rocketState.latitude, rocketState.longitude)
        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.Builder()
                .target(LatLng(trackerLocation.latitude, trackerLocation.longitude))
                .zoom(12f)
                .bearing(azimuth)
                .build()
        }
        val rocketMarkerState = rememberMarkerState()
        LaunchedEffect(rocketState.latitude, rocketState.longitude) {
            rocketMarkerState.position = locatorLatLng
        }

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            onMapLoaded = onMapLoaded,
            cameraPositionState = cameraPositionState,
            properties = properties,
            uiSettings = mapUiSettings,
            onMapClick = { showControls = !showControls }
        ) {
            MapEffect(Unit) { map -> googleMap = map }
            Marker(
                state = rocketMarkerState,
                icon = BitmapDescriptorFactory.defaultMarker(
                    if (lastPreLaunchMessageAge < messageTimeout) BitmapDescriptorFactory.HUE_GREEN
                    else BitmapDescriptorFactory.HUE_RED
                )
            )
            Circle(
                center = locatorLatLng,
                fillColor = Color(if (lastPreLaunchMessageAge < messageTimeout) 0x3000ff00 else 0x30ff0000),
                radius = (4 * rocketState.hdop).toDouble(),
                strokeColor = Color(if (lastPreLaunchMessageAge < messageTimeout) 0x8000ff00 else 0x80ff0000),
                strokeWidth = 1f,
            )
        }

        MapCameraController(
            googleMap = googleMap,
            trackerLocation = trackerLocation,
            rocketState = rocketState,
            cameraPositionState = cameraPositionState,
            isMapLoaded = isMapLoaded,
            hasCompass = hasCompass,
            azimuth = azimuth,
            lastAzimuth = lastAzimuth,
            autoTargetMode = autoTargetMode,
            autoZoomMode = autoZoomMode,
            onBearingUpdate = { viewModel.updateLastHandheldDeviceAzimuth(it) },
        )

        ScaleBar(
            modifier = modifier
                .align(Alignment.BottomStart)
                .offset(x = (-16).dp, y = (-16).dp),
            width = 192.dp,
            height = 64.dp,
            cameraPositionState = cameraPositionState,
            textColor = MaterialTheme.colorScheme.secondary,
            lineColor = MaterialTheme.colorScheme.primary,
            shadowColor = MaterialTheme.colorScheme.primary,
        )
        Image(
            painter = painterResource(id = R.drawable.compass),
            contentDescription = "Compass",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = 48.dp)
                .rotate(-lastAzimuth),
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

        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.SpaceAround
        ) {
            MapControlsColumn(
                bluetoothConnectionState = bluetoothConnectionState,
                lastPreLaunchMessageAge = lastPreLaunchMessageAge,
                rocketState = rocketState,
                receiverDeviceName = receiverDeviceName,
                locatorConfig = locatorConfig,
                showControls = showControls,
                armedState = armedState,
                autoTargetMode = autoTargetMode,
                autoZoomMode = autoZoomMode,
                locatorArmedMessageState = locatorArmedMessageState,
                onToggleArmed = { viewModel.updateArmedState() },
                onToggleAutoTarget = { autoTargetMode = !autoTargetMode },
                onToggleAutoZoom = { autoZoomMode = !autoZoomMode },
                textToSpeech = textToSpeech,
                modifier = modifier,
            )
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
 */
@Composable
private fun MapCameraController(
    googleMap: GoogleMap?,
    trackerLocation: Location,
    rocketState: RocketState,
    cameraPositionState: CameraPositionState,
    isMapLoaded: Boolean,
    hasCompass: Boolean,
    azimuth: Float,
    lastAzimuth: Float,
    autoTargetMode: Boolean,
    autoZoomMode: Boolean,
    onBearingUpdate: (Float) -> Unit,
) {
    val kalmanGainTarget = 0.1f
    val kalmanGainZoom = 0.05f
    val kalmanGainBearing = 0.01f

    var lastUserGestureTime by remember { mutableLongStateOf(0L) }
    var smoothedTarget by remember { mutableStateOf(LatLng(0.0, 0.0)) }
    var smoothedZoom by remember { mutableFloatStateOf(12f) }

    LaunchedEffect(Unit) {
        snapshotFlow { cameraPositionState.cameraMoveStartedReason }
            .collect { reason ->
                when (reason) {
                    CameraMoveStartedReason.GESTURE -> {
                        Log.d("ZoomSource", "User gesture triggered zoom")
                        lastUserGestureTime = System.currentTimeMillis()
                    }
                    CameraMoveStartedReason.DEVELOPER_ANIMATION ->
                        Log.d("ZoomSource", "Programmatic zoom triggered")
                    CameraMoveStartedReason.API_ANIMATION ->
                        Log.d("ZoomSource", "Zoom via built-in API animation")
                    else ->
                        Log.d("ZoomSource", "Unknown zoom source")
                }
            }
    }

    if (!isMapLoaded) return

    val userGestureRecent = System.currentTimeMillis() - lastUserGestureTime <= 5000
    if (userGestureRecent) {
        // Sync Kalman state to wherever the user panned so we resume from there
        smoothedTarget = cameraPositionState.position.target
        smoothedZoom = cameraPositionState.position.zoom
        onBearingUpdate(cameraPositionState.position.bearing)
        return
    }

    val savedTarget = cameraPositionState.position.target
    val savedZoom = cameraPositionState.position.zoom

    val (autoTarget, autoZoom) = if (autoTargetMode || autoZoomMode) {
        val bounds = LatLngBounds.builder()
            .include(LatLng(trackerLocation.latitude, trackerLocation.longitude))
            .include(LatLng(rocketState.latitude, rocketState.longitude))
            .build()
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 300))
        Pair(googleMap?.cameraPosition?.target, googleMap?.cameraPosition?.zoom)
    } else {
        Pair(null, null)
    }

    smoothedTarget = if (autoTargetMode && autoTarget != null)
        LatLng(
            savedTarget.latitude + (autoTarget.latitude - savedTarget.latitude) * kalmanGainTarget,
            savedTarget.longitude + (autoTarget.longitude - savedTarget.longitude) * kalmanGainTarget
        )
    else savedTarget

    smoothedZoom = if (autoZoomMode && autoZoom != null)
        savedZoom + (autoZoom - savedZoom) * kalmanGainZoom
    else savedZoom

    if (hasCompass) {
        val delta = ((azimuth - lastAzimuth + 540f) % 360f) - 180f
        onBearingUpdate((lastAzimuth + delta * kalmanGainBearing + 360f) % 360f)
    }
    val bearing = if (hasCompass) lastAzimuth else cameraPositionState.position.bearing

    cameraPositionState.position = CameraPosition(smoothedTarget, smoothedZoom, 0f, bearing)
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
    receiverDeviceName: String?,
    locatorConfig: LocatorConfig,
    rocketState: RocketState,
    showControls: Boolean,
    armedState: Boolean,
    autoTargetMode: Boolean,
    autoZoomMode: Boolean,
    locatorArmedMessageState: LocatorMessageState,
    onToggleArmed: () -> Unit,
    onToggleAutoTarget: () -> Unit,
    onToggleAutoZoom: () -> Unit,
    textToSpeech: TextToSpeech?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(modifier = modifier.weight(1f))
        Column(
            modifier = Modifier
                .padding(0.dp)
                .heightIn(min = 200.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            val iconSize = 20.dp
            val iconBoxWidth = 40.dp   // wide enough for rocket icon + satellite superscript
            val nameWidth = 165.dp     // fits DEVICE_NAME_LENGTH characters at body size
            val batterySize = 20.dp
            val batteryBoxWidth = 24.dp
            // Row 1: radio icon | receiver name | receiver battery
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                        BluetoothConnectionState.PairingFailed -> "Pairing failed"
                        BluetoothConnectionState.NoDevicesAvailable -> "Waiting for receiver"
                        BluetoothConnectionState.DevicesFound -> "Receivers found"
                        BluetoothConnectionState.Connected -> "Receiver connected"
                        BluetoothConnectionState.Ready -> receiverDeviceName ?: ""
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(iconBoxWidth), contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.rocket_md),
                            contentDescription = stringResource(R.string.locator_satellites),
                            modifier = Modifier.size(iconSize)
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
        }
    }
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        if (showControls) {
            val armedStateText = if (armedState)
                stringResource(R.string.armed_state_armed)
            else
                stringResource(R.string.armed_state_disarmed)

            Button(
                onClick = {
                    if (locatorArmedMessageState == LocatorMessageState.Idle ||
                        locatorArmedMessageState == LocatorMessageState.AckUpdated
                    ) {
                        BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorMessageState.SendRequested)
                        onToggleArmed()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = (-160).dp)
                    .padding(4.dp)
                    .size(width = 120.dp, height = 40.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    when {
                        locatorArmedMessageState == LocatorMessageState.NotAcknowledged ->
                            stringResource(R.string.update_not_acknowledged)
                        armedState && (locatorArmedMessageState == LocatorMessageState.Idle
                                || locatorArmedMessageState == LocatorMessageState.AckUpdated) ->
                            stringResource(R.string.armed_state_armed)
                        armedState && locatorArmedMessageState == LocatorMessageState.SendFailure ->
                            stringResource(R.string.bluetooth_state_disconnected)
                        armedState ->
                            stringResource(R.string.armed_state_disarming)
                        !armedState && (locatorArmedMessageState == LocatorMessageState.Idle
                                || locatorArmedMessageState == LocatorMessageState.AckUpdated) ->
                            stringResource(R.string.armed_state_disarmed)
                        !armedState && locatorArmedMessageState == LocatorMessageState.SendFailure ->
                            stringResource(R.string.bluetooth_state_disconnected)
                        else -> stringResource(R.string.armed_state_arming)
                    }
                )
                LaunchedEffect(armedState) {
                    textToSpeech?.speak(armedStateText, TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
        }
        Button(
            onClick = onToggleAutoTarget,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(y = (-112).dp)
                .padding(4.dp)
                .size(width = 120.dp, height = 40.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(if (autoTargetMode) "Auto center" else "Manual center")
        }
        Button(
            onClick = onToggleAutoZoom,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(y = (-64).dp)
                .padding(4.dp)
                .size(width = 120.dp, height = 40.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(if (autoZoomMode) "Auto zoom" else "Manual zoom")
        }
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
    LaunchedEffect(Unit) {
        locatorStatisticsOffset = viewModel.locatorStatisticsOffset.value
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
                    locatorStatisticsOffset = IntOffset(
                        (locatorStatisticsOffset.x + dragAmount.x.toInt()).coerceIn(16, scaffoldSize.width - columnWidth - 48),
                        (locatorStatisticsOffset.y + dragAmount.y.toInt()).coerceIn(16, scaffoldSize.height - columnHeight - 48)
                    )
                }
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x805D6F96))
            .padding(8.dp)
            .onSizeChanged { size ->
                columnWidth = size.width
                columnHeight = size.height
                locatorStatisticsOffset = IntOffset(
                    locatorStatisticsOffset.x.coerceIn(16, scaffoldSize.width - columnWidth - 48),
                    locatorStatisticsOffset.y.coerceIn(16, scaffoldSize.height - columnHeight - 48)
                )
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

        // ── Flight state (armed) or deployment channel config (disarmed) ──────
        if (armedState) {
            Text(
                text = when (rocketState.flightState) {
                    FlightStates.WaitingForLaunch -> "Waiting For Launch"
                    FlightStates.Launched -> "Launched"
                    FlightStates.Burnout -> "Burnout"
                    FlightStates.Noseover -> "Noseover"
                    FlightStates.DroguePrimaryDeployed -> "Drogue Primary"
                    FlightStates.DrogueBackupDeployed -> "Drogue Backup"
                    FlightStates.MainPrimaryDeployed -> "Main Primary"
                    FlightStates.MainBackupDeployed -> "Main Backup"
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
            ).forEach { (mode, isArmed) ->
                Text(
                    text = deployChannelText(mode, locatorConfig),
                    style = TelemetryTextStyle,
                    color = if (isArmed) Color.Unspecified else MaterialTheme.colorScheme.error,
                )
            }
        }

        if (rocketState.latitude != 0.0 && rocketState.longitude != 0.0)
            Text(
                modifier = modifier,
                text = "${BigDecimal(rocketState.latitude).setScale(6, RoundingMode.HALF_UP)}," +
                        BigDecimal(rocketState.longitude).setScale(6, RoundingMode.HALF_UP).toString(),
                style = TelemetryTextStyle,
            )
    }

    LaunchedEffect(locatorStatisticsOffset) {
        viewModel.updateLocatorStatisticsOffset(locatorStatisticsOffset)
        viewModel.saveUserPreferences()
    }
}

// ── Utility composable ────────────────────────────────────────────────────────

@Composable
fun rememberUpdatedMarkerState(newPosition: LatLng) =
    remember { MarkerState(position = newPosition) }
        .apply { position = newPosition }

// ── Camera preview (landscape mode) ──────────────────────────────────────────

@Composable
fun CameraPreviewScreen(
    handheldDeviceAzimuth: Float,
    locatorAzimuth: Float,
    handheldDevicePitch: Float,
    locatorElevation: Float
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context)
        provider.addListener({
            cameraProvider = provider.get()
        }, ContextCompat.getMainExecutor(context))
    }

    if (cameraProvider != null) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PreviewView(it).apply {
                    val preview = Preview.Builder().build().also { preview ->
                        preview.surfaceProvider = surfaceProvider
                    }
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider?.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                    camera = cameraProvider?.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                }
            }
        )

        val horizontalDelta = ((locatorAzimuth - handheldDeviceAzimuth + 540) % 360) - 180
        val verticalDelta = ((handheldDevicePitch - locatorElevation + 540) % 360) - 180
        val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
        val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
        val centerPxX = with(LocalDensity.current) { (screenWidthDp / 2).toPx() }
        val centerPxY = with(LocalDensity.current) { (screenHeightDp / 2).toPx() }
        val locatorWindowScale = 10
        val locatorWindowPositionSize = 50
        val crosshairLineLength = 25
        val lineWidthDp = 2
        val locatorColor = Color(0xffffc0c0)
        val crosshairColor = Color(0xffc0ffc0)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        zoomRatio = if (zoomRatio == 1f)
                            (camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f)
                        else 1f
                    }
                }
        ) {
            // Crosshair arms
            drawLine(color = crosshairColor, strokeWidth = lineWidthDp.dp.toPx(),
                start = Offset(centerPxX, centerPxY - (locatorWindowPositionSize + crosshairLineLength).dp.toPx()),
                end   = Offset(centerPxX, centerPxY - locatorWindowPositionSize.dp.toPx()))
            drawLine(color = crosshairColor, strokeWidth = lineWidthDp.dp.toPx(),
                start = Offset(centerPxX + (locatorWindowPositionSize + crosshairLineLength).dp.toPx(), centerPxY),
                end   = Offset(centerPxX + locatorWindowPositionSize.dp.toPx(), centerPxY))
            drawLine(color = crosshairColor, strokeWidth = lineWidthDp.dp.toPx(),
                start = Offset(centerPxX, centerPxY + (locatorWindowPositionSize + crosshairLineLength).dp.toPx()),
                end   = Offset(centerPxX, centerPxY + locatorWindowPositionSize.dp.toPx()))
            drawLine(color = crosshairColor, strokeWidth = lineWidthDp.dp.toPx(),
                start = Offset(centerPxX - (locatorWindowPositionSize + crosshairLineLength).dp.toPx(), centerPxY),
                end   = Offset(centerPxX - locatorWindowPositionSize.dp.toPx(), centerPxY))
            // Locator circle
            drawCircle(
                color = locatorColor,
                radius = locatorWindowPositionSize.dp.toPx(),
                center = Offset(centerPxX + horizontalDelta * locatorWindowScale, centerPxY + verticalDelta * locatorWindowScale),
                style = Stroke(width = lineWidthDp.dp.toPx())
            )
        }

        Column {
            Text("Compass: ${handheldDeviceAzimuth.toInt()}")
            Text("Locator: ${locatorAzimuth.toInt()}")
            Text("H Delta: ${horizontalDelta.toInt()}")
            Text("Pitch: ${handheldDevicePitch.toInt()}")
            Text("Elevation: ${locatorElevation.toInt()}")
            Text("V Delta: ${verticalDelta.toInt()}")
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