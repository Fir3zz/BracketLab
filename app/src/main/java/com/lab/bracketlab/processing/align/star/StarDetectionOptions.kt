package com.lab.bracketlab.processing.align.star

import com.lab.bracketlab.processing.raw.RawProxyType

data class StarDetectionOptions(
    val proxyMaxDimension: Int = 1536,
    val primaryProxyType: RawProxyType = RawProxyType.GREEN,
    val allowLumaFallback: Boolean = false,
    val exposureNormalizeProxies: Boolean = false,
    val suppressHotPixelsForProxy: Boolean = false,
    val thresholdSigma: Double = 5.0,
    val minSignalAboveBackground: Double = 0.005,
    val maxStars: Int = 500,
    val localMaxRadius: Int = 2,
    val centroidRadius: Int = 3,
    val minSnr: Double = 5.0,
    val saturationRejectThreshold: Double = 0.98,
    val edgeMargin: Int = 4,
    val minimumDistancePixels: Double = 3.0,
    val minimumRadius: Double = 0.15,
    val maximumRadius: Double = 4.0,
    val minimumStarsForFutureAlignment: Int = 8,
    val backgroundSampleLimit: Int = 262_144,
    val catalogDiagnosticsEnabled: Boolean = true
) {
    init {
        require(proxyMaxDimension in 16..8192)
        require(primaryProxyType == RawProxyType.GREEN)
        require(thresholdSigma.isFinite() && thresholdSigma >= 0.0)
        require(minSignalAboveBackground.isFinite() && minSignalAboveBackground >= 0.0)
        require(maxStars > 0)
        require(localMaxRadius in 1..16)
        require(centroidRadius in 1..16)
        require(minSnr.isFinite() && minSnr >= 0.0)
        require(saturationRejectThreshold.isFinite() && saturationRejectThreshold in 0.0..1.0)
        require(edgeMargin >= maxOf(localMaxRadius, centroidRadius))
        require(minimumDistancePixels.isFinite() && minimumDistancePixels >= 0.0)
        require(minimumRadius.isFinite() && minimumRadius >= 0.0)
        require(maximumRadius.isFinite() && maximumRadius > minimumRadius)
        require(minimumStarsForFutureAlignment >= 0)
        require(backgroundSampleLimit >= 64)
    }
}

