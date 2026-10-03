package com.steampigeon.flightmanager.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.LocatorSettingsBounds
import com.steampigeon.flightmanager.data.LocatorSettingsBounds.Setting
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.NoseAxis
import com.steampigeon.flightmanager.data.Protocol
import kotlinx.coroutines.delay
import kotlin.reflect.KClass

private const val TAG = "LocatorSettings"
private const val maxDelayMillis: Long = 500
private const val minDelayMillis: Long = 100
private const val delayDecayFactor = .25f
private const val configItemWidth = 6f

/**
 * Composable that displays map download options,
 * [onCancelButtonClicked] lambda that cancels the order when user clicks cancel and
 */
@Composable
fun LocatorSettingsScreen(
    viewModel: RocketViewModel = viewModel(),
    service: BluetoothService?,
    onCancelButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var stagedLocatorConfig by remember {mutableStateOf(viewModel.remoteLocatorConfig.value)}
    var locatorConfigChanged = viewModel.locatorConfigChanged.collectAsState().value
    val locatorConfigMessageState = viewModel.locatorConfigMessageState.collectAsState().value
    val locatorVersion by viewModel.locatorVersion.collectAsState()
    val scrollState = rememberScrollState()
    // Settings whose field holds an entry this screen refuses (ADR-0034 decision
    // 3): out of range, unreadable, or empty.  Update stays disabled while any do.
    var refusedSettings by remember { mutableStateOf(emptySet<Setting>()) }
    // The config as it stood when typing began in a field.  A typed value pushes
    // its partner from HERE, not from the last keystroke: typing 300 into a main
    // primary passes through 3 and 30 on the way, and pushing from each of those
    // would leave the backup dragged down to 29 by a number nobody meant.
    var typingBase by remember { mutableStateOf<LocatorConfig?>(null) }

    // The delay or altitude field for whichever deployment a channel is set to.
    // Both channels of a pair edit the same two values, and moving one onto the
    // other pushes the other along (ADR-0034 decision 2) — the arrows step by
    // one, a typed value jumps, and either way the pair stays in order.
    @Composable
    fun DeploymentSetting(mode: DeployMode?) {
        val setting = when (mode) {
            DeployMode.DroguePrimary -> Setting.DroguePrimary
            DeployMode.DrogueBackup -> Setting.DrogueBackup
            DeployMode.MainPrimary -> Setting.MainPrimary
            DeployMode.MainBackup -> Setting.MainBackup
            else -> return
        }
        DeploymentSettingField(
            configItemName = stringResource(
                when (setting) {
                    Setting.DroguePrimary -> R.string.drogue_primary_deploy_delay
                    Setting.DrogueBackup -> R.string.drogue_backup_deploy_delay
                    Setting.MainPrimary -> R.string.main_primary_deploy_altitude
                    Setting.MainBackup -> R.string.main_backup_deploy_altitude
                }
            ),
            setting = setting,
            value = LocatorSettingsBounds.valueOf(stagedLocatorConfig, setting),
            canIncrement = LocatorSettingsBounds.step(stagedLocatorConfig, setting, +1) != stagedLocatorConfig,
            canDecrement = LocatorSettingsBounds.step(stagedLocatorConfig, setting, -1) != stagedLocatorConfig,
            configMessageState = locatorConfigMessageState,
            modifier = modifier,
            onEditStart = { typingBase = stagedLocatorConfig },
            onTyped = { value ->
                stagedLocatorConfig = LocatorSettingsBounds.withValue(typingBase ?: stagedLocatorConfig, setting, value)
                viewModel.updateLocatorConfigChanged(true)
            },
            onStep = { delta ->
                stagedLocatorConfig = LocatorSettingsBounds.step(stagedLocatorConfig, setting, delta)
                typingBase = stagedLocatorConfig
                viewModel.updateLocatorConfigChanged(true)
            },
            onRefusedChange = { refused ->
                refusedSettings = if (refused) refusedSettings + setting else refusedSettings - setting
            }
        )
    }
    // Every field accepted, and the pair in order.  The second can only fail on a
    // config read from a locator older than ADR-0034, which could store anything;
    // one arrow press on either member of the pair puts it back in order.
    val stagedConfigValid = refusedSettings.isEmpty() && LocatorSettingsBounds.isValid(stagedLocatorConfig)

    Column (
        modifier = modifier
            .fillMaxHeight()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.Top
        ) {
            // Firmware version (read-only, populated once VersionInfo is received)
            if (locatorVersion.isNotEmpty()) {
                Text(
                    text = "Firmware: $locatorVersion",
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Capture initial and updated locator configuration data.
            // Used for configuration screen and confirming locator update acknowledgment.
            Text(stringResource(R.string.deployment_channel_1))
            EnumDropdown(
                DeployMode::class,
                stagedLocatorConfig.deploymentChannel1Mode ?: DeployMode.DroguePrimary,
                enabled = locatorConfigMessageState == LocatorMessageState.Idle,
                modifier = modifier
            )
            { newConfigValue ->
                stagedLocatorConfig = stagedLocatorConfig.copy(deploymentChannel1Mode = newConfigValue as DeployMode)
                viewModel.updateLocatorConfigChanged(true)
            }
            DeploymentSetting(stagedLocatorConfig.deploymentChannel1Mode)
            Text(stringResource(R.string.deployment_channel_2))
            EnumDropdown(
                DeployMode::class,
                stagedLocatorConfig.deploymentChannel2Mode ?: DeployMode.DrogueBackup,
                enabled = locatorConfigMessageState == LocatorMessageState.Idle,
                modifier = modifier
            )
            { newConfigValue ->
                stagedLocatorConfig = stagedLocatorConfig.copy(deploymentChannel2Mode = newConfigValue as DeployMode)
                viewModel.updateLocatorConfigChanged(true)
            }
            DeploymentSetting(stagedLocatorConfig.deploymentChannel2Mode)
            Text(stringResource(R.string.deployment_channel_3))
            EnumDropdown(
                DeployMode::class,
                stagedLocatorConfig.deploymentChannel3Mode ?: DeployMode.MainPrimary,
                enabled = locatorConfigMessageState == LocatorMessageState.Idle,
                modifier = modifier
            )
            { newConfigValue ->
                stagedLocatorConfig = stagedLocatorConfig.copy(deploymentChannel3Mode = newConfigValue as DeployMode)
                viewModel.updateLocatorConfigChanged(true)
            }
            DeploymentSetting(stagedLocatorConfig.deploymentChannel3Mode)
            Text(stringResource(R.string.deployment_channel_4))
            EnumDropdown(
                DeployMode::class,
                stagedLocatorConfig.deploymentChannel4Mode ?: DeployMode.MainBackup,
                enabled = locatorConfigMessageState == LocatorMessageState.Idle,
                modifier = modifier
            )
            { newConfigValue ->
                stagedLocatorConfig = stagedLocatorConfig.copy(deploymentChannel4Mode = newConfigValue as DeployMode)
                viewModel.updateLocatorConfigChanged(true)
            }
            DeploymentSetting(stagedLocatorConfig.deploymentChannel4Mode)
            ConfigurationItemText(
                configItemName = stringResource(R.string.device_name),
                configItemValue = stagedLocatorConfig.deviceName,
                configMessageState = locatorConfigMessageState,
                modifier = modifier
            ) { newConfigValue ->
                stagedLocatorConfig = stagedLocatorConfig.copy(deviceName = newConfigValue.take(Protocol.DEVICE_NAME_LENGTH))
                viewModel.updateLocatorConfigChanged(true)
            }
            // The LoRa channel moved to the Channels screen, next to the two scans
            // that choose one and the receiver channel that has to follow it.  This
            // screen is flight configuration; which frequency you are talking on is a
            // different job, and doing it here meant deciding a channel on one screen
            // and verifying it on another.
            //
            // The field is gone, not the value: loraChannel still rides in this
            // screen's config struct, read back from the broadcast, so an edit to any
            // other setting sends the locator's current channel unchanged.
            // Launch-detect altitude and deploy-signal duration are deliberately NOT
            // offered here, and that is the only thing missing from this screen.
            // ADR-0028 is the decision; the short version is that neither field rides
            // in PreLaunchData, so the app cannot read back what the locator holds for
            // either, and it confirms a change by whole-object equality against a
            // config rebuilt from the next broadcast.  Editing one therefore reported
            // "Update not acknowledged" while the locator had accepted and saved it.
            //
            // Omitting the controls is what makes every OTHER field here confirmable:
            // LocatorConfig no longer carries either field, so nothing in the
            // comparison is a value the app had to invent.  Both slots stay on the
            // wire as reserved bytes (LocatorConfigWire) and the locator restores its
            // own values, so a change from this screen no longer overwrites them.

            // How the locator is mounted in the airframe (ADR-0021 Decision 6, #36).
            // Static per installation, but the locator cannot infer it: mounting
            // calibration finds the axis gravity lies along and calls it "up", which
            // is only the nose axis if the rocket happens to be vertical. Stating it
            // is what makes tilt-from-vertical measurable off the pad.
            Text(stringResource(R.string.nose_axis))
            EnumDropdown(
                NoseAxis::class,
                stagedLocatorConfig.noseAxis,
                enabled = locatorConfigMessageState == LocatorMessageState.Idle,
                modifier = modifier
            )
            { newConfigValue ->
                stagedLocatorConfig = stagedLocatorConfig.copy(noseAxis = newConfigValue as NoseAxis)
                viewModel.updateLocatorConfigChanged(true)
            }
        }
        if (refusedSettings.isEmpty() && !stagedConfigValid) {
            Text(
                text = stringResource(R.string.settings_out_of_order),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                // ...and never while a field holds a refused entry: Update would
                // send the last ACCEPTED value, not the one on screen.
                enabled = (locatorConfigChanged && stagedConfigValid &&
                        locatorConfigMessageState == LocatorMessageState.Idle),
                onClick = {
                    if (locatorConfigMessageState == LocatorMessageState.Idle) {
                        viewModel.updateLocatorConfigMessageState(LocatorMessageState.SendRequested)
                        if (service?.changeLocatorConfig(stagedLocatorConfig) == true)
                            viewModel.updateLocatorConfigMessageState(LocatorMessageState.Sent)
                        else
                            viewModel.updateLocatorConfigMessageState(LocatorMessageState.SendFailure)
                        viewModel.updateLocatorConfigState(stagedLocatorConfig, service)
                    }
                }
            ) {
                Text(
                    when (locatorConfigMessageState) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnumDropdown(
    enumClass: KClass<out Enum<*>>,
    selectedValue: Enum<*>,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onValueChange: (Enum<*>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier.padding(bottom = 16.dp)
    ) {
        TextField(
            value = selectedValue.name,
            onValueChange = {},
            modifier = modifier.menuAnchor(MenuAnchorType.PrimaryEditable, true),
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = if (expanded) RoundedCornerShape(4.dp).copy(bottomEnd = CornerSize(0.dp), bottomStart = CornerSize(0.dp))
            else RoundedCornerShape(4.dp)
        )
        ExposedDropdownMenu(expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            enumClass.java.enumConstants?.forEach { enumValue ->
                DropdownMenuItem(text = { Text(enumValue.name) },
                    onClick = {
                        onValueChange(enumValue)
                        expanded = false
                    })
            }
        }
    }
}

@Composable
fun ConfigurationItemNumeric(configItemName: String,
                             initialConfigValue: Int,
                             minValue: Int = 0,
                             maxValue: Int = Int.MAX_VALUE,
                             configMessageState: LocatorMessageState = LocatorMessageState.Idle,
                             modifier: Modifier = Modifier,
                             onRefusedChange: (Boolean) -> Unit = {},
                             onConfigUpdate: (Int) -> Unit) {
    var currentValue by remember { mutableIntStateOf(initialConfigValue)}
    // A typed value outside [minValue, maxValue] is shown and refused, not
    // clamped and not committed (ADR-0034 decision 3); the caller holds its
    // Update button through onRefusedChange.  It used to be committed raw, and
    // the focus-loss clamp below compared against a value typing had already
    // overwritten, so it never ran.
    val refused = currentValue !in minValue..maxValue
    val currentOnRefusedChange by rememberUpdatedState(onRefusedChange)
    LaunchedEffect(refused) { currentOnRefusedChange(refused) }
    var lastKeyPressed by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    // Follow externally-driven changes to the value. `remember` seeds once, so a
    // value set anywhere other than this text field — the channel survey picking a
    // channel, or fresh config arriving from the device — updated the staged config
    // while the field on screen kept showing the old number.
    // Typing is unaffected: the field's own edits call onConfigUpdate, which makes
    // initialConfigValue match currentValue, so this is a no-op on that path.
    LaunchedEffect(initialConfigValue) {
        if (initialConfigValue != currentValue) currentValue = initialConfigValue
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        OutlinedTextField(
            value = if (currentValue == 0 && lastKeyPressed == "Key code: 287762808832")
                        ""
                    else
                        (currentValue).toString(),
            onValueChange = { newValue ->
                // take(6): a long run of digits is out of range anyway; don't overflow Int.
                currentValue = (newValue.filter { it.isDigit() }.take(6).toIntOrNull() ?: 0)
                if (currentValue in minValue..maxValue) onConfigUpdate(currentValue)
            },
            modifier = Modifier
                .weight(configItemWidth)
                .onKeyEvent { keyEvent: KeyEvent ->
                    lastKeyPressed = keyEvent.key.toString() // Capture the last key pressed
                    false // Return false to allow further processing of the event
                },
            enabled = configMessageState == LocatorMessageState.Idle,
            label = { Text(configItemName) },
            isError = refused,
            supportingText = if (refused) {
                { Text(stringResource(R.string.settings_range, minValue, maxValue)) }
            } else null,
            // Single line with a Done key, so Enter dismisses the keypad instead of
            // inserting a newline.
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        )
        //Spacer(modifier = Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            NudgeButton(currentValue, 1, maxValue, configMessageState,
                { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(id = R.string.increment)) },
                { newValue -> currentValue = newValue
                    onConfigUpdate(newValue)
                })
            NudgeButton(currentValue, -1, minValue, configMessageState,
                { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(id = R.string.decrement)) },
                { newValue -> currentValue = newValue
                    onConfigUpdate(newValue)
                })
        }
    }
}

/**
 * One deployment delay or altitude (ADR-0034).
 *
 * A typed entry is checked against the bounds table on every keystroke.  In
 * range, it goes to [onTyped] and may push the pair's other member along.  Out
 * of range, it stays on screen with the reason under it, nothing is staged, and
 * [onRefusedChange] tells the screen to hold Update — decision 3: refuse, never
 * clamp.  The arrows go through [onStep], which stops at the limits, so they
 * never produce anything to refuse.
 */
@Composable
fun DeploymentSettingField(
    configItemName: String,
    setting: Setting,
    value: Int,
    canIncrement: Boolean,
    canDecrement: Boolean,
    configMessageState: LocatorMessageState,
    modifier: Modifier = Modifier,
    onEditStart: () -> Unit,
    onTyped: (Int) -> Unit,
    onStep: (Int) -> Unit,
    onRefusedChange: (Boolean) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(LocatorSettingsBounds.format(setting, value)) }
    var focused by remember { mutableStateOf(false) }
    val parsed = LocatorSettingsBounds.validate(setting, text)
    val problem = (parsed as? LocatorSettingsBounds.Parsed.Refused)?.problem

    // Follow the value when it moves for any other reason: an arrow, a push from
    // the pair's other member, or a field for the same setting on another
    // channel.  Text that already reads as this value is left alone, so "1."
    // stays "1." while the user is still typing "1.5".
    LaunchedEffect(value) {
        if ((LocatorSettingsBounds.validate(setting, text) as? LocatorSettingsBounds.Parsed.Ok)?.value != value)
            text = LocatorSettingsBounds.format(setting, value)
    }
    val currentOnRefusedChange by rememberUpdatedState(onRefusedChange)
    LaunchedEffect(problem != null) { currentOnRefusedChange(problem != null) }
    // Switching a channel's mode removes this field; a refusal must not outlive it
    // and hold Update disabled with nothing on screen to fix.
    DisposableEffect(Unit) { onDispose { currentOnRefusedChange(false) } }

    fun shown(v: Int) =
        if (setting.tenths) "${LocatorSettingsBounds.format(setting, v)} s" else "$v m"

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                text = newText
                val p = LocatorSettingsBounds.validate(setting, newText)
                if (p is LocatorSettingsBounds.Parsed.Ok) onTyped(p.value)
            },
            modifier = Modifier
                .onFocusChanged { focusState ->
                    if (focusState.isFocused && !focused) onEditStart()
                    // Tidy an accepted entry ("05" -> "5", "1." -> "1.0").  A refused
                    // one is left exactly as typed, so the user sees what was wrong.
                    if (!focusState.isFocused && focused && problem == null)
                        text = LocatorSettingsBounds.format(setting, value)
                    focused = focusState.isFocused
                }
                .weight(configItemWidth),
            enabled = configMessageState == LocatorMessageState.Idle,
            label = { Text(configItemName) },
            isError = problem != null,
            supportingText = problem?.let {
                {
                    Text(
                        when (it) {
                            LocatorSettingsBounds.Problem.Empty -> stringResource(R.string.settings_enter_value)
                            LocatorSettingsBounds.Problem.TooPrecise -> stringResource(R.string.settings_tenths_only)
                            is LocatorSettingsBounds.Problem.BelowMin -> stringResource(R.string.settings_min, shown(it.min))
                            is LocatorSettingsBounds.Problem.AboveMax -> stringResource(R.string.settings_max, shown(it.max))
                        }
                    )
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (setting.tenths) KeyboardType.Decimal else KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            StepButton(canIncrement && configMessageState == LocatorMessageState.Idle, { onStep(+1) }) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(id = R.string.increment))
            }
            StepButton(canDecrement && configMessageState == LocatorMessageState.Idle, { onStep(-1) }) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(id = R.string.decrement))
            }
        }
    }
}

/**
 * An arrow that steps by one, with hold-to-repeat.  Unlike [NudgeButton] it does
 * not compute the next value itself: each repeat calls [onStep] afresh, so the
 * caller's push rule runs on every step and the repeat stops at the limit
 * instead of running past it.
 */
@Composable
fun StepButton(
    enabled: Boolean,
    onStep: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val currentOnStep by rememberUpdatedState(onStep)
    TextButton(
        onClick = { if (enabled) onStep() },
        enabled = enabled,
        interactionSource = interactionSource,
        content = content
    )
    // onClick handles a single tap, so the repeat starts only after the initial delay.
    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(maxDelayMillis)
            var currentDelayMillis = maxDelayMillis
            while (isPressed) {
                currentOnStep()
                delay(currentDelayMillis)
                val nextDelayMillis = currentDelayMillis - (currentDelayMillis * delayDecayFactor)
                currentDelayMillis = nextDelayMillis.toLong().coerceAtLeast(minDelayMillis)
            }
        }
    }
}

@Composable
fun ConfigurationItemText(configItemName: String,
                          configItemValue: String,
                          configMessageState: LocatorMessageState = LocatorMessageState.Idle,
                          modifier: Modifier = Modifier,
                          onConfigUpdate: (String) -> Unit) {
    val focusManager = LocalFocusManager.current

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        OutlinedTextField(
            value = configItemValue,
            onValueChange = { newValue ->
                onConfigUpdate(newValue)
            },
            modifier = modifier.weight(configItemWidth),
            enabled = configMessageState == LocatorMessageState.Idle,
            label = { Text(configItemName) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            //modifier = Modifier.weight(1f)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
        }
    }
}

@Composable
fun NudgeButton(
    configItemValue: Int,
    change: Int,
    bound: Int,
    configMessageState: LocatorMessageState = LocatorMessageState.Idle,
    content: @Composable RowScope.() -> Unit,
    onConfigUpdate: (Int) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val enabled = (if (change > 0) configItemValue < bound else configItemValue > bound) &&
            configMessageState == LocatorMessageState.Idle

    TextButton(
        onClick = { if (enabled) onConfigUpdate(configItemValue + change) },
        enabled = enabled,
        interactionSource = interactionSource,
        content = content
    )
    // Hold-to-repeat: after an initial delay, keep incrementing while the button is held.
    // onClick handles the single-tap case, so start the repeat loop only after the initial delay
    // to avoid a double-increment on a quick tap.
    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(maxDelayMillis)
            var currentDelayMillis = maxDelayMillis
            var repeatValue = configItemValue + change
            while (isPressed) {
                onConfigUpdate(repeatValue)
                repeatValue += change
                delay(currentDelayMillis)
                val nextDelayMillis = currentDelayMillis - (currentDelayMillis * delayDecayFactor)
                currentDelayMillis = nextDelayMillis.toLong().coerceAtLeast(minDelayMillis)
            }
        }
    }
}
