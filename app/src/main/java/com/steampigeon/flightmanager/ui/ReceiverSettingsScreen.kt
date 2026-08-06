package com.steampigeon.flightmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.ChannelSurvey
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
    // The password challenge dialog itself is hosted app-wide (see RocketApp); this
    // screen only arms the channel-change flow and shows the conflicting-locator banner.
    val conflictLocatorId by viewModel.conflictLocatorId.collectAsState()
    val locatorConnected by viewModel.locatorConnected.collectAsState()
    val channelSurvey by viewModel.channelSurvey.collectAsState()
    val surveyInProgress by viewModel.surveyInProgress.collectAsState()
    val pendingChannelMove by viewModel.pendingChannelMove.collectAsState()
    val locatorConfigMessageState by viewModel.locatorConfigMessageState.collectAsState()

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
        // Re-entering the screen is the user asking to see conflicts again, so a
        // dismissal from a previous visit does not persist.
        viewModel.resetConflictDismissals()
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
        // Conflicting-traffic warning: another locator is audible and is not the one
        // being displayed — either unauthorized, or authorized but not connected (the
        // connection is single-holder and does not change on its own).  Non-blocking;
        // Connect switches to it, or the user can move to an uncontested channel.
        conflictLocatorId?.let { id ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // When already connected to a different locator, this is genuine
                    // conflicting traffic → offer the switch or an uncontested channel.
                    // When not yet connected, it's simply a new locator to connect to
                    // → invite a password.
                    text = if (locatorConnected)
                        stringResource(R.string.locator_conflict_warning, "%08X".format(id))
                    else
                        stringResource(R.string.locator_unrecognized_prompt, "%08X".format(id)),
                    color = if (locatorConnected)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.requestConnectToConflict() }) {
                    Text(stringResource(R.string.connect))
                }
                TextButton(onClick = { viewModel.dismissConflict() }) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }

        // Channel survey (ADR-0019 tier 3): sweep the band and rank channels by how
        // quiet they are. On demand only — a sweep costs ~1 s of deafness, and the
        // decision it informs is made once, on the ground.
        // Progress for a survey-driven move.  The ADR-0011 cycle waits for
        // PreLaunchData to resume on the new channel and may revert and retry once,
        // so this can run for several seconds with the link legitimately down —
        // silence there reads as a hang.
        pendingChannelMove?.let { channel ->
            val (text, color) = when (locatorConfigMessageState) {
                LocatorMessageState.SendRequested,
                LocatorMessageState.Sent ->
                    stringResource(R.string.channel_move_in_progress, channel) to
                            MaterialTheme.colorScheme.onSurfaceVariant
                LocatorMessageState.AckUpdated ->
                    stringResource(R.string.channel_move_done, channel) to
                            MaterialTheme.colorScheme.onSurfaceVariant
                LocatorMessageState.SendFailure ->
                    stringResource(R.string.channel_move_failed) to
                            MaterialTheme.colorScheme.error
                LocatorMessageState.NotAcknowledged ->
                    stringResource(R.string.channel_move_not_acknowledged, channel) to
                            MaterialTheme.colorScheme.error
                LocatorMessageState.Idle -> null to MaterialTheme.colorScheme.onSurfaceVariant
            }
            text?.let {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = it, color = color, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearPendingChannelMove() }) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
            }
        }

        ChannelSurveySection(
            survey = channelSurvey,
            inProgress = surveyInProgress,
            // Ready, not Connected: Connected is a transient step the connection
            // manager passes through on its way to Ready, so gating on it leaves the
            // button permanently disabled.  Ready is the steady usable state, and is
            // what the map screen gates on.
            enabled = bluetoothConnectionState == BluetoothConnectionState.Ready && !surveyInProgress,
            onScan = { viewModel.requestChannelSurvey(service) },
            locatorConnected = locatorConnected,
            onPick = { channel ->
                if (locatorConnected) {
                    // Move the whole system. "Find a clean channel" means the rocket
                    // goes there too — staging a receiver-only change would point the
                    // receiver at an empty channel and strand the locator behind on
                    // the old one (ADR-0011 invariant 1 vs 5).
                    viewModel.moveLocatorToChannel(service, channel)
                } else {
                    // Nothing to move: stage the receiver-only change for the Update
                    // button, which is the legitimate "go look at that channel" case.
                    stagedReceiverConfig = stagedReceiverConfig.copy(channel = channel)
                    viewModel.updateReceiverConfigChanged(true)
                }
                viewModel.clearChannelSurvey()
            },
        )

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
                        // A channel change points the receiver at a (possibly different)
                        // locator.  Arm recognition first so the next PreLaunchData on the
                        // new channel is recognised, challenged for a password, or reverted.
                        if (stagedReceiverConfig.channel != remoteReceiverConfig.channel) {
                            viewModel.beginChannelChangeRecognition(remoteReceiverConfig.channel)
                        }
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
/**
 * "Find a clean channel" — the tier-3 channel survey (ADR-0019, #33).
 *
 * Three presentation rules come straight from the ADR, and each exists because
 * the obvious alternative gives wrong advice:
 *
 * - **Rank, don't report absolute dBm.** RSSI near the noise floor is uncalibrated
 *   and varies unit to unit, so a level only means something next to the other
 *   levels in the same sweep. Levels are shown as a relative bar, not a number.
 * - **Say nothing when every channel is loud.** That is a transmitter next to the
 *   receiver, not a busy band, and recommending whichever channel read lowest
 *   would be confidently wrong.
 * - **Never imply it predicts the flight.** The sweep measures the receiver's
 *   location; the rocket at altitude hears a different and busier world.
 */
@Composable
private fun ChannelSurveySection(
    survey: ChannelSurvey.Result?,
    inProgress: Boolean,
    enabled: Boolean,
    locatorConnected: Boolean,
    onScan: () -> Unit,
    onPick: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onScan, enabled = enabled) {
                Text(
                    stringResource(
                        if (inProgress) R.string.survey_scanning else R.string.survey_scan
                    )
                )
            }
        }

        when {
            survey == null -> Unit

            survey.status == ChannelSurvey.Status.RefusedArmed -> SurveyNote(
                stringResource(R.string.survey_refused_armed),
                MaterialTheme.colorScheme.error,
            )

            survey.status == ChannelSurvey.Status.RefusedBusy -> SurveyNote(
                stringResource(R.string.survey_refused_busy),
                MaterialTheme.colorScheme.error,
            )

            survey.status != ChannelSurvey.Status.Ok -> SurveyNote(
                stringResource(R.string.survey_failed),
                MaterialTheme.colorScheme.error,
            )

            else -> {
                // Shown ABOVE the list, not instead of it. Everything reading loud
                // means a transmitter is very close, which is the important message
                // — but the ranking below it is still correct, and refusing to show
                // it leaves a correct warning with no way to act on it.
                // Two different situations, and only one is a problem to solve.
                // Flat and elevated is a nearby transmitter — usually the user's own
                // locator on the bench — bleeding equally across the band. It says
                // nothing about the channels, so it is information, not an error.
                // Elevated with structure means real per-channel traffic, and the
                // ranking below is meaningful.
                if (survey.uniformFloor) {
                    SurveyNote(
                        stringResource(R.string.survey_uniform_floor),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (survey.allChannelsHot) {
                    SurveyNote(
                        stringResource(R.string.survey_all_hot),
                        MaterialTheme.colorScheme.error,
                    )
                }
                survey.homeRank?.let { rank ->
                    SurveyNote(
                        stringResource(R.string.survey_home_rank, survey.homeChannel, rank),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Relative bar: quietest channel in this sweep is the reference, the
                // loudest is full scale. Deliberately unlabelled in dBm.
                val quietest = survey.ranked.first().level
                val loudest = survey.ranked.last().level
                val span = (loudest - quietest).coerceAtLeast(1)
                survey.suggestions.forEach { s ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.survey_channel_label, s.channel),
                            modifier = Modifier.width(96.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(
                            progress = { ((s.level - quietest).toFloat() / span).coerceIn(0f, 1f) },
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        TextButton(onClick = { onPick(s.channel) }) {
                            // Different actions, so different labels: with a locator
                            // connected this moves the whole system, without one it
                            // only re-points the receiver.
                            Text(
                                stringResource(
                                    if (locatorConnected) R.string.survey_move_here
                                    else R.string.survey_point_receiver
                                )
                            )
                        }
                    }
                }
                SurveyNote(
                    stringResource(
                        if (locatorConnected) R.string.survey_moves_both
                        else R.string.survey_receiver_only
                    ),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Naming the channels that were excluded, and why, so a short list
                // does not read as a failed scan.
                survey.occupied.forEach { o ->
                    SurveyNote(
                        stringResource(R.string.survey_channel_occupied, o.channel),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SurveyNote(
                    stringResource(R.string.survey_confirmed_note),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SurveyNote(
                    stringResource(R.string.survey_caveat),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SurveyNote(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}
