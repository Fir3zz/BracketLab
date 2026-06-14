package com.lab.bracketlab.processing.align.star

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class StarRansacResult(
    val success: Boolean,
    val transform: StarSimilarityTransform = StarSimilarityTransform.IDENTITY,
    val matches: List<StarMatch> = emptyList(),
    val inlierCount: Int = 0,
    val outlierCount: Int = 0,
    val inlierRatio: Double = 0.0,
    val rmsResidualRawPixels: Double? = null,
    val medianResidualRawPixels: Double? = null,
    val maximumResidualRawPixels: Double? = null,
    val iterationsExecuted: Int = 0,
    val failureCode: StarMatchingFailureCode? = null,
    val diagnosticMessage: String? = null
)

object StarTransformMath {
    fun fromPair(
        first: Pair<DetectedStar, DetectedStar>,
        second: Pair<DetectedStar, DetectedStar>
    ): StarSimilarityTransform? {
        val firstReference = first.first
        val firstTarget = first.second
        val secondReference = second.first
        val secondTarget = second.second
        val targetDx = secondTarget.fullX - firstTarget.fullX
        val targetDy = secondTarget.fullY - firstTarget.fullY
        val referenceDx = secondReference.fullX - firstReference.fullX
        val referenceDy = secondReference.fullY - firstReference.fullY
        val denominator = targetDx * targetDx + targetDy * targetDy
        if (!denominator.isFinite() || denominator <= 1e-12) return null
        val a = (targetDx * referenceDx + targetDy * referenceDy) / denominator
        val b = (targetDx * referenceDy - targetDy * referenceDx) / denominator
        val tx = firstReference.fullX - a * firstTarget.fullX + b * firstTarget.fullY
        val ty = firstReference.fullY - b * firstTarget.fullX - a * firstTarget.fullY
        return StarSimilarityTransform(a, b, tx, ty).takeIf { it.isFinite() }
    }

    fun refine(pairs: List<Pair<DetectedStar, DetectedStar>>): StarSimilarityTransform? {
        if (pairs.size < 2) return null
        val targetMeanX = pairs.sumOf { it.second.fullX } / pairs.size
        val targetMeanY = pairs.sumOf { it.second.fullY } / pairs.size
        val referenceMeanX = pairs.sumOf { it.first.fullX } / pairs.size
        val referenceMeanY = pairs.sumOf { it.first.fullY } / pairs.size
        var numeratorA = 0.0
        var numeratorB = 0.0
        var denominator = 0.0
        for ((reference, target) in pairs) {
            val targetX = target.fullX - targetMeanX
            val targetY = target.fullY - targetMeanY
            val referenceX = reference.fullX - referenceMeanX
            val referenceY = reference.fullY - referenceMeanY
            numeratorA += targetX * referenceX + targetY * referenceY
            numeratorB += targetX * referenceY - targetY * referenceX
            denominator += targetX * targetX + targetY * targetY
        }
        if (!denominator.isFinite() || denominator <= 1e-12) return null
        val a = numeratorA / denominator
        val b = numeratorB / denominator
        val tx = referenceMeanX - a * targetMeanX + b * targetMeanY
        val ty = referenceMeanY - b * targetMeanX - a * targetMeanY
        return StarSimilarityTransform(a, b, tx, ty).takeIf { it.isFinite() }
    }

    fun residual(
        transform: StarSimilarityTransform,
        candidate: StarMatchCandidate
    ): Double {
        val mapped = transform.map(candidate.targetStar.fullX, candidate.targetStar.fullY)
        return hypot(
            mapped.x - candidate.referenceStar.fullX,
            mapped.y - candidate.referenceStar.fullY
        )
    }
}

class StarRansacEstimator {
    fun estimate(
        candidates: List<StarMatchCandidate>,
        options: StarMatchingOptions
    ): StarRansacResult {
        if (candidates.size < 2) {
            return failure(
                StarMatchingFailureCode.NO_MATCH_CANDIDATES,
                "At least two candidate correspondences are required."
            )
        }
        if (
            candidates.any {
                !it.referenceStar.fullX.isFinite() ||
                    !it.referenceStar.fullY.isFinite() ||
                    !it.targetStar.fullX.isFinite() ||
                    !it.targetStar.fullY.isFinite()
            }
        ) {
            return failure(
                StarMatchingFailureCode.INVALID_CATALOG,
                "Candidate correspondence contains non-finite coordinates."
            )
        }

        val pairIndices = deterministicPairs(candidates.size, options.ransacMaxIterations)
        var bestTransform: StarSimilarityTransform? = null
        var bestInliers = IntArray(0)
        var bestSquaredError = Double.POSITIVE_INFINITY
        var iterationsExecuted = 0
        var iterationLimit = pairIndices.size

        for ((firstIndex, secondIndex) in pairIndices) {
            if (iterationsExecuted >= iterationLimit) break
            iterationsExecuted++
            val transform =
                StarTransformMath.fromPair(
                    candidates[firstIndex].referenceStar to candidates[firstIndex].targetStar,
                    candidates[secondIndex].referenceStar to candidates[secondIndex].targetStar
                ) ?: continue
            val inliers =
                candidates.indices.filter {
                    StarTransformMath.residual(transform, candidates[it]) <=
                        options.reprojectionThresholdRawPixels
                }.toIntArray()
            if (inliers.size < 2) continue
            val squaredError =
                inliers.sumOf {
                    val residual = StarTransformMath.residual(transform, candidates[it])
                    residual * residual
                }
            if (
                inliers.size > bestInliers.size ||
                (inliers.size == bestInliers.size && squaredError < bestSquaredError)
            ) {
                bestTransform = transform
                bestInliers = inliers
                bestSquaredError = squaredError
                val ratio = inliers.size.toDouble() / candidates.size
                if (ratio > 0.0 && ratio < 1.0) {
                    val missProbability = 1.0 - ratio * ratio
                    if (missProbability > 0.0 && missProbability < 1.0) {
                        val required =
                            ceil(ln(1.0 - options.ransacConfidence) / ln(missProbability))
                                .toInt()
                                .coerceAtLeast(1)
                        iterationLimit = minOf(iterationLimit, max(required, iterationsExecuted))
                    }
                } else if (ratio >= 1.0) {
                    iterationLimit = iterationsExecuted
                }
            }
        }
        if (bestTransform == null || bestInliers.size < 2) {
            return failure(
                StarMatchingFailureCode.RANSAC_FAILED,
                "RANSAC found no finite similarity hypothesis.",
                iterationsExecuted
            )
        }

        val refined =
            StarTransformMath.refine(
                bestInliers.map {
                    candidates[it].referenceStar to candidates[it].targetStar
                }
            ) ?: return failure(
                StarMatchingFailureCode.RANSAC_FAILED,
                "Inlier refinement was degenerate.",
                iterationsExecuted
            )
        val finalInlierFlags =
            BooleanArray(candidates.size) {
                StarTransformMath.residual(refined, candidates[it]) <=
                    options.reprojectionThresholdRawPixels
            }
        val finalInlierIndices = finalInlierFlags.indices.filter { finalInlierFlags[it] }
        val finalTransform =
            StarTransformMath.refine(
                finalInlierIndices.map {
                    candidates[it].referenceStar to candidates[it].targetStar
                }
            ) ?: refined
        val residuals = candidates.map { StarTransformMath.residual(finalTransform, it) }
        val inlierResiduals =
            residuals.filterIndexed { index, _ ->
                residuals[index] <= options.reprojectionThresholdRawPixels
            }
        val inlierCount = inlierResiduals.size
        val rms =
            if (inlierResiduals.isEmpty()) null
            else sqrt(inlierResiduals.sumOf { it * it } / inlierResiduals.size)
        val sortedResiduals = inlierResiduals.sorted()
        val median =
            when {
                sortedResiduals.isEmpty() -> null
                (sortedResiduals.size and 1) == 1 -> sortedResiduals[sortedResiduals.size / 2]
                else -> {
                    val middle = sortedResiduals.size / 2
                    (sortedResiduals[middle - 1] + sortedResiduals[middle]) * 0.5
                }
            }
        val matches =
            candidates.mapIndexed { index, candidate ->
                StarMatch(
                    referenceStarId = candidate.referenceStar.id,
                    targetStarId = candidate.targetStar.id,
                    referenceFullX = candidate.referenceStar.fullX,
                    referenceFullY = candidate.referenceStar.fullY,
                    targetFullX = candidate.targetStar.fullX,
                    targetFullY = candidate.targetStar.fullY,
                    referenceProxyX = candidate.referenceStar.proxyX,
                    referenceProxyY = candidate.referenceStar.proxyY,
                    targetProxyX = candidate.targetStar.proxyX,
                    targetProxyY = candidate.targetStar.proxyY,
                    geometricScore = candidate.geometricScore,
                    residualRawPixels = residuals[index],
                    ransacInlier =
                        residuals[index] <= options.reprojectionThresholdRawPixels,
                    warnings = candidate.warnings
                )
            }
        return StarRansacResult(
            success = true,
            transform = finalTransform,
            matches = matches,
            inlierCount = inlierCount,
            outlierCount = candidates.size - inlierCount,
            inlierRatio = inlierCount.toDouble() / candidates.size,
            rmsResidualRawPixels = rms,
            medianResidualRawPixels = median,
            maximumResidualRawPixels = inlierResiduals.maxOrNull(),
            iterationsExecuted = iterationsExecuted
        )
    }

    private fun deterministicPairs(
        count: Int,
        maximumIterations: Int
    ): List<Pair<Int, Int>> {
        val totalPairs = count.toLong() * (count - 1L) / 2L
        if (totalPairs <= maximumIterations) {
            return buildList {
                for (first in 0 until count - 1) {
                    for (second in first + 1 until count) add(first to second)
                }
            }
        }

        val output = ArrayList<Pair<Int, Int>>(maximumIterations)
        val seen = HashSet<Long>(maximumIterations * 2)
        var state = 0x5DEECE66DL xor count.toLong()
        while (output.size < maximumIterations) {
            state = (state * 25214903917L + 11L) and ((1L shl 48) - 1L)
            var first = ((state ushr 16) % count).toInt()
            state = (state * 25214903917L + 11L) and ((1L shl 48) - 1L)
            var second = ((state ushr 16) % count).toInt()
            if (first == second) continue
            if (first > second) {
                val swap = first
                first = second
                second = swap
            }
            val key = first.toLong() * count + second
            if (seen.add(key)) output += first to second
        }
        return output
    }

    private fun failure(
        code: StarMatchingFailureCode,
        message: String,
        iterations: Int = 0
    ): StarRansacResult =
        StarRansacResult(
            success = false,
            failureCode = code,
            diagnosticMessage = message,
            iterationsExecuted = iterations
        )
}
