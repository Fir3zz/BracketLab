package com.lab.bracketlab.processing.stack

import com.lab.bracketlab.processing.align.LandscapeAlignmentReport
import com.lab.bracketlab.processing.align.LandscapeFrameAlignment
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import com.lab.bracketlab.processing.calibration.DarkPolicy
import com.lab.bracketlab.processing.calibration.DarkSubtractor
import com.lab.bracketlab.processing.calibration.MasterDark
import com.lab.bracketlab.processing.calibration.MasterDarkMatcher
import com.lab.bracketlab.processing.calibration.MissingMasterDarkBehavior
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * CFA-safe RAW16 stack core.
 *
 * This processor is synchronous and UI-free. Callers must run it outside the
 * Android main thread. It consumes accepted translations only and does not write
 * DNG output.
 */
class AlignedRaw16StackProcessor {
    fun process(
        rawStack: RawStack,
        alignmentReport: LandscapeAlignmentReport,
        options: AlignedRaw16StackOptions = AlignedRaw16StackOptions()
    ): AlignedRaw16StackResult =
        processInternal(rawStack, alignmentReport, options, PackedOutputTarget.Allocate)

    fun processIntoPackedBuffer(
        rawStack: RawStack,
        alignmentReport: LandscapeAlignmentReport,
        options: AlignedRaw16StackOptions = AlignedRaw16StackOptions(),
        reusableOutputBuffer: ByteBuffer
    ): AlignedRaw16StackResult =
        processInternal(
            rawStack,
            alignmentReport,
            options,
            PackedOutputTarget.CallerBuffer(reusableOutputBuffer)
        )

    fun processToPackedFile(
        rawStack: RawStack,
        alignmentReport: LandscapeAlignmentReport,
        options: AlignedRaw16StackOptions = AlignedRaw16StackOptions(),
        outputFile: File
    ): AlignedRaw16StackResult =
        processInternal(
            rawStack,
            alignmentReport,
            options,
            PackedOutputTarget.FileTarget(outputFile)
        )

    private fun processInternal(
        rawStack: RawStack,
        alignmentReport: LandscapeAlignmentReport,
        options: AlignedRaw16StackOptions,
        outputTarget: PackedOutputTarget
    ): AlignedRaw16StackResult {
        val startedNs = System.nanoTime()
        val warnings = mutableListOf<AlignedRaw16StackWarning>()

        if (rawStack.frames.isEmpty()) {
            return failure(
                rawStack = rawStack,
                alignmentReport = alignmentReport,
                options = options,
                warnings = warnings,
                startedNs = startedNs,
                code = AlignedRaw16StackFailureCode.EMPTY_STACK,
                message = "RawStack is empty."
            )
        }

        val frameMap = mutableMapOf<Int, RawFrame>()
        for (frame in rawStack.frames) {
            if (frameMap.put(frame.frameIndex, frame) != null) {
                return failure(
                    rawStack,
                    alignmentReport,
                    options,
                    warnings,
                    startedNs,
                    AlignedRaw16StackFailureCode.DUPLICATE_FRAME_INDEX,
                    "Duplicate RawFrame frameIndex ${frame.frameIndex}."
                )
            }
        }

        val referenceFrameIndex =
            alignmentReport.selectedReferenceFrameIndex
                ?: return failure(
                    rawStack,
                    alignmentReport,
                    options,
                    warnings,
                    startedNs,
                    AlignedRaw16StackFailureCode.ALIGNMENT_REPORT_MISMATCH,
                    "Alignment report has no selected reference frame."
                )
        val referenceFrame =
            frameMap[referenceFrameIndex]
                ?: return failure(
                    rawStack,
                    alignmentReport,
                    options,
                    warnings,
                    startedNs,
                    AlignedRaw16StackFailureCode.ALIGNMENT_REPORT_MISMATCH,
                    "Reference frame $referenceFrameIndex is not present in RawStack."
                )

        val alignmentByFrame = mutableMapOf<Int, LandscapeFrameAlignment>()
        for (result in alignmentReport.frameResults) {
            if (!frameMap.containsKey(result.frameIndex)) {
                return failure(
                    rawStack,
                    alignmentReport,
                    options,
                    warnings,
                    startedNs,
                    AlignedRaw16StackFailureCode.ALIGNMENT_REPORT_MISMATCH,
                    "Alignment result references unknown frameIndex ${result.frameIndex}."
                )
            }
            if (alignmentByFrame.put(result.frameIndex, result) != null) {
                return failure(
                    rawStack,
                    alignmentReport,
                    options,
                    warnings,
                    startedNs,
                    AlignedRaw16StackFailureCode.DUPLICATE_FRAME_INDEX,
                    "Duplicate alignment result for frameIndex ${result.frameIndex}."
                )
            }
        }

        for (frame in rawStack.frames) {
            if (!alignmentByFrame.containsKey(frame.frameIndex)) {
                return failure(
                    rawStack,
                    alignmentReport,
                    options,
                    warnings,
                    startedNs,
                    AlignedRaw16StackFailureCode.MISSING_ALIGNMENT_RESULT,
                    "Missing alignment result for frameIndex ${frame.frameIndex}."
                )
            }
        }

        val referenceAlignment =
            alignmentByFrame[referenceFrameIndex]
                ?: return failure(
                    rawStack,
                    alignmentReport,
                    options,
                    warnings,
                    startedNs,
                    AlignedRaw16StackFailureCode.INVALID_REFERENCE_RESULT,
                    "Missing reference alignment result."
                )
        if (!isValidReferenceResult(referenceAlignment)) {
            return failure(
                rawStack,
                alignmentReport,
                options,
                warnings,
                startedNs,
                AlignedRaw16StackFailureCode.INVALID_REFERENCE_RESULT,
                "Reference result must be accepted with identity transform."
            )
        }

        val referenceValidation = validateFrameBuffer(referenceFrame)
        if (!referenceValidation.valid) {
            return failure(
                rawStack,
                alignmentReport,
                options,
                warnings,
                startedNs,
                referenceValidation.failureCode ?: AlignedRaw16StackFailureCode.INVALID_RAW_BUFFER,
                referenceValidation.message ?: "Invalid reference RAW buffer."
            )
        }
        val requiredOutputBytes = referenceFrame.width.toLong() * referenceFrame.height.toLong() * 2L
        if (
            outputTarget is PackedOutputTarget.CallerBuffer &&
            (
                requiredOutputBytes !in 1L..Int.MAX_VALUE.toLong() ||
                    outputTarget.buffer.capacity().toLong() < requiredOutputBytes
                )
        ) {
            return failure(
                rawStack,
                alignmentReport,
                options,
                warnings,
                startedNs,
                AlignedRaw16StackFailureCode.OUTPUT_BUFFER_TOO_SMALL,
                "Caller output buffer capacity ${outputTarget.buffer.capacity()} is smaller than $requiredOutputBytes bytes."
            )
        }
        val darkResolution = resolveDarkCalibration(referenceFrame, options)
        if (darkResolution.failureMessage != null) {
            return failure(
                rawStack,
                alignmentReport,
                options,
                warnings,
                startedNs,
                AlignedRaw16StackFailureCode.DARK_CALIBRATION_FAILED,
                darkResolution.failureMessage
            )
        }
        warnings += darkResolution.warnings

        val acceptedStates = mutableListOf<ValidatedFrameStackState>()
        val rejectedTranslations = mutableListOf<AppliedRawTranslation>()
        for (frame in rawStack.frames) {
            val alignment = alignmentByFrame[frame.frameIndex]!!
            if (!alignment.accepted) {
                warnings += warning(
                    AlignedRaw16StackWarningCode.FRAME_REJECTED_BY_ALIGNMENT,
                    frame.frameIndex,
                    "Frame was rejected by landscape alignment and will not be averaged."
                )
                rejectedTranslations += rejectedTranslation(frame.frameIndex, alignment)
                continue
            }

            val compatibility = validateFrameCompatibility(referenceFrame, frame, options)
            if (compatibility != null) {
                if (compatibility.fatal) {
                    rejectedTranslations += rejectedTranslation(frame.frameIndex, alignment, compatibility.code)
                    return failure(
                        rawStack,
                        alignmentReport,
                        options,
                        warnings + compatibility.warnings,
                        startedNs,
                        compatibility.code,
                        compatibility.message
                    )
                }
                warnings += compatibility.warnings
            }

            val bufferValidation = validateFrameBuffer(frame)
            if (!bufferValidation.valid) {
                rejectedTranslations += rejectedTranslation(
                    frame.frameIndex,
                    alignment,
                    bufferValidation.failureCode ?: AlignedRaw16StackFailureCode.INVALID_RAW_BUFFER
                )
                return failure(
                    rawStack,
                    alignmentReport,
                    options,
                    warnings,
                    startedNs,
                    bufferValidation.failureCode ?: AlignedRaw16StackFailureCode.INVALID_RAW_BUFFER,
                    bufferValidation.message ?: "Invalid RAW buffer."
                )
            }

            val transformValidation = validateTransform(alignment)
            if (transformValidation != null) {
                return failure(
                    rawStack,
                    alignmentReport,
                    options,
                    warnings,
                    startedNs,
                    transformValidation.first,
                    "Frame ${frame.frameIndex}: ${transformValidation.second}"
                )
            }
            val estimated = alignment.alignmentResult.transform

            val applied = AppliedRawTranslation.fromEstimated(
                frameIndex = frame.frameIndex,
                isReference = frame.frameIndex == referenceFrameIndex,
                estimatedDxRaw = if (frame.frameIndex == referenceFrameIndex) 0.0 else estimated.dx,
                estimatedDyRaw = if (frame.frameIndex == referenceFrameIndex) 0.0 else estimated.dy,
                accepted = true,
                rejectionReason = null,
                warnings = emptyList()
            )
            val appliedWarnings = translationWarnings(applied, options)
            if (
                options.rejectExcessiveQuantizationResidual &&
                applied.residualMagnitudeRaw > options.maximumQuantizationResidualPixels
            ) {
                rejectedTranslations += applied.copy(
                    accepted = false,
                    rejectionReason = AlignedRaw16StackFailureCode.EXCESSIVE_QUANTIZATION_RESIDUAL,
                    warnings = appliedWarnings
                )
                return failure(
                    rawStack,
                    alignmentReport,
                    options,
                    warnings + appliedWarnings.map {
                        warning(it, frame.frameIndex, "Translation quantization residual is ${applied.residualMagnitudeRaw}.")
                    },
                    startedNs,
                    AlignedRaw16StackFailureCode.EXCESSIVE_QUANTIZATION_RESIDUAL,
                    "Frame ${frame.frameIndex} quantization residual exceeds configured maximum."
                )
            }

            warnings += appliedWarnings.map {
                warning(it, frame.frameIndex, "Translation quantization residual is ${applied.residualMagnitudeRaw}.")
            }
            acceptedStates += ValidatedFrameStackState(
                frame = frame,
                appliedTranslation = applied.copy(warnings = appliedWarnings)
            )
        }

        val minimumFrames =
            if (options.allowSingleFrameStack) 1 else max(2, options.minimumAcceptedFrames)
        if (acceptedStates.size < minimumFrames) {
            return failure(
                rawStack,
                alignmentReport,
                options,
                warnings,
                startedNs,
                AlignedRaw16StackFailureCode.INSUFFICIENT_ACCEPTED_FRAMES,
                "Only ${acceptedStates.size} accepted frames remain; required $minimumFrames."
            )
        }

        val stackStates = acceptedStates.map {
            FrameStackState(
                frame = it.frame,
                accessor = Raw16SampleAccessor.create(it.frame),
                appliedTranslation = it.appliedTranslation
            )
        }
        val darkAccessor =
            darkResolution.masterDark?.let {
                Raw16SampleAccessor.create(it.toRawFrame(frameIndex = Int.MIN_VALUE))
            }
        val stackOutput =
            try {
                stackSamples(
                    referenceFrame,
                    stackStates,
                    options,
                    outputTarget,
                    darkResolution.masterDark,
                    darkAccessor
                )
            } finally {
                stackStates.forEach { runCatching { it.accessor.close() } }
                runCatching { darkAccessor?.close() }
            }
        if (stackOutput.noValidSample) {
            stackOutput.outputRaw16FilePath?.let { runCatching { File(it).delete() } }
            return failure(
                rawStack,
                alignmentReport,
                options,
                warnings,
                startedNs,
                AlignedRaw16StackFailureCode.NO_VALID_SAMPLE,
                "At least one output pixel had no valid source sample."
            )
        }
        if (stackOutput.singleContributorPixelCount > 0 || stackOutput.fullContributorPixelCount < referenceFrame.width * referenceFrame.height) {
            warnings += warning(
                AlignedRaw16StackWarningCode.REDUCED_BORDER_SAMPLE_COUNT,
                null,
                "Shifted borders have fewer contributing frames."
            )
        }
        if (
            stackOutput.aggregationDiagnostics.pixelsFallingBackToMean > 0L ||
            stackOutput.aggregationDiagnostics.pixelsFallingBackToOtherMode > 0L
        ) {
            warnings += warning(
                AlignedRaw16StackWarningCode.ROBUST_AGGREGATION_FALLBACK,
                null,
                "Robust aggregation used a configured fallback for " +
                    "${stackOutput.aggregationDiagnostics.pixelsFallingBackToMean + stackOutput.aggregationDiagnostics.pixelsFallingBackToOtherMode} pixels."
            )
        }

        val translations = acceptedStates.map { it.appliedTranslation } + rejectedTranslations
        return AlignedRaw16StackResult(
            success = true,
            width = referenceFrame.width,
            height = referenceFrame.height,
            outputRaw16 = stackOutput.outputRaw16,
            outputRaw16FilePath = stackOutput.outputRaw16FilePath,
            outputByteOrder = ByteOrder.LITTLE_ENDIAN,
            outputRowStride = referenceFrame.width * 2,
            outputPixelStride = 2,
            referenceFrameIndex = referenceFrameIndex,
            inputFrameCount = rawStack.frameCount,
            acceptedFrameCount = acceptedStates.size,
            rejectedFrameCount = rawStack.frameCount - acceptedStates.size,
            appliedTranslations = translations.sortedByInputOrder(rawStack.frames),
            minimumValidCount = stackOutput.minimumValidCount,
            maximumValidCount = stackOutput.maximumValidCount,
            meanValidCount = stackOutput.meanValidCount,
            singleContributorPixelCount = stackOutput.singleContributorPixelCount,
            fullContributorPixelCount = stackOutput.fullContributorPixelCount,
            commonOverlapRect = commonOverlap(referenceFrame.width, referenceFrame.height, acceptedStates.map { it.appliedTranslation }),
            warnings = warnings,
            fatalError = null,
            fatalMessage = null,
            processingDurationMs = elapsedMs(startedNs),
            options = options,
            aggregationDiagnostics = stackOutput.aggregationDiagnostics,
            validCountMap = stackOutput.validCountMap,
            darkCalibrationApplied = darkResolution.masterDark != null,
            masterDarkId = darkResolution.masterDark?.metadata?.id,
            masterDarkFrameCount = darkResolution.masterDark?.metadata?.frameCount ?: 0
        )
    }

    fun targetToReferenceTranslationToSourceLookup(estimatedDx: Int, estimatedDy: Int): Pair<Int, Int> =
        Pair(-estimatedDx, -estimatedDy)

    fun sourceCoordinateForReferencePixel(outputX: Int, outputY: Int, appliedDx: Int, appliedDy: Int): Pair<Int, Int> =
        Pair(outputX - appliedDx, outputY - appliedDy)

    private fun stackSamples(
        referenceFrame: RawFrame,
        states: List<FrameStackState>,
        options: AlignedRaw16StackOptions,
        outputTarget: PackedOutputTarget,
        masterDark: MasterDark?,
        masterDarkAccessor: Raw16SampleAccessor?
    ): StackOutput {
        val width = referenceFrame.width
        val height = referenceFrame.height
        val requiredBytesLong = width.toLong() * height.toLong() * 2L
        require(requiredBytesLong in 1L..Int.MAX_VALUE.toLong()) {
            "Packed RAW16 output exceeds supported ByteBuffer/file row contract."
        }
        val requiredBytes = requiredBytesLong.toInt()
        val outputBuffer =
            when (outputTarget) {
                PackedOutputTarget.Allocate ->
                    ByteBuffer.allocate(requiredBytes).order(ByteOrder.LITTLE_ENDIAN)
                is PackedOutputTarget.CallerBuffer -> {
                    require(outputTarget.buffer.capacity() >= requiredBytes) {
                        "Caller output buffer capacity ${outputTarget.buffer.capacity()} is smaller than $requiredBytes."
                    }
                    outputTarget.buffer.duplicate().apply {
                        order(ByteOrder.LITTLE_ENDIAN)
                        position(0)
                        limit(requiredBytes)
                    }
                }
                is PackedOutputTarget.FileTarget -> null
            }
        val outputFile = (outputTarget as? PackedOutputTarget.FileTarget)?.file
        outputFile?.parentFile?.mkdirs()
        val outputStream = outputFile?.let {
            BufferedOutputStream(FileOutputStream(it, false), OUTPUT_STREAM_BUFFER_BYTES)
        }
        val packedRow = ByteArray(width * 2)
        val validCountMap = if (options.debugValidCountMapEnabled) IntArray(width * height) else null
        val aggregationOptions = options.aggregationOptions
        val rejectionCountMap =
            if (aggregationOptions.debugRejectionCountMapEnabled) IntArray(width * height) else null
        val sampleScratch = IntArray(states.size)
        val aggregationScratch = RawStackAggregator.createScratch(states.size)
        val aggregationWork = RawStackAggregationWorkResult()
        var minCount = Int.MAX_VALUE
        var maxCount = 0
        var countTotal = 0L
        var singleCount = 0
        var fullCount = 0
        var noValidSample = false
        val allFrameCount = states.size
        var totalAcceptedSamples = 0L
        var totalRejectedSamples = 0L
        var pixelsUsingRequestedMode = 0L
        var pixelsFallingBackToMean = 0L
        var pixelsFallingBackToOtherMode = 0L
        var maximumRejectedSamplesAtOnePixel = 0
        var pixelsWithZeroVariance = 0L
        var pixelsWithInsufficientSamples = 0L

        try {
            for (tileStartY in 0 until height step options.tileHeight) {
                val tileEndY = min(height, tileStartY + options.tileHeight)
                for (y in tileStartY until tileEndY) {
                    var rowOffset = 0
                    for (x in 0 until width) {
                var validCount = 0
                for (state in states) {
                    val sourceX = x - state.appliedTranslation.appliedDxRaw
                    val sourceY = y - state.appliedTranslation.appliedDyRaw
                    if (sourceX !in 0 until width || sourceY !in 0 until height) continue
                    val lightSample = state.accessor.sampleAt(sourceX, sourceY)
                    sampleScratch[validCount] =
                        if (masterDark != null && masterDarkAccessor != null) {
                            DarkSubtractor.subtractDarkRaw16At(
                                lightSample = lightSample,
                                masterDarkSample = masterDarkAccessor.sampleAt(sourceX, sourceY),
                                blackLevelPattern = state.frame.blackLevelPattern,
                                x = sourceX,
                                y = sourceY
                            )
                        } else {
                            lightSample
                        }
                    validCount++
                }
                if (validCount == 0) {
                    noValidSample = true
                    packedRow[rowOffset] = 0
                    packedRow[rowOffset + 1] = 0
                } else {
                    val value = RawStackAggregator.aggregateInto(
                        samples = sampleScratch,
                        sampleCount = validCount,
                        options = aggregationOptions,
                        scratch = aggregationScratch,
                        output = aggregationWork
                    )
                    packedRow[rowOffset] = (value and 0xFF).toByte()
                    packedRow[rowOffset + 1] = ((value ushr 8) and 0xFF).toByte()
                    if (aggregationOptions.diagnosticsEnabled) {
                        totalAcceptedSamples += aggregationWork.acceptedSampleCount.toLong()
                        totalRejectedSamples += aggregationWork.totalRejectedCount.toLong()
                        if (aggregationWork.appliedMode == aggregationOptions.mode) {
                            pixelsUsingRequestedMode++
                        } else if (aggregationWork.appliedMode == RawStackAggregationMode.MEAN) {
                            pixelsFallingBackToMean++
                        } else {
                            pixelsFallingBackToOtherMode++
                        }
                        maximumRejectedSamplesAtOnePixel = max(
                            maximumRejectedSamplesAtOnePixel,
                            aggregationWork.totalRejectedCount
                        )
                        if (aggregationWork.zeroVariance) pixelsWithZeroVariance++
                        if (
                            aggregationWork.fallbackReason ==
                            RawStackAggregationFallbackReason.INSUFFICIENT_SAMPLES_FOR_MIN_MAX ||
                            aggregationWork.fallbackReason ==
                            RawStackAggregationFallbackReason.INSUFFICIENT_SAMPLES_FOR_SIGMA ||
                            aggregationWork.fallbackReason ==
                            RawStackAggregationFallbackReason.INSUFFICIENT_REMAINING_SAMPLES
                        ) {
                            pixelsWithInsufficientSamples++
                        }
                    }
                }
                val mapIndex = y * width + x
                validCountMap?.set(mapIndex, validCount)
                rejectionCountMap?.set(
                    mapIndex,
                    if (validCount == 0) 0 else aggregationWork.totalRejectedCount
                )
                minCount = min(minCount, validCount)
                maxCount = max(maxCount, validCount)
                countTotal += validCount.toLong()
                if (validCount == 1) singleCount++
                if (validCount == allFrameCount) fullCount++
                        rowOffset += 2
                    }
                    if (outputBuffer != null) {
                        outputBuffer.put(packedRow, 0, packedRow.size)
                    } else {
                        outputStream!!.write(packedRow)
                    }
                }
            }
            outputStream?.flush()
        } catch (e: Throwable) {
            runCatching { outputStream?.close() }
            outputFile?.delete()
            throw e
        } finally {
            runCatching { outputStream?.close() }
        }
        outputBuffer?.position(0)
        outputBuffer?.limit(requiredBytes)
        val readOnlyOutput = outputBuffer?.asReadOnlyBuffer()?.apply {
            order(ByteOrder.LITTLE_ENDIAN)
            position(0)
            limit(requiredBytes)
        }
        val pixelCount = width * height
        val aggregationDiagnostics =
            if (aggregationOptions.diagnosticsEnabled) {
                RawStackAggregationDiagnostics(
                    requestedMode = aggregationOptions.mode,
                    pixelsProcessed = pixelCount.toLong(),
                    totalInputSamples = countTotal,
                    totalAcceptedSamples = totalAcceptedSamples,
                    totalRejectedSamples = totalRejectedSamples,
                    pixelsUsingRequestedMode = pixelsUsingRequestedMode,
                    pixelsFallingBackToMean = pixelsFallingBackToMean,
                    pixelsFallingBackToOtherMode = pixelsFallingBackToOtherMode,
                    maximumRejectedSamplesAtOnePixel = maximumRejectedSamplesAtOnePixel,
                    meanRejectedSamplesPerPixel =
                        if (pixelCount == 0) 0.0 else totalRejectedSamples.toDouble() / pixelCount.toDouble(),
                    pixelsWithZeroVariance = pixelsWithZeroVariance,
                    pixelsWithInsufficientSamples = pixelsWithInsufficientSamples,
                    rejectionCountMap = rejectionCountMap
                )
            } else {
                RawStackAggregationDiagnostics.empty(aggregationOptions.mode)
            }
        return StackOutput(
            outputRaw16 = readOnlyOutput,
            outputRaw16FilePath = outputFile?.absolutePath,
            minimumValidCount = if (pixelCount == 0) 0 else minCount,
            maximumValidCount = maxCount,
            meanValidCount = if (pixelCount == 0) 0.0 else countTotal.toDouble() / pixelCount.toDouble(),
            singleContributorPixelCount = singleCount,
            fullContributorPixelCount = fullCount,
            validCountMap = validCountMap,
            aggregationDiagnostics = aggregationDiagnostics,
            noValidSample = noValidSample
        )
    }

    private fun validateFrameCompatibility(
        reference: RawFrame,
        frame: RawFrame,
        options: AlignedRaw16StackOptions
    ): CompatibilityFailure? {
        val warnings = mutableListOf<AlignedRaw16StackWarning>()
        if (options.requireSameDimensions && (frame.width != reference.width || frame.height != reference.height)) {
            return fatal(AlignedRaw16StackFailureCode.INCOMPATIBLE_DIMENSIONS, frame.frameIndex, "Frame dimensions differ from reference.")
        }
        if (options.requireSameCfaPattern) {
            val refCfa = reference.cfaPattern
            val cfa = frame.cfaPattern
            if (refCfa == null || cfa == null) {
                val issue = metadataIssue(
                    options = options,
                    code = AlignedRaw16StackFailureCode.INCOMPATIBLE_CFA,
                    warningCode = AlignedRaw16StackWarningCode.MISSING_CFA_METADATA,
                    frameIndex = frame.frameIndex,
                    message = "CFA metadata is missing."
                )
                if (issue.failure != null) return issue.failure
                warnings += issue.warning!!
            } else if (refCfa != cfa) {
                return fatal(AlignedRaw16StackFailureCode.INCOMPATIBLE_CFA, frame.frameIndex, "CFA pattern differs from reference.")
            }
        }
        if (options.requireSameCameraIdWhenKnown) {
            val refCamera = reference.cameraId
            val camera = frame.cameraId
            if (refCamera == null || camera == null) {
                warnings += warning(
                    AlignedRaw16StackWarningCode.MISSING_CAMERA_ID,
                    frame.frameIndex,
                    "Camera ID metadata is missing; compatibility checked only when known."
                )
            } else if (refCamera != camera) {
                return fatal(AlignedRaw16StackFailureCode.INCOMPATIBLE_CAMERA, frame.frameIndex, "Camera ID differs from reference.")
            }
        }
        if (options.requireSameIso) {
            if (reference.iso <= 0 || frame.iso <= 0) {
                val issue = metadataIssue(
                    options = options,
                    code = AlignedRaw16StackFailureCode.INCOMPATIBLE_ISO,
                    warningCode = AlignedRaw16StackWarningCode.MISSING_ISO_METADATA,
                    frameIndex = frame.frameIndex,
                    message = "ISO metadata is invalid."
                )
                if (issue.failure != null) return issue.failure
                warnings += issue.warning!!
            } else if (reference.iso != frame.iso) {
                return fatal(AlignedRaw16StackFailureCode.INCOMPATIBLE_ISO, frame.frameIndex, "ISO differs from reference.")
            }
        }
        if (options.requireSameExposure) {
            if (reference.exposureTimeNs <= 0L || frame.exposureTimeNs <= 0L) {
                val issue = metadataIssue(
                    options = options,
                    code = AlignedRaw16StackFailureCode.INCOMPATIBLE_EXPOSURE,
                    warningCode = AlignedRaw16StackWarningCode.MISSING_EXPOSURE_METADATA,
                    frameIndex = frame.frameIndex,
                    message = "Exposure metadata is invalid."
                )
                if (issue.failure != null) return issue.failure
                warnings += issue.warning!!
            } else if (!exposureMatches(reference.exposureTimeNs, frame.exposureTimeNs, options.exposureRelativeTolerance)) {
                return fatal(AlignedRaw16StackFailureCode.INCOMPATIBLE_EXPOSURE, frame.frameIndex, "Exposure differs from reference.")
            }
        }
        if (options.requireCompatibleBlackLevel) {
            val refBlack = reference.blackLevelPattern
            val black = frame.blackLevelPattern
            if (refBlack == null || black == null) {
                val issue = metadataIssue(
                    options = options,
                    code = AlignedRaw16StackFailureCode.INCOMPATIBLE_BLACK_LEVEL,
                    warningCode = AlignedRaw16StackWarningCode.MISSING_BLACK_LEVEL_METADATA,
                    frameIndex = frame.frameIndex,
                    message = "Black-level metadata is missing."
                )
                if (issue.failure != null) return issue.failure
                warnings += issue.warning!!
            } else if (!refBlack.contentEquals(black)) {
                return fatal(AlignedRaw16StackFailureCode.INCOMPATIBLE_BLACK_LEVEL, frame.frameIndex, "Black-level pattern differs from reference.")
            }
        }
        if (options.requireCompatibleWhiteLevel) {
            val refWhite = reference.whiteLevel
            val white = frame.whiteLevel
            if (refWhite == null || white == null) {
                val issue = metadataIssue(
                    options = options,
                    code = AlignedRaw16StackFailureCode.INCOMPATIBLE_WHITE_LEVEL,
                    warningCode = AlignedRaw16StackWarningCode.MISSING_WHITE_LEVEL_METADATA,
                    frameIndex = frame.frameIndex,
                    message = "White-level metadata is missing."
                )
                if (issue.failure != null) return issue.failure
                warnings += issue.warning!!
            } else if (refWhite != white) {
                return fatal(AlignedRaw16StackFailureCode.INCOMPATIBLE_WHITE_LEVEL, frame.frameIndex, "White level differs from reference.")
            }
        }
        return if (warnings.isEmpty()) null else CompatibilityFailure(
            code = AlignedRaw16StackFailureCode.ALIGNMENT_REPORT_MISMATCH,
            message = "Metadata warnings.",
            fatal = false,
            warnings = warnings
        )
    }

    private fun validateFrameBuffer(frame: RawFrame): Raw16BufferValidation =
        Raw16SampleAccessor.validate(frame)

    private fun resolveDarkCalibration(
        referenceFrame: RawFrame,
        options: AlignedRaw16StackOptions
    ): DarkResolution {
        val input = options.darkCalibration
        if (input.policy == DarkPolicy.OFF) return DarkResolution()
        if (input.policy == DarkPolicy.CAPTURE_MASTER_DARK) {
            return DarkResolution(
                failureMessage = "CAPTURE_MASTER_DARK cannot be used in light-stack processing."
            )
        }
        val masterDark = input.masterDark
        if (masterDark == null) {
            return if (
                input.options.missingMasterDarkBehavior == MissingMasterDarkBehavior.FAIL
            ) {
                DarkResolution(failureMessage = "A compatible MasterDark is required but was not provided.")
            } else {
                DarkResolution(
                    warnings = listOf(
                        warning(
                            AlignedRaw16StackWarningCode.DARK_CALIBRATION_SKIPPED,
                            null,
                            "No MasterDark was provided; stacking continued without dark subtraction."
                        )
                    )
                )
            }
        }
        val reasons = MasterDarkMatcher.rejectionReasons(referenceFrame, masterDark, input.options)
        if (reasons.isNotEmpty()) {
            val message = "MasterDark ${masterDark.metadata.id} is incompatible: ${reasons.joinToString()}."
            return if (
                input.options.missingMasterDarkBehavior == MissingMasterDarkBehavior.FAIL
            ) {
                DarkResolution(failureMessage = message)
            } else {
                DarkResolution(
                    warnings = listOf(
                        warning(
                            AlignedRaw16StackWarningCode.DARK_CALIBRATION_SKIPPED,
                            null,
                            "$message Stacking continued without dark subtraction."
                        )
                    )
                )
            }
        }
        return DarkResolution(
            masterDark = masterDark,
            warnings = listOf(
                warning(
                    AlignedRaw16StackWarningCode.MASTER_DARK_APPLIED,
                    null,
                    "MasterDark ${masterDark.metadata.id} was applied on the fly."
                )
            )
        )
    }

    private fun validateTransform(alignment: LandscapeFrameAlignment): Pair<AlignedRaw16StackFailureCode, String>? {
        val transform = alignment.alignmentResult.transform
        if (alignment.alignmentResult.mode != ResolvedAlignmentMode.LANDSCAPE_TRANSLATION) {
            return AlignedRaw16StackFailureCode.UNSUPPORTED_TRANSFORM_TYPE to "Transform mode is not LANDSCAPE_TRANSLATION."
        }
        if (abs(transform.rotationDegrees) > 0.000001 || abs(transform.scale - 1.0) > 0.000001) {
            return AlignedRaw16StackFailureCode.UNSUPPORTED_TRANSFORM_TYPE to "Transform is not pure translation."
        }
        if (!transform.dx.isFiniteForStack() || !transform.dy.isFiniteForStack()) {
            return AlignedRaw16StackFailureCode.NON_FINITE_TRANSLATION to "Translation is non-finite."
        }
        return null
    }

    private fun isValidReferenceResult(result: LandscapeFrameAlignment): Boolean {
        val transform = result.alignmentResult.transform
        return result.isReference &&
            result.accepted &&
            result.alignmentResult.accepted &&
            transform.dx == 0.0 &&
            transform.dy == 0.0 &&
            abs(transform.rotationDegrees) <= 0.000001 &&
            abs(transform.scale - 1.0) <= 0.000001
    }

    private fun rejectedTranslation(
        frameIndex: Int,
        alignment: LandscapeFrameAlignment,
        reason: AlignedRaw16StackFailureCode? = null
    ): AppliedRawTranslation =
        AppliedRawTranslation.fromEstimated(
            frameIndex = frameIndex,
            isReference = alignment.isReference,
            estimatedDxRaw = alignment.alignmentResult.transform.dx,
            estimatedDyRaw = alignment.alignmentResult.transform.dy,
            accepted = false,
            rejectionReason = reason,
            warnings = emptyList()
        )

    private fun translationWarnings(
        applied: AppliedRawTranslation,
        options: AlignedRaw16StackOptions
    ): List<AlignedRaw16StackWarningCode> {
        val warnings = mutableListOf<AlignedRaw16StackWarningCode>()
        if (applied.quantizationChanged && options.warnOnQuantization) {
            warnings += AlignedRaw16StackWarningCode.TRANSLATION_QUANTIZED_TO_CFA_SAFE_EVEN
        }
        if (applied.residualMagnitudeRaw > 0.0) {
            warnings += AlignedRaw16StackWarningCode.QUANTIZATION_RESIDUAL_PRESENT
        }
        return warnings
    }

    private fun commonOverlap(width: Int, height: Int, translations: List<AppliedRawTranslation>): CommonOverlapRect {
        var left = 0
        var top = 0
        var right = width
        var bottom = height
        for (translation in translations.filter { it.accepted }) {
            val dx = translation.appliedDxRaw
            val dy = translation.appliedDyRaw
            left = max(left, dx)
            top = max(top, dy)
            right = min(right, width + dx)
            bottom = min(bottom, height + dy)
        }
        return CommonOverlapRect(
            left = left.coerceIn(0, width),
            top = top.coerceIn(0, height),
            rightExclusive = right.coerceIn(0, width),
            bottomExclusive = bottom.coerceIn(0, height)
        )
    }

    private fun failure(
        rawStack: RawStack,
        alignmentReport: LandscapeAlignmentReport,
        options: AlignedRaw16StackOptions,
        warnings: List<AlignedRaw16StackWarning>,
        startedNs: Long,
        code: AlignedRaw16StackFailureCode,
        message: String
    ): AlignedRaw16StackResult =
        AlignedRaw16StackResult(
            success = false,
            width = rawStack.frames.firstOrNull()?.width ?: 0,
            height = rawStack.frames.firstOrNull()?.height ?: 0,
            outputRaw16 = null,
            outputByteOrder = ByteOrder.LITTLE_ENDIAN,
            outputRowStride = rawStack.frames.firstOrNull()?.width?.times(2) ?: 0,
            outputPixelStride = 2,
            referenceFrameIndex = alignmentReport.selectedReferenceFrameIndex,
            inputFrameCount = rawStack.frameCount,
            acceptedFrameCount = 0,
            rejectedFrameCount = rawStack.frameCount,
            appliedTranslations = emptyList(),
            minimumValidCount = 0,
            maximumValidCount = 0,
            meanValidCount = 0.0,
            singleContributorPixelCount = 0,
            fullContributorPixelCount = 0,
            commonOverlapRect = null,
            warnings = warnings,
            fatalError = code,
            fatalMessage = message,
            processingDurationMs = elapsedMs(startedNs),
            options = options,
            aggregationDiagnostics =
                RawStackAggregationDiagnostics.empty(options.aggregationOptions.mode),
            validCountMap = null
        )

    private fun exposureMatches(referenceNs: Long, frameNs: Long, tolerance: Double): Boolean {
        val reference = referenceNs.toDouble().coerceAtLeast(1.0)
        return abs(frameNs.toDouble() - referenceNs.toDouble()) / reference <= tolerance
    }

    private fun metadataIssue(
        options: AlignedRaw16StackOptions,
        code: AlignedRaw16StackFailureCode,
        warningCode: AlignedRaw16StackWarningCode,
        frameIndex: Int,
        message: String
    ): MetadataIssue =
        if (options.missingMetadataPolicy == MissingMetadataPolicy.REJECT) {
            MetadataIssue(failure = fatal(code, frameIndex, message), warning = null)
        } else {
            MetadataIssue(failure = null, warning = warning(warningCode, frameIndex, message))
        }

    private fun fatal(
        code: AlignedRaw16StackFailureCode,
        frameIndex: Int,
        message: String
    ): CompatibilityFailure =
        CompatibilityFailure(
            code = code,
            message = "Frame $frameIndex: $message",
            fatal = true,
            warnings = emptyList()
        )

    private fun warning(
        code: AlignedRaw16StackWarningCode,
        frameIndex: Int?,
        message: String
    ): AlignedRaw16StackWarning =
        AlignedRaw16StackWarning(code = code, frameIndex = frameIndex, message = message)

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L

    private fun Double.isFiniteForStack(): Boolean =
        !isNaN() && this != Double.POSITIVE_INFINITY && this != Double.NEGATIVE_INFINITY

    private fun List<AppliedRawTranslation>.sortedByInputOrder(frames: List<RawFrame>): List<AppliedRawTranslation> {
        val order = frames.mapIndexed { index, frame -> frame.frameIndex to index }.toMap()
        return sortedBy { order[it.frameIndex] ?: Int.MAX_VALUE }
    }

    private data class FrameStackState(
        val frame: RawFrame,
        val accessor: Raw16SampleAccessor,
        val appliedTranslation: AppliedRawTranslation
    )

    private data class ValidatedFrameStackState(
        val frame: RawFrame,
        val appliedTranslation: AppliedRawTranslation
    )

    private data class StackOutput(
        val outputRaw16: ByteBuffer?,
        val outputRaw16FilePath: String?,
        val minimumValidCount: Int,
        val maximumValidCount: Int,
        val meanValidCount: Double,
        val singleContributorPixelCount: Int,
        val fullContributorPixelCount: Int,
        val validCountMap: IntArray?,
        val aggregationDiagnostics: RawStackAggregationDiagnostics,
        val noValidSample: Boolean
    )

    private sealed class PackedOutputTarget {
        data object Allocate : PackedOutputTarget()
        data class CallerBuffer(val buffer: ByteBuffer) : PackedOutputTarget()
        data class FileTarget(val file: File) : PackedOutputTarget()
    }

    private data class CompatibilityFailure(
        val code: AlignedRaw16StackFailureCode,
        val message: String,
        val fatal: Boolean,
        val warnings: List<AlignedRaw16StackWarning>
    )

    private data class MetadataIssue(
        val failure: CompatibilityFailure?,
        val warning: AlignedRaw16StackWarning?
    )

    private data class DarkResolution(
        val masterDark: MasterDark? = null,
        val warnings: List<AlignedRaw16StackWarning> = emptyList(),
        val failureMessage: String? = null
    )

    companion object {
        private const val OUTPUT_STREAM_BUFFER_BYTES = 64 * 1024
    }
}
