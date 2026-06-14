package com.lab.bracketlab.processing.align.star

import com.lab.bracketlab.processing.raw.RawProxyType

/**
 * Coordinates use top-left origin, x increasing right and y increasing down.
 * Integer coordinates address pixel centers. Full-resolution coordinates use
 * fullX = proxyX * scaleX and fullY = proxyY * scaleY.
 */
data class StarCatalog(
    val frameIndex: Int,
    val sourceTimestampNs: Long?,
    val proxyType: RawProxyType,
    val proxyWidth: Int,
    val proxyHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scaleX: Double,
    val scaleY: Double,
    val exposureNormalized: Boolean,
    val backgroundEstimate: Double,
    val noiseEstimate: Double,
    val thresholdUsed: Double,
    val thresholdSigma: Double,
    val localMaximumCount: Int,
    val stars: List<DetectedStar>,
    val rejectedCandidateCounts: Map<StarCandidateRejectionReason, Int>,
    val warnings: List<String>,
    val statusCode: StarDetectionFailureCode?,
    val durationMs: Long
) {
    val starCount: Int
        get() = stars.size
}

