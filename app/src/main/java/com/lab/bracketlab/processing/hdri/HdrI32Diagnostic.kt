package com.lab.bracketlab.processing.hdri

import com.lab.bracketlab.processing.align.LandscapeAlignmentReport
import com.lab.bracketlab.processing.debug.IncrementalDiagnosticReport
import com.lab.bracketlab.processing.debug.SingleFlightGuard
import com.lab.bracketlab.processing.hdri.export.dng.LinearRgbFloat16DngExportOptions
import com.lab.bracketlab.processing.hdri.export.dng.LinearRgbFloat16DngMetadataExtractor
import com.lab.bracketlab.processing.hdri.export.dng.LinearRgbFloat16DngSidecar
import com.lab.bracketlab.processing.hdri.export.dng.LinearRgbFloat16DngWriteRequest
import com.lab.bracketlab.processing.hdri.export.dng.LinearRgbFloat16DngWriter
import com.lab.bracketlab.processing.hdri.export.dng.LinearRgbFloat16StorageEstimator
import com.lab.bracketlab.processing.pipeline.CapturedRawSequence
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HdrI32DiagnosticResult(
    val success: Boolean,
    val floatPath: String? = null,
    val xisfPath: String? = null,
    val fitsPath: String? = null,
    val linearRgbFloat16DngPath: String? = null,
    val metadataPath: String? = null,
    val reportPath: String? = null,
    val outputBytes: Long = 0L,
    val warnings: List<HdrI32Warning> = emptyList(),
    val failureCode: HdrI32FailureCode? = null,
    val failureMessage: String? = null,
    val cleanupSucceeded: Boolean = true,
    val logLines: List<String> = emptyList()
)

class HdrI32Diagnostic(
    private val rootDirectory: File
) {
    fun run(
        sequence: CapturedRawSequence,
        darkCalibrationRequested: Boolean = false,
        exportOptions: HdrI32ExportOptions = HdrI32ExportOptions(),
        landscapeAlignmentReport: LandscapeAlignmentReport? = null
    ): HdrI32DiagnosticResult {
        if (!processing.tryAcquire()) {
            return HdrI32DiagnosticResult(
                success = false,
                failureMessage = "HDR diagnostic rejected: another HDR operation is active."
            )
        }
        val logs = mutableListOf<String>()
        val sessionDirectory = sessionDirectory(sequence)
        val reportFile = File(sessionDirectory, HdrI32Store.REPORT_FILENAME)
        var report: IncrementalDiagnosticReport? = null
        var result: HdrI32DiagnosticResult
        try {
            report = IncrementalDiagnosticReport(reportFile)
            append(report, logs, "BracketLab HDR 32F DEV diagnostic")
            val alignmentMode =
                if (landscapeAlignmentReport == null) {
                    HdrI32AlignmentMode.IDENTITY_ONLY
                } else {
                    HdrI32AlignmentMode.LANDSCAPE_TRANSLATION
                }
            append(report, logs, "HDR_ALIGNMENT=${alignmentMode.name}")
            append(report, logs, "Frames=${sequence.frameCount}")
            val stack = sequence.toRawStack()
            val first = stack.frames.firstOrNull()
                ?: return HdrI32DiagnosticResult(
                    success = false,
                    reportPath = reportFile.absolutePath,
                    failureCode = HdrI32FailureCode.EMPTY_HDR_STACK,
                    failureMessage = "Captured HDR sequence is empty.",
                    logLines = logs
                )
            append(report, logs, "Camera=${first.cameraId}")
            append(report, logs, "ISO=${first.iso}")
            append(report, logs, "ExposuresNs=${stack.frames.joinToString { it.exposureTimeNs.toString() }}")
            append(report, logs, "Dimensions=${first.width}x${first.height}")
            append(report, logs, "CFA=${first.cfaPattern}")
            append(
                report,
                logs,
                "BlackLevel=${first.blackLevelPattern?.joinToString() ?: "unavailable"} " +
                    "whiteLevel=${first.whiteLevel ?: "unavailable"}"
            )
            val storage = HdrI32StorageEstimator.estimate(
                first.width,
                first.height,
                stack.frameCount,
                sessionDirectory,
                includeXisfMaster = exportOptions.writeXisf32,
                includeFitsMaster = exportOptions.writeFits32,
                includeLinearRgbFloat16Dng = exportOptions.writeLinearRgbFloat16Dng
            )
            append(report, logs, "Storage required=${storage.totalRequiredBytes}")
            append(report, logs, "Storage available=${storage.availableBytes}")
            if (!storage.sufficient) {
                result = HdrI32DiagnosticResult(
                    success = false,
                    reportPath = reportFile.absolutePath,
                    failureCode = HdrI32FailureCode.INSUFFICIENT_STORAGE,
                    failureMessage = "Insufficient storage for HDR 32F merge.",
                    logLines = logs
                )
                append(report, logs, "Failure=${result.failureCode}: ${result.failureMessage}")
                return result
            }

            val workDirectory = File(sessionDirectory, ".work")
            val options = HdrI32MergeOptions(
                saturationMarginDn = 32,
                tileHeight = 128,
                darkCalibrationRequested = darkCalibrationRequested,
                alignmentMode = alignmentMode,
                appVersion = APP_VERSION
            )
            append(report, logs, "Merge policy=${options.weightPolicy}")
            append(report, logs, "Invalid sample policy=${options.invalidSamplePolicy}")
            append(report, logs, "Saturation margin=${options.saturationMarginDn}")
            append(
                report,
                logs,
                "Low-signal thresholds=${options.blackWeightZeroThreshold}.." +
                    "${options.blackWeightFullThreshold}"
            )
            append(
                report,
                logs,
                "Highlight thresholds=${options.highlightWeightFullThreshold}.." +
                    "${options.highlightWeightZeroThreshold}"
            )
            append(report, logs, "Exposure weight power=${options.exposureWeightPower}")
            append(
                report,
                logs,
                "Highlight coherence=${options.highlightCoherencePolicy}"
            )
            append(report, logs, "Tile height=${options.tileHeight}")
            append(report, logs, "Merge started")
            val merged =
                HdrI32Merger(workDirectory).merge(
                    rawStack = stack,
                    options = options,
                    alignmentReport = landscapeAlignmentReport
                )
            merged.warnings.forEach { append(report, logs, "Warning=${it.code}: ${it.message}") }
            if (!merged.success || merged.frame == null) {
                result = HdrI32DiagnosticResult(
                    success = false,
                    reportPath = reportFile.absolutePath,
                    warnings = merged.warnings,
                    failureCode = merged.failureCode,
                    failureMessage = merged.failureMessage,
                    cleanupSucceeded = merged.temporaryCleanupSucceeded,
                    logLines = logs
                )
                append(report, logs, "Failure=${result.failureCode}: ${result.failureMessage}")
                return result
            }
            val diagnostics = requireNotNull(merged.diagnostics)
            append(report, logs, "Total input samples=${diagnostics.totalInputSamples}")
            append(report, logs, "Valid samples=${diagnostics.validSamples}")
            append(report, logs, "Saturated rejected=${diagnostics.saturatedRejectedSamples}")
            append(
                report,
                logs,
                "Low-signal zero-weight samples=${diagnostics.lowSignalZeroWeightSamples}"
            )
            append(
                report,
                logs,
                "Highlight zero-weight samples=${diagnostics.highlightZeroWeightSamples}"
            )
            append(
                report,
                logs,
                "Total weight-zero pixels=${diagnostics.totalWeightZeroPixels}"
            )
            append(
                report,
                logs,
                "Shared highlight frame-blocks=${diagnostics.sharedHighlightFrameBlocks}"
            )
            append(
                report,
                logs,
                "Block-saturation zero-weight samples=" +
                    "${diagnostics.blockSaturationZeroWeightSamples}"
            )
            append(report, logs, "Fallback pixels=${diagnostics.fallbackPixels}")
            append(report, logs, "No-valid pixels=${diagnostics.noValidSamplePixels}")
            append(
                report,
                logs,
                "Radiance min=${diagnostics.minimumRadiance} max=${diagnostics.maximumRadiance} " +
                    "mean=${diagnostics.meanRadiance}"
            )
            append(report, logs, "Merge durationMs=${diagnostics.processingDurationMs}")
            val stored = HdrI32Store(sessionDirectory).save(merged.frame)
            append(report, logs, "Store success=${stored.success}")
            append(report, logs, "Float32 path=${stored.frame?.storageFile?.absolutePath}")
            append(report, logs, "Metadata path=${stored.frame?.metadataFile?.absolutePath}")
            append(report, logs, "Output bytes=${stored.frame?.storageFile?.length() ?: 0L}")
            val storedFrame = stored.frame
            if (!stored.success || storedFrame == null) {
                result = HdrI32DiagnosticResult(
                    success = false,
                    reportPath = reportFile.absolutePath,
                    warnings = merged.warnings,
                    failureCode = stored.failureCode,
                    failureMessage = stored.failureMessage,
                    cleanupSucceeded = stored.cleanupSucceeded,
                    logLines = logs
                )
                append(report, logs, "Failure=${result.failureCode}: ${result.failureMessage}")
                return result
            }

            val xisfResult =
                if (exportOptions.writeXisf32) {
                    append(report, logs, "XISF32 master write started")
                    HdrI32XisfWriter.write(
                        storedFrame,
                        File(sessionDirectory, HdrI32XisfWriter.DEFAULT_FILENAME)
                    )
                } else {
                    append(report, logs, "XISF32 disabled")
                    null
                }
            append(report, logs, "XISF32 success=${xisfResult?.success}")
            append(report, logs, "XISF32 path=${xisfResult?.outputPath}")
            append(report, logs, "XISF32 bytes=${xisfResult?.outputBytes ?: 0L}")
            append(report, logs, "XISF32 durationMs=${xisfResult?.durationMs ?: 0L}")
            if (xisfResult != null && !xisfResult.success) {
                append(
                    report,
                    logs,
                    "XISF32 failure=${xisfResult.failureCode}: ${xisfResult.failureMessage}"
                )
            }

            val fitsResult =
                if (exportOptions.writeFits32) {
                    append(report, logs, "FITS32 master write started")
                    HdrI32FitsWriter.write(
                        storedFrame,
                        File(sessionDirectory, HdrI32FitsWriter.DEFAULT_FILENAME)
                    )
                } else {
                    append(report, logs, "FITS32 disabled")
                    null
                }
            append(report, logs, "FITS32 success=${fitsResult?.success}")
            append(report, logs, "FITS32 path=${fitsResult?.outputPath}")
            append(report, logs, "FITS32 bytes=${fitsResult?.outputBytes ?: 0L}")
            append(report, logs, "FITS32 header bytes=${fitsResult?.headerBytes ?: 0L}")
            append(report, logs, "FITS32 durationMs=${fitsResult?.durationMs ?: 0L}")
            if (fitsResult != null && !fitsResult.success) {
                append(
                    report,
                    logs,
                    "FITS32 failure=${fitsResult.failureCode}: ${fitsResult.failureMessage}"
                )
            }

            val referenceRecord =
                sequence.recordForFrameIndex(storedFrame.metadata.referenceFrameIndex)
            val linearRgbResult =
                if (exportOptions.writeLinearRgbFloat16Dng) {
                    append(report, logs, "Linear RGB Float16 DNG export started")
                    val linearStorage = LinearRgbFloat16StorageEstimator.estimate(
                        storedFrame.width,
                        storedFrame.height,
                        sessionDirectory
                    )
                    append(
                        report,
                        logs,
                        "Linear RGB DNG storage required=${linearStorage.totalRequiredBytes} " +
                            "available=${linearStorage.availableBytes}"
                    )
                    if (!linearStorage.sufficient) {
                        null
                    } else {
                        val colorMetadata =
                            LinearRgbFloat16DngMetadataExtractor.extract(
                                characteristics = referenceRecord?.cameraCharacteristics,
                                captureResult = referenceRecord?.totalCaptureResult,
                                orientation = referenceRecord?.dngOrientation,
                                appVersion = APP_VERSION
                            )
                        colorMetadata.warnings.forEach {
                            append(report, logs, "Linear RGB DNG warning=$it")
                        }
                        if (!colorMetadata.success || colorMetadata.metadata == null) {
                            append(
                                report,
                                logs,
                                "Linear RGB DNG metadata failure=${colorMetadata.failureMessage}"
                            )
                            null
                        } else {
                            val referenceExposureNs =
                                storedFrame.metadata.referenceExposureTimeNs
                            append(
                                report,
                                logs,
                                "Linear RGB DNG input=${storedFrame.storageFile.absolutePath}"
                            )
                            append(
                                report,
                                logs,
                                "Linear RGB DNG dimensions=${storedFrame.width}x${storedFrame.height} " +
                                    "CFA=${storedFrame.cfaPattern}"
                            )
                            append(report, logs, "Linear RGB DNG demosaic=BILINEAR_FLOAT32")
                            append(
                                report,
                                logs,
                                "Linear RGB DNG reference exposureNs=$referenceExposureNs"
                            )
                            append(
                                report,
                                logs,
                                "Linear RGB DNG normalization=" +
                                    "radiance*referenceExposureSeconds/" +
                                    "(whiteLevel-blackLevelForCfaPhase)"
                            )
                            append(
                                report,
                                logs,
                                "Linear RGB DNG format=LinearRaw RGB Float16 chunky, " +
                                    "uncompressed, little-endian"
                            )
                            append(report, logs, "Linear RGB DNG color metadata complete=true")
                            val request = LinearRgbFloat16DngWriteRequest(
                                hdrFrame = storedFrame,
                                metadata = colorMetadata.metadata,
                                outputFile = File(
                                    sessionDirectory,
                                    LinearRgbFloat16DngWriter.DEFAULT_FILENAME
                                ),
                                options = LinearRgbFloat16DngExportOptions()
                            )
                            val written = LinearRgbFloat16DngWriter.write(request)
                            if (written.success) {
                                val sidecarFile =
                                    File(
                                        sessionDirectory,
                                        LinearRgbFloat16DngSidecar.DEFAULT_FILENAME
                                    )
                                runCatching {
                                    LinearRgbFloat16DngSidecar.write(
                                        sidecarFile,
                                        storedFrame,
                                        request,
                                        written
                                    )
                                    append(
                                        report,
                                        logs,
                                        "Linear RGB DNG sidecar=${sidecarFile.absolutePath}"
                                    )
                                    written
                                }.getOrElse {
                                    append(
                                        report,
                                        logs,
                                        "Linear RGB DNG sidecar failure=${it.message}"
                                    )
                                    written.copy(
                                        success = false,
                                        failureCode =
                                            com.lab.bracketlab.processing.hdri.export.dng
                                                .LinearRgbFloat16DngFailureCode
                                                .OUTPUT_IO_FAILURE,
                                        failureMessage =
                                            "DNG was written, but its metadata sidecar failed: " +
                                                it.message,
                                        exceptionClass = it.javaClass.name
                                    )
                                }
                            } else {
                                written
                            }
                        }
                    }
                } else {
                    append(report, logs, "Linear RGB Float16 DNG disabled")
                    null
                }
            append(report, logs, "Linear RGB DNG success=${linearRgbResult?.success}")
            append(report, logs, "Linear RGB DNG path=${linearRgbResult?.outputPath}")
            append(report, logs, "Linear RGB DNG bytes=${linearRgbResult?.outputBytes ?: 0L}")
            append(
                report,
                logs,
                "Linear RGB DNG max before scale=" +
                    "${linearRgbResult?.diagnostics?.maxRgbBeforeScale ?: 0.0}"
            )
            append(
                report,
                logs,
                "Linear RGB DNG global scale=" +
                    "${linearRgbResult?.diagnostics?.globalScaleApplied ?: 1.0}"
            )
            append(
                report,
                logs,
                "Linear RGB DNG BaselineExposure=" +
                    "${linearRgbResult?.diagnostics?.baselineExposureWritten ?: 0.0}"
            )
            append(
                report,
                logs,
                "Linear RGB DNG values above 1.0 before scale=" +
                    "${linearRgbResult?.diagnostics?.valuesAboveOneBeforeScale ?: 0L}"
            )
            append(
                report,
                logs,
                "Linear RGB DNG values above 1.0 after scale=" +
                    "${linearRgbResult?.diagnostics?.valuesAboveOneAfterScale ?: 0L}"
            )
            append(
                report,
                logs,
                "Linear RGB DNG Float16 max clamps=" +
                    "${linearRgbResult?.diagnostics?.maximumClamps ?: 0L}"
            )
            append(
                report,
                logs,
                "Linear RGB DNG invalid replacements=" +
                    "${linearRgbResult?.diagnostics?.invalidValueReplacements ?: 0L}"
            )
            append(
                report,
                logs,
                "Linear RGB DNG range=${linearRgbResult?.diagnostics?.minimumRgb}.." +
                    "${linearRgbResult?.diagnostics?.maximumRgb}"
            )
            append(
                report,
                logs,
                "Linear RGB DNG durationMs=" +
                    "${linearRgbResult?.diagnostics?.processingDurationMs ?: 0L}"
            )
            append(
                report,
                logs,
                "Linear RGB DNG payload expected=" +
                    "${linearRgbResult?.diagnostics?.expectedImageBytes ?: 0L} " +
                    "stripByteCounts=${linearRgbResult?.diagnostics?.sumStripByteCounts ?: 0L} " +
                    "stripTableValid=${linearRgbResult?.diagnostics?.stripTableValid ?: false}"
            )
            append(report, logs, "Linear RGB Float16 DNG export from HDR")
            append(report, logs, "No tone mapping was applied")
            append(report, logs, "No gamma was applied")
            append(report, logs, "No contrast/saturation/curve was applied")
            append(
                report,
                logs,
                "Global normalization was applied only to prevent Adobe clipping and is " +
                    "compensated by BaselineExposure"
            )
            append(report, logs, "Demosaic was performed only for Adobe compatibility")
            val workCleanup = !workDirectory.exists() || workDirectory.deleteRecursively()
            append(report, logs, "Temporary work cleanup=$workCleanup")
            val allOutputsSucceeded =
                (!exportOptions.writeXisf32 || xisfResult?.success == true) &&
                    (!exportOptions.writeFits32 || fitsResult?.success == true) &&
                    (!exportOptions.writeLinearRgbFloat16Dng ||
                        linearRgbResult?.success == true)
            result = HdrI32DiagnosticResult(
                success = allOutputsSucceeded,
                floatPath = storedFrame.storageFile.absolutePath,
                xisfPath = xisfResult?.outputPath,
                fitsPath = fitsResult?.outputPath,
                linearRgbFloat16DngPath = linearRgbResult?.outputPath,
                metadataPath = storedFrame.metadataFile?.absolutePath,
                reportPath = reportFile.absolutePath,
                outputBytes = storedFrame.storageFile.length(),
                warnings = merged.warnings,
                failureCode =
                    when {
                        exportOptions.writeXisf32 && xisfResult?.success != true ->
                            HdrI32FailureCode.XISF_WRITE_FAILED
                        exportOptions.writeFits32 && fitsResult?.success != true ->
                            HdrI32FailureCode.FITS_WRITE_FAILED
                        exportOptions.writeLinearRgbFloat16Dng &&
                            linearRgbResult?.success != true ->
                            HdrI32FailureCode.LINEAR_RGB_FLOAT16_DNG_FAILED
                        else -> null
                    },
                failureMessage =
                    when {
                        exportOptions.writeXisf32 && xisfResult?.success != true ->
                            xisfResult?.failureMessage
                        exportOptions.writeFits32 && fitsResult?.success != true ->
                            fitsResult?.failureMessage
                        exportOptions.writeLinearRgbFloat16Dng &&
                            linearRgbResult?.success != true ->
                            linearRgbResult?.failureMessage
                                ?: "Linear RGB Float16 DNG metadata or storage validation failed."
                        else -> null
                    },
                cleanupSucceeded =
                    stored.cleanupSucceeded &&
                        (xisfResult?.cleanupSucceeded ?: true) &&
                        (fitsResult?.cleanupSucceeded ?: true) &&
                        (linearRgbResult?.cleanupSucceeded ?: true) &&
                        workCleanup,
                logLines = logs
            )
            append(report, logs, "Final success=${result.success}")
            return result
        } catch (error: Throwable) {
            runCatching { report?.append("Failure=${error.javaClass.simpleName}: ${error.message}") }
            result = HdrI32DiagnosticResult(
                success = false,
                reportPath = reportFile.takeIf(File::exists)?.absolutePath,
                failureCode = HdrI32FailureCode.HDRI_PROCESSING_FAILED,
                failureMessage = error.message,
                logLines = logs
            )
            return result
        } finally {
            val inputCleanup = runCatching { sequence.cleanupTemporaryRawFrames() }.getOrDefault(false)
            runCatching { report?.append("Input cleanup=$inputCleanup") }
            runCatching { report?.append("Processing guard released") }
            runCatching { report?.close() }
            processing.release()
        }
    }

    private fun sessionDirectory(sequence: CapturedRawSequence): File {
        val camera = sequence.cameraId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val timestamp =
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(Date(sequence.createdAtMillis))
        return File(rootDirectory, "camera_$camera${File.separator}session_$timestamp")
    }

    private fun append(
        report: IncrementalDiagnosticReport,
        logs: MutableList<String>,
        line: String
    ) {
        report.append(line)
        logs += line
    }

    companion object {
        private const val APP_VERSION = "0.10"
        private val processing = SingleFlightGuard()

        fun isProcessing(): Boolean = processing.isActive()
    }
}
