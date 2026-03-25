package com.example.blindproject1.sensors

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YawCalibrationStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadOffsetDegrees(): Float = wrap180(prefs.getFloat(KEY_OFFSET_DEGREES, 0f))

    fun loadInvertYaw(): Boolean = prefs.getBoolean(KEY_INVERT_YAW, false)

    fun save(offsetDegrees: Float, invertYaw: Boolean) {
        prefs.edit()
            .putFloat(KEY_OFFSET_DEGREES, wrap180(offsetDegrees))
            .putBoolean(KEY_INVERT_YAW, invertYaw)
            .apply()
    }

    private fun wrap180(value: Float): Float {
        var x = value
        while (x > 180f) x -= 360f
        while (x <= -180f) x += 360f
        return x
    }

    companion object {
        private const val PREFS_NAME = "yaw_calibration_prefs"
        private const val KEY_OFFSET_DEGREES = "offset_degrees"
        private const val KEY_INVERT_YAW = "invert_yaw"
    }
}