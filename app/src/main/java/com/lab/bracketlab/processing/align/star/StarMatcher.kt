package com.lab.bracketlab.processing.align.star

import kotlin.math.hypot

data class FilteredStarSet(
    val detectedCount: Int,
    val eligibleCount: Int,
    val retained: List<DetectedStar>
)

object StarCatalogFilter {
    fun filter(
        catalog: StarCatalog,
        options: StarMatchingOptions
    ): FilteredStarSet {
        val marginX = catalog.sourceWidth * options.edgeMarginFraction
        val marginY = catalog.sourceHeight * options.edgeMarginFraction
        val eligible =
            catalog.stars.filter { star ->
                star.fullX.isFinite() &&
                    star.fullY.isFinite() &&
                    star.proxyX.isFinite() &&
                    star.proxyY.isFinite() &&
                    star.snr.isFinite() &&
                    star.radius.isFinite() &&
                    star.sharpness.isFinite() &&
                    !star.saturated &&
                    star.snr >= options.minimumStarSnr &&
                    star.radius in options.minimumStarRadius..options.maximumStarRadius &&
                    star.sharpness >= options.minimumSharpness &&
                    star.fullX >= marginX &&
                    star.fullY >= marginY &&
                    star.fullX < catalog.sourceWidth - marginX &&
                    star.fullY < catalog.sourceHeight - marginY
            }
        val retained =
            eligible.sortedWith(
                compareByDescending<DetectedStar> { it.detectionQuality }
                    .thenByDescending { it.snr }
                    .thenByDescending { it.flux }
                    .thenBy { it.id }
            ).take(options.maximumRetainedStars)
        return FilteredStarSet(catalog.starCount, eligible.size, retained)
    }
}

object StarReferenceCatalogSelector {
    fun select(
        catalogs: List<StarCatalog>,
        options: StarMatchingOptions
    ): StarReferenceSelection {
        if (catalogs.isEmpty()) {
            return StarReferenceSelection(
                success = false,
                catalogPosition = -1,
                frameIndex = null,
                reason = null,
                starCount = 0,
                eligibleStarCount = 0,
                medianSnr = null,
                failureCode = StarMatchingFailureCode.EMPTY_CATALOG_SET,
                message = "No star catalogs were supplied."
            )
        }
        if (catalogs.size == 1) {
            val filtered = StarCatalogFilter.filter(catalogs.first(), options)
            return selection(
                catalogs,
                0,
                filtered,
                StarReferenceSelectionReason.ONLY_CATALOG
            )
        }

        val middle = catalogs.size / 2
        val middleFiltered = StarCatalogFilter.filter(catalogs[middle], options)
        if (middleFiltered.retained.size >= options.minimumEligibleStars) {
            return selection(
                catalogs,
                middle,
                middleFiltered,
                StarReferenceSelectionReason.MIDDLE_CATALOG
            )
        }

        val candidates =
            catalogs.indices.map { position ->
                Triple(position, StarCatalogFilter.filter(catalogs[position], options), catalogs[position])
            }.filter { it.second.retained.size >= options.minimumEligibleStars }
        val selected =
            candidates.sortedWith(
                compareBy<Triple<Int, FilteredStarSet, StarCatalog>> {
                    kotlin.math.abs(it.first - middle)
                }.thenByDescending { it.second.retained.size }
                    .thenByDescending { medianSnr(it.second.retained) ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.third.frameIndex }
            ).firstOrNull()
                ?: return StarReferenceSelection(
                    success = false,
                    catalogPosition = -1,
                    frameIndex = null,
                    reason = null,
                    starCount = middleFiltered.detectedCount,
                    eligibleStarCount = middleFiltered.eligibleCount,
                    medianSnr = medianSnr(middleFiltered.retained),
                    warnings = listOf("Middle catalog was weak and no usable fallback catalog exists."),
                    failureCode = StarMatchingFailureCode.TOO_FEW_REFERENCE_STARS,
                    message = "No catalog contains enough eligible stars."
                )

        return selection(
            catalogs,
            selected.first,
            selected.second,
            StarReferenceSelectionReason.NEAREST_STRONG_CATALOG_FALLBACK,
            warnings = listOf(
                "Middle catalog frame ${catalogs[middle].frameIndex} had only " +
                    "${middleFiltered.retained.size} retained stars; " +
                    "using frame ${selected.third.frameIndex}."
            )
        )
    }

    private fun selection(
        catalogs: List<StarCatalog>,
        position: Int,
        filtered: FilteredStarSet,
        reason: StarReferenceSelectionReason,
        warnings: List<String> = emptyList()
    ): StarReferenceSelection =
        StarReferenceSelection(
            success = true,
            catalogPosition = position,
            frameIndex = catalogs[position].frameIndex,
            reason = reason,
            starCount = filtered.detectedCount,
            eligibleStarCount = filtered.retained.size,
            medianSnr = medianSnr(filtered.retained),
            warnings = warnings
        )

    private fun medianSnr(stars: List<DetectedStar>): Double? {
        if (stars.isEmpty()) return null
        val sorted = stars.map { it.snr }.sorted()
        val middle = sorted.size / 2
        return if ((sorted.size and 1) == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) * 0.5
        }
    }
}

class StarMatcher(
    private val patternMatcher: StarPatternMatcher = StarPatternMatcher()
) {
    fun match(
        referenceCatalog: StarCatalog,
        targetCatalog: StarCatalog,
        options: StarMatchingOptions
    ): StarMatchResult {
        val reference = StarCatalogFilter.filter(referenceCatalog, options)
        val target = StarCatalogFilter.filter(targetCatalog, options)
        if (reference.retained.size < options.minimumEligibleStars) {
            return failure(
                StarMatchingFailureCode.TOO_FEW_REFERENCE_STARS,
                "Reference catalog has ${reference.retained.size} retained stars.",
                reference,
                target
            )
        }
        if (target.retained.size < options.minimumEligibleStars) {
            return failure(
                StarMatchingFailureCode.TOO_FEW_TARGET_STARS,
                "Target catalog has ${target.retained.size} retained stars.",
                reference,
                target
            )
        }

        val smallMotion =
            mutualNearestCandidates(
                reference.retained,
                target.retained,
                options.smallMotionSearchRadiusRawPixels,
                options.smallMotionAmbiguityRatio,
                StarMatchingStrategy.SMALL_MOTION
            )
        val consistentSmallMotion =
            displacementConsensus(
                smallMotion,
                maximumDeviation = maxOf(
                    options.geometricMatchRadiusRawPixels,
                    options.reprojectionThresholdRawPixels * 2.0
                )
            )
        if (consistentSmallMotion.size >= options.minimumSmallMotionMatches) {
            return success(
                StarMatchingStrategy.SMALL_MOTION,
                consistentSmallMotion,
                reference,
                target
            )
        }

        val geometric = patternMatcher.match(reference.retained, target.retained, options)
        if (geometric.isNotEmpty()) {
            return success(
                StarMatchingStrategy.GEOMETRIC_PATTERN,
                geometric,
                reference,
                target,
                warnings = listOf(
                    "Small-motion matching produced ${smallMotion.size} raw and " +
                        "${consistentSmallMotion.size} displacement-consistent matches; " +
                        "geometric fallback was used."
                )
            )
        }

        return failure(
            StarMatchingFailureCode.NO_MATCH_CANDIDATES,
            "Small-motion and geometric matching produced no usable one-to-one candidates.",
            reference,
            target
        )
    }

    private fun displacementConsensus(
        candidates: List<StarMatchCandidate>,
        maximumDeviation: Double
    ): List<StarMatchCandidate> {
        if (candidates.size < 3) return candidates
        val dxValues =
            candidates.map { it.referenceStar.fullX - it.targetStar.fullX }.sorted()
        val dyValues =
            candidates.map { it.referenceStar.fullY - it.targetStar.fullY }.sorted()
        val medianDx = median(dxValues)
        val medianDy = median(dyValues)
        return candidates.filter {
            hypot(
                (it.referenceStar.fullX - it.targetStar.fullX) - medianDx,
                (it.referenceStar.fullY - it.targetStar.fullY) - medianDy
            ) <= maximumDeviation
        }
    }

    private fun median(sorted: List<Double>): Double {
        val middle = sorted.size / 2
        return if ((sorted.size and 1) == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) * 0.5
        }
    }

    internal fun mutualNearestCandidates(
        reference: List<DetectedStar>,
        target: List<DetectedStar>,
        maximumDistance: Double,
        ambiguityRatio: Double,
        strategy: StarMatchingStrategy,
        transform: StarSimilarityTransform? = null
    ): List<StarMatchCandidate> {
        data class Nearest(val index: Int, val distance: Double, val secondDistance: Double)

        fun nearestReference(targetStar: DetectedStar): Nearest? {
            val mapped = transform?.map(targetStar.fullX, targetStar.fullY)
                ?: StarPoint(targetStar.fullX, targetStar.fullY)
            var bestIndex = -1
            var best = Double.POSITIVE_INFINITY
            var second = Double.POSITIVE_INFINITY
            for (index in reference.indices) {
                val distance =
                    hypot(
                        reference[index].fullX - mapped.x,
                        reference[index].fullY - mapped.y
                    )
                if (distance < best) {
                    second = best
                    best = distance
                    bestIndex = index
                } else if (distance < second) {
                    second = distance
                }
            }
            return bestIndex.takeIf { it >= 0 }?.let { Nearest(it, best, second) }
        }

        val targetToReference = target.map { nearestReference(it) }
        val referenceToTarget = IntArray(reference.size) { -1 }
        val referenceBest = DoubleArray(reference.size) { Double.POSITIVE_INFINITY }
        targetToReference.forEachIndexed { targetIndex, nearest ->
            if (nearest != null && nearest.distance < referenceBest[nearest.index]) {
                referenceBest[nearest.index] = nearest.distance
                referenceToTarget[nearest.index] = targetIndex
            }
        }

        return target.indices.mapNotNull { targetIndex ->
            val nearest = targetToReference[targetIndex] ?: return@mapNotNull null
            if (nearest.distance > maximumDistance) return@mapNotNull null
            if (referenceToTarget[nearest.index] != targetIndex) return@mapNotNull null
            if (
                nearest.secondDistance.isFinite() &&
                nearest.secondDistance > 0.0 &&
                nearest.distance / nearest.secondDistance > ambiguityRatio
            ) {
                return@mapNotNull null
            }
            StarMatchCandidate(
                referenceStar = reference[nearest.index],
                targetStar = target[targetIndex],
                geometricScore = 1.0 / (1.0 + nearest.distance),
                initialResidualRawPixels = nearest.distance,
                strategy = strategy
            )
        }.sortedWith(
            compareBy<StarMatchCandidate> { it.referenceStar.id }
                .thenBy { it.targetStar.id }
        )
    }

    private fun success(
        strategy: StarMatchingStrategy,
        candidates: List<StarMatchCandidate>,
        reference: FilteredStarSet,
        target: FilteredStarSet,
        warnings: List<String> = emptyList()
    ): StarMatchResult =
        StarMatchResult(
            success = true,
            strategy = strategy,
            candidates = candidates,
            referenceDetectedCount = reference.detectedCount,
            referenceEligibleCount = reference.eligibleCount,
            referenceRetainedCount = reference.retained.size,
            targetDetectedCount = target.detectedCount,
            targetEligibleCount = target.eligibleCount,
            targetRetainedCount = target.retained.size,
            warnings = warnings
        )

    private fun failure(
        code: StarMatchingFailureCode,
        message: String,
        reference: FilteredStarSet,
        target: FilteredStarSet
    ): StarMatchResult =
        StarMatchResult(
            success = false,
            strategy = StarMatchingStrategy.NONE,
            referenceDetectedCount = reference.detectedCount,
            referenceEligibleCount = reference.eligibleCount,
            referenceRetainedCount = reference.retained.size,
            targetDetectedCount = target.detectedCount,
            targetEligibleCount = target.eligibleCount,
            targetRetainedCount = target.retained.size,
            failureCode = code,
            diagnosticMessage = message
        )
}
