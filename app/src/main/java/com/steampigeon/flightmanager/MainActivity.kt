package com.steampigeon.flightmanager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.steampigeon.flightmanager.ui.theme.FlightManagerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlightManagerTheme {
                //Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RocketApp(
                        //modifier = Modifier.padding(innerPadding)
                    )
                //}
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, BluetoothService::class.java))
    }
}