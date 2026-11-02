package com.steampigeon.flightmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.data.FirebaseCloud
import com.steampigeon.flightmanager.data.FullRecordHub
import kotlinx.coroutines.launch

/**
 * The account full records are uploaded to (locator ADR "The radio download
 * becomes the primary data path", decision 8): Google or email and password.
 * Says plainly when this build has no Firebase configuration, rather than
 * offering a sign-in that cannot work.
 */
@Composable
fun CloudAccountSection() {
    val account by FirebaseCloud.account.collectAsState()
    val error by FirebaseCloud.lastError.collectAsState()
    val finished by FullRecordHub.finished.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(stringResource(R.string.cloud_title))
        if (!FirebaseCloud.available) {
            Text(stringResource(R.string.cloud_not_configured), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val signedIn = account
        if (signedIn != null) {
            val waiting = finished.count { !it.uploadedBin || !it.uploadedCsv }
            Text(stringResource(R.string.cloud_signed_in, signedIn), style = MaterialTheme.typography.bodySmall)
            Text(
                if (waiting == 0) stringResource(R.string.cloud_all_uploaded, finished.size)
                else stringResource(R.string.cloud_waiting_upload, waiting),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { FirebaseCloud.signOut() }) { Text(stringResource(R.string.cloud_sign_out)) }
            return@Column
        }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        TextField(value = email, onValueChange = { email = it }, singleLine = true,
            label = { Text(stringResource(R.string.cloud_email)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        TextField(value = password, onValueChange = { password = it }, singleLine = true,
            label = { Text(stringResource(R.string.cloud_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = {
                busy = true
                scope.launch { FirebaseCloud.signInWithEmail(email.trim(), password, create = false); busy = false }
            }) { Text(stringResource(R.string.cloud_sign_in)) }
            OutlinedButton(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = {
                busy = true
                scope.launch { FirebaseCloud.signInWithEmail(email.trim(), password, create = true); busy = false }
            }) { Text(stringResource(R.string.cloud_create_account)) }
        }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch { FirebaseCloud.signInWithGoogle(context); busy = false }
        }) { Text(stringResource(R.string.cloud_sign_in_google)) }
        error?.let { Text(stringResource(R.string.cloud_error, it), style = MaterialTheme.typography.bodySmall) }
    }
}
