package com.lab.bracketlab.processing.align.star

import java.io.File
import java.io.IOException
import java.util.Locale

object StarCatalogStore {
    fun writeCatalogs(
        catalogs: List<StarCatalog>,
        directory: File,
        overwrite: Boolean = true
    ): StarCatalogWriteResult {
        val temporaryFiles = mutableListOf<File>()
        val committed = mutableListOf<File>()
        return try {
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Could not create star catalog directory.")
            }
            for (catalog in catalogs) {
                val name = "star_catalog_frame_${catalog.frameIndex.toString().padStart(3, '0')}.json"
                val output = File(directory, name)
                if (output.exists() && !overwrite) {
                    throw IOException("Catalog already exists: ${output.absolutePath}")
                }
                val temporary = File(directory, ".$name.${System.nanoTime()}.tmp")
                temporary.writeText(toJson(catalog), Charsets.UTF_8)
                temporaryFiles += temporary
                if (output.exists() && !output.delete()) {
                    throw IOException("Could not replace catalog: ${output.absolutePath}")
                }
                if (!temporary.renameTo(output)) {
                    temporary.copyTo(output, overwrite = true)
                    if (!temporary.delete()) {
                        throw IOException("Could not remove temporary catalog.")
                    }
                }
                committed += output
            }
            StarCatalogWriteResult(
                success = true,
                catalogPaths = committed.map { it.absolutePath }
            )
        } catch (error: Throwable) {
            val cleanup =
                temporaryFiles.all { !it.exists() || it.delete() } &&
                    committed.all { !it.exists() || it.delete() }
            StarCatalogWriteResult(
                success = false,
                failureCode = StarDetectionFailureCode.CATALOG_WRITE_FAILED,
                failureMessage = "${error.javaClass.simpleName}: ${error.message}",
                cleanupSucceeded = cleanup
            )
        }
    }

    internal fun toJson(catalog: StarCatalog): String =
        buildString {
            appendLine("{")
            appendLine("  \"frameIndex\": ${catalog.frameIndex},")
            appendLine("  \"sourceTimestampNs\": ${catalog.sourceTimestampNs ?: "null"},")
            appendLine("  \"proxyType\": \"${catalog.proxyType.name}\",")
            appendLine("  \"proxyWidth\": ${catalog.proxyWidth},")
            appendLine("  \"proxyHeight\": ${catalog.proxyHeight},")
            appendLine("  \"sourceWidth\": ${catalog.sourceWidth},")
            appendLine("  \"sourceHeight\": ${catalog.sourceHeight},")
            appendLine("  \"scaleX\": ${number(catalog.scaleX)},")
            appendLine("  \"scaleY\": ${number(catalog.scaleY)},")
            appendLine("  \"exposureNormalized\": ${catalog.exposureNormalized},")
            appendLine("  \"backgroundEstimate\": ${numberOrNull(catalog.backgroundEstimate)},")
            appendLine("  \"noiseEstimate\": ${numberOrNull(catalog.noiseEstimate)},")
            appendLine("  \"thresholdUsed\": ${numberOrNull(catalog.thresholdUsed)},")
            appendLine("  \"thresholdSigma\": ${number(catalog.thresholdSigma)},")
            appendLine("  \"localMaximumCount\": ${catalog.localMaximumCount},")
            appendLine("  \"starCount\": ${catalog.starCount},")
            appendLine("  \"statusCode\": ${stringOrNull(catalog.statusCode?.name)},")
            appendLine("  \"durationMs\": ${catalog.durationMs},")
            appendLine("  \"rejectedCandidateCounts\": {")
            val rejected = catalog.rejectedCandidateCounts.entries.toList()
            rejected.forEachIndexed { index, entry ->
                append("    \"${entry.key.name}\": ${entry.value}")
                appendLine(if (index == rejected.lastIndex) "" else ",")
            }
            appendLine("  },")
            appendLine("  \"warnings\": [")
            catalog.warnings.forEachIndexed { index, warning ->
                append("    \"${escape(warning)}\"")
                appendLine(if (index == catalog.warnings.lastIndex) "" else ",")
            }
            appendLine("  ],")
            appendLine("  \"stars\": [")
            catalog.stars.forEachIndexed { index, star ->
                appendLine("    {")
                appendLine("      \"id\": ${star.id},")
                appendLine("      \"proxyX\": ${number(star.proxyX)},")
                appendLine("      \"proxyY\": ${number(star.proxyY)},")
                appendLine("      \"fullX\": ${number(star.fullX)},")
                appendLine("      \"fullY\": ${number(star.fullY)},")
                appendLine("      \"peak\": ${number(star.peak)},")
                appendLine("      \"flux\": ${number(star.flux)},")
                appendLine("      \"background\": ${number(star.background)},")
                appendLine("      \"snr\": ${number(star.snr)},")
                appendLine("      \"radius\": ${number(star.radius)},")
                appendLine("      \"secondMoment\": ${number(star.secondMoment)},")
                appendLine("      \"sharpness\": ${number(star.sharpness)},")
                appendLine("      \"saturated\": ${star.saturated},")
                appendLine("      \"detectionQuality\": ${number(star.detectionQuality)},")
                appendLine(
                    "      \"flags\": [" +
                        star.flags.joinToString { "\"${it.name}\"" } +
                        "]"
                )
                append("    }")
                appendLine(if (index == catalog.stars.lastIndex) "" else ",")
            }
            appendLine("  ]")
            appendLine("}")
        }

    private fun number(value: Double): String =
        String.format(Locale.US, "%.12g", value)

    private fun numberOrNull(value: Double): String =
        if (value.isFinite()) number(value) else "null"

    private fun stringOrNull(value: String?): String =
        value?.let { "\"${escape(it)}\"" } ?: "null"

    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
}

