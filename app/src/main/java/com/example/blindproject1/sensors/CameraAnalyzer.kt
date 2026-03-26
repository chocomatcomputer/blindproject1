package com.example.blindproject1.sensors

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.blindproject1.ml.DetectedObject
import com.example.blindproject1.ml.ObjectDetectorHelper

class CameraAnalyzer(
    private val objectDetectorHelper: ObjectDetectorHelper,
    private val onResults: (List<DetectedObject>, Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastAnalyzedTimestamp = 0L
    private val analysisInterval = 150L

    override fun analyze(image: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()
        if (currentTimestamp - lastAnalyzedTimestamp >= analysisInterval) {

            try {
                val bitmap = image.toBitmap()
                val rotationDegrees = image.imageInfo.rotationDegrees.toFloat()
                val rotatedBitmap = if (rotationDegrees != 0f) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap
                }

                // Run detection
                val results = objectDetectorHelper.detect(rotatedBitmap)
                Log.d("CameraAnalyzer", "Detected ${results.size} objects")

                onResults(results, rotatedBitmap)

                lastAnalyzedTimestamp = currentTimestamp
            } catch (e: Exception) {
                Log.e("CameraAnalyzer", "Error analyzing image", e)
            }
        }
        image.close()
    }
}
