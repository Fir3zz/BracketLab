package com.lab.bracketlab.processing.stack

import com.lab.bracketlab.processing.calibration.DarkCalibrationInput

enum class MissingMetadataPolicy {
    WARN_AND_CONTINUE,
    REJECT
}

/**
 * Options for the CFA-safe integer-translated RAW16 stack core.
 *
 * This is a normal-stack policy: samples are averaged as encoded RAW16 values,
 * with no exposure normalization or RAW radiance conversion.
 */
data class AlignedRaw16StackOptions(
    val requireSameIso: Boolean = true,
    val requireSameExposure: Boolean = true,
    val exposureRelativeTolerance: Double = 0.001,
    val requireSameCfaPattern: Boolean = true,
    val requireSameDimensions: Boolean = true,
    val requireSameCameraIdWhenKnown: Boolean = true,
    val requireCompatibleBlackLevel: Boolean = true,
    val requireCompatibleWhiteLevel: Boolean = true,
    val missingMetadataPolicy: MissingMetadataPolicy = MissingMetadataPolicy.WARN_AND_CONTINUE,
    val minimumAcceptedFrames: Int = 2,
    val allowSingleFrameStack: Boolean = false,
    val requireCfaSafeEvenTranslation: Boolean = true,
    val maximumQuantizationResidualPixels: Double = 1.5,
    val rejectExcessiveQuantizationResidual: Boolean = false,
    val warnOnQuantization: Boolean = true,
    val debugValidCountMapEnabled: Boolean = false,
    val tileHeight: Int = 128,
    val aggregationOptions: RawStackAggregationOptions = RawStackAggregationOptions(),
    val darkCalibration: DarkCalibrationInput = DarkCalibrationInput.OFF
) {
    init {
        require(exposureRelativeTolerance >= 0.0) { "exposureRelativeTolerance must be non-negative." }
        require(minimumAcceptedFrames >= 1) { "minimumAcceptedFrames must be at least 1." }
        require(maximumQuantizationResidualPixels >= 0.0) {
            "maximumQuantizationResidualPixels must be non-negative."
        }
        require(tileHeight in 1..4096) { "tileHeight must be between 1 and 4096 rows." }
    }
}
