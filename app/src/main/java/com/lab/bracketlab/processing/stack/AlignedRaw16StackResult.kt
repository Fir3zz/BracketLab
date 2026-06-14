package com.lab.bracketlab.processing.stack

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class AlignedRaw16StackFailureCode {
    EMPTY_STACK,
    INSUFFICIENT_ACCEPTED_FRAMES,
    ALIGNMENT_REPORT_MISMATCH,
    MISSING_ALIGNMENT_RESULT,
    DUPLICATE_FRAME_INDEX,
    INVALID_REFERENCE_RESULT,
    UNSUPPORTED_TRANSFORM_TYPE,
    INVALID_RAW_DIMENSIONS,
    INVALID_RAW_BUFFER,
    OUTPUT_BUFFER_TOO_SMALL,
    INVALID_ROW_STRIDE,
    INVALID_PIXEL_STRIDE,
    INCOMPATIBLE_DIMENSIONS,
    INCOMPATIBLE_CFA,
    INCOMPATIBLE_CAMERA,
    INCOMPATIBLE_ISO,
    INCOMPATIBLE_EXPOSURE,
    INCOMPATIBLE_BLACK_LEVEL,
    INCOMPATIBLE_WHITE_LEVEL,
    DARK_CALIBRATION_FAILED,
    NON_FINITE_TRANSLATION,
    EXCESSIVE_QUANTIZATION_RESIDUAL,
    NO_VALID_SAMPLE
}

enum class AlignedRaw16StackWarningCode {
    TRANSLATION_QUANTIZED_TO_CFA_SAFE_EVEN,
    MISSING_EXPOSURE_METADATA,
    MISSING_ISO_METADATA,
    MISSING_CAMERA_ID,
    MISSING_CFA_METADATA,
    MISSING_BLACK_LEVEL_METADATA,
    MISSING_WHITE_LEVEL_METADATA,
    REDUCED_BORDER_SAMPLE_COUNT,
    FRAME_REJECTED_BY_ALIGNMENT,
    QUANTIZATION_RESIDUAL_PRESENT,
    ROBUST_AGGREGATION_FALLBACK,
    DARK_CALIBRATION_SKIPPED,
    MASTER_DARK_APPLIED
}

data class AlignedRaw16StackWarning(
    val code: AlignedRaw16StackWarningCode,
    val frameIndex: Int? = null,
    val message: String
)

data class CommonOverlapRect(
    val left: Int,
    val top: Int,
    val rightExclusive: Int,
    val bottomExclusive: Int
) {
    val width: Int
        get() = (rightExclusive - left).coerceAtLeast(0)

    val height: Int
        get() = (bottomExclusive - top).coerceAtLeast(0)

    val isEmpty: Boolean
        get() = width == 0 || height == 0
}

data class AlignedRaw16StackResult(
    val success: Boolean,
    val width: Int,
    val height: Int,
    /** Read-only, packed little-endian RAW16 buffer positioned at zero when present. */
    val outputRaw16: ByteBuffer?,
    /** Packed little-endian RAW16 temporary file when output is streamed. */
    val outputRaw16FilePath: String? = null,
    val outputByteOrder: ByteOrder,
    val outputRowStride: Int,
    val outputPixelStride: Int,
    val referenceFrameIndex: Int?,
    val inputFrameCount: Int,
    val acceptedFrameCount: Int,
    val rejectedFrameCount: Int,
    val appliedTranslations: List<AppliedRawTranslation>,
    val minimumValidCount: Int,
    val maximumValidCount: Int,
    val meanValidCount: Double,
    val singleContributorPixelCount: Int,
    val fullContributorPixelCount: Int,
    val commonOverlapRect: CommonOverlapRect?,
    val warnings: List<AlignedRaw16StackWarning>,
    val fatalError: AlignedRaw16StackFailureCode?,
    val fatalMessage: String?,
    val processingDurationMs: Long,
    val options: AlignedRaw16StackOptions,
    val aggregationDiagnostics: RawStackAggregationDiagnostics =
        RawStackAggregationDiagnostics.empty(),
    /** Optional result-owned map of valid sample counts in row-major order. */
    val validCountMap: IntArray? = null,
    val darkCalibrationApplied: Boolean = false,
    val masterDarkId: String? = null,
    val masterDarkFrameCount: Int = 0
) {
    fun outputRaw16Copy(): ByteArray? {
        val source = outputRaw16
        if (source == null) {
            return outputRaw16FilePath?.let { java.io.File(it).takeIf(java.io.File::exists)?.readBytes() }
        }
        val duplicate = source.asReadOnlyBuffer()
        duplicate.position(0)
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }
}
