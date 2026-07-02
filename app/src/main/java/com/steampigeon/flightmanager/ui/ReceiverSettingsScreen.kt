package com.steampigeon.flightmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
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
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.Protocol

@Composable
fun ReceiverSettingsScreen(
    viewModel: RocketViewModel = viewModel(),
    service: BluetoothService?,
    onCancelButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var stagedReceiverConfig by remember { mutableStateOf(viewModel.remoteReceiverConfig.value) }
    val receiverConfigChanged = viewModel.receiverConfigChanged.collectAsState().value
    val receiverConfigMessageState = viewModel.receiverConfigMessageState.collectAsState().value
    val remoteReceiverConfig = viewModel.remoteReceiverConfig.collectAsState().value
    val rocketState by viewModel.rocketState.collectAsState()
    val receiverVersion by viewModel.receiverVersion.collectAsState()
    val bluetoothConnectionState by BluetoothManagerRepository.bluetoothConnectionState.collectAsState()

    // Keep the staged copy in sync with the remote config as long as the user
    // has not made any local edits.  This ensures that arriving PreLaunchData
    // (channel) or a receiver-device switch (full reset) are reflected
    // immediately rather than showing stale values from a previous session.
    LaunchedEffect(remoteReceiverConfig) {
        if (!receiverConfigChanged) {
            stagedReceiverConfig = remoteReceiverConfig
        }
    }

    // On entry: if no locator PreLaunchData has been received in the last 5 seconds,
    // request the receiver to send its current channel and name directly.
    // The ReceiverInfo response updates remoteReceiverConfig, which the
    // LaunchedEffect above propagates to stagedReceiverConfig automatically.
    LaunchedEffect(Unit) {
        val lastPreLaunchMessageAge =
            System.currentTimeMillis() - rocketState.lastPreLaunchMessageTime
        if (lastPreLaunchMessageAge > 5_000L) {
            service?.requestReceiverInfo()
        }
    }

    // After a config update is sent, request ReceiverInfo to solicit confirmation.
    // PreLaunchData may no longer arrive (locator/receiver LoRa channel mismatch),
    // so ReceiverInfo over BLE is the only reliable acknowledgement path.
    LaunchedEffect(receiverConfigMessageState) {
        if (receiverConfigMessageState == LocatorMessageState.Sent) {
            delay(300L)
            service?.requestReceiverInfo()
        }
    }

    // If the BLE module was reset as part of a name change, the connection drops and
    // then reconnects.  Re-request ReceiverInfo once the link is back so the poll
    // loop can confirm the new channel even when no locator is transmitting.
    LaunchedEffect(bluetoothConnectionState) {
        if (bluetoothConnectionState == BluetoothConnectionState.Connected &&
            receiverConfigMessageState == LocatorMessageState.Sent) {
            delay(500L) // let the BLE module re-enter data mode after reset
            service?.requestReceiverInfo()
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(16.dp)
    ) {
        Column(
            modifier = modifier.padding(start = 40.dp),
            verticalArrangement = Arrangement.SpaceAround
        ) {
            // Firmware version (read-only, populated once VersionInfo is received)
            if (receiverVersion.isNotEmpty()) {
                Text(
                    text = "Firmware: $receiverVersion",
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            ConfigurationItemText(
                configItemName = stringResource(R.string.receiver_name),
                configItemValue = stagedReceiverConfig.deviceName,
                configMessageState = receiverConfigMessageState,
                modifier = modifier
            ) { newConfigValue ->
                stagedReceiverConfig = stagedReceiverConfig.copy(
                    deviceName = newConfigValue.take(Protocol.DEVICE_NAME_LENGTH)
                )
                viewModel.updateReceiverConfigChanged(true)
            }
            // Receiver-only channel change: used to point the receiver at a *different*
            // locator that is already on another channel.  (Changing a locator's own
            // channel is done from Locator Settings, where the receiver auto-follows.)
            ConfigurationItemNumeric(
                configItemName = stringResource(R.string.locator_channel),
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

        Spacer(modifier = modifier.weight(1f))

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // -----------------------------------------------------------------------
        // Standard Cancel / Update row
        // -----------------------------------------------------------------------
        Row(
            modifier = modifier,
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
                        LocatorMessageState.Idle             -> stringResource(R.string.update)
                        LocatorMessageState.SendRequested,
                        LocatorMessageState.Sent             -> stringResource(R.string.updating)
                        LocatorMessageState.AckUpdated       -> stringResource(R.string.updated)
                        LocatorMessageState.SendFailure      -> stringResource(R.string.update_failed)
                        LocatorMessageState.NotAcknowledged  -> stringResource(R.string.update_not_acknowledged)
                    }
                )
            }
        }
    }
}