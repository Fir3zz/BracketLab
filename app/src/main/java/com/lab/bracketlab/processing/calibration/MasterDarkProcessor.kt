package com.lab.bracketlab.processing.calibration

import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.stack.Raw16SampleAccessor
import com.lab.bracketlab.processing.stack.RawStackAggregationDiagnostics
import com.lab.bracketlab.processing.stack.RawStackAggregationFallbackReason
import com.lab.bracketlab.processing.stack.RawStackAggregationMode
import com.lab.bracketlab.processing.stack.RawStackAggregationWorkResult
import com.lab.bracketlab.processing.stack.RawStackAggregator
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MasterDarkProcessor(
    private val workingDirectory: File,
    private val testOutputFileFactory: ((File) -> File)? = null
) {
    fun createMasterDark(
        darkStack: RawStack,
        options: DarkCalibrationOptions = DarkCalibrationOptions(
            darkPolicy = DarkPolicy.CAPTURE_MASTER_DARK
        )
    ): DarkCalibrationResult {
        val startedNs = System.nanoTime()
        val validation = validateDarkStack(darkStack, options)
        if (validation != null) {
            return DarkCalibrationResult.failure(
                validation.first,
                validation.second,
                darkStack.frameCount,
                startedNs
            )
        }

        val reference = darkStack.frames.first()
        val aggregationOptions = options.resolvedAggregationOptions(darkStack.frameCount)
        workingDirectory.mkdirs()
        val outputFile =
            testOutputFileFactory?.invoke(workingDirectory)
                ?: File(
                    workingDirectory,
                    ".masterdark_${System.nanoTime()}.raw16.tmp"
                )
        val accessors = mutableListOf<Raw16SampleAccessor>()

        return try {
            darkStack.frames.mapTo(accessors, Raw16SampleAccessor::create)
            val sampleScratch = IntArray(accessors.size)
            val aggregationScratch = RawStackAggregator.createScratch(accessors.size)
            val aggregationWork = RawStackAggregationWorkResult()
            val packedRow = ByteArray(reference.width * 2)
            var totalInputSamples = 0L
            var totalAcceptedSamples = 0L
            var totalRejectedSamples = 0L
            var pixelsUsingRequestedMode = 0L
            var pixelsFallingBackToMean = 0L
            var pixelsFallingBackToOtherMode = 0L
            var maximumRejected = 0
            var zeroVariancePixels = 0L
            var insufficientPixels = 0L
            var minimumValue = 65535
            var maximumValue = 0
            var valueSum = 0L

            BufferedOutputStream(FileOutputStream(outputFile), STREAM_BUFFER_BYTES).use { output ->
                for (tileStartY in 0 until reference.height step options.tileHeight) {
                    val tileEndY = minOf(reference.height, tileStartY + options.tileHeight)
                    for (y in tileStartY until tileEndY) {
                        var rowOffset = 0
                        for (x in 0 until reference.width) {
                            for (index in accessors.indices) {
                                sampleScratch[index] = accessors[index].sampleAt(x, y)
                            }
                            val value = RawStackAggregator.aggregateInto(
                                samples = sampleScratch,
                                sampleCount = accessors.size,
                                options = aggregationOptions,
                                scratch = aggregationScratch,
                                output = aggregationWork
                            )
                            packedRow[rowOffset] = (value and 0xFF).toByte()
                            packedRow[rowOffset + 1] = ((value ushr 8) and 0xFF).toByte()
                            rowOffset += 2

                            minimumValue = minOf(minimumValue, value)
                            maximumValue = maxOf(maximumValue, value)
                            valueSum += value.toLong()
                            totalInputSamples += aggregationWork.inputSampleCount.toLong()
                            totalAcceptedSamples += aggregationWork.acceptedSampleCount.toLong()
                            totalRejectedSamples += aggregationWork.totalRejectedCount.toLong()
                            if (aggregationWork.appliedMode == aggregationOptions.mode) {
                                pixelsUsingRequestedMode++
                            } else if (aggregationWork.appliedMode == RawStackAggregationMode.MEAN) {
                                pixelsFallingBackToMean++
                            } else {
                                pixelsFallingBackToOtherMode++
                            }
                            maximumRejected = max(maximumRejected, aggregationWork.totalRejectedCount)
                            if (aggregationWork.zeroVariance) zeroVariancePixels++
                            if (
                                aggregationWork.fallbackReason ==
                                RawStackAggregationFallbackReason.INSUFFICIENT_SAMPLES_FOR_MIN_MAX ||
                                aggregationWork.fallbackReason ==
                                RawStackAggregationFallbackReason.INSUFFICIENT_SAMPLES_FOR_SIGMA ||
                                aggregationWork.fallbackReason ==
                                RawStackAggregationFallbackReason.INSUFFICIENT_REMAINING_SAMPLES
                            ) {
                                insufficientPixels++
                            }
                        }
                        output.write(packedRow)
                    }
                }
                output.flush()
            }

            val pixelCount = reference.width.toLong() * reference.height.toLong()
            val expectedBytes = pixelCount * 2L
            if (outputFile.length() != expectedBytes) {
                val cleaned = !outputFile.exists() || outputFile.delete()
                return DarkCalibrationResult.failure(
                    DarkCalibrationFailureCode.DARK_TEMP_FILE_FAILURE,
                    "Packed MasterDark size ${outputFile.length()} differs from expected $expectedBytes.",
                    darkStack.frameCount,
                    startedNs,
                    cleanupSucceeded = cleaned
                )
            }

            val createdAt = System.currentTimeMillis()
            val id = buildMasterDarkId(reference, createdAt)
            val metadata = MasterDarkMetadata(
                id = id,
                width = reference.width,
                height = reference.height,
                rowStride = reference.width * 2,
                pixelStride = 2,
                cfaPattern = reference.cfaPattern ?: CfaPattern.UNKNOWN,
                cameraId = reference.cameraId,
                iso = reference.iso,
                exposureTimeNs = reference.exposureTimeNs,
                frameCount = darkStack.frameCount,
                aggregationMode = aggregationOptions.mode,
                blackLevelPattern = reference.blackLevelPattern?.copyOf(),
                whiteLevel = reference.whiteLevel,
                createdAtMillis = createdAt,
                appVersion = options.appVersion,
                rawFilename = "$id.raw16",
                sourceFrames = darkStack.frames.map {
                    DarkSourceFrameSummary(
                        frameIndex = it.frameIndex,
                        timestampNs = it.timestampNs,
                        iso = it.iso,
                        exposureTimeNs = it.exposureTimeNs
                    )
                },
                minimumDarkValue = minimumValue,
                maximumDarkValue = maximumValue,
                meanDarkValue =
                    if (pixelCount == 0L) null else valueSum.toDouble() / pixelCount.toDouble()
            )
            val diagnostics = RawStackAggregationDiagnostics(
                requestedMode = aggregationOptions.mode,
                pixelsProcessed = pixelCount,
                totalInputSamples = totalInputSamples,
                totalAcceptedSamples = totalAcceptedSamples,
                totalRejectedSamples = totalRejectedSamples,
                pixelsUsingRequestedMode = pixelsUsingRequestedMode,
                pixelsFallingBackToMean = pixelsFallingBackToMean,
                pixelsFallingBackToOtherMode = pixelsFallingBackToOtherMode,
                maximumRejectedSamplesAtOnePixel = maximumRejected,
                meanRejectedSamplesPerPixel =
                    if (pixelCount == 0L) 0.0 else totalRejectedSamples.toDouble() / pixelCount.toDouble(),
                pixelsWithZeroVariance = zeroVariancePixels,
                pixelsWithInsufficientSamples = insufficientPixels,
                rejectionCountMap = null
            )
            DarkCalibrationResult(
                success = true,
                masterDark = MasterDark(metadata, outputFile),
                darkFrameCount = darkStack.frameCount,
                aggregationMode = aggregationOptions.mode,
                aggregationDiagnostics = diagnostics,
                minimumDarkValue = minimumValue,
                maximumDarkValue = maximumValue,
                meanDarkValue = metadata.meanDarkValue,
                processingDurationMs = (System.nanoTime() - startedNs) / 1_000_000L
            )
        } catch (error: Throwable) {
            val cleaned = !outputFile.exists() || outputFile.delete()
            DarkCalibrationResult.failure(
                DarkCalibrationFailureCode.DARK_PROCESSING_FAILED,
                error.message ?: "MasterDark processing failed.",
                darkStack.frameCount,
                startedNs,
                error,
                cleaned
            )
        } finally {
            accessors.forEach { runCatching { it.close() } }
        }
    }

    internal fun validateDarkStack(
        darkStack: RawStack,
        options: DarkCalibrationOptions
    ): Pair<DarkCalibrationFailureCode, String>? {
        if (darkStack.frames.isEmpty()) {
            return DarkCalibrationFailureCode.EMPTY_DARK_STACK to "Dark stack is empty."
        }
        val required = if (options.allowSingleDarkFrame) 1 else max(2, options.minimumDarkFrames)
        if (darkStack.frameCount < max(required, options.minimumDarkFrames)) {
            return DarkCalibrationFailureCode.INSUFFICIENT_DARK_FRAMES to
                "Dark stack has ${darkStack.frameCount} frames; required ${max(required, options.minimumDarkFrames)}."
        }
        val reference = darkStack.frames.first()
        Raw16SampleAccessor.validate(reference).takeIf { !it.valid }?.let {
            return DarkCalibrationFailureCode.INVALID_DARK_RAW_STORAGE to
                (it.message ?: "Reference dark RAW storage is invalid.")
        }
        if (reference.cfaPattern == null || reference.cfaPattern == CfaPattern.UNKNOWN) {
            return DarkCalibrationFailureCode.INCOMPATIBLE_DARK_CFA to
                "Reference dark frame has no supported CFA pattern."
        }

        for (frame in darkStack.frames.drop(1)) {
            Raw16SampleAccessor.validate(frame).takeIf { !it.valid }?.let {
                return DarkCalibrationFailureCode.INVALID_DARK_RAW_STORAGE to
                    "Frame ${frame.frameIndex}: ${it.message}"
            }
            if (frame.width != reference.width || frame.height != reference.height) {
                return DarkCalibrationFailureCode.INCOMPATIBLE_DARK_DIMENSIONS to
                    "Frame ${frame.frameIndex} dimensions differ from reference."
            }
            if (frame.cfaPattern != reference.cfaPattern) {
                return DarkCalibrationFailureCode.INCOMPATIBLE_DARK_CFA to
                    "Frame ${frame.frameIndex} CFA differs from reference."
            }
            if (
                options.requireSameCameraIdWhenKnown &&
                reference.cameraId != null &&
                frame.cameraId != null &&
                reference.cameraId != frame.cameraId
            ) {
                return DarkCalibrationFailureCode.INCOMPATIBLE_DARK_CAMERA to
                    "Frame ${frame.frameIndex} camera ID differs from reference."
            }
            if (
                options.requireSameIsoWhenKnown &&
                reference.iso > 0 &&
                frame.iso > 0 &&
                reference.iso != frame.iso
            ) {
                return DarkCalibrationFailureCode.INCOMPATIBLE_DARK_ISO to
                    "Frame ${frame.frameIndex} ISO differs from reference."
            }
            if (!exposureMatches(reference.exposureTimeNs, frame.exposureTimeNs, options)) {
                return DarkCalibrationFailureCode.INCOMPATIBLE_DARK_EXPOSURE to
                    "Frame ${frame.frameIndex} exposure differs from reference."
            }
            if (
                options.requireCompatibleBlackLevel &&
                !reference.blackLevelPattern.contentEqualsOrBothNull(frame.blackLevelPattern)
            ) {
                return DarkCalibrationFailureCode.INCOMPATIBLE_DARK_BLACK_LEVEL to
                    "Frame ${frame.frameIndex} black level differs from reference."
            }
            if (
                options.requireCompatibleWhiteLevel &&
                reference.whiteLevel != null &&
                frame.whiteLevel != null &&
                reference.whiteLevel != frame.whiteLevel
            ) {
                return DarkCalibrationFailureCode.INCOMPATIBLE_DARK_WHITE_LEVEL to
                    "Frame ${frame.frameIndex} white level differs from reference."
            }
        }
        return null
    }

    private fun exposureMatches(
        referenceNs: Long,
        candidateNs: Long,
        options: DarkCalibrationOptions
    ): Boolean {
        if (referenceNs <= 0L || candidateNs <= 0L) return false
        val difference = kotlin.math.abs(referenceNs - candidateNs)
        if (difference <= options.exposureAbsoluteToleranceNs) return true
        return difference.toDouble() / referenceNs.toDouble() <= options.exposureRelativeTolerance
    }

    private fun buildMasterDarkId(frame: RawFrame, createdAtMillis: Long): String {
        val date = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(createdAtMillis))
        val camera = frame.cameraId?.replace(Regex("[^A-Za-z0-9_.-]"), "_") ?: "unknown"
        return "masterdark_${camera}_ISO${frame.iso}_EXP${frame.exposureTimeNs}ns_$date"
    }

    private fun IntArray?.contentEqualsOrBothNull(other: IntArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> contentEquals(other)
        }

    companion object {
        private const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}
