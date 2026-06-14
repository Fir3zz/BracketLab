package com.lab.bracketlab.processing.align.star

import com.lab.bracketlab.processing.raw.RawProxy
import kotlin.math.abs
import kotlin.math.ceil

data class StarBackgroundEstimate(
    val background: Double,
    val sigma: Double,
    val finiteSampleCount: Int,
    val invalidSampleCount: Int
)

object StarBackgroundEstimator {
    fun estimate(proxy: RawProxy, sampleLimit: Int): StarBackgroundEstimate {
        val stride =
            ceil(proxy.data.size.toDouble() / sampleLimit.coerceAtLeast(1).toDouble())
                .toInt()
                .coerceAtLeast(1)
        val samples = FloatArray((proxy.data.size + stride - 1) / stride)
        var count = 0
        var invalid = 0
        var index = 0
        while (index < proxy.data.size) {
            val value = proxy.data[index]
            if (value.isFinite()) {
                samples[count++] = value
            } else {
                invalid++
            }
            index += stride
        }
        if (count == 0) {
            return StarBackgroundEstimate(Double.NaN, Double.NaN, 0, invalid)
        }
        val finite = samples.copyOf(count)
        finite.sort()
        val median = medianSorted(finite)
        val deviations = FloatArray(count) { abs(finite[it] - median) }
        deviations.sort()
        val mad = medianSorted(deviations)
        return StarBackgroundEstimate(
            background = median.toDouble(),
            sigma = (MAD_TO_SIGMA * mad).coerceAtLeast(0f).toDouble(),
            finiteSampleCount = count,
            invalidSampleCount = invalid
        )
    }

    private fun medianSorted(values: FloatArray): Float {
        val middle = values.size / 2
        return if ((values.size and 1) == 0) {
            (values[middle - 1] + values[middle]) * 0.5f
        } else {
            values[middle]
        }
    }

    private const val MAD_TO_SIGMA = 1.4826f
}

