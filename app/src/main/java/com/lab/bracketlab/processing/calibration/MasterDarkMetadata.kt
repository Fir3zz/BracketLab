package com.lab.bracketlab.processing.calibration

import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.stack.RawStackAggregationMode

data class DarkSourceFrameSummary(
    val frameIndex: Int,
    val timestampNs: Long?,
    val iso: Int,
    val exposureTimeNs: Long
)

data class MasterDarkMetadata(
    val id: String,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val cfaPattern: CfaPattern,
    val cameraId: String?,
    val iso: Int,
    val exposureTimeNs: Long,
    val frameCount: Int,
    val aggregationMode: RawStackAggregationMode,
    val blackLevelPattern: IntArray?,
    val whiteLevel: Int?,
    val createdAtMillis: Long,
    val appVersion: String?,
    val rawFilename: String,
    val sourceFrames: List<DarkSourceFrameSummary>,
    val minimumDarkValue: Int?,
    val maximumDarkValue: Int?,
    val meanDarkValue: Double?
)
