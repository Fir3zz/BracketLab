package com.lab.bracketlab.processing.align

import com.lab.bracketlab.processing.raw.RawProxyType

/**
 * Configuration for landscape translation estimation.
 *
 * Response thresholds are provisional until they are tuned with real BracketLab
 * handheld and tripod sequences.
 */
data class LandscapeAlignmentOptions(
    val proxyMaxDimension: Int = 512,
    val primaryProxyType: RawProxyType = RawProxyType.GREEN,
    val allowLumaFallback: Boolean = true,
    val minimumPhaseResponse: Double = 0.12,
    val strictResponseRejection: Boolean = false,
    val maximumShiftFractionX: Double = 0.25,
    val maximumShiftFractionY: Double = 0.25,
    val minimumOverlapFraction: Double = 0.50,
    val exposureNormalizeProxies: Boolean = true,
    val rejectNonFiniteShift: Boolean = true,
    val preserveInputOrder: Boolean = true,
    val debugDiagnosticsEnabled: Boolean = false
) {
    init {
        require(proxyMaxDimension >= 2) { "proxyMaxDimension must be at least 2." }
        require(primaryProxyType == RawProxyType.GREEN) { "Landscape alignment currently supports GREEN as the primary proxy." }
        require(minimumPhaseResponse >= 0.0) { "minimumPhaseResponse must be non-negative." }
        require(maximumShiftFractionX > 0.0 && maximumShiftFractionX <= 1.0) {
            "maximumShiftFractionX must be in (0, 1]."
        }
        require(maximumShiftFractionY > 0.0 && maximumShiftFractionY <= 1.0) {
            "maximumShiftFractionY must be in (0, 1]."
        }
        require(minimumOverlapFraction > 0.0 && minimumOverlapFraction <= 1.0) {
            "minimumOverlapFraction must be in (0, 1]."
        }
    }
}
