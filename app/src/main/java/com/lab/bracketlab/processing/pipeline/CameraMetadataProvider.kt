package com.lab.bracketlab.processing.pipeline

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult

data class ReferenceCameraMetadata(
    val cameraId: String?,
    val frameIndex: Int,
    val cameraCharacteristics: CameraCharacteristics?,
    val captureResult: CaptureResult?,
    val dngOrientation: Int? = null
) {
    val complete: Boolean
        get() = cameraCharacteristics != null && captureResult != null
}

interface CameraMetadataProvider {
    fun getCameraCharacteristics(cameraId: String?): CameraCharacteristics?

    fun getCaptureResult(frameIndex: Int): CaptureResult?

    fun getReferenceMetadata(frameIndex: Int, cameraId: String?): ReferenceCameraMetadata =
        ReferenceCameraMetadata(
            cameraId = cameraId,
            frameIndex = frameIndex,
            cameraCharacteristics = getCameraCharacteristics(cameraId),
            captureResult = getCaptureResult(frameIndex)
        )
}
