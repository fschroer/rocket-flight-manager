package com.steampigeon.flightmanager.ui

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
import com.steampigeon.flightmanager.data.DeployMode

private var configChanged = false
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

    val rocketData = locatorData.getLocatorData(LocalContext.current, viewModel)
    var deployMode by remember {mutableStateOf(DeployMode.kDroguePrimaryDrogueBackup)}
    var launchDetectAltitude by remember { mutableIntStateOf(0) }
    var droguePrimaryDeployDelay by remember { mutableIntStateOf(0) }
    var drogueBackupDeployDelay by remember { mutableIntStateOf(0) }
    var mainPrimaryDeployAltitude by remember { mutableIntStateOf(0) }
    var mainBackupDeployAltitude by remember { mutableIntStateOf(0) }
    var deploySignalDuration by remember { mutableIntStateOf(0) }
    var deviceName by remember { mutableStateOf("") }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        LaunchedEffect(rocketData) {
            deployMode = rocketData.deployMode
            launchDetectAltitude = rocketData.launchDetectAltitude.toInt()
            droguePrimaryDeployDelay = rocketData.droguePrimaryDeployDelay.toInt()
            drogueBackupDeployDelay = rocketData.drogueBackupDeployDelay.toInt()
            mainPrimaryDeployAltitude = rocketData.mainPrimaryDeployAltitude.toInt()
            mainBackupDeployAltitude = rocketData.mainBackupDeployAltitude.toInt()
            deploySignalDuration = rocketData.deploySignalDuration.toInt()
            deviceName = rocketData.deviceName
        }
        numericEntryWithButtons(launchDetectAltitude, "Launch Detect AGL", 0, 100)
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
            Button(
                modifier = Modifier.weight(1f),
                // the button is enabled when the user makes a selection
                enabled = configChanged,
                onClick = onNextButtonClicked
            ) {
                Text(stringResource(R.string.update))
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
fun numericEntryWithButtons(startValue: Int, labelText: String, minValue: Int = 0, maxValue: Int = Int.MAX_VALUE): Int {
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
                configChanged = true
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
                onClick = {value = (numericValue + 1).toString()},
                enabled = numericValue < maxValue
            ) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Increment")
            }
            TextButton(
                onClick = {value = (numericValue - 1).toString()},
                enabled = numericValue > minValue
            ) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Decrement")
            }
        }
    }
    return value.filter { it.isDigit() }.toIntOrNull() ?: 0
}