package com.example.blindproject1.ml

import android.graphics.RectF

data class DetectedObject(
    val label: String,
    val score: Float,
    val boundingBox: RectF
)

