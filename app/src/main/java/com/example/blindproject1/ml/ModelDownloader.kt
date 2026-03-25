package com.example.blindproject1.ml

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelDownloader @Inject constructor(
    private val context: Context
) {
    // Optional remote URL. Local asset copy is attempted first for stable MVP usage.
    private val modelUrl = "https://github.com/ultralytics/assets/releases/download/v0.0.0/yolov8n_int8.tflite"
    private val modelAssetName = "yolov8n_int8.tflite"
    val modelFile = File(context.filesDir, modelAssetName)

    suspend fun downloadModelIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (modelFile.exists() && modelFile.length() > 500000) {
            Log.d("ModelDownloader", "Model already exists. Size: ${modelFile.length()}")
            return@withContext true
        }

        // 1) Prefer local bundled asset for deterministic startup.
        try {
            context.assets.open(modelAssetName).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (modelFile.exists() && modelFile.length() > 500000) {
                Log.d("ModelDownloader", "Model copied from assets successfully.")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.w("ModelDownloader", "Asset model not found. Fallback to remote download.", e)
        }

        // 2) Fallback to remote download.
        Log.d("ModelDownloader", "Downloading model from $modelUrl")
        val client = OkHttpClient()
        val request = Request.Builder().url(modelUrl).build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d("ModelDownloader", "Model downloaded successfully.")
                return@withContext true
            } else {
                Log.e("ModelDownloader", "Failed to download model: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("ModelDownloader", "Error downloading model", e)
        }
        return@withContext false
    }
}
