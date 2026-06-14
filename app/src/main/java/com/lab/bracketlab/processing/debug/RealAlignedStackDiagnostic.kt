package com.lab.bracketlab.processing.debug

import com.lab.bracketlab.processing.align.LandscapeAlignmentOptions
import com.lab.bracketlab.processing.align.LandscapeAlignmentProcessor
import com.lab.bracketlab.processing.align.LandscapeAlignmentReport
import com.lab.bracketlab.processing.align.LandscapeAlignmentStatus
import com.lab.bracketlab.processing.align.LandscapeFrameAlignment
import com.lab.bracketlab.processing.align.ReferenceFrameSelector
import com.lab.bracketlab.processing.io.FileRaw16DngOutputDestination
import com.lab.bracketlab.processing.io.Raw16DngFailureCode
import com.lab.bracketlab.processing.io.Raw16DngWriteRequest
import com.lab.bracketlab.processing.io.Raw16DngWriteResult
import com.lab.bracketlab.processing.io.Raw16DngWriter
import com.lab.bracketlab.processing.memory.MemoryBudgetEstimator
import com.lab.bracketlab.processing.model.AlignmentResult
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import com.lab.bracketlab.processing.pipeline.CapturedRawSequence
import com.lab.bracketlab.processing.stack.AlignedRaw16StackOptions
import com.lab.bracketlab.processing.stack.AlignedRaw16StackProcessor
import com.lab.bracketlab.processing.stack.AppliedRawTranslation
import com.lab.bracketlab.processing.stack.CommonOverlapRect
import com.lab.bracketlab.processing.stack.RawStackAggregationDiagnostics
import com.lab.bracketlab.processing.stack.RawStackAggregationMode
import com.lab.bracketlab.processing.stack.RawStackAggregationOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RealAlignedStackDiagnosticResult(
    val success: Boolean,
    val outputDirectoryPath: String,
    val control: RealAlignedStackDngResult,
    val aligned: RealAlignedStackDngResult,
    val minMax: RealAlignedStackDngResult,
    val sigma: RealAlignedStackDngResult,
    val reportPath: String?,
    val reportWriteError: String?,
    val logLines: List<String>
)

data class RealAlignedStackDngResult(
    val label: String,
    val success: Boolean,
    val writeResult: Raw16DngWriteResult?,
    val width: Int = 0,
    val height: Int = 0,
    val referenceFrameIndex: Int? = null,
    val acceptedFrameCount: Int = 0,
    val rejectedFrameCount: Int = 0,
    val processingDurationMs: Long = 0L,
    val aggregationDiagnostics: RawStackAggregationDiagnostics? = null,
    val appliedTranslations: List<AppliedRawTranslation> = emptyList(),
    val commonOverlapRect: CommonOverlapRect? = null,
    val minimumValidCount: Int = 0,
    val maximumValidCount: Int = 0,
    val meanValidCount: Double = 0.0,
    val singleContributorPixelCount: Int = 0,
    val fullContributorPixelCount: Int = 0,
    val warnings: List<String> = emptyList()
)

class RealAlignedStackDiagnostic {
    fun run(sequence: CapturedRawSequence): RealAlignedStackDiagnosticResult {
        val outputDir = File(sequence.outputDirectoryPath).also { it.mkdirs() }
        val reportFile = File(outputDir, REPORT_FILENAME)
        val logs = mutableListOf<String>()
        val emptyControl = failedDngResult("control", Raw16DngFailureCode.INVALID_STACK_RESULT, "Not started.")
        var control = emptyControl
        var mean = failedDngResult("mean", Raw16DngFailureCode.INVALID_STACK_RESULT, "Not started.")
        var minMax = failedDngResult("min-max", Raw16DngFailureCode.INVALID_STACK_RESULT, "Not started.")
        var sigma = failedDngResult("sigma", Raw16DngFailureCode.INVALID_STACK_RESULT, "Not started.")
        var reportError: String? = null

        if (!processing.tryAcquire()) {
            val message = "DEV aligned processing rejected: another full-resolution job is active."
            logs += message
            runCatching {
                IncrementalDiagnosticReport(reportFile).use {
                    it.append("BracketLab DEV Aligned RAW16 Stack Report")
                    it.append(message)
                }
            }.onFailure { reportError = it.message }
            return RealAlignedStackDiagnosticResult(
                success = false,
                outputDirectoryPath = outputDir.absolutePath,
                control = control,
                aligned = mean,
                minMax = minMax,
                sigma = sigma,
                reportPath = reportFile.takeIf(File::exists)?.absolutePath,
                reportWriteError = reportError,
                logLines = logs
            )
        }

        var report: IncrementalDiagnosticReport? = null
        try {
            report = IncrementalDiagnosticReport(reportFile)
            append(report, logs, "BracketLab DEV Aligned RAW16 Stack Report")
            append(report, logs, "Generated: ${timestamp()}")
            append(report, logs, "Session started")
            append(report, logs, "Processing guard acquired")
            append(report, logs, "Output dir: ${outputDir.absolutePath}")
            append(report, logs, "Captured accepted frames: ${sequence.frameCount}")
            sequence.rejectedFrames.forEach { append(report, logs, it) }

            val validationErrors = validateSameExposureBurst(sequence)
            if (validationErrors.isNotEmpty()) {
                validationErrors.forEach { append(report, logs, "Validation error: $it") }
                val failure = failedDngResult(
                    "validation",
                    Raw16DngFailureCode.INVALID_STACK_RESULT,
                    validationErrors.joinToString("; ")
                )
                control = failure.copy(label = "control")
                mean = failure.copy(label = "mean")
                minMax = failure.copy(label = "min-max")
                sigma = failure.copy(label = "sigma")
                append(report, logs, "Final status: FAIL")
                return result(false, outputDir, control, mean, minMax, sigma, reportFile, reportError, logs)
            }

            val rawStack = sequence.toRawStack()
            appendSequence(report, sequence)
            val memory = MemoryBudgetEstimator.estimate(rawStack)
            append(report, logs, "Memory pre-flight")
            append(report, logs, "maxMemory=${memory.runtimeMaxMemoryBytes}")
            append(report, logs, "totalMemory=${memory.runtimeTotalMemoryBytes}")
            append(report, logs, "freeMemory=${memory.runtimeFreeMemoryBytes}")
            append(report, logs, "residentInputBytes=${memory.estimatedResidentInputBytes}")
            append(report, logs, "packedOutputBytes=${memory.estimatedOutputBytes}")
            append(report, logs, "diagnosticMapBytes=${memory.estimatedDiagnosticMapBytes}")
            append(report, logs, "strategy=${memory.selectedStrategy}: ${memory.reason}")

            val alignment = LandscapeAlignmentProcessor().align(
                rawStack,
                LandscapeAlignmentOptions(exposureNormalizeProxies = false)
            )
            appendAlignment(report, alignment)
            if (alignment.fatalError != null || alignment.acceptedFrameCount == 0) {
                val failure = failedDngResult(
                    "alignment",
                    Raw16DngFailureCode.PIPELINE_ALIGNMENT_FAILED,
                    alignment.fatalMessage ?: alignment.fatalError?.name ?: "Alignment failed."
                )
                control = failure.copy(label = "control")
                mean = failure.copy(label = "mean")
                minMax = failure.copy(label = "min-max")
                sigma = failure.copy(label = "sigma")
                append(report, logs, "Alignment failed")
                append(report, logs, "Final status: FAIL")
                return result(false, outputDir, control, mean, minMax, sigma, reportFile, reportError, logs)
            }
            append(report, logs, "Alignment completed")

            control = runMode(
                rawStack = rawStack,
                sequence = sequence,
                outputDir = outputDir,
                report = report,
                alignmentReport = buildIdentityAlignmentReport(rawStack),
                label = "control",
                stageName = "Control",
                filename = CONTROL_FILENAME,
                aggregationOptions = RawStackAggregationOptions(mode = RawStackAggregationMode.MEAN),
                description = "BracketLab DEV control identity RAW16 average stack"
            )
            mean = runMode(
                rawStack = rawStack,
                sequence = sequence,
                outputDir = outputDir,
                report = report,
                alignmentReport = alignment,
                label = "mean",
                stageName = "Mean",
                filename = MEAN_FILENAME,
                aggregationOptions = RawStackAggregationOptions(mode = RawStackAggregationMode.MEAN),
                description = "BracketLab DEV aligned RAW16 stack; Aggregation: Mean"
            )
            minMax = runMode(
                rawStack = rawStack,
                sequence = sequence,
                outputDir = outputDir,
                report = report,
                alignmentReport = alignment,
                label = "min-max",
                stageName = "MinMax",
                filename = MIN_MAX_FILENAME,
                aggregationOptions = RawStackAggregationOptions(
                    mode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN
                ),
                description = "BracketLab DEV aligned RAW16 stack; Aggregation: Min-max rejected mean"
            )
            sigma = runMode(
                rawStack = rawStack,
                sequence = sequence,
                outputDir = outputDir,
                report = report,
                alignmentReport = alignment,
                label = "sigma",
                stageName = "Sigma",
                filename = SIGMA_FILENAME,
                aggregationOptions = RawStackAggregationOptions(
                    mode = RawStackAggregationMode.SIGMA_CLIPPED_MEAN
                ),
                description = "BracketLab DEV aligned RAW16 stack; Aggregation: Sigma-clipped mean"
            )

            listOf(control, mean, minMax, sigma).forEach {
                logs += "DEV ${it.label} DNG: ${it.writeResult?.let(::resultLine) ?: "not written"}"
            }
            listOf(control, mean, minMax, sigma).forEach { appendSummary(report, it) }
            val success = control.success && mean.success && minMax.success && sigma.success
            append(report, logs, "Final status: ${if (success) "PASS" else "FAIL"}")
            return result(success, outputDir, control, mean, minMax, sigma, reportFile, reportError, logs)
        } catch (oom: OutOfMemoryError) {
            val message = "OutOfMemoryError: ${oom.message}"
            logs += message
            runCatching { report?.append("Failure stage: memory emergency") }
            runCatching { report?.append(message) }
            return result(false, outputDir, control, mean, minMax, sigma, reportFile, reportError, logs)
        } catch (error: Throwable) {
            val message = "${error.javaClass.simpleName}: ${error.message}"
            logs += message
            runCatching { report?.append("Failure stage: unexpected exception") }
            runCatching { report?.append(message) }
            return result(false, outputDir, control, mean, minMax, sigma, reportFile, reportError, logs)
        } finally {
            val inputCleanup = runCatching { sequence.cleanupTemporaryRawFrames() }.getOrDefault(false)
            runCatching { report?.append("Temporary input cleanup: $inputCleanup") }
            runCatching { report?.append("Processing guard released") }
            runCatching { report?.close() }.onFailure { reportError = it.message }
            processing.release()
        }
    }

    private fun runMode(
        rawStack: RawStack,
        sequence: CapturedRawSequence,
        outputDir: File,
        report: IncrementalDiagnosticReport,
        alignmentReport: LandscapeAlignmentReport,
        label: String,
        stageName: String,
        filename: String,
        aggregationOptions: RawStackAggregationOptions,
        description: String
    ): RealAlignedStackDngResult {
        report.append("$stageName started")
        val packedFile = File(outputDir, ".$filename.packed.raw16")
        packedFile.delete()
        return try {
            val stackResult = AlignedRaw16StackProcessor().processToPackedFile(
                rawStack = rawStack,
                alignmentReport = alignmentReport,
                options = AlignedRaw16StackOptions(
                    aggregationOptions = aggregationOptions,
                    debugValidCountMapEnabled = false,
                    tileHeight = DEFAULT_TILE_HEIGHT
                ),
                outputFile = packedFile
            )
            if (!stackResult.success) {
                val failed = RealAlignedStackDngResult(
                    label = label,
                    success = false,
                    writeResult = Raw16DngWriteResult.failure(
                        Raw16DngFailureCode.PIPELINE_STACKING_FAILED,
                        stackResult.fatalMessage ?: stackResult.fatalError?.name ?: "$stageName failed."
                    ),
                    width = stackResult.width,
                    height = stackResult.height,
                    referenceFrameIndex = stackResult.referenceFrameIndex,
                    processingDurationMs = stackResult.processingDurationMs,
                    warnings = stackResult.warnings.map { "${it.code}: ${it.message}" }
                )
                report.append("$stageName failed: ${failed.writeResult?.failureMessage}")
                failed
            } else {
                val reference = sequence.recordForFrameIndex(stackResult.referenceFrameIndex ?: -1)
                val writeResult =
                    if (reference == null) {
                        Raw16DngWriteResult.failure(
                            Raw16DngFailureCode.PIPELINE_METADATA_FAILED,
                            "$stageName reference metadata is unavailable."
                        )
                    } else {
                        Raw16DngWriter.write(
                            Raw16DngWriteRequest(
                                alignedResult = stackResult,
                                cameraCharacteristics = reference.cameraCharacteristics,
                                captureResult = reference.totalCaptureResult,
                                destination = FileRaw16DngOutputDestination(
                                    File(outputDir, filename),
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
                    }
                val summary = RealAlignedStackDngResult(
                    label = label,
                    success = writeResult.success,
                    writeResult = writeResult,
                    width = stackResult.width,
                    height = stackResult.height,
                    referenceFrameIndex = stackResult.referenceFrameIndex,
                    acceptedFrameCount = stackResult.acceptedFrameCount,
                    rejectedFrameCount = stackResult.rejectedFrameCount,
                    processingDurationMs = stackResult.processingDurationMs,
                    aggregationDiagnostics = stackResult.aggregationDiagnostics.copy(rejectionCountMap = null),
                    appliedTranslations = stackResult.appliedTranslations,
                    commonOverlapRect = stackResult.commonOverlapRect,
                    minimumValidCount = stackResult.minimumValidCount,
                    maximumValidCount = stackResult.maximumValidCount,
                    meanValidCount = stackResult.meanValidCount,
                    singleContributorPixelCount = stackResult.singleContributorPixelCount,
                    fullContributorPixelCount = stackResult.fullContributorPixelCount,
                    warnings = stackResult.warnings.map { "${it.code}: ${it.message}" }
                )
                report.append(
                    "$stageName ${if (summary.success) "completed" else "failed"}: " +
                        resultLine(writeResult)
                )
                summary
            }
        } finally {
            if (packedFile.exists()) {
                report.append("$stageName packed-output cleanup: ${packedFile.delete()}")
            }
        }
    }

    private fun appendSequence(
        report: IncrementalDiagnosticReport,
        sequence: CapturedRawSequence
    ) {
        report.append("Sequence metadata")
        report.append("cameraId=${sequence.cameraId}")
        report.append("frameCount=${sequence.frameCount}")
        sequence.records.forEach { record ->
            report.append(
                "frameIndex=${record.frameIndex} timestamp=${record.resultTimestampNs ?: record.imageTimestampNs} " +
                    "iso=${record.iso} exposureNs=${record.exposureTimeNs} " +
                    "size=${record.width}x${record.height} rowStride=${record.rowStride} " +
                    "pixelStride=${record.pixelStride} cfa=${record.cfaPattern} " +
                    "storage=${if (record.raw16Storage.isFileBacked) "FILE" else "MEMORY"} " +
                    "bytes=${record.raw16Storage.byteCount}"
            )
        }
    }

    private fun appendAlignment(
        report: IncrementalDiagnosticReport,
        alignment: LandscapeAlignmentReport
    ) {
        report.append("Alignment")
        report.append("referenceFrameIndex=${alignment.selectedReferenceFrameIndex}")
        report.append("accepted=${alignment.acceptedFrameCount} rejected=${alignment.rejectedFrameCount}")
        alignment.frameResults.forEach { frame ->
            report.append(
                "frame=${frame.frameIndex} reference=${frame.isReference} accepted=${frame.accepted} " +
                    "response=${frame.phaseResponse} dx=${frame.dxRawPixels} dy=${frame.dyRawPixels} " +
                    "overlap=${frame.overlapFraction} reason=${frame.rejectionReason}"
            )
        }
    }

    private fun appendSummary(
        report: IncrementalDiagnosticReport,
        result: RealAlignedStackDngResult
    ) {
        report.append("${result.label.uppercase(Locale.US)} summary")
        report.append("success=${result.success}")
        report.append("output=${result.width}x${result.height}")
        report.append("referenceFrameIndex=${result.referenceFrameIndex}")
        report.append("accepted=${result.acceptedFrameCount} rejected=${result.rejectedFrameCount}")
        report.append("commonOverlap=${result.commonOverlapRect}")
        report.append(
            "validCount min=${result.minimumValidCount} max=${result.maximumValidCount} " +
                "mean=${result.meanValidCount}"
        )
        result.aggregationDiagnostics?.let {
            report.append(
                "aggregation mode=${it.requestedMode} pixels=${it.pixelsProcessed} " +
                    "inputSamples=${it.totalInputSamples} rejectedSamples=${it.totalRejectedSamples} " +
                    "fallbackMean=${it.pixelsFallingBackToMean} " +
                    "fallbackOther=${it.pixelsFallingBackToOtherMode}"
            )
        }
        result.appliedTranslations.forEach {
            report.append(
                "applied frame=${it.frameIndex} estimatedDx=${it.estimatedDxRaw} " +
                    "estimatedDy=${it.estimatedDyRaw} appliedDx=${it.appliedDxRaw} " +
                    "appliedDy=${it.appliedDyRaw} residual=${it.residualMagnitudeRaw}"
            )
        }
        result.warnings.forEach { report.append("warning=$it") }
    }

    private fun buildIdentityAlignmentReport(rawStack: RawStack): LandscapeAlignmentReport {
        val selection = ReferenceFrameSelector.select(rawStack)
        val options = LandscapeAlignmentOptions(exposureNormalizeProxies = false)
        if (!selection.success) {
            return LandscapeAlignmentReport(
                status = LandscapeAlignmentStatus.FAILURE,
                success = false,
                partialSuccess = false,
                selectedReferenceFrameIndex = null,
                selectedReferencePosition = null,
                referenceSelectionMethod = selection.method,
                referenceExposureTimeSeconds = null,
                totalFrameCount = rawStack.frameCount,
                acceptedFrameCount = 0,
                rejectedFrameCount = rawStack.frameCount,
                lowConfidenceFrameCount = 0,
                frameResults = emptyList(),
                alignmentResults = emptyList(),
                warnings = selection.warnings,
                fatalError = selection.failureReason,
                fatalMessage = selection.message,
                processingDurationMs = 0L,
                options = options
            )
        }
        val frameResults = rawStack.frames.mapIndexed { position, frame ->
            identityFrameAlignment(
                frame = frame,
                position = position,
                isReference = frame.frameIndex == selection.selectedFrameIndex,
                referenceExposureSeconds = selection.referenceExposureTimeSeconds
            )
        }
        return LandscapeAlignmentReport(
            status = LandscapeAlignmentStatus.SUCCESS,
            success = true,
            partialSuccess = false,
            selectedReferenceFrameIndex = selection.selectedFrameIndex,
            selectedReferencePosition = selection.selectedPosition,
            referenceSelectionMethod = selection.method,
            referenceExposureTimeSeconds = selection.referenceExposureTimeSeconds,
            totalFrameCount = rawStack.frameCount,
            acceptedFrameCount = frameResults.size,
            rejectedFrameCount = 0,
            lowConfidenceFrameCount = 0,
            frameResults = frameResults,
            alignmentResults = frameResults.map { it.alignmentResult },
            warnings = selection.warnings,
            fatalError = null,
            fatalMessage = null,
            processingDurationMs = 0L,
            options = options
        )
    }

    private fun identityFrameAlignment(
        frame: RawFrame,
        position: Int,
        isReference: Boolean,
        referenceExposureSeconds: Double?
    ): LandscapeFrameAlignment {
        val alignment = AlignmentResult(
            frameIndex = frame.frameIndex,
            mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
            transform = RawTransform.IDENTITY,
            confidence = 1.0,
            accepted = true,
            diagnosticMessage = if (isReference) "Reference identity transform." else "Control identity transform.",
            rawDx = 0.0,
            rawDy = 0.0,
            response = if (isReference) null else 1.0
        )
        return LandscapeFrameAlignment(
            framePosition = position,
            frameIndex = frame.frameIndex,
            isReference = isReference,
            proxyTypeUsed = null,
            lumaFallbackUsed = false,
            targetExposureTimeSeconds = frame.exposureTimeSeconds.takeIf { it > 0.0 },
            referenceExposureTimeSeconds = referenceExposureSeconds,
            phaseResponse = if (isReference) null else 1.0,
            dxRawPixels = 0.0,
            dyRawPixels = 0.0,
            overlapFraction = 1.0,
            accepted = true,
            lowConfidence = false,
            rejectionReason = null,
            diagnosticMessage = alignment.diagnosticMessage,
            warnings = emptyList(),
            alignmentResult = alignment
        )
    }

    private fun validateSameExposureBurst(sequence: CapturedRawSequence): List<String> {
        val errors = mutableListOf<String>()
        val records = sequence.records
        if (records.size < 3) errors += "At least 3 accepted RAW frames are required."
        val first = records.firstOrNull() ?: return errors
        records.forEach { record ->
            if (record.cameraId != first.cameraId) errors += "Frame ${record.frameIndex} camera ID differs."
            if (record.width != first.width || record.height != first.height) {
                errors += "Frame ${record.frameIndex} dimensions differ."
            }
            if (record.rowStride <= 0 || record.pixelStride != first.pixelStride) {
                errors += "Frame ${record.frameIndex} RAW stride metadata differs."
            }
            if (record.exposureTimeNs != first.exposureTimeNs) {
                errors += "Frame ${record.frameIndex} exposure differs from first frame."
            }
            if (record.iso != first.iso) errors += "Frame ${record.frameIndex} ISO differs from first frame."
            if (record.cfaPattern != first.cfaPattern) errors += "Frame ${record.frameIndex} CFA pattern differs."
            if (!record.blackLevelPattern.contentEqualsOrBothNull(first.blackLevelPattern)) {
                errors += "Frame ${record.frameIndex} black-level pattern differs."
            }
            if (record.whiteLevel != first.whiteLevel) errors += "Frame ${record.frameIndex} white level differs."
        }
        return errors.distinct()
    }

    private fun result(
        success: Boolean,
        outputDir: File,
        control: RealAlignedStackDngResult,
        mean: RealAlignedStackDngResult,
        minMax: RealAlignedStackDngResult,
        sigma: RealAlignedStackDngResult,
        reportFile: File,
        reportError: String?,
        logs: List<String>
    ): RealAlignedStackDiagnosticResult =
        RealAlignedStackDiagnosticResult(
            success = success,
            outputDirectoryPath = outputDir.absolutePath,
            control = control,
            aligned = mean,
            minMax = minMax,
            sigma = sigma,
            reportPath = reportFile.takeIf(File::exists)?.absolutePath,
            reportWriteError = reportError,
            logLines = logs + listOfNotNull(
                reportFile.takeIf(File::exists)?.absolutePath?.let { "DEV report: $it" }
            )
        )

    private fun failedDngResult(
        label: String,
        code: Raw16DngFailureCode,
        message: String
    ): RealAlignedStackDngResult {
        val write = Raw16DngWriteResult.failure(code, message)
        return RealAlignedStackDngResult(
            label = label,
            success = false,
            writeResult = write
        )
    }

    private fun resultLine(result: Raw16DngWriteResult): String =
        if (result.success) {
            "${result.filename} size=${result.bytesWritten ?: "unknown"} " +
                "path=${result.finalPath ?: result.finalUri} writer=${result.writerMethod}"
        } else {
            "FAIL ${result.failureCode ?: "unknown"} ${result.failureMessage.orEmpty()}"
        }

    private fun append(
        report: IncrementalDiagnosticReport,
        logs: MutableList<String>,
        line: String
    ) {
        report.append(line)
        logs += line
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun IntArray?.contentEqualsOrBothNull(other: IntArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> contentEquals(other)
        }

    companion object {
        const val CONTROL_FILENAME = "BracketLab_DEV_ControlIdentity_RAW16.dng"
        const val MEAN_FILENAME = "BracketLab_DEV_AlignedMean_RAW16.dng"
        const val MIN_MAX_FILENAME = "BracketLab_DEV_AlignedMinMax_RAW16.dng"
        const val SIGMA_FILENAME = "BracketLab_DEV_AlignedSigma_RAW16.dng"
        const val REPORT_FILENAME = "BracketLab_DEV_AlignedStack_Report.txt"
        const val DEFAULT_TILE_HEIGHT = 128

        private val processing = SingleFlightGuard()

        fun isProcessing(): Boolean = processing.isActive()
    }
}
