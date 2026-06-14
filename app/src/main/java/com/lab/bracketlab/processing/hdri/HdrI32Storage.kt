package com.lab.bracketlab.processing.hdri

import java.io.File

data class HdrI32StorageEstimate(
    val inputRawBytes: Long,
    val floatOutputTemporaryBytes: Long,
    val floatOutputCommittedBytes: Long,
    val xisfMasterBytes: Long,
    val fitsMasterBytes: Long,
    val linearRgbFloat16DngBytes: Long,
    val metadataAndReportBytes: Long,
    val baseRequiredBytes: Long,
    val safetyMarginBytes: Long,
    val totalRequiredBytes: Long,
    val availableBytes: Long,
    val sufficient: Boolean
)

object HdrI32StorageEstimator {
    fun estimate(
        width: Int,
        height: Int,
        frameCount: Int,
        outputDirectory: File,
        includeXisfMaster: Boolean = true,
        includeFitsMaster: Boolean = true,
        includeLinearRgbFloat16Dng: Boolean = true,
        safetyMarginFraction: Double = 0.20,
        minimumReserveBytes: Long = 256L * 1024L * 1024L
    ): HdrI32StorageEstimate {
        require(safetyMarginFraction in 0.0..1.0)
        require(minimumReserveBytes >= 0L)
        val pixels = safeMultiply(width.toLong(), height.toLong())
        val rawFrameBytes = safeMultiply(pixels, 2L)
        val floatBytes = safeMultiply(pixels, Float.SIZE_BYTES.toLong())
        val inputBytes = safeMultiply(rawFrameBytes, frameCount.toLong())
        val xisfMaster = if (includeXisfMaster) safeAdd(floatBytes, 4096L) else 0L
        val fitsMaster =
            if (includeFitsMaster) {
                safeAdd(floatBytes, HdrI32FitsWriter.FITS_BLOCK_BYTES * 2L)
            } else {
                0L
            }
        val linearRgbDng =
            if (includeLinearRgbFloat16Dng) {
                safeAdd(safeMultiply(pixels, 6L), 512L * 1024L)
            } else {
                0L
            }
        val metadata = 128L * 1024L
        val base =
            listOf(
                inputBytes,
                floatBytes,
                floatBytes,
                xisfMaster,
                fitsMaster,
                linearRgbDng,
                metadata
            )
                .fold(0L, ::safeAdd)
        val percentage = (base.toDouble() * safetyMarginFraction).toLong()
        val margin = maxOf(minimumReserveBytes, percentage)
        val total = safeAdd(base, margin)
        val available = nearestExistingDirectory(outputDirectory)?.usableSpace?.coerceAtLeast(0L) ?: 0L
        return HdrI32StorageEstimate(
            inputRawBytes = inputBytes,
            floatOutputTemporaryBytes = floatBytes,
            floatOutputCommittedBytes = floatBytes,
            xisfMasterBytes = xisfMaster,
            fitsMasterBytes = fitsMaster,
            linearRgbFloat16DngBytes = linearRgbDng,
            metadataAndReportBytes = metadata,
            baseRequiredBytes = base,
            safetyMarginBytes = margin,
            totalRequiredBytes = total,
            availableBytes = available,
            sufficient = available >= total
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
