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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ServiceConnection
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
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
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.RocketScreen
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.RocketUiState
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

//val bluetoothConnectionManager = BluetoothConnectionManager()

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    navController: NavHostController,
    viewModel: RocketViewModel,
    modifier: Modifier
) {
    val context = LocalContext.current
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    )
    val allPermissionsGranted = permissionsState.allPermissionsGranted
    val bluetoothPermissionGranted = permissionsState.permissions[0].hasPermission
    val locationPermissionGranted = permissionsState.permissions[1].hasPermission
// Request permissions
    LaunchedEffect(allPermissionsGranted) {
        if (!allPermissionsGranted)
            permissionsState.launchMultiplePermissionRequest()
    }
    // Initialize Bluetooth Companion Device Manager. Needs time to set up or app crashes.
    InitializeCompanionDeviceManager()
    var drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    var trackerLocation by remember { mutableStateOf<Location?>(null) }
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
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "Connect",
                    style = typography.titleLarge,
                    modifier = Modifier.padding(dimensionResource(R.dimen.padding_medium))
                )
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = "Connect to receiver",
                            style = typography.titleMedium,
                            modifier = Modifier.padding(dimensionResource(R.dimen.padding_small))
                        )
                    },
                    selected = false,
                    onClick = { scope.launch { drawerState.apply { close() }}
                        unpairBluetoothDevice() }
                )
                HorizontalDivider()
                Text(
                    text = "Flight control",
                    style = typography.titleLarge,
                    modifier = Modifier.padding(dimensionResource(R.dimen.padding_medium))
                )
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = "Arm",
                            style = typography.titleMedium,
                            modifier = Modifier.padding(dimensionResource(R.dimen.padding_small))
                        )
                    },
                    selected = false,
                    onClick = { /*TODO*/ }
                )
                HorizontalDivider()
                Text(text = "Flight data",
                    style = typography.titleLarge,
                    modifier = Modifier.padding(dimensionResource(R.dimen.padding_medium))
                )
                NavigationDrawerItem(
                    label = {
                        Text(
                        text = "Download map",
                        style = typography.titleMedium,
                        modifier = Modifier.padding(dimensionResource(R.dimen.padding_small))
                        )
                            },
                    selected = false,
                    onClick = { scope.launch { drawerState.apply { close() }}
                            navController.navigate(RocketScreen.Download.name)
                    }
                )
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = "Export flight path",
                            style = typography.titleMedium,
                            modifier = Modifier.padding(dimensionResource(R.dimen.padding_small))
                        )
                            },
                    selected = false,
                    onClick = { /*TODO*/ }
                )
                HorizontalDivider()
                Text(
                    text = "Settings",
                    style = typography.titleLarge,
                    modifier = Modifier.padding(dimensionResource(R.dimen.padding_medium))
                )
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = "Locator settings",
                            style = typography.titleMedium,
                            modifier = Modifier.padding(dimensionResource(R.dimen.padding_small))
                        )
                            },
                    selected = false,
                    onClick = { scope.launch { drawerState.apply { close() }}
                        navController.navigate(RocketScreen.LocatorSettings.name) }
                )
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = "Receiver settings",
                            style = typography.titleMedium,
                            modifier = Modifier.padding(dimensionResource(R.dimen.padding_small))
                        )
                            },
                    selected = false,
                    onClick = { /*TODO*/ }
                )
            }
        }
    ) {
        Scaffold(
            //topBar = {TopAppBar()},
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    text = { Text("Menu") },
                    icon = { Icon(Icons.Filled.Menu, contentDescription = "") },
                    expanded = false,
                    onClick = {
                        scope.launch {
                            drawerState.apply {
                                if (isClosed) open() else close()
                            }
                        }
                    }
                )
            },
            floatingActionButtonPosition = FabPosition.Start
        ) { contentPadding ->
            LaunchedEffect(locationPermissionGranted) {
                if (locationPermissionGranted) {
                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { locationResult: Location? ->
                            trackerLocation = locationResult
                        }
                }
            }
            //Log.d("MainScreen", "Check Bluetooth state change: $viewModel.uiState.value.bluetoothConnectionState")
            LaunchedEffect (BluetoothManagerRepository.bluetoothConnectionState.value) {
                Log.d("MainScreen", "Bluetooth state change: " + BluetoothManagerRepository.bluetoothConnectionState.toString())
                //Start Bluetooth data handler service
                if (BluetoothManagerRepository.locatorDevice.value != null)
                    context.startService(
                        Intent(
                            context,
                            BluetoothService()::class.java
                        )
                    )
            }
            val tag = "MainScreen"
            when (BluetoothManagerRepository.bluetoothConnectionState.value) {
                BluetoothConnectionState.NotStarted -> {
                    Log.d(tag, "Calling enableBluetooth")
                    enableBluetooth(context)
                }
                BluetoothConnectionState.Enabled -> {
                    if (BluetoothManagerRepository.locatorDevice.value == null) {
                        Log.d(tag, "Calling selectBluetoothDevice")
                        selectBluetoothDevice(context)
                    } else {
                        if (BluetoothManagerRepository.locatorDevice.value?.bondState != BluetoothDevice.BOND_BONDED) {
                            BluetoothManagerRepository.locatorDevice.value?.createBond()
                            Log.d(tag, "Changing state from Enabled to Pairing")
                            BluetoothManagerRepository.updateBluetoothConnectionState(
                                BluetoothConnectionState.Pairing
                            )
                        } else {
                            Log.d(tag, "Changing state from Enabled to Paired")
                            BluetoothManagerRepository.updateBluetoothConnectionState(
                                BluetoothConnectionState.Paired
                            )
                        }
                    }
                }
                else -> {}
            }
            if (trackerLocation != null && trackerLocation!!.longitude != 0.0) {
                var trackerLatLng = LatLng(trackerLocation!!.latitude, trackerLocation!!.longitude)
                val accelerometerState = rememberAccelerometerSensorState(sensorDelay = SensorDelay.Normal)
                val magneticFieldState = rememberMagneticFieldSensorState(sensorDelay = SensorDelay.Normal)
                var lastAccelerometer = FloatArray(3)
                var lastMagnetometer = FloatArray(3)
                var rotationMatrix = FloatArray(9)
                var orientation = FloatArray(3)
                val azimuthHistorySize = 10
                val compassUpdateCountCheck = 10
                var azimuthHistory = remember { FloatArray(azimuthHistorySize) { 0f } }
                var azimuth by remember { mutableFloatStateOf(0f) }
                var averageAzimuth by remember { mutableFloatStateOf(0f) }
                var distanceToLocator by remember { mutableIntStateOf(0) }
                var previousDistanceToLocator by remember { mutableIntStateOf(0) }
                if (accelerometerState.isAvailable && magneticFieldState.isAvailable) {
                    lastAccelerometer[0] = accelerometerState.xForce
                    lastAccelerometer[1] = accelerometerState.yForce
                    lastAccelerometer[2] = accelerometerState.zForce
                    lastMagnetometer[0] = magneticFieldState.xStrength
                    lastMagnetometer[1] = magneticFieldState.yStrength
                    lastMagnetometer[2] = magneticFieldState.zStrength
                    SensorManager.getRotationMatrix(
                        rotationMatrix,
                        null,
                        lastAccelerometer,
                        lastMagnetometer
                    );
                    SensorManager.getOrientation(rotationMatrix, orientation);
                    azimuth = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat()
                    // To do: adjust average when transitioning between 359 and 0 degrees
                    for (i in 0..azimuthHistorySize - 2) {
                        azimuthHistory[i] = azimuthHistory[i + 1]
                    }
                    averageAzimuth = azimuthHistory.average().toFloat()
                    when {
                        averageAzimuth > 270 && azimuth < 90 ->
                            azimuth = azimuth + 360
                        azimuth > 270 && averageAzimuth < 90 ->
                            azimuth = azimuth - 360
                    }
                    when {
                        averageAzimuth > 360 -> {
                            for (i in 0..azimuthHistorySize - 2) {
                                azimuthHistory[i] = azimuthHistory[i] - 360
                            }
                        }
                        averageAzimuth < 0 -> {
                            for (i in 0..azimuthHistorySize - 2) {
                                azimuthHistory[i] = azimuthHistory[i] + 360
                            }
                        }
                    }
                    azimuthHistory[azimuthHistorySize - 1] = azimuth
                    averageAzimuth = azimuthHistory.average().toFloat()
                    var autoTargetMode by remember { mutableStateOf(true)}
                    var autoZoomMode by remember { mutableStateOf(true)}
                    var cameraPositionStateTarget by remember { mutableStateOf(LatLng(0.0, 0.0)) }
                    var cameraPositionStateZoom by remember { mutableFloatStateOf(12f)}
                    val cameraPositionState = rememberCameraPositionState() {
                        position = CameraPosition.Builder().target(trackerLatLng).zoom(cameraPositionStateZoom).bearing(azimuth).build()
                    }
                    val rocketData = RocketData(context, viewModel)
                    val locatorLatLng = LatLng(rocketData.latitude, rocketData.longitude)
                    val state = rememberUpdatedMarkerState(LatLng(rocketData.latitude, rocketData.longitude))
                    var showControls by remember { mutableStateOf(false) }
                    distanceToLocator = computeDistanceBetween(trackerLatLng, locatorLatLng)
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
                            title = rocketData.deviceName,
                            snippet = DecimalFormat("#,###").format(distanceToLocator).toString() + "m",
                        )
                        Circle(
                            center = locatorLatLng,
                            fillColor = Color(0x30ff0000),
                            radius = (4 * rocketData.hdop).toDouble(),
                            strokeColor = Color(0x80ff0000),
                            strokeWidth = 2f,
                        )
                    }
                    if (isMapLoaded) {
                        // Update camera position when markerPosition changes
                        val coroutineScope = rememberCoroutineScope()
                        var lastAverageAzimuth by remember { mutableFloatStateOf(0f) }
                        LaunchedEffect(averageAzimuth) {
                            if (abs(averageAzimuth - lastAverageAzimuth) > 2 && !cameraPositionState.isMoving) {
                                // Animate map to rotate to new bearing, with smooth transition between 359 and 0 degrees
                                coroutineScope.launch {
                                    var newBearing = 0f
                                    var duration = 0
                                    when {
                                        lastAverageAzimuth > 270 && averageAzimuth < 90 -> {
                                            newBearing = azimuthHistory.average().toFloat() + 360
                                            duration =
                                                (averageAzimuth - lastAverageAzimuth + 360).toInt() * 10 + 100
                                        }

                                        lastAverageAzimuth < 90 && averageAzimuth > 270 -> {
                                            newBearing = azimuthHistory.average().toFloat() - 360
                                            duration =
                                                (lastAverageAzimuth - averageAzimuth + 360).toInt() * 10 + 1
                                        }

                                        else -> {
                                            newBearing = azimuthHistory.average().toFloat()
                                            duration =
                                                (abs(lastAverageAzimuth - averageAzimuth)).toInt() * 10 + 1
                                        }
                                    }
                                    if (autoZoomMode) {
                                        if (rocketData.latitude.toInt() != 0 || rocketData.longitude.toInt() != 0) {
                                            if (abs((distanceToLocator - previousDistanceToLocator).toFloat() / (previousDistanceToLocator + 1)) > 0.1) {
                                                cameraPositionStateZoom =
                                                    23 - log2(distanceToLocator.toFloat())
                                                previousDistanceToLocator = distanceToLocator
                                            }
                                        }
                                    }
                                    else
                                        cameraPositionStateZoom = cameraPositionState.position.zoom
                                    cameraPositionStateTarget = if (autoTargetMode){
                                        trackerLatLng
                                    } else {
                                        cameraPositionState.position.target
                                    }
                                    cameraPositionState.animate(
                                        update = CameraUpdateFactory.newCameraPosition(
                                            CameraPosition(
                                                cameraPositionStateTarget,
                                                cameraPositionStateZoom,
                                                0f,
                                                newBearing
                                            )
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
                                lastAverageAzimuth = azimuthHistory.average().toFloat()
                            }
                        }
                        Column (
                            modifier = modifier,
                            verticalArrangement = Arrangement.SpaceAround
                        ) {
                            Row(
                                modifier = modifier,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Spacer(modifier = modifier.weight(1f))
                                Column(
                                    modifier = modifier,
                                    verticalArrangement = Arrangement.Bottom,
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    if (showControls) {
                                        Button(
                                            onClick = { autoTargetMode = !autoTargetMode }) {
                                            Text(
                                                "Center: " + when (autoTargetMode) {
                                                    true -> "Auto"
                                                    false -> "Man"
                                                }
                                            )
                                        }
                                        Button(onClick = {
                                            autoZoomMode = !autoZoomMode
                                        }) {
                                            Text(
                                                "Zoom: " + when (autoZoomMode) {
                                                    true -> "Auto"
                                                    false -> "Man"
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = modifier,
                                horizontalArrangement = Arrangement.SpaceAround,
                            ) {
                                Spacer(modifier = modifier)
                                Text(
                                    textAlign = TextAlign.Center,
                                    text =
                                    when (BluetoothManagerRepository.bluetoothConnectionState.value) {
                                        BluetoothConnectionState.NotStarted,
                                        BluetoothConnectionState.Enabling -> "Enabling bluetooth"
                                        BluetoothConnectionState.NotEnabled -> "Bluetooth not enabled"
                                        BluetoothConnectionState.NotSupported -> "Device doesn't support Bluetooth"
                                        BluetoothConnectionState.Enabled,
                                        BluetoothConnectionState.SelectingDevices -> "Looking for receivers"
                                        BluetoothConnectionState.NoDevicesAvailable -> "No devices available"
                                        BluetoothConnectionState.Pairing -> "Pairing with receiver"
                                        BluetoothConnectionState.PairingFailed -> "Pairing Failed"
                                        BluetoothConnectionState.Paired, BluetoothConnectionState.Connected -> {
                                            if (viewModel.locatorDetected) ""
                                            else "Locator not detected"
                                        }
                                        BluetoothConnectionState.Disconnected -> "Receiver disconnected"
                                        else -> "Undefined state"
                                    },
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = modifier)
                            }
                            Spacer(modifier = modifier)
                            Row(
                                modifier = modifier,
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Spacer (modifier = modifier)
                                Spacer (modifier = modifier)
                                Column(
                                    modifier = modifier,
                                    verticalArrangement = Arrangement.Bottom,
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    if (BluetoothManagerRepository.bluetoothConnectionState.value ==
                                        BluetoothConnectionState.Paired
                                    ) {
                                        LocatorStats(viewModel, modifier)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private lateinit var launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>

@SuppressLint("MissingPermission")
@Composable
fun InitializeCompanionDeviceManager() {
    launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Handle successful pairing
            BluetoothManagerRepository.updateLocatorDevice(result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE))
            BluetoothManagerRepository.locatorDevice.value!!.createBond()
            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.Paired)
        } else {
            // Handle pairing failure
            BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.PairingFailed)
        }
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

fun selectBluetoothDevice(context: Context) {
    val tag = "selectBluetoothDevice"
    val deviceManager = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

    // Create an association request
    if (BluetoothManagerRepository.bluetoothConnectionState.value == BluetoothConnectionState.Enabled) {
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
                launcher.launch(
                    IntentSenderRequest.Builder(chooserLauncher).build()
                )
                //bluetoothConnectionState = BluetoothConnectionState.Pairing
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
        BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.SelectingDevices)
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
    BluetoothManagerRepository.updateBluetoothConnectionState(BluetoothConnectionState.NotStarted)
}

@Composable
fun LocatorStats(viewModel: RocketViewModel, modifier: Modifier) {
    if (viewModel.locatorDetected) {
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }
        Column (
            modifier = modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
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
            ,
            //verticalArrangement = Arrangement.Top,
            //horizontalAlignment = Alignment.Start
        ) {
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = "AGL: ${viewModel.uiState.value.altitudeAboveGroundLevel}m",
                style = typography.bodyLarge,
                color = if (viewModel.uiState.value.altimeterStatus) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = "Acc: ${round(viewModel.gForce * 100) / 100} ${viewModel.locatorOrientation}",
                style = typography.bodyLarge,
                color = if (viewModel.uiState.value.accelerometerStatus) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            //Spacer(modifier = Modifier.weight(1f))
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = when (viewModel.uiState.value.deployMode) {
                    DeployMode.kDroguePrimaryDrogueBackup, DeployMode.kDroguePrimaryMainPrimary -> {
                        "Drogue Primary: " + viewModel.uiState.value.droguePrimaryDeployDelay.toString() + "s"
                    }
                    DeployMode.kMainPrimaryMainBackup -> {
                        "Main Primary: " + viewModel.uiState.value.mainPrimaryDeployAltitude.toString() + "m"
                    }
                    DeployMode.kDrogueBackupMainBackup -> {
                        "Drogue Backup: " + viewModel.uiState.value.drogueBackupDeployDelay.toString() + "s"
                    }
                },
                style = typography.bodyLarge,
                color = if (viewModel.uiState.value.deployChannel1Armed) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            Text(
                //modifier = modifier.padding(start = 4.dp),
                text = when (viewModel.uiState.value.deployMode) {
                    DeployMode.kDroguePrimaryDrogueBackup -> {
                        "Drogue Backup: " + viewModel.uiState.value.drogueBackupDeployDelay.toString() + "s"
                    }
                    DeployMode.kMainPrimaryMainBackup, DeployMode.kDrogueBackupMainBackup -> {
                        "Main Backup: " + viewModel.uiState.value.mainBackupDeployAltitude.toString() + "m"
                    }
                    DeployMode.kDroguePrimaryMainPrimary -> {
                        "Main Primary: " + viewModel.uiState.value.mainPrimaryDeployAltitude.toString() + "m"
                    }
                },
                style = typography.bodyLarge,
                color = if (viewModel.uiState.value.deployChannel2Armed) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(modifier: Modifier = Modifier) {
    CenterAlignedTopAppBar(
        title = {
            Row() {
                Image(
                    modifier = Modifier
                        .size(dimensionResource(id = R.dimen.image_size))
                        .padding(dimensionResource(id = R.dimen.padding_small)),
                    painter = painterResource(R.drawable.rocket),
                    contentDescription = null
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = typography.headlineLarge,
                )
            }
        },
        modifier = modifier
    )
}

fun computeDistanceBetween(latLng1: LatLng, latLng2: LatLng): Int {
    val earthRadius = 6371000 // in meters

    val dLat = Math.toRadians(latLng2.latitude - latLng1.latitude)
    val dLon = Math.toRadians(latLng2.longitude - latLng1.longitude)

    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(latLng1.latitude)) * cos(Math.toRadians(latLng2.latitude)) *
            sin(dLon / 2) * sin(dLon / 2)

    val c = 2 * atan2(sqrt(a), sqrt(1 - a))

    return (earthRadius * c).toInt()
}

@Composable
fun RocketData(context: Context, viewModel: RocketViewModel): RocketUiState {
    val serviceData by viewModel.uiState.collectAsState()
    var service: BluetoothService? by remember { mutableStateOf(null) }
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as BluetoothService.LocalBinder).getService()
                viewModel.collectLocatorData(service!!)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
    }
    DisposableEffect(Unit) {
        val intent = Intent(context, BluetoothService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

        onDispose {
            context.unbindService(connection)
        }
    }
    if (service != null) {
        viewModel.collectLocatorData(service!!)
    }
    return serviceData
}

@Composable
fun rememberUpdatedMarkerState(newPosition: LatLng) =
    remember { MarkerState(position = newPosition) }
        .apply { position = newPosition }

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