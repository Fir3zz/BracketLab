package com.lab.bracketlab.processing.calibration

import com.lab.bracketlab.processing.stack.RawStackAggregationDiagnostics
import com.lab.bracketlab.processing.stack.RawStackAggregationMode

enum class DarkCalibrationFailureCode {
    EMPTY_DARK_STACK,
    INSUFFICIENT_DARK_FRAMES,
    INCOMPATIBLE_DARK_DIMENSIONS,
    INCOMPATIBLE_DARK_CFA,
    INCOMPATIBLE_DARK_CAMERA,
    INCOMPATIBLE_DARK_ISO,
    INCOMPATIBLE_DARK_EXPOSURE,
    INCOMPATIBLE_DARK_BLACK_LEVEL,
    INCOMPATIBLE_DARK_WHITE_LEVEL,
    INVALID_DARK_RAW_STORAGE,
    DARK_PROCESSING_FAILED,
    DARK_TEMP_FILE_FAILURE,
    MASTER_DARK_NOT_FOUND,
    MASTER_DARK_INCOMPATIBLE,
    MASTER_DARK_STORAGE_FAILED,
    INSUFFICIENT_STORAGE
}

data class DarkCalibrationResult(
    val success: Boolean,
    val masterDark: MasterDark? = null,
    val failureCode: DarkCalibrationFailureCode? = null,
    val failureMessage: String? = null,
    val warnings: List<String> = emptyList(),
    val darkFrameCount: Int = 0,
    val aggregationMode: RawStackAggregationMode? = null,
    val aggregationDiagnostics: RawStackAggregationDiagnostics? = null,
    val minimumDarkValue: Int? = null,
    val maximumDarkValue: Int? = null,
    val meanDarkValue: Double? = null,
    val processingDurationMs: Long = 0L,
    val temporaryCleanupSucceeded: Boolean = true,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null
) {
    companion object {
        fun failure(
            code: DarkCalibrationFailureCode,
            message: String,
            frameCount: Int = 0,
            startedNs: Long? = null,
            exception: Throwable? = null,
            cleanupSucceeded: Boolean = true
        ): DarkCalibrationResult =
            DarkCalibrationResult(
                success = false,
                failureCode = code,
                failureMessage = message,
                darkFrameCount = frameCount,
                processingDurationMs =
                    startedNs?.let { (System.nanoTime() - it) / 1_000_000L } ?: 0L,
                temporaryCleanupSucceeded = cleanupSucceeded,
                exceptionClass = exception?.javaClass?.simpleName,
                exceptionMessage = exception?.message
            )
    }
}
