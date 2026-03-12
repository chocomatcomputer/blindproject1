package com.example.blindproject1.ml

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import javax.inject.Inject

class ObjectDetectorHelper @Inject constructor(
    private val modelDownloader: ModelDownloader
) {
    private var interpreter: Interpreter? = null
    private var currentThreshold: Float = 0.3f 
    private var maxResults: Int = 20
    private val inputSize = 640
    private val numChannels = 3
    private val modelInputBytes = 4 // Float32
    private val iouThreshold = 0.45f

    fun setupObjectDetector(threshold: Float = 0.3f) {
        this.currentThreshold = threshold
        
        if (!modelDownloader.modelFile.exists()) {
            Log.e(TAG, "Model file not found. Wait for download.")
            return
        }

        try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelDownloader.modelFile, options)
            Log.d(TAG, "YOLO model loaded successfully with threshold $currentThreshold")
        } catch (e: Exception) {
            Log.e(TAG, "TFLite failed to load YOLO model with error: " + e.message)
        }
    }

    fun setThreshold(newThreshold: Float) {
        if (newThreshold != currentThreshold) {
            clearObjectDetector()
            setupObjectDetector(newThreshold)
        }
    }

    fun clearObjectDetector() {
        interpreter?.close()
        interpreter = null
    }

    fun detect(image: Bitmap): List<DetectedObject> {
        if (interpreter == null) {
            setupObjectDetector(currentThreshold)
        }
        
        val tflite = interpreter ?: return emptyList()

        try {
            val argbBitmap = if (image.config == Bitmap.Config.ARGB_8888) {
                image
            } else {
                image.copy(Bitmap.Config.ARGB_8888, true)
            }
            val resized = Bitmap.createScaledBitmap(argbBitmap, inputSize, inputSize, true)
            val inputBuffer = bitmapToInputBuffer(resized)

            val outputTensor = tflite.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            if (outputShape.size != 3) {
                Log.e(TAG, "Unexpected YOLO output shape: ${outputShape.contentToString()}")
                return emptyList()
            }

            val out0 = outputShape[1]
            val out1 = outputShape[2]
            val outputType = outputTensor.dataType()
            val outputBuffer = ByteBuffer.allocateDirect(
                out0 * out1 * bytesPerType(outputType)
            ).order(ByteOrder.nativeOrder())
            tflite.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            val output = parseOutputToMatrix(outputBuffer, out0, out1, outputType, outputTensor.quantizationParams().scale, outputTensor.quantizationParams().zeroPoint)

            val detections = decodeYoloOutput(output, image.width.toFloat(), image.height.toFloat())
            return nonMaxSuppression(detections, iouThreshold).take(maxResults)
        } catch (e: Exception) {
            Log.e(TAG, "Detection error", e)
            return emptyList()
        }
    }

    private fun bitmapToInputBuffer(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(
            inputSize * inputSize * numChannels * modelInputBytes
        ).order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        var idx = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val px = pixels[idx++]
                val r = ((px shr 16) and 0xFF) / 255f
                val g = ((px shr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f
                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }
        }
        inputBuffer.rewind()
        return inputBuffer
    }

    private fun decodeYoloOutput(
        output: Array<FloatArray>,
        originalWidth: Float,
        originalHeight: Float
    ): List<DetectedObject> {
        val detections = mutableListOf<DetectedObject>()
        if (output.isEmpty()) return detections

        // Common YOLOv8 TFLite layouts:
        // - [84, 8400]   : output[0].size likely > 1000
        // - [8400, 84]
        val channelsFirst = output.size <= 128

        if (channelsFirst) {
            val channels = output.size
            if (channels < 6) return detections
            val boxes = output[0].size
            for (i in 0 until boxes) {
                val cx = output[0][i]
                val cy = output[1][i]
                val w = output[2][i]
                val h = output[3][i]
                val clsIdx = argMaxClassChannels(output, i, 4, channels)
                val score = output[4 + clsIdx][i]
                if (score < currentThreshold) continue
                val label = COCO_LABELS.getOrElse(clsIdx) { "class_$clsIdx" }
                detections.add(
                    DetectedObject(
                        label = label,
                        score = score,
                        boundingBox = toOriginalRect(cx, cy, w, h, originalWidth, originalHeight)
                    )
                )
            }
        } else {
            val boxes = output.size
            for (i in 0 until boxes) {
                val row = output[i]
                if (row.size < 6) continue
                val cx = row[0]
                val cy = row[1]
                val w = row[2]
                val h = row[3]
                val clsIdx = argMax(row, 4, row.size)
                val score = row[clsIdx]
                if (score < currentThreshold) continue
                val normalizedClassIdx = clsIdx - 4
                val label = COCO_LABELS.getOrElse(normalizedClassIdx) { "class_$normalizedClassIdx" }
                detections.add(
                    DetectedObject(
                        label = label,
                        score = score,
                        boundingBox = toOriginalRect(cx, cy, w, h, originalWidth, originalHeight)
                    )
                )
            }
        }

        return detections
    }

    private fun toOriginalRect(
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        originalWidth: Float,
        originalHeight: Float
    ): RectF {
        // Some YOLO exports emit normalized [0..1] boxes, while others emit input-space pixels.
        val normalized = cx <= 2f && cy <= 2f && w <= 2f && h <= 2f
        val cxInput = if (normalized) cx * inputSize else cx
        val cyInput = if (normalized) cy * inputSize else cy
        val wInput = if (normalized) w * inputSize else w
        val hInput = if (normalized) h * inputSize else h

        val scaleX = originalWidth / inputSize
        val scaleY = originalHeight / inputSize

        val left = (cxInput - wInput / 2f) * scaleX
        val top = (cyInput - hInput / 2f) * scaleY
        val right = (cxInput + wInput / 2f) * scaleX
        val bottom = (cyInput + hInput / 2f) * scaleY

        return RectF(
            left.coerceIn(0f, originalWidth),
            top.coerceIn(0f, originalHeight),
            right.coerceIn(0f, originalWidth),
            bottom.coerceIn(0f, originalHeight)
        )
    }

    private fun nonMaxSuppression(
        detections: List<DetectedObject>,
        iouThreshold: Float
    ): List<DetectedObject> {
        if (detections.isEmpty()) return emptyList()
        val result = mutableListOf<DetectedObject>()
        val byClass = detections.groupBy { it.label }

        byClass.values.forEach { classDetections ->
            val sorted = classDetections.sortedByDescending { it.score }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                result.add(best)
                sorted.removeAll { candidate ->
                    iou(best.boundingBox, candidate.boundingBox) >= iouThreshold
                }
            }
        }
        return result.sortedByDescending { it.score }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interWidth = (interRight - interLeft).coerceAtLeast(0f)
        val interHeight = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interWidth * interHeight
        if (interArea <= 0f) return 0f

        val areaA = (a.width().coerceAtLeast(0f)) * (a.height().coerceAtLeast(0f))
        val areaB = (b.width().coerceAtLeast(0f)) * (b.height().coerceAtLeast(0f))
        val union = areaA + areaB - interArea
        if (union <= 0f) return 0f
        return interArea / union
    }

    private fun argMax(values: FloatArray, start: Int, end: Int): Int {
        var maxIdx = start
        var maxVal = Float.NEGATIVE_INFINITY
        for (i in start until end) {
            if (values[i] > maxVal) {
                maxVal = values[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    private fun argMaxClassChannels(
        output: Array<FloatArray>,
        boxIndex: Int,
        classStartChannel: Int,
        channelEndExclusive: Int
    ): Int {
        var classIdx = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (channel in classStartChannel until channelEndExclusive) {
            val score = output[channel][boxIndex]
            if (score > bestScore) {
                bestScore = score
                classIdx = channel - classStartChannel
            }
        }
        return classIdx
    }

    private fun bytesPerType(dataType: DataType): Int {
        return when (dataType) {
            DataType.FLOAT32 -> 4
            DataType.INT32 -> 4
            DataType.INT8 -> 1
            DataType.UINT8 -> 1
            else -> 4
        }
    }

    private fun parseOutputToMatrix(
        buffer: ByteBuffer,
        rows: Int,
        cols: Int,
        dataType: DataType,
        scale: Float,
        zeroPoint: Int
    ): Array<FloatArray> {
        val output = Array(rows) { FloatArray(cols) }

        when (dataType) {
            DataType.FLOAT32 -> {
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        output[r][c] = buffer.float
                    }
                }
            }

            DataType.INT8 -> {
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val q = buffer.get().toInt()
                        output[r][c] = (q - zeroPoint) * scale
                    }
                }
            }

            DataType.UINT8 -> {
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val q = buffer.get().toInt() and 0xFF
                        output[r][c] = (q - zeroPoint) * scale
                    }
                }
            }

            else -> {
                throw IllegalStateException("Unsupported YOLO output tensor type: $dataType")
            }
        }

        return output
    }
    
    companion object {
        private const val TAG = "ObjectDetectorHelper"
        private val COCO_LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
            "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
            "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
            "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed",
            "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven",
            "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
    }
}
