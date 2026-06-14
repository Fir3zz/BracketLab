package com.lab.bracketlab.processing.stack

import kotlin.math.sqrt

data class AppliedRawTranslation(
    val frameIndex: Int,
    val isReference: Boolean,
    val estimatedDxRaw: Double,
    val estimatedDyRaw: Double,
    val appliedDxRaw: Int,
    val appliedDyRaw: Int,
    val residualDxRaw: Double,
    val residualDyRaw: Double,
    val residualMagnitudeRaw: Double,
    val quantizationChanged: Boolean,
    val accepted: Boolean,
    val rejectionReason: AlignedRaw16StackFailureCode?,
    val warnings: List<AlignedRaw16StackWarningCode>
) {
    companion object {
        fun fromEstimated(
            frameIndex: Int,
            isReference: Boolean,
            estimatedDxRaw: Double,
            estimatedDyRaw: Double,
            accepted: Boolean,
            rejectionReason: AlignedRaw16StackFailureCode?,
            warnings: List<AlignedRaw16StackWarningCode>
        ): AppliedRawTranslation {
            val appliedDx = quantizeToNearestEven(estimatedDxRaw)
            val appliedDy = quantizeToNearestEven(estimatedDyRaw)
            val residualDx = estimatedDxRaw - appliedDx.toDouble()
            val residualDy = estimatedDyRaw - appliedDy.toDouble()
            return AppliedRawTranslation(
                frameIndex = frameIndex,
                isReference = isReference,
                estimatedDxRaw = estimatedDxRaw,
                estimatedDyRaw = estimatedDyRaw,
                appliedDxRaw = appliedDx,
                appliedDyRaw = appliedDy,
                residualDxRaw = residualDx,
                residualDyRaw = residualDy,
                residualMagnitudeRaw = sqrt(residualDx * residualDx + residualDy * residualDy),
                quantizationChanged =
                    estimatedDxRaw != appliedDx.toDouble() || estimatedDyRaw != appliedDy.toDouble(),
                accepted = accepted,
                rejectionReason = rejectionReason,
                warnings = warnings
            )
        }

        /**
         * CFA-safe direct mosaic stacking uses only even integer translations.
         * Ties at exactly one raw pixel from either even shift round away from zero.
         */
        fun quantizeToNearestEven(value: Double): Int {
            if (!value.isFiniteForStack()) return 0
            val scaled = value / 2.0
            val rounded =
                if (scaled >= 0.0) {
                    kotlin.math.floor(scaled + 0.5)
                } else {
                    kotlin.math.ceil(scaled - 0.5)
                }
            return (rounded * 2.0).toInt()
        }

        private fun Double.isFiniteForStack(): Boolean =
            !isNaN() && this != Double.POSITIVE_INFINITY && this != Double.NEGATIVE_INFINITY
    }
}
