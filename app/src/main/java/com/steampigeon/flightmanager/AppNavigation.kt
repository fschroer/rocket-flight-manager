package com.steampigeon.flightmanager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.ui.AppSettingsScreen
import com.steampigeon.flightmanager.ui.DeploymentTestScreen
import com.steampigeon.flightmanager.ui.DevicePickerDialog
import com.steampigeon.flightmanager.ui.FlightProfilesScreen
import com.steampigeon.flightmanager.ui.HomeScreen
import com.steampigeon.flightmanager.ui.LocatorSettingsScreen
import com.steampigeon.flightmanager.ui.ReceiverSettingsScreen
import com.steampigeon.flightmanager.ui.RocketViewModel
import java.util.Locale

// ---------------------------------------------------------------------------
// Screen enum
// ---------------------------------------------------------------------------

enum class NavDestination(@StringRes val title: Int) {
    Start(title = R.string.app_name),
    AppSettings(title = R.string.application_settings),
    LocatorSettings(title = R.string.locator_settings),
    ReceiverSettings(title = R.string.receiver_settings),
    DeploymentTest(title = R.string.deployment_test),
    FlightProfiles(title = R.string.flight_profiles),
    Export(title = R.string.export),
}

// ---------------------------------------------------------------------------
// Top app bar
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RocketAppBar(
    currentScreen: NavDestination,
    canNavigateBack: Boolean,
    navigateUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(id = currentScreen.title))
                Image(
                    modifier = Modifier
                        .size(dimensionResource(id = R.dimen.image_size))
                        .padding(dimensionResource(id = R.dimen.padding_small))
                        .scale(2f),
                    painter = painterResource(R.mipmap.steam_pigeon_foreground),
                    contentDescription = null
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = modifier,
        navigationIcon = {
            if (canNavigateBack) {
                IconButton(onClick = navigateUp) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_button)
                    )
                }
            }
        }
    )
}

// ---------------------------------------------------------------------------
// Root composable
// ---------------------------------------------------------------------------

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RocketApp(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: RocketViewModel = viewModel()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentScreen = NavDestination.valueOf(
        backStackEntry?.destination?.route ?: NavDestination.Start.name
    )
    val orientation = LocalConfiguration.current.orientation

    // -----------------------------------------------------------------------
    // Text-to-speech
    // -----------------------------------------------------------------------

    var textToSpeech: TextToSpeech? by remember { mutableStateOf(null) }
    val voiceName by viewModel.voiceName.collectAsState()
    val voiceEnabled by viewModel.voiceEnabled.collectAsState()

    DisposableEffect(Unit) {
        onDispose { textToSpeech?.shutdown() }
    }

    LaunchedEffect(voiceEnabled, voiceName) {
        if (voiceEnabled) {
            textToSpeech?.shutdown()
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.language = Locale.US
                    val desiredVoice = textToSpeech?.voices?.find { it.name.contains(voiceName) }
                    if (desiredVoice != null) textToSpeech?.voice = desiredVoice
                }
            }
        } else {
            textToSpeech?.shutdown()
            textToSpeech = null
        }
    }

    // -----------------------------------------------------------------------
    // Runtime permissions
    // -----------------------------------------------------------------------

    val requiredPermissions = when {
        Build.VERSION.SDK_INT >= 34 -> listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.FOREGROUND_SERVICE,
            android.Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE,
        )
        Build.VERSION.SDK_INT >= 31 -> listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.FOREGROUND_SERVICE,
        )
        Build.VERSION.SDK_INT >= 28 -> listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.BLUETOOTH,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.FOREGROUND_SERVICE,
        )
        else -> listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.BLUETOOTH,
            android.Manifest.permission.CAMERA,
        )
    }

    val permissionsState = rememberMultiplePermissionsState(requiredPermissions)
    val allPermissionsGranted = permissionsState.allPermissionsGranted

    LaunchedEffect(allPermissionsGranted) {
        if (!allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        } else {
            viewModel.startService()
        }
    }

    // -----------------------------------------------------------------------
    // Service binding — btManager lives in BluetoothService, single instance
    // -----------------------------------------------------------------------

    var bluetoothService: BluetoothService? by remember { mutableStateOf(null) }
    var btManager: BluetoothManager? by remember { mutableStateOf(null) }

    if (allPermissionsGranted) {
        BindBluetoothService(context, viewModel) { svc ->
            bluetoothService = svc
            btManager = svc?.btManager
        }
    }

    // The connection state machine is now driven by BluetoothService's serviceScope
    // collector (Doze-exempt via connectedDevice foreground service type), so there
    // is no need to replay state machine events here on first bind.
    //
    // The only case still handled here is activity recreation while a reconnect is
    // mid-backoff: forceReconnect() cancels the pending delay and retries immediately
    // so the user doesn't stare at "Receiver disconnect" waiting for the timer.
    LaunchedEffect(btManager) {
        val mgr = btManager ?: return@LaunchedEffect
        val state = BluetoothManagerRepository.bluetoothConnectionState.value
        when (state) {
            BluetoothConnectionState.Disconnected,
            BluetoothConnectionState.Connected ->
                mgr.forceReconnect()
            else -> { }
        }
    }

    // -----------------------------------------------------------------------
    // Bluetooth enable dialog launcher
    // -----------------------------------------------------------------------

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        btManager?.enableBluetooth()
    }

    // -----------------------------------------------------------------------
    // Device picker state
    //
    // Shown whenever bluetoothConnectionState == DevicesFound.
    // Dismissed by selecting a device (→ Paired) or cancelling (→ Enabled,
    // which will trigger a re-scan next time the user initiates).
    // -----------------------------------------------------------------------

    val bluetoothConnectionState by BluetoothManagerRepository.bluetoothConnectionState.collectAsState()
    val scannedDevices by BluetoothManagerRepository.scannedDevices.collectAsState()
    var showDevicePicker by remember { mutableStateOf(false) }

    // Mirror DevicesFound state into the picker visibility flag.
    // Only ever set to true here — onDeviceSelected and onDismiss are the sole
    // places that set it to false.  The list is populated exclusively by the
    // BLE scan, so only devices that are actively advertising appear.
    LaunchedEffect(bluetoothConnectionState) {
        if (bluetoothConnectionState == BluetoothConnectionState.DevicesFound) {
            showDevicePicker = true
        }
    }

    if (showDevicePicker && scannedDevices.isNotEmpty()) {
        DevicePickerDialog(
            devices = scannedDevices,
            onDeviceSelected = { device ->
                showDevicePicker = false
                btManager?.selectDevice(device)
            },
            onDismiss = {
                showDevicePicker = false
                // Return to Enabled so a fresh scan can be started from the UI.
                BluetoothManagerRepository.updateBluetoothConnectionState(
                    BluetoothConnectionState.Enabled
                )
            }
        )
    }

    // -----------------------------------------------------------------------
    // BT state observer — UI-only reactions
    // The connection state machine is driven by BluetoothService (Doze-exempt).
    // The UI only needs to handle states that require user-facing interactions.
    // -----------------------------------------------------------------------

    LaunchedEffect(bluetoothConnectionState) {
        Log.d("RocketApp", "BT state → $bluetoothConnectionState")
        if (bluetoothConnectionState == BluetoothConnectionState.Enabling) {
            enableBluetoothLauncher.launch(
                btManager?.buildEnableBluetoothIntent()
                    ?: Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            )
        }
        announceBtState(bluetoothConnectionState, textToSpeech)
    }

    // -----------------------------------------------------------------------
    // Navigation scaffold
    // -----------------------------------------------------------------------

    val flightProfileDataDisplayState by viewModel.flightProfileDataDisplayState.collectAsState()

    Scaffold(
        topBar = {
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                RocketAppBar(
                    currentScreen = currentScreen,
                    canNavigateBack = navController.previousBackStackEntry != null,
                    navigateUp = {
                        if (currentScreen == NavDestination.FlightProfiles && flightProfileDataDisplayState) {
                            viewModel.clearFlightProfileData()
                            viewModel.updateFlightProfileDataMessageState(LocatorMessageState.Idle)
                            viewModel.updateFlightProfileDataDisplayState(false)
                        } else {
                            navController.navigateUp()
                        }
                    },
                    modifier = modifier
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavDestination.Start.name,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(route = NavDestination.Start.name) {
                viewModel.updateFlightProfileMetadataMessageState(LocatorMessageState.Idle)
                HomeScreen(
                    navController, viewModel, permissionsState, textToSpeech,
                    onRescan = { btManager?.startScan() },
                    modifier
                )
            }
            composable(route = NavDestination.AppSettings.name) {
                AppSettingsScreen(viewModel, textToSpeech,
                    onCancelButtonClicked = { navigateToStart(navController) }, modifier)
            }
            composable(route = NavDestination.LocatorSettings.name) {
                LocatorSettingsScreen(viewModel, bluetoothService,
                    onCancelButtonClicked = { navigateToStart(navController) }, modifier)
            }
            composable(route = NavDestination.ReceiverSettings.name) {
                ReceiverSettingsScreen(viewModel, bluetoothService,
                    onCancelButtonClicked = { navigateToStart(navController) }, modifier)
            }
            composable(route = NavDestination.FlightProfiles.name) {
                FlightProfilesScreen(viewModel, bluetoothService,
                    onCancelButtonClicked = { navigateToStart(navController) }, modifier)
            }
            composable(route = NavDestination.DeploymentTest.name) {
                DeploymentTestScreen(viewModel, bluetoothService,
                    onCancelButtonClicked = { navigateToStart(navController) }, modifier)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Service binding helper
// ---------------------------------------------------------------------------

@Composable
private fun BindBluetoothService(
    context: Context,
    viewModel: RocketViewModel,
    onServiceChanged: (BluetoothService?) -> Unit
) {
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as BluetoothService.LocalBinder).getService()
                onServiceChanged(svc)
                viewModel.collectInboundMessageData(svc)
            }
            override fun onServiceDisconnected(name: ComponentName?) = onServiceChanged(null)
        }
    }
    DisposableEffect(Unit) {
        val intent = Intent(context, BluetoothService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose { context.unbindService(connection) }
    }
}

// ---------------------------------------------------------------------------
// TTS announcements
// ---------------------------------------------------------------------------

private fun announceBtState(state: BluetoothConnectionState, tts: TextToSpeech?) {
    // when (state) {
    //     BluetoothConnectionState.Connected ->
    //         tts?.speak("Connected to receiver", TextToSpeech.QUEUE_FLUSH, null, null)
    //     BluetoothConnectionState.PairingFailed ->
    //         tts?.speak("Bluetooth connection failed", TextToSpeech.QUEUE_FLUSH, null, null)
    //     else -> {}
    // }
}

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

private fun navigateToStart(navController: NavHostController) {
    navController.popBackStack(NavDestination.Start.name, inclusive = false)
}

// ---------------------------------------------------------------------------
// SharedPreferences helpers
// ---------------------------------------------------------------------------

fun saveData(context: Context, key: String, value: String) {
    context.getSharedPreferences("steam_pigeon_prefs", Context.MODE_PRIVATE)
        .edit().putString(key, value).apply()
}

fun loadData(context: Context, key: String, defaultValue: String): String =
    context.getSharedPreferences("steam_pigeon_prefs", Context.MODE_PRIVATE)
        .getString(key, defaultValue) ?: defaultValue