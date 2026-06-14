package com.lab.bracketlab.processing.calibration

import com.lab.bracketlab.processing.stack.RawStackAggregationMode
import com.lab.bracketlab.processing.stack.RawStackAggregationOptions

enum class DarkPolicy {
    OFF,
    USE_COMPATIBLE_MASTER_DARK,
    CAPTURE_MASTER_DARK
}

enum class DarkAggregationPolicy {
    AUTO,
    MEAN,
    MIN_MAX_REJECTED_MEAN,
    SIGMA_CLIPPED_MEAN
}

enum class MissingMasterDarkBehavior {
    WARN_AND_CONTINUE,
    FAIL
}

data class DarkCalibrationOptions(
    val darkPolicy: DarkPolicy = DarkPolicy.OFF,
    val aggregationPolicy: DarkAggregationPolicy = DarkAggregationPolicy.AUTO,
    val minimumDarkFrames: Int = 1,
    val allowSingleDarkFrame: Boolean = true,
    val exposureRelativeTolerance: Double = 0.001,
    val exposureAbsoluteToleranceNs: Long = 1_000L,
    val requireSameCameraIdWhenKnown: Boolean = true,
    val requireSameIsoWhenKnown: Boolean = true,
    val requireCompatibleBlackLevel: Boolean = true,
    val requireCompatibleWhiteLevel: Boolean = true,
    val missingMasterDarkBehavior: MissingMasterDarkBehavior =
        MissingMasterDarkBehavior.WARN_AND_CONTINUE,
    val tileHeight: Int = 128,
    val aggregationOptions: RawStackAggregationOptions = RawStackAggregationOptions(),
    val diagnosticsEnabled: Boolean = true,
    val candidateDiagnosticsEnabled: Boolean = true,
    val storageSafetyMarginFraction: Double = 0.20,
    val minimumStorageReserveBytes: Long = 256L * 1024L * 1024L,
    val appVersion: String? = null
) {
    init {
        require(minimumDarkFrames >= 1) { "minimumDarkFrames must be at least 1." }
        require(exposureRelativeTolerance >= 0.0 && exposureRelativeTolerance.isFinite()) {
            "exposureRelativeTolerance must be finite and non-negative."
        }
        require(exposureAbsoluteToleranceNs >= 0L) {
            "exposureAbsoluteToleranceNs must be non-negative."
        }
        require(tileHeight in 1..4096) { "tileHeight must be between 1 and 4096." }
        require(storageSafetyMarginFraction in 0.0..1.0) {
            "storageSafetyMarginFraction must be in [0, 1]."
        }
        require(minimumStorageReserveBytes >= 0L) {
            "minimumStorageReserveBytes must be non-negative."
        }
    }

    fun aggregationModeFor(frameCount: Int): RawStackAggregationMode =
        when (aggregationPolicy) {
            DarkAggregationPolicy.AUTO ->
                when {
                    frameCount >= 7 -> RawStackAggregationMode.SIGMA_CLIPPED_MEAN
                    frameCount >= 5 -> RawStackAggregationMode.MIN_MAX_REJECTED_MEAN
                    else -> RawStackAggregationMode.MEAN
                }
            DarkAggregationPolicy.MEAN -> RawStackAggregationMode.MEAN
            DarkAggregationPolicy.MIN_MAX_REJECTED_MEAN ->
                RawStackAggregationMode.MIN_MAX_REJECTED_MEAN
            DarkAggregationPolicy.SIGMA_CLIPPED_MEAN ->
                RawStackAggregationMode.SIGMA_CLIPPED_MEAN
        }

    fun resolvedAggregationOptions(frameCount: Int): RawStackAggregationOptions =
        aggregationOptions.copy(mode = aggregationModeFor(frameCount))
}
