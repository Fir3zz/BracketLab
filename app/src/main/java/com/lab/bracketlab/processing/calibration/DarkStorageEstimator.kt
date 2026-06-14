package com.lab.bracketlab.processing.calibration

import java.io.File

enum class DarkStorageStrategy {
    FILE_BACKED_TILED
}

data class DarkStorageEstimate(
    val frameBytes: Long,
    val darkInputBytes: Long,
    val masterDarkBytes: Long,
    val optionalLightInputBytes: Long,
    val temporaryOutputBytes: Long,
    val estimatedFinalDngBytes: Long,
    val metadataBytes: Long,
    val baseRequiredBytes: Long,
    val safetyMarginBytes: Long,
    val totalRequiredBytes: Long,
    val availableBytes: Long,
    val sufficient: Boolean,
    val selectedStrategy: DarkStorageStrategy
)

object DarkStorageEstimator {
    fun estimate(
        width: Int,
        height: Int,
        darkFrameCount: Int,
        lightFrameCount: Int = 0,
        outputDirectory: File,
        options: DarkCalibrationOptions = DarkCalibrationOptions()
    ): DarkStorageEstimate {
        val frameBytes = safeMultiply(safeMultiply(width.toLong(), height.toLong()), 2L)
        val darkInputs = safeMultiply(frameBytes, darkFrameCount.toLong())
        val lightInputs = safeMultiply(frameBytes, lightFrameCount.toLong())
        val masterDark = frameBytes
        val temporaryOutput = if (lightFrameCount > 0) frameBytes else masterDark
        val dng = if (lightFrameCount > 0) safeAdd(frameBytes, frameBytes / 8L) else 0L
        val metadata = 64L * 1024L
        val base = listOf(darkInputs, lightInputs, masterDark, temporaryOutput, dng, metadata)
            .fold(0L, ::safeAdd)
        val percentageMargin = (base.toDouble() * options.storageSafetyMarginFraction).toLong()
        val safety = maxOf(options.minimumStorageReserveBytes, percentageMargin)
        val total = safeAdd(base, safety)
        val storageVolume = nearestExistingDirectory(outputDirectory)
        val available = storageVolume?.usableSpace?.coerceAtLeast(0L) ?: 0L
        return DarkStorageEstimate(
            frameBytes = frameBytes,
            darkInputBytes = darkInputs,
            masterDarkBytes = masterDark,
            optionalLightInputBytes = lightInputs,
            temporaryOutputBytes = temporaryOutput,
            estimatedFinalDngBytes = dng,
            metadataBytes = metadata,
            baseRequiredBytes = base,
            safetyMarginBytes = safety,
            totalRequiredBytes = total,
            availableBytes = available,
            sufficient = available >= total,
            selectedStrategy = DarkStorageStrategy.FILE_BACKED_TILED
        )
    }

    internal fun safeMultiply(left: Long, right: Long): Long {
        if (left <= 0L || right <= 0L) return 0L
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE
        return left * right
    }

    internal fun safeAdd(left: Long, right: Long): Long {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) return Long.MAX_VALUE
        return left + right
    }

    private fun nearestExistingDirectory(directory: File): File? {
        var candidate: File? = directory
        while (candidate != null) {
            if (candidate.isDirectory) return candidate
            candidate = candidate.parentFile
        }
        return null
    }
}
