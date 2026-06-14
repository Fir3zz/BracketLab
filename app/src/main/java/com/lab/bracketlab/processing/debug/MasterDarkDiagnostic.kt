package com.lab.bracketlab.processing.debug

import com.lab.bracketlab.processing.align.LandscapeAlignmentOptions
import com.lab.bracketlab.processing.align.LandscapeAlignmentProcessor
import com.lab.bracketlab.processing.align.LandscapeAlignmentReport
import com.lab.bracketlab.processing.calibration.DarkAggregationPolicy
import com.lab.bracketlab.processing.calibration.DarkCalibrationInput
import com.lab.bracketlab.processing.calibration.DarkCalibrationOptions
import com.lab.bracketlab.processing.calibration.DarkPolicy
import com.lab.bracketlab.processing.calibration.DarkStorageEstimator
import com.lab.bracketlab.processing.calibration.MasterDark
import com.lab.bracketlab.processing.calibration.MasterDarkMatcher
import com.lab.bracketlab.processing.calibration.MasterDarkProcessor
import com.lab.bracketlab.processing.calibration.MasterDarkStore
import com.lab.bracketlab.processing.calibration.MissingMasterDarkBehavior
import com.lab.bracketlab.processing.io.FileRaw16DngOutputDestination
import com.lab.bracketlab.processing.io.Raw16DngFailureCode
import com.lab.bracketlab.processing.io.Raw16DngWriteRequest
import com.lab.bracketlab.processing.io.Raw16DngWriteResult
import com.lab.bracketlab.processing.io.Raw16DngWriter
import com.lab.bracketlab.processing.pipeline.CapturedRawSequence
import com.lab.bracketlab.processing.stack.AlignedRaw16StackOptions
import com.lab.bracketlab.processing.stack.AlignedRaw16StackProcessor
import com.lab.bracketlab.processing.stack.RawStackAggregationMode
import com.lab.bracketlab.processing.stack.RawStackAggregationOptions
import java.io.File

data class MasterDarkDiagnosticResult(
    val success: Boolean,
    val reportPath: String?,
    val masterDarkRawPath: String? = null,
    val masterDarkMetadataPath: String? = null,
    val controlDng: Raw16DngWriteResult? = null,
    val calibratedDng: Raw16DngWriteResult? = null,
    val logLines: List<String> = emptyList()
)

class MasterDarkDiagnostic(
    private val masterDarkRoot: File
) {
    fun capture(sequence: CapturedRawSequence): MasterDarkDiagnosticResult =
        runGuarded(sequence, CAPTURE_REPORT_FILENAME, "CAPTURE_MASTER_DARK") { report, logs ->
            val stack = sequence.toRawStack()
            val first = stack.frames.firstOrNull()
                ?: return@runGuarded MasterDarkDiagnosticResult(
                    false,
                    report.file.absolutePath,
                    logLines = logs + "No captured dark frames."
                )
            val options = DarkCalibrationOptions(
                darkPolicy = DarkPolicy.CAPTURE_MASTER_DARK,
                aggregationPolicy = DarkAggregationPolicy.AUTO,
                minimumDarkFrames = 1,
                allowSingleDarkFrame = true,
                appVersion = APP_VERSION
            )
            append(report, logs, "Camera=${first.cameraId}")
            append(report, logs, "Dark frames=${stack.frameCount}")
            append(report, logs, "Dimensions=${first.width}x${first.height}")
            append(report, logs, "CFA=${first.cfaPattern}")
            append(report, logs, "ISO=${first.iso} exposureNs=${first.exposureTimeNs}")
            append(
                report,
                logs,
                "BlackLevel=${first.blackLevelPattern?.joinToString() ?: "unavailable"} " +
                    "whiteLevel=${first.whiteLevel ?: "unavailable"}"
            )
            val storage = DarkStorageEstimator.estimate(
                width = first.width,
                height = first.height,
                darkFrameCount = stack.frameCount,
                outputDirectory = masterDarkRoot,
                options = options
            )
            append(report, logs, "Storage required=${storage.totalRequiredBytes}")
            append(report, logs, "Storage available=${storage.availableBytes}")
            if (!storage.sufficient) {
                append(report, logs, "Capture MasterDark failed: insufficient storage")
                return@runGuarded MasterDarkDiagnosticResult(
                    false,
                    report.file.absolutePath,
                    logLines = logs
                )
            }

            append(report, logs, "MasterDark creation started")
            val workDirectory = File(sequence.outputDirectoryPath, ".masterdark_work")
            val result = MasterDarkProcessor(workDirectory).createMasterDark(stack, options)
            append(
                report,
                logs,
                "MasterDark creation success=${result.success} mode=${result.aggregationMode} " +
                    "frames=${result.darkFrameCount} rejected=${result.aggregationDiagnostics?.totalRejectedSamples}"
            )
            if (!result.success || result.masterDark == null) {
                append(report, logs, "Failure=${result.failureCode}: ${result.failureMessage}")
                return@runGuarded MasterDarkDiagnosticResult(
                    false,
                    report.file.absolutePath,
                    logLines = logs
                )
            }
            val stored = MasterDarkStore(masterDarkRoot).save(result.masterDark)
            append(report, logs, "MasterDark store success=${stored.success}")
            append(report, logs, "MasterDark raw=${stored.masterDark?.rawFile?.absolutePath}")
            append(report, logs, "MasterDark metadata=${stored.masterDark?.metadataFile?.absolutePath}")
            append(
                report,
                logs,
                "MasterDark min=${result.minimumDarkValue} max=${result.maximumDarkValue} " +
                    "mean=${result.meanDarkValue}"
            )
            append(report, logs, "MasterDark work cleanup=${!workDirectory.exists() || workDirectory.delete()}")
            MasterDarkDiagnosticResult(
                success = stored.success,
                reportPath = report.file.absolutePath,
                masterDarkRawPath = stored.masterDark?.rawFile?.absolutePath,
                masterDarkMetadataPath = stored.masterDark?.metadataFile?.absolutePath,
                logLines = logs
            )
        }

    fun apply(sequence: CapturedRawSequence): MasterDarkDiagnosticResult =
        runGuarded(sequence, APPLY_REPORT_FILENAME, "APPLY_MASTER_DARK") { report, logs ->
            val rawStack = sequence.toRawStack()
            val referenceCandidate = rawStack.frames.getOrNull(rawStack.frames.size / 2)
                ?: return@runGuarded MasterDarkDiagnosticResult(
                    false,
                    report.file.absolutePath,
                    logLines = logs + "No captured light frames."
                )
            val options = DarkCalibrationOptions(
                darkPolicy = DarkPolicy.USE_COMPATIBLE_MASTER_DARK,
                exposureRelativeTolerance = 0.001,
                exposureAbsoluteToleranceNs = 1_000L,
                missingMasterDarkBehavior = MissingMasterDarkBehavior.FAIL,
                appVersion = APP_VERSION
            )
            val store = MasterDarkStore(masterDarkRoot)
            val storeScan = store.scanAvailable()
            val available = storeScan.masterDarks
            append(report, logs, "Light frames=${rawStack.frameCount}")
            append(report, logs, "Camera=${referenceCandidate.cameraId}")
            append(
                report,
                logs,
                "Light ISO=${referenceCandidate.iso} exposureNs=${referenceCandidate.exposureTimeNs} " +
                    "CFA=${referenceCandidate.cfaPattern}"
            )
            append(
                report,
                logs,
                "Light dimensions=${referenceCandidate.width}x${referenceCandidate.height} " +
                    "whiteLevel=${referenceCandidate.whiteLevel ?: "unavailable"}"
            )
            append(
                report,
                logs,
                "Light blackLevel=${referenceCandidate.blackLevelPattern?.joinToString() ?: "unavailable"}"
            )
            append(
                report,
                logs,
                "MasterDark root exists=${storeScan.rootExists} readable=${storeScan.rootCanRead} " +
                    "filesScanned=${storeScan.filesScanned}"
            )
            storeScan.issues.forEach {
                append(report, logs, "MasterDark store issue=${it.code} path=${it.path}: ${it.message}")
            }
            append(report, logs, "Available MasterDarks=${available.size}")
            val match = MasterDarkMatcher.findCompatibleMasterDark(
                referenceCandidate,
                available,
                options
            )
            append(report, logs, "MasterDark matched=${match.matched}")
            match.candidateDiagnostics.forEach {
                append(
                    report,
                    logs,
                    "Candidate ${it.masterDarkId}: compatible=${it.compatible} reasons=${it.rejectionReasons}"
                )
            }
            val masterDark = match.selected
                ?: return@runGuarded MasterDarkDiagnosticResult(
                    false,
                    report.file.absolutePath,
                    logLines = logs + (match.failureMessage ?: "No compatible MasterDark.")
                )
            append(report, logs, "Selected MasterDark=${masterDark.metadata.id}")
            append(
                report,
                logs,
                "MasterDark ISO=${masterDark.metadata.iso} exposureNs=${masterDark.metadata.exposureTimeNs} " +
                        "frames=${masterDark.metadata.frameCount}"
            )
            append(
                report,
                logs,
                "MasterDark blackLevel=${masterDark.metadata.blackLevelPattern?.joinToString() ?: "unavailable"}"
            )

            val alignment = LandscapeAlignmentProcessor().align(
                rawStack,
                LandscapeAlignmentOptions(exposureNormalizeProxies = false)
            )
            append(
                report,
                logs,
                "Alignment reference=${alignment.selectedReferenceFrameIndex} " +
                    "accepted=${alignment.acceptedFrameCount} rejected=${alignment.rejectedFrameCount}"
            )
            if (alignment.fatalError != null || alignment.acceptedFrameCount == 0) {
                append(report, logs, "Alignment failed=${alignment.fatalMessage}")
                return@runGuarded MasterDarkDiagnosticResult(
                    false,
                    report.file.absolutePath,
                    logLines = logs
                )
            }

            val control = processAndWrite(
                sequence,
                alignment,
                masterDark = null,
                outputFilename = CONTROL_FILENAME,
                description = "BracketLab DEV aligned RAW16 control; Dark calibration: OFF"
            )
            append(report, logs, "Control DNG=${formatWrite(control)}")
            val calibrated = processAndWrite(
                sequence,
                alignment,
                masterDark = masterDark,
                outputFilename = CALIBRATED_FILENAME,
                description =
                    "BracketLab DEV aligned RAW16 stack\n" +
                        "Dark calibration: MasterDark applied\n" +
                        "MasterDark frames: ${masterDark.metadata.frameCount}\n" +
                        "MasterDark ISO/exposure: ${masterDark.metadata.iso}/${masterDark.metadata.exposureTimeNs}ns"
            )
            append(report, logs, "Dark-applied DNG=${formatWrite(calibrated)}")
            append(report, logs, "Dark subtraction=on-the-fly before aggregation")
            MasterDarkDiagnosticResult(
                success = control.success && calibrated.success,
                reportPath = report.file.absolutePath,
                masterDarkRawPath = masterDark.rawFile.absolutePath,
                masterDarkMetadataPath = masterDark.metadataFile?.absolutePath,
                controlDng = control,
                calibratedDng = calibrated,
                logLines = logs
            )
        }

    private fun processAndWrite(
        sequence: CapturedRawSequence,
        alignment: LandscapeAlignmentReport,
        masterDark: MasterDark?,
        outputFilename: String,
        description: String
    ): Raw16DngWriteResult {
        val rawStack = sequence.toRawStack()
        val packedFile = File(sequence.outputDirectoryPath, ".$outputFilename.packed.raw16")
        return try {
            val darkInput =
                if (masterDark == null) {
                    DarkCalibrationInput.OFF
                } else {
                    DarkCalibrationInput.use(
                        masterDark,
                        DarkCalibrationOptions(
                            darkPolicy = DarkPolicy.USE_COMPATIBLE_MASTER_DARK,
                            missingMasterDarkBehavior = MissingMasterDarkBehavior.FAIL
                        )
                    )
                }
            val stackResult = AlignedRaw16StackProcessor().processToPackedFile(
                rawStack = rawStack,
                alignmentReport = alignment,
                options = AlignedRaw16StackOptions(
                    aggregationOptions = RawStackAggregationOptions(
                        mode = RawStackAggregationMode.SIGMA_CLIPPED_MEAN
                    ),
                    darkCalibration = darkInput,
                    tileHeight = 128
                ),
                outputFile = packedFile
            )
            if (!stackResult.success) {
                return Raw16DngWriteResult.failure(
                    Raw16DngFailureCode.PIPELINE_STACKING_FAILED,
                    stackResult.fatalMessage ?: stackResult.fatalError?.name ?: "Stacking failed."
                )
            }
            val reference = sequence.recordForFrameIndex(stackResult.referenceFrameIndex ?: -1)
                ?: return Raw16DngWriteResult.failure(
                    Raw16DngFailureCode.PIPELINE_METADATA_FAILED,
                    "Reference Camera2 metadata is unavailable."
                )
            Raw16DngWriter.write(
                Raw16DngWriteRequest(
                    alignedResult = stackResult,
                    cameraCharacteristics = reference.cameraCharacteristics,
                    captureResult = reference.totalCaptureResult,
                    destination = FileRaw16DngOutputDestination(
                        File(sequence.outputDirectoryPath, outputFilename),
                        overwrite = false
                    ),
                    referenceFrameIndex = reference.frameIndex,
                    metadataFrameIndex = reference.frameIndex,
                    expectedCameraId = sequence.cameraId,
                    metadataCameraId = reference.cameraId,
                    expectedWidth = reference.width,
                    expectedHeight = reference.height,
                    orientation = reference.dngOrientation,
                    description = description,
                    deletePackedInputAfterWrite = true
                )
            )
        } finally {
            if (packedFile.exists()) packedFile.delete()
        }
    }

    private fun runGuarded(
        sequence: CapturedRawSequence,
        reportFilename: String,
        modeName: String,
        block: (
            report: IncrementalDiagnosticReport,
            logs: MutableList<String>
        ) -> MasterDarkDiagnosticResult
    ): MasterDarkDiagnosticResult {
        val outputDirectory = File(sequence.outputDirectoryPath).also { it.mkdirs() }
        val reportFile = File(outputDirectory, reportFilename)
        val logs = mutableListOf<String>()
        if (!processing.tryAcquire()) {
            return MasterDarkDiagnosticResult(
                false,
                null,
                logLines = listOf("MasterDark diagnostic rejected: another dark operation is active.")
            )
        }
        var report: IncrementalDiagnosticReport? = null
        return try {
            report = IncrementalDiagnosticReport(reportFile)
            append(report, logs, "BracketLab MasterDark DEV diagnostic")
            append(report, logs, "Mode=$modeName")
            append(report, logs, "Frames=${sequence.frameCount}")
            append(report, logs, "MasterDark root=${masterDarkRoot.absolutePath}")
            block(report, logs)
        } catch (error: Throwable) {
            runCatching { report?.append("Failure=${error.javaClass.simpleName}: ${error.message}") }
            MasterDarkDiagnosticResult(
                false,
                reportFile.takeIf(File::exists)?.absolutePath,
                logLines = logs + "${error.javaClass.simpleName}: ${error.message}"
            )
        } finally {
            val cleanup = runCatching { sequence.cleanupTemporaryRawFrames() }.getOrDefault(false)
            runCatching { report?.append("Input cleanup=$cleanup") }
            runCatching { report?.append("Processing guard released") }
            runCatching { report?.close() }
            processing.release()
        }
    }

    private fun append(
        report: IncrementalDiagnosticReport,
        logs: MutableList<String>,
        line: String
    ) {
        report.append(line)
        logs += line
    }

    private fun formatWrite(result: Raw16DngWriteResult): String =
        if (result.success) {
            "${result.finalPath ?: result.finalUri} bytes=${result.bytesWritten}"
        } else {
            "FAIL ${result.failureCode}: ${result.failureMessage}"
        }

    companion object {
        const val CAPTURE_REPORT_FILENAME = "BracketLab_DEV_MasterDark_Capture_Report.txt"
        const val APPLY_REPORT_FILENAME = "BracketLab_DEV_MasterDark_Apply_Report.txt"
        const val CONTROL_FILENAME = "BracketLab_DEV_DarkControl_RAW16.dng"
        const val CALIBRATED_FILENAME = "BracketLab_DEV_DarkApplied_RAW16.dng"
        private const val APP_VERSION = "0.10"
        private val processing = SingleFlightGuard()

        fun isProcessing(): Boolean = processing.isActive()
    }
}
