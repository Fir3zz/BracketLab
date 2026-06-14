package com.lab.bracketlab.processing.align

import com.lab.bracketlab.processing.model.AlignmentResult
import com.lab.bracketlab.processing.raw.RawProxyType

enum class LandscapeAlignmentStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILURE
}

enum class ReferenceSelectionMethod {
    NONE,
    ONLY_FRAME,
    MIDDLE_FRAME_INDEX,
    MEDIAN_EXPOSURE_TIME,
    FALLBACK_MIDDLE_FRAME
}

enum class LandscapeAlignmentRejectionReason {
    EMPTY_STACK,
    INVALID_FRAME_DIMENSIONS,
    INVALID_RAW_BUFFER,
    INVALID_FRAME_INDEX,
    INVALID_CFA_PATTERN,
    INCOMPATIBLE_CAMERA,
    PROXY_GENERATION_FAILED,
    OPENCV_UNAVAILABLE,
    LOW_TEXTURE,
    NON_FINITE_SHIFT,
    INVALID_TRANSFORM_TYPE,
    EXCESSIVE_SHIFT,
    INSUFFICIENT_OVERLAP,
    LOW_PHASE_RESPONSE,
    BACKEND_FAILURE
}

data class ReferenceFrameSelection(
    val selectedPosition: Int,
    val selectedFrameIndex: Int,
    val method: ReferenceSelectionMethod,
    val referenceExposureTimeSeconds: Double?,
    val warnings: List<String> = emptyList(),
    val failureReason: LandscapeAlignmentRejectionReason? = null,
    val message: String? = null
) {
    val success: Boolean
        get() = failureReason == null
}

data class LandscapeFrameAlignment(
    val framePosition: Int,
    val frameIndex: Int,
    val isReference: Boolean,
    val proxyTypeUsed: RawProxyType?,
    val lumaFallbackUsed: Boolean,
    val targetExposureTimeSeconds: Double?,
    val referenceExposureTimeSeconds: Double?,
    val phaseResponse: Double?,
    val dxRawPixels: Double,
    val dyRawPixels: Double,
    val overlapFraction: Double?,
    val accepted: Boolean,
    val lowConfidence: Boolean,
    val rejectionReason: LandscapeAlignmentRejectionReason?,
    val diagnosticMessage: String?,
    val warnings: List<String>,
    val alignmentResult: AlignmentResult
)

data class LandscapeAlignmentReport(
    val status: LandscapeAlignmentStatus,
    val success: Boolean,
    val partialSuccess: Boolean,
    val selectedReferenceFrameIndex: Int?,
    val selectedReferencePosition: Int?,
    val referenceSelectionMethod: ReferenceSelectionMethod,
    val referenceExposureTimeSeconds: Double?,
    val totalFrameCount: Int,
    val acceptedFrameCount: Int,
    val rejectedFrameCount: Int,
    val lowConfidenceFrameCount: Int,
    val frameResults: List<LandscapeFrameAlignment>,
    val alignmentResults: List<AlignmentResult>,
    val warnings: List<String>,
    val fatalError: LandscapeAlignmentRejectionReason?,
    val fatalMessage: String?,
    val processingDurationMs: Long,
    val options: LandscapeAlignmentOptions
)
