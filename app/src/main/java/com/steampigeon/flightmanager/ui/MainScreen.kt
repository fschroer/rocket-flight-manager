package com.steampigeon.flightmanager.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
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
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
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
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
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
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.Protocol
import com.steampigeon.flightmanager.data.RocketState
import com.steampigeon.flightmanager.data.SensorHealth
import com.steampigeon.flightmanager.ui.RocketViewModel.Companion.G_FORCE_MS2
import com.steampigeon.flightmanager.ui.RocketViewModel.Companion.RAD2DEG
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import kotlin.math.log
import kotlin.math.round


private const val messageTimeout = 2000
private const val drogueVelocityThreshold = -30
private const val mainVelocityThreshold = -8
private const val landingAltitudeThreshold = 30
private const val minimumSpokenAGLVelocity = 2 * 9.8

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
    var drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var isMapLoaded by remember { mutableStateOf(false) }
    val mapUiSettings = remember { MapUiSettings(compassEnabled = false,
        indoorLevelPickerEnabled = false,
        mapToolbarEnabled = false,
        myLocationButtonEnabled = false,
        tiltGesturesEnabled = false,
        zoomControlsEnabled = false) }
    val properties by remember {
        mutableStateOf(MapProperties(isMyLocationEnabled = true,
            mapType = MapType.SATELLITE))
    }
    val locatorConfig by viewModel.remoteLocatorConfig.collectAsState()
    val rocketState by viewModel.rocketState.collectAsState()
    val armedState = BluetoothManagerRepository.armedState.collectAsState().value
    var previousAGL by remember { mutableIntStateOf(0) }
    var apogeeSpoken by remember { mutableStateOf(false)}
    var launchedState by remember { mutableStateOf(false)}
    var droguePrimaryState by remember { mutableStateOf(false)}
    var drogueBackupState by remember { mutableStateOf(false)}
    var mainPrimaryState by remember { mutableStateOf(false)}
    var mainBackupState by remember { mutableStateOf(false)}
    var drogueDeploySpoken by remember { mutableStateOf(false)}
    var mainDeploySpoken by remember { mutableStateOf(false)}
    var landingSpoken by remember { mutableStateOf(false)}
    val lastPreLaunchMessageAge = System.currentTimeMillis() - rocketState.lastPreLaunchMessageTime
    val orientation = LocalConfiguration.current.orientation

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val fusedOrientationClient = remember { LocationServices.getFusedOrientationProviderClient(context) }
    var trackerLocation by remember { mutableStateOf<Location?>(null) }
    val bluetoothPermissionState = permissionsState.permissions.find { it.permission == android.Manifest.permission.BLUETOOTH }
    val locationPermissionState = permissionsState.permissions.find { it.permission == Manifest.permission.ACCESS_FINE_LOCATION }
    if (locationPermissionState?.hasPermission == true) {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            trackerLocation = location
        }
    }
    val accelerometerState = rememberAccelerometerSensorState(sensorDelay = SensorDelay.Normal)
    val hasCompass = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_COMPASS)
    val magneticFieldState = rememberMagneticFieldSensorState(sensorDelay = SensorDelay.Normal)
    val locatorGPSLock = remember { mutableStateOf(false) }
    val locatorLatLng = LatLng(rocketState.latitude, rocketState.longitude)
    LaunchedEffect(accelerometerState) {
        if (accelerometerState.isAvailable)
            viewModel.handheldDeviceOrientation(accelerometerState, magneticFieldState, trackerLocation, orientation == Configuration.ORIENTATION_LANDSCAPE)
    }
    LaunchedEffect(trackerLocation, locatorLatLng) {
        trackerLocation?.let {
            val vector = viewModel.locatorVector(LatLng(it.latitude, it.longitude), locatorLatLng)
            viewModel.updateLocatorVector(vector)
        }
        locatorGPSLock.value = locatorLatLng.latitude != 0.0 && locatorLatLng.longitude != 0.0
    }
    val distanceToLocator = viewModel.locatorDistance.collectAsState().value
    var launchLocation by remember { mutableStateOf(LatLng(0.0, 0.0))}
    val vectorFromLaunch = viewModel.locatorVector(launchLocation, locatorLatLng)
    val azimuthToLocator = viewModel.locatorAzimuth.collectAsState().value
    val ordinalToLocator = viewModel.locatorOrdinal.collectAsState().value
    val handheldDevicePitch = viewModel.handheldDevicePitch.collectAsState().value
    val locatorElevation = viewModel.locatorElevation.collectAsState().value
    val locatorArmedMessageState = BluetoothManagerRepository.locatorArmedMessageState.collectAsState().value
    var receivedState by remember { mutableStateOf(FlightStates.WaitingForLaunch)}
    var noseoverTime by remember { mutableLongStateOf(0) }

    LaunchedEffect(rocketState.flightState) {
        if (armedState && rocketState.flightState > receivedState) {
            receivedState = rocketState.flightState
            if (rocketState.flightState >= FlightStates.Launched && !launchedState) {
                launchedState = true
                launchLocation = LatLng(rocketState.latitude, rocketState.longitude)
            }
//                FlightStates.Burnout ->
//                    textToSpeech?.speak(locatorConfig.deviceName + "," + rocketState.flightState.toString() + "," + rocketState.altitudeAboveGroundLevel + "meters", TextToSpeech.QUEUE_FLUSH, null, null)
            if (rocketState.flightState >= FlightStates.Noseover && noseoverTime == 0L) {
                noseoverTime = System.currentTimeMillis()
                if (!apogeeSpoken) {
                    apogeeSpoken = true
                    textToSpeech?.speak("Apogee, ${rocketState.altitudeAboveGroundLevel.toInt()} meters.", TextToSpeech.QUEUE_ADD, null, null)
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
    }
    LaunchedEffect(rocketState.altitudeAboveGroundLevel) {
        if (armedState) {
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
                                    "${vectorFromLaunch.distance} meters ${vectorFromLaunch.ordinal} of launch point. . . . . ." else ""}",
                            TextToSpeech.QUEUE_ADD, null, null
                        )
                    }
                    if (!landingSpoken && rocketState.altitudeAboveGroundLevel < landingAltitudeThreshold) {
                        landingSpoken = true
                            textToSpeech.speak(
                                "Landing${if (rocketState.gpsStatus == SensorHealth.Ok)
                                " ${vectorFromLaunch.distance} meters ${vectorFromLaunch.ordinal} of launch point." else ", location unknown."},",
                                TextToSpeech.QUEUE_ADD, null, null)
                    }
                }
            }
            if (rocketState.flightState >= FlightStates.DroguePrimaryDeployed) {
                if (!drogueDeploySpoken && rocketState.drogueDeployDetected) {
                    drogueDeploySpoken = true
                    textToSpeech?.speak("Drogue deployed.", TextToSpeech.QUEUE_ADD, null, null)
                }
            }
            if (rocketState.flightState >= FlightStates.MainPrimaryDeployed) {
                if (!mainDeploySpoken && rocketState.mainDeployDetected) {
                    mainDeploySpoken = true
                    drogueDeploySpoken = true
                    textToSpeech?.speak("Main deployed.", TextToSpeech.QUEUE_ADD, null, null)
                }
            }
        }
    }

    val kalmanGainTarget = 0.1f
    val kalmanGainZoom = 0.05f
    val kalmanGainBearing = 0.05f
    val azimuth = viewModel.handheldDeviceAzimuth.collectAsState().value
    val lastAzimuth = viewModel.lastHandheldDeviceAzimuth.collectAsState().value
    val azimuthChange = (((azimuth - lastAzimuth + 540) % 360) - 180) * kalmanGainBearing
    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
        CameraPreviewScreen(azimuth, azimuthToLocator, handheldDevicePitch, locatorElevation)
    }
    else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = modifier
                        .height(IntrinsicSize.Min)
                        .width(IntrinsicSize.Max),
                    drawerContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    //drawerContentColor = MaterialTheme.colorScheme.secondary
                    drawerShape = RoundedCornerShape(bottomEnd = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(0.dp)
                    ) {
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    text = stringResource(R.string.application_settings),
                                    style = typography.titleLarge,
                                )
                            },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.apply { close() } }
                                navController.navigate(RocketScreen.AppSettings.name)
                            },
                            icon = { Icon(
                                painter = painterResource(R.drawable.settings_applications),
                                contentDescription = stringResource(R.string.application_settings)
                            ) }
                        )
                        if (bluetoothConnectionState == BluetoothConnectionState.Connected) {
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        text = stringResource(R.string.receiver_settings),
                                        style = typography.titleLarge,
                                    )
                                },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.apply { close() } }
                                    navController.navigate(RocketScreen.ReceiverSettings.name)
                                },
                                icon = { Icon(
                                    painter = painterResource(R.drawable.radio),
                                    contentDescription = stringResource(R.string.receiver_settings)
                                ) }
                            )
                        }
                        if (lastPreLaunchMessageAge < messageTimeout) {
                            if (!armedState) {
                                NavigationDrawerItem(
                                    label = {
                                        Text(
                                            text = stringResource(R.string.locator_settings),
                                            style = typography.titleLarge,
                                        )
                                    },
                                    selected = false,
                                    onClick = {
                                        scope.launch { drawerState.apply { close() } }
                                        navController.navigate(RocketScreen.LocatorSettings.name)
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.navigation),
                                            contentDescription = stringResource(R.string.locator_settings)
                                        )
                                    }
                                )
                                NavigationDrawerItem(
                                    label = {
                                        Text(
                                            text = stringResource(R.string.flight_profiles),
                                            style = typography.titleLarge,
                                        )
                                    },
                                    selected = false,
                                    onClick = {
                                        scope.launch { drawerState.apply { close() } }
                                        navController.navigate(RocketScreen.FlightProfiles.name)
                                    },
                                    icon = { Icon(
                                        painter = painterResource(R.drawable.u_turn_right),
                                        contentDescription = stringResource(R.string.flight_profiles)
                                    ) }
                                )
                            }
                            else {
                                NavigationDrawerItem(
                                    label = {
                                        Text(
                                            text = stringResource(R.string.deployment_test),
                                            style = typography.titleLarge,
                                        )
                                    },
                                    selected = false,
                                    onClick = {
                                        scope.launch { drawerState.apply { close() } }
                                        navController.navigate(RocketScreen.DeploymentTest.name)
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.bomb),
                                            contentDescription = stringResource(R.string.deployment_test)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        ) {
            var scaffoldSize by remember { mutableStateOf(IntSize(0, 0)) }
            Scaffold(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    scaffoldSize = coordinates.size
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        text = { Text("Menu") },
                        icon = { Icon(Icons.Filled.Menu, contentDescription = "") },
                        expanded = false,
                        onClick = { scope.launch { drawerState.apply { if (isClosed) open() else close() } } }
                    )
                },
                floatingActionButtonPosition = FabPosition.Start
            ) { padding ->
                trackerLocation?.let {
                    //if (accelerometerState.isAvailable) {
                        var previousDistanceToLocator by remember { mutableIntStateOf(0) }
                        var autoTargetMode by remember { mutableStateOf(true) }
                        var autoZoomMode by remember { mutableStateOf(true) }
                        var cameraPositionStateTarget by remember { mutableStateOf(LatLng(0.0, 0.0)) }
                        val state = rememberMarkerState()
                        LaunchedEffect(rocketState.latitude, rocketState.longitude) {
                            state.position = LatLng(rocketState.latitude, rocketState.longitude)
                        }
                        var showControls by remember { mutableStateOf(false) }
                        var cameraPositionStateZoom by remember { mutableFloatStateOf(12f) }
                        val cameraPositionState = rememberCameraPositionState() {
                            position = CameraPosition.Builder().target(LatLng(it.latitude, it.longitude))
                                .zoom(cameraPositionStateZoom).bearing(azimuth).build()
                        }
                        var userGesture by remember { mutableStateOf(false)}
                        var lastUserGestureTime by remember { mutableLongStateOf(0) }
                        LaunchedEffect(Unit) {
                            snapshotFlow { cameraPositionState.cameraMoveStartedReason }
                                .collect { reason ->
                                    when (reason) {
                                        CameraMoveStartedReason.GESTURE -> {
                                            Log.d("ZoomSource", "User gesture triggered zoom")
                                            userGesture = true
                                            lastUserGestureTime = System.currentTimeMillis()
                                        }
                                        CameraMoveStartedReason.DEVELOPER_ANIMATION -> {
                                            Log.d("ZoomSource", "Programmatic zoom triggered")
                                        }
                                        CameraMoveStartedReason.API_ANIMATION -> {
                                            Log.d("ZoomSource", "Zoom via built-in API animation")
                                        }
                                        else -> {
                                            Log.d("ZoomSource", "Unknown zoom source")
                                        }
                                    }
                                }
                        }
                        GoogleMap(
                            modifier = Modifier.fillMaxSize(), //.padding(padding),
                            onMapLoaded = { isMapLoaded = true },
                            cameraPositionState = cameraPositionState,
                            properties = properties,
                            uiSettings = mapUiSettings,
                            onMapClick = { showControls = !showControls }
                        ) {
//                            val rocket_green = BitmapFactory.decodeResource(context.resources, R.drawable.landed_rocket_located).scale(96, 96, false)
//                            val rocket_red = BitmapFactory.decodeResource(context.resources, R.drawable.landed_rocket_lost).scale(96, 96, false)
                            Marker(
                                state = state,
                                //anchor = Offset(0.5f, 0.5f),
                                //title = locatorConfig.deviceName,
                                //snippet = DecimalFormat("#,###").format(distanceToLocator).toString() + "m",
                                icon = BitmapDescriptorFactory.defaultMarker(
                                    if (lastPreLaunchMessageAge < messageTimeout) BitmapDescriptorFactory.HUE_GREEN
                                    else BitmapDescriptorFactory.HUE_RED
                                )
//                                icon = BitmapDescriptorFactory.fromBitmap(
//                                    if (lastPreLaunchMessageAge < messageTimeout) rocket_green
//                                    else rocket_red
//                                )
                            )
                            Circle(
                                center = locatorLatLng,
                                fillColor = Color(if (lastPreLaunchMessageAge < messageTimeout) 0x3000ff00 else 0x30ff0000),
                                radius = (4 * rocketState.hdop).toDouble(),
                                strokeColor = Color(if (lastPreLaunchMessageAge < messageTimeout) 0x8000ff00 else 0x80ff0000),
                                strokeWidth = 1f,
                            )
                        }
                        Row(
                            modifier = modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            //Spacer(modifier = modifier)
                            Image(
                                painter = painterResource(id = R.drawable.compass),
                                contentDescription = "Compass",
                                modifier = Modifier.rotate(360 - lastAzimuth),
                            )
                            ScaleBar(
                                modifier = modifier,
                                    //.align(Alignment.BottomStart)
                                    //.padding(16.dp),
                                width = 192.dp,
                                height = 64.dp,
                                cameraPositionState = cameraPositionState,
                                textColor = MaterialTheme.colorScheme.secondary,
                                lineColor = MaterialTheme.colorScheme.primary,
                                shadowColor = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (isMapLoaded) {
//                            // Update camera position when markerPosition changes
//                            val coroutineScope = rememberCoroutineScope()
//                            val distanceToLocatorChange = abs((distanceToLocator - previousDistanceToLocator).toFloat() / (previousDistanceToLocator + 1))
//                            LaunchedEffect(distanceToLocator) {
//                                if (distanceToLocatorChange > 0.1) {
//                                    // Animate map to zoom and center
//                                    if (autoZoomMode) {
//                                        if (rocketState.latitude.toInt() != 0 || rocketState.longitude.toInt() != 0) {
//                                            if (distanceToLocatorChange > 0.1) {
//                                                cameraPositionStateZoom = 23 - log(distanceToLocator.toFloat(), 2.5f)
//                                                previousDistanceToLocator = distanceToLocator
//                                            }
//                                        }
//                                    } else
//                                        cameraPositionStateZoom = cameraPositionState.position.zoom
//                                    cameraPositionStateTarget = if (autoTargetMode) {
//                                        LatLng(trackerLocation.latitude, trackerLocation.longitude)
//                                    } else {
//                                        cameraPositionState.position.target
//                                    }
//                                    coroutineScope.launch {
//                                        cameraPositionState.animate(
//                                            update = CameraUpdateFactory.newCameraPosition(
//                                                CameraPosition(cameraPositionStateTarget, cameraPositionStateZoom, 0f, cameraPositionState.position.bearing)
//                                            ),
//                                            durationMs = 250
//                                        )
//                                        /*cameraPositionState.animate(
//                                        update = CameraUpdateFactory.newLatLngBounds(
//                                            LatLngBounds(LatLng(min(location!!.latitude, rocketData.latitude), min(location!!.longitude, rocketData.longitude)), // Southwest corner
//                                                LatLng(max(location!!.latitude, rocketData.latitude), max(location!!.longitude, rocketData.longitude)) // Northeast corner
//                                            ), 1
//                                        ),
//                                        durationMs = 500
//                                    )*/
//                                    }
//                                //}
//                            }
                            if (System.currentTimeMillis() - lastUserGestureTime > 5000) {
//                                if (abs(azimuthChange) > 0.5)
                                    viewModel.updateLastHandheldDeviceAzimuth((lastAzimuth + azimuthChange + 360) % 360)
                                val newTarget = LatLng(it.latitude, it.longitude)
                                val newZoom = if (distanceToLocator > 1) 23 - log(distanceToLocator.toFloat(), 2.3f) else 23f
                                cameraPositionStateTarget = if (autoTargetMode)
                                    LatLng(
                                        cameraPositionStateTarget.latitude + (newTarget.latitude - cameraPositionStateTarget.latitude) * kalmanGainTarget,
                                        cameraPositionStateTarget.longitude + (newTarget.longitude - cameraPositionStateTarget.longitude) * kalmanGainTarget
                                    )
                                else cameraPositionState.position.target
                                cameraPositionStateZoom = if (autoZoomMode && (rocketState.latitude.toInt() != 0 || rocketState.longitude.toInt() != 0))
                                    cameraPositionStateZoom + (newZoom - cameraPositionStateZoom) * kalmanGainZoom else cameraPositionState.position.zoom
                                val cameraPositionStateAzimuth = if (hasCompass) lastAzimuth else cameraPositionState.position.bearing
                                cameraPositionState.position = CameraPosition(cameraPositionStateTarget, cameraPositionStateZoom, 0f, cameraPositionStateAzimuth)
                            }
                            else {
                                cameraPositionStateTarget = cameraPositionState.position.target
                                cameraPositionStateZoom = cameraPositionState.position.zoom
                                viewModel.updateLastHandheldDeviceAzimuth(cameraPositionState.position.bearing)
                            }
                        }
                        Column(
                            modifier = modifier,
                            verticalArrangement = Arrangement.SpaceAround
                        ) {
                            Row(
                                modifier = modifier,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Spacer(modifier = modifier.weight(1f))
                                Column(
                                    modifier = Modifier
                                        .padding(0.dp)
                                        .heightIn(min = 200.dp),
                                    verticalArrangement = Arrangement.Top,
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    if (showControls) {
                                        Button( //Arm/disarm
                                            onClick = {
                                                if (locatorArmedMessageState == LocatorMessageState.Idle
                                                    || locatorArmedMessageState == LocatorMessageState.AckUpdated
                                                ) {
                                                    BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorMessageState.SendRequested)
                                                    viewModel.updateArmedState()
                                                }
                                            },
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .size(width = 120.dp, height = 40.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            val armedStateText = if (armedState) stringResource(R.string.armed_state_armed) else stringResource(R.string.armed_state_disarmed)
                                            Text(
                                                when {
                                                    locatorArmedMessageState == LocatorMessageState.NotAcknowledged ->
                                                        stringResource(id = R.string.update_not_acknowledged)
                                                    armedState && (locatorArmedMessageState == LocatorMessageState.Idle
                                                        || locatorArmedMessageState == LocatorMessageState.AckUpdated) ->
                                                            stringResource(id = R.string.armed_state_armed)
                                                    armedState && locatorArmedMessageState == LocatorMessageState.SendFailure ->
                                                        stringResource(id = R.string.bluetooth_state_disconnected)
                                                    armedState -> stringResource(id = R.string.armed_state_disarming)
                                                    !armedState && (locatorArmedMessageState == LocatorMessageState.Idle
                                                            || locatorArmedMessageState == LocatorMessageState.AckUpdated) ->
                                                        stringResource(id = R.string.armed_state_disarmed)
                                                    !armedState && locatorArmedMessageState == LocatorMessageState.SendFailure ->
                                                        stringResource(id = R.string.bluetooth_state_disconnected)
                                                    !armedState -> stringResource(id = R.string.armed_state_arming)
                                                    else -> "Unknown"
                                                }
                                            )
                                            LaunchedEffect(armedState) {
                                                textToSpeech?.speak(armedStateText, TextToSpeech.QUEUE_FLUSH, null, null)
                                            }
                                        }
                                        Button( //Auto-center
                                            onClick = { autoTargetMode = !autoTargetMode },
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .size(width = 120.dp, height = 40.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text(when (autoTargetMode) { true -> "Auto center" false -> "Manual center" }) }
                                        Button( //Auto-zoom
                                            onClick = { autoZoomMode = !autoZoomMode },
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .size(width = 120.dp, height = 40.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) { Text(when (autoZoomMode) { true -> "Auto zoom" false -> "Manual zoom" }) }
                                    }
                                }
                            }
                            Row(
                                modifier = modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                //Spacer(modifier = modifier)
                                Text(
                                    textAlign = TextAlign.Center,
                                    text =
                                        when (bluetoothConnectionState) {
                                            BluetoothConnectionState.Starting,
                                            BluetoothConnectionState.Enabling -> "Enabling bluetooth"
                                            BluetoothConnectionState.NotEnabled -> "Bluetooth not enabled"
                                            BluetoothConnectionState.NotSupported -> "Bluetooth not supported"
                                            BluetoothConnectionState.Enabled,
                                            BluetoothConnectionState.AssociateStart,
                                            BluetoothConnectionState.AssociateWait,
                                            BluetoothConnectionState.PairingFailed -> "Waiting for receiver"
                                            BluetoothConnectionState.Pairing -> "Pairing with receiver"
                                            BluetoothConnectionState.Paired,
                                            BluetoothConnectionState.NoDevicesAvailable -> "Waiting for receiver"
                                            BluetoothConnectionState.Connected -> ""
                                            BluetoothConnectionState.Disconnected -> "Receiver disconnected"
                                            else -> "Undefined state"
                                        },
                                    style = typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = modifier)
                            }
                            if (lastPreLaunchMessageAge < messageTimeout) {
                                Row(
                                    modifier = modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    PulsingText(
                                        text = (if (!armedState) "Disarmed" else "")
                                                + (if (!armedState && !locatorGPSLock.value) "\n" else "")
                                                + (if (!locatorGPSLock.value) "No GPS" else ""),
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                        style = typography.displayLarge,
                                    )
                                }
                            }
                            else {
                                Row(
                                    modifier = modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    PulsingText(
                                        text = "No Locator",
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                        style = typography.displayLarge,
                                    )
                                }
                                }
                        }
                        if (bluetoothConnectionState == BluetoothConnectionState.Connected) {
                            LocatorStats(rocketState, armedState, distanceToLocator, locatorConfig, viewModel, scaffoldSize, textToSpeech, modifier)
                        }
                    //}
                }
            }
        }
    }
}

@Composable
fun LocatorStats(rocketState: RocketState, armedState: Boolean, distanceToLocator: Int, locatorConfig: LocatorConfig, viewModel: RocketViewModel, scaffoldSize: IntSize, textToSpeech: TextToSpeech?, modifier: Modifier) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    var columnWidth by remember { mutableStateOf(0) }
    var columnHeight by remember { mutableStateOf(0) }
    var locatorStatisticsOffset by remember { mutableStateOf(IntOffset(0, 0))}
    LaunchedEffect(Unit) {
        locatorStatisticsOffset = viewModel.locatorStatisticsOffset.value
    }
    Column(
        modifier = modifier
            .offset { locatorStatisticsOffset }
            .clickable {
                if (armedState)
                    textToSpeech?.speak(
                        locatorConfig.deviceName + "," + rocketState.flightState.toString() + "," + rocketState.altitudeAboveGroundLevel + "meters",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        null
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
            //.size(200.dp, 100.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x805D6F96))
            .padding(8.dp)
            .onSizeChanged { size ->
                columnWidth = size.width
                columnHeight = size.height
                locatorStatisticsOffset = IntOffset(
                    (locatorStatisticsOffset.x).coerceIn(16, scaffoldSize.width - columnWidth - 48),
                    (locatorStatisticsOffset.y).coerceIn(16, scaffoldSize.height - columnHeight - 48)
                )
            }
            .defaultMinSize(minWidth = 160.dp),
        //verticalArrangement = Arrangement.Top,
        //horizontalAlignment = Alignment.Start
    ) {
        Row {
            Text(text = locatorConfig.deviceName)
            Text(text = " ".repeat(Protocol.DEVICE_NAME_LENGTH).take(Protocol.DEVICE_NAME_LENGTH - locatorConfig.deviceName.length))
            Icon(
                painter = painterResource(R.drawable.rocket_md),
                contentDescription = stringResource(id = R.string.locator_satellites)
            )
            Text(text = buildAnnotatedString {
                withStyle(
                    style = SpanStyle(
                        fontSize = 10.sp, // Adjust the size of the superscript text
                        baselineShift = BaselineShift.Superscript
                    )
                ) {
                    append(rocketState.satellites.toString())
                }
            })
            if (!armedState) {
                //Text(text = " ")
                val locatorBatteryLevel = rocketState.locatorBatteryLevel.coerceIn(0..7)
                val locatorBatteryLevelDrawableResourceID = context.resources.getIdentifier("battery_${locatorBatteryLevel}_bar", "drawable", context.packageName)
                val locatorBatteryLevelStringResourceID = context.resources.getIdentifier("battery_${locatorBatteryLevel}_bar", "string", context.packageName)
                Icon(
                    painter = painterResource(locatorBatteryLevelDrawableResourceID),
                    contentDescription = stringResource(locatorBatteryLevelStringResourceID)
                )
                val receiverBatteryLevel = rocketState.receiverBatteryLevel.coerceIn(0..7)
                val receiverBatteryLevelDrawableResourceID = context.resources.getIdentifier("battery_${receiverBatteryLevel}_bar", "drawable", context.packageName)
                val receiverBatteryLevelStringResourceID = context.resources.getIdentifier("battery_${receiverBatteryLevel}_bar", "string", context.packageName)
                Icon(
                    painter = painterResource(R.drawable.radio),
                    contentDescription = null
                )
                Icon(
                    painter = painterResource(receiverBatteryLevelDrawableResourceID),
                    contentDescription = stringResource(receiverBatteryLevelStringResourceID)
                )
            }
        }
        Text(
            text = "Dist: ${if (rocketState.latitude != 0.0) DecimalFormat("#,###").format(distanceToLocator) + " m" else stringResource(R.string.unknown)}",
            color = if (rocketState.gpsStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
        )
        Text(
            //modifier = modifier.padding(start = 4.dp),
            text = "AGL: ${DecimalFormat("#0.0").format(round(rocketState.altitudeAboveGroundLevel * 100) / 100)}m",
            style = typography.bodyLarge,
            color = if (rocketState.baroStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
        )
        Text(
            //modifier = modifier.padding(start = 4.dp),
            text = "Acc: ${DecimalFormat("#0.0").format(round(rocketState.accelerometer.x / G_FORCE_MS2 * 10) / 10)}, " +
                    "${DecimalFormat("#0.0").format(round(rocketState.accelerometer.y / G_FORCE_MS2 * 10) / 10)}, " +
                    DecimalFormat("#0.0").format(round(rocketState.accelerometer.z / G_FORCE_MS2 * 10) / 10),
            style = typography.bodyLarge,
            color = if (rocketState.imuStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
        )
        Text(
            //modifier = modifier.padding(start = 4.dp),
            text = "Gyro: ${DecimalFormat("#0.0").format(round(rocketState.gyro.x * RAD2DEG * 10) / 10)}, " +
                    "${DecimalFormat("#0.0").format(round(rocketState.gyro.y * RAD2DEG * 10) / 10)}, " +
                    DecimalFormat("#0.0").format(round(rocketState.gyro.z * RAD2DEG * 10) / 10),
            style = typography.bodyLarge,
            color = if (rocketState.imuStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
        )
        if (armedState) {
            Text(
                //modifier = modifier.padding(start = 4.dp),
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
                style = typography.bodyLarge,
            )
        }
        else {
            //Spacer(modifier = Modifier.weight(1f))
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = when (locatorConfig.deploymentChannel1Mode) {
                    DeployMode.DroguePrimary ->
                        "Drogue Primary: " + (locatorConfig.droguePrimaryDeployDelay / 10).toString() + "." + (locatorConfig.droguePrimaryDeployDelay % 10).toString() + "s"
                    DeployMode.DrogueBackup ->
                        "Drogue Backup: " + (locatorConfig.drogueBackupDeployDelay / 10).toString() + "." + (locatorConfig.drogueBackupDeployDelay % 10).toString() + "s"
                    DeployMode.MainPrimary ->
                        "Main Primary: " + locatorConfig.mainPrimaryDeployAltitude.toString() + "m"
                    DeployMode.MainBackup ->
                        "Main Backup: " + locatorConfig.mainBackupDeployAltitude.toString() + "m"
                    else -> ""
                },
                style = typography.bodyLarge,
                color = if (rocketState.deployChannel1Armed) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = when (locatorConfig.deploymentChannel2Mode) {
                    DeployMode.DroguePrimary ->
                        "Drogue Primary: " + (locatorConfig.droguePrimaryDeployDelay / 10).toString() + "." + (locatorConfig.droguePrimaryDeployDelay % 10).toString() + "s"
                    DeployMode.DrogueBackup ->
                        "Drogue Backup: " + (locatorConfig.drogueBackupDeployDelay / 10).toString() + "." + (locatorConfig.drogueBackupDeployDelay % 10).toString() + "s"
                    DeployMode.MainPrimary ->
                        "Main Primary: " + locatorConfig.mainPrimaryDeployAltitude.toString() + "m"
                    DeployMode.MainBackup ->
                        "Main Backup: " + locatorConfig.mainBackupDeployAltitude.toString() + "m"
                    else -> ""
                },
                style = typography.bodyLarge,
                color = if (rocketState.deployChannel2Armed) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = when (locatorConfig.deploymentChannel3Mode) {
                    DeployMode.DroguePrimary ->
                        "Drogue Primary: " + (locatorConfig.droguePrimaryDeployDelay / 10).toString() + "." + (locatorConfig.droguePrimaryDeployDelay % 10).toString() + "s"
                    DeployMode.DrogueBackup ->
                        "Drogue Backup: " + (locatorConfig.drogueBackupDeployDelay / 10).toString() + "." + (locatorConfig.drogueBackupDeployDelay % 10).toString() + "s"
                    DeployMode.MainPrimary ->
                        "Main Primary: " + locatorConfig.mainPrimaryDeployAltitude.toString() + "m"
                    DeployMode.MainBackup ->
                        "Main Backup: " + locatorConfig.mainBackupDeployAltitude.toString() + "m"
                    else -> ""
                },
                style = typography.bodyLarge,
                color = if (rocketState.deployChannel3Armed) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = when (locatorConfig.deploymentChannel4Mode) {
                    DeployMode.DroguePrimary ->
                        "Drogue Primary: " + (locatorConfig.droguePrimaryDeployDelay / 10).toString() + "." + (locatorConfig.droguePrimaryDeployDelay % 10).toString() + "s"
                    DeployMode.DrogueBackup ->
                        "Drogue Backup: " + (locatorConfig.drogueBackupDeployDelay / 10).toString() + "." + (locatorConfig.drogueBackupDeployDelay % 10).toString() + "s"
                    DeployMode.MainPrimary ->
                        "Main Primary: " + locatorConfig.mainPrimaryDeployAltitude.toString() + "m"
                    DeployMode.MainBackup ->
                        "Main Backup: " + locatorConfig.mainBackupDeployAltitude.toString() + "m"
                    else -> ""
                },
                style = typography.bodyLarge,
                color = if (rocketState.deployChannel4Armed) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
        }
        if (rocketState.latitude != 0.0 && rocketState.longitude != 0.0)
            Text(
                modifier = modifier,
                text = BigDecimal(rocketState.latitude).setScale(6, RoundingMode.HALF_UP).toString() + "," +
                        BigDecimal(rocketState.longitude).setScale(6, RoundingMode.HALF_UP).toString()
            )
    }
    LaunchedEffect(locatorStatisticsOffset) {
        viewModel.updateLocatorStatisticsOffset(locatorStatisticsOffset)
        viewModel.saveUserPreferences()
    }
}

@Composable
fun rememberUpdatedMarkerState(newPosition: LatLng) =
    remember { MarkerState(position = newPosition) }
        .apply { position = newPosition }

@Composable
fun CameraPreviewScreen(handheldDeviceAzimuth: Float, locatorAzimuth: Float, handheldDevicePitch: Float, locatorElevation: Float) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }

    // Request camera permissions
    // (You will need to handle permissions properly)

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
                    // Setup CameraX preview use case
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = surfaceProvider
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
        Canvas(modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    zoomRatio = if (zoomRatio == 1f) (camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f)
                    else 1f
                }
            }
        ) {
            drawLine(
                color = crosshairColor,
                start = Offset(centerPxX, centerPxY - (locatorWindowPositionSize + crosshairLineLength).dp.toPx()),
                end = Offset(centerPxX, centerPxY - locatorWindowPositionSize.dp.toPx()),
                strokeWidth = lineWidthDp.dp.toPx(),
            )
            drawLine(
                color = crosshairColor,
                start = Offset(centerPxX + (locatorWindowPositionSize + crosshairLineLength).dp.toPx(), centerPxY),
                end = Offset(centerPxX + locatorWindowPositionSize.dp.toPx(), centerPxY),
                strokeWidth = lineWidthDp.dp.toPx(),
            )
            drawLine(
                color = crosshairColor,
                start = Offset(centerPxX, centerPxY + (locatorWindowPositionSize + crosshairLineLength).dp.toPx()),
                end = Offset(centerPxX, centerPxY + locatorWindowPositionSize.dp.toPx()),
                strokeWidth = lineWidthDp.dp.toPx(),
            )
            drawLine(
                color = crosshairColor,
                start = Offset(centerPxX - (locatorWindowPositionSize + crosshairLineLength).dp.toPx(), centerPxY),
                end = Offset(centerPxX - locatorWindowPositionSize.dp.toPx(), centerPxY),
                strokeWidth = lineWidthDp.dp.toPx(),
            )
//            drawRect(
//                color = crosshairColor,
//                topLeft = Offset(centerPxX - 50.dp.toPx(), centerPxY - 50.dp.toPx()),
//                size = Size(100.dp.toPx(), 100.dp.toPx()),
//                style = Stroke(width = 2.dp.toPx())
//            )
            drawCircle(
                color = locatorColor,
                radius = locatorWindowPositionSize.dp.toPx(),
                center = Offset(centerPxX + horizontalDelta * locatorWindowScale, centerPxY + verticalDelta * locatorWindowScale),
                style = Stroke(width = lineWidthDp.dp.toPx())
            )
        }
        Column{
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

@Composable
fun PulsingText(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    minAlpha: Float = 0f,
    maxAlpha: Float = 1f,
    durationMillis: Int = 500
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

    Text(
        text = text,
        color = color,
        textAlign = textAlign,
        style = style,
        modifier = Modifier.alpha(alpha)
    )
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

    // Animate opacity between 0f and 1f
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "BlinkAlpha"
    )

    // Toggle visibility on a timer
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMillis)
            visible = !visible
        }
    }

    Text(
        text = text,
        color = color,
        textAlign = textAlign,
        style = style,
        modifier = Modifier.alpha(alpha)
    )
}

@Composable
fun ExitAppButton(activity: Activity) {
    var showDialog by remember { mutableStateOf(false) }

    Button(onClick = { showDialog = true }) {
        Text("Exit App")
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Exit App") },
            text = { Text("Are you sure you want to exit the app?") },
            confirmButton = {
                Button(onClick = {
                    showDialog = false
                    activity.finish()
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                Button(onClick = { showDialog = false }) {
                    Text("No")
                }
            }
        )
    }
}