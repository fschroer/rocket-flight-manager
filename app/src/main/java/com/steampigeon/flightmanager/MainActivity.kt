package com.steampigeon.flightmanager

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import org.maplibre.android.MapLibre
import com.steampigeon.flightmanager.ui.RocketViewModel
import com.steampigeon.flightmanager.ui.theme.FlightManagerTheme

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var viewModel: RocketViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before any MapLibre view/offline API is touched (FlightMapScreen's map).
        MapLibre.getInstance(applicationContext)
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[RocketViewModel::class.java]
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        setContent {
            FlightManagerTheme {
                RocketApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onResume() {
        super.onResume()

        val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        // SENSOR_DELAY_UI (~60 ms), not SENSOR_DELAY_GAME (~20 ms). GAME is a rate
        // for something being aimed; what consumes this is a compass rose and the
        // AR overlay's heading, both of which are eased on the way out
        // (easeAngle) and neither of which can show 50 distinct headings a second.
        // The samples are not free: each one runs the rotation-matrix math on the
        // main thread and writes four StateFlows that FlightMapScreen collects, so
        // the rate set here is also a recomposition rate for the whole map screen.
        sensorManager.registerListener(
            this,
            rotationVector,
            SensorManager.SENSOR_DELAY_UI
        )
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            // Forward fused orientation data to ViewModel
            viewModel.updateOrientation(event.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onPostResume() {
        super.onPostResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}