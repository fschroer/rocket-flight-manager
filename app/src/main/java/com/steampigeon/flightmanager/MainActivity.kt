package com.steampigeon.flightmanager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.steampigeon.flightmanager.ui.theme.FlightManagerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //Start Bluetooth data handler service
        setContent {
            FlightManagerTheme {
                RocketApp()
            }
        }
        startForegroundService(Intent(this, BluetoothService()::class.java))
        //Thread.sleep(1000)
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPostResume() {
        super.onPostResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations)
            stopService(Intent(this, BluetoothService::class.java))
    }
}