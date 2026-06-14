package com.lab.bracketlab.processing.align.star.warp

import com.lab.bracketlab.processing.align.star.StarAlignmentReport
import com.lab.bracketlab.processing.align.star.StarFrameAlignment
import com.lab.bracketlab.processing.calibration.DarkPolicy
import com.lab.bracketlab.processing.calibration.DarkSubtractor
import com.lab.bracketlab.processing.calibration.MasterDark
import com.lab.bracketlab.processing.calibration.MasterDarkMatcher
import com.lab.bracketlab.processing.calibration.MissingMasterDarkBehavior
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.stack.Raw16SampleAccessor
import com.lab.bracketlab.processing.stack.RawStackAggregationDiagnostics
import com.lab.bracketlab.processing.stack.RawStackAggregationMode
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * File-backed CPU star warp. Every interpolation operates in one virtual
 * half-resolution CFA phase plane; the full Bayer mosaic is never interpolated.
 */
class StarAlignedRaw16StackProcessor {
    fun processToPackedFile(
        rawStack: RawStack,
        alignmentReport: StarAlignmentReport,
        options: StarAlignedRaw16StackOptions,
        outputFile: File
    ): StarAlignedRaw16StackResult {
        val startedNs = System.nanoTime()
        val warnings = mutableListOf<StarAlignedRaw16Warning>()
        if (rawStack.frames.isEmpty()) {
            return failure(rawStack, options, startedNs, StarAlignedRaw16FailureCode.EMPTY_STAR_STACK, "RawStack is empty.")
        }
        val frameMap = mutableMapOf<Int, RawFrame>()
        for (frame in rawStack.frames) {
            if (frameMap.put(frame.frameIndex, frame) != null) {
                return failure(
                    rawStack, options, startedNs,
                    StarAlignedRaw16FailureCode.INVALID_STAR_ALIGNMENT_REPORT,
                    "Duplicate RawFrame frameIndex ${frame.frameIndex}."
                )
            }
        }
        val referenceIndex =
            alignmentReport.referenceFrameIndex
                ?: return failure(
                    rawStack, options, startedNs,
                    StarAlignedRaw16FailureCode.REFERENCE_FRAME_MISMATCH,
                    "Star alignment report has no reference frame."
                )
        val reference =
            frameMap[referenceIndex]
                ?: return failure(
                    rawStack, options, startedNs,
                    StarAlignedRaw16FailureCode.REFERENCE_FRAME_MISMATCH,
                    "Reference frame $referenceIndex is missing from RawStack."
                )
        val layout =
            CfaPhaseLayout.from(reference.cfaPattern)
                ?: return failure(
                    rawStack, options, startedNs,
                    StarAlignedRaw16FailureCode.CFA_MAPPING_FAILED,
                    "Unsupported or missing Bayer CFA pattern ${reference.cfaPattern}."
                )

        val alignments = mutableMapOf<Int, StarFrameAlignment>()
        for (result in alignmentReport.frameResults) {
            if (alignments.put(result.frameIndex, result) != null) {
                return failure(
                    rawStack, options, startedNs,
                    StarAlignedRaw16FailureCode.DUPLICATE_FRAME_TRANSFORM,
                    "Duplicate star transform for frame ${result.frameIndex}."
                )
            }
        }
        for (frame in rawStack.frames) {
            if (!alignments.containsKey(frame.frameIndex)) {
                return failure(
                    rawStack, options, startedNs,
                    StarAlignedRaw16FailureCode.MISSING_FRAME_TRANSFORM,
                    "Missing star transform for frame ${frame.frameIndex}."
                )
            }
        }
        if (
            alignmentReport.totalFrameCount != rawStack.frameCount ||
            alignmentReport.frameResults.size != rawStack.frameCount
        ) {
            return failure(
                rawStack, options, startedNs,
                StarAlignedRaw16FailureCode.INVALID_STAR_ALIGNMENT_REPORT,
                "Star alignment frame count does not match RawStack."
            )
        }
        val referenceAlignment = alignments[referenceIndex]
        if (
            referenceAlignment == null ||
            !referenceAlignment.accepted ||
            !referenceAlignment.isReference ||
            alignments.values.count(StarFrameAlignment::isReference) != 1 ||
            !referenceAlignment.transform.isIdentity()
        ) {
            return failure(
                rawStack, options, startedNs,
                StarAlignedRaw16FailureCode.REFERENCE_FRAME_MISMATCH,
                "The selected star reference must be the sole accepted identity transform."
            )
        }

        val accepted = mutableListOf<FrameState>()
        for (frame in rawStack.frames) {
            val alignment = requireNotNull(alignments[frame.frameIndex])
            if (!alignment.accepted) {
                warnings += warning(
                    StarAlignedRaw16WarningCode.FRAME_REJECTED_BY_STAR_ALIGNMENT,
                    frame.frameIndex,
                    "Frame rejected by star matching/RANSAC."
                )
                continue
            }
            compatibilityFailure(reference, frame, options)?.let {
                return failure(rawStack, options, startedNs, it.first, "Frame ${frame.frameIndex}: ${it.second}", warnings)
            }
            val validation = Raw16SampleAccessor.validate(frame)
            if (!validation.valid) {
                return failure(
                    rawStack, options, startedNs,
                    StarAlignedRaw16FailureCode.INVALID_RAW_STORAGE,
                    "Frame ${frame.frameIndex}: ${validation.message}",
                    warnings
                )
            }
            if (!alignment.transform.isFinite()) {
                return failure(
                    rawStack, options, startedNs,
                    StarAlignedRaw16FailureCode.NON_FINITE_TRANSFORM,
                    "Frame ${frame.frameIndex} has a non-finite star transform.",
                    warnings
                )
            }
            val inverse =
                SimilarityTransformInverse.fromTargetToReference(alignment.transform)
                    ?: return failure(
                        rawStack, options, startedNs,
                        StarAlignedRaw16FailureCode.NON_INVERTIBLE_TRANSFORM,
                        "Frame ${frame.frameIndex} has a non-invertible transform.",
                        warnings
                    )
            val estimatedFraction = estimateValidFraction(reference.width, reference.height, inverse)
            if (frame.frameIndex != referenceIndex && estimatedFraction < options.minimumFrameValidFraction) {
                warnings += warning(
                    StarAlignedRaw16WarningCode.LOW_VALID_SOURCE_FRACTION,
                    frame.frameIndex,
                    "Estimated valid source fraction $estimatedFraction is below ${options.minimumFrameValidFraction}; frame excluded."
                )
                continue
            }
            accepted += FrameState(frame, alignment, inverse, estimatedFraction)
        }
        if (accepted.none { it.frame.frameIndex == referenceIndex }) {
            return failure(
                rawStack, options, startedNs,
                StarAlignedRaw16FailureCode.REFERENCE_FRAME_MISMATCH,
                "Accepted reference identity frame is missing.",
                warnings
            )
        }
        if (accepted.size < options.minimumAcceptedFrames) {
            return failure(
                rawStack, options, startedNs,
                StarAlignedRaw16FailureCode.INSUFFICIENT_ACCEPTED_FRAMES,
                "Only ${accepted.size} accepted frames remain; required ${options.minimumAcceptedFrames}.",
                warnings
            )
        }

        val dark = resolveDark(reference, options)
        if (dark.failure != null) {
            return failure(
                rawStack, options, startedNs,
                StarAlignedRaw16FailureCode.INVALID_RAW_STORAGE,
                dark.failure,
                warnings
            )
        }
        warnings += dark.warnings
        val plan =
            try {
                AdaptiveStarTilePlanner.plan(
                    reference.width,
                    reference.height,
                    accepted.size,
                    options.preferredTileWidth,
                    options.preferredTileHeight,
                    options.tileWorkingMemoryBudgetBytes
                )
            } catch (error: Throwable) {
                return failure(
                    rawStack, options, startedNs,
                    StarAlignedRaw16FailureCode.WARP_PROCESSING_FAILED,
                    error.message ?: "Tile planning failed.",
                    warnings
                )
            }
        if (plan.reducedFromPreferred) {
            warnings += warning(
                StarAlignedRaw16WarningCode.TILE_SIZE_REDUCED_FOR_MEMORY_BUDGET,
                null,
                "Tile reduced to ${plan.tileWidth}x${plan.tileHeight} for ${plan.estimatedWorkingBytes} bytes."
            )
        }

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists() && !outputFile.delete()) {
            return failure(
                rawStack, options, startedNs,
                StarAlignedRaw16FailureCode.OUTPUT_TEMP_FILE_FAILED,
                "Could not replace temporary output ${outputFile.absolutePath}.",
                warnings
            )
        }

        val frameDiagnostics = accepted.associate {
            it.frame.frameIndex to MutableFrameDiagnostics(it.frame.frameIndex, it.frame.frameIndex == referenceIndex)
        }
        var minValid = Int.MAX_VALUE
        var maxValid = 0
        var validTotal = 0L
        var referenceOnly = 0L
        var allFrames = 0L
        var totalSamples = 0L
        var totalAcceptedSamples = 0L
        var totalRejectedSamples = 0L
        var pixelsRequestedMode = 0L
        var fallbackMean = 0L
        var fallbackOther = 0L
        var maxRejected = 0
        var zeroVariance = 0L
        var insufficientSamples = 0L
        var processedPixels = 0L
        var cleanupSucceeded = true
        val accessors = mutableListOf<Raw16SampleAccessor>()
        var darkAccessor: Raw16SampleAccessor? = null
        try {
            accepted.forEach { state ->
                state.accessor = Raw16SampleAccessor.create(state.frame).also(accessors::add)
            }
            darkAccessor =
                dark.masterDark?.toRawFrame(Int.MIN_VALUE)?.let {
                    Raw16SampleAccessor.create(it)
                }
            RandomAccessFile(outputFile, "rw").use { output ->
                output.setLength(reference.width.toLong() * reference.height.toLong() * 2L)
                val sampler =
                    CfaSafeSimilaritySampler(layout, options.integralCoordinateEpsilon)
                val aggregator = WarpedSampleAggregator(accepted.size)
                for (tile in AdaptiveStarTilePlanner.tiles(reference.width, reference.height, plan)) {
                    val cube = FloatArray(tile.pixelCount * accepted.size) { Float.NaN }
                    accepted.forEachIndexed { frameSlot, state ->
                        val accessor = requireNotNull(state.accessor)
                        val window =
                            SourceTileReader(state.frame, accessor).readWindow(tile, state.inverse)
                                ?: return failureAfterOutput(
                                    rawStack, options, startedNs,
                                    StarAlignedRaw16FailureCode.SOURCE_WINDOW_READ_FAILED,
                                    "Could not read source window for frame ${state.frame.frameIndex}.",
                                    warnings, outputFile
                                )
                        val diagnostics = requireNotNull(frameDiagnostics[state.frame.frameIndex])
                        for (localY in 0 until tile.height) {
                            val outputY = tile.top + localY
                            for (localX in 0 until tile.width) {
                                val outputX = tile.left + localX
                                val pixelIndex = localY * tile.width + localX
                                val warped =
                                    sampler.sample(
                                        outputX,
                                        outputY,
                                        state.inverse,
                                        window,
                                        state.frame.width,
                                        state.frame.height
                                    ) { sourceX, sourceY ->
                                        val light = window.sampleAt(sourceX, sourceY)
                                        if (darkAccessor == null || dark.masterDark == null) {
                                            light
                                        } else {
                                            DarkSubtractor.subtractDarkRaw16At(
                                                light,
                                                requireNotNull(darkAccessor).sampleAt(sourceX, sourceY),
                                                state.frame.blackLevelPattern,
                                                sourceX,
                                                sourceY
                                            )
                                        }
                                    }
                                if (warped.valid && warped.value.isFinite()) {
                                    cube[pixelIndex * accepted.size + frameSlot] = warped.value.toFloat()
                                    diagnostics.valid++
                                    when (warped.path) {
                                        CfaSamplingPath.DIRECT -> diagnostics.direct++
                                        CfaSamplingPath.HORIZONTAL_LINEAR -> diagnostics.horizontal++
                                        CfaSamplingPath.VERTICAL_LINEAR -> diagnostics.vertical++
                                        CfaSamplingPath.BILINEAR -> diagnostics.bilinear++
                                        CfaSamplingPath.INVALID -> Unit
                                    }
                                } else {
                                    diagnostics.invalid++
                                }
                            }
                        }
                    }

                    val packedRow = ByteArray(tile.width * 2)
                    val compact = FloatArray(accepted.size)
                    for (localY in 0 until tile.height) {
                        val outputY = tile.top + localY
                        for (localX in 0 until tile.width) {
                            val pixelIndex = localY * tile.width + localX
                            val base = pixelIndex * accepted.size
                            var count = 0
                            for (slot in accepted.indices) {
                                val value = cube[base + slot]
                                if (value.isFinite()) compact[count++] = value
                            }
                            if (count == 0) {
                                return failureAfterOutput(
                                    rawStack, options, startedNs,
                                    StarAlignedRaw16FailureCode.WARP_PROCESSING_FAILED,
                                    "Output pixel ${tile.left + localX},$outputY has no valid contributor.",
                                    warnings, outputFile
                                )
                            }
                            val result =
                                aggregator.aggregate(compact, 0, count, options.aggregationOptions)
                            val byteOffset = localX * 2
                            packedRow[byteOffset] = (result.value and 0xFF).toByte()
                            packedRow[byteOffset + 1] = ((result.value ushr 8) and 0xFF).toByte()
                            minValid = min(minValid, count)
                            maxValid = max(maxValid, count)
                            validTotal += count
                            if (count == 1) referenceOnly++
                            if (count == accepted.size) allFrames++
                            processedPixels++
                            totalSamples += count
                            totalAcceptedSamples += result.acceptedCount
                            totalRejectedSamples += result.rejectedCount
                            maxRejected = max(maxRejected, result.rejectedCount)
                            if (result.appliedMode == options.aggregationOptions.mode && result.fallbackReason == null) {
                                pixelsRequestedMode++
                            } else if (result.appliedMode == RawStackAggregationMode.MEAN) {
                                fallbackMean++
                            } else {
                                fallbackOther++
                            }
                            if (result.zeroVariance) zeroVariance++
                            if (result.fallbackReason != null) insufficientSamples++
                        }
                        val fileOffset =
                            ((outputY.toLong() * reference.width.toLong()) + tile.left.toLong()) * 2L
                        output.seek(fileOffset)
                        output.write(packedRow)
                    }
                }
            }
        } catch (error: Throwable) {
            cleanupSucceeded = !outputFile.exists() || outputFile.delete()
            return failure(
                rawStack, options, startedNs,
                StarAlignedRaw16FailureCode.WARP_PROCESSING_FAILED,
                "${error.javaClass.simpleName}: ${error.message}",
                warnings,
                outputFilePath = null,
                cleanupSucceeded = cleanupSucceeded
            )
        } finally {
            accessors.forEach { runCatching { it.close() } }
            runCatching { darkAccessor?.close() }
        }

        if (referenceOnly > 0L) {
            warnings += warning(
                StarAlignedRaw16WarningCode.REFERENCE_ONLY_BORDER_PIXELS,
                null,
                "$referenceOnly pixels use only the identity reference frame."
            )
        }
        if (allFrames < processedPixels) {
            warnings += warning(
                StarAlignedRaw16WarningCode.REDUCED_ROTATED_BORDER_COVERAGE,
                null,
                "Rotated/scaled borders have reduced contributor counts."
            )
        }
        if (fallbackMean + fallbackOther > 0L) {
            warnings += warning(
                StarAlignedRaw16WarningCode.ROBUST_AGGREGATION_FALLBACK,
                null,
                "${fallbackMean + fallbackOther} pixels used an aggregation fallback."
            )
        }
        val aggregationDiagnostics =
            RawStackAggregationDiagnostics(
                requestedMode = options.aggregationOptions.mode,
                pixelsProcessed = processedPixels,
                totalInputSamples = totalSamples,
                totalAcceptedSamples = totalAcceptedSamples,
                totalRejectedSamples = totalRejectedSamples,
                pixelsUsingRequestedMode = pixelsRequestedMode,
                pixelsFallingBackToMean = fallbackMean,
                pixelsFallingBackToOtherMode = fallbackOther,
                maximumRejectedSamplesAtOnePixel = maxRejected,
                meanRejectedSamplesPerPixel =
                    if (processedPixels == 0L) 0.0
                    else totalRejectedSamples.toDouble() / processedPixels.toDouble(),
                pixelsWithZeroVariance = zeroVariance,
                pixelsWithInsufficientSamples = insufficientSamples,
                rejectionCountMap = null
            )
        return StarAlignedRaw16StackResult(
            success = true,
            width = reference.width,
            height = reference.height,
            outputRaw16FilePath = outputFile.absolutePath,
            referenceFrameIndex = referenceIndex,
            inputFrameCount = rawStack.frameCount,
            acceptedFrameCount = accepted.size,
            rejectedFrameCount = rawStack.frameCount - accepted.size,
            minimumValidCount = if (minValid == Int.MAX_VALUE) 0 else minValid,
            maximumValidCount = maxValid,
            meanValidCount =
                if (processedPixels == 0L) 0.0 else validTotal.toDouble() / processedPixels.toDouble(),
            referenceOnlyPixelCount = referenceOnly,
            fullContributorPixelCount = allFrames,
            frameDiagnostics = accepted.map { requireNotNull(frameDiagnostics[it.frame.frameIndex]).toImmutable(processedPixels) },
            aggregationDiagnostics = aggregationDiagnostics,
            tilePlan = plan,
            warnings = warnings,
            fatalError = null,
            fatalMessage = null,
            processingDurationMs = elapsedMs(startedNs),
            options = options,
            darkCalibrationApplied = dark.masterDark != null,
            masterDarkId = dark.masterDark?.metadata?.id,
            temporaryCleanupSucceeded = cleanupSucceeded
        )
    }

    private fun compatibilityFailure(
        reference: RawFrame,
        frame: RawFrame,
        options: StarAlignedRaw16StackOptions
    ): Pair<StarAlignedRaw16FailureCode, String>? {
        if (frame.width != reference.width || frame.height != reference.height) {
            return StarAlignedRaw16FailureCode.INCOMPATIBLE_DIMENSIONS to "dimensions differ from reference"
        }
        if (frame.cfaPattern == null || frame.cfaPattern != reference.cfaPattern) {
            return StarAlignedRaw16FailureCode.INCOMPATIBLE_CFA to "CFA differs from reference"
        }
        if (frame.cameraId != null && reference.cameraId != null && frame.cameraId != reference.cameraId) {
            return StarAlignedRaw16FailureCode.INCOMPATIBLE_CAMERA to "camera ID differs from reference"
        }
        if (options.requireSameIso && frame.iso != reference.iso) {
            return StarAlignedRaw16FailureCode.INCOMPATIBLE_ISO to "ISO differs from reference"
        }
        if (
            options.requireSameExposure &&
            abs(frame.exposureTimeNs - reference.exposureTimeNs).toDouble() /
            reference.exposureTimeNs.coerceAtLeast(1L).toDouble() > options.exposureRelativeTolerance
        ) {
            return StarAlignedRaw16FailureCode.INCOMPATIBLE_EXPOSURE to "exposure differs from reference"
        }
        return null
    }

    private fun resolveDark(
        reference: RawFrame,
        options: StarAlignedRaw16StackOptions
    ): DarkResolution {
        val input = options.darkCalibration
        if (input.policy == DarkPolicy.OFF) return DarkResolution()
        val masterDark = input.masterDark
        if (masterDark == null) {
            return if (input.options.missingMasterDarkBehavior == MissingMasterDarkBehavior.FAIL) {
                DarkResolution(failure = "A compatible MasterDark is required.")
            } else {
                DarkResolution(
                    warnings = listOf(
                        warning(
                            StarAlignedRaw16WarningCode.MASTER_DARK_NOT_FOUND,
                            null,
                            "No MasterDark provided; star stack continued without dark subtraction."
                        )
                    )
                )
            }
        }
        val reasons = MasterDarkMatcher.rejectionReasons(reference, masterDark, input.options)
        if (reasons.isNotEmpty()) {
            val message = "MasterDark is incompatible: ${reasons.joinToString()}."
            return if (input.options.missingMasterDarkBehavior == MissingMasterDarkBehavior.FAIL) {
                DarkResolution(failure = message)
            } else {
                DarkResolution(
                    warnings = listOf(
                        warning(StarAlignedRaw16WarningCode.MASTER_DARK_NOT_FOUND, null, message)
                    )
                )
            }
        }
        return DarkResolution(
            masterDark = masterDark,
            warnings = listOf(
                warning(
                    StarAlignedRaw16WarningCode.MASTER_DARK_APPLIED,
                    null,
                    "MasterDark ${masterDark.metadata.id} applied before interpolation."
                )
            )
        )
    }

    private fun estimateValidFraction(width: Int, height: Int, inverse: SimilarityTransformInverse): Double {
        val grid = 32
        var valid = 0
        var total = 0
        for (gy in 0 until grid) {
            val y = (gy + 0.5) * height / grid
            for (gx in 0 until grid) {
                val x = (gx + 0.5) * width / grid
                val target = inverse.mapReferenceToTarget(x, y)
                if (target.x >= 2.0 && target.y >= 2.0 && target.x < width - 2.0 && target.y < height - 2.0) {
                    valid++
                }
                total++
            }
        }
        return valid.toDouble() / total.toDouble()
    }

    private fun failureAfterOutput(
        rawStack: RawStack,
        options: StarAlignedRaw16StackOptions,
        startedNs: Long,
        code: StarAlignedRaw16FailureCode,
        message: String,
        warnings: List<StarAlignedRaw16Warning>,
        outputFile: File
    ): StarAlignedRaw16StackResult {
        val cleanup = !outputFile.exists() || outputFile.delete()
        return failure(rawStack, options, startedNs, code, message, warnings, cleanupSucceeded = cleanup)
    }

    private fun failure(
        rawStack: RawStack,
        options: StarAlignedRaw16StackOptions,
        startedNs: Long,
        code: StarAlignedRaw16FailureCode,
        message: String,
        warnings: List<StarAlignedRaw16Warning> = emptyList(),
        outputFilePath: String? = null,
        cleanupSucceeded: Boolean = true
    ): StarAlignedRaw16StackResult =
        StarAlignedRaw16StackResult(
            success = false,
            width = rawStack.frames.firstOrNull()?.width ?: 0,
            height = rawStack.frames.firstOrNull()?.height ?: 0,
            outputRaw16FilePath = outputFilePath,
            referenceFrameIndex = null,
            inputFrameCount = rawStack.frameCount,
            acceptedFrameCount = 0,
            rejectedFrameCount = rawStack.frameCount,
            minimumValidCount = 0,
            maximumValidCount = 0,
            meanValidCount = 0.0,
            referenceOnlyPixelCount = 0,
            fullContributorPixelCount = 0,
            frameDiagnostics = emptyList(),
            aggregationDiagnostics = RawStackAggregationDiagnostics.empty(options.aggregationOptions.mode),
            tilePlan = null,
            warnings = warnings,
            fatalError = code,
            fatalMessage = message,
            processingDurationMs = elapsedMs(startedNs),
            options = options,
            temporaryCleanupSucceeded = cleanupSucceeded
        )

    private fun warning(
        code: StarAlignedRaw16WarningCode,
        frameIndex: Int?,
        message: String
    ) = StarAlignedRaw16Warning(code, frameIndex, message)

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L

    private fun com.lab.bracketlab.processing.align.star.StarSimilarityTransform.isIdentity(): Boolean =
        abs(a - 1.0) <= 1e-12 &&
            abs(b) <= 1e-12 &&
            abs(tx) <= 1e-9 &&
            abs(ty) <= 1e-9

    private data class FrameState(
        val frame: RawFrame,
        val alignment: StarFrameAlignment,
        val inverse: SimilarityTransformInverse,
        val estimatedValidFraction: Double,
        var accessor: Raw16SampleAccessor? = null
    )

    private data class DarkResolution(
        val masterDark: MasterDark? = null,
        val warnings: List<StarAlignedRaw16Warning> = emptyList(),
        val failure: String? = null
    )

    private class MutableFrameDiagnostics(
        val frameIndex: Int,
        val reference: Boolean
    ) {
        var valid = 0L
        var invalid = 0L
        var direct = 0L
        var horizontal = 0L
        var vertical = 0L
        var bilinear = 0L

        fun toImmutable(totalPixels: Long): StarFrameWarpDiagnostics =
            StarFrameWarpDiagnostics(
                frameIndex,
                reference,
                valid,
                invalid,
                if (totalPixels == 0L) 0.0 else valid.toDouble() / totalPixels.toDouble(),
                direct,
                horizontal,
                vertical,
                bilinear
            )
    }
}
