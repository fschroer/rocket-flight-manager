package com.steampigeon.flightmanager.ui

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.LocatorArmedMessageState
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.LocatorConfigMessageState

private const val TAG = "LocatorSettings"
private val locatorData = LocatorData()

/**
 * Composable that displays map download options,
 * [onSelectionChanged] lambda that notifies the parent composable when a new value is selected,
 * [onCancelButtonClicked] lambda that cancels the order when user clicks cancel and
 * [onNextButtonClicked] lambda that triggers the navigation to next screen
 */
@Composable
fun LocatorSettingsScreen(
    viewModel: RocketViewModel,
    onSelectionChanged: (String) -> Unit = {},
    onCancelButtonClicked: () -> Unit = {},
    onNextButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var configChanged = remember {mutableStateOf(false)}
    var initialConfigCaptured by remember {mutableStateOf(false)}
    var updateableLocatorConfig by remember {mutableStateOf(LocatorConfig())}
    var updatedLocatorConfig by remember {mutableStateOf(LocatorConfig())}
    var configChangeAcknowldedgeWaitCount by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Capture initial and updated locator configuration data.
        // Used for configuration screen and confirming locator update acknowledgement.
        val rocketData = locatorData.getLocatorData(LocalContext.current, viewModel)
        LaunchedEffect(viewModel.locatorConfig.collectAsState()) {
            Log.d(TAG, "Current time: ${System.currentTimeMillis()}")
            Log.d(TAG, "Last message: ${rocketData.lastMessageTime}")
            Log.d(TAG, "Difference: ${System.currentTimeMillis() - rocketData.lastMessageTime}")
            if (System.currentTimeMillis() - rocketData.lastMessageTime < 2000) {
                if (!initialConfigCaptured) {
                    updateableLocatorConfig = viewModel.locatorConfig.value
                    initialConfigCaptured = true
                }
                updatedLocatorConfig = viewModel.locatorConfig.value
            }
        }
        if (initialConfigCaptured) {
            numericEntryWithButtons(
                updateableLocatorConfig.launchDetectAltitude.toInt(),
                "Launch Detect AGL",
                0,
                100,
                configChanged
            )
        }
        var number by remember { mutableStateOf(0) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimensionResource(R.dimen.padding_medium)),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_medium)),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onCancelButtonClicked
            ) {
                Text(stringResource(R.string.cancel))
            }
            val locatorConfigMessageState = BluetoothManagerRepository.locatorConfigMessageState.collectAsState().value
            when (locatorConfigMessageState) {
                LocatorConfigMessageState.Sent -> {
                    if (updateableLocatorConfig == updatedLocatorConfig)
                        BluetoothManagerRepository.updateLocatorConfigMessageState(
                            LocatorConfigMessageState.AckUpdated
                        )
                    else {
                        configChangeAcknowldedgeWaitCount++
                        if (configChangeAcknowldedgeWaitCount >= 5) {
                            BluetoothManagerRepository.updateLocatorConfigMessageState(
                                LocatorConfigMessageState.NotAcknowledged
                            )
                        }
                    }
                }
                else -> {}
            }
            Button(
                modifier = Modifier.weight(1f),
                // the button is enabled when the user makes a selection
                enabled = configChanged.value,
                onClick = {
                    if (BluetoothManagerRepository.locatorConfigMessageState.value == LocatorConfigMessageState.Idle
                        || BluetoothManagerRepository.locatorConfigMessageState.value == LocatorConfigMessageState.AckUpdated
                        || BluetoothManagerRepository.locatorConfigMessageState.value == LocatorConfigMessageState.SendFailure
                        || BluetoothManagerRepository.locatorConfigMessageState.value == LocatorConfigMessageState.NotAcknowledged
                        ) {
                        BluetoothManagerRepository.updateLocatorConfigMessageState(LocatorConfigMessageState.SendRequested)
                        BluetoothManagerRepository.updateLocatorConfig(updateableLocatorConfig)
                    }
                }
            ) {
                Text( when (locatorConfigMessageState) {
                    LocatorConfigMessageState.Idle -> stringResource(R.string.update)
                    LocatorConfigMessageState.SendRequested -> stringResource(R.string.updating)
                    LocatorConfigMessageState.AckUpdated -> stringResource(R.string.updated)
                    LocatorConfigMessageState.SendFailure -> stringResource(R.string.update_failed)
                    LocatorConfigMessageState.NotAcknowledged -> stringResource(R.string.update_not_acknowledged)
                    else -> {""}
                } )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NumberInputWithUpDown(
    value: Int,
    onValueChange: (Int) -> Unit,
    minValue: Int = 0,
    maxValue: Int = Int.MAX_VALUE
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { onValueChange(value - 1) },
            shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
            modifier = Modifier.combinedClickable(
                onLongClick = {
                    onValueChange(value - 5)
                },
                onClick = {
                    onValueChange(value - 1)
                },
            ),
            enabled = value > minValue
        ) {
            Text(text = "-",
                style = typography.titleMedium,)
        }

        TextField(
            value = value.toString(),
            onValueChange = {
                val newValue = it.toIntOrNull() ?: value
                if (newValue in minValue..maxValue) {
                    onValueChange(newValue)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(50.dp)
        )

        Button(onClick = { onValueChange(value + 1) }, enabled = value < maxValue) {
            Text("+")
        }
    }
}

@Composable
fun numericEntryWithButtons(startValue: Int, labelText: String, minValue: Int = 0, maxValue: Int = Int.MAX_VALUE, configChanged: MutableState<Boolean>): Int {
    var value by remember { mutableStateOf(startValue.toString()) }
    val numericValue = value.toIntOrNull() ?: 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { newValue ->
                val newNumericValue = newValue.filter { it.isDigit() }.toIntOrNull() ?: 0
                value = if (newValue.isEmpty()) {
                    ""
                } else {
                    newNumericValue.coerceIn(minValue, maxValue).toString()
                }
                configChanged.value = true
            },
            label = { Text(labelText) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            //modifier = Modifier.weight(1f)
        )
        //Spacer(modifier = Modifier.width(8.dp))
        Column(
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(
                onClick = { value = (numericValue + 1).toString()
                    configChanged.value = true
                },
                enabled = numericValue < maxValue
            ) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Increment")
            }
            TextButton(
                onClick = { value = (numericValue - 1).toString()
                    configChanged.value = true
                },
                enabled = numericValue > minValue
            ) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Decrement")
            }
        }
    }
    return value.filter { it.isDigit() }.toIntOrNull() ?: 0
}