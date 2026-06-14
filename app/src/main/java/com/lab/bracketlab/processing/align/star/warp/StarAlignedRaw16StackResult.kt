package com.lab.bracketlab.processing.align.star.warp

import com.lab.bracketlab.processing.stack.AlignedRaw16StackOptions
import com.lab.bracketlab.processing.stack.AlignedRaw16StackResult
import com.lab.bracketlab.processing.stack.RawStackAggregationDiagnostics
import java.nio.ByteOrder

enum class StarAlignedRaw16FailureCode {
    EMPTY_STAR_STACK,
    INVALID_STAR_ALIGNMENT_REPORT,
    REFERENCE_FRAME_MISMATCH,
    MISSING_FRAME_TRANSFORM,
    DUPLICATE_FRAME_TRANSFORM,
    UNSUPPORTED_TRANSFORM,
    NON_FINITE_TRANSFORM,
    NON_INVERTIBLE_TRANSFORM,
    INCOMPATIBLE_DIMENSIONS,
    INCOMPATIBLE_CFA,
    INCOMPATIBLE_CAMERA,
    INCOMPATIBLE_ISO,
    INCOMPATIBLE_EXPOSURE,
    INVALID_RAW_STORAGE,
    INSUFFICIENT_ACCEPTED_FRAMES,
    SOURCE_WINDOW_READ_FAILED,
    CFA_MAPPING_FAILED,
    WARP_PROCESSING_FAILED,
    OUTPUT_TEMP_FILE_FAILED,
    DNG_WRITE_FAILED
}

enum class StarAlignedRaw16WarningCode {
    FRAME_REJECTED_BY_STAR_ALIGNMENT,
    REDUCED_ROTATED_BORDER_COVERAGE,
    REFERENCE_ONLY_BORDER_PIXELS,
    LOW_VALID_SOURCE_FRACTION,
    MASTER_DARK_NOT_FOUND,
    MASTER_DARK_APPLIED,
    ROBUST_AGGREGATION_FALLBACK,
    TILE_SIZE_REDUCED_FOR_MEMORY_BUDGET
}

data class StarAlignedRaw16Warning(
    val code: StarAlignedRaw16WarningCode,
    val frameIndex: Int? = null,
    val message: String
)

data class StarFrameWarpDiagnostics(
    val frameIndex: Int,
    val reference: Boolean,
    val validSamples: Long,
    val invalidSamples: Long,
    val validFraction: Double,
    val directSamples: Long,
    val horizontalLinearSamples: Long,
    val verticalLinearSamples: Long,
    val bilinearSamples: Long
)

data class StarAlignedRaw16StackResult(
    val success: Boolean,
    val width: Int,
    val height: Int,
    val outputRaw16FilePath: String?,
    val referenceFrameIndex: Int?,
    val inputFrameCount: Int,
    val acceptedFrameCount: Int,
    val rejectedFrameCount: Int,
    val minimumValidCount: Int,
    val maximumValidCount: Int,
    val meanValidCount: Double,
    val referenceOnlyPixelCount: Long,
    val fullContributorPixelCount: Long,
    val frameDiagnostics: List<StarFrameWarpDiagnostics>,
    val aggregationDiagnostics: RawStackAggregationDiagnostics,
    val tilePlan: StarTilePlan?,
    val warnings: List<StarAlignedRaw16Warning>,
    val fatalError: StarAlignedRaw16FailureCode?,
    val fatalMessage: String?,
    val processingDurationMs: Long,
    val options: StarAlignedRaw16StackOptions,
    val darkCalibrationApplied: Boolean = false,
    val masterDarkId: String? = null,
    val temporaryCleanupSucceeded: Boolean = true
) {
    fun asWriterCompatibleResult(): AlignedRaw16StackResult =
        AlignedRaw16StackResult(
            success = success,
            width = width,
            height = height,
            outputRaw16 = null,
            outputRaw16FilePath = outputRaw16FilePath,
            outputByteOrder = ByteOrder.LITTLE_ENDIAN,
            outputRowStride = width * 2,
            outputPixelStride = 2,
            referenceFrameIndex = referenceFrameIndex,
            inputFrameCount = inputFrameCount,
            acceptedFrameCount = acceptedFrameCount,
            rejectedFrameCount = rejectedFrameCount,
            appliedTranslations = emptyList(),
            minimumValidCount = minimumValidCount,
            maximumValidCount = maximumValidCount,
            meanValidCount = meanValidCount,
            singleContributorPixelCount = referenceOnlyPixelCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            fullContributorPixelCount = fullContributorPixelCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            commonOverlapRect = null,
            warnings = emptyList(),
            fatalError = null,
            fatalMessage = fatalMessage,
            processingDurationMs = processingDurationMs,
            options =
                AlignedRaw16StackOptions(
                    minimumAcceptedFrames = options.minimumAcceptedFrames,
                    aggregationOptions = options.aggregationOptions,
                    darkCalibration = options.darkCalibration
                ),
            aggregationDiagnostics = aggregationDiagnostics,
            validCountMap = null,
            darkCalibrationApplied = darkCalibrationApplied,
            masterDarkId = masterDarkId
        )
}
