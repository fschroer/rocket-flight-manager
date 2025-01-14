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
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.ConfigMessageState
import kotlinx.coroutines.delay
import java.math.RoundingMode
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KClass

private const val TAG = "LocatorSettings"
private const val maxDelayMillis: Long = 500
private const val minDelayMillis: Long = 100
private const val delayDecayFactor: Float = .25f

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

    Column (
        modifier = modifier
            .fillMaxHeight()
            .padding(16.dp)
    ) {
        Column(
            modifier = modifier
//            .verticalScroll(scrollState)
                .padding(start = 40.dp),
            verticalArrangement = Arrangement.SpaceAround
        ) {
            // Capture initial and updated locator configuration data.
            // Used for configuration screen and confirming locator update acknowledgement.
            EnumDropdown(
                DeployMode::class,
                stagedLocatorConfig.deployMode ?: DeployMode.DroguePrimaryDrogueBackup,
                enabled = locatorConfigMessageState == ConfigMessageState.Idle,
                modifier = modifier
            )
            { newConfigValue ->
                stagedLocatorConfig = stagedLocatorConfig.copy(deployMode = newConfigValue as DeployMode)
                viewModel.updateLocatorConfigChanged(true)
            }
            if (stagedLocatorConfig.deployMode == DeployMode.DroguePrimaryDrogueBackup || stagedLocatorConfig.deployMode == DeployMode.DroguePrimaryMainPrimary)
                ConfigurationItemNumeric(
                    configItemName = stringResource(R.string.drogue_primary_deploy_delay),
                    initialConfigValue = stagedLocatorConfig.droguePrimaryDeployDelay.toDouble() / 10,
                    minValue = 0.0,
                    maxValue = max((stagedLocatorConfig.drogueBackupDeployDelay - 1).toDouble() / 10, 0.0),
                    configMessageState = locatorConfigMessageState,
                    modifier = modifier
                ) { newConfigValue ->
                    stagedLocatorConfig = stagedLocatorConfig.copy(droguePrimaryDeployDelay = (newConfigValue * 10).toInt())
                    viewModel.updateLocatorConfigChanged(true)
                }
            if (stagedLocatorConfig.deployMode == DeployMode.DroguePrimaryDrogueBackup || stagedLocatorConfig.deployMode == DeployMode.DrogueBackupMainBackup)
                ConfigurationItemNumeric(
                    configItemName = stringResource(R.string.drogue_backup_deploy_delay),
                    initialConfigValue = stagedLocatorConfig.drogueBackupDeployDelay.toDouble() / 10,
                    minValue = min((stagedLocatorConfig.droguePrimaryDeployDelay + 1).toDouble() / 10, 3.0),
                    maxValue = 3.0,
                    configMessageState = locatorConfigMessageState,
                    modifier = modifier
                ) { newConfigValue ->
                    stagedLocatorConfig = stagedLocatorConfig.copy(drogueBackupDeployDelay = (newConfigValue * 10).toInt())
                    viewModel.updateLocatorConfigChanged(true)
                }
            if (stagedLocatorConfig.deployMode == DeployMode.DroguePrimaryMainPrimary || stagedLocatorConfig.deployMode == DeployMode.MainPrimaryMainBackup)
                ConfigurationItemNumeric(
                    configItemName = stringResource(R.string.main_primary_deploy_altitude),
                    initialConfigValue = stagedLocatorConfig.mainPrimaryDeployAltitude,
                    minValue = min(stagedLocatorConfig.mainBackupDeployAltitude + 1, 500),
                    maxValue = 500,
                    configMessageState = locatorConfigMessageState,
                    modifier = modifier
                ) { newConfigValue ->
                    stagedLocatorConfig = stagedLocatorConfig.copy(mainPrimaryDeployAltitude = newConfigValue)
                    viewModel.updateLocatorConfigChanged(true)
                }
            if (stagedLocatorConfig.deployMode == DeployMode.MainPrimaryMainBackup || stagedLocatorConfig.deployMode == DeployMode.DrogueBackupMainBackup)
                ConfigurationItemNumeric(
                    configItemName = stringResource(R.string.main_backup_deploy_altitude),
                    initialConfigValue = stagedLocatorConfig.mainBackupDeployAltitude,
                    minValue = 0,
                    maxValue = max(stagedLocatorConfig.mainPrimaryDeployAltitude - 1, 0),
                    configMessageState = locatorConfigMessageState,
                    modifier = modifier
                ) { newConfigValue ->
                    stagedLocatorConfig = stagedLocatorConfig.copy(mainBackupDeployAltitude = newConfigValue)
                    viewModel.updateLocatorConfigChanged(true)
                }
            ConfigurationItemText(
                configItemName = stringResource(R.string.device_name),
                configItemValue = stagedLocatorConfig.deviceName,
                configMessageState = locatorConfigMessageState,
                modifier = modifier
            ) { newConfigValue ->
                stagedLocatorConfig = stagedLocatorConfig.copy(deviceName = newConfigValue)
                viewModel.updateLocatorConfigChanged(true)
            }
            ConfigurationItemNumeric(
                configItemName = stringResource(R.string.launch_detect_altitude),
                initialConfigValue = stagedLocatorConfig.launchDetectAltitude,
                minValue = 0,
                maxValue = 100,
                configMessageState = locatorConfigMessageState,
                modifier = modifier
            ) { newConfigValue ->
                stagedLocatorConfig =
                    stagedLocatorConfig.copy(launchDetectAltitude = newConfigValue)
                viewModel.updateLocatorConfigChanged(true)
            }
            ConfigurationItemNumeric(
                configItemName = stringResource(R.string.deploy_signal_duration),
                initialConfigValue = stagedLocatorConfig.deploySignalDuration.toDouble() / 10,
                minValue = 0.5,
                maxValue = 2.0,
                configMessageState = locatorConfigMessageState,
                modifier = modifier
            ) { newConfigValue ->
                stagedLocatorConfig =
                    stagedLocatorConfig.copy(deploySignalDuration = (newConfigValue * 10).toInt())
                viewModel.updateLocatorConfigChanged(true)
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
                Text(stringResource(R.string.cancel))
            }
            Button(
                modifier = Modifier.weight(1f),
                // the button is enabled when the user makes a selection
                enabled = (locatorConfigChanged && locatorConfigMessageState == ConfigMessageState.Idle),
                onClick = {
                    if (locatorConfigMessageState == ConfigMessageState.Idle) {
                        viewModel.updateLocatorConfigMessageState(ConfigMessageState.SendRequested)
                        if (service?.changeLocatorConfig(stagedLocatorConfig) == true)
                            viewModel.updateLocatorConfigMessageState(ConfigMessageState.Sent)
                        else
                            viewModel.updateLocatorConfigMessageState(ConfigMessageState.SendFailure)
                        viewModel.updateLocatorConfigState(stagedLocatorConfig)
                    }

                }
            ) {
                Text(
                    when (locatorConfigMessageState) {
                        ConfigMessageState.Idle -> stringResource(R.string.update)
                        ConfigMessageState.SendRequested,
                             ConfigMessageState.Sent -> stringResource(R.string.updating)
                        ConfigMessageState.AckUpdated -> stringResource(R.string.updated)
                        ConfigMessageState.SendFailure -> stringResource(R.string.update_failed)
                        ConfigMessageState.NotAcknowledged -> stringResource(R.string.update_not_acknowledged)
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
            enumClass.java.enumConstants.forEach { enumValue ->
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
                             configMessageState: ConfigMessageState = ConfigMessageState.Idle,
                             modifier: Modifier = Modifier,
                             onConfigUpdate: (Int) -> Unit) {
    var currentValue by remember { mutableIntStateOf(initialConfigValue)}
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        OutlinedTextField(
            value = if (currentValue != 0)
                        (currentValue).toString()
                    else
                        "",
            onValueChange = { newValue ->
                currentValue = (newValue.filter { it.isDigit() }.toIntOrNull() ?: 0)
            },
            modifier = modifier.onFocusChanged { focusState ->
                if (!focusState.isFocused && currentValue != initialConfigValue) {
                    currentValue = currentValue.coerceIn(minValue, maxValue)
                    onConfigUpdate(currentValue)
                }
                                               },
            enabled = configMessageState == ConfigMessageState.Idle,
            label = { Text(configItemName) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            //modifier = Modifier.weight(1f)
        )
        //Spacer(modifier = Modifier.width(8.dp))
        Column(
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            NudgeButton(currentValue, 1, maxValue, configMessageState,
                { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Increment") },
                { newValue -> currentValue = newValue
                    onConfigUpdate(newValue)
                })
            NudgeButton(currentValue, -1, minValue, configMessageState,
                { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Decrement") },
                { newValue -> currentValue = newValue
                    onConfigUpdate(newValue)
                })
        }
    }
}

@Composable
fun ConfigurationItemNumeric(configItemName: String,
                             initialConfigValue: Double,
                             minValue: Double = 0.0,
                             maxValue: Double = Double.MAX_VALUE,
                             configMessageState: ConfigMessageState = ConfigMessageState.Idle,
                             modifier: Modifier = Modifier,
                             onConfigUpdate: (Double) -> Unit) {
    var configValue by remember { mutableDoubleStateOf(initialConfigValue)}
    var configText by remember { mutableStateOf(initialConfigValue.toString()) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        OutlinedTextField(
            value = configText,
            onValueChange = { newValue ->
                configValue = (newValue.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0)
                configText = newValue
            },
            modifier = modifier.onFocusChanged { focusState ->
                if (!focusState.isFocused && configValue != initialConfigValue) {
                    configValue = configValue.coerceIn(minValue, maxValue)
                    configText = configValue.toBigDecimal().setScale(1, RoundingMode.HALF_UP).toString()
                    onConfigUpdate(configValue)
                }
            },
            enabled = configMessageState == ConfigMessageState.Idle,
            label = { Text(configItemName) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            //modifier = Modifier.weight(1f)
        )
        //Spacer(modifier = Modifier.width(8.dp))
        Column(
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            NudgeButton(configValue, 0.1, maxValue, configMessageState,
                { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Increment") },
                { newValue -> configValue = newValue
                    configText = configValue.toBigDecimal().setScale(1, RoundingMode.HALF_UP).toString()
                    onConfigUpdate(newValue)
                })
            NudgeButton(configValue, -0.1, minValue, configMessageState,
                { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Decrement") },
                { newValue -> configValue = newValue
                    configText = configValue.toBigDecimal().setScale(1, RoundingMode.HALF_UP).toString()
                    onConfigUpdate(newValue)
                })
        }
    }
}

@Composable
fun ConfigurationItemText(configItemName: String,
                          configItemValue: String,
                          configMessageState: ConfigMessageState = ConfigMessageState.Idle,
                          modifier: Modifier = Modifier,
                          onConfigUpdate: (String) -> Unit) {

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
            enabled = configMessageState == ConfigMessageState.Idle,
            label = { Text(configItemName) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            //modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun NudgeButton(
    configItemValue: Int,
    change: Int,
    bound: Int,
    configMessageState: ConfigMessageState = ConfigMessageState.Idle,
    content: @Composable RowScope.() -> Unit,
    onConfigUpdate: (Int) -> Unit
) {
    var counter = configItemValue
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val currentClickListener by rememberUpdatedState(onConfigUpdate)

    TextButton(
        onClick = { },
        enabled = (if(change > 0) counter < bound else counter > bound) && configMessageState == ConfigMessageState.Idle,
        interactionSource = interactionSource,
        content = content
    )
    // Increment counter while button is pressed
    LaunchedEffect(isPressed) {
        var currentDelayMillis = maxDelayMillis
        while (isPressed) {
            counter += change
            onConfigUpdate(counter)
            //currentClickListener(counter)
            delay(currentDelayMillis)
            val nextDelayMillis = currentDelayMillis - (currentDelayMillis * delayDecayFactor)
            currentDelayMillis = nextDelayMillis.toLong().coerceAtLeast(minDelayMillis)
        }
    }
}

@Composable
fun NudgeButton(
    configItemValue: Double,
    change: Double,
    bound: Double,
    configMessageState: ConfigMessageState = ConfigMessageState.Idle,
    content: @Composable RowScope.() -> Unit,
    onConfigUpdate: (Double) -> Unit
) {
    var counter = configItemValue
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val currentClickListener by rememberUpdatedState(onConfigUpdate)

    TextButton(
        onClick = { },
        enabled = (if(change > 0) counter < bound - 0.05 else counter > bound + 0.05) && configMessageState == ConfigMessageState.Idle,
        interactionSource = interactionSource,
        content = content
    )
    // Increment counter while button is pressed
    LaunchedEffect(isPressed) {
        var currentDelayMillis = maxDelayMillis
        while (isPressed) {
            counter += change
            onConfigUpdate(counter)
            //currentClickListener(counter)
            delay(currentDelayMillis)
            val nextDelayMillis = currentDelayMillis - (currentDelayMillis * delayDecayFactor)
            currentDelayMillis = nextDelayMillis.toLong().coerceAtLeast(minDelayMillis)
        }
    }
}