package com.lab.bracketlab.processing.pipeline

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import com.lab.bracketlab.processing.model.RawStack

data class CapturedRawSequence(
    val cameraId: String,
    val cameraCharacteristics: CameraCharacteristics,
    val records: List<CapturedFrameRecord>,
    val rejectedFrames: List<String>,
    val outputDirectoryPath: String,
    val createdAtMillis: Long = System.currentTimeMillis()
) : CameraMetadataProvider {
    val frameCount: Int
        get() = records.size

    fun toRawStack(): RawStack =
        RawStack(records.map { it.toRawFrame() }, cameraId)

    fun recordForFrameIndex(frameIndex: Int): CapturedFrameRecord? =
        records.firstOrNull { it.frameIndex == frameIndex }

    fun cleanupTemporaryRawFrames(): Boolean =
        records.fold(true) { cleaned, record ->
            record.raw16Storage.deleteIfOwned() && cleaned
        }.let { filesCleaned ->
            val directoriesCleaned = records
                .mapNotNull {
                    (it.raw16Storage as? com.lab.bracketlab.processing.storage.FileBackedRaw16FrameStorage)
                        ?.file
                        ?.parentFile
                }
                .distinctBy { it.absolutePath }
                .all { directory ->
                    !directory.exists() ||
                        (directory.listFiles()?.isEmpty() == true && directory.delete())
                }
            filesCleaned && directoriesCleaned
        }

    override fun getCameraCharacteristics(cameraId: String?): CameraCharacteristics? =
        cameraCharacteristics.takeIf { cameraId == null || cameraId == this.cameraId }

    override fun getCaptureResult(frameIndex: Int): CaptureResult? =
        recordForFrameIndex(frameIndex)?.totalCaptureResult

    override fun getReferenceMetadata(frameIndex: Int, cameraId: String?): ReferenceCameraMetadata {
        val record = recordForFrameIndex(frameIndex)
        return ReferenceCameraMetadata(
            cameraId = record?.cameraId ?: cameraId,
            frameIndex = frameIndex,
            cameraCharacteristics = record?.cameraCharacteristics
                ?: getCameraCharacteristics(cameraId),
            captureResult = record?.totalCaptureResult,
            dngOrientation = record?.dngOrientation
        )
    }
}
