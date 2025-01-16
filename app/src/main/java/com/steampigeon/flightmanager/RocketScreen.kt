package com.steampigeon.flightmanager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
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
import com.steampigeon.flightmanager.ui.AppSettingsScreen
import com.steampigeon.flightmanager.ui.DeploymentTestScreen
import com.steampigeon.flightmanager.ui.RocketViewModel
import com.steampigeon.flightmanager.ui.ExportFlightPathScreen
import com.steampigeon.flightmanager.ui.HomeScreen
import com.steampigeon.flightmanager.ui.LocatorSettingsScreen
import com.steampigeon.flightmanager.ui.ReceiverSettingsScreen
import java.util.Locale

enum class RocketScreen(@StringRes val title: Int) {
    Start(title = R.string.app_name),
    AppSettings(title = R.string.application_settings),
    LocatorSettings(title = R.string.locator_settings),
    ReceiverSettings(title = R.string.receiver_settings),
    DeploymentTest(title = R.string.deployment_test),
    Export(title = R.string.export),
}

var service: BluetoothService? = null

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
    LaunchedEffect(voiceName) {
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

private fun navigateToStart(
    navController: NavHostController
) {
    navController.popBackStack(RocketScreen.Start.name, inclusive = false)
}