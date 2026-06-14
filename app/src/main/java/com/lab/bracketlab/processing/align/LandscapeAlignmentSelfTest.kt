package com.lab.bracketlab.processing.align

import com.lab.bracketlab.processing.model.AlignmentResult
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.raw.RawProxy
import com.lab.bracketlab.processing.raw.RawProxyType
import kotlin.math.abs
import kotlin.math.roundToInt

data class LandscapeAlignmentSelfTestResult(
    val name: String,
    val status: PhaseCorrelationSelfTestStatus,
    val message: String
)

object LandscapeAlignmentSelfTest {
    fun runAll(): List<LandscapeAlignmentSelfTestResult> {
        val results = mutableListOf<LandscapeAlignmentSelfTestResult>()
        results += runPureContractTests()
        results += when (val load = OpenCvRuntime.ensureLoaded()) {
            OpenCvLoadResult.Success,
            OpenCvLoadResult.AlreadyLoaded -> runOpenCvRuntimeTests()
            is OpenCvLoadResult.Failure ->
                listOf(
                    LandscapeAlignmentSelfTestResult(
                        name = "OpenCV landscape runtime cases",
                        status = PhaseCorrelationSelfTestStatus.SKIPPED,
                        message = "OpenCV native runtime unavailable: ${load.reason} ${load.exceptionMessage.orEmpty()}".trim()
                    )
                )
        }
        return results
    }

    private fun runPureContractTests(): List<LandscapeAlignmentSelfTestResult> {
        val results = mutableListOf<LandscapeAlignmentSelfTestResult>()

        results += check("empty stack failure") {
            val report = LandscapeAlignmentProcessor(FixedBackend()).align(RawStack(emptyList()))
            !report.success &&
                report.fatalError == LandscapeAlignmentRejectionReason.EMPTY_STACK &&
                report.totalFrameCount == 0
        }

        results += check("single-frame identity no backend call") {
            val backend = CountingBackend()
            val frame = rawFrame(frameIndex = 0)
            val report = LandscapeAlignmentProcessor(backend).align(RawStack(listOf(frame)))
            report.success &&
                backend.calls == 0 &&
                report.frameResults.single().isReference &&
                report.frameResults.single().alignmentResult.transform == RawTransform.IDENTITY
        }

        results += check("same-exposure odd stack selects middle frame index") {
            val frames = listOf(rawFrame(0), rawFrame(1), rawFrame(2))
            val selection = ReferenceFrameSelector.select(RawStack(frames))
            selection.selectedFrameIndex == 1 &&
                selection.method == ReferenceSelectionMethod.MIDDLE_FRAME_INDEX
        }

        results += check("same-exposure even stack selects upper middle frame index") {
            val frames = listOf(rawFrame(0), rawFrame(1), rawFrame(2), rawFrame(3))
            val selection = ReferenceFrameSelector.select(RawStack(frames))
            selection.selectedFrameIndex == 2 &&
                selection.method == ReferenceSelectionMethod.MIDDLE_FRAME_INDEX
        }

        results += check("EV-spaced stack selects median exposure") {
            val frames = listOf(
                rawFrame(0, exposureTimeNs = 1_000_000L),
                rawFrame(1, exposureTimeNs = 8_000_000L),
                rawFrame(2, exposureTimeNs = 4_000_000L)
            )
            val selection = ReferenceFrameSelector.select(RawStack(frames))
            selection.selectedFrameIndex == 2 &&
                selection.referenceExposureTimeSeconds == 0.004 &&
                selection.method == ReferenceSelectionMethod.MEDIAN_EXPOSURE_TIME
        }

        results += check("missing exposure metadata falls back to middle order") {
            val frames = listOf(
                rawFrame(0, exposureTimeNs = 1_000_000L),
                rawFrame(1, exposureTimeNs = 0L),
                rawFrame(2, exposureTimeNs = 4_000_000L)
            )
            val selection = ReferenceFrameSelector.select(RawStack(frames))
            selection.selectedFrameIndex == 1 &&
                selection.method == ReferenceSelectionMethod.FALLBACK_MIDDLE_FRAME &&
                selection.warnings.isNotEmpty()
        }

        results += check("excessive shift rejected") {
            val backend = FixedBackend(mapOf(0 to transformResult(0, dx = 20.0, dy = 0.0, response = 0.9)))
            val frames = listOf(rawFrame(1), rawFrame(0), rawFrame(2))
            val report = LandscapeAlignmentProcessor(backend).align(
                RawStack(frames),
                LandscapeAlignmentOptions(
                    proxyMaxDimension = 64,
                    maximumShiftFractionX = 0.20,
                    maximumShiftFractionY = 0.50,
                    minimumOverlapFraction = 0.10
                )
            )
            report.frameResults.first { it.frameIndex == 0 }.rejectionReason ==
                LandscapeAlignmentRejectionReason.EXCESSIVE_SHIFT
        }

        results += check("insufficient overlap rejected") {
            val backend = FixedBackend(mapOf(0 to transformResult(0, dx = 30.0, dy = 0.0, response = 0.9)))
            val frames = listOf(rawFrame(1), rawFrame(0), rawFrame(2))
            val report = LandscapeAlignmentProcessor(backend).align(
                RawStack(frames),
                LandscapeAlignmentOptions(
                    proxyMaxDimension = 64,
                    maximumShiftFractionX = 1.0,
                    maximumShiftFractionY = 1.0,
                    minimumOverlapFraction = 0.80
                )
            )
            report.frameResults.first { it.frameIndex == 0 }.rejectionReason ==
                LandscapeAlignmentRejectionReason.INSUFFICIENT_OVERLAP
        }

        results += check("low phase response warning when non-strict") {
            val backend = FixedBackend(mapOf(0 to transformResult(0, dx = 0.0, dy = 0.0, response = 0.01)))
            val frames = listOf(rawFrame(1), rawFrame(0), rawFrame(2))
            val report = LandscapeAlignmentProcessor(backend).align(
                RawStack(frames),
                LandscapeAlignmentOptions(proxyMaxDimension = 64, minimumPhaseResponse = 0.50, strictResponseRejection = false)
            )
            val result = report.frameResults.first { it.frameIndex == 0 }
            result.accepted && result.lowConfidence && result.warnings.isNotEmpty()
        }

        results += check("low phase response rejected when strict") {
            val backend = FixedBackend(mapOf(0 to transformResult(0, dx = 0.0, dy = 0.0, response = 0.01)))
            val frames = listOf(rawFrame(1), rawFrame(0), rawFrame(2))
            val report = LandscapeAlignmentProcessor(backend).align(
                RawStack(frames),
                LandscapeAlignmentOptions(proxyMaxDimension = 64, minimumPhaseResponse = 0.50, strictResponseRejection = true)
            )
            report.frameResults.first { it.frameIndex == 0 }.rejectionReason ==
                LandscapeAlignmentRejectionReason.LOW_PHASE_RESPONSE
        }

        results += check("one invalid target frame does not block valid targets") {
            val backend = FixedBackend(mapOf(2 to transformResult(2, dx = 0.0, dy = 0.0, response = 0.9)))
            val frames = listOf(
                rawFrame(1),
                rawFrame(0).copy(raw16 = null),
                rawFrame(2)
            )
            val report = LandscapeAlignmentProcessor(backend).align(RawStack(frames))
            report.partialSuccess &&
                report.frameResults.first { it.frameIndex == 0 }.rejectionReason ==
                    LandscapeAlignmentRejectionReason.INVALID_RAW_BUFFER &&
                report.frameResults.first { it.frameIndex == 2 }.accepted
        }

        results += check("reference identity always accepted") {
            val frames = listOf(rawFrame(0), rawFrame(1), rawFrame(2))
            val report = LandscapeAlignmentProcessor(FixedBackend()).align(RawStack(frames))
            val reference = report.frameResults.first { it.isReference }
            reference.accepted &&
                reference.dxRawPixels == 0.0 &&
                reference.dyRawPixels == 0.0 &&
                reference.alignmentResult.transform == RawTransform.IDENTITY
        }

        results += check("result order follows original input order") {
            val frames = listOf(rawFrame(2), rawFrame(0), rawFrame(1))
            val report = LandscapeAlignmentProcessor(FixedBackend()).align(RawStack(frames))
            report.frameResults.map { it.frameIndex } == listOf(2, 0, 1)
        }

        results += check("original RAW buffers remain unchanged") {
            val frames = listOf(rawFrame(0), rawFrame(1), rawFrame(2))
            val before = frames.map { it.raw16!!.copyOf() }
            LandscapeAlignmentProcessor(FixedBackend()).align(RawStack(frames))
            frames.zip(before).all { (frame, copy) -> frame.raw16!!.contentEquals(copy) }
        }

        results += check("luma fallback attempted for low-texture backend rejection") {
            val backend = FallbackBackend()
            val frames = listOf(rawFrame(1), rawFrame(0), rawFrame(2))
            val report = LandscapeAlignmentProcessor(backend).align(
                RawStack(frames),
                LandscapeAlignmentOptions(proxyMaxDimension = 64, allowLumaFallback = true)
            )
            val result = report.frameResults.first { it.frameIndex == 0 }
            result.accepted &&
                result.lumaFallbackUsed &&
                result.proxyTypeUsed == RawProxyType.LUMA_ALIGNMENT &&
                backend.proxyTypes.contains(RawProxyType.GREEN) &&
                backend.proxyTypes.contains(RawProxyType.LUMA_ALIGNMENT)
        }

        return results
    }

    private fun runOpenCvRuntimeTests(): List<LandscapeAlignmentSelfTestResult> {
        val results = mutableListOf<LandscapeAlignmentSelfTestResult>()

        results += check("identity sequence OpenCV runtime") {
            val frames = listOf(rawFrame(0), rawFrame(1), rawFrame(2))
            val report = LandscapeAlignmentProcessor(OpenCvAlignmentBackend()).align(
                RawStack(frames),
                runtimeOptions()
            )
            report.success &&
                report.frameResults.all { it.accepted && nearlyEqual(it.dxRawPixels, 0.0, 1.2) && nearlyEqual(it.dyRawPixels, 0.0, 1.2) }
        }

        results += check("multiple known translations OpenCV runtime") {
            val frames = listOf(
                rawFrame(frameIndex = 0, shiftX = 4, shiftY = 0),
                rawFrame(frameIndex = 1),
                rawFrame(frameIndex = 2, shiftX = -6, shiftY = 4)
            )
            val report = LandscapeAlignmentProcessor(OpenCvAlignmentBackend()).align(
                RawStack(frames),
                runtimeOptions()
            )
            val left = report.frameResults.first { it.frameIndex == 0 }
            val right = report.frameResults.first { it.frameIndex == 2 }
            report.success &&
                left.accepted &&
                right.accepted &&
                nearlyEqual(left.dxRawPixels, -4.0, 1.5) &&
                nearlyEqual(left.dyRawPixels, 0.0, 1.5) &&
                nearlyEqual(right.dxRawPixels, 6.0, 1.5) &&
                nearlyEqual(right.dyRawPixels, -4.0, 1.5)
        }

        results += check("proxy-to-RAW scale OpenCV runtime") {
            val frames = listOf(
                rawFrame(frameIndex = 0, width = 128, height = 96, shiftX = 6, shiftY = -4),
                rawFrame(frameIndex = 1, width = 128, height = 96),
                rawFrame(frameIndex = 2, width = 128, height = 96)
            )
            val report = LandscapeAlignmentProcessor(OpenCvAlignmentBackend()).align(
                RawStack(frames),
                runtimeOptions(proxyMaxDimension = 64)
            )
            val shifted = report.frameResults.first { it.frameIndex == 0 }
            shifted.accepted &&
                nearlyEqual(shifted.dxRawPixels, -6.0, 2.5) &&
                nearlyEqual(shifted.dyRawPixels, 4.0, 2.5)
        }

        results += check("exposure brightness difference OpenCV runtime") {
            val frames = listOf(
                rawFrame(frameIndex = 0, shiftX = 4, shiftY = 2, brightnessScale = 1.6f, exposureTimeNs = 16_000_000L),
                rawFrame(frameIndex = 1, exposureTimeNs = 8_000_000L),
                rawFrame(frameIndex = 2, exposureTimeNs = 8_000_000L)
            )
            val report = LandscapeAlignmentProcessor(OpenCvAlignmentBackend()).align(
                RawStack(frames),
                runtimeOptions()
            )
            val shifted = report.frameResults.first { it.frameIndex == 0 }
            shifted.accepted &&
                nearlyEqual(shifted.dxRawPixels, -4.0, 1.8) &&
                nearlyEqual(shifted.dyRawPixels, -2.0, 1.8)
        }

        return results
    }

    private fun runtimeOptions(proxyMaxDimension: Int = 64): LandscapeAlignmentOptions =
        LandscapeAlignmentOptions(
            proxyMaxDimension = proxyMaxDimension,
            minimumPhaseResponse = 0.0,
            strictResponseRejection = false,
            maximumShiftFractionX = 0.60,
            maximumShiftFractionY = 0.60,
            minimumOverlapFraction = 0.20,
            exposureNormalizeProxies = true,
            allowLumaFallback = true
        )

    private fun rawFrame(
        frameIndex: Int,
        width: Int = 64,
        height: Int = 48,
        exposureTimeNs: Long = 8_000_000L,
        shiftX: Int = 0,
        shiftY: Int = 0,
        brightnessScale: Float = 1.0f
    ): RawFrame {
        val samples = shiftedSamples(width, height, shiftX, shiftY, brightnessScale)
        return RawFrame(
            width = width,
            height = height,
            raw16 = samplesToRaw16(samples),
            rowStride = width * 2,
            pixelStride = 2,
            exposureTimeNs = exposureTimeNs,
            iso = 100,
            cameraId = "0",
            timestampNs = frameIndex.toLong(),
            frameIndex = frameIndex,
            blackLevelPattern = intArrayOf(BLACK_LEVEL, BLACK_LEVEL, BLACK_LEVEL, BLACK_LEVEL),
            whiteLevel = WHITE_LEVEL,
            cfaPattern = CfaPattern.RGGB
        )
    }

    private fun shiftedSamples(
        width: Int,
        height: Int,
        shiftX: Int,
        shiftY: Int,
        brightnessScale: Float
    ): IntArray {
        val output = IntArray(width * height) { BLACK_LEVEL }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val tx = x + shiftX
                val ty = y + shiftY
                if (tx !in 0 until width || ty !in 0 until height) continue
                output[ty * width + tx] = normalizedToRaw(syntheticNormalized(width, height, x, y) * brightnessScale)
            }
        }
        return output
    }

    private fun syntheticNormalized(width: Int, height: Int, x: Int, y: Int): Float {
        var value = 0.08f + (((x * 13 + y * 7) % 31).toFloat() / 620f)
        value = stampValue(value, x, y, 8, 8, 9, 7, 0.92f)
        value = stampValue(value, x, y, width / 3, height / 4, 13, 5, 0.68f)
        value = stampValue(value, x, y, width - 20, height - 16, 7, 9, 0.77f)
        value = stampValue(value, x, y, width / 4, height - 13, 4, 6, 0.48f)
        return value
    }

    private fun stampValue(
        current: Float,
        x: Int,
        y: Int,
        x0: Int,
        y0: Int,
        w: Int,
        h: Int,
        value: Float
    ): Float =
        if (x in x0 until x0 + w && y in y0 until y0 + h) value else current

    private fun normalizedToRaw(value: Float): Int {
        val clamped = value.coerceIn(0f, 1f)
        return (BLACK_LEVEL + clamped * (WHITE_LEVEL - BLACK_LEVEL)).roundToInt()
    }

    private fun samplesToRaw16(samples: IntArray): ByteArray {
        val raw = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val offset = index * 2
            raw[offset] = (sample and 0xFF).toByte()
            raw[offset + 1] = ((sample ushr 8) and 0xFF).toByte()
        }
        return raw
    }

    private fun transformResult(frameIndex: Int, dx: Double, dy: Double, response: Double): AlignmentResult =
        AlignmentResult(
            frameIndex = frameIndex,
            mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
            transform = RawTransform(dx = dx, dy = dy),
            confidence = response,
            accepted = true,
            proxyDx = dx,
            proxyDy = dy,
            rawDx = dx,
            rawDy = dy,
            response = response,
            diagnosticMessage = "Synthetic backend transform."
        )

    private fun rejectedLowTexture(frameIndex: Int): AlignmentResult =
        AlignmentResult(
            frameIndex = frameIndex,
            mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
            confidence = 0.0,
            accepted = false,
            rejectionReason = "LOW_TEXTURE",
            diagnosticMessage = "Target proxy is constant or nearly textureless."
        )

    private fun check(
        name: String,
        predicate: () -> Boolean
    ): LandscapeAlignmentSelfTestResult =
        try {
            val pass = predicate()
            LandscapeAlignmentSelfTestResult(
                name = name,
                status = if (pass) PhaseCorrelationSelfTestStatus.PASS else PhaseCorrelationSelfTestStatus.FAIL,
                message = if (pass) "passed" else "failed"
            )
        } catch (e: Throwable) {
            LandscapeAlignmentSelfTestResult(
                name = name,
                status = PhaseCorrelationSelfTestStatus.FAIL,
                message = "threw ${e::class.java.simpleName}: ${e.message}"
            )
        }

    private fun nearlyEqual(left: Double, right: Double, tolerance: Double): Boolean =
        abs(left - right) <= tolerance

    private class CountingBackend : AlignmentBackend {
        var calls = 0
        override fun estimateTranslation(reference: RawProxy, target: RawProxy): AlignmentResult {
            calls++
            return LandscapeAlignmentSelfTest.transformResult(target.sourceFrameIndex, dx = 0.0, dy = 0.0, response = 1.0)
        }
    }

    private class FixedBackend(
        private val results: Map<Int, AlignmentResult> = emptyMap()
    ) : AlignmentBackend {
        override fun estimateTranslation(reference: RawProxy, target: RawProxy): AlignmentResult =
            results[target.sourceFrameIndex]
                ?: LandscapeAlignmentSelfTest.transformResult(target.sourceFrameIndex, dx = 0.0, dy = 0.0, response = 1.0)
    }

    private class FallbackBackend : AlignmentBackend {
        val proxyTypes = mutableListOf<RawProxyType>()

        override fun estimateTranslation(reference: RawProxy, target: RawProxy): AlignmentResult {
            proxyTypes += target.proxyType
            return if (target.proxyType == RawProxyType.GREEN) {
                LandscapeAlignmentSelfTest.rejectedLowTexture(target.sourceFrameIndex)
            } else {
                LandscapeAlignmentSelfTest.transformResult(target.sourceFrameIndex, dx = 0.0, dy = 0.0, response = 0.9)
            }
        }
    }

    private const val BLACK_LEVEL = 64
    private const val WHITE_LEVEL = 4095
}
