package com.example.blindproject1.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class HapticManager(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun triggerNormalFeedback() {
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 50ms vibration with default amplitude
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        }
    }

    fun triggerDangerFeedback() {
        if (vibrator?.hasVibrator() == true) {
            // Pattern: Wait 0ms, Vibrate 500ms, Sleep 100ms, Vibrate 500ms
            val timings = longArrayOf(0, 500, 100, 500)
            
            // Amplitudes: 0, 255 (Max), 0, 255 (Max)
            // Note: amplitude array size must match timings array size for createWaveform with amplitudes
            val amplitudes = intArrayOf(0, 255, 0, 255)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Check if device supports amplitude control
                if (vibrator.hasAmplitudeControl()) {
                     vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                     vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        }
    }
}