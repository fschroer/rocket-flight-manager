package com.steampigeon.flightmanager.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.LocatorConfigMessageState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "LocatorSettings"
//private val locatorData = LocatorData()

/**
 * Composable that displays map download options,
 * [onSelectionChanged] lambda that notifies the parent composable when a new value is selected,
 * [onCancelButtonClicked] lambda that cancels the order when user clicks cancel and
 * [onNextButtonClicked] lambda that triggers the navigation to next screen
 */
@Composable
fun LocatorSettingsScreen(
    viewModel: RocketViewModel = viewModel(),
    service: BluetoothService?,
    onSelectionChanged: (String) -> Unit = {},
    onCancelButtonClicked: () -> Unit = {},
    onNextButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var configChanged = remember {mutableStateOf(false)}
    var stagedLocatorConfig by remember {mutableStateOf(viewModel.remoteLocatorConfig.value)}
    var configChangeAcknowldedgeWaitCount by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Capture initial and updated locator configuration data.
        // Used for configuration screen and confirming locator update acknowledgement.
        //locatorData.getLocatorData(LocalContext.current, viewModel)
        val remoteLocatorConfig = viewModel.remoteLocatorConfig.collectAsState()
        LongPressCounter()
        var counter by remember { mutableIntStateOf(0) }
        RepeatingButton(onClick = { counter++ })
        Text(
            text = "Count: $counter"
        )
        LaunchDetectAltitude(stagedLocatorConfig, 0, 100, configChanged) { newConfig -> stagedLocatorConfig = newConfig}
        //numericEntryWithButtons(stagedLocatorConfig.launchDetectAltitude.toInt(), "Launch Detect AGL", 0, 100, configChanged)
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
                    if (remoteLocatorConfig.value == stagedLocatorConfig)
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
                        service?.changeLocatorConfig(stagedLocatorConfig)
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
fun LaunchDetectAltitude(locatorConfig: LocatorConfig, minValue: Int = 0, maxValue: Int = Int.MAX_VALUE, configChanged: MutableState<Boolean>, onConfigUpdate: (LocatorConfig) -> Unit) {

    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val viewConfiguration = LocalViewConfiguration.current

    LaunchedEffect(interactionSource) {
        var isLongClick = false

        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    if (isLongClick)
                        onConfigUpdate(locatorConfig.copy(launchDetectAltitude = locatorConfig.launchDetectAltitude + 1))
                    isLongClick = false
                    delay(viewConfiguration.longPressTimeoutMillis)
                    isLongClick = true
                    //Toast.makeText(context, "Long click", Toast.LENGTH_SHORT).show()
                }

                is PressInteraction.Release -> {
                    if (isLongClick.not()) {
                        Toast.makeText(context, "click", Toast.LENGTH_SHORT).show()
                    }

                }

            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        OutlinedTextField(
            value = if (locatorConfig.launchDetectAltitude == 0) {
                ""
                } else {
                    locatorConfig.launchDetectAltitude.toString()
            },
            onValueChange = { newValue ->
                onConfigUpdate(locatorConfig.copy(launchDetectAltitude = (newValue.filter { it.isDigit() }.toIntOrNull() ?: 0).coerceIn(minValue, maxValue)))
                configChanged.value = true
            },
            label = { Text(stringResource(R.string.launch_detect_altitude)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            //modifier = Modifier.weight(1f)
        )
        //Spacer(modifier = Modifier.width(8.dp))
        Column(
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(
                onClick = { onConfigUpdate(locatorConfig.copy(launchDetectAltitude = locatorConfig.launchDetectAltitude + 1))
                    configChanged.value = true
                },
                interactionSource = interactionSource,
                enabled = locatorConfig.launchDetectAltitude < maxValue
            ) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Increment")
            }
            TextButton(
                onClick = { onConfigUpdate(locatorConfig.copy(launchDetectAltitude = locatorConfig.launchDetectAltitude - 1))
                    configChanged.value = true
                },
                interactionSource = interactionSource,
                enabled = locatorConfig.launchDetectAltitude > minValue
            ) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Decrement")
            }
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

@Composable
fun RepeatingButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    maxDelayMillis: Long = 500,
    minDelayMillis: Long = 100,
    delayDecayFactor: Float = .25f,
) {

    val currentClickListener by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()

    Button(
        modifier = modifier.pointerInput(interactionSource, enabled) {
            scope.launch {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    val heldButtonJob = launch {
                        var currentDelayMillis = maxDelayMillis
                        while (enabled && down.pressed) {
                            currentClickListener()
                            delay(currentDelayMillis)
                            val nextDelayMillis =
                                currentDelayMillis - (currentDelayMillis * delayDecayFactor)
                            currentDelayMillis =
                                nextDelayMillis.toLong().coerceAtLeast(minDelayMillis)
                        }
                    }

                    waitForUpOrCancellation()
                    heldButtonJob.cancel()
                }
            }
        },
        onClick = {},
        enabled = enabled,
        interactionSource = interactionSource
    ) {
        Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Increment")
    }
}

@Composable
fun LongPressCounter(
    maxDelayMillis: Long = 500,
    minDelayMillis: Long = 100,
    delayDecayFactor: Float = .25f,
    ) {
    var counter by remember { mutableStateOf(0) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.padding(16.dp)) {
        Button(
            onClick = { },
            interactionSource = interactionSource
        ) {
            Text("Hold to Increment")
        }

        Text(text = "Counter: $counter")

        // Increment counter while button is pressed
        LaunchedEffect(isPressed) {
            var currentDelayMillis = maxDelayMillis
            while (isPressed) {
                counter++
                delay(currentDelayMillis)
                val nextDelayMillis =
                    currentDelayMillis - (currentDelayMillis * delayDecayFactor)
                currentDelayMillis =
                    nextDelayMillis.toLong().coerceAtLeast(minDelayMillis)
            }
        }
    }
}