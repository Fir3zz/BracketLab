package com.lab.bracketlab.processing.hdri.export.dng

import com.lab.bracketlab.processing.hdri.HdrI32Frame
import java.io.File

object LinearRgbFloat16DngSidecar {
    const val DEFAULT_FILENAME = "hdr_linear_rgb_float16_metadata.json"

    fun write(
        target: File,
        frame: HdrI32Frame,
        request: LinearRgbFloat16DngWriteRequest,
        result: LinearRgbFloat16DngWriteResult
    ) {
        val diagnostics = result.diagnostics
        val contents =
            buildString {
                appendLine("{")
                appendLine("  \"format\": \"DNG_LINEAR_RGB_FLOAT16\",")
                appendLine("  \"input\": \"${escape(frame.storageFile.absolutePath)}\",")
                appendLine("  \"output\": ${quoted(result.outputPath)},")
                appendLine("  \"width\": ${frame.width},")
                appendLine("  \"height\": ${frame.height},")
                appendLine("  \"cfaPattern\": \"${frame.cfaPattern.name}\",")
                appendLine(
                    "  \"hdrWeightPolicy\": \"${frame.metadata.weightPolicy.name}\","
                )
                appendLine(
                    "  \"highlightCoherencePolicy\": " +
                        "\"${frame.metadata.highlightCoherencePolicy.name}\","
                )
                appendLine(
                    "  \"sharedHighlightFrameBlocks\": " +
                        "${frame.metadata.sharedHighlightFrameBlocks},"
                )
                appendLine(
                    "  \"blockSaturationZeroWeightSamples\": " +
                        "${frame.metadata.blockSaturationZeroWeightSamples},"
                )
                appendLine("  \"demosaic\": \"BILINEAR_FLOAT32\",")
                appendLine(
                    "  \"normalizationFormula\": " +
                        "\"radiance * referenceExposureSeconds / " +
                        "(whiteLevel - blackLevelForCfaPhase)\","
                )
                appendLine(
                    "  \"referenceExposureTimeNs\": " +
                        "${request.options.referenceExposureTimeNs ?: frame.metadata.referenceExposureTimeNs},"
                )
                appendLine("  \"whiteLevel\": ${frame.metadata.whiteLevel},")
                appendLine(
                    "  \"blackLevelPattern\": " +
                        frame.metadata.blackLevelPattern.joinToString("[", "]") +
                        ","
                )
                appendLine("  \"photometricInterpretation\": \"LinearRaw\",")
                appendLine("  \"bitsPerSample\": [16, 16, 16],")
                appendLine("  \"sampleFormat\": [\"Float\", \"Float\", \"Float\"],")
                appendLine("  \"samplesPerPixel\": 3,")
                appendLine("  \"rowsPerStrip\": ${result.rowsPerStrip},")
                appendLine("  \"compression\": \"None\",")
                appendLine("  \"colorMetadataComplete\": true,")
                appendLine("  \"outputBytes\": ${result.outputBytes},")
                appendLine(
                    "  \"maxRgbBeforeScale\": ${diagnostics?.maxRgbBeforeScale ?: 0.0},"
                )
                appendLine(
                    "  \"globalScaleApplied\": ${diagnostics?.globalScaleApplied ?: 1.0},"
                )
                appendLine(
                    "  \"baselineExposureWritten\": " +
                        "${diagnostics?.baselineExposureWritten ?: 0.0},"
                )
                appendLine(
                    "  \"valuesAboveOneBeforeScale\": " +
                        "${diagnostics?.valuesAboveOneBeforeScale ?: 0L},"
                )
                appendLine(
                    "  \"valuesAboveOneAfterScale\": " +
                        "${diagnostics?.valuesAboveOneAfterScale ?: 0L},"
                )
                appendLine("  \"float16MaximumClamps\": ${diagnostics?.maximumClamps ?: 0L},")
                appendLine(
                    "  \"invalidValueReplacements\": " +
                        "${diagnostics?.invalidValueReplacements ?: 0L},"
                )
                appendLine("  \"negativeClamps\": ${diagnostics?.negativeClamps ?: 0L},")
                appendLine("  \"minimumRgb\": ${diagnostics?.minimumRgb ?: "null"},")
                appendLine("  \"maximumRgb\": ${diagnostics?.maximumRgb ?: "null"},")
                appendLine("  \"meanRgb\": ${diagnostics?.meanRgb ?: "null"},")
                appendLine(
                    "  \"expectedImageBytes\": ${diagnostics?.expectedImageBytes ?: 0L},"
                )
                appendLine(
                    "  \"sumStripByteCounts\": ${diagnostics?.sumStripByteCounts ?: 0L},"
                )
                appendLine(
                    "  \"stripTableValid\": ${diagnostics?.stripTableValid ?: false},"
                )
                appendLine(
                    "  \"processingDurationMs\": " +
                        "${diagnostics?.processingDurationMs ?: 0L},"
                )
                appendLine("  \"temporaryCleanupSucceeded\": ${result.cleanupSucceeded},")
                appendLine("  \"toneMappingApplied\": false,")
                appendLine("  \"gammaApplied\": false,")
                appendLine("  \"contrastApplied\": false,")
                appendLine("  \"saturationApplied\": false,")
                appendLine("  \"curveApplied\": false,")
                appendLine("  \"globalAdobeNormalizationApplied\": true,")
                appendLine("  \"globalAdobeNormalizationCompensatedByBaselineExposure\": true,")
                appendLine("  \"whiteBalanceBakedIntoPixels\": false")
                appendLine("}")
            }
        val parent = requireNotNull(target.parentFile) {
            "Linear RGB Float16 DNG sidecar requires a parent directory."
        }
        check(parent.isDirectory || parent.mkdirs()) {
            "Could not create Linear RGB Float16 DNG sidecar directory."
        }
        val temporary = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            temporary.writeText(contents, Charsets.UTF_8)
            if (target.exists() && !target.delete()) {
                error("Could not replace Linear RGB Float16 DNG sidecar.")
            }
            check(temporary.renameTo(target)) {
                "Could not commit Linear RGB Float16 DNG sidecar."
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun quoted(value: String?): String =
        value?.let { "\"${escape(it)}\"" } ?: "null"

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
