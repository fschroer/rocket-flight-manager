package com.steampigeon.flightmanager.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.NavHostController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
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
import com.steampigeon.flightmanager.BluetoothConnectionManager
import com.steampigeon.flightmanager.BluetoothConnectionState
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.RocketScreen
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.RocketUiState
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

val bluetoothConnectionManager = BluetoothConnectionManager()

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
    bluetoothConnectionManager.InitializeCompanionDeviceManager()
    var drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    var trackerLocation by remember { mutableStateOf<Location?>(null) }
    var isMapLoaded by remember { mutableStateOf(false) }
    val uiSettings = remember { MapUiSettings(myLocationButtonEnabled = false, compassEnabled = false, zoomControlsEnabled = false) }
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
                        bluetoothConnectionManager.unpairBluetoothDevice() }
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
                    onClick = { /*TODO*/ }
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
        LaunchedEffect(locationPermissionGranted) {
            if (locationPermissionGranted) {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { locationResult: Location? ->
                        trackerLocation = locationResult
                    }
            }
        }
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
                    var compassUpdateCount by remember { mutableIntStateOf(0) }
                    val rocketData = RocketData(context, viewModel)
                    val locatorLatLng = LatLng(rocketData.latitude, rocketData.longitude)
                    val state = rememberUpdatedMarkerState(LatLng(rocketData.latitude, rocketData.longitude))
                    distanceToLocator = computeDistanceBetween(trackerLatLng, locatorLatLng)
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        onMapLoaded = { isMapLoaded = true },
                        cameraPositionState = cameraPositionState,
                        properties = properties,
                        uiSettings = uiSettings,
                        onMapClick = { compassUpdateCount = 0 }
                    ) {
                        bluetoothConnectionManager.maintainLocatorDevicePairing(context)
                        LaunchedEffect(bluetoothConnectionManager.bluetoothConnectionState) {
                            if (bluetoothConnectionManager.bluetoothConnectionState == BluetoothConnectionState.Paired)
                                initLocatorDeviceComms(context)
                        }
                        Marker(
                            state = state,
                            title = rocketData.deviceName,
                            snippet = DecimalFormat("#,###").format(distanceToLocator).toString() + "m",
                        )
                    }
                    if (isMapLoaded) {
                        // Update camera position when markerPosition changes
                        val coroutineScope = rememberCoroutineScope()
                        var lastAverageAzimuth by remember { mutableFloatStateOf(0f) }
                        LaunchedEffect(averageAzimuth) {
                            compassUpdateCount++
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
                                                    24 - log2(distanceToLocator.toFloat())
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
                                compassUpdateCount = 0
                                lastAverageAzimuth = azimuthHistory.average().toFloat()
                            }
                        }
                        Column(
                            modifier = modifier,
                            //.statusBarsPadding()
                            //.verticalScroll(rememberScrollState())
                            //.safeDrawingPadding()
                            //.padding(contentPadding),
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.End
                        ) {
                            if (bluetoothConnectionManager.bluetoothConnectionState ==
                                BluetoothConnectionState.Paired) {
                                    LocatorStats(viewModel)
                            }
                        }
                    }
                }
            }
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (bluetoothConnectionManager.bluetoothConnectionState) {
                    BluetoothConnectionState.NotStarted, BluetoothConnectionState.Enabled -> {
                        Text(
                            textAlign = TextAlign.Center,
                            text = "Receiver not connected",
                            color = Color.Red
                        )
                    }
                    BluetoothConnectionState.Enabling -> {
                        Text(
                            textAlign = TextAlign.Center,
                            text = "Enabling bluetooth",
                            color = Color.Red
                        )
                    }
                    BluetoothConnectionState.NotEnabled -> {
                        Text(
                            textAlign = TextAlign.Center,
                            text = "Bluetooth not enabled",
                            color = Color.Red
                        )
                    }
                    BluetoothConnectionState.NotSupported -> {
                        Text(
                            textAlign = TextAlign.Center,
                            text = "Device doesn't support Bluetooth",
                            color = Color.Red
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun LocatorStats(viewModel: RocketViewModel) {
    if (viewModel.locatorDetected) {
        Text(
            text = "AGL: ${viewModel.uiState.value.altitudeAboveGroundLevel}",
            color = if (viewModel.uiState.value.altimeterStatus) Color.Green else Color.Red,
            //textAlign = TextAlign.Left
        )
        val accelerometerTextColor =
            if (viewModel.uiState.value.accelerometerStatus) Color.Green else Color.Red
        Text(
            text = "AccX: ${viewModel.uiState.value.accelerometer.x}",
            color = accelerometerTextColor,
            //textAlign = TextAlign.Left
        )
        Text(
            text = "AccY: ${viewModel.uiState.value.accelerometer.y}",
            color = accelerometerTextColor,
            //textAlign = TextAlign.Left
        )
        Text(
            text = "AccZ: ${viewModel.uiState.value.accelerometer.z}",
            color = accelerometerTextColor,
            //textAlign = TextAlign.Left
        )
        //Spacer(modifier = Modifier.weight(1f))
        Text(
            text = when (viewModel.uiState.value.deployMode) {
                DeployMode.kDroguePrimaryDrogueBackup, DeployMode.kDroguePrimaryMainPrimary -> {
                    "Drogue Primary: " +
                            viewModel.uiState.value.droguePrimaryDeployDelay.toString() + "s"
                }

                DeployMode.kMainPrimaryMainBackup -> {
                    "Main Primary: " +
                            viewModel.uiState.value.mainPrimaryDeployAltitude.toString() + "m"
                }

                DeployMode.kDrogueBackupMainBackup -> {
                    "Drogue Backup: " +
                            viewModel.uiState.value.drogueBackupDeployDelay.toString() + "s"
                }
            },
            color = if (viewModel.uiState.value.deployChannel1Armed) Color.Green else Color.Red,
            //textAlign = TextAlign.Left
        )
        Text(
            text = when (viewModel.uiState.value.deployMode) {
                DeployMode.kDroguePrimaryDrogueBackup -> {
                    "Drogue Backup: " +
                            viewModel.uiState.value.drogueBackupDeployDelay.toString() + "s"
                }

                DeployMode.kMainPrimaryMainBackup, DeployMode.kDrogueBackupMainBackup -> {
                    "Main Backup: " +
                            viewModel.uiState.value.mainBackupDeployAltitude.toString() + "m"
                }

                DeployMode.kDroguePrimaryMainPrimary -> {
                    "Main Primary: " +
                            viewModel.uiState.value.mainPrimaryDeployAltitude.toString() + "m"
                }
            },
            color = if (viewModel.uiState.value.deployChannel2Armed) Color.Green else Color.Red,
            //textAlign = TextAlign.Left
        )
    } else {
        Text(
            text = "Locator not detected",
            textAlign = TextAlign.Center,
            color = Color.Red
        )
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

fun initLocatorDeviceComms(context: Context) {
    // Register broadcast receiver for bluetooth status events
    /*if (!bluetoothConnectionManager.receiverRegistered) {
        bluetoothConnectionManager.RegisterReceiver(context)
        bluetoothConnectionManager.sendBroadcast(context)
    }*/
    //Start Bluetooth data handler service
    if (bluetoothConnectionManager.locatorDevice != null)
        context.startService(
            Intent(
                context,
                BluetoothService::class.java
            ).putExtra("device", bluetoothConnectionManager.locatorDevice)
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