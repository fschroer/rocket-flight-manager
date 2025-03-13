package com.steampigeon.flightmanager

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
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.ExperimentalMaterialApi
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
import com.steampigeon.flightmanager.ui.RocketViewModel
import com.steampigeon.flightmanager.ui.FlightProfilesScreen
import com.steampigeon.flightmanager.ui.HomeScreen
import com.steampigeon.flightmanager.ui.LocatorSettingsScreen
import com.steampigeon.flightmanager.ui.ReceiverSettingsScreen
import java.util.Locale
import java.util.regex.Pattern

enum class RocketScreen(@StringRes val title: Int) {
    Start(title = R.string.app_name),
    AppSettings(title = R.string.application_settings),
    LocatorSettings(title = R.string.locator_settings),
    ReceiverSettings(title = R.string.receiver_settings),
    DeploymentTest(title = R.string.deployment_test),
    FlightProfiles(title = R.string.flight_profiles),
    Export(title = R.string.export),
}

var service: BluetoothService? = null
private lateinit var launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>

/**
 * Composable that displays the topBar and displays back button if back navigation is possible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RocketAppBar(
    currentScreen: RocketScreen,
    canNavigateBack: Boolean,
    navigateUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    modifier = Modifier
                        .size(dimensionResource(id = R.dimen.image_size))
                        .padding(dimensionResource(id = R.dimen.padding_small)),
                    painter = painterResource(R.drawable.rocket),
                    contentDescription = null
                )
                Text(stringResource(id = currentScreen.title))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
            //containerColor = Color.Transparent,
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

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterialApi::class)
@Composable
fun RocketApp(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: RocketViewModel = viewModel()
    StartLocatorDataCollection(context, viewModel)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentScreen = RocketScreen.valueOf(
        backStackEntry?.destination?.route ?: RocketScreen.Start.name
    )
    val orientation = LocalConfiguration.current.orientation
    var textToSpeech: TextToSpeech? by remember { mutableStateOf(null) }
    val voiceName = viewModel.voiceName.collectAsState().value
    val voiceEnabled = viewModel.voiceEnabled.collectAsState().value
    LaunchedEffect(voiceEnabled, voiceName) {
        if (voiceEnabled) {
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.language = Locale.US
                    val voices = textToSpeech?.voices
                    val desiredVoice = voices?.find { voice ->
                        voice.name.contains(voiceName)
                    }
                    if (desiredVoice != null) {
                        textToSpeech?.voice = desiredVoice
                    } else {
                        // Handle case where voice is not found
                    }
                }
            }
        }
        else
            textToSpeech = null
    }
    val permissionsState = rememberMultiplePermissionsState(
        when (Build.VERSION.SDK_INT) {
            in 1..27 -> {
                listOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.BLUETOOTH,
                    android.Manifest.permission.CAMERA,
                )
            }
            in 28..30 -> {
                listOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.BLUETOOTH,
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.FOREGROUND_SERVICE,
                )
            }
            in 31..33 -> {
                listOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.FOREGROUND_SERVICE,
                )
            }
            else -> {
                listOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.FOREGROUND_SERVICE,
                    android.Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE,
                )
            }
        }
    )
    val allPermissionsGranted = permissionsState.allPermissionsGranted
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
    Scaffold(
        topBar = {
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                RocketAppBar(
                    currentScreen = currentScreen,
                    canNavigateBack = navController.previousBackStackEntry != null,
                    navigateUp = { navController.navigateUp() },
                    modifier = modifier
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = RocketScreen.Start.name,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(route = RocketScreen.Start.name) {
                HomeScreen(
                    navController,
                    viewModel,
                    permissionsState,
                    textToSpeech,
                    modifier = modifier
                    //.fillMaxSize()
                    //.padding(dimensionResource(R.dimen.padding_medium))
                )
            }
            composable(route = RocketScreen.AppSettings.name) {
                AppSettingsScreen(
                    viewModel,
                    textToSpeech,
                    onCancelButtonClicked = { navigateToStart(navController) },
                    modifier = modifier
                )
            }
            composable(route = RocketScreen.LocatorSettings.name) {
                LocatorSettingsScreen(
                    viewModel,
                    service,
                    onCancelButtonClicked = { navigateToStart(navController) },
                    modifier = modifier
                )
            }
            composable(route = RocketScreen.ReceiverSettings.name) {
                ReceiverSettingsScreen(
                    viewModel,
                    service,
                    onCancelButtonClicked = { navigateToStart(navController) },
                    modifier = modifier
                )
            }
            composable(route = RocketScreen.FlightProfiles.name) {
                //viewModel.updateRequestFlightProfileMetadata(true)
                //viewModel.updateFlightProfileMetadataMessageState(LocatorMessageState.Idle)
                FlightProfilesScreen(
                    viewModel,
                    service,
                    onCancelButtonClicked = {
                        navigateToStart(navController)
                    },
                    modifier = modifier
                )
            }
            composable(route = RocketScreen.DeploymentTest.name) {
                DeploymentTestScreen(
                    viewModel,
                    service,
                    onCancelButtonClicked = { navigateToStart(navController) },
                    modifier = modifier
                )
            }
        }
    }
}

@Composable
fun StartLocatorDataCollection(context: Context, viewModel: RocketViewModel) {
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as BluetoothService.LocalBinder).getService()
                viewModel.collectInboundMessageData(service!!)
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

private fun navigateToStart(
    navController: NavHostController
) {
    navController.popBackStack(RocketScreen.Start.name, inclusive = false)
}

fun saveData(context: Context, key: String, value: String) {
    val sharedPref = context.getSharedPreferences("steam_pigeon_prefs", Context.MODE_PRIVATE)
    with(sharedPref.edit()) {
        putString(key, value)
        apply()
    }
}

fun loadData(context: Context, key: String, defaultValue: String): String {
    val sharedPref = context.getSharedPreferences("steam_pigeon_prefs", Context.MODE_PRIVATE)
    return sharedPref.getString(key, defaultValue) ?: defaultValue
}