package com.steampigeon.flightmanager

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.appcompat.app.AppCompatActivity

class Compass : AppCompatActivity(), SensorEventListener {
    private var sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private var accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var lastAccelerometer = FloatArray(3)
    private var lastMagnetometer = FloatArray(3)
    private var lastAccelerometerSet = false
    private var lastMagnetometerSet = false
    private var rotationMatrix = FloatArray(9)
    private var orientation = FloatArray(3)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor == magnetometer) {
            System.arraycopy(event.values, 0, lastMagnetometer, 0, event.values.size);
            lastMagnetometerSet = true;
        } else if (event.sensor == accelerometer) {
            System.arraycopy(event.values, 0, lastAccelerometer, 0, event.values.size);
            lastAccelerometerSet = true;
        }

        if (lastAccelerometerSet && lastMagnetometerSet) {
            SensorManager.getRotationMatrix(rotationMatrix, null, lastAccelerometer, lastMagnetometer);
            SensorManager.getOrientation(rotationMatrix, orientation);

            var azimuthInRadians = orientation[0].toDouble()
            var azimuthInDegrees = ((Math.toDegrees(azimuthInRadians) + 360) % 360).toFloat()
        }

    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        TODO("Not yet implemented")
    }

}