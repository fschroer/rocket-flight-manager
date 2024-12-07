package com.steampigeon.flightmanager.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.ui.theme.FlightManagerTheme

/**
 * Composable that displays map download options,
 * [onSelectionChanged] lambda that notifies the parent composable when a new value is selected,
 * [onCancelButtonClicked] lambda that cancels the order when user clicks cancel and
 * [onNextButtonClicked] lambda that triggers the navigation to next screen
 */
@Composable
fun LocatorSettingsScreen(
    onSelectionChanged: (String) -> Unit = {},
    onCancelButtonClicked: () -> Unit = {},
    onNextButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedValue by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        NumericEntryWithButtons()
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
                enabled = selectedValue.isNotEmpty(),
                onClick = onNextButtonClicked
            ) {
                Text(stringResource(R.string.next))
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
                onClick = { onValueChange(value - 1) },
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
fun NumericEntryWithButtons() {
    var value by remember { mutableStateOf("0") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { newValue ->
                value = if (newValue.isEmpty()) {
                    "0"
                } else {
                    newValue.filter { it.isDigit() }
                }
            },
            label = { Text("Launch Detect AGL") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        //Spacer(modifier = Modifier.width(8.dp))
        Column(
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = { value = (value.toIntOrNull() ?: 0 + 1).toString() }) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Increment")
            }
            TextButton(onClick = { value = (value.toIntOrNull() ?: 0 - 1).toString() }) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Decrement")
            }
        }
    }
}

@Preview
@Composable
fun LocatorSettingsScreenPreview() {
    FlightManagerTheme {
        LocatorSettingsScreen(
            modifier = Modifier.fillMaxHeight()
        )
    }
}