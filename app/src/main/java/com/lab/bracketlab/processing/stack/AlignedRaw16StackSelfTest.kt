package com.lab.bracketlab.processing.stack

import com.lab.bracketlab.processing.align.LandscapeAlignmentOptions
import com.lab.bracketlab.processing.align.LandscapeAlignmentReport
import com.lab.bracketlab.processing.align.LandscapeAlignmentStatus
import com.lab.bracketlab.processing.align.LandscapeFrameAlignment
import com.lab.bracketlab.processing.align.ReferenceSelectionMethod
import com.lab.bracketlab.processing.align.PhaseCorrelationSelfTestStatus
import com.lab.bracketlab.processing.model.AlignmentResult
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import com.lab.bracketlab.processing.raw.CfaPattern
import java.nio.ByteOrder

data class AlignedRaw16StackSelfTestCaseResult(
    val name: String,
    val status: PhaseCorrelationSelfTestStatus,
    val message: String
)

data class AlignedRaw16StackSelfTestReport(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val processingDurationMs: Long,
    val results: List<AlignedRaw16StackSelfTestCaseResult>
)

object AlignedRaw16StackSelfTest {
    fun runAll(): AlignedRaw16StackSelfTestReport {
        val startedNs = System.nanoTime()
        val results = listOf(
            test("one-frame identity allowed") { oneFrameIdentityAllowed() },
            test("multi-frame identity arithmetic mean") { multiFrameIdentityMean() },
            test("positive even X translation sign") { evenXSign(dx = 2) },
            test("negative even X translation sign") { evenXSign(dx = -2) },
            test("positive even Y translation sign") { evenYSign(dy = 2) },
            test("negative even Y translation sign") { evenYSign(dy = -2) },
            test("combined even X/Y translation sign") { combinedEvenSign() },
            test("odd estimated X quantized") { oddEstimatedXQuantized() },
            test("odd estimated Y quantized") { oddEstimatedYQuantized() },
            test("subpixel estimated shift quantized") { subpixelQuantized() },
            test("excessive quantization residual rejects") { excessiveResidualRejects() },
            test("CFA phase preservation RGGB") { cfaPhasePreserved(CfaPattern.RGGB) },
            test("CFA phase preservation GRBG") { cfaPhasePreserved(CfaPattern.GRBG) },
            test("CFA phase preservation GBRG") { cfaPhasePreserved(CfaPattern.GBRG) },
            test("CFA phase preservation BGGR") { cfaPhasePreserved(CfaPattern.BGGR) },
            test("unsafe odd shift is never applied") { unsafeOddShiftNeverApplied() },
            test("border valid counts") { borderValidCounts() },
            test("common-overlap rectangle") { commonOverlapRectangle() },
            test("rejected alignment frame excluded") { rejectedAlignmentFrameExcluded() },
            test("duplicate alignment result rejected") { duplicateAlignmentRejected() },
            test("missing alignment result rejected") { missingAlignmentRejected() },
            test("frame order differs from frameIndex") { frameOrderDiffersFromFrameIndex() },
            test("row-stride padding") { rowStridePaddingRead() },
            test("pixel stride validation") { pixelStrideValidation() },
            test("unsigned values above 32767") { unsignedAbove32767() },
            test("65535 samples remain unsigned") { fullScale65535() },
            test("Long accumulation with many frames") { longAccumulationManyFrames() },
            test("different dimensions rejected") { differentDimensionsRejected() },
            test("different CFA rejected") { differentCfaRejected() },
            test("different known ISO rejected") { differentIsoRejected() },
            test("different known exposure rejected") { differentExposureRejected() },
            test("missing metadata warn policy") { missingMetadataWarnPolicy() },
            test("missing metadata reject policy") { missingMetadataRejectPolicy() },
            test("original input buffers unchanged") { originalBuffersUnchanged() },
            test("alignment report immutable") { alignmentReportImmutable() },
            test("output packing") { outputPacking() },
            test("identity compatibility baseline") { identityCompatibilityBaseline() },
            test("determinism") { determinism() }
        )
        return AlignedRaw16StackSelfTestReport(
            passed = results.count { it.status == PhaseCorrelationSelfTestStatus.PASS },
            failed = results.count { it.status == PhaseCorrelationSelfTestStatus.FAIL },
            skipped = results.count { it.status == PhaseCorrelationSelfTestStatus.SKIPPED },
            processingDurationMs = (System.nanoTime() - startedNs) / 1_000_000L,
            results = results
        )
    }

    private fun oneFrameIdentityAllowed(): Boolean {
        val frame = frame(0) { x, y -> sampleValue(x, y) }
        val result = process(listOf(frame), mapOf(0 to RawTransform.IDENTITY), allowSingle = true)
        return result.success && result.outputSamples().contentEquals(frame.samplesPacked())
    }

    private fun multiFrameIdentityMean(): Boolean {
        val first = frame(0) { x, y -> 100 + x + y * 10 }
        val second = frame(1) { x, y -> 200 + x + y * 10 }
        val result = process(listOf(first, second), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform.IDENTITY))
        val expected = IntArray(first.width * first.height) { index ->
            val x = index % first.width
            val y = index / first.width
            roundedMean(listOf(100 + x + y * 10, 200 + x + y * 10))
        }
        return result.success && result.outputSamples().contentEquals(expected)
    }

    private fun evenXSign(dx: Int): Boolean {
        val ref = frame(0) { x, y -> 1000 + x + y * 100 }
        val target = frame(1) { x, y -> 2000 + x + y * 100 }
        val result = process(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform(dx = dx.toDouble())))
        val x = if (dx > 0) 4 else 1
        val y = 2
        val output = result.outputSamples()[y * ref.width + x]
        val expected = roundedMean(listOf(1000 + x + y * 100, 2000 + (x - dx) + y * 100))
        return result.success && output == expected
    }

    private fun evenYSign(dy: Int): Boolean {
        val ref = frame(0) { x, y -> 1000 + x + y * 100 }
        val target = frame(1) { x, y -> 2000 + x + y * 100 }
        val result = process(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform(dy = dy.toDouble())))
        val x = 3
        val y = if (dy > 0) 4 else 1
        val output = result.outputSamples()[y * ref.width + x]
        val expected = roundedMean(listOf(1000 + x + y * 100, 2000 + x + (y - dy) * 100))
        return result.success && output == expected
    }

    private fun combinedEvenSign(): Boolean {
        val ref = frame(0) { x, y -> 1000 + x + y * 100 }
        val target = frame(1) { x, y -> 2000 + x + y * 100 }
        val result = process(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform(dx = 2.0, dy = -2.0)))
        val x = 4
        val y = 1
        val expected = roundedMean(listOf(1000 + x + y * 100, 2000 + (x - 2) + (y + 2) * 100))
        return result.success && result.outputSamples()[y * ref.width + x] == expected
    }

    private fun oddEstimatedXQuantized(): Boolean {
        val result = basicQuantizationResult(dx = 3.0, dy = 0.0)
        val translation = result.appliedTranslations.first { it.frameIndex == 1 }
        return result.success &&
            translation.estimatedDxRaw == 3.0 &&
            translation.appliedDxRaw == 4 &&
            translation.residualDxRaw == -1.0 &&
            translation.quantizationChanged
    }

    private fun oddEstimatedYQuantized(): Boolean {
        val result = basicQuantizationResult(dx = 0.0, dy = -3.0)
        val translation = result.appliedTranslations.first { it.frameIndex == 1 }
        return result.success &&
            translation.estimatedDyRaw == -3.0 &&
            translation.appliedDyRaw == -4 &&
            translation.residualDyRaw == 1.0 &&
            translation.quantizationChanged
    }

    private fun subpixelQuantized(): Boolean {
        val result = basicQuantizationResult(dx = 1.2, dy = -2.6)
        val translation = result.appliedTranslations.first { it.frameIndex == 1 }
        return result.success &&
            translation.appliedDxRaw == 2 &&
            translation.appliedDyRaw == -2 &&
            translation.residualDxRaw == -0.8 &&
            close(translation.residualDyRaw, -0.6, 0.000001)
    }

    private fun excessiveResidualRejects(): Boolean {
        val ref = frame(0) { x, y -> sampleValue(x, y) }
        val target = frame(1) { x, y -> sampleValue(x, y) }
        val report = report(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform(dx = 1.0)))
        val result = AlignedRaw16StackProcessor().process(
            RawStack(listOf(ref, target)),
            report,
            options(rejectExcessiveQuantizationResidual = true, maximumQuantizationResidualPixels = 0.5)
        )
        return !result.success && result.fatalError == AlignedRaw16StackFailureCode.EXCESSIVE_QUANTIZATION_RESIDUAL
    }

    private fun cfaPhasePreserved(pattern: CfaPattern): Boolean {
        val ref = frame(0, width = 8, height = 8, cfaPattern = pattern) { x, y -> phaseValue(pattern, x, y) }
        val target = frame(1, width = 8, height = 8, cfaPattern = pattern) { x, y -> phaseValue(pattern, x, y) + 20 }
        val result = process(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform(dx = 2.0, dy = 2.0)))
        if (!result.success) return false
        val samples = result.outputSamples()
        for (y in 2 until 8) {
            for (x in 2 until 8) {
                val phaseBase = phaseValue(pattern, x, y)
                val value = samples[y * 8 + x]
                if (value !in phaseBase..phaseBase + 20) return false
            }
        }
        return true
    }

    private fun unsafeOddShiftNeverApplied(): Boolean {
        val result = basicQuantizationResult(dx = -1.0, dy = 1.0)
        val translation = result.appliedTranslations.first { it.frameIndex == 1 }
        return translation.appliedDxRaw % 2 == 0 && translation.appliedDyRaw % 2 == 0
    }

    private fun borderValidCounts(): Boolean {
        val ref = frame(0) { x, y -> sampleValue(x, y) }
        val target = frame(1) { x, y -> sampleValue(x, y) }
        val result = process(
            listOf(ref, target),
            mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform(dx = 2.0)),
            debugMap = true
        )
        val map = result.validCountMap ?: return false
        return result.success &&
            map[0] == 1 &&
            map[2] == 2 &&
            result.singleContributorPixelCount == ref.height * 2 &&
            result.fullContributorPixelCount == ref.height * (ref.width - 2)
    }

    private fun commonOverlapRectangle(): Boolean {
        val ref = frame(0, width = 8, height = 8) { x, y -> sampleValue(x, y) }
        val a = frame(1, width = 8, height = 8) { x, y -> sampleValue(x, y) }
        val b = frame(2, width = 8, height = 8) { x, y -> sampleValue(x, y) }
        val result = process(
            listOf(ref, a, b),
            mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform(dx = 2.0), 2 to RawTransform(dy = -2.0))
        )
        return result.commonOverlapRect == CommonOverlapRect(left = 2, top = 0, rightExclusive = 8, bottomExclusive = 6)
    }

    private fun rejectedAlignmentFrameExcluded(): Boolean {
        val ref = frame(0) { _, _ -> 100 }
        val target = frame(1) { _, _ -> 900 }
        val result = AlignedRaw16StackProcessor().process(
            RawStack(listOf(ref, target)),
            report(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY), rejected = setOf(1)),
            options(allowSingleFrameStack = true, minimumAcceptedFrames = 1)
        )
        return result.success && result.outputSamples().all { it == 100 } && result.rejectedFrameCount == 1
    }

    private fun duplicateAlignmentRejected(): Boolean {
        val ref = frame(0) { x, y -> sampleValue(x, y) }
        val target = frame(1) { x, y -> sampleValue(x, y) }
        val base = report(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform.IDENTITY))
        val duplicate = base.copy(frameResults = base.frameResults + base.frameResults.last())
        val result = AlignedRaw16StackProcessor().process(RawStack(listOf(ref, target)), duplicate, options())
        return !result.success && result.fatalError == AlignedRaw16StackFailureCode.DUPLICATE_FRAME_INDEX
    }

    private fun missingAlignmentRejected(): Boolean {
        val ref = frame(0) { x, y -> sampleValue(x, y) }
        val target = frame(1) { x, y -> sampleValue(x, y) }
        val result = AlignedRaw16StackProcessor().process(
            RawStack(listOf(ref, target)),
            report(listOf(ref), mapOf(0 to RawTransform.IDENTITY)),
            options()
        )
        return !result.success && result.fatalError == AlignedRaw16StackFailureCode.MISSING_ALIGNMENT_RESULT
    }

    private fun frameOrderDiffersFromFrameIndex(): Boolean {
        val a = frame(2) { _, _ -> 200 }
        val ref = frame(0) { _, _ -> 100 }
        val b = frame(1) { _, _ -> 300 }
        val result = process(listOf(a, ref, b), mapOf(2 to RawTransform.IDENTITY, 0 to RawTransform.IDENTITY, 1 to RawTransform.IDENTITY), referenceIndex = 0)
        return result.success && result.outputSamples().all { it == 200 }
    }

    private fun rowStridePaddingRead(): Boolean {
        val ref = frame(0, width = 5, height = 4, rowPadding = 6) { x, y -> 100 + x + y * 10 }
        val result = process(listOf(ref), mapOf(0 to RawTransform.IDENTITY), allowSingle = true)
        return result.success && result.outputSamples().contentEquals(ref.samplesPacked())
    }

    private fun pixelStrideValidation(): Boolean {
        val ref = frame(0, pixelStride = 1) { x, y -> sampleValue(x, y) }
        val result = process(listOf(ref), mapOf(0 to RawTransform.IDENTITY), allowSingle = true)
        return !result.success && result.fatalError == AlignedRaw16StackFailureCode.INVALID_PIXEL_STRIDE
    }

    private fun unsignedAbove32767(): Boolean {
        val ref = frame(0) { _, _ -> 40000 }
        val result = process(listOf(ref), mapOf(0 to RawTransform.IDENTITY), allowSingle = true)
        return result.success && result.outputSamples().all { it == 40000 }
    }

    private fun fullScale65535(): Boolean {
        val ref = frame(0) { _, _ -> 65535 }
        val result = process(listOf(ref), mapOf(0 to RawTransform.IDENTITY), allowSingle = true)
        return result.success && result.outputSamples().all { it == 65535 }
    }

    private fun longAccumulationManyFrames(): Boolean {
        val frames = (0 until 80).map { index -> frame(index) { _, _ -> 65535 } }
        val transforms = frames.associate { it.frameIndex to RawTransform.IDENTITY }
        val result = process(frames, transforms, referenceIndex = 0)
        return result.success && result.outputSamples().all { it == 65535 }
    }

    private fun differentDimensionsRejected(): Boolean {
        val ref = frame(0, width = 6, height = 6) { x, y -> sampleValue(x, y) }
        val target = frame(1, width = 8, height = 6) { x, y -> sampleValue(x, y) }
        val result = process(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform.IDENTITY))
        return !result.success && result.fatalError == AlignedRaw16StackFailureCode.INCOMPATIBLE_DIMENSIONS
    }

    private fun differentCfaRejected(): Boolean {
        val ref = frame(0, cfaPattern = CfaPattern.RGGB) { x, y -> sampleValue(x, y) }
        val target = frame(1, cfaPattern = CfaPattern.BGGR) { x, y -> sampleValue(x, y) }
        val result = process(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform.IDENTITY))
        return !result.success && result.fatalError == AlignedRaw16StackFailureCode.INCOMPATIBLE_CFA
    }

    private fun differentIsoRejected(): Boolean {
        val ref = frame(0, iso = 100) { x, y -> sampleValue(x, y) }
        val target = frame(1, iso = 200) { x, y -> sampleValue(x, y) }
        val result = process(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform.IDENTITY))
        return !result.success && result.fatalError == AlignedRaw16StackFailureCode.INCOMPATIBLE_ISO
    }

    private fun differentExposureRejected(): Boolean {
        val ref = frame(0, exposureTimeNs = 10_000_000L) { x, y -> sampleValue(x, y) }
        val target = frame(1, exposureTimeNs = 20_000_000L) { x, y -> sampleValue(x, y) }
        val result = process(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform.IDENTITY))
        return !result.success && result.fatalError == AlignedRaw16StackFailureCode.INCOMPATIBLE_EXPOSURE
    }

    private fun missingMetadataWarnPolicy(): Boolean {
        val ref = frame(0, cfaPattern = null) { x, y -> sampleValue(x, y) }
        val target = frame(1, cfaPattern = null) { x, y -> sampleValue(x, y) }
        val result = AlignedRaw16StackProcessor().process(
            RawStack(listOf(ref, target)),
            report(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform.IDENTITY)),
            options(missingMetadataPolicy = MissingMetadataPolicy.WARN_AND_CONTINUE, requireSameCfaPattern = true)
        )
        return result.success && result.warnings.any { it.code == AlignedRaw16StackWarningCode.MISSING_CFA_METADATA }
    }

    private fun missingMetadataRejectPolicy(): Boolean {
        val ref = frame(0, cfaPattern = null) { x, y -> sampleValue(x, y) }
        val target = frame(1, cfaPattern = null) { x, y -> sampleValue(x, y) }
        val result = AlignedRaw16StackProcessor().process(
            RawStack(listOf(ref, target)),
            report(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform.IDENTITY)),
            options(missingMetadataPolicy = MissingMetadataPolicy.REJECT, requireSameCfaPattern = true)
        )
        return !result.success && result.fatalError == AlignedRaw16StackFailureCode.INCOMPATIBLE_CFA
    }

    private fun originalBuffersUnchanged(): Boolean {
        val frames = listOf(frame(0) { x, y -> sampleValue(x, y) }, frame(1) { x, y -> sampleValue(x, y) + 10 })
        val before = frames.map { it.raw16!!.copyOf() }
        process(frames, mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform(dx = 2.0)))
        return frames.zip(before).all { (frame, copy) -> frame.raw16!!.contentEquals(copy) }
    }

    private fun alignmentReportImmutable(): Boolean {
        val frames = listOf(frame(0) { x, y -> sampleValue(x, y) }, frame(1) { x, y -> sampleValue(x, y) + 10 })
        val alignmentReport = report(frames, mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform(dx = 2.0)))
        val before = alignmentReport.frameResults.map { it.frameIndex to it.alignmentResult.transform }
        process(frames, mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform(dx = 2.0)))
        val after = alignmentReport.frameResults.map { it.frameIndex to it.alignmentResult.transform }
        return before == after
    }

    private fun outputPacking(): Boolean {
        val ref = frame(0, width = 5, height = 3) { x, y -> sampleValue(x, y) }
        val result = process(listOf(ref), mapOf(0 to RawTransform.IDENTITY), allowSingle = true)
        val buffer = result.outputRaw16 ?: return false
        return result.success &&
            result.outputByteOrder == ByteOrder.LITTLE_ENDIAN &&
            result.outputPixelStride == 2 &&
            result.outputRowStride == 10 &&
            buffer.isReadOnly &&
            buffer.position() == 0 &&
            buffer.limit() == 5 * 3 * 2
    }

    private fun identityCompatibilityBaseline(): Boolean {
        val frames = listOf(
            frame(0) { x, y -> 100 + x + y * 10 },
            frame(1) { x, y -> 200 + x + y * 10 },
            frame(2) { x, y -> 300 + x + y * 10 }
        )
        val result = process(frames, frames.associate { it.frameIndex to RawTransform.IDENTITY }, referenceIndex = 0)
        val expected = straightforwardMean(frames)
        return result.success && result.outputSamples().contentEquals(expected)
    }

    private fun determinism(): Boolean {
        val frames = listOf(frame(0) { x, y -> sampleValue(x, y) }, frame(1) { x, y -> sampleValue(x, y) + 7 })
        val transforms = mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform(dx = 2.0))
        val first = process(frames, transforms).outputRaw16Copy()
        val second = process(frames, transforms).outputRaw16Copy()
        return first != null && second != null && first.contentEquals(second)
    }

    private fun basicQuantizationResult(dx: Double, dy: Double): AlignedRaw16StackResult {
        val ref = frame(0) { x, y -> sampleValue(x, y) }
        val target = frame(1) { x, y -> sampleValue(x, y) }
        return process(listOf(ref, target), mapOf(0 to RawTransform.IDENTITY, 1 to RawTransform(dx = dx, dy = dy)))
    }

    private fun process(
        frames: List<RawFrame>,
        transforms: Map<Int, RawTransform>,
        referenceIndex: Int = frames.first().frameIndex,
        allowSingle: Boolean = false,
        debugMap: Boolean = false
    ): AlignedRaw16StackResult =
        AlignedRaw16StackProcessor().process(
            RawStack(frames),
            report(frames, transforms, referenceIndex = referenceIndex),
            options(allowSingleFrameStack = allowSingle, minimumAcceptedFrames = if (allowSingle) 1 else 2, debugValidCountMapEnabled = debugMap)
        )

    private fun report(
        frames: List<RawFrame>,
        transforms: Map<Int, RawTransform>,
        referenceIndex: Int = frames.firstOrNull()?.frameIndex ?: -1,
        rejected: Set<Int> = emptySet()
    ): LandscapeAlignmentReport {
        val frameResults = frames.mapIndexed { position, frame ->
            val transform = transforms[frame.frameIndex] ?: RawTransform.IDENTITY
            val accepted = frame.frameIndex !in rejected
            val alignmentResult = AlignmentResult(
                frameIndex = frame.frameIndex,
                mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
                transform = transform,
                confidence = if (accepted) 1.0 else 0.0,
                accepted = accepted,
                rejectionReason = if (accepted) null else "TEST_REJECTED",
                diagnosticMessage = if (accepted) "test accepted" else "test rejected",
                rawDx = transform.dx,
                rawDy = transform.dy,
                response = if (accepted) 1.0 else 0.0
            )
            LandscapeFrameAlignment(
                framePosition = position,
                frameIndex = frame.frameIndex,
                isReference = frame.frameIndex == referenceIndex,
                proxyTypeUsed = null,
                lumaFallbackUsed = false,
                targetExposureTimeSeconds = frame.exposureTimeSeconds,
                referenceExposureTimeSeconds = frames.firstOrNull { it.frameIndex == referenceIndex }?.exposureTimeSeconds,
                phaseResponse = if (accepted) 1.0 else 0.0,
                dxRawPixels = transform.dx,
                dyRawPixels = transform.dy,
                overlapFraction = 1.0,
                accepted = accepted,
                lowConfidence = false,
                rejectionReason = null,
                diagnosticMessage = null,
                warnings = emptyList(),
                alignmentResult = alignmentResult
            )
        }
        return LandscapeAlignmentReport(
            status = LandscapeAlignmentStatus.SUCCESS,
            success = true,
            partialSuccess = false,
            selectedReferenceFrameIndex = referenceIndex,
            selectedReferencePosition = frames.indexOfFirst { it.frameIndex == referenceIndex },
            referenceSelectionMethod = ReferenceSelectionMethod.MIDDLE_FRAME_INDEX,
            referenceExposureTimeSeconds = frames.firstOrNull { it.frameIndex == referenceIndex }?.exposureTimeSeconds,
            totalFrameCount = frames.size,
            acceptedFrameCount = frameResults.count { it.accepted },
            rejectedFrameCount = frameResults.count { !it.accepted },
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

    private fun options(
        allowSingleFrameStack: Boolean = false,
        minimumAcceptedFrames: Int = 2,
        debugValidCountMapEnabled: Boolean = false,
        rejectExcessiveQuantizationResidual: Boolean = false,
        maximumQuantizationResidualPixels: Double = 1.5,
        missingMetadataPolicy: MissingMetadataPolicy = MissingMetadataPolicy.WARN_AND_CONTINUE,
        requireSameCfaPattern: Boolean = true
    ): AlignedRaw16StackOptions =
        AlignedRaw16StackOptions(
            allowSingleFrameStack = allowSingleFrameStack,
            minimumAcceptedFrames = minimumAcceptedFrames,
            debugValidCountMapEnabled = debugValidCountMapEnabled,
            rejectExcessiveQuantizationResidual = rejectExcessiveQuantizationResidual,
            maximumQuantizationResidualPixels = maximumQuantizationResidualPixels,
            missingMetadataPolicy = missingMetadataPolicy,
            requireSameCfaPattern = requireSameCfaPattern
        )

    private fun frame(
        frameIndex: Int,
        width: Int = 6,
        height: Int = 6,
        rowPadding: Int = 0,
        pixelStride: Int = 2,
        iso: Int = 100,
        exposureTimeNs: Long = 10_000_000L,
        cfaPattern: CfaPattern? = CfaPattern.RGGB,
        cameraId: String? = "0",
        sample: (Int, Int) -> Int
    ): RawFrame {
        val rowStride = (width - 1) * pixelStride + 2 + rowPadding
        val raw = ByteArray(rowStride * height) { 0x55.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = sample(x, y).coerceIn(0, 65535)
                val offset = y * rowStride + x * pixelStride
                raw[offset] = (value and 0xFF).toByte()
                if (pixelStride >= 2) raw[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            }
        }
        return RawFrame(
            width = width,
            height = height,
            raw16 = raw,
            rowStride = rowStride,
            pixelStride = pixelStride,
            exposureTimeNs = exposureTimeNs,
            iso = iso,
            cameraId = cameraId,
            frameIndex = frameIndex,
            blackLevelPattern = intArrayOf(64, 64, 64, 64),
            whiteLevel = 65535,
            cfaPattern = cfaPattern
        )
    }

    private fun RawFrame.samplesPacked(): IntArray {
        val accessor = Raw16SampleAccessor.create(this)
        return IntArray(width * height) { index ->
            accessor.sampleAt(index % width, index / width)
        }
    }

    private fun AlignedRaw16StackResult.outputSamples(): IntArray {
        val bytes = outputRaw16Copy() ?: return IntArray(0)
        return IntArray(bytes.size / 2) { index ->
            val offset = index * 2
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }
    }

    private fun straightforwardMean(frames: List<RawFrame>): IntArray {
        val accessors = frames.map { Raw16SampleAccessor.create(it) }
        val first = frames.first()
        return IntArray(first.width * first.height) { index ->
            val x = index % first.width
            val y = index / first.width
            roundedMean(accessors.map { it.sampleAt(x, y) })
        }
    }

    private fun phaseValue(pattern: CfaPattern, x: Int, y: Int): Int {
        val row = y and 1
        val col = x and 1
        val phase = when (pattern) {
            CfaPattern.RGGB -> row * 2 + col
            CfaPattern.GRBG -> if (row == 0) 1 - col else 2 + col
            CfaPattern.GBRG -> if (row == 0) 2 + col else 1 - col
            CfaPattern.BGGR -> 3 - (row * 2 + col)
            CfaPattern.MONO -> 0
            CfaPattern.UNKNOWN -> 0
        }
        return 1000 + phase * 1000
    }

    private fun sampleValue(x: Int, y: Int): Int = 100 + x + y * 10

    private fun roundedMean(values: List<Int>): Int {
        val sum = values.fold(0L) { acc, value -> acc + value.toLong() }
        return ((sum + values.size.toLong() / 2L) / values.size.toLong()).toInt()
    }

    private fun close(left: Double, right: Double, tolerance: Double): Boolean =
        kotlin.math.abs(left - right) <= tolerance

    private fun test(name: String, block: () -> Boolean): AlignedRaw16StackSelfTestCaseResult =
        try {
            val passed = block()
            AlignedRaw16StackSelfTestCaseResult(
                name = name,
                status = if (passed) PhaseCorrelationSelfTestStatus.PASS else PhaseCorrelationSelfTestStatus.FAIL,
                message = if (passed) "passed" else "failed"
            )
        } catch (e: Throwable) {
            AlignedRaw16StackSelfTestCaseResult(
                name = name,
                status = PhaseCorrelationSelfTestStatus.FAIL,
                message = "threw ${e::class.java.simpleName}: ${e.message}"
            )
        }
}
