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
    // Official EfficientDet-Lite0 model URL from TensorFlow Hub
    private val modelUrl = "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite"
    val modelFile = File(context.filesDir, "efficientdet_lite0.tflite")

    suspend fun downloadModelIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (modelFile.exists() && modelFile.length() > 1000000) {
            Log.d("ModelDownloader", "Model already exists. Size: ${modelFile.length()}")
            return@withContext true
        }

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