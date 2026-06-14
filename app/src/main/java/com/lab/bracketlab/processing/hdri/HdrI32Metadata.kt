package com.lab.bracketlab.processing.hdri

import com.lab.bracketlab.processing.raw.CfaPattern

data class HdrI32SourceFrameMetadata(
    val frameIndex: Int,
    val timestampNs: Long?,
    val exposureTimeNs: Long,
    val iso: Int
)

data class HdrI32Metadata(
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    val pixelStrideBytes: Int,
    val cfaPattern: CfaPattern,
    val cameraId: String?,
    val iso: Int,
    val inputExposureTimesNs: List<Long>,
    val referenceFrameIndex: Int,
    val referenceExposureTimeNs: Long,
    val frameCount: Int,
    val whiteLevel: Int,
    val blackLevelPattern: IntArray,
    val saturationMarginDn: Int,
    val weightPolicy: HdrI32WeightPolicy,
    val invalidSamplePolicy: HdrI32InvalidSamplePolicy,
    val alignmentMode: HdrI32AlignmentMode,
    val storagePath: String,
    val createdAtMillis: Long,
    val appVersion: String?,
    val sourceFrames: List<HdrI32SourceFrameMetadata>,
    val totalInputSamples: Long,
    val validSamples: Long,
    val saturatedRejectedSamples: Long,
    val fallbackPixels: Long,
    val noValidSamplePixels: Long,
    val minimumRadiance: Double?,
    val maximumRadiance: Double?,
    val meanRadiance: Double?,
    val blackWeightZeroThreshold: Double = 0.002,
    val blackWeightFullThreshold: Double = 0.02,
    val highlightWeightFullThreshold: Double = 0.90,
    val highlightWeightZeroThreshold: Double = 0.98,
    val exposureWeightPower: Double = 1.0,
    val highlightCoherencePolicy: HdrI32HighlightCoherencePolicy =
        HdrI32HighlightCoherencePolicy.BAYER_2X2_SHARED,
    val lowSignalZeroWeightSamples: Long = 0L,
    val highlightZeroWeightSamples: Long = 0L,
    val totalWeightZeroPixels: Long = 0L,
    val sharedHighlightFrameBlocks: Long = 0L,
    val blockSaturationZeroWeightSamples: Long = 0L
)
