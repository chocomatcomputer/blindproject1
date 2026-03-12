package com.example.blindproject1.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.File
import javax.inject.Inject

class ObjectDetectorHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloader: ModelDownloader
) {
    private var objectDetector: ObjectDetector? = null
    private var currentThreshold: Float = 0.3f 
    private var maxResults: Int = 10 

    fun setupObjectDetector(threshold: Float = 0.3f) {
        this.currentThreshold = threshold
        
        if (!modelDownloader.modelFile.exists()) {
            Log.e(TAG, "Model file not found. Wait for download.")
            return
        }

        val optionsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
            .setScoreThreshold(currentThreshold)
            .setMaxResults(maxResults)

        val baseOptionsBuilder = BaseOptions.builder().setNumThreads(4)
        optionsBuilder.setBaseOptions(baseOptionsBuilder.build())

        try {
            objectDetector = ObjectDetector.createFromFileAndOptions(
                modelDownloader.modelFile,
                optionsBuilder.build()
            )
            Log.d(TAG, "Model loaded successfully from filesDir with threshold $currentThreshold")
        } catch (e: Exception) {
            Log.e(TAG, "TFLite failed to load model with error: " + e.message)
        }
    }

    fun setThreshold(newThreshold: Float) {
        if (newThreshold != currentThreshold) {
            clearObjectDetector()
            setupObjectDetector(newThreshold)
        }
    }

    fun clearObjectDetector() {
        objectDetector = null
    }

    fun detect(image: Bitmap): List<Detection> {
        if (objectDetector == null) {
            setupObjectDetector(currentThreshold)
        }
        
        if (objectDetector == null) return emptyList()

        try {
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(320, 320, ResizeOp.ResizeMethod.BILINEAR))
                .build()
                
            val argbBitmap = if (image.config == Bitmap.Config.ARGB_8888) {
                image
            } else {
                image.copy(Bitmap.Config.ARGB_8888, true)
            }

            val tensorImage = imageProcessor.process(TensorImage.fromBitmap(argbBitmap))
            
            return objectDetector?.detect(tensorImage) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Detection error", e)
            return emptyList()
        }
    }
    
    companion object {
        private const val TAG = "ObjectDetectorHelper"
    }
}