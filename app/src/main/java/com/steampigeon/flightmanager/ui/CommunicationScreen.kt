package com.steampigeon.flightmanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.TextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.KnownLocator
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.ChannelMove
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.FlightStates
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

/** Width of the "Looking for" field and its menu. Sized for the longest locator name
 *  likely to be seen rather than for the widest possible one: a name that overruns
 *  ellipsises in the field and still reads in full in the open menu. */
private val SearchTargetFieldWidth = 200.dp

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
    val armedState by BluetoothManagerRepository.armedState.collectAsState()
    val bluetoothConnectionState by BluetoothManagerRepository.bluetoothConnectionState.collectAsState()
    val conflictLocatorId by viewModel.conflictLocatorId.collectAsState()
    val locatorConnected by viewModel.locatorConnected.collectAsState()
    val connectedLocatorId by viewModel.connectedLocatorId.collectAsState()
    val remoteLocatorConfig by viewModel.remoteLocatorConfig.collectAsState()
    val channelSurvey by viewModel.channelSurvey.collectAsState()
    val surveyInProgress by viewModel.surveyInProgress.collectAsState()
    val locatorSearch by viewModel.locatorSearch.collectAsState()
    val knownLocators by viewModel.knownLocators.collectAsState()
    val channelMoveBannerChannel by viewModel.channelMoveBannerChannel.collectAsState()
    val channelMoveResult by viewModel.channelMoveResult.collectAsState()
    val channelMoveOutcome by viewModel.channelMoveOutcome.collectAsState()

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

    // Both scans are refused by the RECEIVER while the locator is armed or flying
    // (ADR-0029 decision 7). Mirrored here so the buttons go dead with the reason
    // already on screen, rather than inviting a press whose only outcome is a
    // refusal — fschroer, 2026-08-30, running bench 4.
    //
    // Written to match the receiver's condition exactly rather than reusing
    // FlightMapScreen's isInFlight, which counts Landed as in flight: the receiver
    // excludes Landed deliberately, so a rocket on the ground is refused for being
    // ARMED and not for flying, and disabling on a stricter rule here would gray out
    // a scan the receiver would have run.
    //
    // This is an affordance, NOT enforcement. The receiver's gate is the real one and
    // stays — app-side gating is soft (ADR-0006 Decision 5), and the refusal text
    // below still renders if a request gets through anyway.
    val locatorArmedOrFlying = armedState ||
            (rocketState.flightState != FlightStates.WaitingLaunch &&
                    rocketState.flightState != FlightStates.Landed)

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
        // And neither scan's results persist across a visit — both live in the
        // ViewModel, so re-entering showed minutes-old findings as though they were
        // current. A run still in progress is left alone; the rule and the reasons
        // are in clearScansForNewVisit.
        viewModel.clearScansForNewVisit()
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
            // PreLaunchData to resume on the new channel, then probes both channels
            // (~2.8 s) and may revert and retry once, so this runs for several seconds
            // with the link legitimately down — silence there reads as a hang.
            // Dismiss hides the MESSAGE, not the staged channel: that channel is
            // what the ADR-0029 search looks on after a failed move, and it used to
            // be thrown away by the act of clearing the error describing it.
            //
            // The terminal state is read from channelMoveResult, which outlives the
            // 2 s Idle reset — the outcome of a cycle that can run ~23 s used to be
            // on screen for two.
            channelMoveBannerChannel?.let { channel ->
                val shownState = channelMoveResult ?: locatorConfigMessageState
                val (text, color) = when (shownState) {
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
                    // Three different endings share this state and leave the hardware
                    // in different places — see channelMoveOutcome.
                    //
                    // Where the receiver ACTUALLY is, not where the move was aimed.
                    // These two messages used to name the attempted channel, which is
                    // false whenever the forward never transmitted: with the locator
                    // already silent no forwarding window ever opens, so the receiver
                    // never follows and is still on the old channel. Reported from the
                    // bench 2026-08-30 — right verdict, wrong sentence. The receiver's
                    // own channel is known here regardless, because the channel watch
                    // polls ReceiverInfo every 2 s while the locator is quiet.
                    // Which sentence is earned is decided in ChannelMove.message and
                    // pinned there; this only maps it to a resource. Three of this
                    // amendment's defects were messages rather than logic, so the
                    // choice does not live inline in a composable any more.
                    LocatorMessageState.NotAcknowledged -> when (
                        ChannelMove.message(
                            verdict = channelMoveOutcome,
                            attemptedChannel = channel,
                            receiverChannel = remoteReceiverConfig.channel,
                        )
                    ) {
                        ChannelMove.Message.NotChecked ->
                            stringResource(
                                R.string.channel_move_not_checked, remoteReceiverConfig.channel,
                            ) to MaterialTheme.colorScheme.error
                        ChannelMove.Message.NothingMoved ->
                            stringResource(
                                R.string.channel_move_nothing_moved, remoteReceiverConfig.channel,
                            ) to MaterialTheme.colorScheme.onSurfaceVariant
                        ChannelMove.Message.Unresolved ->
                            stringResource(
                                R.string.channel_move_unresolved, remoteReceiverConfig.channel,
                            ) to MaterialTheme.colorScheme.error
                        else ->
                            stringResource(R.string.channel_move_not_acknowledged, channel) to
                                    MaterialTheme.colorScheme.error
                    }
                    LocatorMessageState.Idle -> null to MaterialTheme.colorScheme.onSurfaceVariant
                }
                text?.let {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = it, color = color, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.dismissChannelMoveBanner() }) {
                            Text(stringResource(R.string.dismiss))
                        }
                    }
                }
            }

            // Said once, above both scans, because one condition disables both and
            // two identical notes read as two problems.
            if (locatorArmedOrFlying) {
                ChannelNote(
                    stringResource(R.string.scans_blocked_armed),
                    MaterialTheme.colorScheme.error,
                )
            }

            // Search first, scan second. This screen is opened far more often because
            // something is missing than because something is noisy.
            LocatorSearchSection(
                run = locatorSearch,
                knownLocators = knownLocators,
                targetId = searchTargetId,
                candidates = viewModel.searchCandidates(searchTargetId),
                enabled = bluetoothConnectionState == BluetoothConnectionState.Ready &&
                        locatorSearch?.running != true && !surveyInProgress &&
                        !locatorArmedOrFlying,
                onTargetChange = { searchTargetId = it },
                onSearch = { channels ->
                    viewModel.startLocatorSearch(service, channels, searchTargetId ?: 0L)
                },
                onCancel = { viewModel.cancelLocatorSearch(service) },
                currentChannel = remoteReceiverConfig.channel,
                connectedLocatorId = connectedLocatorId,
                canConnect = receiverConfigMessageState == LocatorMessageState.Idle,
                onPick = { channel ->
                    // Receiver-only, always. The locator is already ON that channel —
                    // that is what the search just established — so moving it would be
                    // the one action guaranteed to lose it again.
                    //
                    // The staged value moves with it, or the field below would sit at
                    // the old number offering to undo what this just did — but ONLY if
                    // the change actually went out. Staging first and asking afterwards
                    // put a channel the app never visited into the field, with an
                    // enabled Update button offering to apply it.
                    if (viewModel.pointReceiverAtChannel(service, channel)) {
                        stagedReceiverChannel = channel
                        receiverChannelEdited = false
                    }
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
            //
            // **But never hide a scan this section is running, or the answer it
            // produced.** The rule above is about OFFERING the sweep. A sweep leaves
            // the receiver deaf for ~7.8 s — longer than the 5 s silence window — so
            // gating on `hearingLocator` alone made the section hide itself about five
            // seconds into its own scan, taking the "Scanning…" indicator with it, and
            // reappear with the results once broadcasts resumed. Reported 2026-08-30 as
            // the indicator vanishing and results arriving 3–4 seconds later; the scan
            // was running the whole time.
            //
            // `channelSurvey != null` is load-bearing, not belt-and-braces. Without it
            // the section hides again at the instant the results land — the sweep has
            // ended, so `surveyInProgress` is false, while the locator's next broadcast
            // is still up to a second away — and flickers back a moment later. Results
            // do not linger across visits: clearScansForNewVisit drops them on entry,
            // with the same "except one still running" exception.
            //
            // Same lesson as clearScansForNewVisit: a rule about when to START
            // something must not be applied to something already under way.
            if (hearingLocator || surveyInProgress || channelSurvey != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                ChannelSurveySection(
                    survey = channelSurvey,
                    inProgress = surveyInProgress,
                    knownLocators = knownLocators,
                    // Ready, not Connected: Connected is a transient step the connection
                    // manager passes through on its way to Ready, so gating on it leaves
                    // the button permanently disabled.
                    enabled = bluetoothConnectionState == BluetoothConnectionState.Ready &&
                            !surveyInProgress && locatorSearch?.running != true &&
                            !locatorArmedOrFlying,
                    onScan = { viewModel.requestChannelSurvey(service) },
                    locatorConnected = locatorConnected,
                    canPick = if (locatorConnected)
                        locatorConfigMessageState == LocatorMessageState.Idle
                    else receiverConfigMessageState == LocatorMessageState.Idle,
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
                            // decision, not a draft of one. Staged only if the change
                            // went out, for the reason the search's pick is.
                            if (viewModel.pointReceiverAtChannel(service, channel)) {
                                stagedReceiverChannel = channel
                                receiverChannelEdited = false
                            }
                        }
                        viewModel.clearChannelSurvey()
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // No help on the heading. The two fields do different things to different
            // devices, and one icon holding both paragraphs made the reader work out
            // which applied to which — the question the icon was meant to answer. Each
            // field carries its own instead.
            Text(
                text = stringResource(R.string.channels_manual_title),
                style = MaterialTheme.typography.titleMedium,
            )

            // ── Receiver channel ────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                // weight(1f) so the field takes the row and leaves the icon its place;
                // ConfigurationItemNumeric applies fillMaxWidth internally, which would
                // otherwise push the icon off the end.
                ConfigurationItemNumeric(
                    configItemName = stringResource(R.string.channels_receiver_channel),
                    initialConfigValue = stagedReceiverChannel,
                    minValue = 0,
                    maxValue = 63,
                    configMessageState = receiverConfigMessageState,
                    modifier = Modifier.weight(1f)
                ) { newConfigValue ->
                    stagedReceiverChannel = newConfigValue
                    receiverChannelEdited = true
                }
                SectionHelp(listOf(stringResource(R.string.channels_receiver_explainer)))
            }
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
                    unrecognizedLabel = stringResource(R.string.channels_occupant_unrecognized),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConfigurationItemNumeric(
                        configItemName = stringResource(R.string.channels_locator_channel),
                        initialConfigValue = stagedLocatorChannel,
                        minValue = 0,
                        maxValue = 63,
                        configMessageState = locatorConfigMessageState,
                        modifier = Modifier.weight(1f)
                    ) { newConfigValue ->
                        stagedLocatorChannel = newConfigValue
                        locatorChannelEdited = true
                    }
                    SectionHelp(listOf(stringResource(R.string.channels_locator_explainer)))
                }
                // Gated on a staged change, because the warning is a claim about a
                // MOVE. Ungated it fired on the channel the locator is already using,
                // telling the user that staying put would collide with themselves —
                // and it was right about the occupancy and wrong about everything else.
                if (locatorChannelChanged) {
                    ChannelOccupancy.occupantOf(
                        stagedLocatorChannel, channelSurvey, locatorSearch,
                        excludeLocatorId = connectedLocatorId,
                        labelOf = { id -> knownLocators[id]?.label?.takeIf { it.isNotEmpty() } },
                        unrecognizedLabel = stringResource(R.string.channels_occupant_unrecognized),
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

/**
 * A section title with an **i** that reveals its help on demand.
 *
 * The prose used to sit permanently under every control, and the screen grew to the
 * point where the standing explanations outweighed the results they were explaining
 * — the thing a user actually came to read was surrounded by paragraphs they had
 * already read on every previous visit. Help that is one tap away is help that can
 * afford to be complete.
 *
 * Only STATIC prose belongs here. Anything that varies with what just happened — a
 * scan's verdict, a refusal, "nothing found on those channels", the occupant of a
 * channel being typed — stays on the screen, because it is the answer rather than
 * the instructions.
 */
@Composable
private fun SectionTitle(title: String, help: List<String>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        SectionHelp(help)
    }
}

/** The **i** on its own, for a section whose title is already a control. */
@Composable
private fun SectionHelp(help: List<String>) {
    var showHelp by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(
                onClick = { showHelp = true },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.help_show),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showHelp) {
                // focusable = true is what makes a tap anywhere else dismiss it, and
                // what routes the back gesture here rather than off the screen.
                // The vertical drop is applied to the POPUP, not to its content.
                //
                // Offsetting the Surface inside the window left the window itself
                // starting at the icon and covering the 28 dp of transparent space
                // above the card. A focusable popup consumes touches anywhere in its
                // own window, so taps on the icon — and along the band to its right —
                // landed inside the popup, were swallowed, and dismissed nothing.
                // Moving the offset out makes the window exactly the card, so
                // everything else on the screen is genuinely outside it.
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(0, with(LocalDensity.current) { 28.dp.roundToPx() }),
                    onDismissRequest = { showHelp = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    // Clickable as well as focusable: focusable dismisses a tap
                    // OUTSIDE, and without this the popup itself was the one place on
                    // the screen where tapping did nothing. Reading it is the whole
                    // interaction, so the tap that follows is "done", wherever it lands.
                    // No indication — a ripple would suggest the surface is a control.
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 3.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { showHelp = false },
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            help.forEach {
                                Text(text = it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
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
    // Which device the pick commands decides which in-flight change has to finish
    // first — see LocatorSearchSection's canConnect.
    canPick: Boolean,
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
            // This section's button is its title, so the help hangs off the button.
            // What a pick does depends on whether a locator is connected — moving the
            // whole system or only the receiver — so that line is chosen here rather
            // than being two entries.
            SectionHelp(
                help = listOf(
                    stringResource(
                        if (locatorConnected) R.string.survey_moves_both
                        else R.string.survey_receiver_only
                    ),
                    stringResource(R.string.survey_confirmed_note),
                    stringResource(R.string.survey_caveat),
                ),
            )
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

            // Not an error: the scan gave way to something the user asked for.
            survey.status == ChannelSurvey.Status.Cancelled -> ChannelNote(
                stringResource(R.string.survey_cancelled),
                MaterialTheme.colorScheme.onSurfaceVariant,
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
                        TextButton(onClick = { onPick(s.channel) }, enabled = canPick) {
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
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun LocatorSearchSection(
    run: LocatorSearch.Run?,
    knownLocators: Map<Long, KnownLocator>,
    targetId: Long?,
    candidates: List<Int>,
    enabled: Boolean,
    // A change is already on its way to the receiver. Connect stays VISIBLE but
    // stops responding: pointReceiverAtChannel refuses while the receiver's config
    // message is not idle, so the second tap was dropped on the floor — a control
    // that silently did nothing, which is the failure this screen exists to avoid.
    canConnect: Boolean,
    onTargetChange: (Long?) -> Unit,
    onSearch: (List<Int>) -> Unit,
    onCancel: () -> Unit,
    onPick: (Int) -> Unit,
    currentChannel: Int,
    connectedLocatorId: Long?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        SectionTitle(
            title = stringResource(R.string.search_title),
            help = listOf(
                stringResource(R.string.search_explainer),
                stringResource(R.string.search_widen_help),
                stringResource(R.string.search_receiver_only),
                stringResource(R.string.search_unauthenticated),
            ),
        )

        // Targeting is an accelerator, not a requirement: with a target the receiver
        // stops on the first frame from it, usually after one dwell. Without one the
        // run is a census, which is what finds a locator the app has never met.
        if (knownLocators.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            val targetName = targetId?.let { knownLocators[it]?.label }
                ?.takeIf { it.isNotEmpty() }
                ?: stringResource(R.string.search_target_any)
            // The house dropdown: a read-only TextField carrying the selection with a
            // trailing chevron, the same shape Locator Settings' EnumDropdown and App
            // Settings' voice picker use. This had been a bare TextButton, which reads
            // as an action rather than as a field holding a current value — and the
            // value here is exactly what the user needs to check before starting a
            // search that behaves differently depending on it.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.search_target_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { if (enabled) expanded = !expanded },
                ) {
                    TextField(
                        value = targetName,
                        onValueChange = {},
                        readOnly = true,
                        enabled = enabled,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        // NotEditable, because the field cannot be typed into: it makes
                        // the whole field the anchor, so tapping the text opens the menu
                        // rather than only the chevron doing so.
                        // An explicit width is the only thing that shrinks this.
                        // Material gives a TextField a wide default minimum, so
                        // widthIn(max) is ignored and weight(1f) made it fill the row —
                        // a large filled block for a two-word value. The menu inherits
                        // the anchor's width, so this sizes both.
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .width(SearchTargetFieldWidth),
                        shape = if (expanded)
                            RoundedCornerShape(4.dp).copy(
                                bottomEnd = CornerSize(0.dp),
                                bottomStart = CornerSize(0.dp),
                            )
                        else RoundedCornerShape(4.dp),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
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
            // Both searches side by side, because they are the same decision at two
            // scales — try the likely channels, or try everything — and stacking them
            // made the second read as a consequence of the first rather than an
            // alternative to it. Widening only appears once a short run has completed;
            // the help behind the section's "i" says so.
            // FlowRow, not Row: side by side is the intent, but "Search 6 channels"
            // and "Search all 64 channels" together are within a few dp of a phone's
            // usable width at the default font scale, and past it at a larger one. A
            // Row would clip the second button; this drops it to the next line only
            // when it genuinely does not fit.
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(onClick = { onSearch(candidates) }, enabled = enabled) {
                    Text(stringResource(R.string.search_start, candidates.size))
                }
                if (run?.canWiden == true) {
                    Button(onClick = { onSearch(emptyList()) }, enabled = enabled) {
                        Text(stringResource(R.string.search_widen))
                    }
                }
            }
        }

        // Hits are actionable the moment they appear, and acting ends the run — the
        // receiver cannot both sweep and sit on the channel you just chose, so a
        // receiver channel change cancels the scan rather than being applied
        // underneath it (#40). Said out loud, because a scan stopping is otherwise
        // indistinguishable from a scan failing.
        if (run?.running == true && run.hits.isNotEmpty()) {
            ChannelNote(
                stringResource(R.string.search_connect_ends_scan),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Hits appear as they arrive, including mid-run: on a targeted search the
        // run ends the moment one is found, and on a census the user should not have
        // to wait out 63 more channels to see the first answer.
        run?.hits?.forEach { hit ->
            // The name off the air, else the one we stored, else nothing — the row
            // reads "Unrecognized locator on channel N" rather than falling back to a
            // hex id. A TelemetryData hit carries no name at all — an armed locator's
            // frame has no room for one — so the stored label is what covers that case.
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
                // Both branches are CENTRED in a slot that sizes to its own content.
                //
                // They must not fillMaxWidth: this Box is an unweighted child of the
                // Row, and Row measures those against the whole available width before
                // the weighted ones get anything. Filling it therefore consumed the
                // entire row and left the weight(1f) column — the name and the
                // RSSI/SNR — measured at zero width, which took the results off the
                // screen and wrapped what remained into a very tall row. Letting each
                // branch wrap keeps the slot honest and leaves the rest for the column.
                Box(
                    modifier = Modifier.widthIn(min = SearchActionSlotWidth),
                    contentAlignment = Alignment.Center,
                ) {
                    // Channel AND identity — see Hit.connectedOn. Identity alone
                    // marked every row for one locator as Connected, because a
                    // near-field locator's several hits all carry the same id, which
                    // left no way to reach the real channel from the false one.
                    if (hit.connectedOn(currentChannel, connectedLocatorId)) {
                        Text(
                            text = stringResource(R.string.search_connected),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Button(onClick = { onPick(hit.channel) }, enabled = canConnect) {
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
            // The widen BUTTON now sits beside the short search above; what stays here
            // is the reason it appeared. Said only when the run failed at its actual
            // job: a targeted run that turned up somebody else has not succeeded, and
            // naming the locator is clearer than "nothing found" when the screen is
            // showing a hit.
            if (run.canWiden) {
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
            } else if (run.wholeBand && run.hits.isEmpty() &&
                run.status == LocatorSearch.Status.Done
            ) {
                ChannelNote(
                    stringResource(R.string.search_none_band),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

