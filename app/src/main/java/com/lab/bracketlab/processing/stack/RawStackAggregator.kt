package com.lab.bracketlab.processing.stack

import kotlin.math.sqrt

/**
 * Aggregates unsigned RAW16 samples for one already-aligned output coordinate.
 *
 * The public API returns a descriptive result. The stack processor uses
 * [aggregateInto] with reusable scratch state to avoid per-pixel allocation.
 */
object RawStackAggregator {
    fun aggregate(
        samples: IntArray,
        sampleCount: Int,
        options: RawStackAggregationOptions = RawStackAggregationOptions()
    ): RawStackAggregationResult {
        validateInput(samples, sampleCount)
        val scratch = RawStackAggregationScratch(samples.size.coerceAtLeast(1))
        val work = RawStackAggregationWorkResult()
        aggregateInto(samples, sampleCount, options, scratch, work)
        return work.toImmutable()
    }

    internal fun createScratch(maxSampleCount: Int): RawStackAggregationScratch {
        require(maxSampleCount >= 1) { "maxSampleCount must be at least 1." }
        return RawStackAggregationScratch(maxSampleCount)
    }

    internal fun aggregateInto(
        samples: IntArray,
        sampleCount: Int,
        options: RawStackAggregationOptions,
        scratch: RawStackAggregationScratch,
        output: RawStackAggregationWorkResult
    ): Int {
        require(sampleCount in 1..samples.size) {
            "sampleCount must be between 1 and samples.size."
        }
        require(sampleCount <= scratch.capacity) {
            "Scratch capacity is smaller than sampleCount."
        }
        output.reset(options.mode, sampleCount)
        return when (options.mode) {
            RawStackAggregationMode.MEAN ->
                mean(samples, sampleCount, output)

            RawStackAggregationMode.MIN_MAX_REJECTED_MEAN ->
                minMax(samples, sampleCount, options, scratch, output)

            RawStackAggregationMode.SIGMA_CLIPPED_MEAN ->
                sigmaClip(samples, sampleCount, options, scratch, output)

            RawStackAggregationMode.MAXIMUM ->
                maximum(samples, sampleCount, output)
        }
    }

    private fun maximum(
        samples: IntArray,
        sampleCount: Int,
        output: RawStackAggregationWorkResult
    ): Int {
        var value = samples[0]
        for (index in 1 until sampleCount) {
            if (samples[index] > value) value = samples[index]
        }
        output.finish(
            outputValue = value,
            appliedMode = RawStackAggregationMode.MAXIMUM,
            acceptedSampleCount = sampleCount
        )
        return value
    }

    private fun mean(
        samples: IntArray,
        sampleCount: Int,
        output: RawStackAggregationWorkResult
    ): Int {
        val value = roundedMean(samples, sampleCount, null)
        output.finish(
            outputValue = value,
            appliedMode = RawStackAggregationMode.MEAN,
            acceptedSampleCount = sampleCount
        )
        return value
    }

    private fun minMax(
        samples: IntArray,
        sampleCount: Int,
        options: RawStackAggregationOptions,
        scratch: RawStackAggregationScratch,
        output: RawStackAggregationWorkResult
    ): Int {
        if (sampleCount < options.minimumSamplesForMinMax) {
            return fallbackToMean(
                samples,
                sampleCount,
                output,
                RawStackAggregationFallbackReason.INSUFFICIENT_SAMPLES_FOR_MIN_MAX
            )
        }
        val remaining = sampleCount - options.lowSamplesToReject - options.highSamplesToReject
        if (remaining < 1) {
            return fallbackToMean(
                samples,
                sampleCount,
                output,
                RawStackAggregationFallbackReason.INSUFFICIENT_REMAINING_SAMPLES
            )
        }

        samples.copyInto(scratch.sorted, endIndex = sampleCount)
        insertionSort(scratch.sorted, sampleCount)
        var sum = 0L
        val start = options.lowSamplesToReject
        val endExclusive = sampleCount - options.highSamplesToReject
        for (index in start until endExclusive) {
            sum += scratch.sorted[index].toLong()
        }
        val value = roundedMeanFromSum(sum, remaining)
        output.finish(
            outputValue = value,
            appliedMode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN,
            acceptedSampleCount = remaining,
            rejectedLowCount = options.lowSamplesToReject,
            rejectedHighCount = options.highSamplesToReject
        )
        return value
    }

    private fun sigmaClip(
        samples: IntArray,
        sampleCount: Int,
        options: RawStackAggregationOptions,
        scratch: RawStackAggregationScratch,
        output: RawStackAggregationWorkResult
    ): Int {
        if (sampleCount < options.minimumSamplesForSigmaClip) {
            return fallback(
                samples,
                sampleCount,
                options,
                scratch,
                output,
                RawStackAggregationFallbackReason.INSUFFICIENT_SAMPLES_FOR_SIGMA
            )
        }

        for (index in 0 until sampleCount) {
            scratch.active[index] = true
        }
        var activeCount = sampleCount
        var zeroVariance = false

        repeat(options.maxSigmaIterations) {
            calculateStatistics(samples, sampleCount, scratch.active, activeCount, scratch)
            val mean = scratch.statisticsMean
            val standardDeviation = scratch.statisticsStandardDeviation
            if (!mean.isFinite() || !standardDeviation.isFinite()) {
                return fallback(
                    samples,
                    sampleCount,
                    options,
                    scratch,
                    output,
                    RawStackAggregationFallbackReason.INSUFFICIENT_REMAINING_SAMPLES
                )
            }
            if (standardDeviation == 0.0) {
                zeroVariance = true
                val value = roundedMean(samples, sampleCount, scratch.active)
                output.finish(
                    outputValue = value,
                    appliedMode = RawStackAggregationMode.SIGMA_CLIPPED_MEAN,
                    acceptedSampleCount = activeCount,
                    rejectedSigmaCount = sampleCount - activeCount,
                    zeroVariance = true
                )
                return value
            }

            val distance = options.sigmaThreshold * standardDeviation
            val lower = mean - distance
            val upper = mean + distance
            var nextCount = 0
            var newlyRejected = 0
            for (index in 0 until sampleCount) {
                val sample = samples[index].toDouble()
                val keep = scratch.active[index] && sample >= lower && sample <= upper
                scratch.candidate[index] = keep
                if (keep) {
                    nextCount++
                } else if (scratch.active[index]) {
                    newlyRejected++
                }
            }

            if (newlyRejected == 0) {
                val value = roundedMean(samples, sampleCount, scratch.active)
                output.finish(
                    outputValue = value,
                    appliedMode = RawStackAggregationMode.SIGMA_CLIPPED_MEAN,
                    acceptedSampleCount = activeCount,
                    rejectedSigmaCount = sampleCount - activeCount,
                    zeroVariance = zeroVariance
                )
                return value
            }
            if (nextCount < options.minimumRemainingSamples) {
                return fallback(
                    samples,
                    sampleCount,
                    options,
                    scratch,
                    output,
                    RawStackAggregationFallbackReason.INSUFFICIENT_REMAINING_SAMPLES
                )
            }

            val previous = scratch.active
            scratch.active = scratch.candidate
            scratch.candidate = previous
            activeCount = nextCount
        }

        val value = roundedMean(samples, sampleCount, scratch.active)
        output.finish(
            outputValue = value,
            appliedMode = RawStackAggregationMode.SIGMA_CLIPPED_MEAN,
            acceptedSampleCount = activeCount,
            rejectedSigmaCount = sampleCount - activeCount,
            zeroVariance = zeroVariance
        )
        return value
    }

    private fun fallback(
        samples: IntArray,
        sampleCount: Int,
        options: RawStackAggregationOptions,
        scratch: RawStackAggregationScratch,
        output: RawStackAggregationWorkResult,
        reason: RawStackAggregationFallbackReason
    ): Int {
        if (
            options.fallbackMode == RawStackAggregationMode.MIN_MAX_REJECTED_MEAN &&
            sampleCount >= options.minimumSamplesForMinMax
        ) {
            samples.copyInto(scratch.sorted, endIndex = sampleCount)
            insertionSort(scratch.sorted, sampleCount)
            val remaining = sampleCount - options.lowSamplesToReject - options.highSamplesToReject
            if (remaining >= 1) {
                var sum = 0L
                val start = options.lowSamplesToReject
                val endExclusive = sampleCount - options.highSamplesToReject
                for (index in start until endExclusive) {
                    sum += scratch.sorted[index].toLong()
                }
                val value = roundedMeanFromSum(sum, remaining)
                output.finish(
                    outputValue = value,
                    appliedMode = RawStackAggregationMode.MIN_MAX_REJECTED_MEAN,
                    acceptedSampleCount = remaining,
                    rejectedLowCount = options.lowSamplesToReject,
                    rejectedHighCount = options.highSamplesToReject,
                    fallbackReason = reason
                )
                return value
            }
        }
        return fallbackToMean(samples, sampleCount, output, reason)
    }

    private fun fallbackToMean(
        samples: IntArray,
        sampleCount: Int,
        output: RawStackAggregationWorkResult,
        reason: RawStackAggregationFallbackReason
    ): Int {
        val value = roundedMean(samples, sampleCount, null)
        output.finish(
            outputValue = value,
            appliedMode = RawStackAggregationMode.MEAN,
            acceptedSampleCount = sampleCount,
            fallbackReason = reason
        )
        return value
    }

    private fun calculateStatistics(
        samples: IntArray,
        sampleCount: Int,
        active: BooleanArray,
        activeCount: Int,
        scratch: RawStackAggregationScratch
    ) {
        var sum = 0.0
        for (index in 0 until sampleCount) {
            if (active[index]) sum += samples[index].toDouble()
        }
        val mean = sum / activeCount.toDouble()
        var squaredDistanceSum = 0.0
        for (index in 0 until sampleCount) {
            if (!active[index]) continue
            val distance = samples[index].toDouble() - mean
            squaredDistanceSum += distance * distance
        }
        val variance = squaredDistanceSum / activeCount.toDouble()
        scratch.statisticsMean = mean
        scratch.statisticsStandardDeviation = sqrt(variance.coerceAtLeast(0.0))
    }

    private fun roundedMean(
        samples: IntArray,
        sampleCount: Int,
        active: BooleanArray?
    ): Int {
        var sum = 0L
        var count = 0
        for (index in 0 until sampleCount) {
            if (active == null || active[index]) {
                sum += samples[index].toLong()
                count++
            }
        }
        return roundedMeanFromSum(sum, count)
    }

    private fun roundedMeanFromSum(sum: Long, count: Int): Int =
        ((sum + count.toLong() / 2L) / count.toLong())
            .coerceIn(0L, 65535L)
            .toInt()

    private fun insertionSort(values: IntArray, count: Int) {
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

    private fun validateInput(samples: IntArray, sampleCount: Int) {
        require(sampleCount in 1..samples.size) {
            "sampleCount must be between 1 and samples.size."
        }
        for (index in 0 until sampleCount) {
            require(samples[index] in 0..65535) {
                "RAW16 sample at index $index is outside 0..65535."
            }
        }
    }
}

internal class RawStackAggregationScratch(
    val capacity: Int
) {
    val sorted = IntArray(capacity)
    var active = BooleanArray(capacity)
    var candidate = BooleanArray(capacity)
    var statisticsMean: Double = 0.0
    var statisticsStandardDeviation: Double = 0.0
}

internal class RawStackAggregationWorkResult {
    var outputValue: Int = 0
        private set
    var requestedMode: RawStackAggregationMode = RawStackAggregationMode.MEAN
        private set
    var appliedMode: RawStackAggregationMode = RawStackAggregationMode.MEAN
        private set
    var inputSampleCount: Int = 0
        private set
    var acceptedSampleCount: Int = 0
        private set
    var rejectedLowCount: Int = 0
        private set
    var rejectedHighCount: Int = 0
        private set
    var rejectedSigmaCount: Int = 0
        private set
    var fallbackReason: RawStackAggregationFallbackReason? = null
        private set
    var zeroVariance: Boolean = false
        private set

    val fallbackUsed: Boolean
        get() = fallbackReason != null

    val totalRejectedCount: Int
        get() = rejectedLowCount + rejectedHighCount + rejectedSigmaCount

    fun reset(mode: RawStackAggregationMode, sampleCount: Int) {
        outputValue = 0
        requestedMode = mode
        appliedMode = mode
        inputSampleCount = sampleCount
        acceptedSampleCount = 0
        rejectedLowCount = 0
        rejectedHighCount = 0
        rejectedSigmaCount = 0
        fallbackReason = null
        zeroVariance = false
    }

    fun finish(
        outputValue: Int,
        appliedMode: RawStackAggregationMode,
        acceptedSampleCount: Int,
        rejectedLowCount: Int = 0,
        rejectedHighCount: Int = 0,
        rejectedSigmaCount: Int = 0,
        fallbackReason: RawStackAggregationFallbackReason? = null,
        zeroVariance: Boolean = false
    ) {
        this.outputValue = outputValue
        this.appliedMode = appliedMode
        this.acceptedSampleCount = acceptedSampleCount
        this.rejectedLowCount = rejectedLowCount
        this.rejectedHighCount = rejectedHighCount
        this.rejectedSigmaCount = rejectedSigmaCount
        this.fallbackReason = fallbackReason
        this.zeroVariance = zeroVariance
    }

    fun toImmutable(): RawStackAggregationResult =
        RawStackAggregationResult(
            outputValue = outputValue,
            requestedMode = requestedMode,
            appliedMode = appliedMode,
            inputSampleCount = inputSampleCount,
            acceptedSampleCount = acceptedSampleCount,
            rejectedLowCount = rejectedLowCount,
            rejectedHighCount = rejectedHighCount,
            rejectedSigmaCount = rejectedSigmaCount,
            fallbackUsed = fallbackUsed,
            fallbackReason = fallbackReason,
            zeroVariance = zeroVariance
        )
}
