package com.lab.bracketlab.processing.stack

enum class RawStackAggregationMode {
    MEAN,
    MIN_MAX_REJECTED_MEAN,
    SIGMA_CLIPPED_MEAN,
    /** Fixed-camera light-trail composite: retain the brightest RAW16 sample. */
    MAXIMUM
}

enum class RawStackAggregationFallbackReason {
    INSUFFICIENT_SAMPLES_FOR_MIN_MAX,
    INSUFFICIENT_SAMPLES_FOR_SIGMA,
    INSUFFICIENT_REMAINING_SAMPLES
}
