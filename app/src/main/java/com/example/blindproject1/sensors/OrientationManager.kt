package com.example.blindproject1.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Manages device orientation sensors to provide the current azimuth (heading).
 * Supports fallback to Accelerometer + Magnetometer if Rotation Vector is unavailable.
 */
class OrientationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /**
     * Emits azimuth in degrees (0..360). 0 = North, 90 = East, etc.
     */
    val azimuthFlow: Flow<Float> = callbackFlow {
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        
        // Variables for fallback mechanism (Accel + Mag)
        val accelerometerReading = FloatArray(3)
        val magnetometerReading = FloatArray(3)
        var hasAccelerometer = false
        var hasMagnetometer = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return

                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    emitAzimuth(orientationAngles[0])
                } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
                    hasAccelerometer = true
                    calculateOrientation()
                } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
                    hasMagnetometer = true
                    calculateOrientation()
                }
            }

            private fun calculateOrientation() {
                if (hasAccelerometer && hasMagnetometer) {
                    if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) {
                        SensorManager.getOrientation(rotationMatrix, orientationAngles)
                        emitAzimuth(orientationAngles[0])
                    }
                }
            }
            
            private fun emitAzimuth(azimuthRadians: Float) {
                // orientationAngles[0] is azimuth in radians (-PI to PI)
                // Convert to degrees (0 to 360)
                var azimuthDegrees = Math.toDegrees(azimuthRadians.toDouble()).toFloat()
                azimuthDegrees = (azimuthDegrees + 360) % 360
                trySend(azimuthDegrees)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // No-op
            }
        }

        // Try Rotation Vector first
        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(listener, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            // Fallback to Accelerometer + Magnetometer
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            
            if (accelerometer != null && magnetometer != null) {
                sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
                sensorManager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_UI)
            } else {
                close(Exception("Suitable orientation sensors not found"))
            }
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
}