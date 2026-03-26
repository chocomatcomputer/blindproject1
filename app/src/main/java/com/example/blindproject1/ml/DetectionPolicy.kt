package com.example.blindproject1.ml

data class DetectionPolicy(
    val importantLabels: Set<String> = setOf(
        "person",
        "car",
        "motorcycle",
        "bicycle",
        "truck",
        "bus",
        "traffic light",
        "bench",
        "chair"
    ),
    val minConfidence: Float = 0.35f,
    val nearObjectHeightFraction: Float = 0.15f,
    val trafficLightHeightFraction: Float = 0.10f
)
