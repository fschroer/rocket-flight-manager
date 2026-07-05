package com.steampigeon.flightmanager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.steampigeon.flightmanager.R
import kotlinx.coroutines.delay

/**
 * Password prompt for connecting to a locator the app is not yet authorized for.
 *
 * Raised either on first contact with an unknown locator (passive) or by a receiver
 * channel change onto an unknown one ([isChannelChange] = true, where dismissing
 * reverts the channel). A wrong password sets [error] and keeps the dialog open to
 * retry; a correct one closes it. The field has a standard show/hide (eye) toggle.
 */
@Composable
fun LocatorPasswordDialog(
    deviceName: String,
    isChannelChange: Boolean,
    error: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit = {},
) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    // Briefly reveal the last-typed character (as native password fields do) before
    // masking it. revealLast is set on each added character and cleared by the timer
    // below; keystroke restarts that timer so fast typing keeps only the newest char.
    var revealLast by remember { mutableStateOf(false) }
    var keystroke by remember { mutableIntStateOf(0) }
    LaunchedEffect(keystroke) {
        if (revealLast) { delay(1000); revealLast = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.locator_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.locator_password_prompt, deviceName.ifEmpty { "?" }))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { new ->
                        val trimmed = new.take(15)
                        revealLast = trimmed.length > password.length   // reveal on add, not on delete
                        password = trimmed
                        keystroke++
                        if (error) onEdit()
                    },
                    singleLine = true,
                    isError = error,
                    label = { Text(stringResource(R.string.password)) },
                    visualTransformation =
                        if (visible) VisualTransformation.None
                        else RevealLastCharTransformation(revealLast),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (visible) R.string.hide_password else R.string.show_password
                                ),
                            )
                        }
                    },
                )
                if (error) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.incorrect_password),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }) {
                Text(stringResource(R.string.connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (isChannelChange) R.string.cancel else R.string.dismiss))
            }
        },
    )
}

/**
 * Masks every character except, when [revealLast] is true, the final one — the
 * common "briefly show the last-typed letter" password behavior. The transform is
 * 1-for-1 (one glyph per source char), so [OffsetMapping.Identity] is correct.
 */
private class RevealLastCharTransformation(
    private val revealLast: Boolean,
    private val mask: Char = '•',
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val out = buildString {
            text.forEachIndexed { i, c -> append(if (revealLast && i == text.lastIndex) c else mask) }
        }
        return TransformedText(AnnotatedString(out), OffsetMapping.Identity)
    }
}
