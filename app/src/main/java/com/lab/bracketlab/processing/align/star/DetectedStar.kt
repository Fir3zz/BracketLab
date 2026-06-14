package com.lab.bracketlab.processing.align.star

enum class StarFlag {
    LOW_COMPACTNESS,
    NEAR_SATURATION,
    LUMA_FALLBACK
}

data class DetectedStar(
    val id: Int,
    val frameIndex: Int,
    val proxyX: Double,
    val proxyY: Double,
    val fullX: Double,
    val fullY: Double,
    val peak: Double,
    val flux: Double,
    val background: Double,
    val snr: Double,
    val radius: Double,
    val secondMoment: Double,
    val sharpness: Double,
    val saturated: Boolean,
    val detectionQuality: Double,
    val flags: Set<StarFlag> = emptySet()
)

