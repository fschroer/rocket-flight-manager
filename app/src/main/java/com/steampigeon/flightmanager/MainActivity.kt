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

/**
 * Sampling period for the magnetometer, in microseconds. It is registered only to
 * hear its accuracy, so this is set as slow as is reasonable to ask for rather
 * than at any rate the readings would need.
 */
private const val MAGNETOMETER_SAMPLE_PERIOD_US = 1_000_000

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

        // Registered for its accuracy callback ONLY — the values are discarded in
        // onSensorChanged. Which sensor actually reports calibration state is a
        // property of the device: the magnetometer is the live one on a Pixel 9 Pro
        // XL and silent on a Moto G 5S, where the rotation vector reports instead.
        // Both are consumed (see RocketViewModel.recomputeCompassAccuracy) because
        // betting on either alone leaves the warning unreachable on the other half
        // of the hardware.
        //
        // One second is REQUESTED, and is not what arrives. samplingPeriodUs is a
        // hint, and the rotation vector above is registered at SENSOR_DELAY_UI —
        // on a fused implementation that sensor is built on this one, so the
        // magnetometer runs at the rate the fastest client needs and every client
        // sees it. Measured on a Moto G 5S: samples 85-100 ms apart against this
        // 1 s request. The request is kept because it costs nothing and is honored
        // where the magnetometer is not already being driven, but nothing here may
        // assume a 1 s cadence.
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (magnetometer != null) {
            sensorManager.registerListener(this, magnetometer, MAGNETOMETER_SAMPLE_PERIOD_US)
        } else {
            // Said out loud rather than swallowed by a null-safe call. "No such
            // sensor" and "sensor present but never speaks" produce identical
            // silence in the log, and only one of them is a bug worth chasing.
            SpLog.d("Compass", "no TYPE_MAGNETIC_FIELD on this device — accuracy from the fused sensor only")
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            // Forward fused orientation data to ViewModel
            Sensor.TYPE_ROTATION_VECTOR -> viewModel.updateOrientation(event.values)
            // Not the heading — that comes from the fused sensor above. These
            // readings are here for their magnitude alone, which is the only
            // interference test that survives a device pinning its accuracy flags
            // (the Moto G 5S pins both). Arrives far faster than the 1 s requested
            // above — measured at 85-100 ms — so treat this as a hot path.
            Sensor.TYPE_MAGNETIC_FIELD  -> viewModel.updateFieldMagnitude(event.values)
        }
    }

    // The only signal Android gives that the compass is lying. Hard and soft iron
    // near the phone — a truck bed, a launch rail, a magnetic phone mount — bias
    // the heading by tens of degrees with nothing in the azimuth itself to say so.
    // Discarding this callback (as this method used to) left the AR overlay
    // pointing confidently at open sky with no way for the app to know.
    //
    // Neither sensor is the authority, because neither is reliably alive: the
    // magnetometer reports on a Pixel 9 Pro XL and never fires on a Moto G 5S,
    // where the rotation vector reports instead. Both are forwarded and the
    // ViewModel publishes the worst of whichever have spoken.
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (sensor?.type) {
            Sensor.TYPE_MAGNETIC_FIELD  -> viewModel.updateCompassAccuracy(accuracy)
            Sensor.TYPE_ROTATION_VECTOR -> viewModel.updateFusedAccuracy(accuracy)
        }
    }

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