package com.lab.bracketlab.processing.align.star

import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.raw.RawProxy
import com.lab.bracketlab.processing.raw.RawProxyGenerator
import com.lab.bracketlab.processing.raw.RawProxyType
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

class StarDetector {
    fun detect(
        rawStack: RawStack,
        options: StarDetectionOptions = StarDetectionOptions()
    ): StarDetectionResult {
        val startedNs = System.nanoTime()
        if (rawStack.isEmpty) {
            return StarDetectionResult(
                success = false,
                failureCode = StarDetectionFailureCode.EMPTY_STACK,
                failureMessage = "Star detection requires at least one RAW frame.",
                durationMs = elapsedMs(startedNs),
                options = options
            )
        }

        val catalogs = mutableListOf<StarCatalog>()
        val warnings = mutableListOf<String>()
        val exposureReference =
            if (options.exposureNormalizeProxies) {
                rawStack.frames
                    .map { it.exposureTimeSeconds }
                    .filter { it.isFinite() && it > 0.0 }
                    .sorted()
                    .let { values -> values.getOrNull(values.size / 2) }
            } else {
                null
            }
        for (frame in rawStack.frames) {
            val invalid = validateFrame(frame)
            if (invalid != null) {
                warnings += "Frame ${frame.frameIndex}: ${invalid.second}"
                catalogs += invalidCatalog(frame, options, invalid.first, invalid.second)
                continue
            }
            val greenResult =
                runCatching {
                    createProxy(frame, options.primaryProxyType, options, exposureReference)
                }
            if (greenResult.isFailure) {
                val error = greenResult.exceptionOrNull()
                warnings += "Frame ${frame.frameIndex}: proxy generation failed: ${error?.message}"
                catalogs += invalidCatalog(
                    frame,
                    options,
                    StarDetectionFailureCode.PROXY_GENERATION_FAILED,
                    error?.message ?: "Proxy generation failed."
                )
                continue
            }
            val green = requireNotNull(greenResult.getOrNull())
            var catalog = detectProxy(green, frame, options)
            if (
                options.allowLumaFallback &&
                catalog.starCount < options.minimumStarsForFutureAlignment
            ) {
                val luma =
                    runCatching {
                        createProxy(
                            frame,
                            RawProxyType.LUMA_ALIGNMENT,
                            options,
                            exposureReference
                        )
                    }.getOrNull()
                if (luma != null) {
                    val fallbackCatalog = detectProxy(luma, frame, options, lumaFallback = true)
                    if (fallbackCatalog.starCount > catalog.starCount) {
                        catalog = fallbackCatalog
                    }
                }
            }
            catalogs += catalog
            warnings += catalog.warnings.map { "Frame ${frame.frameIndex}: $it" }
        }

        val usable = catalogs.count {
            it.statusCode != StarDetectionFailureCode.INVALID_FRAME &&
                it.statusCode != StarDetectionFailureCode.INVALID_RAW_STORAGE &&
                it.statusCode != StarDetectionFailureCode.PROXY_GENERATION_FAILED &&
                it.statusCode != StarDetectionFailureCode.STAR_DETECTION_FAILED
        }
        return StarDetectionResult(
            success = usable > 0,
            catalogs = catalogs,
            globalWarnings = warnings,
            failureCode =
                if (usable > 0) null
                else catalogs.firstOrNull()?.statusCode ?: StarDetectionFailureCode.STAR_DETECTION_FAILED,
            failureMessage = if (usable > 0) null else "No RAW frame produced a star catalog.",
            durationMs = elapsedMs(startedNs),
            options = options
        )
    }

    internal fun detectProxy(
        proxy: RawProxy,
        frame: RawFrame,
        options: StarDetectionOptions,
        lumaFallback: Boolean = false
    ): StarCatalog {
        val startedNs = System.nanoTime()
        val background =
            StarBackgroundEstimator.estimate(proxy, options.backgroundSampleLimit)
        if (!background.background.isFinite() || !background.sigma.isFinite()) {
            return invalidCatalog(
                frame,
                options,
                StarDetectionFailureCode.STAR_DETECTION_FAILED,
                "Proxy contains no finite samples.",
                proxy
            )
        }
        val threshold =
            max(
                background.background + options.thresholdSigma * background.sigma,
                background.background + options.minSignalAboveBackground
            )
        val rejected = StarCandidateRejectionReason.entries.associateWith { 0 }.toMutableMap()
        val candidates = mutableListOf<DetectedStar>()
        var localMaximumCount = 0
        val margin = options.edgeMargin

        for (y in 0 until proxy.height) {
            for (x in 0 until proxy.width) {
                val peak = proxy.data[y * proxy.width + x].toDouble()
                if (!peak.isFinite()) {
                    rejected.increment(StarCandidateRejectionReason.NON_FINITE_VALUE)
                    continue
                }
                if (peak <= threshold) {
                    rejected.increment(StarCandidateRejectionReason.BELOW_THRESHOLD)
                    continue
                }
                if (
                    x < margin ||
                    y < margin ||
                    x >= proxy.width - margin ||
                    y >= proxy.height - margin
                ) {
                    rejected.increment(StarCandidateRejectionReason.TOO_CLOSE_TO_EDGE)
                    continue
                }
                if (!isLocalMaximum(proxy, x, y, options.localMaxRadius)) continue
                localMaximumCount++
                if (peak >= options.saturationRejectThreshold) {
                    rejected.increment(StarCandidateRejectionReason.SATURATED)
                    continue
                }
                val measurement =
                    StarCentroidEstimator.estimate(
                        proxy,
                        x,
                        y,
                        options.centroidRadius,
                        background.sigma
                    )
                if (measurement == null) {
                    rejected.increment(StarCandidateRejectionReason.BAD_CENTROID)
                    continue
                }
                if (measurement.radius < options.minimumRadius) {
                    rejected.increment(StarCandidateRejectionReason.TOO_SMALL)
                    continue
                }
                if (measurement.radius > options.maximumRadius) {
                    rejected.increment(StarCandidateRejectionReason.TOO_LARGE)
                    continue
                }
                if (measurement.snr < options.minSnr) {
                    rejected.increment(StarCandidateRejectionReason.LOW_SNR)
                    continue
                }
                val flags = buildSet {
                    if (measurement.sharpness < 0.08) add(StarFlag.LOW_COMPACTNESS)
                    if (measurement.peak >= options.saturationRejectThreshold * 0.95) {
                        add(StarFlag.NEAR_SATURATION)
                    }
                    if (lumaFallback) add(StarFlag.LUMA_FALLBACK)
                }
                val quality =
                    measurement.snr *
                        max(measurement.sharpness, 1e-6) *
                        ln(1.0 + measurement.flux)
                candidates +=
                    DetectedStar(
                        id = -1,
                        frameIndex = frame.frameIndex,
                        proxyX = measurement.centroidX,
                        proxyY = measurement.centroidY,
                        fullX = measurement.centroidX * proxy.scaleX,
                        fullY = measurement.centroidY * proxy.scaleY,
                        peak = measurement.peak,
                        flux = measurement.flux,
                        background = measurement.localBackground,
                        snr = measurement.snr,
                        radius = measurement.radius,
                        secondMoment = measurement.secondMoment,
                        sharpness = measurement.sharpness,
                        saturated = false,
                        detectionQuality = quality,
                        flags = flags
                    )
            }
        }

        val sorted =
            candidates.sortedWith(
                compareByDescending<DetectedStar> { it.detectionQuality }
                    .thenByDescending { it.snr }
                    .thenBy { it.proxyY }
                    .thenBy { it.proxyX }
            )
        val kept = ArrayList<DetectedStar>(minOf(sorted.size, options.maxStars))
        for (candidate in sorted) {
            if (kept.any { distanceSquared(it, candidate) < options.minimumDistancePixels * options.minimumDistancePixels }) {
                rejected.increment(StarCandidateRejectionReason.DUPLICATE_NEARBY)
                continue
            }
            if (kept.size >= options.maxStars) {
                rejected.increment(StarCandidateRejectionReason.MAX_STARS_LIMIT)
                continue
            }
            kept += candidate.copy(id = kept.size)
        }

        val warnings = mutableListOf<String>()
        val status =
            when {
                kept.isEmpty() -> {
                    warnings += "No stars detected."
                    StarDetectionFailureCode.NO_STARS_DETECTED
                }
                kept.size < options.minimumStarsForFutureAlignment -> {
                    warnings +=
                        "Only ${kept.size} stars detected; future matching may require more."
                    StarDetectionFailureCode.TOO_FEW_STARS_FOR_FUTURE_ALIGNMENT
                }
                else -> null
            }
        if (background.invalidSampleCount > 0) {
            warnings += "${background.invalidSampleCount} non-finite proxy samples were ignored."
        }
        return StarCatalog(
            frameIndex = frame.frameIndex,
            sourceTimestampNs = frame.timestampNs,
            proxyType = proxy.proxyType,
            proxyWidth = proxy.width,
            proxyHeight = proxy.height,
            sourceWidth = frame.width,
            sourceHeight = frame.height,
            scaleX = proxy.scaleX,
            scaleY = proxy.scaleY,
            exposureNormalized = proxy.exposureNormalized,
            backgroundEstimate = background.background,
            noiseEstimate = background.sigma,
            thresholdUsed = threshold,
            thresholdSigma = options.thresholdSigma,
            localMaximumCount = localMaximumCount,
            stars = kept,
            rejectedCandidateCounts = rejected,
            warnings = warnings,
            statusCode = status,
            durationMs = elapsedMs(startedNs)
        )
    }

    private fun createProxy(
        frame: RawFrame,
        type: RawProxyType,
        options: StarDetectionOptions,
        referenceExposure: Double?
    ): RawProxy {
        val proxy =
            when (type) {
                RawProxyType.GREEN ->
                    RawProxyGenerator.extractGreenProxy(
                        frame,
                        options.proxyMaxDimension,
                        referenceExposure
                    )
                RawProxyType.LUMA_ALIGNMENT ->
                    RawProxyGenerator.extractLumaProxyForAlignment(
                        frame,
                        options.proxyMaxDimension,
                        referenceExposure
                    )
            }
        return if (options.suppressHotPixelsForProxy) {
            RawProxyGenerator.suppressHotPixelsForProxy(proxy)
        } else {
            proxy
        }
    }

    private fun validateFrame(
        frame: RawFrame
    ): Pair<StarDetectionFailureCode, String>? {
        if (frame.width <= 0 || frame.height <= 0 || frame.rowStride <= 0 || frame.pixelStride < 2) {
            return StarDetectionFailureCode.INVALID_FRAME to "Invalid RAW dimensions or stride."
        }
        if (frame.cfaPattern == null || frame.cfaPattern == CfaPattern.UNKNOWN) {
            return StarDetectionFailureCode.INVALID_FRAME to "CFA pattern is unavailable."
        }
        val storage =
            frame.resolvedRaw16Storage()
                ?: return StarDetectionFailureCode.INVALID_RAW_STORAGE to "RAW16 storage is unavailable."
        val required =
            (frame.height - 1L) * frame.rowStride.toLong() +
                (frame.width - 1L) * frame.pixelStride.toLong() +
                2L
        if (storage.byteCount < required) {
            return StarDetectionFailureCode.INVALID_RAW_STORAGE to
                "RAW16 storage is smaller than the declared dimensions."
        }
        return null
    }

    private fun isLocalMaximum(proxy: RawProxy, x: Int, y: Int, radius: Int): Boolean {
        val center = proxy.data[y * proxy.width + x]
        for (yy in y - radius..y + radius) {
            for (xx in x - radius..x + radius) {
                if (xx == x && yy == y) continue
                val neighbor = proxy.data[yy * proxy.width + xx]
                if (!neighbor.isFinite()) continue
                if (neighbor > center) return false
                if (neighbor == center && (yy < y || (yy == y && xx < x))) return false
            }
        }
        return true
    }

    private fun invalidCatalog(
        frame: RawFrame,
        options: StarDetectionOptions,
        code: StarDetectionFailureCode,
        message: String,
        proxy: RawProxy? = null
    ): StarCatalog =
        StarCatalog(
            frameIndex = frame.frameIndex,
            sourceTimestampNs = frame.timestampNs,
            proxyType = proxy?.proxyType ?: options.primaryProxyType,
            proxyWidth = proxy?.width ?: 0,
            proxyHeight = proxy?.height ?: 0,
            sourceWidth = frame.width,
            sourceHeight = frame.height,
            scaleX = proxy?.scaleX ?: 1.0,
            scaleY = proxy?.scaleY ?: 1.0,
            exposureNormalized = proxy?.exposureNormalized ?: false,
            backgroundEstimate = Double.NaN,
            noiseEstimate = Double.NaN,
            thresholdUsed = Double.NaN,
            thresholdSigma = options.thresholdSigma,
            localMaximumCount = 0,
            stars = emptyList(),
            rejectedCandidateCounts = emptyMap(),
            warnings = listOf(message),
            statusCode = code,
            durationMs = 0L
        )

    private fun MutableMap<StarCandidateRejectionReason, Int>.increment(
        reason: StarCandidateRejectionReason
    ) {
        this[reason] = (this[reason] ?: 0) + 1
    }

    private fun distanceSquared(left: DetectedStar, right: DetectedStar): Double {
        val dx = left.proxyX - right.proxyX
        val dy = left.proxyY - right.proxyY
        return dx * dx + dy * dy
    }

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L
}
