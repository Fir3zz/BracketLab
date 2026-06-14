package com.lab.bracketlab.processing.hdri

enum class HdrI32WeightPolicy {
    UNIFORM_VALID_RADIANCE,
    SNR_WEIGHTED_RADIANCE
}

enum class HdrI32HighlightCoherencePolicy {
    PER_SAMPLE,
    BAYER_2X2_SHARED
}

enum class HdrI32InvalidSamplePolicy {
    LEAST_SATURATED_FALLBACK,
    FAIL
}

enum class HdrI32AlignmentMode {
    IDENTITY_ONLY,
    LANDSCAPE_TRANSLATION
}

data class HdrI32MergeOptions(
    val saturationMarginDn: Int = 32,
    val weightPolicy: HdrI32WeightPolicy = HdrI32WeightPolicy.SNR_WEIGHTED_RADIANCE,
    val blackWeightZeroThreshold: Double = 0.002,
    val blackWeightFullThreshold: Double = 0.02,
    val highlightWeightFullThreshold: Double = 0.90,
    val highlightWeightZeroThreshold: Double = 0.98,
    val exposureWeightPower: Double = 1.0,
    val highlightCoherencePolicy: HdrI32HighlightCoherencePolicy =
        HdrI32HighlightCoherencePolicy.BAYER_2X2_SHARED,
    val invalidSamplePolicy: HdrI32InvalidSamplePolicy =
        HdrI32InvalidSamplePolicy.LEAST_SATURATED_FALLBACK,
    val tileHeight: Int = 128,
    val allowIdenticalExposureSetForDiagnostics: Boolean = false,
    val darkCalibrationRequested: Boolean = false,
    val alignmentMode: HdrI32AlignmentMode = HdrI32AlignmentMode.IDENTITY_ONLY,
    val appVersion: String? = null
) {
    init {
        require(saturationMarginDn >= 0) { "saturationMarginDn must be non-negative." }
        require(blackWeightZeroThreshold.isFinite() && blackWeightZeroThreshold >= 0.0) {
            "blackWeightZeroThreshold must be finite and non-negative."
        }
        require(
            blackWeightFullThreshold.isFinite() &&
                blackWeightFullThreshold > blackWeightZeroThreshold
        ) {
            "blackWeightFullThreshold must be greater than blackWeightZeroThreshold."
        }
        require(
            highlightWeightFullThreshold.isFinite() &&
                highlightWeightFullThreshold > blackWeightFullThreshold
        ) {
            "highlightWeightFullThreshold must be greater than blackWeightFullThreshold."
        }
        require(
            highlightWeightZeroThreshold.isFinite() &&
                highlightWeightZeroThreshold > highlightWeightFullThreshold &&
                highlightWeightZeroThreshold <= 1.0
        ) {
            "highlightWeightZeroThreshold must be in " +
                "(highlightWeightFullThreshold, 1.0]."
        }
        require(exposureWeightPower.isFinite() && exposureWeightPower >= 0.0) {
            "exposureWeightPower must be finite and non-negative."
        }
        require(tileHeight in 1..4096) { "tileHeight must be between 1 and 4096." }
    }
}
