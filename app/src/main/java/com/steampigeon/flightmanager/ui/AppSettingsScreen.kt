package com.steampigeon.flightmanager.ui

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.R
import kotlinx.coroutines.launch

private const val TAG = "AppSettings"

/**
 * Composable that displays map download options,
 * [onCancelButtonClicked] lambda that cancels the order when user clicks cancel and
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    viewModel: RocketViewModel = viewModel(),
    textToSpeech: TextToSpeech?,
    onCancelButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
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
            val voiceEnabled = viewModel.voiceEnabled.collectAsState().value
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = voiceEnabled,
                    onCheckedChange = { viewModel.updateVoiceEnabled(it) }
                )
                Text("Enable Speech")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Voice Name:")
            val voices = textToSpeech?.voices
            val voiceName = viewModel.voiceName.collectAsState().value
            val options = mutableListOf<String>()
            voices?.forEach{ voice ->
                if (voice.locale.language == "en")
                    options.add(voice.name)}
            options.sort()
            var expanded by remember { mutableStateOf(false) }
            var selectedOptionText by remember { mutableStateOf(voiceName) }
            val coroutineScope = rememberCoroutineScope()
            val listState = rememberLazyListState()
            LaunchedEffect(expanded) {
                if (expanded) {
                    val index = options.indexOf(selectedOptionText)
                    if (index >= 0) {
                        coroutineScope.launch {
                            listState.scrollToItem(index)
                        }
                    }
                }
            }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                TextField(
                    readOnly = true,
                    value = selectedOptionText,
                    onValueChange = { },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(),
                    colors = ExposedDropdownMenuDefaults.textFieldColors()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                selectedOptionText = option
                                viewModel.updateVoiceName(option)
                                expanded = false
                            },
                            modifier = modifier
                                .background(if (selectedOptionText == option) Color.Gray else Color.Transparent)
                        )
                    }
                }
            }
            LaunchedEffect(voiceEnabled, voiceName) {
                viewModel.updateVoiceEnabled(voiceEnabled)
                viewModel.updateVoiceName(voiceName)
                viewModel.saveUserPreferences()
            }
            // Capture initial and updated app configuration data.
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
                Text(stringResource(R.string.return_to_main))
            }
        }
    }
}