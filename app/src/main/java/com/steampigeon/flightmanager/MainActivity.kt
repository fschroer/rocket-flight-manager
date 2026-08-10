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
        // onSensorChanged. TYPE_ROTATION_VECTOR is a fused virtual sensor and many
        // implementations never update its accuracy field, so hanging the
        // calibration warning off it produced a warning that no amount of magnetic
        // interference could trigger. Calibration state belongs to the magnetometer,
        // so that is what gets asked.
        //
        // One second between samples, not SENSOR_DELAY_NORMAL (~200 ms): accuracy
        // callbacks are event-driven and arrive regardless of the sampling period,
        // so the value stream is pure overhead here and is set as slow as the API
        // allows to ask for.
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { magnetometer ->
            sensorManager.registerListener(this, magnetometer, MAGNETOMETER_SAMPLE_PERIOD_US)
        }
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
        // TYPE_MAGNETIC_FIELD deliberately falls through: it is registered for its
        // accuracy callback and its readings are of no use here — the heading comes
        // from the fused rotation vector.
    }

    // The only signal Android gives that the compass is lying. Hard and soft iron
    // near the phone — a truck bed, a launch rail, a magnetic phone mount — bias
    // the heading by tens of degrees with nothing in the azimuth itself to say so.
    // Discarding this callback (as this method used to) left the AR overlay
    // pointing confidently at open sky with no way for the app to know.
    //
    // The magnetometer is the authority: it owns the calibration state, and the
    // fused rotation vector's accuracy proved inert under a magnet held against the
    // phone. The rotation-vector value is still forwarded for diagnosis, so a device
    // where the fused sensor IS live can be told apart from one where it is not.
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (sensor?.type) {
            Sensor.TYPE_MAGNETIC_FIELD  -> viewModel.updateCompassAccuracy(accuracy)
            Sensor.TYPE_ROTATION_VECTOR -> viewModel.logFusedAccuracy(accuracy)
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