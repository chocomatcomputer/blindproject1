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
 * Direction-first audio compass for blind navigation.
 *
 * Design goal:
 * - Do NOT aim for subtle "nice" spatialization.
 * - Aim for unmistakable directional guidance on normal stereo earbuds.
 *
 * Strategy:
 * - On-target: centered beacon.
 * - Off to left/right: almost single-ear cue on the correct side.
 * - Far off / behind: strong "turn this way" cue only on that side.
 *
 * Caller must provide routeAzimuth = targetBearing - correctedGlassesYaw.
 */
class AudioEngine {

    companion object {
        private const val TAG = "AudioEngine"

        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val BLOCK_FRAMES = 256

        private const val FRONT_CONE_DEG = 10f
        private const val SIDE_CONE_DEG = 45f
        private const val TURN_CONE_DEG = 100f

        private const val MAX_ITD_SEC = 0.00065f
        private const val MASTER_GAIN = 0.90f

        private const val ROUTE_FREQ_A = 720.0
        private const val ROUTE_FREQ_B = 1280.0
        private const val OBSTACLE_FREQ = 1700.0
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
    private var noiseState = 0x1A2B3C4D

    private val routeLeftDelay = FractionalDelayLine(96)
    private val routeRightDelay = FractionalDelayLine(96)
    private val obstacleLeftDelay = FractionalDelayLine(96)
    private val obstacleRightDelay = FractionalDelayLine(96)

    private val routeLeftShadow = OnePoleLowPass()
    private val routeRightShadow = OnePoleLowPass()
    private val obstacleLeftShadow = OnePoleLowPass()
    private val obstacleRightShadow = OnePoleLowPass()

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
            name = "BlindNav-AudioCompass"
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

                    val routeMono = nextRouteMono(routeSmoothedAzimuth, routeDistanceMeters)
                    val routeStereo = renderRouteCompass(routeMono, routeSmoothedAzimuth)

                    left += routeStereo.left * normalVolumeScale
                    right += routeStereo.right * normalVolumeScale
                }

                val obstacleAz = obstacleAzimuthDeg
                if (obstacleLayerEnabled && obstacleAz != null) {
                    obstacleSmoothedAzimuth = smoothAngle(obstacleSmoothedAzimuth, obstacleAz, 0.20f)
                    val obstacleMono = nextObstacleMono(obstacleSeverity)
                    val obstacleStereo = renderObstacleCompass(obstacleMono, obstacleSmoothedAzimuth, obstacleSeverity)

                    val duck = 1f - 0.55f * obstacleSeverity
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
                Log.e(TAG, "AudioTrack write error: $written")
                break
            }
        }
    }

    /**
     * Route cue is intentionally broadband and transient-heavy.
     * Much easier to lateralize than a pure sine tone.
     */
    private fun nextRouteMono(routeAzimuth: Float, distanceMeters: Float): Float {
        val absAz = abs(routeAzimuth)
        val cycleMs = when {
            absAz <= FRONT_CONE_DEG -> 360f
            absAz <= SIDE_CONE_DEG -> 260f
            absAz <= TURN_CONE_DEG -> 180f
            else -> 120f
        }

        val pulseWidthMs = 34f
        val cycleSamples = max(1L, (cycleMs * SAMPLE_RATE / 1000f).toLong())
        val pulseWidthSamples = max(1L, (pulseWidthMs * SAMPLE_RATE / 1000f).toLong())
        val pos = routeCounter % cycleSamples
        routeCounter++

        val pulseOffsets = when {
            absAz <= FRONT_CONE_DEG -> longArrayOf(
                0L,
                (0.080f * SAMPLE_RATE).toLong()
            )
            absAz <= SIDE_CONE_DEG -> longArrayOf(0L)
            absAz <= TURN_CONE_DEG -> longArrayOf(
                0L,
                (0.055f * SAMPLE_RATE).toLong()
            )
            else -> longArrayOf(
                0L,
                (0.040f * SAMPLE_RATE).toLong(),
                (0.080f * SAMPLE_RATE).toLong()
            )
        }

        val env = pulseTrainEnvelope(pos, pulseOffsets, pulseWidthSamples)
        if (env <= 0f) return 0f

        val click = sharpClickEnvelope(pos, pulseOffsets, 10)
        val progress = ((pos % pulseWidthSamples).toFloat() / pulseWidthSamples.toFloat()).coerceIn(0f, 1f)

        val fA = ROUTE_FREQ_A + 220.0 * progress
        val fB = ROUTE_FREQ_B + 140.0 * progress

        routePhaseA += 2.0 * PI * fA / SAMPLE_RATE
        routePhaseB += 2.0 * PI * fB / SAMPLE_RATE
        if (routePhaseA > 2.0 * PI) routePhaseA -= 2.0 * PI
        if (routePhaseB > 2.0 * PI) routePhaseB -= 2.0 * PI

        val noise = nextWhiteNoise()
        val mono =
            0.26f * click +
                    0.34f * sin(routePhaseA).toFloat() +
                    0.18f * sin(routePhaseB).toFloat() +
                    0.22f * noise

        val distanceGain = (1f / (1f + 0.08f * distanceMeters)).coerceIn(0.55f, 1f)
        return mono * env * 0.60f * distanceGain
    }

    private fun nextObstacleMono(severity: Float): Float {
        val sev = severity.coerceIn(0.15f, 1f)
        val cycleMs = lerp(220f, 95f, sev)
        val pulseWidthMs = lerp(26f, 72f, sev)

        val cycleSamples = max(1L, (cycleMs * SAMPLE_RATE / 1000f).toLong())
        val pulseWidthSamples = max(1L, (pulseWidthMs * SAMPLE_RATE / 1000f).toLong())
        val pos = obstacleCounter % cycleSamples
        obstacleCounter++

        val pulseOffsets = longArrayOf(0L)
        val env = pulseTrainEnvelope(pos, pulseOffsets, pulseWidthSamples)
        if (env <= 0f) return 0f

        val click = sharpClickEnvelope(pos, pulseOffsets, 8)
        obstaclePhase += 2.0 * PI * OBSTACLE_FREQ / SAMPLE_RATE
        if (obstaclePhase > 2.0 * PI) obstaclePhase -= 2.0 * PI

        val tone = sin(obstaclePhase).toFloat()
        val noise = nextWhiteNoise()
        val mono =
            0.22f * click +
                    0.36f * tone +
                    0.42f * noise

        return mono * env * (0.28f + 0.50f * sev)
    }

    /**
     * Navigation-optimized route rendering.
     * - Front: centered beacon
     * - Side: strong ipsilateral cue
     * - Behind: "turn this way" ear-only cue, not fake rear localization
     */
    private fun renderRouteCompass(mono: Float, azimuthDeg: Float): StereoFrame {
        val absAz = abs(azimuthDeg)

        return when {
            absAz <= FRONT_CONE_DEG -> {
                // Very small pan near center, mostly centered.
                val pan = (azimuthDeg / FRONT_CONE_DEG).coerceIn(-1f, 1f) * 0.25f
                val left = mono * (1f - pan).coerceIn(0f, 1.2f)
                val right = mono * (1f + pan).coerceIn(0f, 1.2f)
                StereoFrame(left = left * 0.92f, right = right * 0.92f)
            }

            absAz <= SIDE_CONE_DEG -> {
                // Front-left / front-right: near ear dominant, far ear faint.
                renderLateralized(
                    mono = mono,
                    azimuthDeg = azimuthDeg,
                    nearGain = 1.0f,
                    farGain = 0.10f,
                    itdSec = 0.00038f,
                    farEarAlpha = 0.08f,
                    hardOneEar = false,
                    isObstacle = false
                )
            }

            absAz <= TURN_CONE_DEG -> {
                // Strong turn cue: essentially one-ear guidance.
                renderLateralized(
                    mono = mono,
                    azimuthDeg = azimuthDeg,
                    nearGain = 1.0f,
                    farGain = 0.015f,
                    itdSec = 0.00065f,
                    farEarAlpha = 0.05f,
                    hardOneEar = true,
                    isObstacle = false
                )
            }

            else -> {
                // Too far behind to trust "rear" localization on generic earbuds.
                // Turn cue only on the side you should rotate toward.
                renderTurnCueOnly(
                    mono = mono,
                    azimuthDeg = azimuthDeg
                )
            }
        }
    }

    private fun renderObstacleCompass(mono: Float, azimuthDeg: Float, severity: Float): StereoFrame {
        return renderLateralized(
            mono = mono,
            azimuthDeg = azimuthDeg,
            nearGain = 1.0f + 0.25f * severity,
            farGain = 0.06f,
            itdSec = 0.00050f,
            farEarAlpha = 0.06f,
            hardOneEar = true,
            isObstacle = true
        )
    }

    private fun renderTurnCueOnly(mono: Float, azimuthDeg: Float): StereoFrame {
        return if (azimuthDeg < 0f) {
            StereoFrame(left = mono * 1.0f, right = 0f)
        } else {
            StereoFrame(left = 0f, right = mono * 1.0f)
        }
    }

    private fun renderLateralized(
        mono: Float,
        azimuthDeg: Float,
        nearGain: Float,
        farGain: Float,
        itdSec: Float,
        farEarAlpha: Float,
        hardOneEar: Boolean,
        isObstacle: Boolean
    ): StereoFrame {
        val leftDelay = if (isObstacle) obstacleLeftDelay else routeLeftDelay
        val rightDelay = if (isObstacle) obstacleRightDelay else routeRightDelay
        val leftShadow = if (isObstacle) obstacleLeftShadow else routeLeftShadow
        val rightShadow = if (isObstacle) obstacleRightShadow else routeRightShadow

        val delaySamples = itdSec * SAMPLE_RATE

        return if (azimuthDeg < 0f) {
            // Source is on the left
            val nearLeft = leftDelay.process(mono, 0f) * nearGain
            val farRightRaw = rightDelay.process(mono, delaySamples)
            val farRight = if (hardOneEar) {
                farRightRaw * farGain
            } else {
                rightShadow.process(farRightRaw, farEarAlpha) * farGain
            }
            StereoFrame(
                left = nearLeft,
                right = farRight
            )
        } else {
            // Source is on the right
            val nearRight = rightDelay.process(mono, 0f) * nearGain
            val farLeftRaw = leftDelay.process(mono, delaySamples)
            val farLeft = if (hardOneEar) {
                farLeftRaw * farGain
            } else {
                leftShadow.process(farLeftRaw, farEarAlpha) * farGain
            }
            StereoFrame(
                left = farLeft,
                right = nearRight
            )
        }
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

        val bufferSize = max(minBufferSize, BLOCK_FRAMES * CHANNELS * BYTES_PER_SAMPLE * 8)

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

    private fun lerp(a: Float, b: Float, t: Float): Float {
        return a + (b - a) * t.coerceIn(0f, 1f)
    }

    private fun softClip(x: Float): Float {
        return x / (1f + 0.28f * abs(x))
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