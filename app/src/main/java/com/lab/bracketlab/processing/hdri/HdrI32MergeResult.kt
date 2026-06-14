package com.lab.bracketlab.processing.hdri

data class HdrI32MergeDiagnostics(
    val totalInputSamples: Long,
    val validSamples: Long,
    val saturatedRejectedSamples: Long,
    val fallbackPixels: Long,
    val noValidSamplePixels: Long,
    val minimumRadiance: Double?,
    val maximumRadiance: Double?,
    val meanRadiance: Double?,
    val tileHeight: Int,
    val processingDurationMs: Long,
    val lowSignalZeroWeightSamples: Long = 0L,
    val highlightZeroWeightSamples: Long = 0L,
    val totalWeightZeroPixels: Long = 0L,
    val sharedHighlightFrameBlocks: Long = 0L,
    val blockSaturationZeroWeightSamples: Long = 0L
)

data class HdrI32MergeResult(
    val success: Boolean,
    val frame: HdrI32Frame? = null,
    val diagnostics: HdrI32MergeDiagnostics? = null,
    val warnings: List<HdrI32Warning> = emptyList(),
    val failureCode: HdrI32FailureCode? = null,
    val failureMessage: String? = null,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val temporaryCleanupSucceeded: Boolean = true
)
