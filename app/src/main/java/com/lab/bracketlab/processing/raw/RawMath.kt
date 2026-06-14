package com.lab.bracketlab.processing.raw

import kotlin.math.max

object RawMath {
    fun blackSubtract(rawSample: Int, blackLevel: Int): Int =
        max(rawSample - blackLevel, 0)

    fun normalizeRaw(rawSample: Int, blackLevel: Int, whiteLevel: Int): Double {
        val signal = blackSubtract(rawSample, blackLevel)
        val range = max(whiteLevel - blackLevel, 1)
        return signal.toDouble() / range.toDouble()
    }

    fun isSaturated(rawSample: Int, whiteLevel: Int, threshold: Double = 0.98): Boolean =
        rawSample.toDouble() >= whiteLevel.toDouble() * threshold

    fun isNearNoiseFloor(rawSample: Int, blackLevel: Int, threshold: Int): Boolean =
        rawSample <= blackLevel + threshold

    fun radianceFromRaw(
        rawSample: Int,
        blackLevel: Int,
        whiteLevel: Int,
        exposureTimeSeconds: Double
    ): Double {
        if (exposureTimeSeconds <= 0.0) return 0.0
        return normalizeRaw(rawSample, blackLevel, whiteLevel) / exposureTimeSeconds
    }

    fun evScaleFromExposure(
        exposureTimeSeconds: Double,
        referenceExposureTimeSeconds: Double
    ): Double {
        if (referenceExposureTimeSeconds <= 0.0) return 1.0
        return exposureTimeSeconds / referenceExposureTimeSeconds
    }
}
