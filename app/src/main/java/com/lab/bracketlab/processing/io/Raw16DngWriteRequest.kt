package com.lab.bracketlab.processing.io

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.location.Location
import com.lab.bracketlab.processing.stack.AlignedRaw16StackResult

data class Raw16DngWriteRequest(
    val alignedResult: AlignedRaw16StackResult?,
    val cameraCharacteristics: CameraCharacteristics?,
    val captureResult: CaptureResult?,
    val destination: Raw16DngOutputDestination?,
    val referenceFrameIndex: Int? = alignedResult?.referenceFrameIndex,
    val metadataFrameIndex: Int? = null,
    val expectedCameraId: String? = null,
    val metadataCameraId: String? = null,
    val expectedWidth: Int? = null,
    val expectedHeight: Int? = null,
    val orientation: Int? = null,
    val description: String? = null,
    val location: Location? = null,
    val thumbnail: Bitmap? = null,
    val sequenceIdentifier: String? = null,
    val deletePackedInputAfterWrite: Boolean = false
)

data class Raw16DngWriteOptions(
    val orientation: Int? = null,
    val description: String? = null,
    val location: Location? = null,
    val sequenceIdentifier: String? = null
)
