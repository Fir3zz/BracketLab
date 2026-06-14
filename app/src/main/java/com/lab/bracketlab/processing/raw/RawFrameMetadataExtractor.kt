package com.lab.bracketlab.processing.raw

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.BlackLevelPattern
import android.media.Image
import com.lab.bracketlab.processing.model.RawFrame

object RawFrameMetadataExtractor {
    fun fromCameraRawImage(
        image: Image,
        result: CaptureResult?,
        characteristics: CameraCharacteristics?,
        cameraId: String? = null,
        frameIndex: Int = 0,
        sourceFilePath: String? = null,
        copyRawBuffer: Boolean = false
    ): RawFrame? {
        if (image.format != ImageFormat.RAW_SENSOR) return null
        val plane = image.planes.firstOrNull() ?: return null
        val exposureTimeNs = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val iso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        val timestampNs = result?.get(CaptureResult.SENSOR_TIMESTAMP)
        val cfaPattern = BayerUtils.fromCameraCharacteristicsValue(
            characteristics?.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        )

        return RawFrame(
            width = image.width,
            height = image.height,
            raw16 = if (copyRawBuffer) copyPlaneBuffer(plane.buffer) else null,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            exposureTimeNs = exposureTimeNs,
            iso = iso,
            cameraId = cameraId,
            timestampNs = timestampNs,
            frameIndex = frameIndex,
            sourceFilePath = sourceFilePath,
            blackLevelPattern = extractBlackLevelPattern(
                characteristics?.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ),
            whiteLevel = characteristics?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            cfaPattern = cfaPattern
        )
    }

    private fun copyPlaneBuffer(buffer: java.nio.ByteBuffer): ByteArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        return ByteArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    fun extractBlackLevelPattern(pattern: BlackLevelPattern?): IntArray? {
        if (pattern == null) return null
        return intArrayOf(
            pattern.getOffsetForIndex(0, 0),
            pattern.getOffsetForIndex(1, 0),
            pattern.getOffsetForIndex(0, 1),
            pattern.getOffsetForIndex(1, 1)
        )
    }
}
