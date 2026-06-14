package com.lab.bracketlab.processing.align.star

import com.lab.bracketlab.processing.model.AlignmentResult

enum class StarAlignmentStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILURE
}

data class StarReferenceSelection(
    val success: Boolean,
    val catalogPosition: Int,
    val frameIndex: Int?,
    val reason: StarReferenceSelectionReason?,
    val starCount: Int,
    val eligibleStarCount: Int,
    val medianSnr: Double?,
    val warnings: List<String> = emptyList(),
    val failureCode: StarMatchingFailureCode? = null,
    val message: String? = null
)

data class StarSpatialDistribution(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
    val boundingBoxCoverageFraction: Double,
    val occupiedGridCells: Int,
    val totalGridCells: Int,
    val geometryEigenvalueRatio: Double,
    val degenerate: Boolean
)

data class StarFrameAlignment(
    val framePosition: Int,
    val frameIndex: Int,
    val isReference: Boolean,
    val detectedStarCount: Int,
    val eligibleStarCount: Int,
    val retainedStarCount: Int,
    val candidateMatchCount: Int,
    val ransacInlierCount: Int,
    val outlierCount: Int,
    val inlierRatio: Double,
    val rmsResidualRawPixels: Double?,
    val medianResidualRawPixels: Double?,
    val maximumResidualRawPixels: Double?,
    val transform: StarSimilarityTransform,
    val matchingStrategy: StarMatchingStrategy,
    val spatialDistribution: StarSpatialDistribution?,
    val matches: List<StarMatch>,
    val accepted: Boolean,
    val failureCode: StarMatchingFailureCode?,
    val diagnosticMessage: String?,
    val warnings: List<String>,
    val alignmentResult: AlignmentResult
)

data class StarAlignmentReport(
    val status: StarAlignmentStatus,
    val success: Boolean,
    val partialSuccess: Boolean,
    val referenceFrameIndex: Int?,
    val referenceCatalogPosition: Int?,
    val referenceSelectionReason: StarReferenceSelectionReason?,
    val referenceStarCount: Int,
    val referenceMedianSnr: Double?,
    val totalFrameCount: Int,
    val acceptedFrameCount: Int,
    val rejectedFrameCount: Int,
    val frameResults: List<StarFrameAlignment>,
    val alignmentResults: List<AlignmentResult>,
    val warnings: List<String>,
    val fatalError: StarMatchingFailureCode?,
    val fatalMessage: String?,
    val durationMs: Long,
    val options: StarMatchingOptions
)
