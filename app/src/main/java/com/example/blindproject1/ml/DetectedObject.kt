package com.example.blindproject1.ml

import android.graphics.RectF

/**
 * Model-agnostic detection result used by app layers.
 */
data class DetectedObject(
    val label: String,
    val score: Float,
    val boundingBox: RectF
)

