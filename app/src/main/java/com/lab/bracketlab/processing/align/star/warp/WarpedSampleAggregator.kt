package com.lab.bracketlab.processing.align.star.warp

import com.lab.bracketlab.processing.stack.RawStackAggregationFallbackReason
import com.lab.bracketlab.processing.stack.RawStackAggregationMode
import com.lab.bracketlab.processing.stack.RawStackAggregationOptions
import kotlin.math.floor
import kotlin.math.sqrt

data class WarpedAggregationResult(
    val value: Int,
    val acceptedCount: Int,
    val rejectedCount: Int,
    val appliedMode: RawStackAggregationMode,
    val fallbackReason: RawStackAggregationFallbackReason?,
    val zeroVariance: Boolean
)

class WarpedSampleAggregator(maxSamples: Int) {
    private val sorted = DoubleArray(maxSamples)
    private var active = BooleanArray(maxSamples)
    private var candidate = BooleanArray(maxSamples)

    fun aggregate(
        samples: FloatArray,
        offset: Int,
        sampleCount: Int,
        options: RawStackAggregationOptions
    ): WarpedAggregationResult {
        require(sampleCount > 0 && sampleCount <= active.size)
        return when (options.mode) {
            RawStackAggregationMode.MEAN -> mean(samples, offset, sampleCount)
            RawStackAggregationMode.MIN_MAX_REJECTED_MEAN ->
                minMax(samples, offset, sampleCount, options)
            RawStackAggregationMode.SIGMA_CLIPPED_MEAN ->
                sigma(samples, offset, sampleCount, options)
            RawStackAggregationMode.MAXIMUM ->
                maximum(samples, offset, sampleCount)
        }
    }

    private fun maximum(
        samples: FloatArray,
        offset: Int,
        count: Int
    ): WarpedAggregationResult {
        var maximum = samples[offset].toDouble()
        for (index in 1 until count) {
            maximum = maxOf(maximum, samples[offset + index].toDouble())
        }
        return result(
            roundRaw16(maximum),
            count,
            0,
            RawStackAggregationMode.MAXIMUM
        )
    }

    private fun mean(samples: FloatArray, offset: Int, count: Int): WarpedAggregationResult {
        var sum = 0.0
        for (index in 0 until count) sum += samples[offset + index].toDouble()
        return result(roundRaw16(sum / count), count, 0, RawStackAggregationMode.MEAN)
    }

    private fun minMax(
        samples: FloatArray,
        offset: Int,
        count: Int,
        options: RawStackAggregationOptions
    ): WarpedAggregationResult {
        if (count < options.minimumSamplesForMinMax) {
            return mean(samples, offset, count).copy(
                fallbackReason = RawStackAggregationFallbackReason.INSUFFICIENT_SAMPLES_FOR_MIN_MAX
            )
        }
        for (index in 0 until count) sorted[index] = samples[offset + index].toDouble()
        insertionSort(sorted, count)
        val start = options.lowSamplesToReject
        val end = count - options.highSamplesToReject
        if (end <= start) {
            return mean(samples, offset, count).copy(
                fallbackReason = RawStackAggregationFallbackReason.INSUFFICIENT_REMAINING_SAMPLES
            )
        }
        var sum = 0.0
        for (index in start until end) sum += sorted[index]
        return result(
            roundRaw16(sum / (end - start)),
            end - start,
            count - (end - start),
            RawStackAggregationMode.MIN_MAX_REJECTED_MEAN
        )
    }

    private fun sigma(
        samples: FloatArray,
        offset: Int,
        count: Int,
        options: RawStackAggregationOptions
    ): WarpedAggregationResult {
        if (count < options.minimumSamplesForSigmaClip) {
            return fallback(
                samples,
                offset,
                count,
                options,
                RawStackAggregationFallbackReason.INSUFFICIENT_SAMPLES_FOR_SIGMA
            )
        }
        for (index in 0 until count) active[index] = true
        var activeCount = count
        repeat(options.maxSigmaIterations) {
            var sum = 0.0
            for (index in 0 until count) {
                if (active[index]) sum += samples[offset + index].toDouble()
            }
            val mean = sum / activeCount
            var squared = 0.0
            for (index in 0 until count) {
                if (!active[index]) continue
                val delta = samples[offset + index].toDouble() - mean
                squared += delta * delta
            }
            val sigma = sqrt((squared / activeCount).coerceAtLeast(0.0))
            if (sigma == 0.0) {
                return result(roundRaw16(mean), activeCount, count - activeCount, options.mode, zeroVariance = true)
            }
            val lower = mean - options.sigmaThreshold * sigma
            val upper = mean + options.sigmaThreshold * sigma
            var nextCount = 0
            var rejected = 0
            for (index in 0 until count) {
                val value = samples[offset + index].toDouble()
                val keep = active[index] && value >= lower && value <= upper
                candidate[index] = keep
                if (keep) nextCount++ else if (active[index]) rejected++
            }
            if (rejected == 0) {
                return result(roundRaw16(mean), activeCount, count - activeCount, options.mode)
            }
            if (nextCount < options.minimumRemainingSamples) {
                return fallback(
                    samples,
                    offset,
                    count,
                    options,
                    RawStackAggregationFallbackReason.INSUFFICIENT_REMAINING_SAMPLES
                )
            }
            val swap = active
            active = candidate
            candidate = swap
            activeCount = nextCount
        }
        var sum = 0.0
        for (index in 0 until count) if (active[index]) sum += samples[offset + index].toDouble()
        return result(
            roundRaw16(sum / activeCount),
            activeCount,
            count - activeCount,
            options.mode
        )
    }

    private fun fallback(
        samples: FloatArray,
        offset: Int,
        count: Int,
        options: RawStackAggregationOptions,
        reason: RawStackAggregationFallbackReason
    ): WarpedAggregationResult {
        if (
            options.fallbackMode == RawStackAggregationMode.MIN_MAX_REJECTED_MEAN &&
            count >= options.minimumSamplesForMinMax
        ) {
            return minMax(
                samples,
                offset,
                count,
                options.copy(mode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN)
            ).copy(fallbackReason = reason)
        }
        return mean(samples, offset, count).copy(fallbackReason = reason)
    }

    private fun result(
        value: Int,
        accepted: Int,
        rejected: Int,
        mode: RawStackAggregationMode,
        fallbackReason: RawStackAggregationFallbackReason? = null,
        zeroVariance: Boolean = false
    ): WarpedAggregationResult =
        WarpedAggregationResult(value, accepted, rejected, mode, fallbackReason, zeroVariance)

    private fun roundRaw16(value: Double): Int =
        floor(value + 0.5).toLong().coerceIn(0L, 65535L).toInt()

    private fun insertionSort(values: DoubleArray, count: Int) {
        for (index in 1 until count) {
            val value = values[index]
            var insertAt = index - 1
            while (insertAt >= 0 && values[insertAt] > value) {
                values[insertAt + 1] = values[insertAt]
                insertAt--
            }
            values[insertAt + 1] = value
        }
    }
}
