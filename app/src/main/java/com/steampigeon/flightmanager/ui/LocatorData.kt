package com.steampigeon.flightmanager.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.data.RocketUiState

class LocatorData {
    @Composable
    fun getLocatorData(context: Context, viewModel: RocketViewModel): RocketUiState {
        val serviceData by viewModel.uiState.collectAsState()
        var service: BluetoothService? by remember { mutableStateOf(null) }
        val connection = remember {
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = (binder as BluetoothService.LocalBinder).getService()
                    viewModel.collectLocatorData(service!!)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }
            }
        }
        DisposableEffect(Unit) {
            val intent = Intent(context, BluetoothService::class.java)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

            onDispose {
                context.unbindService(connection)
            }
        }
        if (service != null) {
            viewModel.collectLocatorData(service!!)
        }
        return serviceData
    }
}