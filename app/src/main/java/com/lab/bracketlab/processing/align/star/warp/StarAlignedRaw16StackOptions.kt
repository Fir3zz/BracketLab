package com.lab.bracketlab.processing.align.star.warp

import com.lab.bracketlab.processing.calibration.DarkCalibrationInput
import com.lab.bracketlab.processing.stack.RawStackAggregationOptions

enum class StarWarpInterpolationMode {
    BILINEAR_CFA_PLANE
}

data class StarAlignedRaw16StackOptions(
    val aggregationOptions: RawStackAggregationOptions = RawStackAggregationOptions(),
    val interpolationMode: StarWarpInterpolationMode = StarWarpInterpolationMode.BILINEAR_CFA_PLANE,
    val integralCoordinateEpsilon: Double = 1e-7,
    val tileWorkingMemoryBudgetBytes: Long = 24L * 1024L * 1024L,
    val preferredTileWidth: Int = 256,
    val preferredTileHeight: Int = 128,
    val minimumAcceptedFrames: Int = 2,
    val minimumFrameValidFraction: Double = 0.05,
    val debugValidityMapEnabled: Boolean = false,
    val debugReportEnabled: Boolean = true,
    val preserveReferenceDimensions: Boolean = true,
    val requireSameIso: Boolean = true,
    val requireSameExposure: Boolean = true,
    val exposureRelativeTolerance: Double = 0.001,
    val darkCalibration: DarkCalibrationInput = DarkCalibrationInput.OFF
) {
    init {
        require(integralCoordinateEpsilon > 0.0 && integralCoordinateEpsilon < 0.01)
        require(tileWorkingMemoryBudgetBytes >= 1L * 1024L * 1024L)
        require(preferredTileWidth in 8..4096)
        require(preferredTileHeight in 8..4096)
        require(minimumAcceptedFrames >= 1)
        require(minimumFrameValidFraction in 0.0..1.0)
        require(exposureRelativeTolerance >= 0.0)
    }
}
