package com.steampigeon.flightmanager.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.res.Configuration
import android.location.Location
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.mutualmobile.composesensors.SensorDelay
import com.mutualmobile.composesensors.rememberAccelerometerSensorState
import com.mutualmobile.composesensors.rememberMagneticFieldSensorState
import com.steampigeon.flightmanager.RocketScreen
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.data.LocatorArmedMessageState
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.RocketState
import java.text.DecimalFormat
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.log
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlin.toString

private lateinit var launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>
private const val messageTimeout = 2000

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterialApi::class)
@Composable
fun HomeScreen(
    navController: NavHostController,
    viewModel: RocketViewModel = viewModel(),
    textToSpeech: TextToSpeech?,
    modifier: Modifier
) {
    val context = LocalContext.current
    val permissionsState = rememberMultiplePermissionsState(
        when (Build.VERSION.SDK_INT) {
            in 1..27 -> {
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.CAMERA,
                )
            }
            in 28..30 -> {
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.CAMERA,
                    Manifest.permission.FOREGROUND_SERVICE,
                )
            }
            in 31..33 -> {
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.CAMERA,
                    Manifest.permission.FOREGROUND_SERVICE,
                )
            }
            else -> {
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.CAMERA,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE,
                )
            }
        }
    )
    val allPermissionsGranted = permissionsState.allPermissionsGranted
    val bluetoothPermissionState = permissionsState.permissions.find { it.permission == Manifest.permission.BLUETOOTH }
    val locationPermissionState = permissionsState.permissions.find { it.permission == Manifest.permission.ACCESS_FINE_LOCATION }
// Request permissions
    LaunchedEffect(allPermissionsGranted) {
        if (!allPermissionsGranted) 
            permissionsState.launchMultiplePermissionRequest()
    }
    // Initialize Bluetooth Companion Device Manager. Needs time to set up or app crashes.
    launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Handle successful pairing
            BluetoothManagerRepository.updateLocatorDevice(result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE))
            BluetoothManagerRepository.locatorDevice.value!!.createBond()
            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Paired)
            Log.d("Pairing", "Changing state to Paired")
        } else {
            // Handle pairing failure
            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.PairingFailed)
            Log.d("Pairing", "Changing state to PairingFailed")
        }
    }
    val bluetoothConnectionState = BluetoothManagerRepository.bluetoothConnectionState.collectAsState().value
    LaunchedEffect(bluetoothConnectionState) {
        manageBlueToothState(context, bluetoothConnectionState, textToSpeech)
    }
    var drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var isMapLoaded by remember { mutableStateOf(false) }
    val uiSettings = remember { MapUiSettings(compassEnabled = false,
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
    LaunchedEffect(rocketState.flightState) {
        if (armedState && rocketState.flightState != null)
            textToSpeech?.speak(locatorConfig.deviceName + "," + rocketState.flightState.toString() + "," + rocketState.altitudeAboveGroundLevel + "meters", TextToSpeech.QUEUE_FLUSH, null, null)
    }
    val lastPreLaunchMessageAge = System.currentTimeMillis() - rocketState.lastPreLaunchMessageTime
    val orientation = LocalConfiguration.current.orientation

    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    var trackerLocation by remember { mutableStateOf<Location?>(null) }
    LaunchedEffect(locationPermissionState?.hasPermission) {
        if (locationPermissionState?.hasPermission == true) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { trackerLocation = it }
        }
    }
    val accelerometerState = rememberAccelerometerSensorState(sensorDelay = SensorDelay.Normal)
    val magneticFieldState = rememberMagneticFieldSensorState(sensorDelay = SensorDelay.Normal)
    val azimuth = viewModel.handheldDeviceAzimuth.collectAsState().value
    val lastAzimuth = viewModel.lastHandheldDeviceAzimuth.collectAsState().value
    val azimuthChange = ((lastAzimuth - azimuth + 540) % 360) - 180
    val locatorLatLng = LatLng(rocketState.latitude, rocketState.longitude)
    LaunchedEffect(accelerometerState) {
        if (trackerLocation?.longitude != 0.0 && accelerometerState.isAvailable && magneticFieldState.isAvailable) {
            viewModel.handheldDeviceOrientation(accelerometerState, magneticFieldState, orientation == Configuration.ORIENTATION_LANDSCAPE)
            viewModel.locatorVector(LatLng(trackerLocation?.latitude ?: 0.0, trackerLocation?.longitude ?: 0.0), locatorLatLng)
        }
    }
    val distanceToLocator = viewModel.locatorDistance.collectAsState().value
    val azimuthToLocator = viewModel.locatorAzimuth.collectAsState().value
    val handheldDevicePitch = viewModel.handheldDevicePitch.collectAsState().value
    val locatorElevation = viewModel.locatorElevation.collectAsState().value

    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
        CameraPreviewScreen(azimuth, azimuthToLocator, handheldDevicePitch, locatorElevation)
    }
    else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = modifier.height(IntrinsicSize.Min)
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
                                    text = "App settings",
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
                                contentDescription = stringResource(id = R.string.application_settings)
                            ) }
                        )
                        if (bluetoothConnectionState == BluetoothConnectionState.Connected) {
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        text = "Receiver settings",
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
                                    contentDescription = stringResource(id = R.string.receiver_settings)
                                ) }
                            )
                        }
                        if (lastPreLaunchMessageAge < messageTimeout) {
                            if (!BluetoothManagerRepository.armedState.value) {
                                NavigationDrawerItem(
                                    label = {
                                        Text(
                                            text = "Locator settings",
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
                                            contentDescription = stringResource(id = R.string.locator_settings)
                                        )
                                    }
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
                                            contentDescription = stringResource(id = R.string.deployment_test)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        ) {
            Scaffold(
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        text = { Text("Menu") },
                        icon = { Icon(Icons.Filled.Menu, contentDescription = "") },
                        expanded = false,
                        onClick = { scope.launch { drawerState.apply { if (isClosed) open() else close() } } }
                    )
                },
                floatingActionButtonPosition = FabPosition.Start
            ) {
                val trackerLatLng = LatLng(trackerLocation?.latitude ?: 0.0, trackerLocation?.longitude ?: 0.0)
                if (trackerLocation != null && trackerLocation?.longitude != 0.0) {
                    if (accelerometerState.isAvailable && magneticFieldState.isAvailable) {
                        var previousDistanceToLocator by remember { mutableIntStateOf(0) }
                        var autoTargetMode by remember { mutableStateOf(true) }
                        var autoZoomMode by remember { mutableStateOf(true) }
                        var cameraPositionStateTarget by remember { mutableStateOf(LatLng(0.0, 0.0)) }
                        var cameraPositionStateZoom by remember { mutableFloatStateOf(12f) }
                        val cameraPositionState = rememberCameraPositionState() {
                            position = CameraPosition.Builder().target(trackerLatLng)
                                .zoom(cameraPositionStateZoom).bearing(azimuth).build()
                        }
                        val state = rememberUpdatedMarkerState(LatLng(rocketState.latitude, rocketState.longitude))
                        var showControls by remember { mutableStateOf(false) }
                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            onMapLoaded = { isMapLoaded = true },
                            cameraPositionState = cameraPositionState,
                            properties = properties,
                            uiSettings = uiSettings,
                            onMapClick = { showControls = !showControls }
                        ) {
                            Marker(
                                state = state,
                                title = locatorConfig.deviceName,
                                snippet = DecimalFormat("#,###").format(distanceToLocator).toString() + "m",
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
                            //ScaleBar( //crashes
                                //modifier = modifier
                                    //.align(Alignment.BottomStart)
                                //    .padding(16.dp),
                                //cameraPositionState = cameraPositionState,
                            //)
                        }
                        if (isMapLoaded) {
                            // Update camera position when markerPosition changes
                            val coroutineScope = rememberCoroutineScope()
                            LaunchedEffect(azimuth) {
                                if (abs(azimuthChange) > 2 && !cameraPositionState.isMoving) {
                                    // Animate map to rotate to new bearing, with smooth transition between 359 and 0 degrees
                                    coroutineScope.launch {
                                        if (autoZoomMode) {
                                            if (rocketState.latitude.toInt() != 0 || rocketState.longitude.toInt() != 0) {
                                                if (abs((distanceToLocator - previousDistanceToLocator).toFloat() / (previousDistanceToLocator + 1)) > 0.1) {
                                                    cameraPositionStateZoom = 23 - log(distanceToLocator.toFloat(), 2.5f)
                                                    previousDistanceToLocator = distanceToLocator
                                                }
                                            }
                                        } else
                                            cameraPositionStateZoom = cameraPositionState.position.zoom
                                        cameraPositionStateTarget = if (autoTargetMode) { trackerLatLng } else { cameraPositionState.position.target }
                                        cameraPositionState.animate(
                                            update = CameraUpdateFactory.newCameraPosition(
                                                CameraPosition(cameraPositionStateTarget, cameraPositionStateZoom, 0f, lastAzimuth + azimuthChange)
                                            ),
                                            durationMs = 500
                                        )
                                        /*cameraPositionState.animate(
                                        update = CameraUpdateFactory.newLatLngBounds(
                                            LatLngBounds(LatLng(min(location!!.latitude, rocketData.latitude), min(location!!.longitude, rocketData.longitude)), // Southwest corner
                                                LatLng(max(location!!.latitude, rocketData.latitude), max(location!!.longitude, rocketData.longitude)) // Northeast corner
                                            ), 1
                                        ),
                                        durationMs = 500
                                    )*/
                                    }
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
                                        modifier = Modifier.padding(0.dp).heightIn(min = 200.dp),
                                        verticalArrangement = Arrangement.Top,
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        if (showControls) {
                                            Button(
                                                onClick = {
                                                    if (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorArmedMessageState.Idle
                                                        || BluetoothManagerRepository.locatorArmedMessageState.value == LocatorArmedMessageState.AckUpdated
                                                    )
                                                        BluetoothManagerRepository.updateLocatorArmedMessageState(LocatorArmedMessageState.SendRequested)
                                                },
                                                modifier = Modifier.padding(4.dp).size(width = 120.dp, height = 40.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                val armedStateText = if (armedState) stringResource(R.string.armed_state_armed) else stringResource(R.string.armed_state_disarmed)
                                                Text(
                                                    when {
                                                        armedState && (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorArmedMessageState.Idle
                                                            || BluetoothManagerRepository.locatorArmedMessageState.value == LocatorArmedMessageState.AckUpdated) ->
                                                                stringResource(id = R.string.armed_state_armed)
                                                        armedState && BluetoothManagerRepository.locatorArmedMessageState.value == LocatorArmedMessageState.SendFailure ->
                                                            stringResource(id = R.string.bluetooth_state_disconnected)
                                                        armedState -> stringResource(id = R.string.armed_state_disarming)
                                                        !armedState && (BluetoothManagerRepository.locatorArmedMessageState.value == LocatorArmedMessageState.Idle
                                                                || BluetoothManagerRepository.locatorArmedMessageState.value == LocatorArmedMessageState.AckUpdated) ->
                                                            stringResource(id = R.string.armed_state_disarmed)
                                                        !armedState && BluetoothManagerRepository.locatorArmedMessageState.value == LocatorArmedMessageState.SendFailure ->
                                                            stringResource(id = R.string.bluetooth_state_disconnected)
                                                        !armedState -> stringResource(id = R.string.armed_state_arming)
                                                        else -> "Unknown"
                                                    }
                                                )
                                                LaunchedEffect(armedState) {
                                                    textToSpeech?.speak(armedStateText, TextToSpeech.QUEUE_FLUSH, null, null)
                                                }
                                            }
                                            Button(
                                                onClick = { autoTargetMode = !autoTargetMode },
                                                modifier = Modifier.padding(4.dp).size(width = 120.dp, height = 40.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text(when (autoTargetMode) { true -> "Auto center" false -> "Manual center" }) }
                                            Button(
                                                onClick = { autoZoomMode = !autoZoomMode },
                                                modifier = Modifier.padding(4.dp).size(width = 120.dp, height = 40.dp),
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
                                                BluetoothConnectionState.NoDevicesAvailable -> "Waiting for locator"
                                                BluetoothConnectionState.Connected -> ""
                                                BluetoothConnectionState.Disconnected -> "Receiver disconnected"
                                                else -> "Undefined state"
                                            },
                                        style = typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = modifier)
                                }
                            }
                        }
                        if (bluetoothConnectionState == BluetoothConnectionState.Connected) {
                            LocatorStats(rocketState, distanceToLocator, locatorConfig, modifier, textToSpeech)
                        }
                    }
                }
            }
        }
    }
}

fun manageBlueToothState(context: Context, bluetoothConnectionState: BluetoothConnectionState, textToSpeech: TextToSpeech?) {
    val tag = "MainScreen"
    when (bluetoothConnectionState) {
        BluetoothConnectionState.Starting -> {
            Log.d(tag, "Calling enableBluetooth")
            enableBluetooth(context)
        }
        BluetoothConnectionState.Enabled, BluetoothConnectionState.NoDevicesAvailable -> {
            if (BluetoothManagerRepository.locatorDevice.value == null) {
                Log.d(tag, "Calling selectBluetoothDevice")
                selectBluetoothDevice(context, bluetoothConnectionState)
            } else {
                if (BluetoothManagerRepository.locatorDevice.value?.bondState != BluetoothDevice.BOND_BONDED) {
                    BluetoothManagerRepository.locatorDevice.value?.createBond()
                    Log.d(tag, "Changing state from Enabled to Pairing")
                    BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Pairing)
                } else {
                    Log.d(tag, "Changing state from Enabled to Paired")
                    BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Paired)
                }
            }
        }
        //BluetoothConnectionState.Connected ->
        //    textToSpeech?.speak("connected to receiver", TextToSpeech.QUEUE_FLUSH, null, null)
        else -> {}
    }
}

@SuppressLint("MissingPermission")
fun enableBluetooth(context: Context) : Boolean? {
    val tag = "enableBlueTooth"
    val bluetoothManager= context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter = bluetoothManager.adapter

    when (bluetoothAdapter?.isEnabled) {
        true -> {
            Log.d(tag, "Changing state to Enabled")
            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Enabled)
        }
        false -> {
            val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            context.startActivity(enableBluetoothIntent)
            Log.d(tag, "Changing state to Enabling")
            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Enabling)
        }
        null -> {
            Log.d(tag, "Changing state to NotSupported")
            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.NotSupported)
        }
    }
    return bluetoothAdapter?.isEnabled
}

fun selectBluetoothDevice(context: Context, bluetoothConnectionState: BluetoothConnectionState) {
    val tag = "selectBluetoothDevice"
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
        Log.d(tag, "Launch association")
        deviceManager.associate(pairingRequest, object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                launcher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                Log.d(tag, "onDeviceFound: ${chooserLauncher.toString()}")
            }

            override fun onAssociationPending(intentSender: IntentSender) {
                super.onAssociationPending(intentSender)
                Log.d(tag, "onAssociationPending: ${intentSender.toString()}")
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                super.onAssociationCreated(associationInfo)
                Log.d(tag, "onAssociationCreated: ${associationInfo.toString()}")
            }

            override fun onFailure(error: CharSequence?) {
                // Handle no devices found or "don't allow" user selection
                BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.PairingFailed)
                Log.d(tag, "onFailure: $error")
            }
        }, null)
        BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.AssociateStart)
    }
}

fun unpairBluetoothDevice() {
    if (BluetoothManagerRepository.locatorDevice.value != null) {
        try {
            val removeBondMethod = BluetoothManagerRepository.locatorDevice.value!!.javaClass.getMethod("removeBond")
            removeBondMethod.invoke(BluetoothManagerRepository.locatorDevice.value)
        } catch (e: Exception) {
            e.printStackTrace()
            // Handle the exception, maybe log it or show a message to the user
        }
    }
    BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Idle)
}

@Composable
fun LocatorStats(rocketState: RocketState, distanceToLocator: Int, locatorConfig: LocatorConfig, modifier: Modifier, textToSpeech: TextToSpeech?) {
    var userMoved by remember { mutableStateOf(false) }
    var columnWidth by remember { mutableStateOf(0) }
    var columnHeight by remember { mutableStateOf(0) }
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val initialPositionX = configuration.screenWidthDp.dp.value.toFloat() * context.resources.displayMetrics.density - columnWidth * 1.3f
    val initialPositionY = configuration.screenHeightDp.dp.value.toFloat() * context.resources.displayMetrics.density - columnHeight * 2.2f
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    if (!userMoved && columnWidth > 0) {
        offsetX = initialPositionX
        offsetY = initialPositionY
        userMoved = true
    }
    Column (
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .clickable{
                if (BluetoothManagerRepository.armedState.value && rocketState.flightState != null)
                    textToSpeech?.speak(locatorConfig.deviceName + "," + rocketState.flightState.toString() + "," + rocketState.altitudeAboveGroundLevel + "meters", TextToSpeech.QUEUE_FLUSH, null, null)
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            //.size(200.dp, 100.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x805D6F96))
            .padding(8.dp)
            .onSizeChanged { size ->
                columnWidth = size.width
                columnHeight = size.height
            }
            .defaultMinSize(minWidth = 160.dp)
        ,
        //verticalArrangement = Arrangement.Top,
        //horizontalAlignment = Alignment.Start
    ) {
        Row {
            Text(text = locatorConfig.deviceName)
            Text(text = " ".repeat(RocketViewModel.DEVICE_NAME_LENGTH).take(RocketViewModel.DEVICE_NAME_LENGTH - locatorConfig.deviceName.length))
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
            Text(text = " ")
            val batteryLevel = rocketState.batteryLevel.coerceIn(0..7)
            val drawableResourceID = context.resources.getIdentifier("battery_${batteryLevel}_bar", "drawable", context.packageName)
            val stringResourceID = context.resources.getIdentifier("battery_${batteryLevel}_bar", "string", context.packageName)
            Icon(
                painter = painterResource(drawableResourceID),
                contentDescription = stringResource(stringResourceID)
            )
        }
        Text(text = "Dist: ${DecimalFormat("#,###").format(distanceToLocator)} m")
        if (BluetoothManagerRepository.armedState.value) {
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = when (rocketState.flightState) {
                    FlightStates.WaitingForLaunch -> "Waiting For Launch"
                    FlightStates.Launched -> "Launched"
                    FlightStates.Burnout -> "Burnout"
                    FlightStates.Noseover -> "Noseover"
                    FlightStates.DroguePrimaryDeployed -> "Drogue Primary Deployed"
                    FlightStates.DrogueBackupDeployed -> "Drogue Backup Deployed"
                    FlightStates.MainPrimaryDeployed -> "Main Primary Deployed"
                    FlightStates.MainBackupDeployed -> "Main Backup Deployed"
                    FlightStates.Landed -> "Landed"
                    else -> ""
                },
                style = typography.bodyLarge,
            )
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = "AGL: ${rocketState.altitudeAboveGroundLevel}m",
                style = typography.bodyLarge,
            )
        }
        else {
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = "AGL: ${rocketState.altitudeAboveGroundLevel}m",
                style = typography.bodyLarge,
                color = if (rocketState.altimeterStatus) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = "Acc: ${DecimalFormat("#0.00").format(round(rocketState.gForce * 100) / 100)} ${rocketState.orientation}",
                style = typography.bodyLarge,
                color = if (rocketState.accelerometerStatus) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            //Spacer(modifier = Modifier.weight(1f))
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = when (locatorConfig.deployMode) {
                    DeployMode.DroguePrimaryDrogueBackup, DeployMode.DroguePrimaryMainPrimary -> {
                        "Drogue Primary: " + (locatorConfig.droguePrimaryDeployDelay / 10).toString() + "." + (locatorConfig.droguePrimaryDeployDelay % 10).toString() + "s"
                    }

                    DeployMode.MainPrimaryMainBackup -> {
                        "Main Primary: " + locatorConfig.mainPrimaryDeployAltitude.toString() + "m"
                    }

                    DeployMode.DrogueBackupMainBackup -> {
                        "Drogue Backup: " + (locatorConfig.drogueBackupDeployDelay / 10).toString() + "." + (locatorConfig.drogueBackupDeployDelay % 10).toString() + "s"
                    }
                    else -> ""
                },
                style = typography.bodyLarge,
                color = if (rocketState.deployChannel1Armed) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = when (locatorConfig.deployMode) {
                    DeployMode.DroguePrimaryDrogueBackup -> {
                        "Drogue Backup: " + (locatorConfig.drogueBackupDeployDelay / 10).toString() + "." + (locatorConfig.drogueBackupDeployDelay % 10).toString() + "s"
                    }

                    DeployMode.MainPrimaryMainBackup, DeployMode.DrogueBackupMainBackup -> {
                        "Main Backup: " + locatorConfig.mainBackupDeployAltitude.toString() + "m"
                    }

                    DeployMode.DroguePrimaryMainPrimary -> {
                        "Main Primary: " + locatorConfig.mainPrimaryDeployAltitude.toString() + "m"
                    }
                    else -> ""
                },
                style = typography.bodyLarge,
                color = if (rocketState.deployChannel2Armed) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
        }
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