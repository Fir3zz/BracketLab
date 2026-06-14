package com.lab.bracketlab.processing.hdri.export.dng

import java.io.File

data class LinearRgbFloat16StorageEstimate(
    val imagePayloadBytes: Long,
    val metadataBytes: Long,
    val safetyMarginBytes: Long,
    val totalRequiredBytes: Long,
    val availableBytes: Long,
    val sufficient: Boolean
)

object LinearRgbFloat16StorageEstimator {
    fun estimate(
        width: Int,
        height: Int,
        outputDirectory: File,
        safetyMarginFraction: Double = 0.20,
        minimumReserveBytes: Long = 256L * 1024L * 1024L
    ): LinearRgbFloat16StorageEstimate {
        require(width > 0 && height > 0)
        require(safetyMarginFraction in 0.0..1.0)
        val payload = width.toLong() * height.toLong() * 3L * 2L
        val metadata = 512L * 1024L
        val base = payload + metadata
        val margin =
            maxOf(minimumReserveBytes, (base.toDouble() * safetyMarginFraction).toLong())
        val required = base + margin
        val available = nearestExistingDirectory(outputDirectory)?.usableSpace ?: 0L
        return LinearRgbFloat16StorageEstimate(
            imagePayloadBytes = payload,
            metadataBytes = metadata,
            safetyMarginBytes = margin,
            totalRequiredBytes = required,
            availableBytes = available,
            sufficient = available >= required
        )
    }

    private fun nearestExistingDirectory(directory: File): File? {
        var current: File? = directory
        while (current != null) {
            if (current.isDirectory) return current
            current = current.parentFile
        }
        return null
    }
}
