package com.lab.bracketlab.processing.hdri.export.dng

enum class LinearRgbFloat16DngFailureCode {
    INVALID_HDR_FRAME,
    INVALID_COLOR_METADATA,
    INVALID_TIFF_TAGS,
    FILE_TOO_LARGE_FOR_CLASSIC_TIFF,
    INSUFFICIENT_STORAGE,
    OUTPUT_IO_FAILURE,
    OUTPUT_COMMIT_FAILED,
    PARTIAL_OUTPUT_CLEANUP_FAILED
}

data class LinearRgbFloat16DngDiagnostics(
    val pixelCount: Long,
    val channelSampleCount: Long,
    val valuesAboveOne: Long,
    val maximumClamps: Long,
    val invalidValueReplacements: Long,
    val negativeClamps: Long,
    val minimumRgb: Double?,
    val maximumRgb: Double?,
    val meanRgb: Double?,
    val inputInvalidValues: Long,
    val inputNegativeClamps: Long,
    val processingDurationMs: Long,
    val maxRgbBeforeScale: Double = 0.0,
    val globalScaleApplied: Double = 1.0,
    val baselineExposureWritten: Double = 0.0,
    val valuesAboveOneBeforeScale: Long = 0L,
    val valuesAboveOneAfterScale: Long = 0L,
    val expectedImageBytes: Long = 0L,
    val sumStripByteCounts: Long = 0L,
    val stripTableValid: Boolean = false
)

data class LinearRgbFloat16DngWriteResult(
    val success: Boolean,
    val outputPath: String? = null,
    val outputBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val rowsPerStrip: Int = 0,
    val stripCount: Int = 0,
    val diagnostics: LinearRgbFloat16DngDiagnostics? = null,
    val warnings: List<String> = emptyList(),
    val failureCode: LinearRgbFloat16DngFailureCode? = null,
    val failureMessage: String? = null,
    val exceptionClass: String? = null,
    val cleanupSucceeded: Boolean = true
)
