package com.lab.bracketlab.processing.stack

import com.lab.bracketlab.processing.align.LandscapeAlignmentOptions
import com.lab.bracketlab.processing.align.LandscapeAlignmentReport
import com.lab.bracketlab.processing.align.LandscapeAlignmentStatus
import com.lab.bracketlab.processing.align.LandscapeFrameAlignment
import com.lab.bracketlab.processing.align.PhaseCorrelationSelfTestStatus
import com.lab.bracketlab.processing.align.ReferenceSelectionMethod
import com.lab.bracketlab.processing.io.Raw16DngWriter
import com.lab.bracketlab.processing.model.AlignmentResult
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import com.lab.bracketlab.processing.raw.CfaPattern
import java.nio.ByteOrder
import kotlin.math.abs

data class RawStackAggregatorSelfTestCaseResult(
    val name: String,
    val status: PhaseCorrelationSelfTestStatus,
    val message: String
)

data class RawStackAggregatorSelfTestReport(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val processingDurationMs: Long,
    val results: List<RawStackAggregatorSelfTestCaseResult>
)

object RawStackAggregatorSelfTest {
    fun runAll(): RawStackAggregatorSelfTestReport {
        val startedNs = System.nanoTime()
        val results = listOf(
            test("mean single sample") { meanSingleSample() },
            test("mean deterministic rounding") { meanDeterministicRounding() },
            test("mean unsigned above 32767") { meanUnsignedAbove32767() },
            test("mean full scale 65535") { meanFullScale() },
            test("mean Long accumulator") { meanLongAccumulator() },
            test("mean exact old implementation equivalence") { meanOldImplementationEquivalence() },
            test("min-max high outlier") { minMaxHighOutlier() },
            test("min-max low outlier") { minMaxLowOutlier() },
            test("min-max both outliers") { minMaxBothOutliers() },
            test("min-max duplicate minimum instances") { minMaxDuplicateMinimumInstances() },
            test("min-max duplicate maximum instances") { minMaxDuplicateMaximumInstances() },
            test("min-max all equal") { minMaxAllEqual() },
            test("min-max insufficient fallback") { minMaxInsufficientFallback() },
            test("min-max invalid rejection count") { minMaxInvalidRejectionCount() },
            test("min-max multiple low and high rejection") { minMaxMultipleRejection() },
            test("min-max scratch reuse deterministic") { minMaxScratchReuse() },
            test("sigma high outlier") { sigmaHighOutlier() },
            test("sigma low outlier") { sigmaLowOutlier() },
            test("sigma both outliers") { sigmaBothOutliers() },
            test("sigma all equal") { sigmaAllEqual() },
            test("sigma zero variance") { sigmaZeroVariance() },
            test("sigma insufficient fallback policies") { sigmaInsufficientFallbackPolicies() },
            test("sigma maximum iteration stop") { sigmaMaximumIterationStop() },
            test("sigma no rejection") { sigmaNoRejection() },
            test("sigma minimum remaining policy") { sigmaMinimumRemainingPolicy() },
            test("sigma deterministic repeat") { sigmaDeterministicRepeat() },
            test("sigma finite statistics") { sigmaFiniteStatistics() },
            test("sigma extreme RAW16 values") { sigmaExtremeRaw16Values() },
            test("integration default mean byte-identical") { integrationMeanByteIdentical() },
            test("integration min-max removes hot pixel") { integrationMinMaxRemovesHotPixel() },
            test("integration sigma removes hot pixel") { integrationSigmaRemovesHotPixel() },
            test("integration border fallback to mean") { integrationBorderFallback() },
            test("integration valid-count behavior unchanged") { integrationValidCountUnchanged() },
            test("integration CFA phase remains correct") { integrationCfaPhase() },
            test("integration source buffers unchanged") { integrationSourceBuffersUnchanged() },
            test("integration output packing unchanged") { integrationOutputPacking() },
            test("integration DNG writer accepts robust output") { integrationDngWriterAcceptsOutput() },
            test("integration identity and translated behavior") { integrationIdentityAndTranslation() }
        )
        return RawStackAggregatorSelfTestReport(
            passed = results.count { it.status == PhaseCorrelationSelfTestStatus.PASS },
            failed = results.count { it.status == PhaseCorrelationSelfTestStatus.FAIL },
            skipped = results.count { it.status == PhaseCorrelationSelfTestStatus.SKIPPED },
            processingDurationMs = (System.nanoTime() - startedNs) / 1_000_000L,
            results = results
        )
    }

    private fun meanSingleSample(): Boolean =
        aggregate(intArrayOf(43210), mode = RawStackAggregationMode.MEAN).outputValue == 43210

    private fun meanDeterministicRounding(): Boolean =
        aggregate(intArrayOf(1, 2), mode = RawStackAggregationMode.MEAN).outputValue == 2

    private fun meanUnsignedAbove32767(): Boolean =
        aggregate(intArrayOf(40000, 50000), mode = RawStackAggregationMode.MEAN).outputValue == 45000

    private fun meanFullScale(): Boolean =
        aggregate(intArrayOf(65535, 65535), mode = RawStackAggregationMode.MEAN).outputValue == 65535

    private fun meanLongAccumulator(): Boolean {
        val samples = IntArray(100_000) { 65535 }
        return aggregate(samples, mode = RawStackAggregationMode.MEAN).outputValue == 65535
    }

    private fun meanOldImplementationEquivalence(): Boolean {
        for (count in 1..128) {
            val samples = IntArray(count) { index ->
                ((index * 7919L + count * 104729L) and 0xFFFFL).toInt()
            }
            val expected = oldMean(samples, count)
            val actual = aggregate(samples, mode = RawStackAggregationMode.MEAN).outputValue
            if (actual != expected) return false
        }
        return true
    }

    private fun minMaxHighOutlier(): Boolean {
        val result = aggregate(intArrayOf(100, 101, 99, 100, 65535), RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        return result.outputValue == 100 && result.rejectedHighCount == 1 && result.acceptedSampleCount == 3
    }

    private fun minMaxLowOutlier(): Boolean {
        val result = aggregate(intArrayOf(10000, 10001, 9999, 10000, 0), RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        return result.outputValue == 10000 && result.rejectedLowCount == 1
    }

    private fun minMaxBothOutliers(): Boolean {
        val result = aggregate(intArrayOf(0, 1000, 1000, 1000, 65535), RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        return result.outputValue == 1000 && result.totalRejectedCount == 2
    }

    private fun minMaxDuplicateMinimumInstances(): Boolean {
        val result = aggregate(intArrayOf(10, 10, 20, 20, 30), RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        return result.outputValue == oldMean(intArrayOf(10, 20, 20), 3)
    }

    private fun minMaxDuplicateMaximumInstances(): Boolean {
        val result = aggregate(intArrayOf(10, 20, 20, 30, 30), RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        return result.outputValue == oldMean(intArrayOf(20, 20, 30), 3)
    }

    private fun minMaxAllEqual(): Boolean {
        val result = aggregate(IntArray(5) { 12345 }, RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        return result.outputValue == 12345 && result.acceptedSampleCount == 3
    }

    private fun minMaxInsufficientFallback(): Boolean {
        val result = aggregate(intArrayOf(100, 200, 300, 400), RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        return result.outputValue == 250 &&
            result.appliedMode == RawStackAggregationMode.MEAN &&
            result.fallbackReason == RawStackAggregationFallbackReason.INSUFFICIENT_SAMPLES_FOR_MIN_MAX
    }

    private fun minMaxInvalidRejectionCount(): Boolean =
        runCatching {
            RawStackAggregationOptions(
                mode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN,
                minimumSamplesForMinMax = 5,
                lowSamplesToReject = 3,
                highSamplesToReject = 2
            )
        }.isFailure

    private fun minMaxMultipleRejection(): Boolean {
        val options = RawStackAggregationOptions(
            mode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN,
            minimumSamplesForMinMax = 7,
            lowSamplesToReject = 2,
            highSamplesToReject = 2
        )
        val result = RawStackAggregator.aggregate(
            intArrayOf(0, 1, 100, 100, 100, 60000, 65535),
            7,
            options
        )
        return result.outputValue == 100 &&
            result.rejectedLowCount == 2 &&
            result.rejectedHighCount == 2
    }

    private fun minMaxScratchReuse(): Boolean {
        val options = RawStackAggregationOptions(mode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        val scratch = RawStackAggregator.createScratch(5)
        val work = RawStackAggregationWorkResult()
        val first = intArrayOf(0, 100, 100, 100, 65535)
        val second = intArrayOf(10, 20, 30, 40, 50)
        RawStackAggregator.aggregateInto(first, first.size, options, scratch, work)
        val firstValue = work.outputValue
        RawStackAggregator.aggregateInto(second, second.size, options, scratch, work)
        val secondValue = work.outputValue
        return firstValue == 100 && secondValue == 30 &&
            secondValue == RawStackAggregator.aggregate(second, second.size, options).outputValue
    }

    private fun sigmaHighOutlier(): Boolean {
        val samples = IntArray(9) { if (it == 8) 65535 else 100 }
        val result = aggregate(samples, RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
        return result.outputValue == 100 && result.rejectedSigmaCount == 1
    }

    private fun sigmaLowOutlier(): Boolean {
        val samples = IntArray(9) { if (it == 8) 0 else 10000 }
        val result = aggregate(samples, RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
        return result.outputValue == 10000 && result.rejectedSigmaCount == 1
    }

    private fun sigmaBothOutliers(): Boolean {
        val samples = IntArray(15) { 10000 }
        samples[13] = 0
        samples[14] = 65535
        val result = aggregate(samples, RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
        return result.outputValue == 10000 && result.rejectedSigmaCount == 2
    }

    private fun sigmaAllEqual(): Boolean {
        val result = aggregate(IntArray(7) { 22222 }, RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
        return result.outputValue == 22222 && result.rejectedSigmaCount == 0
    }

    private fun sigmaZeroVariance(): Boolean {
        val result = aggregate(IntArray(7) { 777 }, RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
        return result.zeroVariance && result.acceptedSampleCount == 7
    }

    private fun sigmaInsufficientFallbackPolicies(): Boolean {
        val samples = intArrayOf(100, 100, 100, 100, 65535)
        val meanFallback = RawStackAggregator.aggregate(
            samples,
            samples.size,
            RawStackAggregationOptions(mode = RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
        )
        val minMaxFallback = RawStackAggregator.aggregate(
            samples,
            samples.size,
            RawStackAggregationOptions(
                mode = RawStackAggregationMode.SIGMA_CLIPPED_MEAN,
                fallbackMode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN
            )
        )
        return meanFallback.appliedMode == RawStackAggregationMode.MEAN &&
            meanFallback.outputValue == oldMean(samples, samples.size) &&
            minMaxFallback.appliedMode == RawStackAggregationMode.MIN_MAX_REJECTED_MEAN &&
            minMaxFallback.outputValue == 100
    }

    private fun sigmaMaximumIterationStop(): Boolean {
        val samples = IntArray(15) { 10000 }
        samples[13] = 0
        samples[14] = 65535
        val result = RawStackAggregator.aggregate(
            samples,
            samples.size,
            RawStackAggregationOptions(
                mode = RawStackAggregationMode.SIGMA_CLIPPED_MEAN,
                maxSigmaIterations = 1
            )
        )
        return result.rejectedSigmaCount == 1 && result.outputValue == 9286
    }

    private fun sigmaNoRejection(): Boolean {
        val samples = intArrayOf(98, 99, 100, 100, 100, 101, 102)
        val result = aggregate(samples, RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
        return result.rejectedSigmaCount == 0 && result.outputValue == 100
    }

    private fun sigmaMinimumRemainingPolicy(): Boolean {
        val samples = intArrayOf(0, 100, 200, 300, 400, 500, 600)
        val result = RawStackAggregator.aggregate(
            samples,
            samples.size,
            RawStackAggregationOptions(
                mode = RawStackAggregationMode.SIGMA_CLIPPED_MEAN,
                sigmaThreshold = 0.1,
                minimumRemainingSamples = 6
            )
        )
        return result.appliedMode == RawStackAggregationMode.MEAN &&
            result.fallbackReason == RawStackAggregationFallbackReason.INSUFFICIENT_REMAINING_SAMPLES &&
            result.outputValue == 300
    }

    private fun sigmaDeterministicRepeat(): Boolean {
        val samples = intArrayOf(100, 101, 100, 99, 100, 101, 100, 99, 65535)
        val first = aggregate(samples, RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
        val second = aggregate(samples, RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
        return first == second
    }

    private fun sigmaFiniteStatistics(): Boolean {
        val samples = IntArray(128) { index -> (index * 509).coerceAtMost(65535) }
        val result = aggregate(samples, RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
        return result.outputValue in 0..65535 &&
            result.acceptedSampleCount > 0 &&
            result.totalRejectedCount <= samples.size
    }

    private fun sigmaExtremeRaw16Values(): Boolean {
        val samples = IntArray(20) { if ((it and 1) == 0) 0 else 65535 }
        val result = aggregate(samples, RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
        return result.outputValue == 32768 && result.acceptedSampleCount == 20
    }

    private fun integrationMeanByteIdentical(): Boolean {
        val frames = (0 until 5).map { frameIndex ->
            frame(frameIndex) { x, y -> 1000 + frameIndex * 31 + x * 3 + y * 17 }
        }
        val result = process(frames, aggregation = RawStackAggregationOptions())
        val expected = legacyMeanBytes(frames)
        return result.success && result.outputRaw16Copy()!!.contentEquals(expected)
    }

    private fun integrationMinMaxRemovesHotPixel(): Boolean {
        val hotX = 2
        val hotY = 3
        val frames = (0 until 5).map { frameIndex ->
            frame(frameIndex) { x, y ->
                if (frameIndex == 4 && x == hotX && y == hotY) 65535 else 1000
            }
        }
        val result = process(
            frames,
            aggregation = RawStackAggregationOptions(mode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        )
        return result.success && result.outputSamples()[hotY * WIDTH + hotX] == 1000
    }

    private fun integrationSigmaRemovesHotPixel(): Boolean {
        val hotX = 3
        val hotY = 2
        val frames = (0 until 9).map { frameIndex ->
            frame(frameIndex) { x, y ->
                if (frameIndex == 8 && x == hotX && y == hotY) 65535 else 1200
            }
        }
        val result = process(
            frames,
            aggregation = RawStackAggregationOptions(mode = RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
        )
        return result.success && result.outputSamples()[hotY * WIDTH + hotX] == 1200
    }

    private fun integrationBorderFallback(): Boolean {
        val frames = (0 until 5).map { frameIndex -> frame(frameIndex) { _, _ -> 1000 + frameIndex * 10 } }
        val transforms = mapOf(
            0 to RawTransform.IDENTITY,
            1 to RawTransform(dx = 2.0),
            2 to RawTransform(dx = 2.0),
            3 to RawTransform(dx = 2.0),
            4 to RawTransform(dx = 2.0)
        )
        val result = process(
            frames,
            transforms,
            RawStackAggregationOptions(mode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        )
        return result.success &&
            result.aggregationDiagnostics.pixelsFallingBackToMean > 0 &&
            result.aggregationDiagnostics.pixelsWithInsufficientSamples > 0
    }

    private fun integrationValidCountUnchanged(): Boolean {
        val frames = (0 until 5).map { frameIndex -> frame(frameIndex) { _, _ -> 1000 + frameIndex } }
        val transforms = frames.associate { frame ->
            frame.frameIndex to if (frame.frameIndex == 0) RawTransform.IDENTITY else RawTransform(dx = 2.0)
        }
        val mean = process(
            frames,
            transforms,
            RawStackAggregationOptions(),
            debugValidCountMap = true
        )
        val robust = process(
            frames,
            transforms,
            RawStackAggregationOptions(mode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN),
            debugValidCountMap = true
        )
        return mean.validCountMap!!.contentEquals(robust.validCountMap!!) &&
            mean.minimumValidCount == robust.minimumValidCount &&
            mean.maximumValidCount == robust.maximumValidCount
    }

    private fun integrationCfaPhase(): Boolean {
        val frames = (0 until 5).map { frameIndex ->
            frame(frameIndex, cfaPattern = CfaPattern.GRBG) { x, y ->
                cfaPhaseValue(CfaPattern.GRBG, x, y) + frameIndex
            }
        }
        val result = process(
            frames,
            aggregation = RawStackAggregationOptions(mode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        )
        if (!result.success) return false
        return result.outputSamples().withIndex().all { (index, value) ->
            val x = index % WIDTH
            val y = index / WIDTH
            value == cfaPhaseValue(CfaPattern.GRBG, x, y) + 2
        }
    }

    private fun integrationSourceBuffersUnchanged(): Boolean {
        val frames = (0 until 7).map { frameIndex -> frame(frameIndex) { x, y -> 1000 + frameIndex + x + y } }
        val before = frames.map { it.raw16!!.copyOf() }
        process(
            frames,
            aggregation = RawStackAggregationOptions(mode = RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
        )
        return frames.zip(before).all { (frame, bytes) -> frame.raw16!!.contentEquals(bytes) }
    }

    private fun integrationOutputPacking(): Boolean {
        val frames = (0 until 5).map { frameIndex -> frame(frameIndex) { _, _ -> 1000 + frameIndex } }
        val result = process(
            frames,
            aggregation = RawStackAggregationOptions(mode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        )
        val buffer = result.outputRaw16 ?: return false
        return result.success &&
            result.outputByteOrder == ByteOrder.LITTLE_ENDIAN &&
            result.outputRowStride == WIDTH * 2 &&
            result.outputPixelStride == 2 &&
            buffer.isReadOnly &&
            buffer.position() == 0 &&
            buffer.limit() == WIDTH * HEIGHT * 2
    }

    private fun integrationDngWriterAcceptsOutput(): Boolean {
        val frames = (0 until 5).map { frameIndex -> frame(frameIndex) { _, _ -> 1000 + frameIndex } }
        val result = process(
            frames,
            aggregation = RawStackAggregationOptions(mode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        )
        return result.success && Raw16DngWriter.validatePackedResult(result).success
    }

    private fun integrationIdentityAndTranslation(): Boolean {
        val frames = (0 until 5).map { frameIndex ->
            frame(frameIndex) { x, y -> 1000 + frameIndex * 10 + x + y * 10 }
        }
        val transforms = mapOf(
            0 to RawTransform.IDENTITY,
            1 to RawTransform.IDENTITY,
            2 to RawTransform(dx = 2.0),
            3 to RawTransform(dx = 2.0),
            4 to RawTransform(dx = 2.0)
        )
        val result = process(
            frames,
            transforms,
            RawStackAggregationOptions(mode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        )
        val x = 4
        val y = 2
        val samples = intArrayOf(
            1000 + x + y * 10,
            1010 + x + y * 10,
            1020 + (x - 2) + y * 10,
            1030 + (x - 2) + y * 10,
            1040 + (x - 2) + y * 10
        )
        val expected = RawStackAggregator.aggregate(
            samples,
            samples.size,
            RawStackAggregationOptions(mode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
        ).outputValue
        return result.success && result.outputSamples()[y * WIDTH + x] == expected
    }

    private fun aggregate(
        samples: IntArray,
        mode: RawStackAggregationMode
    ): RawStackAggregationResult =
        RawStackAggregator.aggregate(
            samples,
            samples.size,
            RawStackAggregationOptions(mode = mode)
        )

    private fun process(
        frames: List<RawFrame>,
        transforms: Map<Int, RawTransform> = frames.associate { it.frameIndex to RawTransform.IDENTITY },
        aggregation: RawStackAggregationOptions,
        debugValidCountMap: Boolean = false
    ): AlignedRaw16StackResult =
        AlignedRaw16StackProcessor().process(
            rawStack = RawStack(frames),
            alignmentReport = report(frames, transforms),
            options = AlignedRaw16StackOptions(
                minimumAcceptedFrames = 1,
                allowSingleFrameStack = true,
                debugValidCountMapEnabled = debugValidCountMap,
                aggregationOptions = aggregation
            )
        )

    private fun report(
        frames: List<RawFrame>,
        transforms: Map<Int, RawTransform>
    ): LandscapeAlignmentReport {
        val referenceIndex = frames.first().frameIndex
        val frameResults = frames.mapIndexed { position, frame ->
            val transform = transforms[frame.frameIndex] ?: RawTransform.IDENTITY
            val isReference = frame.frameIndex == referenceIndex
            val alignmentResult = AlignmentResult(
                frameIndex = frame.frameIndex,
                mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
                transform = transform,
                confidence = 1.0,
                accepted = true,
                diagnosticMessage = "aggregation self-test",
                rawDx = transform.dx,
                rawDy = transform.dy,
                response = if (isReference) null else 1.0
            )
            LandscapeFrameAlignment(
                framePosition = position,
                frameIndex = frame.frameIndex,
                isReference = isReference,
                proxyTypeUsed = null,
                lumaFallbackUsed = false,
                targetExposureTimeSeconds = frame.exposureTimeSeconds,
                referenceExposureTimeSeconds = frames.first().exposureTimeSeconds,
                phaseResponse = if (isReference) null else 1.0,
                dxRawPixels = transform.dx,
                dyRawPixels = transform.dy,
                overlapFraction = 1.0,
                accepted = true,
                lowConfidence = false,
                rejectionReason = null,
                diagnosticMessage = "aggregation self-test",
                warnings = emptyList(),
                alignmentResult = alignmentResult
            )
        }
        return LandscapeAlignmentReport(
            status = LandscapeAlignmentStatus.SUCCESS,
            success = true,
            partialSuccess = false,
            selectedReferenceFrameIndex = referenceIndex,
            selectedReferencePosition = 0,
            referenceSelectionMethod = ReferenceSelectionMethod.MIDDLE_FRAME_INDEX,
            referenceExposureTimeSeconds = frames.first().exposureTimeSeconds,
            totalFrameCount = frames.size,
            acceptedFrameCount = frames.size,
            rejectedFrameCount = 0,
            lowConfidenceFrameCount = 0,
            frameResults = frameResults,
            alignmentResults = frameResults.map { it.alignmentResult },
            warnings = emptyList(),
            fatalError = null,
            fatalMessage = null,
            processingDurationMs = 0L,
            options = LandscapeAlignmentOptions()
        )
    }

    private fun frame(
        frameIndex: Int,
        cfaPattern: CfaPattern = CfaPattern.RGGB,
        sample: (Int, Int) -> Int
    ): RawFrame {
        val rowStride = WIDTH * 2
        val raw = ByteArray(rowStride * HEIGHT)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val value = sample(x, y).coerceIn(0, 65535)
                val offset = y * rowStride + x * 2
                raw[offset] = (value and 0xFF).toByte()
                raw[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            }
        }
        return RawFrame(
            width = WIDTH,
            height = HEIGHT,
            raw16 = raw,
            rowStride = rowStride,
            pixelStride = 2,
            exposureTimeNs = 10_000_000L,
            iso = 100,
            blackLevelPattern = intArrayOf(64, 64, 64, 64),
            whiteLevel = 65535,
            cfaPattern = cfaPattern,
            cameraId = "0",
            frameIndex = frameIndex
        )
    }

    private fun legacyMeanBytes(frames: List<RawFrame>): ByteArray {
        val accessors = frames.map(Raw16SampleAccessor::create)
        val output = ByteArray(WIDTH * HEIGHT * 2)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                var sum = 0L
                for (accessor in accessors) sum += accessor.sampleAt(x, y).toLong()
                val mean = ((sum + frames.size.toLong() / 2L) / frames.size.toLong()).toInt()
                val offset = (y * WIDTH + x) * 2
                output[offset] = (mean and 0xFF).toByte()
                output[offset + 1] = ((mean ushr 8) and 0xFF).toByte()
            }
        }
        return output
    }

    private fun AlignedRaw16StackResult.outputSamples(): IntArray {
        val bytes = outputRaw16Copy() ?: return IntArray(0)
        return IntArray(bytes.size / 2) { index ->
            val offset = index * 2
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }
    }

    private fun cfaPhaseValue(pattern: CfaPattern, x: Int, y: Int): Int {
        val phase = when (pattern) {
            CfaPattern.RGGB -> (y and 1) * 2 + (x and 1)
            CfaPattern.GRBG -> if ((y and 1) == 0) 1 - (x and 1) else 2 + (x and 1)
            CfaPattern.GBRG -> if ((y and 1) == 0) 2 + (x and 1) else 1 - (x and 1)
            CfaPattern.BGGR -> 3 - ((y and 1) * 2 + (x and 1))
            CfaPattern.MONO,
            CfaPattern.UNKNOWN -> 0
        }
        return 1000 + phase * 1000
    }

    private fun oldMean(samples: IntArray, count: Int): Int {
        var sum = 0L
        for (index in 0 until count) sum += samples[index].toLong()
        return ((sum + count.toLong() / 2L) / count.toLong())
            .coerceIn(0L, 65535L)
            .toInt()
    }

    private fun test(
        name: String,
        block: () -> Boolean
    ): RawStackAggregatorSelfTestCaseResult =
        try {
            if (block()) {
                RawStackAggregatorSelfTestCaseResult(
                    name,
                    PhaseCorrelationSelfTestStatus.PASS,
                    "passed"
                )
            } else {
                RawStackAggregatorSelfTestCaseResult(
                    name,
                    PhaseCorrelationSelfTestStatus.FAIL,
                    "condition returned false"
                )
            }
        } catch (error: Throwable) {
            RawStackAggregatorSelfTestCaseResult(
                name,
                PhaseCorrelationSelfTestStatus.FAIL,
                "threw ${error.javaClass.simpleName}: ${error.message}"
            )
        }

    private const val WIDTH = 6
    private const val HEIGHT = 6
}
