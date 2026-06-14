package com.lab.bracketlab.processing.raw

import kotlin.math.abs

object RawPrimitiveSelfTest {
    fun run(): List<String> {
        val failures = mutableListOf<String>()

        check("blackSubtract clamps below black", failures) {
            RawMath.blackSubtract(rawSample = 500, blackLevel = 600) == 0
        }

        check("normalizeRaw returns linear normalized value", failures) {
            nearlyEqual(
                RawMath.normalizeRaw(rawSample = 1500, blackLevel = 500, whiteLevel = 2500),
                0.5
            )
        }

        check("isSaturated respects threshold", failures) {
            RawMath.isSaturated(rawSample = 980, whiteLevel = 1000, threshold = 0.98) &&
                !RawMath.isSaturated(rawSample = 979, whiteLevel = 1000, threshold = 0.98)
        }

        check("isNearNoiseFloor works above and below threshold", failures) {
            RawMath.isNearNoiseFloor(rawSample = 520, blackLevel = 500, threshold = 20) &&
                !RawMath.isNearNoiseFloor(rawSample = 521, blackLevel = 500, threshold = 20)
        }

        check("radianceFromRaw scales with exposure time", failures) {
            val shortExposure = RawMath.radianceFromRaw(
                rawSample = 1500,
                blackLevel = 500,
                whiteLevel = 2500,
                exposureTimeSeconds = 0.5
            )
            val longExposure = RawMath.radianceFromRaw(
                rawSample = 1500,
                blackLevel = 500,
                whiteLevel = 2500,
                exposureTimeSeconds = 1.0
            )
            nearlyEqual(shortExposure, longExposure * 2.0)
        }

        check("CFA color mapping returns expected RGGB positions", failures) {
            BayerUtils.colorAt(CfaPattern.RGGB, 0, 0) == CfaColor.RED &&
                BayerUtils.colorAt(CfaPattern.RGGB, 1, 0) == CfaColor.GREEN_RED_ROW &&
                BayerUtils.colorAt(CfaPattern.RGGB, 0, 1) == CfaColor.GREEN_BLUE_ROW &&
                BayerUtils.colorAt(CfaPattern.RGGB, 1, 1) == CfaColor.BLUE
        }

        return failures
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
}
