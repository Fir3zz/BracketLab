package com.lab.bracketlab.processing.pipeline

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.storage.Raw16FrameStorage

data class CapturedFrameRecord(
    val frameIndex: Int,
    val sequenceNumber: Int,
    val captureFrameNumber: Int,
    val raw16Storage: Raw16FrameStorage,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val imageTimestampNs: Long,
    val resultTimestampNs: Long?,
    val exposureTimeNs: Long,
    val iso: Int,
    val cameraId: String,
    val cfaPattern: CfaPattern?,
    val blackLevelPattern: IntArray?,
    val whiteLevel: Int?,
    val totalCaptureResult: TotalCaptureResult,
    val cameraCharacteristics: CameraCharacteristics,
    val dngOrientation: Int,
    val dngOrientationDegrees: Int
) {
    fun toRawFrame(): RawFrame =
        RawFrame(
            width = width,
            height = height,
            raw16Storage = raw16Storage,
            rowStride = rowStride,
            pixelStride = pixelStride,
            exposureTimeNs = exposureTimeNs,
            iso = iso,
            cameraId = cameraId,
            timestampNs = resultTimestampNs ?: imageTimestampNs,
            frameIndex = frameIndex,
            blackLevelPattern = blackLevelPattern,
            whiteLevel = whiteLevel,
            cfaPattern = cfaPattern,
            sourceFilePath =
                (raw16Storage as? com.lab.bracketlab.processing.storage.FileBackedRaw16FrameStorage)
                    ?.file
                    ?.absolutePath
        )
}
