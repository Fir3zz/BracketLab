package com.lab.bracketlab.processing.raw

import com.lab.bracketlab.processing.model.RawFrame
import kotlin.math.abs

object RawProxySelfTest {
    fun run(): List<String> {
        val failures = mutableListOf<String>()

        check("proxy dimensions respect targetMaxSize", failures) {
            val proxy = RawProxyGenerator.extractGreenProxy(
                rawFrame = testFrame(width = 8, height = 4, pattern = CfaPattern.RGGB),
                targetMaxSize = 4
            )
            proxy.width == 4 && proxy.height == 2
        }

        check("proxy data is finite", failures) {
            val proxy = RawProxyGenerator.extractGreenProxy(
                rawFrame = testFrame(width = 4, height = 4, pattern = CfaPattern.RGGB),
                targetMaxSize = 4
            )
            proxy.data.all { it.isFinite() }
        }

        check("original RAW buffer is not modified", failures) {
            val frame = testFrame(width = 4, height = 4, pattern = CfaPattern.RGGB)
            val before = frame.raw16!!.copyOf()
            RawProxyGenerator.extractLumaProxyForAlignment(frame, targetMaxSize = 2)
            before.contentEquals(frame.raw16)
        }

        check("exposure normalization changes brightness in expected direction", failures) {
            val frame = testFrame(
                width = 2,
                height = 2,
                pattern = CfaPattern.RGGB,
                exposureTimeNs = 500_000_000L
            )
            val original = RawProxyGenerator.extractGreenProxy(frame, targetMaxSize = 1)
            val normalized = RawProxyGenerator.extractGreenProxy(
                rawFrame = frame,
                targetMaxSize = 1,
                referenceExposureTimeSeconds = 1.0
            )
            normalized.exposureNormalized &&
                nearlyEqual(normalized.data[0].toDouble(), original.data[0].toDouble() * 2.0)
        }

        check("CFA green extraction respects RGGB", failures) {
            greenProxyForPattern(CfaPattern.RGGB) == EXPECTED_GREEN_PROXY
        }

        check("CFA green extraction respects GRBG", failures) {
            greenProxyForPattern(CfaPattern.GRBG) == EXPECTED_GREEN_PROXY
        }

        check("CFA green extraction respects GBRG", failures) {
            greenProxyForPattern(CfaPattern.GBRG) == EXPECTED_GREEN_PROXY
        }

        check("CFA green extraction respects BGGR", failures) {
            greenProxyForPattern(CfaPattern.BGGR) == EXPECTED_GREEN_PROXY
        }

        check("hot pixel suppression only changes isolated extreme center", failures) {
            val data = FloatArray(9) { 0.1f }
            data[4] = 10f
            val proxy = RawProxy(
                width = 3,
                height = 3,
                data = data,
                scaleX = 1.0,
                scaleY = 1.0,
                sourceFrameIndex = 0,
                exposureNormalized = false,
                proxyType = RawProxyType.GREEN
            )
            val suppressed = RawProxyGenerator.suppressHotPixelsForProxy(proxy)
            nearlyEqual(suppressed.data[4].toDouble(), 0.1) &&
                suppressed.data.filterIndexed { index, _ -> index != 4 }.all { nearlyEqual(it.toDouble(), 0.1) }
        }

        return failures
    }

    private fun greenProxyForPattern(pattern: CfaPattern): Float {
        val frame = testFrame(
            width = 2,
            height = 2,
            pattern = pattern,
            sampleForColor = { color ->
                if (color == CfaColor.GREEN_RED_ROW || color == CfaColor.GREEN_BLUE_ROW) {
                    2_000
                } else {
                    0
                }
            }
        )
        return RawProxyGenerator.extractGreenProxy(frame, targetMaxSize = 1).data[0]
    }

    private fun testFrame(
        width: Int,
        height: Int,
        pattern: CfaPattern,
        exposureTimeNs: Long = 1_000_000_000L,
        sampleForColor: (CfaColor) -> Int = { 1_000 }
    ): RawFrame {
        val rowStride = width * 2
        val raw = ByteArray(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = BayerUtils.colorAt(pattern, x, y)
                val sample = sampleForColor(color).coerceIn(0, 0xFFFF)
                val offset = y * rowStride + x * 2
                raw[offset] = (sample and 0xFF).toByte()
                raw[offset + 1] = ((sample ushr 8) and 0xFF).toByte()
            }
        }
        return RawFrame(
            width = width,
            height = height,
            raw16 = raw,
            rowStride = rowStride,
            pixelStride = 2,
            exposureTimeNs = exposureTimeNs,
            iso = 100,
            frameIndex = 3,
            blackLevelPattern = intArrayOf(0, 0, 0, 0),
            whiteLevel = 4_000,
            cfaPattern = pattern
        )
    }

    private fun check(
        name: String,
        failures: MutableList<String>,
        predicate: () -> Boolean
    ) {
        if (!predicate()) failures += name
    }

    private fun nearlyEqual(left: Double, right: Double): Boolean =
        abs(left - right) < 0.000001

    private const val EXPECTED_GREEN_PROXY = 0.5f
}
