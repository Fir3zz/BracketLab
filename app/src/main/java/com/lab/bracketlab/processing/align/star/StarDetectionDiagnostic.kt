package com.lab.bracketlab.processing.align.star

import com.lab.bracketlab.processing.debug.IncrementalDiagnosticReport
import com.lab.bracketlab.processing.debug.SingleFlightGuard
import com.lab.bracketlab.processing.pipeline.CapturedRawSequence
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StarDetectionDiagnosticResult(
    val success: Boolean,
    val catalogPaths: List<String> = emptyList(),
    val reportPath: String? = null,
    val logLines: List<String> = emptyList(),
    val failureCode: StarDetectionFailureCode? = null,
    val failureMessage: String? = null,
    val inputCleanupSucceeded: Boolean = true
)

class StarDetectionDiagnostic(
    private val rootDirectory: File
) {
    fun run(
        sequence: CapturedRawSequence,
        options: StarDetectionOptions = StarDetectionOptions(
            allowLumaFallback = true,
            suppressHotPixelsForProxy = false
        )
    ): StarDetectionDiagnosticResult {
        if (!guard.tryAcquire()) {
            return StarDetectionDiagnosticResult(
                success = false,
                failureCode = StarDetectionFailureCode.STAR_DETECTION_FAILED,
                failureMessage = "Another star detection diagnostic is active."
            )
        }
        val logs = mutableListOf<String>()
        val directory = sessionDirectory(sequence)
        val reportFile = File(directory, REPORT_FILENAME)
        var report: IncrementalDiagnosticReport? = null
        var inputCleanup: Boolean
        try {
            report = IncrementalDiagnosticReport(reportFile)
            append(report, logs, "BracketLab Star Detection DEV diagnostic")
            append(report, logs, "Frames=${sequence.frameCount}")
            append(report, logs, "Camera=${sequence.cameraId}")
            append(report, logs, "Proxy=${options.primaryProxyType}")
            append(report, logs, "Proxy max dimension=${options.proxyMaxDimension}")
            append(report, logs, "Exposure normalized=${options.exposureNormalizeProxies}")
            append(report, logs, "Threshold sigma=${options.thresholdSigma}")
            append(report, logs, "Centroid radius=${options.centroidRadius}")
            append(report, logs, "No matching, RANSAC or transform estimation is performed.")

            val detection = StarDetector().detect(sequence.toRawStack(), options)
            for (catalog in detection.catalogs) {
                append(
                    report,
                    logs,
                    "Frame ${catalog.frameIndex}: proxy=${catalog.proxyType} " +
                        "${catalog.proxyWidth}x${catalog.proxyHeight} " +
                        "scale=${catalog.scaleX},${catalog.scaleY}"
                )
                append(
                    report,
                    logs,
                    "Frame ${catalog.frameIndex}: background=${catalog.backgroundEstimate} " +
                        "sigma=${catalog.noiseEstimate} threshold=${catalog.thresholdUsed}"
                )
                append(
                    report,
                    logs,
                    "Frame ${catalog.frameIndex}: localMaxima=${catalog.localMaximumCount} " +
                        "stars=${catalog.starCount} status=${catalog.statusCode ?: "OK"}"
                )
                append(
                    report,
                    logs,
                    "Frame ${catalog.frameIndex}: rejected=" +
                        catalog.rejectedCandidateCounts.entries.joinToString {
                            "${it.key}=${it.value}"
                        }
                )
                catalog.stars.take(TOP_STARS_TO_REPORT).forEach { star ->
                    append(
                        report,
                        logs,
                        "Star frame=${catalog.frameIndex} id=${star.id} " +
                            "proxy=${format(star.proxyX)},${format(star.proxyY)} " +
                            "raw=${format(star.fullX)},${format(star.fullY)} " +
                            "snr=${format(star.snr)} flux=${format(star.flux)}"
                    )
                }
                catalog.warnings.forEach {
                    append(report, logs, "Frame ${catalog.frameIndex} warning=$it")
                }
            }
            detection.globalWarnings.forEach { append(report, logs, "Warning=$it") }
            append(report, logs, "Detection durationMs=${detection.durationMs}")

            val stored = StarCatalogStore.writeCatalogs(detection.catalogs, directory)
            append(report, logs, "Catalog storage success=${stored.success}")
            stored.catalogPaths.forEach { append(report, logs, "Catalog path=$it") }
            if (!stored.success) {
                append(
                    report,
                    logs,
                    "Catalog failure=${stored.failureCode}: ${stored.failureMessage}"
                )
            }
            append(report, logs, "Transform estimated=false")
            inputCleanup = runCatching { sequence.cleanupTemporaryRawFrames() }.getOrDefault(false)
            append(report, logs, "Input cleanup=$inputCleanup")
            append(report, logs, "Final success=${detection.success && stored.success}")
            return StarDetectionDiagnosticResult(
                success = detection.success && stored.success,
                catalogPaths = stored.catalogPaths,
                reportPath = reportFile.absolutePath,
                logLines = logs,
                failureCode = detection.failureCode ?: stored.failureCode,
                failureMessage = detection.failureMessage ?: stored.failureMessage,
                inputCleanupSucceeded = inputCleanup
            )
        } catch (error: Throwable) {
            runCatching {
                report?.append("Failure=${error.javaClass.simpleName}: ${error.message}")
            }
            inputCleanup = runCatching { sequence.cleanupTemporaryRawFrames() }.getOrDefault(false)
            runCatching { report?.append("Input cleanup=$inputCleanup") }
            return StarDetectionDiagnosticResult(
                success = false,
                reportPath = reportFile.absolutePath,
                logLines = logs,
                failureCode = StarDetectionFailureCode.STAR_DETECTION_FAILED,
                failureMessage = "${error.javaClass.simpleName}: ${error.message}",
                inputCleanupSucceeded = inputCleanup
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
        return File(rootDirectory, "session_$timestamp")
    }

    private fun append(
        report: IncrementalDiagnosticReport,
        logs: MutableList<String>,
        line: String
    ) {
        report.append(line)
        logs += line
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)

    companion object {
        private const val REPORT_FILENAME = "star_detection_report.txt"
        private const val TOP_STARS_TO_REPORT = 10
        private val guard = SingleFlightGuard()

        fun isProcessing(): Boolean = guard.isActive()
    }
}
