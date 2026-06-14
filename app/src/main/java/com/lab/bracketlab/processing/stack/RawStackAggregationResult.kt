package com.lab.bracketlab.processing.stack

data class RawStackAggregationResult(
    val outputValue: Int,
    val requestedMode: RawStackAggregationMode,
    val appliedMode: RawStackAggregationMode,
    val inputSampleCount: Int,
    val acceptedSampleCount: Int,
    val rejectedLowCount: Int,
    val rejectedHighCount: Int,
    val rejectedSigmaCount: Int,
    val fallbackUsed: Boolean,
    val fallbackReason: RawStackAggregationFallbackReason?,
    val zeroVariance: Boolean
) {
    val totalRejectedCount: Int
        get() = rejectedLowCount + rejectedHighCount + rejectedSigmaCount
}

data class RawStackAggregationDiagnostics(
    val requestedMode: RawStackAggregationMode,
    val pixelsProcessed: Long,
    val totalInputSamples: Long,
    val totalAcceptedSamples: Long,
    val totalRejectedSamples: Long,
    val pixelsUsingRequestedMode: Long,
    val pixelsFallingBackToMean: Long,
    val pixelsFallingBackToOtherMode: Long,
    val maximumRejectedSamplesAtOnePixel: Int,
    val meanRejectedSamplesPerPixel: Double,
    val pixelsWithZeroVariance: Long,
    val pixelsWithInsufficientSamples: Long,
    val rejectionCountMap: IntArray? = null
) {
    companion object {
        fun empty(
            requestedMode: RawStackAggregationMode = RawStackAggregationMode.MEAN
        ): RawStackAggregationDiagnostics =
            RawStackAggregationDiagnostics(
                requestedMode = requestedMode,
                pixelsProcessed = 0L,
                totalInputSamples = 0L,
                totalAcceptedSamples = 0L,
                totalRejectedSamples = 0L,
                pixelsUsingRequestedMode = 0L,
                pixelsFallingBackToMean = 0L,
                pixelsFallingBackToOtherMode = 0L,
                maximumRejectedSamplesAtOnePixel = 0,
                meanRejectedSamplesPerPixel = 0.0,
                pixelsWithZeroVariance = 0L,
                pixelsWithInsufficientSamples = 0L,
                rejectionCountMap = null
            )
    }
}
