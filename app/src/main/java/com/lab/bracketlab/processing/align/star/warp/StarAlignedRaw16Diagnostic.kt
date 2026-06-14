package com.lab.bracketlab.processing.align.star.warp

import com.lab.bracketlab.processing.align.star.StarAlignmentProcessor
import com.lab.bracketlab.processing.align.star.StarAlignmentReport
import com.lab.bracketlab.processing.align.star.StarDetectionOptions
import com.lab.bracketlab.processing.align.star.StarDetector
import com.lab.bracketlab.processing.align.star.StarMatchingOptions
import com.lab.bracketlab.processing.debug.IncrementalDiagnosticReport
import com.lab.bracketlab.processing.debug.SingleFlightGuard
import com.lab.bracketlab.processing.io.FileRaw16DngOutputDestination
import com.lab.bracketlab.processing.io.Raw16DngWriteOptions
import com.lab.bracketlab.processing.pipeline.CapturedRawSequence
import com.lab.bracketlab.processing.stack.RawStackAggregationMode
import com.lab.bracketlab.processing.stack.RawStackAggregationOptions
import java.io.File
import java.util.Locale

data class StarAlignedRaw16DiagnosticResult(
    val success: Boolean,
    val dngPath: String? = null,
    val dngBytes: Long? = null,
    val reportPath: String? = null,
    val jsonPath: String? = null,
    val referenceFrameIndex: Int? = null,
    val logLines: List<String> = emptyList(),
    val failureMessage: String? = null,
    val inputCleanupSucceeded: Boolean = true,
    val temporaryCleanupSucceeded: Boolean = true
)

class StarAlignedRaw16Diagnostic(
    private val rootDirectory: File
) {
    fun run(
        sequence: CapturedRawSequence,
        detectionOptions: StarDetectionOptions =
            StarDetectionOptions(
                allowLumaFallback = true,
                suppressHotPixelsForProxy = false
            ),
        matchingOptions: StarMatchingOptions = StarMatchingOptions(),
        stackOptions: StarAlignedRaw16StackOptions =
            StarAlignedRaw16StackOptions(
                aggregationOptions =
                    RawStackAggregationOptions(mode = RawStackAggregationMode.MEAN)
            )
    ): StarAlignedRaw16DiagnosticResult {
        if (!guard.tryAcquire()) {
            return StarAlignedRaw16DiagnosticResult(
                success = false,
                failureMessage = "Another star-aligned RAW16 diagnostic is active."
            )
        }
        val logs = mutableListOf<String>()
        val directory =
            File(
                rootDirectory,
                "session_${sequence.createdAtMillis}_star_aligned"
            ).also(File::mkdirs)
        val reportFile = File(directory, REPORT_FILENAME)
        val jsonFile = File(directory, JSON_FILENAME)
        val packedFile = File(directory, ".$DNG_FILENAME.packed.raw16")
        val dngFile = File(directory, DNG_FILENAME)
        var report: IncrementalDiagnosticReport? = null
        var inputCleanupAttempted = false
        try {
            report = IncrementalDiagnosticReport(reportFile)
            append(report, logs, "BracketLab Star-Aligned RAW16 DEV diagnostic")
            append(report, logs, "Frames=${sequence.frameCount}")
            append(report, logs, "Camera=${sequence.cameraId}")
            append(report, logs, "Transform convention=TARGET/SOURCE to REFERENCE")
            append(report, logs, "Warp=CFA-phase-safe bilinear")
            append(report, logs, "Aggregation=${stackOptions.aggregationOptions.mode}")

            val rawStack = sequence.toRawStack()
            val storageEstimate = storageEstimate(rawStack, directory)
            append(
                report,
                logs,
                "Storage required=${storageEstimate.requiredBytes} " +
                    "available=${storageEstimate.availableBytes}"
            )
            if (!storageEstimate.sufficient) {
                error("Insufficient storage for star-aligned RAW16 export.")
            }
            val detection = StarDetector().detect(rawStack, detectionOptions)
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
            if (detection.catalogs.size != rawStack.frameCount) {
                error(
                    detection.failureMessage
                        ?: "Star detection did not produce one catalog per RAW frame."
                )
            }

            val alignment =
                StarAlignmentProcessor().align(detection.catalogs, matchingOptions)
            appendAlignment(report, logs, alignment)
            if (!alignment.success && !alignment.partialSuccess) {
                error(alignment.fatalMessage ?: "Star alignment failed.")
            }
            val export =
                StarAlignedRaw16ExportPipeline().processAndWrite(
                    rawStack = rawStack,
                    alignmentReport = alignment,
                    cameraMetadataProvider = sequence,
                    stackOptions = stackOptions,
                    writeOptions =
                        Raw16DngWriteOptions(
                            description = null,
                            sequenceIdentifier = "DEV_STAR_ALIGNED"
                        ),
                    outputDestination =
                        FileRaw16DngOutputDestination(dngFile, overwrite = true),
                    temporaryPackedRaw16 = packedFile
                )
            val stack = export.stackResult
            stack?.let {
                append(
                    report,
                    logs,
                    "Tile=${it.tilePlan?.tileWidth}x${it.tilePlan?.tileHeight} " +
                        "estimatedWorkingBytes=${it.tilePlan?.estimatedWorkingBytes}"
                )
                append(
                    report,
                    logs,
                    "Contributors min=${it.minimumValidCount} max=${it.maximumValidCount} " +
                        "mean=${format(it.meanValidCount)} referenceOnly=${it.referenceOnlyPixelCount} " +
                        "allFrames=${it.fullContributorPixelCount}"
                )
                append(
                    report,
                    logs,
                    "Aggregation rejected=${it.aggregationDiagnostics.totalRejectedSamples} " +
                        "fallbackMean=${it.aggregationDiagnostics.pixelsFallingBackToMean}"
                )
                it.frameDiagnostics.forEach { frame ->
                    val alignmentFrame =
                        alignment.frameResults.firstOrNull {
                            it.frameIndex == frame.frameIndex
                        }
                    append(
                        report,
                        logs,
                        "Frame=${frame.frameIndex} reference=${frame.reference} " +
                            "tx=${format(alignmentFrame?.transform?.tx)} " +
                            "ty=${format(alignmentFrame?.transform?.ty)} " +
                            "rotation=${format(alignmentFrame?.transform?.rotationDegrees)} " +
                            "scale=${format(alignmentFrame?.transform?.scale)} " +
                            "inliers=${alignmentFrame?.ransacInlierCount ?: 0} " +
                            "rms=${format(alignmentFrame?.rmsResidualRawPixels)}"
                    )
                    append(
                        report,
                        logs,
                        "Frame=${frame.frameIndex} validFraction=${format(frame.validFraction)} " +
                            "direct=${frame.directSamples} horizontal=${frame.horizontalLinearSamples} " +
                            "vertical=${frame.verticalLinearSamples} bilinear=${frame.bilinearSamples} " +
                            "invalid=${frame.invalidSamples}"
                    )
                }
                it.warnings.forEach { warning ->
                    append(
                        report,
                        logs,
                        "Warning=${warning.code} frame=${warning.frameIndex}: ${warning.message}"
                    )
                }
            }
            export.dngWriteResult?.warnings?.forEach {
                append(report, logs, "Writer warning=${it.code}: ${it.message}")
            }
            append(report, logs, "DNG success=${export.success}")
            append(report, logs, "DNG path=${export.dngWriteResult?.finalPath}")
            append(report, logs, "DNG bytes=${export.dngWriteResult?.bytesWritten}")
            append(report, logs, "Temporary RAW cleanup=${export.temporaryRawCleanupSucceeded}")
            append(report, logs, "DurationMs=${export.totalDurationMs}")
            export.failureMessage?.let { append(report, logs, "Failure=$it") }
            writeJsonAtomically(jsonFile, alignment, stack)
            append(report, logs, "Transform/stack JSON=${jsonFile.absolutePath}")
            append(report, logs, "Final success=${export.success}")
            val temporaryCleanup =
                export.temporaryRawCleanupSucceeded &&
                    (!packedFile.exists() || packedFile.delete())
            val inputCleanup =
                runCatching { sequence.cleanupTemporaryRawFrames() }.getOrDefault(false)
            inputCleanupAttempted = true
            append(report, logs, "Input cleanup=$inputCleanup")
            return StarAlignedRaw16DiagnosticResult(
                success = export.success,
                dngPath = export.dngWriteResult?.finalPath,
                dngBytes = export.dngWriteResult?.bytesWritten,
                reportPath = reportFile.absolutePath,
                jsonPath = jsonFile.absolutePath,
                referenceFrameIndex = alignment.referenceFrameIndex,
                logLines = logs,
                failureMessage = export.failureMessage,
                inputCleanupSucceeded = inputCleanup,
                temporaryCleanupSucceeded = temporaryCleanup
            )
        } catch (error: Throwable) {
            val message = "${error.javaClass.simpleName}: ${error.message}"
            appendSafely(report, logs, "Failure=$message")
            val temporaryCleanup =
                listOf(packedFile, File(directory, ".$DNG_FILENAME.tmp"))
                    .all { !it.exists() || it.delete() }
            val inputCleanup =
                runCatching { sequence.cleanupTemporaryRawFrames() }.getOrDefault(false)
            inputCleanupAttempted = true
            appendSafely(report, logs, "Input cleanup=$inputCleanup")
            return StarAlignedRaw16DiagnosticResult(
                success = false,
                reportPath = reportFile.takeIf(File::exists)?.absolutePath,
                jsonPath = jsonFile.takeIf(File::exists)?.absolutePath,
                logLines = logs,
                failureMessage = message,
                inputCleanupSucceeded = inputCleanup,
                temporaryCleanupSucceeded = temporaryCleanup
            )
        } finally {
            if (!inputCleanupAttempted) {
                val inputCleanup =
                    runCatching { sequence.cleanupTemporaryRawFrames() }.getOrDefault(false)
                appendSafely(report, logs, "Input cleanup=$inputCleanup")
            }
            appendSafely(report, logs, "Processing guard released")
            runCatching { report?.close() }
            guard.release()
        }
    }

    private fun appendAlignment(
        report: IncrementalDiagnosticReport,
        logs: MutableList<String>,
        alignment: StarAlignmentReport
    ) {
        append(
            report,
            logs,
            "Alignment status=${alignment.status} reference=${alignment.referenceFrameIndex} " +
                "accepted=${alignment.acceptedFrameCount} rejected=${alignment.rejectedFrameCount}"
        )
        alignment.frameResults.forEach { frame ->
            append(
                report,
                logs,
                "Alignment frame=${frame.frameIndex} accepted=${frame.accepted} " +
                    "inliers=${frame.ransacInlierCount} rms=${format(frame.rmsResidualRawPixels)} " +
                    "tx=${format(frame.transform.tx)} ty=${format(frame.transform.ty)} " +
                    "rotation=${format(frame.transform.rotationDegrees)} " +
                    "scale=${format(frame.transform.scale)}"
            )
        }
        alignment.warnings.forEach { append(report, logs, "Alignment warning=$it") }
    }

    private fun writeJsonAtomically(
        output: File,
        alignment: StarAlignmentReport,
        stack: StarAlignedRaw16StackResult?
    ) {
        val temporary = File(output.parentFile, ".${output.name}.${System.nanoTime()}.tmp")
        try {
            temporary.writeText(
                buildString {
                    appendLine("{")
                    appendLine("  \"referenceFrameIndex\": ${alignment.referenceFrameIndex},")
                    appendLine("  \"acceptedFrameCount\": ${alignment.acceptedFrameCount},")
                    appendLine("  \"stackSuccess\": ${stack?.success ?: false},")
                    appendLine("  \"tileWidth\": ${stack?.tilePlan?.tileWidth ?: "null"},")
                    appendLine("  \"tileHeight\": ${stack?.tilePlan?.tileHeight ?: "null"},")
                    appendLine("  \"estimatedWorkingBytes\": ${stack?.tilePlan?.estimatedWorkingBytes ?: "null"},")
                    appendLine("  \"minimumValidCount\": ${stack?.minimumValidCount ?: "null"},")
                    appendLine("  \"maximumValidCount\": ${stack?.maximumValidCount ?: "null"},")
                    appendLine("  \"meanValidCount\": ${stack?.meanValidCount ?: "null"},")
                    appendLine("  \"referenceOnlyPixelCount\": ${stack?.referenceOnlyPixelCount ?: "null"},")
                    appendLine("  \"frames\": [")
                    alignment.frameResults.forEachIndexed { index, frame ->
                        appendLine("    {")
                        appendLine("      \"frameIndex\": ${frame.frameIndex},")
                        appendLine("      \"reference\": ${frame.isReference},")
                        appendLine("      \"accepted\": ${frame.accepted},")
                        appendLine("      \"a\": ${frame.transform.a},")
                        appendLine("      \"b\": ${frame.transform.b},")
                        appendLine("      \"tx\": ${frame.transform.tx},")
                        appendLine("      \"ty\": ${frame.transform.ty},")
                        appendLine("      \"rotationDegrees\": ${frame.transform.rotationDegrees},")
                        appendLine("      \"scale\": ${frame.transform.scale},")
                        appendLine("      \"inliers\": ${frame.ransacInlierCount},")
                        appendLine("      \"rmsRawPixels\": ${frame.rmsResidualRawPixels}")
                        append("    }")
                        appendLine(if (index == alignment.frameResults.lastIndex) "" else ",")
                    }
                    appendLine("  ]")
                    appendLine("}")
                },
                Charsets.UTF_8
            )
            if (output.exists() && !output.delete()) error("Could not replace transform JSON.")
            if (!temporary.renameTo(output)) {
                temporary.copyTo(output, overwrite = true)
                if (!temporary.delete()) error("Could not remove temporary transform JSON.")
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun storageEstimate(
        rawStack: com.lab.bracketlab.processing.model.RawStack,
        directory: File
    ): StorageEstimate {
        val frame = rawStack.frames.firstOrNull()
            ?: return StorageEstimate(0L, directory.usableSpace, false)
        val packedBytes =
            frame.width.toLong() * frame.height.toLong() * 2L
        val workingAndDng = packedBytes * 2L
        val withMargin =
            (workingAndDng.toDouble() * 1.20).toLong() +
                64L * 1024L * 1024L
        val available = directory.usableSpace
        return StorageEstimate(
            requiredBytes = withMargin,
            availableBytes = available,
            sufficient = available > 0L && available >= withMargin
        )
    }

    private fun append(
        report: IncrementalDiagnosticReport,
        logs: MutableList<String>,
        line: String
    ) {
        report.append(line)
        logs += line
    }

    private fun appendSafely(
        report: IncrementalDiagnosticReport?,
        logs: MutableList<String>,
        line: String
    ) {
        logs += line
        runCatching { report?.append(line) }
    }

    private fun format(value: Double?): String =
        value?.takeIf(Double::isFinite)?.let {
            String.format(Locale.US, "%.6f", it)
        } ?: "n/a"

    companion object {
        private const val DNG_FILENAME = "BracketLab_DEV_StarAligned_RAW16.dng"
        private const val REPORT_FILENAME = "star_aligned_raw16_report.txt"
        private const val JSON_FILENAME = "star_aligned_raw16_transforms.json"
        private val guard = SingleFlightGuard()

        fun isProcessing(): Boolean = guard.isActive()
    }

    private data class StorageEstimate(
        val requiredBytes: Long,
        val availableBytes: Long,
        val sufficient: Boolean
    )
}
