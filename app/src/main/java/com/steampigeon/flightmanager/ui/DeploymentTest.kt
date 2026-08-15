package com.steampigeon.flightmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.steampigeon.flightmanager.data.DeploymentTestOption

/**
 * Composable that displays map download options,
 * [onCancelButtonClicked] lambda that cancels the order when user clicks cancel and
 */
@Composable
fun DeploymentTestScreen(
    viewModel: RocketViewModel = viewModel(),
    service: BluetoothService?,
    onCancelButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val deploymentTestCountdown = viewModel.deploymentTestCountdown.collectAsState().value
    val deploymentTestCancelPending = viewModel.deploymentTestCancelPending.collectAsState().value
    val deploymentTestActive = viewModel.deploymentTestActive.collectAsState().value
    var deploymentTestOption by remember {mutableStateOf(DeploymentTestOption.None)}

    // Cancel any active deployment test when the user navigates away.
    // rememberUpdatedState ensures onDispose sees the latest service reference.
    //
    // The state is deliberately NOT cleared here.  Clearing it discarded the
    // locator's countdown, so a cancel lost on the way out left the operator
    // walking off with a live charge and an app that had forgotten about it —
    // and coming back to this screen showed a resting button rather than the
    // test still counting.  Leaving it alone means the countdown is still there
    // if the cancel did not land, and the view model's silence watchdog clears
    // everything a few seconds after the locator actually goes quiet.
    val currentService by rememberUpdatedState(service)
    DisposableEffect(Unit) {
        onDispose {
            currentService?.deploymentTest(0)
            viewModel.noteDeploymentTestCancelSent()
        }
    }

    Column (
        modifier = modifier.fillMaxHeight().padding(16.dp)
    ) {
        Column(
            modifier = modifier.padding(start = 40.dp),
            verticalArrangement = Arrangement.SpaceAround
        ) {
            // Perform remote deployment test
            EnumDropdown(
                DeploymentTestOption::class,
                deploymentTestOption,
                enabled = true,
                modifier = modifier
            )
            { newConfigValue ->
                deploymentTestOption = newConfigValue as DeploymentTestOption
            }
            // Start only.  It used to be the cancel as well, which is why the
            // manual had to warn that a press landing just after the countdown
            // lapsed would start a FRESH test — the worst possible outcome for
            // someone stabbing at the button trying to stop one.  Stopping now
            // has its own control below, so this one is simply disabled for the
            // duration and shows the count.
            Button(
                onClick = {
                    service?.deploymentTest(deploymentTestOption.ordinal)
                    viewModel.updateDeploymentTestActive(true)
                },
                modifier = modifier,
                enabled = deploymentTestOption != DeploymentTestOption.None
                        && !deploymentTestActive
            ) {
                Text(
                    when {
                        deploymentTestOption == DeploymentTestOption.None ->
                            "Select Deployment Channel"
                        deploymentTestCountdown > 0 ->
                            deploymentTestCountdown.toString()
                        deploymentTestActive ->
                            "Deployment Channel ${deploymentTestOption.ordinal} Test…"
                        else ->
                            "Deployment Channel ${deploymentTestOption.ordinal} Test"
                    }
                )
            }
            // Stop.  Present and visible from the moment this screen opens —
            // greyed out until there is something to stop — so that the way out
            // is known BEFORE the countdown starts rather than hunted for during
            // it.  Error-colored to match the disarm button on the flight map,
            // which is the other control that makes a rocket safer.
            //
            // Sends and says so, but changes nothing about the countdown: it
            // clears when the locator stops sending one, which is the only
            // evidence the cancel was heard.  Pressing repeatedly re-sends, which
            // is what an operator will do anyway and is the right answer on a
            // link that drops frames.
            Button(
                onClick = {
                    service?.deploymentTest(0)
                    viewModel.noteDeploymentTestCancelSent()
                },
                modifier = Modifier
                    .padding(top = 12.dp)
                    .height(48.dp),
                enabled = deploymentTestActive,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            ) {
                Text(
                    if (deploymentTestCancelPending)
                        stringResource(R.string.stopping_deployment_test)
                    else
                        stringResource(R.string.stop_deployment_test)
                )
            }
        }
        Spacer (modifier = modifier.weight(1f))
        Row(
            modifier = modifier,
            //.fillMaxWidth()
            //.padding(dimensionResource(R.dimen.padding_medium)),
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
}