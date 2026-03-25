package com.example.blindproject1.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Route-exclusive auditory compass.
 *
 * Goal:
 * - Blind user should never be confused about direction.
 * - If aligned with target: centered in both ears.
 * - If target is left/right: sound comes only from that side.
 * - If target is far off / behind: faster and more urgent pulses from that side only.
 *
 * This intentionally does NOT try to make subtle "natural" HRTF space.
 * It prioritizes directional certainty over realism.
 *
 * Caller should provide:
 * routeAzimuth = targetBearing - correctedGlassesYaw
 */
class AudioEngine {

    companion object {
        private const val TAG = "AudioEngine"

        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val BLOCK_FRAMES = 256

        // Alignment windows
        private const val CENTER_WINDOW_DEG = 8f
        private const val SMALL_TURN_DEG = 30f
        private const val MEDIUM_TURN_DEG = 75f
        private const val LARGE_TURN_DEG = 135f

        private const val MASTER_GAIN = 0.90f

        // Tone palette
        private const val ROUTE_FREQ_A = 760.0
        private const val ROUTE_FREQ_B = 1280.0
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
    private var normalVolumeScale = 1f

    private var smoothedRouteAzimuth = 0f
    private var routeCounter = 0L
    private var phaseA = 0.0
    private var phaseB = 0.0
    private var noiseState = 0x13579BDF.toInt()

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
            name = "BlindNav-AuditoryCompass"
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

    /**
     * Route-only mode에서는 obstacle spatial audio를 사용하지 않음.
     * 기존 ViewModel 호환을 위해 no-op으로 유지.
     */
    fun setObstacleCue(deg: Float?, severity: Float = 0f) {
        // no-op
    }

    fun clearObstacleCue() {
        // no-op
    }

    fun playDangerSound() {
        // no-op
    }

    fun stopDangerSound() {
        // no-op
    }

    /**
     * Route cue는 항상 명확해야 하므로 과도한 ducking을 막는다.
     */
    fun setNormalVolumeScale(scale: Float) {
        normalVolumeScale = scale.coerceIn(0.90f, 1.0f)
    }

    private fun renderLoop(track: AudioTrack) {
        val interleaved = ShortArray(BLOCK_FRAMES * CHANNELS)

        while (running) {
            for (i in 0 until BLOCK_FRAMES) {
                var left = 0f
                var right = 0f

                if (routeActive) {
                    smoothedRouteAzimuth = smoothAngle(smoothedRouteAzimuth, routeAzimuthDeg, 0.14f)

                    val profile = routeProfileFor(smoothedRouteAzimuth)
                    val mono = nextRouteMono(profile, routeDistanceMeters)

                    val stereo = when (profile.directionMode) {
                        DirectionMode.CENTER -> StereoFrame(
                            left = mono * 0.92f,
                            right = mono * 0.92f
                        )

                        DirectionMode.LEFT_ONLY -> StereoFrame(
                            left = mono,
                            right = 0f
                        )

                        DirectionMode.RIGHT_ONLY -> StereoFrame(
                            left = 0f,
                            right = mono
                        )
                    }

                    left += stereo.left * normalVolumeScale
                    right += stereo.right * normalVolumeScale
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

    private fun nextRouteMono(profile: CueProfile, distanceMeters: Float): Float {
        val cycleSamples = max(1L, (profile.cycleMs * SAMPLE_RATE / 1000f).toLong())
        val pulseWidthSamples = max(1L, (profile.pulseWidthMs * SAMPLE_RATE / 1000f).toLong())
        val pos = routeCounter % cycleSamples
        routeCounter++

        val pulseStartsSamples = profile.pulseStartsMs.map {
            (it * SAMPLE_RATE / 1000f).toLong()
        }.toLongArray()

        val env = pulseTrainEnvelope(
            posInCycle = pos,
            pulseStarts = pulseStartsSamples,
            pulseWidthSamples = pulseWidthSamples
        )

        if (env <= 0f) return 0f

        val click = sharpClickEnvelope(
            posInCycle = pos,
            pulseStarts = pulseStartsSamples,
            clickSamples = 10
        )

        val localPulsePos = localPulsePosition(
            posInCycle = pos,
            pulseStarts = pulseStartsSamples,
            pulseWidthSamples = pulseWidthSamples
        )

        val progress = if (localPulsePos >= 0L) {
            (localPulsePos.toFloat() / pulseWidthSamples.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        val fA = ROUTE_FREQ_A + profile.freqLiftA * progress
        val fB = ROUTE_FREQ_B + profile.freqLiftB * progress

        phaseA += 2.0 * PI * fA / SAMPLE_RATE
        phaseB += 2.0 * PI * fB / SAMPLE_RATE
        if (phaseA > 2.0 * PI) phaseA -= 2.0 * PI
        if (phaseB > 2.0 * PI) phaseB -= 2.0 * PI

        val noise = nextWhiteNoise()

        val mono =
            0.28f * click +
                    0.36f * sin(phaseA).toFloat() +
                    0.16f * sin(phaseB).toFloat() +
                    0.20f * noise

        val distanceGain = (1f / (1f + 0.08f * distanceMeters)).coerceIn(0.60f, 1f)
        return mono * env * profile.amplitude * distanceGain
    }

    private fun routeProfileFor(angleDeg: Float): CueProfile {
        val absAz = abs(angleDeg)

        return when {
            absAz <= CENTER_WINDOW_DEG -> {
                CueProfile(
                    directionMode = DirectionMode.CENTER,
                    cycleMs = 420f,
                    pulseWidthMs = 36f,
                    pulseStartsMs = floatArrayOf(0f, 90f),
                    amplitude = 0.58f,
                    freqLiftA = 180.0,
                    freqLiftB = 100.0
                )
            }

            absAz <= SMALL_TURN_DEG -> {
                CueProfile(
                    directionMode = if (angleDeg < 0f) DirectionMode.LEFT_ONLY else DirectionMode.RIGHT_ONLY,
                    cycleMs = 260f,
                    pulseWidthMs = 34f,
                    pulseStartsMs = floatArrayOf(0f),
                    amplitude = 0.64f,
                    freqLiftA = 220.0,
                    freqLiftB = 120.0
                )
            }

            absAz <= MEDIUM_TURN_DEG -> {
                CueProfile(
                    directionMode = if (angleDeg < 0f) DirectionMode.LEFT_ONLY else DirectionMode.RIGHT_ONLY,
                    cycleMs = 180f,
                    pulseWidthMs = 32f,
                    pulseStartsMs = floatArrayOf(0f, 55f),
                    amplitude = 0.72f,
                    freqLiftA = 260.0,
                    freqLiftB = 140.0
                )
            }

            absAz <= LARGE_TURN_DEG -> {
                CueProfile(
                    directionMode = if (angleDeg < 0f) DirectionMode.LEFT_ONLY else DirectionMode.RIGHT_ONLY,
                    cycleMs = 130f,
                    pulseWidthMs = 28f,
                    pulseStartsMs = floatArrayOf(0f, 42f, 84f),
                    amplitude = 0.82f,
                    freqLiftA = 320.0,
                    freqLiftB = 160.0
                )
            }

            else -> {
                // Behind: shortest-turn side only
                CueProfile(
                    directionMode = if (angleDeg < 0f) DirectionMode.LEFT_ONLY else DirectionMode.RIGHT_ONLY,
                    cycleMs = 95f,
                    pulseWidthMs = 24f,
                    pulseStartsMs = floatArrayOf(0f, 30f, 60f),
                    amplitude = 0.88f,
                    freqLiftA = 360.0,
                    freqLiftB = 180.0
                )
            }
        }
    }

    private fun pulseTrainEnvelope(
        posInCycle: Long,
        pulseStarts: LongArray,
        pulseWidthSamples: Long
    ): Float {
        var env = 0f
        for (start in pulseStarts) {
            val local = posInCycle - start
            if (local in 0 until pulseWidthSamples) {
                env = max(env, raisedCosine(local, pulseWidthSamples))
            }
        }
        return env
    }

    private fun sharpClickEnvelope(
        posInCycle: Long,
        pulseStarts: LongArray,
        clickSamples: Int
    ): Float {
        var env = 0f
        for (start in pulseStarts) {
            val local = posInCycle - start
            if (local in 0 until clickSamples.toLong()) {
                val x = 1f - (local.toFloat() / clickSamples.toFloat())
                env = max(env, x)
            }
        }
        return env
    }

    private fun localPulsePosition(
        posInCycle: Long,
        pulseStarts: LongArray,
        pulseWidthSamples: Long
    ): Long {
        for (start in pulseStarts) {
            val local = posInCycle - start
            if (local in 0 until pulseWidthSamples) {
                return local
            }
        }
        return -1L
    }

    private fun smoothAngle(current: Float, target: Float, alpha: Float): Float {
        val delta = wrap180(target - current)
        return wrap180(current + delta * alpha)
    }

    private fun raisedCosine(pos: Long, total: Long): Float {
        if (total <= 1L) return 1f
        val x = pos.toDouble() / (total - 1).toDouble()
        return (0.5 - 0.5 * kotlin.math.cos(2.0 * PI * x)).toFloat()
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

    private fun softClip(x: Float): Float {
        return x / (1f + 0.28f * abs(x))
    }

    private fun floatToPcm16(x: Float): Short {
        val clamped = x.coerceIn(-1f, 1f)
        return (clamped * Short.MAX_VALUE).toInt().toShort()
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

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        return try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
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

    private enum class DirectionMode {
        CENTER,
        LEFT_ONLY,
        RIGHT_ONLY
    }

    private data class CueProfile(
        val directionMode: DirectionMode,
        val cycleMs: Float,
        val pulseWidthMs: Float,
        val pulseStartsMs: FloatArray,
        val amplitude: Float,
        val freqLiftA: Double,
        val freqLiftB: Double
    )

    private data class StereoFrame(
        val left: Float,
        val right: Float
    )
}