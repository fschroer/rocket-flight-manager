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
import com.steampigeon.flightmanager.data.LocatorMessageState

/**
 * Composable that displays map download options,
 * [onCancelButtonClicked] lambda that cancels receiver settings when user clicks cancel
 */
@Composable
fun ReceiverSettingsScreen(
    viewModel: RocketViewModel = viewModel(),
    service: BluetoothService?,
    onCancelButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var stagedReceiverConfig by remember {mutableStateOf(viewModel.remoteReceiverConfig.value)}
    var receiverConfigChanged = viewModel.receiverConfigChanged.collectAsState().value
    val receiverConfigMessageState = viewModel.receiverConfigMessageState.collectAsState().value

    Column (
        modifier = modifier.fillMaxHeight().padding(16.dp)
    ) {
        Column(
            modifier = modifier.padding(start = 40.dp),
            verticalArrangement = Arrangement.SpaceAround
        ) {
            // Capture initial and updated receiver configuration data.
            // Used for configuration screen and confirming receiver update acknowledgement.
            ConfigurationItemNumeric(
                configItemName = stringResource(R.string.lora_channel),
                initialConfigValue = stagedReceiverConfig.channel,
                minValue = 0,
                maxValue = 63,
                configMessageState = receiverConfigMessageState,
                modifier = modifier
            ) { newConfigValue ->
                stagedReceiverConfig = stagedReceiverConfig.copy(channel = newConfigValue)
                viewModel.updateReceiverConfigChanged(true)
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
            Button(
                modifier = Modifier.weight(1f),
                // the button is enabled when the user makes a selection
                enabled = (receiverConfigChanged && receiverConfigMessageState == LocatorMessageState.Idle),
                onClick = {
                    if (receiverConfigMessageState == LocatorMessageState.Idle) {
                        viewModel.updateReceiverConfigMessageState(LocatorMessageState.SendRequested)
                        if (service?.changeReceiverConfig(stagedReceiverConfig) == true)
                            viewModel.updateReceiverConfigMessageState(LocatorMessageState.Sent)
                        else
                            viewModel.updateReceiverConfigMessageState(LocatorMessageState.SendFailure)
                        viewModel.updateReceiverConfigState(stagedReceiverConfig)
                    }
                }
            ) {
                Text(
                    when (receiverConfigMessageState) {
                        LocatorMessageState.Idle -> stringResource(R.string.update)
                        LocatorMessageState.SendRequested,
                        LocatorMessageState.Sent -> stringResource(R.string.updating)
                        LocatorMessageState.AckUpdated -> stringResource(R.string.updated)
                        LocatorMessageState.SendFailure -> stringResource(R.string.update_failed)
                        LocatorMessageState.NotAcknowledged -> stringResource(R.string.update_not_acknowledged)
                    }
                )
            }
        }
    }
}