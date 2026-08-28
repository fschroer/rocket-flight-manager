package com.steampigeon.flightmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.KnownLocator
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.ChannelOccupancy
import com.steampigeon.flightmanager.data.ChannelSurvey
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.LocatorSearch
import kotlinx.coroutines.delay

/**
 * Everything about **which channel you are listening to**, in one place.
 *
 * Split out of Receiver Settings because the old grouping was by *device* and the
 * question is not about a device. Someone who powers a rocket up and hears nothing
 * is not thinking "receiver configuration" — they are thinking "where is my
 * locator", and the tools that answer it were filed under the hardware that
 * happens to perform the search.
 *
 * The controls are one workflow, in the order you actually reach for them: find
 * the locator you have lost, find a channel worth moving to, point the receiver by
 * hand, move the locator by hand. The middle two appear only when they can do
 * anything — see the gate at each.
 *
 * **Choosing from a list acts; typing a number needs Update.** A channel picked
 * out of a scan result is a decision already made — the search just established
 * that the locator is on 48, and there is nothing left to confirm — so the button
 * applies. A number being typed has no such moment, since every keystroke is a
 * valid channel, so the field keeps an Update button. The first cut staged the
 * picks as well, which meant tapping "Point receiver" appeared to do nothing and
 * left the real action in a different section of the screen.
 *
 * **Two devices, two Update buttons, deliberately.** The receiver's channel and
 * the locator's channel are different messages with different acknowledgment paths
 * (the receiver echoes over BLE; the locator is confirmed by inference through
 * [ADR-0011](docs/adr/0011)'s recognition cycle). One button over both would have
 * to hide that difference, and the difference is exactly what a user needs to see
 * when one of them does not take.
 */
/** Width of the trailing Connect / Connected slot on a search hit row. Wide enough
 *  for either label plus a Button's content padding, so both start at the same x and
 *  the rows form a column. `widthIn` rather than `width` so an outsized font scale
 *  grows it instead of clipping it. */
private val SearchActionSlotWidth = 132.dp

@Composable
fun CommunicationScreen(
    viewModel: RocketViewModel = viewModel(),
    service: BluetoothService?,
    onCancelButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val remoteReceiverConfig = viewModel.remoteReceiverConfig.collectAsState().value
    val receiverConfigMessageState = viewModel.receiverConfigMessageState.collectAsState().value
    val locatorConfigMessageState by viewModel.locatorConfigMessageState.collectAsState()
    val rocketState by viewModel.rocketState.collectAsState()
    val bluetoothConnectionState by BluetoothManagerRepository.bluetoothConnectionState.collectAsState()
    val conflictLocatorId by viewModel.conflictLocatorId.collectAsState()
    val locatorConnected by viewModel.locatorConnected.collectAsState()
    val connectedLocatorId by viewModel.connectedLocatorId.collectAsState()
    val remoteLocatorConfig by viewModel.remoteLocatorConfig.collectAsState()
    val channelSurvey by viewModel.channelSurvey.collectAsState()
    val surveyInProgress by viewModel.surveyInProgress.collectAsState()
    val locatorSearch by viewModel.locatorSearch.collectAsState()
    val knownLocators by viewModel.knownLocators.collectAsState()
    val pendingChannelMove by viewModel.pendingChannelMove.collectAsState()

    // Whether a locator's broadcasts are actually arriving, on the same 5 s rule the
    // channel watchdog uses for "the locator has gone quiet". Deliberately not the
    // 2 s freshness the map applies to a reading: this decides whether a whole
    // section is on screen, and at 1 Hz a single dropped broadcast would blink it.
    //
    // Recomputed on a tick because silence has no event. Nothing arrives to trigger
    // recomposition when the locator stops, so without this the section would stay
    // on screen until something else happened to redraw it.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }
    val hearingLocator = rocketState.lastMessageTime != 0L &&
            now - rocketState.lastMessageTime < RocketViewModel.CHANNEL_WATCH_SILENCE_MS

    // Which locator the search should stop on; null = report everything it finds,
    // which is also the only thing that works for a borrowed locator the app has
    // never heard of.
    var searchTargetId by remember { mutableStateOf<Long?>(null) }

    // Staged channels are LOCAL to this screen, not the shared receiverConfigChanged
    // flag Receiver Settings uses for the name. The two screens now edit different
    // fields of the same struct, and a shared dirty flag would let a name staged
    // over there light up the Update button over here with nothing to send.
    var stagedReceiverChannel by remember { mutableIntStateOf(remoteReceiverConfig.channel) }
    var stagedLocatorChannel by remember { mutableIntStateOf(remoteLocatorConfig.loraChannel) }
    // "The user typed here" is tracked, not derived. Deriving it from
    // staged != remote reads correctly and behaves backwards: the sync below runs
    // BECAUSE the device value changed, which is the moment the two are guaranteed
    // to differ, so a derived flag is true exactly when the sync is needed and
    // blocks it. That is what left the locator channel reading 0 — the screen
    // composed before the locator's config had arrived, seeded 0, and then refused
    // every update on the grounds that 0 was an edit in progress.
    var receiverChannelEdited by remember { mutableStateOf(false) }
    var locatorChannelEdited by remember { mutableStateOf(false) }
    val receiverChannelChanged = stagedReceiverChannel != remoteReceiverConfig.channel
    val locatorChannelChanged = stagedLocatorChannel != remoteLocatorConfig.loraChannel

    // Follow the device while the user has not typed anything, so a late-arriving
    // config or a channel changed from elsewhere shows up immediately — but never
    // overwrite a number being edited.
    LaunchedEffect(remoteReceiverConfig.channel) {
        if (!receiverChannelEdited) stagedReceiverChannel = remoteReceiverConfig.channel
    }
    LaunchedEffect(remoteLocatorConfig.loraChannel) {
        if (!locatorChannelEdited) stagedLocatorChannel = remoteLocatorConfig.loraChannel
    }

    LaunchedEffect(Unit) {
        // Re-entering is the user asking to see conflicts again, so a dismissal from
        // a previous visit does not persist.
        viewModel.resetConflictDismissals()
        // With no locator being heard, ReceiverInfo over BLE is the only way to learn
        // the channel we are actually on — and being on the wrong one is the whole
        // reason to open this screen.
        if (System.currentTimeMillis() - rocketState.lastMessageTime > 5_000L) {
            service?.requestReceiverInfo()
        }
    }

    // Solicit confirmation after a receiver change: PreLaunchData may no longer
    // arrive (that is what changing channel does), so BLE is the only ack path.
    LaunchedEffect(receiverConfigMessageState) {
        if (receiverConfigMessageState == LocatorMessageState.Sent) {
            delay(300L)
            service?.requestReceiverInfo()
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
        ) {
            // Conflicting traffic: another locator is audible and is not the one being
            // displayed. It belongs here rather than with the receiver's settings —
            // "somebody else is on your channel" is a channel fact, and the two
            // remedies (switch to it, or move away from it) are both on this screen.
            conflictLocatorId?.let { id ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
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

            // Progress for a locator channel move. The ADR-0011 cycle waits for
            // PreLaunchData to resume on the new channel and may revert and retry once,
            // so this runs for several seconds with the link legitimately down —
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

            // Search first, scan second. This screen is opened far more often because
            // something is missing than because something is noisy.
            LocatorSearchSection(
                run = locatorSearch,
                knownLocators = knownLocators,
                targetId = searchTargetId,
                candidates = viewModel.searchCandidates(searchTargetId),
                enabled = bluetoothConnectionState == BluetoothConnectionState.Ready &&
                        locatorSearch?.running != true && !surveyInProgress,
                onTargetChange = { searchTargetId = it },
                onSearch = { channels ->
                    viewModel.startLocatorSearch(service, channels, searchTargetId ?: 0L)
                },
                onCancel = { viewModel.cancelLocatorSearch(service) },
                connectedLocatorId = connectedLocatorId,
                onPick = { channel ->
                    // Receiver-only, always. The locator is already ON that channel —
                    // that is what the search just established — so moving it would be
                    // the one action guaranteed to lose it again.
                    //
                    // The staged value moves with it, or the field below would sit at
                    // the old number offering to undo what this just did.
                    stagedReceiverChannel = channel
                    receiverChannelEdited = false
                    viewModel.pointReceiverAtChannel(service, channel)
                    // Results are deliberately NOT cleared: the hit just acted on is
                    // the thing worth still seeing, and the row now reports that the
                    // receiver is there.
                },
            )

            // Shown only while a locator is being heard (fschroer, 2026-08-25).
            // "Find a clean channel" is for a link that is working badly; with nothing
            // coming through, the question is not which channel is quiet but where the
            // rocket is, and that is the section above.
            //
            // This NARROWS ADR-0019, whose tier-2 addendum argued for offering the sweep
            // from the no-locator state: it is the one instrument that catches a
            // continuous non-LoRa emitter, which the passive path cannot see. That
            // diagnostic is unreachable without a locator now, and ADR-0029 records the
            // trade rather than leaving it to be rediscovered.
            if (hearingLocator) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                ChannelSurveySection(
                    survey = channelSurvey,
                    inProgress = surveyInProgress,
                    knownLocators = knownLocators,
                    // Ready, not Connected: Connected is a transient step the connection
                    // manager passes through on its way to Ready, so gating on it leaves
                    // the button permanently disabled.
                    enabled = bluetoothConnectionState == BluetoothConnectionState.Ready &&
                            !surveyInProgress && locatorSearch?.running != true,
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
                            // Nothing to move: point the receiver, the legitimate "go look
                            // at that channel" case. Applied on the tap for the same reason
                            // the search's pick is — choosing from a ranked list is the
                            // decision, not a draft of one.
                            stagedReceiverChannel = channel
                            receiverChannelEdited = false
                            viewModel.pointReceiverAtChannel(service, channel)
                        }
                        viewModel.clearChannelSurvey()
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = stringResource(R.string.channels_manual_title),
                style = MaterialTheme.typography.titleSmall,
            )

            // ── Receiver channel ────────────────────────────────────────────────
            ConfigurationItemNumeric(
                configItemName = stringResource(R.string.channels_receiver_channel),
                initialConfigValue = stagedReceiverChannel,
                minValue = 0,
                maxValue = 63,
                configMessageState = receiverConfigMessageState,
                modifier = Modifier
            ) { newConfigValue ->
                stagedReceiverChannel = newConfigValue
                receiverChannelEdited = true
            }
            ChannelNote(
                stringResource(R.string.channels_receiver_explainer),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // What is known to be on the channel being typed. The scans already
            // gathered this; without it the manual field is the only control on this
            // screen that does not know the band it is pointing at, and typing a
            // number that another rocket is using is exactly the mistake it invites.
            // Only while a change is staged. The note describes what you would be
            // pointing at, so with nothing staged there is nothing to describe — and
            // "Twist 0 is on channel 34" while sitting on 34, connected to Twist 0, is
            // just the status panel read back as though it were news.
            if (receiverChannelChanged) {
                ChannelOccupancy.occupantOf(
                    stagedReceiverChannel, channelSurvey, locatorSearch,
                    excludeLocatorId = connectedLocatorId,
                    labelOf = { id -> knownLocators[id]?.label?.takeIf { it.isNotEmpty() } },
                )?.let { who ->
                    ChannelNote(
                        stringResource(R.string.channels_occupant_note, stagedReceiverChannel, who),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ApplyRow(
                enabled = receiverChannelChanged &&
                        receiverConfigMessageState == LocatorMessageState.Idle,
                messageState = receiverConfigMessageState,
                // Same call the two pick buttons make. It builds the message from the
                // last read-back and changes only the channel — the receiver's name
                // lives on Receiver Settings and rides in this same message, so sending
                // a locally-staged copy of the whole struct would let this screen
                // quietly revert a rename made over there.
                onApply = {
                    receiverChannelEdited = false
                    viewModel.pointReceiverAtChannel(service, stagedReceiverChannel)
                },
            )

            // ── Locator channel ─────────────────────────────────────────────────
            // Only offered when a locator is connected: this is a locator-directed
            // command (ADR-0020), so with nothing connected there is no locator to
            // address and the send would be refused anyway.
            if (locatorConnected) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ConfigurationItemNumeric(
                    configItemName = stringResource(R.string.channels_locator_channel),
                    initialConfigValue = stagedLocatorChannel,
                    minValue = 0,
                    maxValue = 63,
                    configMessageState = locatorConfigMessageState,
                    modifier = Modifier
                ) { newConfigValue ->
                    stagedLocatorChannel = newConfigValue
                    locatorChannelEdited = true
                }
                ChannelNote(
                    stringResource(R.string.channels_locator_explainer),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Gated on a staged change, because the warning is a claim about a
                // MOVE. Ungated it fired on the channel the locator is already using,
                // telling the user that staying put would collide with themselves —
                // and it was right about the occupancy and wrong about everything else.
                if (locatorChannelChanged) {
                    ChannelOccupancy.occupantOf(
                        stagedLocatorChannel, channelSurvey, locatorSearch,
                        excludeLocatorId = connectedLocatorId,
                        labelOf = { id -> knownLocators[id]?.label?.takeIf { it.isNotEmpty() } },
                    )?.let { who ->
                        ChannelNote(
                            stringResource(
                                R.string.channels_occupant_warning, stagedLocatorChannel, who,
                            ),
                            MaterialTheme.colorScheme.error,
                        )
                    }
                }
                ApplyRow(
                    enabled = locatorChannelChanged &&
                            locatorConfigMessageState == LocatorMessageState.Idle,
                    messageState = locatorConfigMessageState,
                    onApply = {
                        locatorChannelEdited = false
                        // The same call the survey's "Move here" makes, deliberately:
                        // one mechanism with two entry points rather than a second path
                        // to the same wire message. It carries the ADR-0011 confirm and
                        // revert-on-failure cycle, and lights the progress banner above.
                        viewModel.moveLocatorToChannel(service, stagedLocatorChannel)
                    },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onCancelButtonClicked
        ) {
            Text(stringResource(R.string.return_to_main))
        }
    }
}

/** Per-control apply button. Each device acknowledges differently, so each gets its
 *  own button and its own state rather than one that averages the two. */
@Composable
private fun ApplyRow(
    enabled: Boolean,
    messageState: LocatorMessageState,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_medium)),
    ) {
        Button(enabled = enabled, onClick = onApply) {
            Text(
                when (messageState) {
                    LocatorMessageState.Idle            -> stringResource(R.string.update)
                    LocatorMessageState.SendRequested,
                    LocatorMessageState.Sent            -> stringResource(R.string.updating)
                    LocatorMessageState.AckUpdated      -> stringResource(R.string.updated)
                    LocatorMessageState.SendFailure     -> stringResource(R.string.update_failed)
                    LocatorMessageState.NotAcknowledged -> stringResource(R.string.update_not_acknowledged)
                }
            )
        }
    }
}

@Composable
internal fun ChannelNote(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
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
internal fun ChannelSurveySection(
    survey: ChannelSurvey.Result?,
    inProgress: Boolean,
    enabled: Boolean,
    locatorConnected: Boolean,
    // Only ever read to put a name against an id the sweep reported. Claimed
    // identity from the air, so it labels and nothing more.
    knownLocators: Map<Long, KnownLocator>,
    onScan: () -> Unit,
    onPick: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Filled, like Update: these start work rather than offering a choice,
            // and the outlined style read as secondary next to the fields below.
            // "Return to main" stays outlined — it is the one control here that does
            // nothing to the hardware.
            Button(onClick = onScan, enabled = enabled) {
                Text(
                    stringResource(
                        if (inProgress) R.string.survey_scanning else R.string.survey_scan
                    )
                )
            }
        }

        when {
            survey == null -> Unit

            survey.status == ChannelSurvey.Status.RefusedArmed -> ChannelNote(
                stringResource(R.string.survey_refused_armed),
                MaterialTheme.colorScheme.error,
            )

            survey.status == ChannelSurvey.Status.RefusedBusy -> ChannelNote(
                stringResource(R.string.survey_refused_busy),
                MaterialTheme.colorScheme.error,
            )

            survey.status != ChannelSurvey.Status.Ok -> ChannelNote(
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
                    ChannelNote(
                        stringResource(R.string.survey_uniform_floor),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (survey.allChannelsHot) {
                    ChannelNote(
                        stringResource(R.string.survey_all_hot),
                        MaterialTheme.colorScheme.error,
                    )
                }
                survey.homeRank?.let { rank ->
                    ChannelNote(
                        stringResource(R.string.survey_home_rank, survey.homeChannel, rank),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Relative bar: quietest channel in this sweep is the reference, the
                // loudest is full scale. Deliberately unlabeled in dBm.
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
                ChannelNote(
                    stringResource(
                        if (locatorConnected) R.string.survey_moves_both
                        else R.string.survey_receiver_only
                    ),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Naming the channels that were excluded, and why, so a short list
                // does not read as a failed scan.
                if (survey.homeChannelInUse) {
                    // With a locator connected the occupant is ours and naming it is
                    // just confirmation. With none connected it is someone else's, and
                    // the name is the difference between "somebody is on your channel"
                    // and "your other rocket is, and you forgot it was powered on".
                    val homeWho = survey.confirmed
                        .firstOrNull { it.channel == survey.homeChannel }
                        ?.let { knownLocators[it.locatorId]?.label }
                        ?.takeIf { it.isNotEmpty() }
                    ChannelNote(
                        if (homeWho != null)
                            stringResource(
                                R.string.survey_home_in_use_named, survey.homeChannel, homeWho,
                            )
                        else stringResource(
                            if (locatorConnected) R.string.survey_home_in_use_own
                            else R.string.survey_home_in_use_other,
                            survey.homeChannel,
                        ),
                        if (locatorConnected) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                }
                survey.occupied.forEach { o ->
                    // Naming the occupant turns "some channel is busy" into "that is
                    // my other rocket", which is the difference between a warning you
                    // can act on and one you can only read. Unnamed when the id is
                    // unknown or absent — the count alone still says it is occupied.
                    val who = knownLocators[o.locatorId]?.label?.takeIf { it.isNotEmpty() }
                    ChannelNote(
                        if (who != null)
                            stringResource(R.string.survey_channel_occupied_named, o.channel, who)
                        else stringResource(R.string.survey_channel_occupied, o.channel),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ChannelNote(
                    stringResource(R.string.survey_confirmed_note),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChannelNote(
                    stringResource(R.string.survey_caveat),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "Find a locator" — the candidate-channel search (#33 follow-up to ADR-0019).
 *
 * Shaped by what a dwell costs. Each channel takes a full broadcast period to
 * rule out, so the default run is a handful of channels the locator is actually
 * likely to be on, and the whole band is offered only after those miss. The
 * channel list is shown before the run starts for the same reason: the user
 * should see that this is seconds of work, not a black box.
 */
@Composable
internal fun LocatorSearchSection(
    run: LocatorSearch.Run?,
    knownLocators: Map<Long, KnownLocator>,
    targetId: Long?,
    candidates: List<Int>,
    enabled: Boolean,
    onTargetChange: (Long?) -> Unit,
    onSearch: (List<Int>) -> Unit,
    onCancel: () -> Unit,
    onPick: (Int) -> Unit,
    connectedLocatorId: Long?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = stringResource(R.string.search_title),
            style = MaterialTheme.typography.titleSmall,
        )
        ChannelNote(
            stringResource(R.string.search_explainer),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Targeting is an accelerator, not a requirement: with a target the receiver
        // stops on the first frame from it, usually after one dwell. Without one the
        // run is a census, which is what finds a locator the app has never met.
        if (knownLocators.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            val targetName = targetId?.let { knownLocators[it]?.label }
                ?.takeIf { it.isNotEmpty() }
                ?: stringResource(R.string.search_target_any)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.search_target_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box {
                    TextButton(onClick = { expanded = true }, enabled = enabled) {
                        Text(targetName)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.search_target_any)) },
                            onClick = { onTargetChange(null); expanded = false },
                        )
                        knownLocators.forEach { (id, locator) ->
                            DropdownMenuItem(
                                text = { Text(locator.label.ifEmpty { "%08X".format(id) }) },
                                onClick = { onTargetChange(id); expanded = false },
                            )
                        }
                    }
                }
            }
        }

        if (run?.running == true) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinearProgressIndicator(
                    progress = { run.fraction },
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.search_cancel)) }
            }
            ChannelNote(
                stringResource(
                    if (run.wholeBand) R.string.search_progress_band else R.string.search_progress,
                    run.searched.coerceAtLeast(1), run.total,
                ),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(onClick = { onSearch(candidates) }, enabled = enabled) {
                Text(stringResource(R.string.search_start, candidates.size))
            }
        }

        // Hits appear as they arrive, including mid-run: on a targeted search the
        // run ends the moment one is found, and on a census the user should not have
        // to wait out 63 more channels to see the first answer.
        run?.hits?.forEach { hit ->
            // The name off the air, else the one we stored, else the raw id. A
            // TelemetryData hit carries no name at all — an armed locator's frame has
            // no room for one — so the stored label is what covers that case.
            val name = hit.deviceName.takeIf { it.isNotEmpty() }
                ?: knownLocators[hit.locatorId]?.label?.takeIf { it.isNotEmpty() }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            name == null -> stringResource(R.string.search_hit_unknown, hit.channel)
                            hit.armed -> stringResource(R.string.search_hit_armed, name, hit.channel)
                            else -> stringResource(R.string.search_hit, name, hit.channel)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Both numbers, in the status panel's format and colours. Neither
                    // decides alone: a locator a few feet from the receiver is heard on
                    // channels it is nowhere near and reads STRONG, and SNR is what
                    // separates that artifact from a genuine occupant. With the same
                    // locator reported on two channels, this row is what tells you
                    // which one to point at.
                    Row {
                        Text(
                            text = "${hit.rssi} dBm",
                            style = MaterialTheme.typography.labelSmall,
                            color = rssiColor(hit.rssi),
                        )
                        Text("  ", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = "SNR ${hit.snr} dB",
                            style = MaterialTheme.typography.labelSmall,
                            color = snrColor(hit.snr),
                        )
                        // One locator cannot be on two channels. When it is reported on
                        // several, all but the strongest are flagged rather than hidden:
                        // the reading is real, it is the CHANNEL attribution that is
                        // doubtful, and the numbers beside it are what let the user
                        // check that judgement instead of taking it on trust.
                        if (hit.channel in run.suspectChannels) {
                            Text("  ", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = stringResource(R.string.search_hit_suspect),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                // The receiver's own channel is the acknowledgment: it is read back
                // from the device (ReceiverInfo, or the next broadcast), so this flips
                // when the move has actually landed rather than when it was requested.
                // A fixed-width slot with both branches aligned to its START.
                //
                // The first attempt padded the text with ButtonDefaults.ContentPadding
                // and stopped there, which aligns the wrong edge: the Column above
                // takes weight(1f), so the trailing element is pushed against the row's
                // END, and equal end padding lines up the RIGHT edges. "Connected" is
                // the longer word, so its left edge hung out past the Connect labels.
                // Anchoring the slot instead makes the button and the text both start
                // at the same x, and the button's own start padding then puts its label
                // exactly where the padded text sits.
                Box(
                    modifier = Modifier.widthIn(min = SearchActionSlotWidth),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // Identity, not channel. Being tuned to a channel is not being
                    // connected: for a locator the app does not yet know, the receiver
                    // arrives on the channel while an ADR-0006 password challenge is
                    // still outstanding, and the row would have claimed Connected
                    // through the whole of it.
                    if (hit.locatorId != 0L && hit.locatorId == connectedLocatorId) {
                        Text(
                            text = stringResource(R.string.search_connected),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = ButtonDefaults.ContentPadding
                                    .calculateStartPadding(LayoutDirection.Ltr)
                            ),
                        )
                    } else {
                        Button(onClick = { onPick(hit.channel) }) {
                            Text(stringResource(R.string.search_connect))
                        }
                    }
                }
            }
        }

        if (run != null && !run.running) {
            when (run.status) {
                LocatorSearch.Status.RefusedArmed -> ChannelNote(
                    stringResource(R.string.search_refused_armed),
                    MaterialTheme.colorScheme.error,
                )
                LocatorSearch.Status.RefusedBusy -> ChannelNote(
                    stringResource(R.string.search_refused_busy),
                    MaterialTheme.colorScheme.error,
                )
                LocatorSearch.Status.Cancelled -> ChannelNote(
                    stringResource(R.string.search_cancelled),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LocatorSearch.Status.Unknown -> ChannelNote(
                    stringResource(R.string.search_failed),
                    MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
            // Widening is offered after ANY completed short run, never automatically.
            // It is ~80 s of a deaf receiver, so it stays a decision the user makes
            // knowing what it costs — but it has to be reachable. Gating it behind an
            // empty result meant that while any locator was audible there was no way
            // to sweep the band at all, which is precisely the multi-rocket case.
            if (run.canWiden) {
                // Said only when the run failed at its actual job. A targeted run that
                // turned up somebody else has not succeeded, and naming the locator is
                // clearer than "nothing found" when the screen is showing a hit.
                if (run.missed) {
                    val wanted = run.targetLocatorId
                        .takeIf { it != 0L }
                        ?.let { knownLocators[it]?.label?.takeIf { n -> n.isNotEmpty() } }
                    ChannelNote(
                        if (wanted != null)
                            stringResource(R.string.search_target_not_found, wanted)
                        else stringResource(R.string.search_none),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { onSearch(emptyList()) },
                    enabled = enabled,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.search_widen))
                }
            } else if (run.wholeBand && run.hits.isEmpty() &&
                run.status == LocatorSearch.Status.Done
            ) {
                ChannelNote(
                    stringResource(R.string.search_none_band),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (run.hits.isNotEmpty()) {
                ChannelNote(
                    stringResource(R.string.search_receiver_only),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChannelNote(
                    stringResource(R.string.search_unauthenticated),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

