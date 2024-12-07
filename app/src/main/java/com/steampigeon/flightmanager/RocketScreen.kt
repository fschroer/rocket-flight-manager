package com.steampigeon.flightmanager

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.steampigeon.flightmanager.ui.RocketViewModel
import com.steampigeon.flightmanager.ui.DownloadMapScreen
import com.steampigeon.flightmanager.ui.ExportFlightPathScreen
import com.steampigeon.flightmanager.ui.HomeScreen
import com.steampigeon.flightmanager.ui.LocatorSettingsScreen
import com.steampigeon.flightmanager.ui.ReceiverSettingsScreen
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect

enum class RocketScreen(@StringRes val title: Int) {
    Start(title = R.string.app_name),
    Download(title = R.string.download_map),
    Export(title = R.string.export),
    LocatorSettings(title = R.string.locator_settings),
    ReceiverSettings(title = R.string.receiver_settings),
}

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
    viewModel: RocketViewModel = viewModel(),
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current
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
                    viewModel,
                    modifier = Modifier
                        //.fillMaxSize()
                        .padding(dimensionResource(R.dimen.padding_medium))
                )
            }
            composable(route = RocketScreen.Download.name) {
                DownloadMapScreen(
                    onNextButtonClicked = { /* To do */ },
                    onCancelButtonClicked = {
                        navigateToStart(viewModel, navController)
                    },
                    //onSelectionChanged = { viewModel.setFlavor(it) },
                    modifier = Modifier.fillMaxHeight()
                )
            }
            composable(route = RocketScreen.Export.name) {
                ExportFlightPathScreen(
                    onNextButtonClicked = { /* To do */ },
                    onCancelButtonClicked = {
                        navigateToStart(viewModel, navController)
                    },
                    //onSelectionChanged = { viewModel.setDate(it) },
                    modifier = Modifier.fillMaxHeight()
                )
            }
            composable(route = RocketScreen.LocatorSettings.name) {
                LocatorSettingsScreen(
                    onNextButtonClicked = { /* To do */ },
                    onCancelButtonClicked = {
                        navigateToStart(viewModel, navController)
                    },
                    //onSelectionChanged = { viewModel.setDate(it) },
                    modifier = Modifier.fillMaxHeight()
                )
            }
            composable(route = RocketScreen.ReceiverSettings.name) {
                ReceiverSettingsScreen(
                    onNextButtonClicked = { /* To do */ },
                    onCancelButtonClicked = {
                        navigateToStart(viewModel, navController)
                    },
                    //onSelectionChanged = { viewModel.setDate(it) },
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
    }
}

private fun navigateToStart(
    viewModel: RocketViewModel,
    navController: NavHostController
) {
    navController.popBackStack(RocketScreen.Start.name, inclusive = false)
}