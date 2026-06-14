package com.lab.bracketlab.processing.align.star

enum class StarDetectionFailureCode {
    EMPTY_STACK,
    INVALID_FRAME,
    INVALID_RAW_STORAGE,
    PROXY_GENERATION_FAILED,
    NO_STARS_DETECTED,
    TOO_FEW_STARS_FOR_FUTURE_ALIGNMENT,
    INVALID_OPTIONS,
    STAR_DETECTION_FAILED,
    CATALOG_WRITE_FAILED
}

enum class StarCandidateRejectionReason {
    BELOW_THRESHOLD,
    TOO_CLOSE_TO_EDGE,
    SATURATED,
    DUPLICATE_NEARBY,
    INVALID_WINDOW,
    TOO_SMALL,
    TOO_LARGE,
    LOW_SNR,
    BAD_CENTROID,
    NON_FINITE_VALUE,
    MAX_STARS_LIMIT
}

data class StarDetectionResult(
    val success: Boolean,
    val catalogs: List<StarCatalog> = emptyList(),
    val globalWarnings: List<String> = emptyList(),
    val failureCode: StarDetectionFailureCode? = null,
    val failureMessage: String? = null,
    val durationMs: Long = 0L,
    val options: StarDetectionOptions
)

data class StarCatalogWriteResult(
    val success: Boolean,
    val catalogPaths: List<String> = emptyList(),
    val reportPath: String? = null,
    val failureCode: StarDetectionFailureCode? = null,
    val failureMessage: String? = null,
    val cleanupSucceeded: Boolean = true
)

