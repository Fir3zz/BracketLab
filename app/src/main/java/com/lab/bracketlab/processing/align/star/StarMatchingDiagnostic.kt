package com.lab.bracketlab.processing.align.star

import com.lab.bracketlab.processing.debug.IncrementalDiagnosticReport
import com.lab.bracketlab.processing.debug.SingleFlightGuard
import com.lab.bracketlab.processing.pipeline.CapturedRawSequence
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StarMatchingDiagnosticResult(
    val success: Boolean,
    val reportPath: String? = null,
    val jsonPath: String? = null,
    val catalogPaths: List<String> = emptyList(),
    val logLines: List<String> = emptyList(),
    val failureCode: StarMatchingFailureCode? = null,
    val failureMessage: String? = null,
    val inputCleanupSucceeded: Boolean = true
)

class StarMatchingDiagnostic(
    private val rootDirectory: File
) {
    fun run(
        sequence: CapturedRawSequence,
        detectionOptions: StarDetectionOptions = StarDetectionOptions(
            allowLumaFallback = true,
            suppressHotPixelsForProxy = false
        ),
        matchingOptions: StarMatchingOptions = StarMatchingOptions()
    ): StarMatchingDiagnosticResult {
        if (!guard.tryAcquire()) {
            return StarMatchingDiagnosticResult(
                success = false,
                failureCode = StarMatchingFailureCode.MATCHING_FAILED,
                failureMessage = "Another star matching diagnostic is active."
            )
        }
        val logs = mutableListOf<String>()
        val directory = sessionDirectory(sequence)
        val reportFile = File(directory, REPORT_FILENAME)
        val jsonFile = File(directory, JSON_FILENAME)
        var report: IncrementalDiagnosticReport? = null
        var cleanupSucceeded: Boolean
        try {
            report = IncrementalDiagnosticReport(reportFile)
            append(report, logs, "BracketLab Star Matching / RANSAC DEV diagnostic")
            append(report, logs, "Frames=${sequence.frameCount}")
            append(report, logs, "Camera=${sequence.cameraId}")
            append(report, logs, "Transform convention=TARGET/SOURCE to REFERENCE")
            append(report, logs, "RANSAC thresholdRawPx=${matchingOptions.reprojectionThresholdRawPixels}")
            append(report, logs, "RANSAC maxIterations=${matchingOptions.ransacMaxIterations}")
            append(report, logs, "RANSAC confidence=${matchingOptions.ransacConfidence}")
            append(report, logs, "No RAW transform, warp or stack is performed.")

            val detection = StarDetector().detect(sequence.toRawStack(), detectionOptions)
            append(
                report,
                logs,
                "Detection success=${detection.success} catalogs=${detection.catalogs.size}"
            )
            detection.catalogs.forEach {
                append(
                    report,
                    logs,
                    "Detection frame=${it.frameIndex} stars=${it.starCount} " +
                        "proxy=${it.proxyType} ${it.proxyWidth}x${it.proxyHeight}"
                )
            }
            if (detection.catalogs.isEmpty()) {
                throw IllegalStateException(
                    "Star detection produced no catalogs: ${detection.failureCode}"
                )
            }
            val catalogWrite = StarCatalogStore.writeCatalogs(detection.catalogs, directory)
            append(report, logs, "Catalog storage success=${catalogWrite.success}")
            if (!catalogWrite.success) {
                throw IllegalStateException(catalogWrite.failureMessage ?: "Catalog storage failed.")
            }

            val alignment = StarAlignmentProcessor().align(detection.catalogs, matchingOptions)
            append(
                report,
                logs,
                "Reference frame=${alignment.referenceFrameIndex} " +
                    "reason=${alignment.referenceSelectionReason} " +
                    "stars=${alignment.referenceStarCount} medianSnr=${alignment.referenceMedianSnr}"
            )
            alignment.warnings.forEach { append(report, logs, "Warning=$it") }
            alignment.frameResults.forEach { frame ->
                append(
                    report,
                    logs,
                    "Frame=${frame.frameIndex} reference=${frame.isReference} " +
                        "strategy=${frame.matchingStrategy} candidates=${frame.candidateMatchCount} " +
                        "inliers=${frame.ransacInlierCount} outliers=${frame.outlierCount} " +
                        "ratio=${format(frame.inlierRatio)}"
                )
                append(
                    report,
                    logs,
                    "Frame=${frame.frameIndex} tx=${format(frame.transform.tx)} " +
                        "ty=${format(frame.transform.ty)} " +
                        "rotationDeg=${format(frame.transform.rotationDegrees)} " +
                        "scale=${format(frame.transform.scale)}"
                )
                append(
                    report,
                    logs,
                    "Frame=${frame.frameIndex} rms=${formatNullable(frame.rmsResidualRawPixels)} " +
                        "median=${formatNullable(frame.medianResidualRawPixels)} " +
                        "max=${formatNullable(frame.maximumResidualRawPixels)} " +
                        "accepted=${frame.accepted} failure=${frame.failureCode ?: "NONE"}"
                )
                frame.spatialDistribution?.let {
                    append(
                        report,
                        logs,
                        "Frame=${frame.frameIndex} coverage=${format(it.boundingBoxCoverageFraction)} " +
                            "grid=${it.occupiedGridCells}/${it.totalGridCells} " +
                            "eigenRatio=${format(it.geometryEigenvalueRatio)}"
                    )
                }
            }
            append(
                report,
                logs,
                "Alignment status=${alignment.status} accepted=${alignment.acceptedFrameCount} " +
                    "rejected=${alignment.rejectedFrameCount} durationMs=${alignment.durationMs}"
            )
            writeJsonAtomically(jsonFile, alignment)
            append(report, logs, "JSON report=${jsonFile.absolutePath}")
            append(report, logs, "RAW transform applied=false")
            append(report, logs, "Final success=${alignment.success}")
            cleanupSucceeded =
                runCatching { sequence.cleanupTemporaryRawFrames() }.getOrDefault(false)
            append(report, logs, "Input cleanup=$cleanupSucceeded")
            return StarMatchingDiagnosticResult(
                success = alignment.success,
                reportPath = reportFile.absolutePath,
                jsonPath = jsonFile.absolutePath,
                catalogPaths = catalogWrite.catalogPaths,
                logLines = logs,
                failureCode = alignment.fatalError,
                failureMessage = alignment.fatalMessage,
                inputCleanupSucceeded = cleanupSucceeded
            )
        } catch (error: Throwable) {
            runCatching {
                report?.append("Failure=${error.javaClass.simpleName}: ${error.message}")
            }
            cleanupSucceeded =
                runCatching { sequence.cleanupTemporaryRawFrames() }.getOrDefault(false)
            runCatching { report?.append("Input cleanup=$cleanupSucceeded") }
            return StarMatchingDiagnosticResult(
                success = false,
                reportPath = reportFile.absolutePath,
                jsonPath = jsonFile.takeIf { it.exists() }?.absolutePath,
                logLines = logs,
                failureCode = StarMatchingFailureCode.MATCHING_FAILED,
                failureMessage = "${error.javaClass.simpleName}: ${error.message}",
                inputCleanupSucceeded = cleanupSucceeded
            )
        } finally {
            runCatching { report?.close() }
            guard.release()
        }
    }

    private fun sessionDirectory(sequence: CapturedRawSequence): File {
        val timestamp =
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(Date(sequence.createdAtMillis))
        return File(rootDirectory, "session_${timestamp}_matching")
    }

    private fun writeJsonAtomically(
        output: File,
        alignment: StarAlignmentReport
    ) {
        output.parentFile?.mkdirs()
        val temporary = File(output.parentFile, ".${output.name}.${System.nanoTime()}.tmp")
        try {
            temporary.writeText(toJson(alignment), Charsets.UTF_8)
            if (output.exists() && !output.delete()) {
                error("Could not replace existing star alignment JSON.")
            }
            if (!temporary.renameTo(output)) {
                temporary.copyTo(output, overwrite = true)
                if (!temporary.delete()) error("Could not remove temporary JSON report.")
            }
        } catch (error: Throwable) {
            temporary.delete()
            output.delete()
            throw error
        }
    }

    private fun toJson(report: StarAlignmentReport): String =
        buildString {
            appendLine("{")
            appendLine("  \"status\": \"${report.status}\",")
            appendLine("  \"success\": ${report.success},")
            appendLine("  \"referenceFrameIndex\": ${report.referenceFrameIndex ?: "null"},")
            appendLine("  \"referenceSelectionReason\": ${jsonString(report.referenceSelectionReason?.name)},")
            appendLine("  \"totalFrameCount\": ${report.totalFrameCount},")
            appendLine("  \"acceptedFrameCount\": ${report.acceptedFrameCount},")
            appendLine("  \"rejectedFrameCount\": ${report.rejectedFrameCount},")
            appendLine("  \"durationMs\": ${report.durationMs},")
            appendLine("  \"frames\": [")
            report.frameResults.forEachIndexed { index, frame ->
                appendLine("    {")
                appendLine("      \"frameIndex\": ${frame.frameIndex},")
                appendLine("      \"reference\": ${frame.isReference},")
                appendLine("      \"strategy\": \"${frame.matchingStrategy}\",")
                appendLine("      \"candidateMatches\": ${frame.candidateMatchCount},")
                appendLine("      \"inliers\": ${frame.ransacInlierCount},")
                appendLine("      \"outliers\": ${frame.outlierCount},")
                appendLine("      \"inlierRatio\": ${number(frame.inlierRatio)},")
                appendLine("      \"rmsRawPixels\": ${numberOrNull(frame.rmsResidualRawPixels)},")
                appendLine("      \"medianRawPixels\": ${numberOrNull(frame.medianResidualRawPixels)},")
                appendLine("      \"maximumRawPixels\": ${numberOrNull(frame.maximumResidualRawPixels)},")
                appendLine("      \"txRawPixels\": ${number(frame.transform.tx)},")
                appendLine("      \"tyRawPixels\": ${number(frame.transform.ty)},")
                appendLine("      \"rotationDegrees\": ${number(frame.transform.rotationDegrees)},")
                appendLine("      \"scale\": ${number(frame.transform.scale)},")
                appendLine("      \"matrix2x3\": [${frame.transform.matrix2x3.joinToString { number(it) }}],")
                appendLine("      \"accepted\": ${frame.accepted},")
                appendLine("      \"failureCode\": ${jsonString(frame.failureCode?.name)}")
                append("    }")
                appendLine(if (index == report.frameResults.lastIndex) "" else ",")
            }
            appendLine("  ]")
            appendLine("}")
        }

    private fun append(
        report: IncrementalDiagnosticReport,
        logs: MutableList<String>,
        line: String
    ) {
        report.append(line)
        logs += line
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun formatNullable(value: Double?): String =
        value?.let(::format) ?: "--"

    private fun number(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.12g", value) else "null"

    private fun numberOrNull(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let(::number) ?: "null"

    private fun jsonString(value: String?): String =
        value?.let { "\"$it\"" } ?: "null"

    companion object {
        private const val REPORT_FILENAME = "star_matching_report.txt"
        private const val JSON_FILENAME = "star_alignment_report.json"
        private val guard = SingleFlightGuard()

        fun isProcessing(): Boolean = guard.isActive()
    }
}
