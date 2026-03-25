package com.example.blindproject1.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 더 강하게 방향감을 주는 binaural cue engine.
 *
 * 핵심:
 * - route cue는 targetBearing - glassesYaw 로 계산된 azimuth에서 항상 들린다.
 * - 더 이상 "폰을 돌려야" 소리가 맞는 구조가 아니다.
 * - 방향감 강화를 위해 ITD / ILD / far-ear low-pass 를 과장해서 넣었다.
 */
class AudioEngine {

    companion object {
        private const val TAG = "AudioEngine"

        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_COUNT = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val BLOCK_FRAMES = 256

        private const val ROUTE_FREQ_A = 760.0
        private const val ROUTE_FREQ_B = 1180.0
        private const val OBSTACLE_FREQ = 1650.0

        // 방향감 강화를 위해 약간 과장한 ITD
        private const val MAX_ITD_SEC = 0.00055f

        private const val MASTER_GAIN = 0.9f
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
    private var noiseState = 0x2468ACE.toInt()

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

        obstacleLayerEnabled = false
        routeActive = false
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
        val interleaved = ShortArray(BLOCK_FRAMES * CHANNEL_COUNT)

        while (running) {
            for (i in 0 until BLOCK_FRAMES) {
                var left = 0f
                var right = 0f

                if (routeActive) {
                    routeSmoothedAzimuth = smoothAngle(routeSmoothedAzimuth, routeAzimuthDeg, 0.12f)

                    val routeMono = nextRouteSample(routeSmoothedAzimuth, routeDistanceMeters)
                    val routeStereo = renderSource(
                        mono = routeMono,
                        azimuthDeg = routeSmoothedAzimuth,
                        leftDelay = routeLeftDelay,
                        rightDelay = routeRightDelay,
                        leftShadow = routeLeftShadow,
                        rightShadow = routeRightShadow,
                        sourceGain = 1f
                    )

                    left += routeStereo.left * normalVolumeScale
                    right += routeStereo.right * normalVolumeScale
                }

                val obstacleAz = obstacleAzimuthDeg
                if (obstacleLayerEnabled && obstacleAz != null) {
                    obstacleSmoothedAzimuth = smoothAngle(obstacleSmoothedAzimuth, obstacleAz, 0.20f)

                    val sev = obstacleSeverity.coerceIn(0f, 1f)
                    val obstacleMono = nextObstacleSample(sev)

                    val obstacleStereo = renderSource(
                        mono = obstacleMono,
                        azimuthDeg = obstacleSmoothedAzimuth,
                        leftDelay = obstacleLeftDelay,
                        rightDelay = obstacleRightDelay,
                        leftShadow = obstacleLeftShadow,
                        rightShadow = obstacleRightShadow,
                        sourceGain = 1.15f + 0.35f * sev
                    )

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
                Log.e(TAG, "AudioTrack write error: $written")
                break
            }
        }
    }

    private fun nextRouteSample(routeAzimuth: Float, distanceMeters: Float): Float {
        val absAz = abs(routeAzimuth)
        val alignment = 1f - (absAz / 180f).coerceIn(0f, 1f)

        val intervalMs = lerp(360f, 220f, alignment)
        val beepMs = 52f

        val cycleSamples = max(1L, (intervalMs * SAMPLE_RATE / 1000f).toLong())
        val onSamples = max(1L, (beepMs * SAMPLE_RATE / 1000f).toLong())

        val pos = routeCounter % cycleSamples
        routeCounter++

        if (pos >= onSamples) return 0f

        val env = raisedCosine(pos, onSamples)
        val chirpProgress = pos.toFloat() / onSamples.toFloat()
        val freqA = ROUTE_FREQ_A + 220.0 * chirpProgress
        val freqB = ROUTE_FREQ_B + 120.0 * chirpProgress

        routePhaseA += 2.0 * PI * freqA / SAMPLE_RATE
        routePhaseB += 2.0 * PI * freqB / SAMPLE_RATE
        if (routePhaseA > 2.0 * PI) routePhaseA -= 2.0 * PI
        if (routePhaseB > 2.0 * PI) routePhaseB -= 2.0 * PI

        val click = if (pos < 16) (1f - pos / 16f) else 0f
        val tone =
            0.55f * sin(routePhaseA).toFloat() +
                    0.25f * sin(routePhaseB).toFloat() +
                    0.20f * click

        val distanceGain = (1f / (1f + 0.08f * distanceMeters)).coerceIn(0.55f, 1f)
        return tone * env * 0.36f * distanceGain
    }

    private fun nextObstacleSample(severity: Float): Float {
        val sev = severity.coerceIn(0.15f, 1f)

        val intervalMs = lerp(240f, 100f, sev)
        val burstMs = lerp(32f, 78f, sev)

        val cycleSamples = max(1L, (intervalMs * SAMPLE_RATE / 1000f).toLong())
        val onSamples = max(1L, (burstMs * SAMPLE_RATE / 1000f).toLong())

        val pos = obstacleCounter % cycleSamples
        obstacleCounter++

        if (pos >= onSamples) return 0f

        val env = raisedCosine(pos, onSamples)
        obstaclePhase += 2.0 * PI * OBSTACLE_FREQ / SAMPLE_RATE
        if (obstaclePhase > 2.0 * PI) obstaclePhase -= 2.0 * PI

        val tone = sin(obstaclePhase).toFloat()
        val noise = nextWhiteNoise()
        val attack = if (pos < 10) 1f else 0f

        val mono = 0.50f * tone + 0.35f * noise + 0.15f * attack
        return mono * env * (0.22f + 0.42f * sev)
    }

    private fun renderSource(
        mono: Float,
        azimuthDeg: Float,
        leftDelay: FractionalDelayLine,
        rightDelay: FractionalDelayLine,
        leftShadow: OnePoleLowPass,
        rightShadow: OnePoleLowPass,
        sourceGain: Float
    ): StereoFrame {
        val azRad = Math.toRadians(azimuthDeg.toDouble())
        val pan = sin(azRad).toFloat().coerceIn(-1f, 1f)
        val frontness = cos(azRad).toFloat()

        val baseLeft = sqrt(0.5f * (1f - pan))
        val baseRight = sqrt(0.5f * (1f + pan))

        val absPan = abs(pan)
        val maxItdSamples = MAX_ITD_SEC * SAMPLE_RATE
        val leftDelaySamples = if (pan > 0f) maxItdSamples * absPan else 0f
        val rightDelaySamples = if (pan < 0f) maxItdSamples * absPan else 0f

        var left = leftDelay.process(mono, leftDelaySamples)
        var right = rightDelay.process(mono, rightDelaySamples)

        // 더 강한 head shadow
        val farEarGain = 1f - 0.65f * absPan  // full side일 때 far ear 35%
        val farEarAlpha = 0.24f - 0.18f * absPan // full side일 때 더 강한 low-pass

        when {
            pan > 0f -> {
                left = leftShadow.process(left, farEarAlpha) * farEarGain
                right = rightShadow.bypass(right)
            }
            pan < 0f -> {
                left = leftShadow.bypass(left)
                right = rightShadow.process(right, farEarAlpha) * farEarGain
            }
            else -> {
                left = leftShadow.bypass(left)
                right = rightShadow.bypass(right)
            }
        }

        // 뒤쪽은 약간 더 작고 어둡게
        val backGain = if (frontness >= 0f) 1f else 0.82f

        return StereoFrame(
            left = left * baseLeft * sourceGain * backGain,
            right = right * baseRight * sourceGain * backGain
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

        val bufferSize = max(minBufferSize, BLOCK_FRAMES * CHANNEL_COUNT * BYTES_PER_SAMPLE * 8)

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
            Log.e(TAG, "Failed to create AudioTrack", e)
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
        return x / (1f + 0.35f * abs(x))
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
            val a = alpha.coerceIn(0.04f, 1f)
            y += a * (input - y)
            return y
        }

        fun bypass(input: Float): Float {
            y = input
            return input
        }
    }
}