package com.example.blindproject1.sensors

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.blindproject1.ml.DetectedObject
import com.example.blindproject1.ml.ObjectDetectorHelper

/**
 * CameraX ImageAnalyzer that feeds frames to our TFLite Object Detector.
 */
class CameraAnalyzer(
    private val objectDetectorHelper: ObjectDetectorHelper,
    private val onResults: (List<DetectedObject>, Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastAnalyzedTimestamp = 0L
    // 기존 500ms(2FPS)에서 150ms(약 6~7FPS)로 줄여서 다가오는 물체를 더 빠르게 캐치함
    private val analysisInterval = 150L

    override fun analyze(image: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()
        if (currentTimestamp - lastAnalyzedTimestamp >= analysisInterval) {

            try {
                // Convert ImageProxy to Bitmap
                val bitmap = image.toBitmap()

                // 중요: 스마트폰을 세로로 들고 있을 때 카메라 프레임이 90도 누워있는 상태로 AI에 들어가면 사람 인식이 안 됨.
                // CameraX의 rotationDegrees를 통해 이미지를 항상 정방향(위가 위로 오게)으로 돌려줌.
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
