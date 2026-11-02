package com.steampigeon.flightmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.SimFlightProfile
import kotlinx.coroutines.delay
import kotlin.random.Random

/** How long Start waits for the simulated bit before saying the locator refused. */
private const val SIM_START_CONFIRM_MS = 5_000L

/**
 * Start or cancel a simulated flight that the locator computes (locator ADR "An
 * app-started simulated flight computed on the locator", 0041).
 *
 * Reached from the drawer under the same gate as Flight Profiles and Locator
 * Settings (heard and disarmed), because the locator starts one only while
 * disarmed.  The ADR's ranges are enforced here as they are on the locator: an
 * out-of-range field is shown and refused, and holds Start.
 *
 * Start asks first, because a rocket on the rail being prepped passes the
 * locator's own checks as well as one on the bench.  It then waits for the
 * simulated bit in telemetry, which is the only evidence the locator accepted
 * (it refuses silently), and returns to the map once it arrives, where the
 * flight plays under the SIMULATED banner.  No bit within a few seconds means
 * the locator did not start, and the screen says so.
 *
 * Cancel is always shown, and enabled while a simulation is running.
 */
@Composable
fun SimulatedFlightScreen(
    viewModel: RocketViewModel = viewModel(),
    service: BluetoothService?,
    onStarted: () -> Unit = {},
    onCancelButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val rocketState by viewModel.rocketState.collectAsState()
    val simulated = rocketState.simulated
    var profile by remember { mutableStateOf(SimFlightProfile()) }
    val refused = remember { mutableStateMapOf<String, Boolean>() }
    var confirming by remember { mutableStateOf(false) }
    var waitingForStart by remember { mutableStateOf(false) }
    var notStarted by remember { mutableStateOf(false) }
    var cancelSent by remember { mutableStateOf(false) }
    val currentOnStarted by rememberUpdatedState(onStarted)

    // Waiting for the locator to accept: the bit arriving is the success path,
    // the timeout the refusal.
    LaunchedEffect(waitingForStart, simulated) {
        if (!waitingForStart) return@LaunchedEffect
        if (simulated) {
            waitingForStart = false
            currentOnStarted()
            return@LaunchedEffect
        }
        delay(SIM_START_CONFIRM_MS)
        waitingForStart = false
        notStarted = true
    }
    LaunchedEffect(simulated) { if (!simulated) cancelSent = false }

    val fieldsValid = profile.valid && refused.values.none { it }

    Column(
        modifier = modifier.fillMaxHeight().padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.sim_flight_explanation), style = MaterialTheme.typography.bodyMedium)
            SimField(R.string.sim_flight_apogee, profile.apogeeM, SimFlightProfile.APOGEE_M, !simulated, refused) {
                profile = profile.copy(apogeeM = it)
            }
            SimField(R.string.sim_flight_burn, profile.burnTenths, SimFlightProfile.BURN_TENTHS, !simulated, refused) {
                profile = profile.copy(burnTenths = it)
            }
            SimField(R.string.sim_flight_drogue, profile.drogueMps, SimFlightProfile.DROGUE_MPS, !simulated, refused) {
                profile = profile.copy(drogueMps = it)
            }
            SimField(R.string.sim_flight_main, profile.mainMps, SimFlightProfile.MAIN_MPS, !simulated, refused) {
                profile = profile.copy(mainMps = it)
            }
            SimField(R.string.sim_flight_wind, profile.windMps, SimFlightProfile.WIND_MPS, !simulated, refused) {
                profile = profile.copy(windMps = it)
            }
            SimField(R.string.sim_flight_wind_from, profile.windFromDeg, SimFlightProfile.WIND_FROM_DEG, !simulated, refused) {
                profile = profile.copy(windFromDeg = it)
            }

            Button(
                onClick = { notStarted = false; confirming = true },
                enabled = fieldsValid && !simulated && !waitingForStart,
            ) {
                Text(
                    if (waitingForStart) stringResource(R.string.sim_flight_starting)
                    else stringResource(R.string.sim_flight_start)
                )
            }
            if (notStarted) {
                Text(
                    stringResource(R.string.sim_flight_not_started),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Error-colored like the deployment-test stop and the map's disarm:
            // the control that ends something.  Re-sends on every press; it clears
            // when the simulated bit does, the only evidence it was heard.
            Button(
                onClick = {
                    service?.cancelSimulatedFlight()
                    cancelSent = true
                },
                modifier = Modifier.height(48.dp),
                enabled = simulated,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            ) {
                Text(
                    if (cancelSent) stringResource(R.string.sim_flight_canceling)
                    else stringResource(R.string.sim_flight_cancel)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_medium)),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onCancelButtonClicked
            ) {
                Text(stringResource(R.string.return_to_main))
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.sim_flight_confirm_title)) },
            text = { Text(stringResource(R.string.sim_flight_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val here = viewModel.trackerLocation.value
                    val sent = service?.startSimulatedFlight(
                        profile, Random.nextInt(0x10000), here?.latitude, here?.longitude) == true
                    if (sent) waitingForStart = true else notStarted = true
                }) { Text(stringResource(R.string.sim_flight_start)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SimField(
    labelRes: Int,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    refused: MutableMap<String, Boolean>,
    onUpdate: (Int) -> Unit,
) {
    val label = stringResource(labelRes)
    ConfigurationItemNumeric(
        configItemName = label,
        initialConfigValue = value,
        minValue = range.first,
        maxValue = range.last,
        configMessageState = if (enabled) LocatorMessageState.Idle else LocatorMessageState.SendRequested,
        onRefusedChange = { refused[label] = it },
        onConfigUpdate = onUpdate,
    )
}
