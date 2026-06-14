package com.lab.bracketlab.processing.align

import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.raw.RawProxy
import com.lab.bracketlab.processing.raw.RawProxyType
import kotlin.math.abs

enum class PhaseCorrelationSelfTestStatus {
    PASS,
    FAIL,
    SKIPPED
}

data class PhaseCorrelationSelfTestResult(
    val name: String,
    val status: PhaseCorrelationSelfTestStatus,
    val message: String
)

object PhaseCorrelationSelfTest {
    fun runPureKotlinContractTests(): List<PhaseCorrelationSelfTestResult> {
        val results = mutableListOf<PhaseCorrelationSelfTestResult>()
        val backend = OpenCvAlignmentBackend()

        results += check("proxy scale conversion") {
            val proxy = proxy(width = 4, height = 4, scaleX = 3.0, scaleY = 2.0)
            nearlyEqual(proxy.proxyDxToRawDx(2.0), 6.0) &&
                nearlyEqual(proxy.proxyDyToRawDy(-2.0), -4.0)
        }

        results += check("identity transform convention") {
            val identity = RawTransform.IDENTITY
            nearlyEqual(identity.dx, 0.0) &&
                nearlyEqual(identity.dy, 0.0) &&
                nearlyEqual(identity.scale, 1.0)
        }

        results += check("OpenCV shift conversion uses target-to-reference convention") {
            val target = proxy(width = 4, height = 4, scaleX = 4.0, scaleY = 2.0)
            val transform = backend.opencvShiftToTargetToReferenceTransform(
                target = target,
                openCvDxProxy = 1.5,
                openCvDyProxy = -2.0
            )
            nearlyEqual(transform.dx, -6.0) && nearlyEqual(transform.dy, 4.0)
        }

        results += check("unequal dimensions are rejected before OpenCV runtime") {
            val result = backend.estimateTranslation(
                reference = proxy(width = 4, height = 4),
                target = proxy(width = 5, height = 4)
            )
            !result.accepted && result.rejectionReason?.contains("dimensions differ") == true
        }

        return results
    }

    fun runOpenCvRuntimeTests(): List<PhaseCorrelationSelfTestResult> {
        return when (val load = OpenCvRuntime.ensureLoaded()) {
            OpenCvLoadResult.Success,
            OpenCvLoadResult.AlreadyLoaded -> runOpenCvCases()
            is OpenCvLoadResult.Failure ->
                listOf(
                    PhaseCorrelationSelfTestResult(
                        name = "OpenCV runtime cases",
                        status = PhaseCorrelationSelfTestStatus.SKIPPED,
                        message = "OpenCV native runtime unavailable: ${load.reason} ${load.exceptionMessage.orEmpty()}".trim()
                    )
                )
        }
    }

    private fun runOpenCvCases(): List<PhaseCorrelationSelfTestResult> {
        val results = mutableListOf<PhaseCorrelationSelfTestResult>()
        val backend = OpenCvAlignmentBackend()
        val reference = syntheticReference()

        results += runtimeCase(
            name = "identity",
            backend = backend,
            reference = reference,
            target = reference.copy(data = reference.data.copyOf(), sourceFrameIndex = 1),
            expectedRawDx = 0.0,
            expectedRawDy = 0.0
        )

        results += shiftedRuntimeCase("positive X translation", backend, reference, shiftX = 4, shiftY = 0)
        results += shiftedRuntimeCase("negative X translation", backend, reference, shiftX = -4, shiftY = 0)
        results += shiftedRuntimeCase("positive Y translation", backend, reference, shiftX = 0, shiftY = 3)
        results += shiftedRuntimeCase("negative Y translation", backend, reference, shiftX = 0, shiftY = -3)
        results += shiftedRuntimeCase("combined X/Y translation", backend, reference, shiftX = 5, shiftY = -3)

        results += run {
            val scaledReference = reference.copy(scaleX = 2.0, scaleY = 3.0)
            val target = shiftedProxy(scaledReference, shiftX = 2, shiftY = -2, sourceFrameIndex = 8)
            runtimeCase(
                name = "proxy scaling",
                backend = backend,
                reference = scaledReference,
                target = target,
                expectedRawDx = -4.0,
                expectedRawDy = 6.0
            )
        }

        results += run {
            val target = shiftedProxy(reference, shiftX = 3, shiftY = 2, sourceFrameIndex = 9)
            val brighter = target.copy(data = target.data.map { it * 3f }.toFloatArray())
            runtimeCase(
                name = "brightness scaling",
                backend = backend,
                reference = reference,
                target = brighter,
                expectedRawDx = -3.0,
                expectedRawDy = -2.0
            )
        }

        results += check("constant image rejection") {
            val constant = RawProxy(
                width = reference.width,
                height = reference.height,
                data = FloatArray(reference.width * reference.height) { 0.25f },
                scaleX = reference.scaleX,
                scaleY = reference.scaleY,
                sourceFrameIndex = 10,
                exposureNormalized = false,
                proxyType = RawProxyType.GREEN
            )
            val result = backend.estimateTranslation(reference, constant)
            !result.accepted && result.rejectionReason?.contains("textureless") == true
        }

        results += check("unequal dimension rejection") {
            val result = backend.estimateTranslation(reference, proxy(width = 31, height = 32))
            !result.accepted && result.rejectionReason?.contains("dimensions differ") == true
        }

        return results
    }

    private fun shiftedRuntimeCase(
        name: String,
        backend: OpenCvAlignmentBackend,
        reference: RawProxy,
        shiftX: Int,
        shiftY: Int
    ): PhaseCorrelationSelfTestResult {
        val target = shiftedProxy(reference, shiftX, shiftY, sourceFrameIndex = 2)
        return runtimeCase(
            name = name,
            backend = backend,
            reference = reference,
            target = target,
            expectedRawDx = -shiftX.toDouble(),
            expectedRawDy = -shiftY.toDouble()
        )
    }

    private fun runtimeCase(
        name: String,
        backend: OpenCvAlignmentBackend,
        reference: RawProxy,
        target: RawProxy,
        expectedRawDx: Double,
        expectedRawDy: Double
    ): PhaseCorrelationSelfTestResult {
        val result = backend.estimateTranslation(reference, target)
        val pass =
            result.accepted &&
                nearlyEqual(result.transform.dx, expectedRawDx, tolerance = 1.0) &&
                nearlyEqual(result.transform.dy, expectedRawDy, tolerance = 1.0)
        return PhaseCorrelationSelfTestResult(
            name = name,
            status = if (pass) PhaseCorrelationSelfTestStatus.PASS else PhaseCorrelationSelfTestStatus.FAIL,
            message =
                "target content shift expected target->reference=($expectedRawDx,$expectedRawDy), " +
                    "opencvProxy=(${result.proxyDx},${result.proxyDy}), " +
                    "projectRaw=(${result.rawDx},${result.rawDy}), response=${result.response}, " +
                    "accepted=${result.accepted}, rejection=${result.rejectionReason}"
        )
    }

    private fun syntheticReference(): RawProxy {
        val width = 64
        val height = 48
        val data = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            (((x * 13 + y * 7) % 31).toFloat() / 310f)
        }
        stamp(data, width, x0 = 10, y0 = 9, w = 5, h = 8, value = 0.90f)
        stamp(data, width, x0 = 24, y0 = 13, w = 11, h = 4, value = 0.65f)
        stamp(data, width, x0 = 44, y0 = 31, w = 6, h = 7, value = 0.78f)
        stamp(data, width, x0 = 17, y0 = 34, w = 3, h = 5, value = 0.45f)
        return RawProxy(
            width = width,
            height = height,
            data = data,
            scaleX = 1.0,
            scaleY = 1.0,
            sourceFrameIndex = 0,
            exposureNormalized = false,
            proxyType = RawProxyType.GREEN,
            notes = "Synthetic asymmetric non-periodic reference proxy."
        )
    }

    private fun shiftedProxy(
        reference: RawProxy,
        shiftX: Int,
        shiftY: Int,
        sourceFrameIndex: Int
    ): RawProxy {
        val shifted = FloatArray(reference.data.size)
        for (y in 0 until reference.height) {
            for (x in 0 until reference.width) {
                val tx = x + shiftX
                val ty = y + shiftY
                if (tx in 0 until reference.width && ty in 0 until reference.height) {
                    shifted[ty * reference.width + tx] = reference.data[y * reference.width + x]
                }
            }
        }
        return reference.copy(data = shifted, sourceFrameIndex = sourceFrameIndex)
    }

    private fun proxy(width: Int, height: Int, scaleX: Double = 1.0, scaleY: Double = 1.0): RawProxy {
        val data = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            ((x + y * 3) % 11).toFloat() / 10f
        }
        return RawProxy(
            width = width,
            height = height,
            data = data,
            scaleX = scaleX,
            scaleY = scaleY,
            sourceFrameIndex = 1,
            exposureNormalized = false,
            proxyType = RawProxyType.GREEN
        )
    }

    private fun stamp(data: FloatArray, width: Int, x0: Int, y0: Int, w: Int, h: Int, value: Float) {
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) {
                data[y * width + x] = value
            }
        }
    }

    private fun check(
        name: String,
        predicate: () -> Boolean
    ): PhaseCorrelationSelfTestResult {
        val pass = predicate()
        return PhaseCorrelationSelfTestResult(
            name = name,
            status = if (pass) PhaseCorrelationSelfTestStatus.PASS else PhaseCorrelationSelfTestStatus.FAIL,
            message = if (pass) "passed" else "failed"
        )
    }

    private fun nearlyEqual(left: Double, right: Double, tolerance: Double = 0.000001): Boolean =
        abs(left - right) <= tolerance
}
