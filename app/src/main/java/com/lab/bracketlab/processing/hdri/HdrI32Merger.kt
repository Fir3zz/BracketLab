package com.lab.bracketlab.processing.hdri

import com.lab.bracketlab.processing.align.LandscapeAlignmentReport
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.raw.BayerUtils
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.stack.AppliedRawTranslation
import com.lab.bracketlab.processing.stack.Raw16SampleAccessor
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.pow

class HdrI32Merger(
    private val workingDirectory: File,
    private val testOutputFileFactory: ((File) -> File)? = null
) {
    fun merge(
        rawStack: RawStack,
        options: HdrI32MergeOptions = HdrI32MergeOptions(),
        alignmentReport: LandscapeAlignmentReport? = null
    ): HdrI32MergeResult {
        val startedNs = System.nanoTime()
        validate(rawStack, options)?.let { (code, message) ->
            return failure(code, message)
        }

        val alignedStates =
            resolveAlignment(rawStack, options, alignmentReport)
                ?: return failure(
                    HdrI32FailureCode.INVALID_ALIGNMENT_REPORT,
                    "HDR landscape alignment report is missing, invalid, or has fewer than two accepted frames."
                )
        val frames = alignedStates.map { it.frame }
        val reference =
            alignedStates.firstOrNull { it.isReference }?.frame
                ?: return failure(
                    HdrI32FailureCode.INVALID_ALIGNMENT_REPORT,
                    "HDR alignment has no accepted reference frame."
                )
        val warnings =
            mutableListOf(
                if (options.alignmentMode == HdrI32AlignmentMode.IDENTITY_ONLY) {
                    HdrI32Warning(
                        HdrI32WarningCode.HDRI_ALIGNMENT_IDENTITY_ONLY,
                        "HDR alignment is identity-only."
                    )
                } else {
                    HdrI32Warning(
                        HdrI32WarningCode.HDRI_ALIGNMENT_LANDSCAPE_TRANSLATION,
                        "Landscape translation was applied before HDR radiance merging."
                    )
                }
            )
        if (options.darkCalibrationRequested) {
            warnings += HdrI32Warning(
                HdrI32WarningCode.DARK_CALIBRATION_DISABLED_FOR_HDRI,
                "MasterDark is intentionally ignored for HDR multi-exposure input."
            )
        }
        if (frames.map { it.exposureTimeNs }.distinct().size == 1) {
            warnings += HdrI32Warning(
                HdrI32WarningCode.IDENTICAL_EXPOSURE_SET_ALLOWED,
                "Identical exposure set was allowed for diagnostics."
            )
        }

        workingDirectory.mkdirs()
        val outputFile =
            testOutputFileFactory?.invoke(workingDirectory)
                ?: File(workingDirectory, ".hdri32_${System.nanoTime()}.rawf32.tmp")
        val accessors = mutableListOf<Raw16SampleAccessor>()
        var totalInputSamples = 0L
        var validSamples = 0L
        var saturatedRejected = 0L
        var lowSignalZeroWeightSamples = 0L
        var highlightZeroWeightSamples = 0L
        var totalWeightZeroPixels = 0L
        var sharedHighlightFrameBlocks = 0L
        var blockSaturationZeroWeightSamples = 0L
        var fallbackPixels = 0L
        var noValidPixels = 0L
        var minRadiance = Double.POSITIVE_INFINITY
        var maxRadiance = Double.NEGATIVE_INFINITY
        var radianceSum = 0.0
        val pixelCount = reference.width.toLong() * reference.height.toLong()

        return try {
            frames.mapTo(accessors, Raw16SampleAccessor::create)
            val useSharedHighlight =
                options.weightPolicy == HdrI32WeightPolicy.SNR_WEIGHTED_RADIANCE &&
                    options.highlightCoherencePolicy ==
                    HdrI32HighlightCoherencePolicy.BAYER_2X2_SHARED
            val blockColumnCount = (reference.width + 1) / 2
            val blockRawSamples =
                if (useSharedHighlight) {
                    Array(frames.size) {
                        IntArray(reference.width * BAYER_BLOCK_HEIGHT)
                    }
                } else {
                    emptyArray()
                }
            val blockHighlightWeights =
                if (useSharedHighlight) {
                    Array(frames.size) { DoubleArray(blockColumnCount) { 1.0 } }
                } else {
                    emptyArray()
                }
            val blockHasSaturation =
                if (useSharedHighlight) {
                    Array(frames.size) { BooleanArray(blockColumnCount) }
                } else {
                    emptyArray()
                }
            val rowBytes = ByteArray(reference.width * Float.SIZE_BYTES)
            val rowBuffer = ByteBuffer.wrap(rowBytes).order(ByteOrder.LITTLE_ENDIAN)
            BufferedOutputStream(FileOutputStream(outputFile, false), STREAM_BUFFER_BYTES).use { output ->
                for (tileStartY in 0 until reference.height step options.tileHeight) {
                    val tileEndY = minOf(reference.height, tileStartY + options.tileHeight)
                    for (y in tileStartY until tileEndY) {
                        if (useSharedHighlight && y % BAYER_BLOCK_HEIGHT == 0) {
                            sharedHighlightFrameBlocks +=
                                prepareSharedHighlightRow(
                                    blockStartY = y,
                                    states = alignedStates,
                                    accessors = accessors,
                                    options = options,
                                    rawSamples = blockRawSamples,
                                    highlightWeights = blockHighlightWeights,
                                    blockSaturation = blockHasSaturation
                                )
                        }
                        rowBuffer.clear()
                        for (x in 0 until reference.width) {
                            val black = BayerUtils.blackLevelAt(reference.blackLevelPattern, x, y)
                            val fullScaleSignal =
                                requireNotNull(reference.whiteLevel) - black
                            var radianceTotal = 0.0
                            var validCount = 0
                            var weightedRadianceTotal = 0.0
                            var weightTotal = 0.0
                            var longestValidExposure = Double.NEGATIVE_INFINITY
                            var longestValidRadiance = 0.0
                            var leastRaw = Int.MAX_VALUE
                            var leastIndex = -1
                            for (index in frames.indices) {
                                val frame = frames[index]
                                val raw =
                                    if (useSharedHighlight) {
                                        val localY = y % BAYER_BLOCK_HEIGHT
                                        blockRawSamples[index][localY * reference.width + x]
                                    } else {
                                        sampleAtReferenceCoordinate(
                                            alignedStates[index],
                                            accessors[index],
                                            x,
                                            y,
                                            reference.width,
                                            reference.height
                                        ) ?: INVALID_RAW_SAMPLE
                                    }
                                totalInputSamples++
                                if (raw == INVALID_RAW_SAMPLE) continue
                                if (raw < leastRaw) {
                                    leastRaw = raw
                                    leastIndex = index
                                }
                                if (isSaturated(raw, requireNotNull(frame.whiteLevel), options.saturationMarginDn)) {
                                    saturatedRejected++
                                    continue
                                }
                                val signal = (raw - black).coerceAtLeast(0)
                                val radiance = signal.toDouble() / frame.exposureTimeSeconds
                                if (radiance.isFinite() && radiance >= 0.0) {
                                    validSamples++
                                    val sharedBlockSaturated =
                                        useSharedHighlight &&
                                            blockHasSaturation[index][x / BAYER_BLOCK_WIDTH]
                                    if (
                                        !sharedBlockSaturated &&
                                        frame.exposureTimeSeconds > longestValidExposure
                                    ) {
                                        longestValidExposure = frame.exposureTimeSeconds
                                        longestValidRadiance = radiance
                                    }
                                    when (options.weightPolicy) {
                                        HdrI32WeightPolicy.UNIFORM_VALID_RADIANCE -> {
                                            radianceTotal += radiance
                                            validCount++
                                        }
                                        HdrI32WeightPolicy.SNR_WEIGHTED_RADIANCE -> {
                                            val signalNorm =
                                                signal.toDouble() / fullScaleSignal.toDouble()
                                            val signalWeight =
                                                risingWeight(
                                                    signalNorm,
                                                    options.blackWeightZeroThreshold,
                                                    options.blackWeightFullThreshold
                                                )
                                            val highlightWeight =
                                                if (useSharedHighlight) {
                                                    blockHighlightWeights[index][
                                                        x / BAYER_BLOCK_WIDTH
                                                    ]
                                                } else {
                                                    fallingWeight(
                                                        signalNorm,
                                                        options.highlightWeightFullThreshold,
                                                        options.highlightWeightZeroThreshold
                                                    )
                                                }
                                            if (signalWeight == 0.0) {
                                                lowSignalZeroWeightSamples++
                                            }
                                            if (highlightWeight == 0.0) {
                                                highlightZeroWeightSamples++
                                                if (sharedBlockSaturated) {
                                                    blockSaturationZeroWeightSamples++
                                                }
                                            }
                                            val exposureWeight =
                                                frame.exposureTimeSeconds.pow(
                                                    options.exposureWeightPower
                                                )
                                            val weight =
                                                signalWeight * highlightWeight * exposureWeight
                                            if (weight.isFinite() && weight > 0.0) {
                                                weightedRadianceTotal += radiance * weight
                                                weightTotal += weight
                                            }
                                        }
                                    }
                                }
                            }

                            val outputRadiance =
                                when {
                                    options.weightPolicy ==
                                        HdrI32WeightPolicy.UNIFORM_VALID_RADIANCE &&
                                        validCount > 0 ->
                                        radianceTotal / validCount.toDouble()
                                    options.weightPolicy ==
                                        HdrI32WeightPolicy.SNR_WEIGHTED_RADIANCE &&
                                        weightTotal > 0.0 ->
                                        weightedRadianceTotal / weightTotal
                                    longestValidExposure.isFinite() -> {
                                        totalWeightZeroPixels++
                                        fallbackPixels++
                                        longestValidRadiance
                                    }
                                    else -> {
                                        if (
                                            options.weightPolicy ==
                                            HdrI32WeightPolicy.SNR_WEIGHTED_RADIANCE
                                        ) {
                                            totalWeightZeroPixels++
                                        }
                                        noValidPixels++
                                        when (options.invalidSamplePolicy) {
                                            HdrI32InvalidSamplePolicy.FAIL ->
                                                throw NoValidHdrSampleException(x, y)
                                            HdrI32InvalidSamplePolicy
                                                .LEAST_SATURATED_FALLBACK -> {
                                                check(leastIndex >= 0)
                                                val frame = frames[leastIndex]
                                                val signal =
                                                    (leastRaw - black).coerceAtLeast(0)
                                                fallbackPixels++
                                                signal.toDouble() /
                                                    frame.exposureTimeSeconds
                                            }
                                        }
                                    }
                                }
                            val safeRadiance =
                                outputRadiance
                                    .takeIf { it.isFinite() && it >= 0.0 }
                                    ?: throw IllegalStateException("Non-finite HDR radiance at $x,$y.")
                            val floatValue = safeRadiance.toFloat()
                            if (!floatValue.isFinite() || floatValue < 0f) {
                                throw IllegalStateException("Float32 radiance overflow at $x,$y.")
                            }
                            rowBuffer.putFloat(floatValue)
                            minRadiance = minOf(minRadiance, safeRadiance)
                            maxRadiance = maxOf(maxRadiance, safeRadiance)
                            radianceSum += safeRadiance
                        }
                        output.write(rowBytes)
                    }
                }
                output.flush()
            }

            val expectedBytes = pixelCount * Float.SIZE_BYTES.toLong()
            if (outputFile.length() != expectedBytes) {
                val cleaned = deleteTemporary(outputFile)
                return HdrI32MergeResult(
                    success = false,
                    warnings = warnings,
                    failureCode = HdrI32FailureCode.HDRI_TEMP_FILE_FAILURE,
                    failureMessage =
                        "Float32 output size=${outputFile.length()}, expected=$expectedBytes.",
                    temporaryCleanupSucceeded = cleaned
                )
            }
            val diagnostics = HdrI32MergeDiagnostics(
                totalInputSamples = totalInputSamples,
                validSamples = validSamples,
                saturatedRejectedSamples = saturatedRejected,
                fallbackPixels = fallbackPixels,
                noValidSamplePixels = noValidPixels,
                minimumRadiance = minRadiance.takeIf(Double::isFinite),
                maximumRadiance = maxRadiance.takeIf(Double::isFinite),
                meanRadiance =
                    if (pixelCount > 0L) radianceSum / pixelCount.toDouble() else null,
                tileHeight = options.tileHeight,
                processingDurationMs = elapsedMs(startedNs),
                lowSignalZeroWeightSamples = lowSignalZeroWeightSamples,
                highlightZeroWeightSamples = highlightZeroWeightSamples,
                totalWeightZeroPixels = totalWeightZeroPixels,
                sharedHighlightFrameBlocks = sharedHighlightFrameBlocks,
                blockSaturationZeroWeightSamples = blockSaturationZeroWeightSamples
            )
            if (fallbackPixels > 0L) {
                warnings += HdrI32Warning(
                    HdrI32WarningCode.SATURATED_SAMPLE_FALLBACK_USED,
                    "$fallbackPixels pixels used the least-saturated fallback."
                )
            }
            val metadata = HdrI32Metadata(
                width = reference.width,
                height = reference.height,
                rowStrideBytes = reference.width * Float.SIZE_BYTES,
                pixelStrideBytes = Float.SIZE_BYTES,
                cfaPattern = requireNotNull(reference.cfaPattern),
                cameraId = reference.cameraId,
                iso = reference.iso,
                inputExposureTimesNs = frames.map { it.exposureTimeNs },
                referenceFrameIndex = reference.frameIndex,
                referenceExposureTimeNs = reference.exposureTimeNs,
                frameCount = frames.size,
                whiteLevel = requireNotNull(reference.whiteLevel),
                blackLevelPattern = requireNotNull(reference.blackLevelPattern).copyOf(),
                saturationMarginDn = options.saturationMarginDn,
                weightPolicy = options.weightPolicy,
                invalidSamplePolicy = options.invalidSamplePolicy,
                alignmentMode = options.alignmentMode,
                storagePath = outputFile.absolutePath,
                createdAtMillis = System.currentTimeMillis(),
                appVersion = options.appVersion,
                sourceFrames = frames.map {
                    HdrI32SourceFrameMetadata(
                        it.frameIndex,
                        it.timestampNs,
                        it.exposureTimeNs,
                        it.iso
                    )
                },
                totalInputSamples = diagnostics.totalInputSamples,
                validSamples = diagnostics.validSamples,
                saturatedRejectedSamples = diagnostics.saturatedRejectedSamples,
                fallbackPixels = diagnostics.fallbackPixels,
                noValidSamplePixels = diagnostics.noValidSamplePixels,
                minimumRadiance = diagnostics.minimumRadiance,
                maximumRadiance = diagnostics.maximumRadiance,
                meanRadiance = diagnostics.meanRadiance,
                blackWeightZeroThreshold = options.blackWeightZeroThreshold,
                blackWeightFullThreshold = options.blackWeightFullThreshold,
                highlightWeightFullThreshold = options.highlightWeightFullThreshold,
                highlightWeightZeroThreshold = options.highlightWeightZeroThreshold,
                exposureWeightPower = options.exposureWeightPower,
                highlightCoherencePolicy = options.highlightCoherencePolicy,
                lowSignalZeroWeightSamples = diagnostics.lowSignalZeroWeightSamples,
                highlightZeroWeightSamples = diagnostics.highlightZeroWeightSamples,
                totalWeightZeroPixels = diagnostics.totalWeightZeroPixels,
                sharedHighlightFrameBlocks = diagnostics.sharedHighlightFrameBlocks,
                blockSaturationZeroWeightSamples =
                    diagnostics.blockSaturationZeroWeightSamples
            )
            HdrI32MergeResult(
                success = true,
                frame = HdrI32Frame(metadata, outputFile),
                diagnostics = diagnostics,
                warnings = warnings
            )
        } catch (error: NoValidHdrSampleException) {
            val cleaned = deleteTemporary(outputFile)
            HdrI32MergeResult(
                success = false,
                warnings = warnings,
                failureCode = HdrI32FailureCode.NO_VALID_HDR_SAMPLE,
                failureMessage = error.message,
                exceptionClass = error.javaClass.simpleName,
                exceptionMessage = error.message,
                temporaryCleanupSucceeded = cleaned
            )
        } catch (error: Throwable) {
            val cleaned = deleteTemporary(outputFile)
            HdrI32MergeResult(
                success = false,
                warnings = warnings,
                failureCode = HdrI32FailureCode.HDRI_PROCESSING_FAILED,
                failureMessage = error.message ?: "HDR processing failed.",
                exceptionClass = error.javaClass.simpleName,
                exceptionMessage = error.message,
                temporaryCleanupSucceeded = cleaned
            )
        } finally {
            accessors.forEach { runCatching { it.close() } }
        }
    }

    internal fun validate(
        rawStack: RawStack,
        options: HdrI32MergeOptions
    ): Pair<HdrI32FailureCode, String>? {
        if (rawStack.isEmpty) {
            return HdrI32FailureCode.EMPTY_HDR_STACK to "HDR stack is empty."
        }
        if (rawStack.frameCount < 2) {
            return HdrI32FailureCode.INSUFFICIENT_HDR_FRAMES to
                "HDR requires at least two frames."
        }
        val reference = rawStack.frames.first()
        if (reference.exposureTimeNs <= 0L) {
            return HdrI32FailureCode.INVALID_EXPOSURE_TIME to
                "Reference exposure time is invalid."
        }
        if (
            reference.cfaPattern == null ||
            reference.cfaPattern == CfaPattern.UNKNOWN
        ) {
            return HdrI32FailureCode.INCOMPATIBLE_CFA to "Reference CFA is unavailable."
        }
        if (reference.blackLevelPattern == null || reference.blackLevelPattern.size < 4) {
            return HdrI32FailureCode.INCOMPATIBLE_BLACK_LEVEL to
                "Reference black-level pattern is unavailable."
        }
        if (reference.whiteLevel == null || reference.whiteLevel <= 0) {
            return HdrI32FailureCode.INCOMPATIBLE_WHITE_LEVEL to
                "Reference white level is unavailable."
        }
        if (reference.blackLevelPattern.any { it >= reference.whiteLevel }) {
            return HdrI32FailureCode.INCOMPATIBLE_BLACK_LEVEL to
                "Reference black level must be below white level."
        }
        for (frame in rawStack.frames) {
            val bufferValidation = Raw16SampleAccessor.validate(frame)
            if (!bufferValidation.valid) {
                return HdrI32FailureCode.INVALID_RAW_STORAGE to
                    "Frame ${frame.frameIndex}: ${bufferValidation.message}"
            }
            if (frame.exposureTimeNs <= 0L) {
                return HdrI32FailureCode.INVALID_EXPOSURE_TIME to
                    "Frame ${frame.frameIndex} exposure time is invalid."
            }
            if (frame.width != reference.width || frame.height != reference.height) {
                return HdrI32FailureCode.INCOMPATIBLE_DIMENSIONS to
                    "Frame ${frame.frameIndex} dimensions differ."
            }
            if (frame.cfaPattern != reference.cfaPattern) {
                return HdrI32FailureCode.INCOMPATIBLE_CFA to
                    "Frame ${frame.frameIndex} CFA differs."
            }
            if (
                reference.cameraId != null &&
                frame.cameraId != null &&
                reference.cameraId != frame.cameraId
            ) {
                return HdrI32FailureCode.INCOMPATIBLE_CAMERA to
                    "Frame ${frame.frameIndex} camera ID differs."
            }
            if (frame.iso != reference.iso) {
                return HdrI32FailureCode.INCOMPATIBLE_ISO to
                    "Frame ${frame.frameIndex} ISO differs."
            }
            if (!frame.blackLevelPattern.contentEquals(reference.blackLevelPattern)) {
                return HdrI32FailureCode.INCOMPATIBLE_BLACK_LEVEL to
                    "Frame ${frame.frameIndex} black-level pattern differs."
            }
            if (frame.whiteLevel != reference.whiteLevel) {
                return HdrI32FailureCode.INCOMPATIBLE_WHITE_LEVEL to
                    "Frame ${frame.frameIndex} white level differs."
            }
        }
        if (
            !options.allowIdenticalExposureSetForDiagnostics &&
            rawStack.frames.map { it.exposureTimeNs }.distinct().size == 1
        ) {
            return HdrI32FailureCode.IDENTICAL_EXPOSURE_SET to
                "HDR exposure set contains only one exposure time."
        }
        return null
    }

    private fun resolveAlignment(
        rawStack: RawStack,
        options: HdrI32MergeOptions,
        report: LandscapeAlignmentReport?
    ): List<HdrAlignedFrameState>? {
        if (options.alignmentMode == HdrI32AlignmentMode.IDENTITY_ONLY) {
            val reference =
                rawStack.frames.sortedBy { it.exposureTimeNs }[rawStack.frameCount / 2]
            return rawStack.frames.map {
                HdrAlignedFrameState(
                    frame = it,
                    isReference = it.frameIndex == reference.frameIndex,
                    appliedDx = 0,
                    appliedDy = 0
                )
            }
        }
        if (
            report == null ||
            (!report.success && !report.partialSuccess) ||
            report.totalFrameCount != rawStack.frameCount
        ) {
            return null
        }
        val referenceIndex = report.selectedReferenceFrameIndex ?: return null
        val byIndex = report.frameResults.associateBy { it.frameIndex }
        val states =
            rawStack.frames.mapNotNull { frame ->
                val aligned = byIndex[frame.frameIndex] ?: return null
                if (!aligned.accepted) return@mapNotNull null
                val transform = aligned.alignmentResult.transform
                if (
                    !transform.dx.isFinite() ||
                    !transform.dy.isFinite() ||
                    !transform.rotationDegrees.isFinite() ||
                    !transform.scale.isFinite() ||
                    transform.rotationDegrees != 0.0 ||
                    transform.scale != 1.0
                ) {
                    return null
                }
                val applied =
                    AppliedRawTranslation.fromEstimated(
                        frameIndex = frame.frameIndex,
                        isReference = frame.frameIndex == referenceIndex,
                        estimatedDxRaw =
                            if (frame.frameIndex == referenceIndex) 0.0 else transform.dx,
                        estimatedDyRaw =
                            if (frame.frameIndex == referenceIndex) 0.0 else transform.dy,
                        accepted = true,
                        rejectionReason = null,
                        warnings = emptyList()
                    )
                HdrAlignedFrameState(
                    frame = frame,
                    isReference = frame.frameIndex == referenceIndex,
                    appliedDx = applied.appliedDxRaw,
                    appliedDy = applied.appliedDyRaw
                )
            }
        return states.takeIf {
            it.size >= 2 && it.count(HdrAlignedFrameState::isReference) == 1
        }
    }

    private fun sampleAtReferenceCoordinate(
        state: HdrAlignedFrameState,
        accessor: Raw16SampleAccessor,
        outputX: Int,
        outputY: Int,
        width: Int,
        height: Int
    ): Int? {
        val sourceX = outputX - state.appliedDx
        val sourceY = outputY - state.appliedDy
        if (sourceX !in 0 until width || sourceY !in 0 until height) return null
        return accessor.sampleAt(sourceX, sourceY)
    }

    private fun isSaturated(raw: Int, whiteLevel: Int, margin: Int): Boolean =
        raw >= (whiteLevel - margin).coerceAtLeast(0)

    private fun prepareSharedHighlightRow(
        blockStartY: Int,
        states: List<HdrAlignedFrameState>,
        accessors: List<Raw16SampleAccessor>,
        options: HdrI32MergeOptions,
        rawSamples: Array<IntArray>,
        highlightWeights: Array<DoubleArray>,
        blockSaturation: Array<BooleanArray>
    ): Long {
        val reference = states.first(HdrAlignedFrameState::isReference).frame
        var coherentFrameBlocks = 0L
        for (frameIndex in states.indices) {
            val state = states[frameIndex]
            val frame = state.frame
            val samples = rawSamples[frameIndex]
            samples.fill(INVALID_RAW_SAMPLE)
            for (localY in 0 until BAYER_BLOCK_HEIGHT) {
                val outputY = blockStartY + localY
                if (outputY >= reference.height) continue
                val rowOffset = localY * frame.width
                for (outputX in 0 until reference.width) {
                    samples[rowOffset + outputX] =
                        sampleAtReferenceCoordinate(
                            state,
                            accessors[frameIndex],
                            outputX,
                            outputY,
                            reference.width,
                            reference.height
                        ) ?: INVALID_RAW_SAMPLE
                }
            }

            for (blockX in highlightWeights[frameIndex].indices) {
                val startX = blockX * BAYER_BLOCK_WIDTH
                var maximumSignalNorm = 0.0
                var hasSaturation = false
                for (localY in 0 until BAYER_BLOCK_HEIGHT) {
                    val outputY = blockStartY + localY
                    if (outputY >= reference.height) continue
                    val rowOffset = localY * frame.width
                    for (localX in 0 until BAYER_BLOCK_WIDTH) {
                        val outputX = startX + localX
                        if (outputX >= reference.width) continue
                        val raw = samples[rowOffset + outputX]
                        if (raw == INVALID_RAW_SAMPLE) continue
                        if (
                            isSaturated(
                                raw,
                                requireNotNull(frame.whiteLevel),
                                options.saturationMarginDn
                            )
                        ) {
                            hasSaturation = true
                        }
                        val black =
                            BayerUtils.blackLevelAt(
                                requireNotNull(reference.blackLevelPattern),
                                outputX,
                                outputY
                            )
                        val fullScale = requireNotNull(reference.whiteLevel) - black
                        val signal = (raw - black).coerceAtLeast(0)
                        maximumSignalNorm =
                            maxOf(
                                maximumSignalNorm,
                                signal.toDouble() / fullScale.toDouble()
                            )
                    }
                }
                blockSaturation[frameIndex][blockX] = hasSaturation
                highlightWeights[frameIndex][blockX] =
                    if (hasSaturation) {
                        0.0
                    } else {
                        fallingWeight(
                            maximumSignalNorm,
                            options.highlightWeightFullThreshold,
                            options.highlightWeightZeroThreshold
                        )
                    }
                if (
                    hasSaturation ||
                    maximumSignalNorm >= options.highlightWeightFullThreshold
                ) {
                    coherentFrameBlocks++
                }
            }
        }
        return coherentFrameBlocks
    }

    private fun risingWeight(value: Double, zeroAt: Double, fullAt: Double): Double =
        when {
            value <= zeroAt -> 0.0
            value >= fullAt -> 1.0
            else -> smoothStep((value - zeroAt) / (fullAt - zeroAt))
        }

    private fun fallingWeight(value: Double, fullAt: Double, zeroAt: Double): Double =
        when {
            value <= fullAt -> 1.0
            value >= zeroAt -> 0.0
            else -> 1.0 - smoothStep((value - fullAt) / (zeroAt - fullAt))
        }

    private fun smoothStep(value: Double): Double {
        val clamped = value.coerceIn(0.0, 1.0)
        return clamped * clamped * (3.0 - 2.0 * clamped)
    }

    private fun deleteTemporary(file: File): Boolean =
        when {
            !file.exists() -> true
            file.isDirectory -> file.deleteRecursively()
            else -> file.delete()
        }

    private fun failure(code: HdrI32FailureCode, message: String): HdrI32MergeResult =
        HdrI32MergeResult(success = false, failureCode = code, failureMessage = message)

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L

    private class NoValidHdrSampleException(x: Int, y: Int) :
        IllegalStateException("No valid HDR sample at $x,$y.")

    private data class HdrAlignedFrameState(
        val frame: RawFrame,
        val isReference: Boolean,
        val appliedDx: Int,
        val appliedDy: Int
    )

    companion object {
        private const val INVALID_RAW_SAMPLE = -1
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val BAYER_BLOCK_WIDTH = 2
        private const val BAYER_BLOCK_HEIGHT = 2
    }
}
