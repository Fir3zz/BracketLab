package com.lab.bracketlab.processing.align.star

data class StarMatchCandidate(
    val referenceStar: DetectedStar,
    val targetStar: DetectedStar,
    val geometricScore: Double,
    val initialResidualRawPixels: Double,
    val strategy: StarMatchingStrategy,
    val warnings: List<String> = emptyList()
)

data class StarMatch(
    val referenceStarId: Int,
    val targetStarId: Int,
    val referenceFullX: Double,
    val referenceFullY: Double,
    val targetFullX: Double,
    val targetFullY: Double,
    val referenceProxyX: Double,
    val referenceProxyY: Double,
    val targetProxyX: Double,
    val targetProxyY: Double,
    val geometricScore: Double,
    val residualRawPixels: Double,
    val ransacInlier: Boolean,
    val warnings: List<String> = emptyList()
)

data class StarMatchResult(
    val success: Boolean,
    val strategy: StarMatchingStrategy,
    val candidates: List<StarMatchCandidate> = emptyList(),
    val referenceDetectedCount: Int = 0,
    val referenceEligibleCount: Int = 0,
    val referenceRetainedCount: Int = 0,
    val targetDetectedCount: Int = 0,
    val targetEligibleCount: Int = 0,
    val targetRetainedCount: Int = 0,
    val failureCode: StarMatchingFailureCode? = null,
    val diagnosticMessage: String? = null,
    val warnings: List<String> = emptyList()
)
