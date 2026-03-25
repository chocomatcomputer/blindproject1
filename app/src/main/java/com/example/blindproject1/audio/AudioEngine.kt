package com.example.blindproject1.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Navigation-optimized binaural engine.
 *
 * Goals:
 * - Route sound must come only from the desired heading.
 * - Strong lateralization on ordinary stereo / bone-conduction earphones.
 * - Head-tracked externally by caller via setRouteAzimuth(target - glassesYaw).
 *
 * This is NOT platform Spatializer-based.
 * It renders stereo binaural cues directly in the app.
 */
class AudioEngine {

    companion object {
        private const val TAG = "AudioEngine"

        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val BLOCK_FRAMES = 256

        // Stronger directional cues than pure tones.
        private const val ROUTE_FREQ_A = 700.0
        private const val ROUTE_FREQ_B = 1250.0
        private const val OBSTACLE_FREQ = 1750.0

        // Stronger ITD for lateralization on bone conduction.
        private const val MAX_ITD_SEC = 0.00063f // ~0.63 ms

        private const val MASTER_GAIN = 0.92f
    }

    @Volatile
    private var running = false

    private var audioTrack: AudioTrack? = null
    private var renderThread: Thread? = null

    @Volatile
    private var routeActive = false

    @Volatile
    private var routeAzimuthDeg = 0f

    @Volatile
    private var routeDistanceMeters = 5f

    @Volatile
    private var obstacleAzimuthDeg: Float? = null

    @Volatile
    private var obstacleSeverity = 0f

    @Volatile
    private var obstacleLayerEnabled = false

    @Volatile
    private var normalVolumeScale = 1f

    private var routeSmoothedAzimuth = 0f
    private var obstacleSmoothedAzimuth = 0f

    private var routePhaseA = 0.0
    private var routePhaseB = 0.0
    private var obstaclePhase = 0.0
    private var routeCounter = 0L
    private var obstacleCounter = 0L
    private var noiseState = 0x13579BDF.toInt()

    private val routeLeftDelay = FractionalDelayLine(96)
    private val routeRightDelay = FractionalDelayLine(96)
    private val obstacleLeftDelay = FractionalDelayLine(96)
    private val obstacleRightDelay = FractionalDelayLine(96)

    private val routeLeftShadow = OnePoleLowPass()
    private val routeRightShadow = OnePoleLowPass()
    private val obstacleLeftShadow = OnePoleLowPass()
    private val obstacleRightShadow = OnePoleLowPass()

    private val rearLeftDarkening = OnePoleLowPass()
    private val rearRightDarkening = OnePoleLowPass()

    @Synchronized
    fun start() {
        if (running) return

        val track = createAudioTrack() ?: return
        audioTrack = track

        try {
            track.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioTrack", e)
            track.release()
            audioTrack = null
            return
        }

        running = true
        renderThread = Thread {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (_: Exception) {
            }
            renderLoop(track)
        }.apply {
            name = "BlindNav-SpatialAudio"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false

        renderThread?.interrupt()
        try {
            renderThread?.join(300)
        } catch (_: InterruptedException) {
        }
        renderThread = null

        try {
            audioTrack?.pause()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.flush()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = null

        routeActive = false
        obstacleLayerEnabled = false
        obstacleAzimuthDeg = null
        obstacleSeverity = 0f
        normalVolumeScale = 1f
    }

    fun setRouteActive(active: Boolean) {
        routeActive = active
    }

    fun setRouteAzimuth(deg: Float) {
        routeAzimuthDeg = wrap180(deg)
    }

    fun updateDirection(angle: Float) {
        setRouteAzimuth(angle)
    }

    fun setRouteDistanceMeters(distanceMeters: Float) {
        routeDistanceMeters = distanceMeters.coerceAtLeast(0.5f)
    }

    fun setObstacleCue(deg: Float?, severity: Float = 0f) {
        obstacleAzimuthDeg = deg?.let { wrap180(it) }
        obstacleSeverity = severity.coerceIn(0f, 1f)
    }

    fun clearObstacleCue() {
        obstacleAzimuthDeg = null
        obstacleSeverity = 0f
    }

    fun playDangerSound() {
        obstacleLayerEnabled = true
    }

    fun stopDangerSound() {
        obstacleLayerEnabled = false
        clearObstacleCue()
    }

    fun setNormalVolumeScale(scale: Float) {
        normalVolumeScale = scale.coerceIn(0f, 1f)
    }

    private fun renderLoop(track: AudioTrack) {
        val interleaved = ShortArray(BLOCK_FRAMES * CHANNELS)

        while (running) {
            for (i in 0 until BLOCK_FRAMES) {
                var left = 0f
                var right = 0f

                if (routeActive) {
                    routeSmoothedAzimuth = smoothAngle(routeSmoothedAzimuth, routeAzimuthDeg, 0.10f)

                    val routeMono = nextRouteSample(routeSmoothedAzimuth, routeDistanceMeters)
                    val routeStereo = renderBinaural(
                        mono = routeMono,
                        azimuthDeg = routeSmoothedAzimuth,
                        leftDelay = routeLeftDelay,
                        rightDelay = routeRightDelay,
                        leftShadow = routeLeftShadow,
                        rightShadow = routeRightShadow,
                        sourceGain = 1.0f,
                        rearDarkening = true
                    )

                    left += routeStereo.left * normalVolumeScale
                    right += routeStereo.right * normalVolumeScale
                }

                val obsAz = obstacleAzimuthDeg
                if (obstacleLayerEnabled && obsAz != null) {
                    obstacleSmoothedAzimuth = smoothAngle(obstacleSmoothedAzimuth, obsAz, 0.18f)

                    val sev = obstacleSeverity.coerceIn(0f, 1f)
                    val obstacleMono = nextObstacleSample(sev)
                    val obstacleStereo = renderBinaural(
                        mono = obstacleMono,
                        azimuthDeg = obstacleSmoothedAzimuth,
                        leftDelay = obstacleLeftDelay,
                        rightDelay = obstacleRightDelay,
                        leftShadow = obstacleLeftShadow,
                        rightShadow = obstacleRightShadow,
                        sourceGain = 1.1f + 0.45f * sev,
                        rearDarkening = false
                    )

                    // Duck route a bit when obstacle exists.
                    val duck = 1f - 0.55f * sev
                    left = left * duck + obstacleStereo.left
                    right = right * duck + obstacleStereo.right
                }

                interleaved[i * 2] = floatToPcm16(softClip(left * MASTER_GAIN))
                interleaved[i * 2 + 1] = floatToPcm16(softClip(right * MASTER_GAIN))
            }

            val written = try {
                track.write(interleaved, 0, interleaved.size)
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack write failed", e)
                -1
            }

            if (written < 0) {
                Log.e(TAG, "AudioTrack write returned error: $written")
                break
            }
        }
    }

    /**
     * Route cue:
     * Broadband transient + chirp + noise is much easier to localize than a pure sine tone.
     */
    private fun nextRouteSample(routeAzimuth: Float, distanceMeters: Float): Float {
        val alignment = 1f - (abs(routeAzimuth) / 180f).coerceIn(0f, 1f)

        // Faster pulses when the direction is closer to center.
        val intervalMs = lerp(420f, 210f, alignment)
        val pulseMs = 46f

        val cycleSamples = max(1L, (intervalMs * SAMPLE_RATE / 1000f).toLong())
        val onSamples = max(1L, (pulseMs * SAMPLE_RATE / 1000f).toLong())

        val pos = routeCounter % cycleSamples
        routeCounter++

        if (pos >= onSamples) return 0f

        val env = raisedCosine(pos, onSamples)
        val progress = pos.toFloat() / onSamples.toFloat()

        val click = if (pos < 14L) 1f - (pos / 14f) else 0f
        val chirpA = ROUTE_FREQ_A + 260.0 * progress
        val chirpB = ROUTE_FREQ_B + 140.0 * progress

        routePhaseA += 2.0 * PI * chirpA / SAMPLE_RATE
        routePhaseB += 2.0 * PI * chirpB / SAMPLE_RATE
        if (routePhaseA > 2.0 * PI) routePhaseA -= 2.0 * PI
        if (routePhaseB > 2.0 * PI) routePhaseB -= 2.0 * PI

        val noise = nextWhiteNoise()

        val mono =
            0.28f * click +
                    0.32f * sin(routePhaseA).toFloat() +
                    0.18f * sin(routePhaseB).toFloat() +
                    0.22f * noise

        val distanceGain = (1f / (1f + 0.07f * distanceMeters)).coerceIn(0.55f, 1f)

        return mono * env * 0.58f * distanceGain
    }

    /**
     * Obstacle cue:
     * More urgent, sharper, slightly noisier.
     */
    private fun nextObstacleSample(severity: Float): Float {
        val sev = severity.coerceIn(0.15f, 1f)

        val intervalMs = lerp(240f, 90f, sev)
        val pulseMs = lerp(28f, 72f, sev)

        val cycleSamples = max(1L, (intervalMs * SAMPLE_RATE / 1000f).toLong())
        val onSamples = max(1L, (pulseMs * SAMPLE_RATE / 1000f).toLong())

        val pos = obstacleCounter % cycleSamples
        obstacleCounter++

        if (pos >= onSamples) return 0f

        val env = raisedCosine(pos, onSamples)
        obstaclePhase += 2.0 * PI * OBSTACLE_FREQ / SAMPLE_RATE
        if (obstaclePhase > 2.0 * PI) obstaclePhase -= 2.0 * PI

        val tone = sin(obstaclePhase).toFloat()
        val noise = nextWhiteNoise()
        val click = if (pos < 10L) 1f - (pos / 10f) else 0f

        val mono =
            0.22f * click +
                    0.36f * tone +
                    0.42f * noise

        return mono * env * (0.24f + 0.50f * sev)
    }

    /**
     * Strong HRTF-lite renderer.
     *
     * 1) Interaural time difference (fractional delay)
     * 2) Interaural level difference (very strong far-ear suppression)
     * 3) Far-ear spectral shadowing (low-pass)
     * 4) Rear darkening
     */
    private fun renderBinaural(
        mono: Float,
        azimuthDeg: Float,
        leftDelay: FractionalDelayLine,
        rightDelay: FractionalDelayLine,
        leftShadow: OnePoleLowPass,
        rightShadow: OnePoleLowPass,
        sourceGain: Float,
        rearDarkening: Boolean
    ): StereoFrame {
        val azimuthRad = Math.toRadians(azimuthDeg.toDouble())
        val pan = sin(azimuthRad).toFloat().coerceIn(-1f, 1f)
        val frontness = cos(azimuthRad).toFloat()
        val absPan = abs(pan)

        val maxDelaySamples = MAX_ITD_SEC * SAMPLE_RATE
        val leftDelaySamples = if (pan > 0f) maxDelaySamples * absPan else 0f
        val rightDelaySamples = if (pan < 0f) maxDelaySamples * absPan else 0f

        var left = leftDelay.process(mono, leftDelaySamples)
        var right = rightDelay.process(mono, rightDelaySamples)

        // Strong ILD for bone conduction / ordinary stereo.
        // Near ear stays strong, far ear is heavily attenuated at large azimuth.
        val farEarGain = lerp(1f, 0.06f, absPan)
        val nearEarGain = 1.0f

        // Extra low-pass on the far ear.
        val farEarAlpha = lerp(0.90f, 0.06f, absPan)

        when {
            pan > 0f -> {
                left = leftShadow.process(left, farEarAlpha) * farEarGain
                right = rightShadow.bypass(right) * nearEarGain
            }
            pan < 0f -> {
                left = leftShadow.bypass(left) * nearEarGain
                right = rightShadow.process(right, farEarAlpha) * farEarGain
            }
            else -> {
                left = leftShadow.bypass(left)
                right = rightShadow.bypass(right)
            }
        }

        // Equal-power base panning, then far-ear suppression pushes the image outward.
        val baseLeft = sqrt(0.5f * (1f - pan))
        val baseRight = sqrt(0.5f * (1f + pan))

        var outLeft = left * baseLeft
        var outRight = right * baseRight

        if (rearDarkening && frontness < 0f) {
            outLeft = rearLeftDarkening.process(outLeft, 0.14f) * 0.84f
            outRight = rearRightDarkening.process(outRight, 0.14f) * 0.84f
        } else {
            outLeft = rearLeftDarkening.bypass(outLeft)
            outRight = rearRightDarkening.bypass(outRight)
        }

        return StereoFrame(
            left = outLeft * sourceGain,
            right = outRight * sourceGain
        )
    }

    private fun createAudioTrack(): AudioTrack? {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize <= 0) {
            Log.e(TAG, "Invalid minBufferSize: $minBufferSize")
            return null
        }

        val bufferSize = max(
            minBufferSize,
            BLOCK_FRAMES * CHANNELS * BYTES_PER_SAMPLE * 8
        )

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        return try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            builder.build()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating AudioTrack", e)
            null
        }
    }

    private fun smoothAngle(current: Float, target: Float, alpha: Float): Float {
        val delta = wrap180(target - current)
        return wrap180(current + delta * alpha)
    }

    private fun raisedCosine(pos: Long, total: Long): Float {
        if (total <= 1L) return 1f
        val x = pos.toDouble() / (total - 1).toDouble()
        return (0.5 - 0.5 * cos(2.0 * PI * x)).toFloat()
    }

    private fun nextWhiteNoise(): Float {
        var x = noiseState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        noiseState = x
        return ((x and 0x7fffffff) / 1073741824f) - 1f
    }

    private fun wrap180(value: Float): Float {
        var x = value
        while (x > 180f) x -= 360f
        while (x <= -180f) x += 360f
        return x
    }

    private fun lerp(a: Float, b: Float, t: Float): Float {
        return a + (b - a) * t.coerceIn(0f, 1f)
    }

    private fun softClip(x: Float): Float {
        return x / (1f + 0.30f * abs(x))
    }

    private fun floatToPcm16(x: Float): Short {
        val clamped = x.coerceIn(-1f, 1f)
        return (clamped * Short.MAX_VALUE).toInt().toShort()
    }

    private data class StereoFrame(
        val left: Float,
        val right: Float
    )

    private class FractionalDelayLine(maxDelaySamples: Int) {
        private val buffer = FloatArray(maxDelaySamples + 4)
        private var writeIndex = 0

        fun process(input: Float, delaySamples: Float): Float {
            buffer[writeIndex] = input

            var readPos = writeIndex - delaySamples
            while (readPos < 0f) readPos += buffer.size

            val i0 = readPos.toInt() % buffer.size
            val i1 = (i0 + 1) % buffer.size
            val frac = readPos - i0

            val s0 = buffer[i0]
            val s1 = buffer[i1]
            val out = s0 + (s1 - s0) * frac

            writeIndex++
            if (writeIndex >= buffer.size) writeIndex = 0

            return out
        }
    }

    private class OnePoleLowPass {
        private var y = 0f

        fun process(input: Float, alpha: Float): Float {
            val a = alpha.coerceIn(0.03f, 1f)
            y += a * (input - y)
            return y
        }

        fun bypass(input: Float): Float {
            y = input
            return input
        }
    }
}