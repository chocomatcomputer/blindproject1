package com.example.blindproject1.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Esp32GlassesRepository @Inject constructor(
    private val client: OkHttpClient
) {

    /**
     * Robust camera frame source:
     * 1) Try raw MJPEG candidates
     * 2) If none yield a frame quickly, fallback to repeated /capture JPEG polling
     */
    fun cameraFrames(cameraUrl: String): Flow<Bitmap> = flow {
        val streamCandidates = buildStreamCandidates(cameraUrl)
        val captureCandidates = buildCaptureCandidates(cameraUrl)

        Log.d(TAG, "Stream candidates: $streamCandidates")
        Log.d(TAG, "Capture candidates: $captureCandidates")

        var success = false

        for (candidate in streamCandidates) {
            val ok = openMjpegStream(
                streamUrl = candidate,
                firstFrameTimeoutMs = 2500L
            ) { bitmap ->
                emit(bitmap)
            }

            if (ok) {
                success = true
                break
            }
        }

        if (!success) {
            for (candidate in captureCandidates) {
                val ok = pollJpegFrames(candidate) { bitmap ->
                    emit(bitmap)
                }
                if (ok) {
                    success = true
                    break
                }
            }
        }

        if (!success) {
            Log.e(TAG, "No usable camera endpoint found from input: $cameraUrl")
        }
    }.flowOn(Dispatchers.IO)

    private fun buildStreamCandidates(cameraUrl: String): List<String> {
        val trimmed = cameraUrl.trim().trimEnd('/')

        val uri = runCatching { URI(trimmed) }.getOrNull()
        val scheme = uri?.scheme ?: "http"
        val host = uri?.host
        val port = uri?.port ?: -1

        val list = mutableListOf<String>()

        fun add(url: String) {
            val normalized = url.trim().trimEnd('/')
            if (normalized.isNotBlank() && normalized !in list) {
                list.add(normalized)
            }
        }

        if (trimmed.endsWith("/stream")) {
            add(trimmed)
        } else {
            add("$trimmed/stream")
            add(trimmed)
        }

        if (host != null) {
            if (port != -1) {
                add("$scheme://$host:$port/stream")
                add("$scheme://$host:$port")
            }
            add("$scheme://$host/stream")
            add("$scheme://$host")
        }

        return list
    }

    private fun buildCaptureCandidates(cameraUrl: String): List<String> {
        val trimmed = cameraUrl.trim().trimEnd('/')

        val uri = runCatching { URI(trimmed) }.getOrNull()
        val scheme = uri?.scheme ?: "http"
        val host = uri?.host
        val port = uri?.port ?: -1

        val list = mutableListOf<String>()

        fun add(url: String) {
            val normalized = url.trim().trimEnd('/')
            if (normalized.isNotBlank() && normalized !in list) {
                list.add(normalized)
            }
        }

        if (trimmed.endsWith("/capture")) {
            add(trimmed)
        } else {
            add("$trimmed/capture")
        }

        if (host != null) {
            if (port != -1) {
                add("$scheme://$host:$port/capture")
            }
            add("$scheme://$host/capture")
        }

        return list
    }

    /**
     * Returns true if at least one frame was emitted.
     */
    private suspend fun openMjpegStream(
        streamUrl: String,
        firstFrameTimeoutMs: Long,
        emitFrame: suspend (Bitmap) -> Unit
    ): Boolean {
        val request = Request.Builder()
            .url(streamUrl)
            .get()
            .build()

        val call = client.newCall(request)
        val job = currentCoroutineContext()[Job]

        job?.invokeOnCompletion {
            call.cancel()
        }

        var emitted = false
        val startTime = System.currentTimeMillis()

        try {
            call.execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty()
                Log.d(
                    TAG,
                    "Trying MJPEG stream URL=$streamUrl code=${response.code} contentType=$contentType"
                )

                if (!response.isSuccessful) {
                    return false
                }

                val body = response.body ?: return false
                val input = body.byteStream()

                val frameBuffer = ByteArrayOutputStream(128 * 1024)
                val chunk = ByteArray(4096)

                var previous = -1
                var collecting = false

                while (job?.isActive != false) {
                    if (!emitted && System.currentTimeMillis() - startTime > firstFrameTimeoutMs) {
                        Log.w(TAG, "No first MJPEG frame within ${firstFrameTimeoutMs}ms: $streamUrl")
                        return false
                    }

                    val read = input.read(chunk)
                    if (read == -1) break

                    for (i in 0 until read) {
                        val b = chunk[i].toInt() and 0xFF

                        if (!collecting) {
                            if (previous == 0xFF && b == 0xD8) {
                                frameBuffer.reset()
                                frameBuffer.write(0xFF)
                                frameBuffer.write(0xD8)
                                collecting = true
                                previous = -1
                                continue
                            }
                        } else {
                            frameBuffer.write(b)

                            if (previous == 0xFF && b == 0xD9) {
                                val jpegBytes = frameBuffer.toByteArray()
                                val bitmap = BitmapFactory.decodeByteArray(
                                    jpegBytes,
                                    0,
                                    jpegBytes.size
                                )

                                if (bitmap != null) {
                                    emitted = true
                                    emitFrame(bitmap)
                                } else {
                                    Log.w(TAG, "Failed to decode MJPEG frame from $streamUrl")
                                }

                                frameBuffer.reset()
                                collecting = false
                                previous = -1
                                continue
                            }
                        }

                        previous = b
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MJPEG stream error at $streamUrl", e)
        }

        return emitted
    }

    /**
     * Returns true if at least one JPEG frame was emitted.
     * This is a fallback when MJPEG is unavailable or HTML page is returned instead.
     */
    private suspend fun pollJpegFrames(
        captureUrl: String,
        emitFrame: suspend (Bitmap) -> Unit
    ): Boolean {
        val job = currentCoroutineContext()[Job]
        var emitted = false
        var consecutiveFailures = 0

        Log.d(TAG, "Trying JPEG polling fallback: $captureUrl")

        while (job?.isActive != false) {
            val bitmap = fetchCaptureBitmap(captureUrl)
            if (bitmap != null) {
                emitted = true
                consecutiveFailures = 0
                emitFrame(bitmap)
            } else {
                consecutiveFailures++
                Log.w(TAG, "JPEG polling failed ($consecutiveFailures): $captureUrl")
                if (!emitted && consecutiveFailures >= 3) {
                    return false
                }
            }

            delay(120)
        }

        return emitted
    }

    private suspend fun fetchCaptureBitmap(captureUrl: String): Bitmap? {
        val request = Request.Builder()
            .url(captureUrl)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty()
                Log.d(
                    TAG,
                    "Trying capture URL=$captureUrl code=${response.code} contentType=$contentType"
                )

                if (!response.isSuccessful) {
                    return null
                }

                val bytes = response.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture error at $captureUrl", e)
            null
        }
    }

    suspend fun fetchYaw(dataUrl: String): Float? {
        val request = Request.Builder()
            .url(dataUrl.trim())
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "yaw failed: ${response.code}")
                    return null
                }

                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val yaw = json.optDouble("yaw", Double.NaN)
                if (yaw.isNaN()) null else yaw.toFloat()
            }
        } catch (e: Exception) {
            Log.e(TAG, "yaw error", e)
            null
        }
    }

    companion object {
        private const val TAG = "Esp32GlassesRepo"
    }
}