package com.lab.bracketlab.processing.calibration

import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.storage.FileBackedRaw16FrameStorage
import java.io.File

data class MasterDark(
    val metadata: MasterDarkMetadata,
    val rawFile: File,
    val metadataFile: File? = null
) {
    val storage: FileBackedRaw16FrameStorage
        get() = FileBackedRaw16FrameStorage(rawFile, deleteOnCleanup = false)

    fun toRawFrame(frameIndex: Int = 0): RawFrame =
        RawFrame(
            width = metadata.width,
            height = metadata.height,
            raw16Storage = storage,
            rowStride = metadata.rowStride,
            pixelStride = metadata.pixelStride,
            exposureTimeNs = metadata.exposureTimeNs,
            iso = metadata.iso,
            cameraId = metadata.cameraId,
            timestampNs = metadata.createdAtMillis * 1_000_000L,
            frameIndex = frameIndex,
            sourceFilePath = rawFile.absolutePath,
            blackLevelPattern = metadata.blackLevelPattern,
            whiteLevel = metadata.whiteLevel,
            cfaPattern = metadata.cfaPattern
        )
}
