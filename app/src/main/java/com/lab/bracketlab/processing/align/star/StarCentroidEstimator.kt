package com.lab.bracketlab.processing.align.star

import com.lab.bracketlab.processing.raw.RawProxy
import kotlin.math.max
import kotlin.math.sqrt

data class StarCentroidMeasurement(
    val centroidX: Double,
    val centroidY: Double,
    val peak: Double,
    val flux: Double,
    val localBackground: Double,
    val snr: Double,
    val radius: Double,
    val secondMoment: Double,
    val sharpness: Double
)

object StarCentroidEstimator {
    fun estimate(
        proxy: RawProxy,
        centerX: Int,
        centerY: Int,
        radius: Int,
        globalNoise: Double
    ): StarCentroidMeasurement? {
        val xStart = centerX - radius
        val xEnd = centerX + radius
        val yStart = centerY - radius
        val yEnd = centerY + radius
        if (xStart < 0 || yStart < 0 || xEnd >= proxy.width || yEnd >= proxy.height) {
            return null
        }

        val border = FloatArray((radius * 2 + 1) * 4)
        var borderCount = 0
        for (y in yStart..yEnd) {
            for (x in xStart..xEnd) {
                val value = proxy.data[y * proxy.width + x]
                if (!value.isFinite()) return null
                if (x == xStart || x == xEnd || y == yStart || y == yEnd) {
                    border[borderCount++] = value
                }
            }
        }
        if (borderCount == 0) return null
        val localBackground = median(border.copyOf(borderCount)).toDouble()

        var sumWeight = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var peak = Double.NEGATIVE_INFINITY
        var positivePixels = 0
        for (y in yStart..yEnd) {
            for (x in xStart..xEnd) {
                val value = proxy.data[y * proxy.width + x].toDouble()
                peak = max(peak, value)
                val weight = (value - localBackground).coerceAtLeast(0.0)
                if (weight > 0.0) {
                    sumWeight += weight
                    sumX += x.toDouble() * weight
                    sumY += y.toDouble() * weight
                    positivePixels++
                }
            }
        }
        if (!sumWeight.isFinite() || sumWeight <= 0.0) return null

        val centroidX = sumX / sumWeight
        val centroidY = sumY / sumWeight
        var moment = 0.0
        for (y in yStart..yEnd) {
            for (x in xStart..xEnd) {
                val value = proxy.data[y * proxy.width + x].toDouble()
                val weight = (value - localBackground).coerceAtLeast(0.0)
                if (weight <= 0.0) continue
                val dx = x.toDouble() - centroidX
                val dy = y.toDouble() - centroidY
                moment += (dx * dx + dy * dy) * weight
            }
        }
        val secondMoment = moment / sumWeight
        val measuredRadius = sqrt(secondMoment.coerceAtLeast(0.0))
        val noise = max(globalNoise, MINIMUM_NOISE)
        val snr = sumWeight / (noise * sqrt(positivePixels.coerceAtLeast(1).toDouble()))
        val peakSignal = (peak - localBackground).coerceAtLeast(0.0)
        val sharpness = if (sumWeight > 0.0) peakSignal / sumWeight else 0.0
        if (
            !centroidX.isFinite() ||
            !centroidY.isFinite() ||
            !snr.isFinite() ||
            !measuredRadius.isFinite() ||
            !sharpness.isFinite()
        ) {
            return null
        }
        return StarCentroidMeasurement(
            centroidX = centroidX,
            centroidY = centroidY,
            peak = peak,
            flux = sumWeight,
            localBackground = localBackground,
            snr = snr,
            radius = measuredRadius,
            secondMoment = secondMoment,
            sharpness = sharpness
        )
    }

    private fun median(values: FloatArray): Float {
        values.sort()
        val middle = values.size / 2
        return if ((values.size and 1) == 0) {
            (values[middle - 1] + values[middle]) * 0.5f
        } else {
            values[middle]
        }
    }

    private const val MINIMUM_NOISE = 1e-7
}

