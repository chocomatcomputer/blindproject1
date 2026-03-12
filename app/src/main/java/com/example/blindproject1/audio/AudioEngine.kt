package com.example.blindproject1.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Manages audio playback for spatial navigation cues.
 * Implements "Cone of Sound" with intermittent beeping for precise direction finding.
 */
class AudioEngine {

    companion object {
        private const val TAG = "AudioEngine"
        private const val SAMPLE_RATE = 44100
        private const val NORMAL_FREQ = 440.0 // Hz (A4)
        private const val DANGER_FREQ = 880.0 // Hz (A5)
        
        // Intermittent Beep Config
        private const val BEEP_MS = 200
        private const val SILENCE_MS = 600 // Faster cycle (800ms total)
        
        // Cone of Sound Config
        private const val CONE_THRESHOLD_DEGREES = 20f
        // 부드러운 볼륨 감쇠를 시작할 각도 (예: 5도 이내는 100%, 5도~20도 사이는 점진적 감소)
        private const val FULL_VOLUME_DEGREES = 5f
    }

    private var normalTrack: AudioTrack? = null
    private var dangerTrack: AudioTrack? = null
    
    private var currentAngle: Float = 0f
    private var normalVolumeScale: Float = 1.0f

    fun start() {
        if (normalTrack == null || normalTrack?.state == AudioTrack.STATE_UNINITIALIZED) {
            setupNormalTrack()
        }
        
        try {
            normalTrack?.play()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to start normalTrack", e)
        }
    }

    fun stop() {
        stopTrack(normalTrack)
        stopTrack(dangerTrack)
        normalTrack?.release()
        dangerTrack?.release()
        normalTrack = null
        dangerTrack = null
    }

    fun playDangerSound() {
        if (dangerTrack == null || dangerTrack?.state == AudioTrack.STATE_UNINITIALIZED) {
            setupDangerTrack()
        }
        try {
            if (dangerTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                dangerTrack?.play()
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to start dangerTrack", e)
        }
    }

    fun stopDangerSound() {
        try {
            if (dangerTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                dangerTrack?.pause()
                dangerTrack?.stop()
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to stop dangerTrack", e)
        }
    }
    
    fun setNormalVolumeScale(scale: Float) {
        normalVolumeScale = scale.coerceIn(0f, 1f)
        updateDirection(currentAngle)
    }

    /**
     * Updates the stereo balance and volume based on "Cone of Sound".
     * @param angle Relative angle to target (-180 to 180). 0 is Front.
     */
    fun updateDirection(angle: Float) {
        currentAngle = angle
        val absAngle = abs(angle)

        var leftVol = 0f
        var rightVol = 0f

        // Cone of Sound Logic: Only play sound if within the threshold (e.g. ±20 degrees)
        if (absAngle <= CONE_THRESHOLD_DEGREES) {
            
            // 1. Calculate Panning (Left/Right balance)
            // Map -20 to 20 into a wider pan range (-1 to 1) for distinct directionality
            val panInput = (angle / CONE_THRESHOLD_DEGREES).coerceIn(-1f, 1f)
            
            if (panInput < 0) {
                // Target is to the Left
                leftVol = 1.0f
                rightVol = 1.0f + panInput // Reduces right volume (e.g. -0.5 -> 0.5)
            } else {
                // Target is to the Right
                rightVol = 1.0f
                leftVol = 1.0f - panInput // Reduces left volume (e.g. 0.5 -> 0.5)
            }
            
            // 2. Calculate Fade Volume (Distance from center)
            // If perfectly centered (0~5 degrees), keep volume at 1.0 (Loudest)
            // As it moves towards the edge (5~20 degrees), smoothly fade out the volume
            var coneGain = 1.0f
            if (absAngle > FULL_VOLUME_DEGREES) {
                val fadeRange = CONE_THRESHOLD_DEGREES - FULL_VOLUME_DEGREES // 20 - 5 = 15
                val progress = (absAngle - FULL_VOLUME_DEGREES) / fadeRange // 0.0 to 1.0
                
                // You can use linear fade (1.0f - progress) or exponential/cosine for smoother fade.
                // Let's use linear for predictable behavior.
                coneGain = 1.0f - progress.coerceIn(0f, 1f)
            }
            
            // Apply the fade gain to both left and right channels
            leftVol *= coneGain
            rightVol *= coneGain
            
        } else {
            // Outside the 20-degree cone: Complete Silence
            leftVol = 0f
            rightVol = 0f
        }

        // Apply master volume scale (e.g., when danger sound is active, normal sound is reduced)
        leftVol = leftVol.coerceIn(0f, 1f) * normalVolumeScale
        rightVol = rightVol.coerceIn(0f, 1f) * normalVolumeScale

        try {
            @Suppress("DEPRECATION")
            normalTrack?.setStereoVolume(leftVol, rightVol)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to set stereo volume", e)
        }
    }

    private fun setupNormalTrack() {
        val totalDurationMs = BEEP_MS + SILENCE_MS
        val totalSamples = (totalDurationMs * SAMPLE_RATE / 1000)
        val beepSamples = (BEEP_MS * SAMPLE_RATE / 1000)
        
        val audioData = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            if (i < beepSamples) {
                // Sine wave
                val t = i.toDouble() / SAMPLE_RATE
                val rawSample = sin(2.0 * PI * NORMAL_FREQ * t)
                
                // Simple Envelope to prevent popping (fade in/out over 100 samples)
                var envelope = 1.0
                val fadeLength = 100
                if (i < fadeLength) {
                    envelope = i.toDouble() / fadeLength
                } else if (i > beepSamples - fadeLength) {
                    envelope = (beepSamples - i).toDouble() / fadeLength
                }
                
                audioData[i] = (rawSample * Short.MAX_VALUE * envelope).toInt().toShort()
            } else {
                audioData[i] = 0 // Silence
            }
        }

        normalTrack = createStaticAudioTrack(audioData)
        normalTrack?.setLoopPoints(0, totalSamples, -1)
    }

    private fun setupDangerTrack() {
        // Fast square wave beep
        val beepDuration = 0.1
        val silenceDuration = 0.1
        val totalDuration = beepDuration + silenceDuration
        val sampleCount = (SAMPLE_RATE * totalDuration).toInt()
        val beepSampleCount = (SAMPLE_RATE * beepDuration).toInt()
        
        val audioData = ShortArray(sampleCount)

        for (i in 0 until sampleCount) {
            if (i < beepSampleCount) {
                val period = SAMPLE_RATE / DANGER_FREQ
                val sample = if ((i / (period / 2)).toInt() % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
                audioData[i] = sample
            } else {
                audioData[i] = 0
            }
        }

        dangerTrack = createStaticAudioTrack(audioData)
        dangerTrack?.setLoopPoints(0, sampleCount, -1)
        @Suppress("DEPRECATION")
        dangerTrack?.setStereoVolume(1.0f, 1.0f)
    }

    private fun createStaticAudioTrack(data: ShortArray): AudioTrack? {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()

            track.write(data, 0, data.size)
            track
        } catch (e: Exception) {
            Log.e(TAG, "Error creating AudioTrack", e)
            null
        }
    }
    
    private fun stopTrack(track: AudioTrack?) {
        try {
            if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping track", e)
        }
    }
}