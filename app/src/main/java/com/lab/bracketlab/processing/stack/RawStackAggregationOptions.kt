package com.lab.bracketlab.processing.stack

/**
 * CPU-only RAW16 aggregation policy.
 *
 * Samples remain encoded unsigned RAW16 values. No black subtraction,
 * exposure normalization, white balance, gamma, or tone mapping is applied.
 */
data class RawStackAggregationOptions(
    val mode: RawStackAggregationMode = RawStackAggregationMode.MEAN,
    val minimumSamplesForMinMax: Int = 5,
    val lowSamplesToReject: Int = 1,
    val highSamplesToReject: Int = 1,
    val minimumSamplesForSigmaClip: Int = 7,
    val sigmaThreshold: Double = 2.5,
    val maxSigmaIterations: Int = 2,
    val minimumRemainingSamples: Int = 3,
    val fallbackMode: RawStackAggregationMode = RawStackAggregationMode.MEAN,
    val diagnosticsEnabled: Boolean = true,
    val debugRejectionCountMapEnabled: Boolean = false
) {
    init {
        require(minimumSamplesForMinMax >= 1) {
            "minimumSamplesForMinMax must be at least 1."
        }
        require(lowSamplesToReject >= 0) {
            "lowSamplesToReject must be non-negative."
        }
        require(highSamplesToReject >= 0) {
            "highSamplesToReject must be non-negative."
        }
        require(lowSamplesToReject + highSamplesToReject < minimumSamplesForMinMax) {
            "Min-max rejection must leave at least one sample."
        }
        require(minimumSamplesForSigmaClip >= 2) {
            "minimumSamplesForSigmaClip must be at least 2."
        }
        require(sigmaThreshold.isFinite() && sigmaThreshold > 0.0) {
            "sigmaThreshold must be finite and greater than zero."
        }
        require(maxSigmaIterations >= 1) {
            "maxSigmaIterations must be at least 1."
        }
        require(minimumRemainingSamples >= 1) {
            "minimumRemainingSamples must be at least 1."
        }
        require(minimumRemainingSamples <= minimumSamplesForSigmaClip) {
            "minimumRemainingSamples cannot exceed minimumSamplesForSigmaClip."
        }
        require(fallbackMode != RawStackAggregationMode.SIGMA_CLIPPED_MEAN) {
            "Sigma clipping cannot be used as a fallback mode."
        }
        if (mode == RawStackAggregationMode.MIN_MAX_REJECTED_MEAN) {
            require(fallbackMode == RawStackAggregationMode.MEAN) {
                "Min-max rejection can only fall back to MEAN."
            }
        }
        require(!debugRejectionCountMapEnabled || diagnosticsEnabled) {
            "debugRejectionCountMapEnabled requires diagnosticsEnabled."
        }
    }
}
