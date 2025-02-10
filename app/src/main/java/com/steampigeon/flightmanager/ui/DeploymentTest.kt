package com.steampigeon.flightmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    var deploymentTestOption by remember {mutableStateOf(DeploymentTestOption.None)}

    Column (
        modifier = modifier.fillMaxHeight().padding(16.dp)
    ) {
        Column(
            modifier = modifier.padding(start = 40.dp),
            verticalArrangement = Arrangement.SpaceAround
        ) {
            // Capture initial and updated receiver configuration data.
            // Used for configuration screen and confirming receiver update acknowledgement.
            EnumDropdown(
                DeploymentTestOption::class,
                deploymentTestOption,
                enabled = true,
                modifier = modifier
            )
            { newConfigValue ->
                deploymentTestOption = newConfigValue as DeploymentTestOption
            }
            Button(
                onClick = {
                    if (deploymentTestCountdown == 0)
                        service?.deploymentTest(deploymentTestOption.ordinal)
                    else {
                        service?.deploymentTest(0)
                        viewModel.updateDeploymentTestCountdown(0)
                    }
                },
                modifier = modifier,
                enabled = deploymentTestOption != DeploymentTestOption.None
            ) {
                Text(
                    when {
                        deploymentTestOption == DeploymentTestOption.None ->
                            "Select Deployment Channel"
                        deploymentTestCountdown == 0 ->
                            "Deployment Channel ${deploymentTestOption.ordinal} Test"
                        else ->
                            deploymentTestCountdown.toString()
                    }
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