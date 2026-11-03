package com.steampigeon.flightmanager.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.simflight.Canopy
import com.steampigeon.flightmanager.data.simflight.DefaultRocket
import com.steampigeon.flightmanager.data.simflight.Drag
import com.steampigeon.flightmanager.data.simflight.MotorHit
import com.steampigeon.flightmanager.data.simflight.SimFlightStore
import com.steampigeon.flightmanager.data.simflight.SimMotor
import com.steampigeon.flightmanager.data.simflight.SimRocket
import com.steampigeon.flightmanager.data.simflight.ThrustCurveApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/** How long Start waits for the simulated bit before saying the locator refused. */
private const val SIM_START_CONFIRM_MS = 5_000L

/**
 * Describe a rocket and start a simulated flight that the locator computes from
 * it (locator ADR "An app-started simulated flight computed on the locator",
 * 0041, amendment 2026-11-03).
 *
 * Reached from the drawer under the same gate as Flight Profiles and Locator
 * Settings (heard and disarmed), because the locator starts one only while
 * disarmed.
 *
 * The screen is the rocket, top to bottom: the saved-rocket picker and the
 * airframe, drag (one Cd spread over the Mach grid, the eight points, or a fit
 * from an OpenRocket CSV export), the motor (thrustcurve.org search, the
 * motors already on the phone, or a .eng/.rse file), the launch and the day,
 * recovery (typed rates, each with a canopy calculator), and wind.  Rockets and
 * motors are kept on the phone (SimFlightStore) so it works with no signal.
 * Before anything is saved, it offers Frank's Tron Short 2.6 on an H90
 * (DefaultRocket).
 *
 * The locator's ranges are checked here too (SimRocket.problems), listed above
 * Start, and hold it: the locator refuses an out-of-range start silently.
 *
 * Start asks first, because a rocket on the rail being prepped passes the
 * locator's own checks as well as one on the bench.  It saves the rocket, then
 * waits for the simulated bit in telemetry, which is the only evidence the
 * locator accepted, and returns to the map once it arrives.  No bit within a
 * few seconds means the locator did not start, and the screen says so.
 *
 * Stop is always shown, and enabled while a simulation is running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatedFlightScreen(
    viewModel: RocketViewModel = viewModel(),
    service: BluetoothService?,
    onStarted: () -> Unit = {},
    onCancelButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SimFlightStore(context.applicationContext) }
    val rocketState by viewModel.rocketState.collectAsState()
    val simulated = rocketState.simulated

    // The default motor is cached like any other, so a saved copy of the
    // default rocket finds it again.
    var rockets by remember { mutableStateOf(store.rockets()) }
    var rocket by remember { mutableStateOf(store.lastRocket() ?: DefaultRocket.rocket) }
    var motors by remember {
        if (store.loadMotor(DefaultRocket.MOTOR_ID) == null)
            store.saveMotorWithId(DefaultRocket.MOTOR_ID, DefaultRocket.MOTOR_ENG)
        mutableStateOf(store.cachedMotors())
    }
    var motor by remember { mutableStateOf(store.loadMotor(rocket.motorId)) }
    fun pickMotor(m: SimMotor) { motor = m; rocket = rocket.copy(motorId = m.id); motors = store.cachedMotors() }

    val refused = remember { mutableStateMapOf<String, Boolean>() }
    var confirming by remember { mutableStateOf(false) }
    var waitingForStart by remember { mutableStateOf(false) }
    var notStarted by remember { mutableStateOf(false) }
    var cancelSent by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var canopyFor by remember { mutableStateOf<String?>(null) }   // "drogue" or "main"
    val currentOnStarted by rememberUpdatedState(onStarted)
    val editable = !simulated && !waitingForStart

    // Waiting for the locator to accept: the bit arriving is the success path,
    // the timeout the refusal.
    LaunchedEffect(waitingForStart, simulated) {
        if (!waitingForStart) return@LaunchedEffect
        if (simulated) {
            waitingForStart = false
            currentOnStarted()
            return@LaunchedEffect
        }
        delay(SIM_START_CONFIRM_MS)
        waitingForStart = false
        notStarted = true
    }
    LaunchedEffect(simulated) { if (!simulated) cancelSent = false }

    val csvFailed = stringResource(R.string.sim_flight_csv_failed)
    val motorFailed = stringResource(R.string.sim_flight_motor_file_failed)
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val cd = withContext(Dispatchers.IO) { readText(context, uri)?.let { Drag.fitOpenRocketCsv(it) } }
            if (cd == null) importMessage = csvFailed
            else { rocket = rocket.copy(cd = cd.toList()); importMessage = null }
        }
    }
    val motorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val m = withContext(Dispatchers.IO) {
                val text = readText(context, uri) ?: return@withContext null
                store.saveMotorWithId("file:" + (displayName(context, uri) ?: uri.lastPathSegment ?: "motor"), text)
            }
            if (m == null) importMessage = motorFailed else { pickMotor(m); importMessage = null }
        }
    }

    val problems = rocket.problems(motor)
    val fieldsValid = problems.isEmpty() && refused.values.none { it }

    Column(
        modifier = modifier.fillMaxHeight().padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.sim_flight_explanation), style = MaterialTheme.typography.bodyMedium)

            // ── Rocket ─────────────────────────────────────────────────────
            SimSection(R.string.sim_flight_section_rocket)
            if (rockets.isNotEmpty()) {
                SimPicker(
                    label = stringResource(R.string.sim_flight_saved_rockets),
                    selected = rockets.firstOrNull { it.name == rocket.name }?.name ?: "",
                    options = rockets.map { it.name },
                    enabled = editable,
                ) { name ->
                    rockets.firstOrNull { it.name == name }?.let {
                        rocket = it
                        motor = store.loadMotor(it.motorId)
                    }
                }
            }
            SimTextField(stringResource(R.string.sim_flight_name), rocket.name, editable) {
                rocket = rocket.copy(name = it)
            }
            SimIntField(R.string.sim_flight_dry_mass, rocket.dryMassG, 10..200_000, editable, refused) {
                rocket = rocket.copy(dryMassG = it)
            }
            SimDecimalField(stringResource(R.string.sim_flight_diameter), rocket.diameterMm, 5f, 500f, 1, editable, refused) {
                rocket = rocket.copy(diameterMm = it)
            }
            SimIntField(R.string.sim_flight_length, rocket.lengthMm, 100..5000, editable, refused) {
                rocket = rocket.copy(lengthMm = it)
            }
            SimDecimalField(stringResource(R.string.sim_flight_stability), rocket.stabilityCal, 0.5f, 10f, 1, editable, refused) {
                rocket = rocket.copy(stabilityCal = it)
            }
            Text(stringResource(R.string.sim_flight_stability_help), style = MaterialTheme.typography.bodySmall)

            // ── Drag ───────────────────────────────────────────────────────
            SimSection(R.string.sim_flight_section_drag)
            Text(stringResource(R.string.sim_flight_drag_help), style = MaterialTheme.typography.bodySmall)
            var typedCd by remember { mutableStateOf(rocket.cd.firstOrNull() ?: 0.45f) }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SimDecimalField(stringResource(R.string.sim_flight_cd_typed), typedCd, 0.05f, 3.0f, 3, editable, refused,
                    modifier = Modifier.weight(1f)) { typedCd = it }
                OutlinedButton(onClick = { rocket = rocket.copy(cd = Drag.spread(typedCd).toList()) }, enabled = editable) {
                    Text(stringResource(R.string.sim_flight_cd_spread))
                }
            }
            Drag.MACH_GRID.indices.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { i ->
                        SimDecimalField(
                            stringResource(R.string.sim_flight_cd_at_mach, formatMach(Drag.MACH_GRID[i])),
                            rocket.cd[i], 0.05f, 3.0f, 3, editable, refused, modifier = Modifier.weight(1f),
                        ) { v -> rocket = rocket.copy(cd = rocket.cd.toMutableList().also { it[i] = v }) }
                    }
                }
            }
            OutlinedButton(onClick = { csvLauncher.launch(arrayOf("text/*", "application/octet-stream")) }, enabled = editable) {
                Text(stringResource(R.string.sim_flight_cd_import_csv))
            }

            // ── Motor ──────────────────────────────────────────────────────
            SimSection(R.string.sim_flight_section_motor)
            Text(
                motor?.let { motorSummary(it) } ?: stringResource(R.string.sim_flight_no_motor),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (motors.isNotEmpty()) {
                SimPicker(
                    label = stringResource(R.string.sim_flight_cached_motors),
                    selected = motor?.let { "${it.manufacturer} ${it.designation}" } ?: "",
                    options = motors.map { "${it.manufacturer} ${it.designation}" },
                    enabled = editable,
                ) { label -> motors.firstOrNull { "${it.manufacturer} ${it.designation}" == label }?.let { pickMotor(it) } }
            }
            MotorSearch(editable, onPicked = { hit ->
                scope.launch {
                    val text = ThrustCurveApi.downloadEng(hit.motorId)
                    val m = text?.let { withContext(Dispatchers.IO) { store.saveMotorWithId(hit.motorId, it) } }
                    if (m == null) importMessage = motorFailed else { pickMotor(m); importMessage = null }
                }
            })
            OutlinedButton(onClick = { motorLauncher.launch(arrayOf("*/*")) }, enabled = editable) {
                Text(stringResource(R.string.sim_flight_motor_import))
            }
            importMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            // ── Launch and day ─────────────────────────────────────────────
            SimSection(R.string.sim_flight_section_launch)
            SimIntField(R.string.sim_flight_rail_length, rocket.railLengthCm, 0..2000, editable, refused) {
                rocket = rocket.copy(railLengthCm = it)
            }
            SimDecimalField(stringResource(R.string.sim_flight_rail_angle), rocket.railAngleTenths / 10f, 0f, 20f, 1, editable, refused) {
                rocket = rocket.copy(railAngleTenths = (it * 10f).roundToInt())
            }
            SimIntField(R.string.sim_flight_launch_direction, rocket.launchAzDeg, 0..359, editable, refused) {
                rocket = rocket.copy(launchAzDeg = it)
            }
            val tempLabel = stringResource(R.string.sim_flight_temperature)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = rocket.tempC == null,
                    onCheckedChange = { useBaro ->
                        // The field leaves the screen, so its refusal must too.
                        if (useBaro) refused.remove(tempLabel)
                        rocket = rocket.copy(tempC = if (useBaro) null else 15)
                    },
                    enabled = editable,
                )
                Text(stringResource(R.string.sim_flight_temp_from_baro), style = MaterialTheme.typography.bodyMedium)
            }
            rocket.tempC?.let { t ->
                SimDecimalField(tempLabel, t.toFloat(), -40f, 50f, 0, editable, refused) {
                    rocket = rocket.copy(tempC = it.roundToInt())
                }
            }

            // ── Recovery ───────────────────────────────────────────────────
            SimSection(R.string.sim_flight_section_recovery)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    SimIntField(R.string.sim_flight_drogue, rocket.drogueMps, 5..60, editable, refused) {
                        rocket = rocket.copy(drogueMps = it)
                    }
                }
                OutlinedButton(onClick = { canopyFor = "drogue" }, enabled = editable) {
                    Text(stringResource(R.string.sim_flight_canopy_calculate))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    SimIntField(R.string.sim_flight_main, rocket.mainMps, 2..15, editable, refused) {
                        rocket = rocket.copy(mainMps = it)
                    }
                }
                OutlinedButton(onClick = { canopyFor = "main" }, enabled = editable) {
                    Text(stringResource(R.string.sim_flight_canopy_calculate))
                }
            }

            // ── Wind ───────────────────────────────────────────────────────
            SimSection(R.string.sim_flight_section_wind)
            SimIntField(R.string.sim_flight_wind, rocket.windMps, 0..20, editable, refused) {
                rocket = rocket.copy(windMps = it)
            }
            SimIntField(R.string.sim_flight_wind_from, rocket.windFromDeg, 0..359, editable, refused) {
                rocket = rocket.copy(windFromDeg = it)
            }

            HorizontalDivider()
            problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(
                onClick = { store.saveRocket(rocket); rockets = store.rockets() },
                enabled = rocket.name.isNotBlank() && editable,
            ) { Text(stringResource(R.string.sim_flight_save_rocket)) }
            Button(
                onClick = { notStarted = false; confirming = true },
                enabled = fieldsValid && !simulated && !waitingForStart,
            ) {
                Text(
                    if (waitingForStart) stringResource(R.string.sim_flight_starting)
                    else stringResource(R.string.sim_flight_start)
                )
            }
            if (notStarted) {
                Text(
                    stringResource(R.string.sim_flight_not_started),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Error-colored like the deployment-test stop and the map's disarm:
            // the control that ends something.  Re-sends on every press; it clears
            // when the simulated bit does, the only evidence it was heard.
            Button(
                onClick = {
                    service?.cancelSimulatedFlight()
                    cancelSent = true
                },
                modifier = Modifier.height(48.dp),
                enabled = simulated,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            ) {
                Text(
                    if (cancelSent) stringResource(R.string.sim_flight_canceling)
                    else stringResource(R.string.sim_flight_cancel)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
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

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.sim_flight_confirm_title)) },
            text = { Text(stringResource(R.string.sim_flight_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val m = motor
                    if (rocket.name.isNotBlank()) { store.saveRocket(rocket); rockets = store.rockets() }
                    val here = viewModel.trackerLocation.value
                    val sent = m != null && service?.startSimulatedFlight(
                        rocket, m, Random.nextInt(0x10000), here?.latitude, here?.longitude) == true
                    if (sent) waitingForStart = true else notStarted = true
                }) { Text(stringResource(R.string.sim_flight_start)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    canopyFor?.let { which ->
        CanopyDialog(
            burnoutMassKg = rocket.burnoutMassKg(motor),
            tempC = (rocket.tempC ?: 15).toFloat(),
            onDismiss = { canopyFor = null },
            onUse = { rate ->
                rocket = if (which == "drogue") rocket.copy(drogueMps = rate) else rocket.copy(mainMps = rate)
                canopyFor = null
            },
        )
    }
}

@Composable
private fun SimSection(titleRes: Int) {
    Spacer(Modifier.height(4.dp))
    Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
}

/** A whole-number field: the settings screens' own, with their range refusal. */
@Composable
private fun SimIntField(
    labelRes: Int,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    refused: MutableMap<String, Boolean>,
    onUpdate: (Int) -> Unit,
) {
    val label = stringResource(labelRes)
    ConfigurationItemNumeric(
        configItemName = label,
        initialConfigValue = value,
        minValue = range.first,
        maxValue = range.last,
        configMessageState = if (enabled) LocatorMessageState.Idle else LocatorMessageState.SendRequested,
        onRefusedChange = { refused[label] = it },
        onConfigUpdate = onUpdate,
    )
}

/**
 * A number with a decimal point or a sign, which ConfigurationItemNumeric does
 * not take: Cd, diameter, rail angle, temperature.  Like it, an entry out of
 * range is shown and refused, never clamped (ADR-0034 decision 3).
 */
@Composable
private fun SimDecimalField(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    decimals: Int,
    enabled: Boolean,
    refused: MutableMap<String, Boolean>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onValue: (Float) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    fun fmt(v: Float) = String.format(Locale.US, "%.${decimals}f", v)
    var text by remember { mutableStateOf(fmt(value)) }
    // Follow the value when it changes for another reason: a saved rocket
    // loaded, Spread, a CSV fit.  Text already reading as this value stays.
    LaunchedEffect(value) { if (text.toFloatOrNull() != value) text = fmt(value) }
    val parsed = text.toFloatOrNull()
    val bad = parsed == null || parsed < min || parsed > max
    LaunchedEffect(bad) { refused[label] = bad }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() || it == '.' || it == '-' }.take(9)
            text.toFloatOrNull()?.let { if (it in min..max) onValue(it) }
        },
        modifier = modifier,
        enabled = enabled,
        label = { Text(label) },
        isError = bad,
        supportingText = if (bad) {
            { Text(stringResource(R.string.sim_flight_range_decimal, fmt(min), fmt(max))) }
        } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (min < 0f) KeyboardType.Text else KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
    )
}

@Composable
private fun SimTextField(label: String, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.take(40)) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimPicker(label: String, selected: String, options: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        OutlinedTextField(
            readOnly = true,
            value = selected,
            onValueChange = { },
            label = { Text(label) },
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { expanded = false; onPick(option) },
                )
            }
        }
    }
}

/** thrustcurve.org: a query, Search, and the results to tap. */
@Composable
private fun MotorSearch(enabled: Boolean, onPicked: (MotorHit) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val hits = remember { mutableStateListOf<MotorHit>() }
    val focusManager = LocalFocusManager.current
    fun search() {
        focusManager.clearFocus()
        searching = true
        scope.launch {
            val r = ThrustCurveApi.search(query)
            hits.clear(); hits.addAll(r)
            searching = false; searched = true
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(20) },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            label = { Text(stringResource(R.string.sim_flight_motor_search)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        OutlinedButton(onClick = { search() }, enabled = enabled && query.isNotBlank() && !searching) {
            Text(stringResource(R.string.sim_flight_motor_search_button))
        }
    }
    if (searching) Text(stringResource(R.string.sim_flight_motor_searching), style = MaterialTheme.typography.bodySmall)
    else if (searched && hits.isEmpty())
        Text(stringResource(R.string.sim_flight_motor_none_found), style = MaterialTheme.typography.bodySmall)
    hits.forEach { h ->
        Text(
            String.format(Locale.US, "%s %s  ·  %.0f mm  ·  %.0f N s  ·  %.1f s",
                h.manufacturer, h.designation, h.diameterMm, h.totalImpulseNs, h.burnTimeS),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { hits.clear(); searched = false; onPicked(h) }
                .padding(vertical = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Descent rate from a canopy's diameter and Cd at the rocket's burnout mass. */
@Composable
private fun CanopyDialog(burnoutMassKg: Float, tempC: Float, onDismiss: () -> Unit, onUse: (Int) -> Unit) {
    var diameterCm by remember { mutableStateOf("91") }
    var cdText by remember { mutableStateOf("0.75") }
    val d = diameterCm.toFloatOrNull()
    val cd = cdText.toFloatOrNull()
    val rate = if (d != null && cd != null) Canopy.descentRate(burnoutMassKg, d / 100f, cd, tempC) else 0f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sim_flight_canopy_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sim_flight_canopy_mass, String.format(Locale.US, "%.0f", burnoutMassKg * 1000f)))
                OutlinedTextField(
                    value = diameterCm, onValueChange = { diameterCm = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                    label = { Text(stringResource(R.string.sim_flight_canopy_diameter)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = cdText, onValueChange = { cdText = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                    label = { Text(stringResource(R.string.sim_flight_canopy_cd)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    if (rate > 0f) stringResource(R.string.sim_flight_canopy_result, String.format(Locale.US, "%.1f", rate))
                    else stringResource(R.string.sim_flight_canopy_incomplete),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onUse(rate.roundToInt()) }, enabled = rate > 0f) {
                Text(stringResource(R.string.sim_flight_canopy_use))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun motorSummary(m: SimMotor) = String.format(
    Locale.US, "%s %s: class %c, %.1f N s, %.2f s burn, %.0f N peak, %.0f g loaded, %.0f mm",
    m.manufacturer, m.designation, m.impulseClass, m.impulseNs, m.burnS, m.peakN, m.loadedKg * 1000f, m.diameterMm,
)

private fun formatMach(m: Float) = if (m == m.roundToInt().toFloat()) m.roundToInt().toString() else m.toString()

private fun readText(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
} catch (e: Exception) { null }

private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
    }
} catch (e: Exception) { null }
