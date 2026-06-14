package com.lab.bracketlab.processing.align.star

import kotlin.math.abs
import kotlin.math.hypot

class StarPatternMatcher {
    fun match(
        reference: List<DetectedStar>,
        target: List<DetectedStar>,
        options: StarMatchingOptions
    ): List<StarMatchCandidate> {
        val referenceSubset = reference.take(options.patternStarLimit)
        val targetSubset = target.take(options.patternStarLimit)
        if (referenceSubset.size < 3 || targetSubset.size < 3) return emptyList()

        val referenceTriangles = triangles(referenceSubset, options)
        val targetTriangles = triangles(targetSubset, options)
        if (referenceTriangles.isEmpty() || targetTriangles.isEmpty()) return emptyList()

        val hypotheses = mutableListOf<Pair<Double, StarSimilarityTransform>>()
        for (targetTriangle in targetTriangles) {
            for (referenceTriangle in referenceTriangles) {
                val ratioError =
                    abs(targetTriangle.ratioSmall - referenceTriangle.ratioSmall) +
                        abs(targetTriangle.ratioMiddle - referenceTriangle.ratioMiddle)
                if (ratioError > options.triangleRatioTolerance * 2.0) continue
                if (targetTriangle.orientationSign != referenceTriangle.orientationSign) continue
                val scale = referenceTriangle.longestSide / targetTriangle.longestSide
                if (
                    scale < options.minimumScale * (1.0 - options.triangleScaleToleranceFraction) ||
                    scale > options.maximumScale * (1.0 + options.triangleScaleToleranceFraction)
                ) {
                    continue
                }
                val pairs =
                    targetTriangle.canonicalVertices.indices.map { index ->
                        referenceTriangle.canonicalVertices[index] to
                            targetTriangle.canonicalVertices[index]
                    }
                val transform = StarTransformMath.refine(pairs) ?: continue
                if (!transform.isFinite()) continue
                hypotheses += ratioError to transform
            }
        }
        if (hypotheses.isEmpty()) return emptyList()

        val matcher = StarMatcher(this)
        var best: List<StarMatchCandidate> = emptyList()
        var bestResidual = Double.POSITIVE_INFINITY
        hypotheses.sortedBy { it.first }.take(MAX_HYPOTHESES_TO_SCORE).forEach { (_, transform) ->
            val candidates =
                matcher.mutualNearestCandidates(
                    reference = reference,
                    target = target,
                    maximumDistance = options.geometricMatchRadiusRawPixels,
                    ambiguityRatio = 1.0,
                    strategy = StarMatchingStrategy.GEOMETRIC_PATTERN,
                    transform = transform
                )
            val residual = candidates.sumOf { it.initialResidualRawPixels }
            if (
                candidates.size > best.size ||
                (candidates.size == best.size && residual < bestResidual)
            ) {
                best = candidates
                bestResidual = residual
            }
        }
        return best
    }

    private fun triangles(
        stars: List<DetectedStar>,
        options: StarMatchingOptions
    ): List<Triangle> {
        val triangles = LinkedHashMap<String, Triangle>()
        for (anchorIndex in stars.indices) {
            val neighbors =
                stars.indices.filter { it != anchorIndex }
                    .sortedBy {
                        distance(stars[anchorIndex], stars[it])
                    }.take(options.patternNearestNeighbors)
            for (first in 0 until neighbors.size - 1) {
                for (second in first + 1 until neighbors.size) {
                    val indices = intArrayOf(anchorIndex, neighbors[first], neighbors[second])
                    val key = indices.sorted().joinToString(":")
                    if (key in triangles) continue
                    val triangle = Triangle.create(indices.map { stars[it] }) ?: continue
                    triangles[key] = triangle
                    if (triangles.size >= options.maximumPatternTriangles) {
                        return triangles.values.toList()
                    }
                }
            }
        }
        return triangles.values.toList()
    }

    private fun distance(first: DetectedStar, second: DetectedStar): Double =
        hypot(first.fullX - second.fullX, first.fullY - second.fullY)

    private data class Triangle(
        val canonicalVertices: List<DetectedStar>,
        val ratioSmall: Double,
        val ratioMiddle: Double,
        val longestSide: Double,
        val orientationSign: Int
    ) {
        companion object {
            fun create(vertices: List<DetectedStar>): Triangle? {
                if (vertices.size != 3) return null
                val oppositeSides =
                    listOf(
                        distance(vertices[1], vertices[2]),
                        distance(vertices[0], vertices[2]),
                        distance(vertices[0], vertices[1])
                    )
                if (oppositeSides.any { !it.isFinite() || it <= 1e-6 }) return null
                val sortedSides = oppositeSides.sorted()
                val longest = sortedSides[2]
                val canonical =
                    vertices.indices.sortedWith(
                        compareBy<Int> { oppositeSides[it] }.thenBy { vertices[it].id }
                    ).map { vertices[it] }
                val cross =
                    (canonical[1].fullX - canonical[0].fullX) *
                        (canonical[2].fullY - canonical[0].fullY) -
                        (canonical[1].fullY - canonical[0].fullY) *
                        (canonical[2].fullX - canonical[0].fullX)
                if (!cross.isFinite() || abs(cross) <= 1e-3) return null
                return Triangle(
                    canonicalVertices = canonical,
                    ratioSmall = sortedSides[0] / longest,
                    ratioMiddle = sortedSides[1] / longest,
                    longestSide = longest,
                    orientationSign = if (cross > 0.0) 1 else -1
                )
            }

            private fun distance(first: DetectedStar, second: DetectedStar): Double =
                hypot(first.fullX - second.fullX, first.fullY - second.fullY)
        }
    }

    companion object {
        private const val MAX_HYPOTHESES_TO_SCORE = 256
    }
}
