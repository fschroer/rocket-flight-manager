package com.steampigeon.flightmanager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.steampigeon.flightmanager.ui.RocketViewModel
import com.steampigeon.flightmanager.ui.ExportFlightPathScreen
import com.steampigeon.flightmanager.ui.HomeScreen
import com.steampigeon.flightmanager.ui.LocatorSettingsScreen
import com.steampigeon.flightmanager.ui.ReceiverSettingsScreen

enum class RocketScreen(@StringRes val title: Int) {
    Start(title = R.string.app_name),
    LocatorSettings(title = R.string.locator_settings),
    ReceiverSettings(title = R.string.receiver_settings),
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
        title = { Text(stringResource(id = currentScreen.title)) },
        colors = TopAppBarDefaults.mediumTopAppBarColors(
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

@Composable
fun RocketApp(
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentScreen = RocketScreen.valueOf(
        backStackEntry?.destination?.route ?: RocketScreen.Start.name
    )
    Scaffold(
        topBar = {
            RocketAppBar(
                currentScreen = currentScreen,
                canNavigateBack = navController.previousBackStackEntry != null,
                navigateUp = { navController.navigateUp() }
            )
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
                    modifier = Modifier
                        //.fillMaxSize()
                        //.padding(dimensionResource(R.dimen.padding_medium))
                )
            }
            composable(route = RocketScreen.LocatorSettings.name) {
                val mainBackStackEntry = remember { navController.getBackStackEntry(RocketScreen.Start.name) }
                val mainViewModel: RocketViewModel = viewModel(mainBackStackEntry)
                LocatorSettingsScreen(
                    mainViewModel,
                    service,
                    onNextButtonClicked = { /* To do */ },
                    onCancelButtonClicked = {
                        navigateToStart(navController)
                    },
                    //onSelectionChanged = { viewModel.setDate(it) },
                    modifier = Modifier.fillMaxHeight()
                )
            }
            composable(route = RocketScreen.ReceiverSettings.name) {
                ReceiverSettingsScreen(
                    onNextButtonClicked = { /* To do */ },
                    onCancelButtonClicked = {
                        navigateToStart(navController)
                    },
                    //onSelectionChanged = { viewModel.setDate(it) },
                    modifier = Modifier.fillMaxHeight()
                )
            }
            composable(route = RocketScreen.Export.name) {
                ExportFlightPathScreen(
                    onNextButtonClicked = { /* To do */ },
                    onCancelButtonClicked = {
                        navigateToStart(navController)
                    },
                    //onSelectionChanged = { viewModel.setDate(it) },
                    modifier = Modifier.fillMaxHeight()
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
}

private fun navigateToStart(
    navController: NavHostController
) {
    navController.popBackStack(RocketScreen.Start.name, inclusive = false)
}