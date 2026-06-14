package com.lab.bracketlab.processing.hdri

import java.io.File
import java.io.IOException

data class HdrI32StoreResult(
    val success: Boolean,
    val frame: HdrI32Frame? = null,
    val sessionDirectory: File? = null,
    val failureCode: HdrI32FailureCode? = null,
    val failureMessage: String? = null,
    val cleanupSucceeded: Boolean = true
)

class HdrI32Store(
    private val sessionDirectory: File
) {
    fun save(frame: HdrI32Frame): HdrI32StoreResult {
        sessionDirectory.mkdirs()
        val finalFloat = File(sessionDirectory, FLOAT_FILENAME)
        val finalMetadata = File(sessionDirectory, METADATA_FILENAME)
        val tempFloat = File(sessionDirectory, ".$FLOAT_FILENAME.${System.nanoTime()}.tmp")
        val tempMetadata = File(sessionDirectory, ".$METADATA_FILENAME.${System.nanoTime()}.tmp")
        var floatCommitted = false

        return try {
            frame.storageFile.inputStream().use { input ->
                tempFloat.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            val expectedBytes = frame.width.toLong() * frame.height.toLong() * Float.SIZE_BYTES
            if (tempFloat.length() != expectedBytes) {
                throw IOException(
                    "HDR Float32 size=${tempFloat.length()}, expected=$expectedBytes."
                )
            }
            val committedMetadata = frame.metadata.copy(storagePath = finalFloat.absolutePath)
            tempMetadata.writeText(HdrI32MetadataJson.encode(committedMetadata), Charsets.UTF_8)
            if (finalFloat.exists() && !finalFloat.delete()) {
                throw IOException("Could not replace existing HDR Float32 file.")
            }
            if (finalMetadata.exists() && !finalMetadata.delete()) {
                throw IOException("Could not replace existing HDR metadata.")
            }
            if (!tempFloat.renameTo(finalFloat)) {
                throw IOException("Could not commit HDR Float32 file.")
            }
            floatCommitted = true
            if (!tempMetadata.renameTo(finalMetadata)) {
                throw IOException("Could not commit HDR metadata.")
            }
            if (frame.storageFile.absolutePath != finalFloat.absolutePath) {
                frame.storageFile.delete()
            }
            HdrI32StoreResult(
                success = true,
                frame = HdrI32Frame(committedMetadata, finalFloat, finalMetadata),
                sessionDirectory = sessionDirectory
            )
        } catch (error: Throwable) {
            val cleaned =
                deletePath(tempFloat) &&
                    deletePath(tempMetadata) &&
                    (!floatCommitted || deletePath(finalFloat)) &&
                    deletePath(finalMetadata)
            HdrI32StoreResult(
                success = false,
                sessionDirectory = sessionDirectory,
                failureCode = HdrI32FailureCode.HDRI_STORAGE_FAILED,
                failureMessage = error.message,
                cleanupSucceeded = cleaned
            )
        }
    }

    fun cleanupIncompleteWrites(): Boolean {
        if (!sessionDirectory.exists()) return true
        return sessionDirectory
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".tmp") }
            .all(::deletePath)
    }

    private fun deletePath(file: File): Boolean =
        when {
            !file.exists() -> true
            file.isDirectory -> file.deleteRecursively()
            else -> file.delete()
        }

    companion object {
        const val FLOAT_FILENAME = "hdr32_cfa_float.rawf32"
        const val METADATA_FILENAME = "hdr32_metadata.json"
        const val REPORT_FILENAME = "hdr32_report.txt"
    }
}

internal object HdrI32MetadataJson {
    fun encode(metadata: HdrI32Metadata): String =
        buildString {
            appendLine("{")
            appendLine("  \"width\": ${metadata.width},")
            appendLine("  \"height\": ${metadata.height},")
            appendLine("  \"rowStrideBytes\": ${metadata.rowStrideBytes},")
            appendLine("  \"pixelStrideBytes\": ${metadata.pixelStrideBytes},")
            appendLine("  \"byteOrder\": \"LITTLE_ENDIAN\",")
            appendLine("  \"sampleFormat\": \"FLOAT32_LINEAR_RADIANCE_CFA\",")
            appendLine("  \"cfaPattern\": \"${metadata.cfaPattern.name}\",")
            appendLine("  \"cameraId\": ${stringOrNull(metadata.cameraId)},")
            appendLine("  \"iso\": ${metadata.iso},")
            appendLine("  \"inputExposureTimesNs\": ${longList(metadata.inputExposureTimesNs)},")
            appendLine("  \"referenceFrameIndex\": ${metadata.referenceFrameIndex},")
            appendLine("  \"referenceExposureTimeNs\": ${metadata.referenceExposureTimeNs},")
            appendLine("  \"frameCount\": ${metadata.frameCount},")
            appendLine("  \"whiteLevel\": ${metadata.whiteLevel},")
            appendLine("  \"blackLevelPattern\": ${intArray(metadata.blackLevelPattern)},")
            appendLine("  \"saturationMarginDn\": ${metadata.saturationMarginDn},")
            appendLine("  \"weightPolicy\": \"${metadata.weightPolicy.name}\",")
            appendLine(
                "  \"blackWeightZeroThreshold\": ${metadata.blackWeightZeroThreshold},"
            )
            appendLine(
                "  \"blackWeightFullThreshold\": ${metadata.blackWeightFullThreshold},"
            )
            appendLine(
                "  \"highlightWeightFullThreshold\": " +
                    "${metadata.highlightWeightFullThreshold},"
            )
            appendLine(
                "  \"highlightWeightZeroThreshold\": " +
                    "${metadata.highlightWeightZeroThreshold},"
            )
            appendLine("  \"exposureWeightPower\": ${metadata.exposureWeightPower},")
            appendLine(
                "  \"highlightCoherencePolicy\": " +
                    "\"${metadata.highlightCoherencePolicy.name}\","
            )
            appendLine("  \"invalidSamplePolicy\": \"${metadata.invalidSamplePolicy.name}\",")
            appendLine("  \"alignmentMode\": \"${metadata.alignmentMode.name}\",")
            appendLine("  \"storagePath\": \"${escape(metadata.storagePath)}\",")
            appendLine("  \"createdAtMillis\": ${metadata.createdAtMillis},")
            appendLine("  \"appVersion\": ${stringOrNull(metadata.appVersion)},")
            appendLine("  \"totalInputSamples\": ${metadata.totalInputSamples},")
            appendLine("  \"validSamples\": ${metadata.validSamples},")
            appendLine("  \"saturatedRejectedSamples\": ${metadata.saturatedRejectedSamples},")
            appendLine(
                "  \"lowSignalZeroWeightSamples\": " +
                    "${metadata.lowSignalZeroWeightSamples},"
            )
            appendLine(
                "  \"highlightZeroWeightSamples\": " +
                    "${metadata.highlightZeroWeightSamples},"
            )
            appendLine(
                "  \"totalWeightZeroPixels\": ${metadata.totalWeightZeroPixels},"
            )
            appendLine(
                "  \"sharedHighlightFrameBlocks\": " +
                    "${metadata.sharedHighlightFrameBlocks},"
            )
            appendLine(
                "  \"blockSaturationZeroWeightSamples\": " +
                    "${metadata.blockSaturationZeroWeightSamples},"
            )
            appendLine("  \"fallbackPixels\": ${metadata.fallbackPixels},")
            appendLine("  \"noValidSamplePixels\": ${metadata.noValidSamplePixels},")
            appendLine("  \"minimumRadiance\": ${metadata.minimumRadiance ?: "null"},")
            appendLine("  \"maximumRadiance\": ${metadata.maximumRadiance ?: "null"},")
            appendLine("  \"meanRadiance\": ${metadata.meanRadiance ?: "null"},")
            appendLine("  \"sourceFrames\": [")
            metadata.sourceFrames.forEachIndexed { index, frame ->
                append(
                    "    {\"frameIndex\": ${frame.frameIndex}, " +
                        "\"timestampNs\": ${frame.timestampNs ?: "null"}, " +
                        "\"exposureTimeNs\": ${frame.exposureTimeNs}, \"iso\": ${frame.iso}}"
                )
                if (index != metadata.sourceFrames.lastIndex) append(',')
                appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }

    private fun longList(values: List<Long>): String =
        values.joinToString(prefix = "[", postfix = "]")

    private fun intArray(values: IntArray): String =
        values.joinToString(prefix = "[", postfix = "]")

    private fun stringOrNull(value: String?): String =
        value?.let { "\"${escape(it)}\"" } ?: "null"

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
