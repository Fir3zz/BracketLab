package com.lab.bracketlab.processing.align.star

/**
 * Provisional thresholds for short fixed-camera star sequences.
 *
 * All geometric distances are expressed in full-resolution RAW pixels.
 */
data class StarMatchingOptions(
    val minimumEligibleStars: Int = 4,
    val minimumStarSnr: Double = 5.0,
    val minimumStarRadius: Double = 0.10,
    val maximumStarRadius: Double = 8.0,
    val minimumSharpness: Double = 0.0,
    val edgeMarginFraction: Double = 0.01,
    val maximumRetainedStars: Int = 200,
    val smallMotionSearchRadiusRawPixels: Double = 180.0,
    val smallMotionAmbiguityRatio: Double = 0.80,
    val minimumSmallMotionMatches: Int = 4,
    val patternStarLimit: Int = 48,
    val patternNearestNeighbors: Int = 7,
    val maximumPatternTriangles: Int = 3_000,
    val triangleRatioTolerance: Double = 0.025,
    val triangleScaleToleranceFraction: Double = 0.15,
    val geometricMatchRadiusRawPixels: Double = 18.0,
    val reprojectionThresholdRawPixels: Double = 6.0,
    val ransacMaxIterations: Int = 1_000,
    val ransacConfidence: Double = 0.995,
    val minimumInliers: Int = 4,
    val minimumInlierRatio: Double = 0.35,
    val maximumRmsErrorRawPixels: Double = 4.0,
    val maximumAbsoluteRotationDegrees: Double = 15.0,
    val minimumScale: Double = 0.90,
    val maximumScale: Double = 1.10,
    val maximumTranslationFractionX: Double = 0.50,
    val maximumTranslationFractionY: Double = 0.50,
    val minimumSpatialCoverageFraction: Double = 0.002,
    val spatialGridColumns: Int = 3,
    val spatialGridRows: Int = 3,
    val minimumOccupiedGridCells: Int = 2,
    val minimumGeometryEigenvalueRatio: Double = 0.002,
    val preserveInputOrder: Boolean = true,
    val diagnosticsEnabled: Boolean = true
) {
    init {
        require(minimumEligibleStars >= 2)
        require(minimumStarSnr >= 0.0 && minimumStarSnr.isFinite())
        require(minimumStarRadius >= 0.0 && minimumStarRadius.isFinite())
        require(maximumStarRadius >= minimumStarRadius && maximumStarRadius.isFinite())
        require(minimumSharpness >= 0.0 && minimumSharpness.isFinite())
        require(edgeMarginFraction in 0.0..0.25)
        require(maximumRetainedStars >= minimumEligibleStars)
        require(smallMotionSearchRadiusRawPixels > 0.0)
        require(smallMotionAmbiguityRatio in 0.0..1.0)
        require(minimumSmallMotionMatches >= 2)
        require(patternStarLimit >= 3)
        require(patternNearestNeighbors >= 2)
        require(maximumPatternTriangles > 0)
        require(triangleRatioTolerance > 0.0)
        require(triangleScaleToleranceFraction >= 0.0)
        require(geometricMatchRadiusRawPixels > 0.0)
        require(reprojectionThresholdRawPixels > 0.0)
        require(ransacMaxIterations > 0)
        require(ransacConfidence > 0.0 && ransacConfidence < 1.0)
        require(minimumInliers >= 2)
        require(minimumInlierRatio in 0.0..1.0)
        require(maximumRmsErrorRawPixels > 0.0)
        require(maximumAbsoluteRotationDegrees >= 0.0)
        require(minimumScale > 0.0 && maximumScale >= minimumScale)
        require(maximumTranslationFractionX > 0.0)
        require(maximumTranslationFractionY > 0.0)
        require(minimumSpatialCoverageFraction >= 0.0)
        require(spatialGridColumns > 0 && spatialGridRows > 0)
        require(minimumOccupiedGridCells > 0)
        require(minimumGeometryEigenvalueRatio >= 0.0)
    }
}
