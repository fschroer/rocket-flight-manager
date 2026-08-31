package com.steampigeon.flightmanager.ui

// ---------------------------------------------------------------------------
// App Flight Logs. Lists the logs the app recorded for itself — one per detected
// launch — and lets one be read on the phone, sent to a PC, or deleted.
//
// What these are NOT: the locator's flight archive, which is downloaded on the
// Flight Profiles screen and is the authority on what the rocket did. These record
// what the PHONE saw — the same 1 Hz frames plus the receiver's RSSI, SNR and noise
// floor for each one, and what the app announced about them. That information exists
// nowhere else once the app is closed, and during a flight nobody can watch it.
// ---------------------------------------------------------------------------

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.FlightLogContents
import com.steampigeon.flightmanager.data.FlightLogFile
import com.steampigeon.flightmanager.data.FlightLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppFlightLogsScreen(
    viewModel: RocketViewModel,
    onCancelButtonClicked: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val logs by viewModel.flightLogs.collectAsState()
    val recordingName by viewModel.flightLogRecordingName.collectAsState()

    // Re-listed on entry rather than relied on to be current: the recorder writes
    // from the packet collector while this screen is nowhere near composition, so
    // arriving here after a flight must show the file that flight produced.
    LaunchedEffect(Unit) { viewModel.refreshFlightLogs() }

    var viewing by remember { mutableStateOf<FlightLogFile?>(null) }
    var deleting by remember { mutableStateOf<FlightLogFile?>(null) }
    // A share that cannot build a URI must say so.  Silently doing nothing is the
    // one outcome a user reads as the app being broken rather than the file being
    // missing, and it is the same tap either way.
    var shareFailed by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        if (recordingName != null) {
            Text(
                text = stringResource(R.string.flight_log_recording),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(dimensionResource(R.dimen.padding_medium)),
            )
        }
        if (logs.isEmpty()) {
            // weight(1f) on BOTH branches, so Cancel sits at the bottom either way.
            // Without it the empty state packed everything to the top and the button
            // moved down the screen the moment a first log existed — the same control
            // in two places depending on state nobody chose.
            Text(
                text = stringResource(R.string.flight_log_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(dimensionResource(R.dimen.padding_medium)),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    dimensionResource(R.dimen.padding_medium)
                ),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_small)),
            ) {
                items(logs, key = { it.name }) { log ->
                    FlightLogRow(
                        log = log,
                        store = viewModel.flightLogStore,
                        stillOpen = log.name == recordingName,
                        onView = { viewing = log },
                        onShareFailed = { shareFailed = true },
                        onDelete = { deleting = log },
                    )
                }
            }
        }
        OutlinedButton(
            onClick = onCancelButtonClicked,
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimensionResource(R.dimen.padding_medium)),
        ) {
            Text(stringResource(R.string.cancel))
        }
    }

    viewing?.let { log ->
        // produceState on the IO dispatcher: reading up to MAX_VIEW_ROWS lines is
        // not composition's work, and doing it inline froze the tap that opened it.
        // Null until the read lands, which the viewer shows as an empty list for the
        // one frame it takes.
        val contents by produceState<FlightLogContents?>(null, log.name) {
            value = withContext(Dispatchers.IO) { viewModel.readFlightLog(log.name) }
        }
        FlightLogViewer(
            log = log,
            contents = contents,
            onDismiss = { viewing = null },
        )
    }

    if (shareFailed) {
        AlertDialog(
            onDismissRequest = { shareFailed = false },
            text = { Text(stringResource(R.string.flight_log_share_failed)) },
            confirmButton = {
                TextButton(onClick = { shareFailed = false }) {
                    Text(stringResource(R.string.flight_log_close))
                }
            },
        )
    }

    deleting?.let { log ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.flight_log_delete_title)) },
            text = { Text(stringResource(R.string.flight_log_delete_body, log.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFlightLog(log.name)
                    deleting = null
                }) { Text(stringResource(R.string.flight_log_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun FlightLogRow(
    log: FlightLogFile,
    store: FlightLogStore,
    stillOpen: Boolean,
    onView: () -> Unit,
    onShareFailed: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(dimensionResource(R.dimen.padding_medium))) {
            Text(log.locatorName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.flight_log_row_summary,
                    log.capturedAt,
                    stringResource(
                        R.string.flight_log_size,
                        // Rounded UP: a log that exists must never read as 0 kB, which
                        // is the one number that would make it look like a failed
                        // recording rather than a short one.
                        ((log.sizeBytes + 1023) / 1024).coerceAtLeast(1).toString(),
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (stillOpen) {
                Text(
                    stringResource(R.string.flight_log_open_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_small)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onView) { Text(stringResource(R.string.flight_log_view)) }
                TextButton(onClick = {
                    // The Android share sheet IS the export mechanism: it reaches a
                    // paired laptop over Bluetooth, Quick Share, Drive or mail, and
                    // "save a copy" through the same sheet writes into Downloads
                    // where a USB cable finds it. None of that needs anything
                    // installed on the PC, which a serial or SPP protocol would.
                    val uri = store.uriFor(log.name)
                    if (uri == null) onShareFailed() else {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, log.name)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(
                                send, context.getString(R.string.flight_log_share_title)
                            )
                        )
                    }
                }) { Text(stringResource(R.string.flight_log_share)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.flight_log_delete)) }
            }
        }
    }
}

/**
 * Reads a log on the phone.
 *
 * Monospaced and horizontally scrolled, showing the CSV as it actually is rather
 * than as a table: the file is the deliverable, and a prettified view would hide
 * exactly the formatting problems worth catching before the log reaches a PC.
 */
@Composable
private fun FlightLogViewer(
    log: FlightLogFile,
    contents: FlightLogContents?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(log.name, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                if (contents != null && contents.truncated) {
                    Text(
                        stringResource(
                            R.string.flight_log_truncated,
                            contents.rows.size,
                            contents.totalRows,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                LazyColumn {
                    items(contents?.rows ?: emptyList()) { row ->
                        Text(
                            text = row,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.flight_log_close)) }
        },
    )
}
