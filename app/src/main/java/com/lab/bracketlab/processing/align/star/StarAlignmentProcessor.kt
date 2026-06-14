package com.lab.bracketlab.processing.align.star

import com.lab.bracketlab.processing.model.AlignmentResult
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Synchronous CPU orchestration. Callers must execute this outside the UI thread.
 * No RAW pixels, proxies or catalogs are mutated.
 */
class StarAlignmentProcessor(
    private val matcher: StarMatcher = StarMatcher(),
    private val ransacEstimator: StarRansacEstimator = StarRansacEstimator()
) {
    fun align(
        catalogs: List<StarCatalog>,
        options: StarMatchingOptions = StarMatchingOptions()
    ): StarAlignmentReport {
        val startedNs = System.nanoTime()
        if (catalogs.isEmpty()) {
            return fatal(
                options,
                startedNs,
                StarMatchingFailureCode.EMPTY_CATALOG_SET,
                "No star catalogs were supplied."
            )
        }
        val invalid = catalogs.firstOrNull { !validCatalog(it) }
        if (invalid != null) {
            return fatal(
                options,
                startedNs,
                StarMatchingFailureCode.INVALID_CATALOG,
                "Catalog for frame ${invalid.frameIndex} is invalid.",
                catalogs.size
            )
        }
        if (catalogs.map { it.frameIndex }.distinct().size != catalogs.size) {
            return fatal(
                options,
                startedNs,
                StarMatchingFailureCode.INVALID_CATALOG,
                "Catalog frame indices must be unique.",
                catalogs.size
            )
        }

        val selection = StarReferenceCatalogSelector.select(catalogs, options)
        if (!selection.success || selection.catalogPosition !in catalogs.indices) {
            return fatal(
                options,
                startedNs,
                selection.failureCode ?: StarMatchingFailureCode.NO_REFERENCE_CATALOG,
                selection.message ?: "Reference catalog selection failed.",
                catalogs.size,
                warnings = selection.warnings
            )
        }
        val reference = catalogs[selection.catalogPosition]
        val frameResults =
            catalogs.mapIndexed { position, catalog ->
                if (position == selection.catalogPosition) {
                    referenceResult(position, catalog, options)
                } else {
                    alignTarget(position, reference, catalog, options)
                }
            }
        val accepted = frameResults.count { it.accepted }
        val rejected = frameResults.size - accepted
        val status =
            when {
                accepted == frameResults.size -> StarAlignmentStatus.SUCCESS
                accepted > 0 -> StarAlignmentStatus.PARTIAL_SUCCESS
                else -> StarAlignmentStatus.FAILURE
            }
        return StarAlignmentReport(
            status = status,
            success = rejected == 0,
            partialSuccess = accepted > 0 && rejected > 0,
            referenceFrameIndex = reference.frameIndex,
            referenceCatalogPosition = selection.catalogPosition,
            referenceSelectionReason = selection.reason,
            referenceStarCount = selection.eligibleStarCount,
            referenceMedianSnr = selection.medianSnr,
            totalFrameCount = catalogs.size,
            acceptedFrameCount = accepted,
            rejectedFrameCount = rejected,
            frameResults = frameResults,
            alignmentResults = frameResults.map { it.alignmentResult },
            warnings = selection.warnings + frameResults.flatMap { it.warnings },
            fatalError = null,
            fatalMessage = null,
            durationMs = elapsedMs(startedNs),
            options = options
        )
    }

    private fun alignTarget(
        position: Int,
        reference: StarCatalog,
        target: StarCatalog,
        options: StarMatchingOptions
    ): StarFrameAlignment {
        if (
            reference.sourceWidth != target.sourceWidth ||
            reference.sourceHeight != target.sourceHeight
        ) {
            return rejected(
                position,
                target,
                StarMatchingFailureCode.INVALID_CATALOG,
                "Reference and target source dimensions differ."
            )
        }
        val matchResult = matcher.match(reference, target, options)
        if (!matchResult.success) {
            return rejected(
                position,
                target,
                matchResult.failureCode ?: StarMatchingFailureCode.MATCHING_FAILED,
                matchResult.diagnosticMessage ?: "Star matching failed.",
                matchResult = matchResult
            )
        }
        val ransac = ransacEstimator.estimate(matchResult.candidates, options)
        if (!ransac.success) {
            return rejected(
                position,
                target,
                ransac.failureCode ?: StarMatchingFailureCode.RANSAC_FAILED,
                ransac.diagnosticMessage ?: "RANSAC failed.",
                matchResult = matchResult,
                ransac = ransac
            )
        }
        val inlierReferenceStars =
            ransac.matches.filter { it.ransacInlier }.mapNotNull { match ->
                reference.stars.firstOrNull { it.id == match.referenceStarId }
            }
        val distribution =
            spatialDistribution(
                inlierReferenceStars,
                reference.sourceWidth,
                reference.sourceHeight,
                options
            )
        val validation = validate(ransac, distribution, target, options)
        if (validation != null) {
            return rejected(
                position,
                target,
                validation.first,
                validation.second,
                matchResult,
                ransac,
                distribution
            )
        }

        val transform = ransac.transform
        val alignment =
            AlignmentResult(
                frameIndex = target.frameIndex,
                mode = ResolvedAlignmentMode.STAR_ALIGNMENT,
                transform = transform.toRawTransform(),
                confidence = ransac.inlierRatio,
                accepted = true,
                diagnosticMessage =
                    "${matchResult.strategy}; ${ransac.inlierCount}/${matchResult.candidates.size} inliers"
            )
        return StarFrameAlignment(
            framePosition = position,
            frameIndex = target.frameIndex,
            isReference = false,
            detectedStarCount = matchResult.targetDetectedCount,
            eligibleStarCount = matchResult.targetEligibleCount,
            retainedStarCount = matchResult.targetRetainedCount,
            candidateMatchCount = matchResult.candidates.size,
            ransacInlierCount = ransac.inlierCount,
            outlierCount = ransac.outlierCount,
            inlierRatio = ransac.inlierRatio,
            rmsResidualRawPixels = ransac.rmsResidualRawPixels,
            medianResidualRawPixels = ransac.medianResidualRawPixels,
            maximumResidualRawPixels = ransac.maximumResidualRawPixels,
            transform = transform,
            matchingStrategy = matchResult.strategy,
            spatialDistribution = distribution,
            matches = ransac.matches,
            accepted = true,
            failureCode = null,
            diagnosticMessage = alignment.diagnosticMessage,
            warnings = matchResult.warnings,
            alignmentResult = alignment
        )
    }

    private fun validate(
        ransac: StarRansacResult,
        distribution: StarSpatialDistribution,
        target: StarCatalog,
        options: StarMatchingOptions
    ): Pair<StarMatchingFailureCode, String>? {
        val transform = ransac.transform
        if (!transform.isFinite()) {
            return StarMatchingFailureCode.NON_FINITE_TRANSFORM to "Transform is non-finite."
        }
        if (ransac.inlierCount < options.minimumInliers) {
            return StarMatchingFailureCode.TOO_FEW_INLIERS to
                "Only ${ransac.inlierCount} RANSAC inliers were found."
        }
        if (ransac.inlierRatio < options.minimumInlierRatio) {
            return StarMatchingFailureCode.LOW_INLIER_RATIO to
                "Inlier ratio ${ransac.inlierRatio} is below ${options.minimumInlierRatio}."
        }
        if (
            ransac.rmsResidualRawPixels == null ||
            ransac.rmsResidualRawPixels > options.maximumRmsErrorRawPixels
        ) {
            return StarMatchingFailureCode.EXCESSIVE_RMS_ERROR to
                "RMS residual ${ransac.rmsResidualRawPixels} exceeds ${options.maximumRmsErrorRawPixels}."
        }
        if (abs(transform.rotationDegrees) > options.maximumAbsoluteRotationDegrees) {
            return StarMatchingFailureCode.EXCESSIVE_ROTATION to
                "Rotation ${transform.rotationDegrees} degrees is implausible."
        }
        if (transform.scale !in options.minimumScale..options.maximumScale) {
            return StarMatchingFailureCode.INVALID_SCALE to
                "Scale ${transform.scale} is outside ${options.minimumScale}..${options.maximumScale}."
        }
        if (
            abs(transform.tx) > target.sourceWidth * options.maximumTranslationFractionX ||
            abs(transform.ty) > target.sourceHeight * options.maximumTranslationFractionY
        ) {
            return StarMatchingFailureCode.EXCESSIVE_TRANSLATION to
                "Translation ${transform.tx},${transform.ty} exceeds configured fractions."
        }
        if (distribution.degenerate) {
            return StarMatchingFailureCode.DEGENERATE_GEOMETRY to
                "Inlier geometry is nearly collinear."
        }
        if (
            distribution.boundingBoxCoverageFraction <
            options.minimumSpatialCoverageFraction ||
            distribution.occupiedGridCells < options.minimumOccupiedGridCells
        ) {
            return StarMatchingFailureCode.INSUFFICIENT_SPATIAL_COVERAGE to
                "Inlier coverage=${distribution.boundingBoxCoverageFraction}, " +
                    "gridCells=${distribution.occupiedGridCells}."
        }
        return null
    }

    private fun spatialDistribution(
        stars: List<DetectedStar>,
        width: Int,
        height: Int,
        options: StarMatchingOptions
    ): StarSpatialDistribution {
        if (stars.isEmpty()) {
            return StarSpatialDistribution(
                0.0, 0.0, 0.0, 0.0, 0.0, 0,
                options.spatialGridColumns * options.spatialGridRows,
                0.0,
                true
            )
        }
        val minX = stars.minOf { it.fullX }
        val minY = stars.minOf { it.fullY }
        val maxX = stars.maxOf { it.fullX }
        val maxY = stars.maxOf { it.fullY }
        val coverage =
            ((maxX - minX).coerceAtLeast(0.0) * (maxY - minY).coerceAtLeast(0.0)) /
                (width.toDouble() * height.toDouble())
        val cells =
            stars.map {
                val column =
                    (it.fullX / width * options.spatialGridColumns)
                        .toInt().coerceIn(0, options.spatialGridColumns - 1)
                val row =
                    (it.fullY / height * options.spatialGridRows)
                        .toInt().coerceIn(0, options.spatialGridRows - 1)
                row * options.spatialGridColumns + column
            }.toSet().size

        val meanX = stars.sumOf { it.fullX } / stars.size
        val meanY = stars.sumOf { it.fullY } / stars.size
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        stars.forEach {
            val x = it.fullX - meanX
            val y = it.fullY - meanY
            xx += x * x
            yy += y * y
            xy += x * y
        }
        val trace = xx + yy
        val determinantTerm = sqrt(max(0.0, (xx - yy) * (xx - yy) + 4.0 * xy * xy))
        val largest = (trace + determinantTerm) * 0.5
        val smallest = (trace - determinantTerm) * 0.5
        val ratio = if (largest > 0.0) max(0.0, smallest) / largest else 0.0
        return StarSpatialDistribution(
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY,
            boundingBoxCoverageFraction = coverage,
            occupiedGridCells = cells,
            totalGridCells = options.spatialGridColumns * options.spatialGridRows,
            geometryEigenvalueRatio = ratio,
            degenerate = ratio < options.minimumGeometryEigenvalueRatio
        )
    }

    private fun referenceResult(
        position: Int,
        catalog: StarCatalog,
        options: StarMatchingOptions
    ): StarFrameAlignment {
        val filtered = StarCatalogFilter.filter(catalog, options)
        val alignment =
            AlignmentResult(
                frameIndex = catalog.frameIndex,
                mode = ResolvedAlignmentMode.STAR_ALIGNMENT,
                transform = RawTransform.IDENTITY,
                confidence = 1.0,
                accepted = true,
                diagnosticMessage = "Reference star catalog; identity transform."
            )
        return StarFrameAlignment(
            framePosition = position,
            frameIndex = catalog.frameIndex,
            isReference = true,
            detectedStarCount = filtered.detectedCount,
            eligibleStarCount = filtered.eligibleCount,
            retainedStarCount = filtered.retained.size,
            candidateMatchCount = 0,
            ransacInlierCount = filtered.retained.size,
            outlierCount = 0,
            inlierRatio = 1.0,
            rmsResidualRawPixels = 0.0,
            medianResidualRawPixels = 0.0,
            maximumResidualRawPixels = 0.0,
            transform = StarSimilarityTransform.IDENTITY,
            matchingStrategy = StarMatchingStrategy.IDENTITY,
            spatialDistribution = null,
            matches = emptyList(),
            accepted = true,
            failureCode = null,
            diagnosticMessage = alignment.diagnosticMessage,
            warnings = emptyList(),
            alignmentResult = alignment
        )
    }

    private fun rejected(
        position: Int,
        catalog: StarCatalog,
        code: StarMatchingFailureCode,
        message: String,
        matchResult: StarMatchResult? = null,
        ransac: StarRansacResult? = null,
        distribution: StarSpatialDistribution? = null
    ): StarFrameAlignment {
        val alignment =
            AlignmentResult(
                frameIndex = catalog.frameIndex,
                mode = ResolvedAlignmentMode.STAR_ALIGNMENT,
                transform = RawTransform.IDENTITY,
                confidence = ransac?.inlierRatio ?: 0.0,
                accepted = false,
                rejectionReason = code.name,
                diagnosticMessage = message
            )
        return StarFrameAlignment(
            framePosition = position,
            frameIndex = catalog.frameIndex,
            isReference = false,
            detectedStarCount = matchResult?.targetDetectedCount ?: catalog.starCount,
            eligibleStarCount = matchResult?.targetEligibleCount ?: 0,
            retainedStarCount = matchResult?.targetRetainedCount ?: 0,
            candidateMatchCount = matchResult?.candidates?.size ?: 0,
            ransacInlierCount = ransac?.inlierCount ?: 0,
            outlierCount = ransac?.outlierCount ?: 0,
            inlierRatio = ransac?.inlierRatio ?: 0.0,
            rmsResidualRawPixels = ransac?.rmsResidualRawPixels,
            medianResidualRawPixels = ransac?.medianResidualRawPixels,
            maximumResidualRawPixels = ransac?.maximumResidualRawPixels,
            transform = ransac?.transform ?: StarSimilarityTransform.IDENTITY,
            matchingStrategy = matchResult?.strategy ?: StarMatchingStrategy.NONE,
            spatialDistribution = distribution,
            matches = ransac?.matches ?: emptyList(),
            accepted = false,
            failureCode = code,
            diagnosticMessage = message,
            warnings = matchResult?.warnings ?: emptyList(),
            alignmentResult = alignment
        )
    }

    private fun validCatalog(catalog: StarCatalog): Boolean =
        catalog.frameIndex >= 0 &&
            catalog.sourceWidth > 0 &&
            catalog.sourceHeight > 0 &&
            catalog.proxyWidth > 0 &&
            catalog.proxyHeight > 0 &&
            catalog.scaleX > 0.0 &&
            catalog.scaleY > 0.0 &&
            catalog.scaleX.isFinite() &&
            catalog.scaleY.isFinite()

    private fun fatal(
        options: StarMatchingOptions,
        startedNs: Long,
        code: StarMatchingFailureCode,
        message: String,
        totalFrames: Int = 0,
        warnings: List<String> = emptyList()
    ): StarAlignmentReport =
        StarAlignmentReport(
            status = StarAlignmentStatus.FAILURE,
            success = false,
            partialSuccess = false,
            referenceFrameIndex = null,
            referenceCatalogPosition = null,
            referenceSelectionReason = null,
            referenceStarCount = 0,
            referenceMedianSnr = null,
            totalFrameCount = totalFrames,
            acceptedFrameCount = 0,
            rejectedFrameCount = totalFrames,
            frameResults = emptyList(),
            alignmentResults = emptyList(),
            warnings = warnings,
            fatalError = code,
            fatalMessage = message,
            durationMs = elapsedMs(startedNs),
            options = options
        )

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L
}
