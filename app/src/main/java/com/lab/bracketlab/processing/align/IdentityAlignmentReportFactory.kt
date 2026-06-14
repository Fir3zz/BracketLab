package com.lab.bracketlab.processing.align

import com.lab.bracketlab.processing.model.AlignmentResult
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode

object IdentityAlignmentReportFactory {
    fun create(rawStack: RawStack): LandscapeAlignmentReport {
        val selection = ReferenceFrameSelector.select(rawStack)
        val options = LandscapeAlignmentOptions(exposureNormalizeProxies = false)
        if (!selection.success) {
            return LandscapeAlignmentReport(
                status = LandscapeAlignmentStatus.FAILURE,
                success = false,
                partialSuccess = false,
                selectedReferenceFrameIndex = null,
                selectedReferencePosition = null,
                referenceSelectionMethod = selection.method,
                referenceExposureTimeSeconds = null,
                totalFrameCount = rawStack.frameCount,
                acceptedFrameCount = 0,
                rejectedFrameCount = rawStack.frameCount,
                lowConfidenceFrameCount = 0,
                frameResults = emptyList(),
                alignmentResults = emptyList(),
                warnings = selection.warnings,
                fatalError = selection.failureReason,
                fatalMessage = selection.message,
                processingDurationMs = 0L,
                options = options
            )
        }

        val frameResults =
            rawStack.frames.mapIndexed { position, frame ->
                identityFrame(
                    frame = frame,
                    position = position,
                    isReference = frame.frameIndex == selection.selectedFrameIndex,
                    referenceExposureSeconds = selection.referenceExposureTimeSeconds
                )
            }
        return LandscapeAlignmentReport(
            status = LandscapeAlignmentStatus.SUCCESS,
            success = true,
            partialSuccess = false,
            selectedReferenceFrameIndex = selection.selectedFrameIndex,
            selectedReferencePosition = selection.selectedPosition,
            referenceSelectionMethod = selection.method,
            referenceExposureTimeSeconds = selection.referenceExposureTimeSeconds,
            totalFrameCount = rawStack.frameCount,
            acceptedFrameCount = frameResults.size,
            rejectedFrameCount = 0,
            lowConfidenceFrameCount = 0,
            frameResults = frameResults,
            alignmentResults = frameResults.map { it.alignmentResult },
            warnings = selection.warnings,
            fatalError = null,
            fatalMessage = null,
            processingDurationMs = 0L,
            options = options
        )
    }

    private fun identityFrame(
        frame: RawFrame,
        position: Int,
        isReference: Boolean,
        referenceExposureSeconds: Double?
    ): LandscapeFrameAlignment {
        val alignment =
            AlignmentResult(
                frameIndex = frame.frameIndex,
                // The RAW16 stack core consumes the translation contract even when
                // alignment is disabled. Identity is therefore a zero translation.
                mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
                transform = RawTransform.IDENTITY,
                confidence = 1.0,
                accepted = true,
                diagnosticMessage =
                    if (isReference) {
                        "Reference identity transform."
                    } else {
                        "Identity transform requested."
                    },
                rawDx = 0.0,
                rawDy = 0.0,
                response = if (isReference) null else 1.0
            )
        return LandscapeFrameAlignment(
            framePosition = position,
            frameIndex = frame.frameIndex,
            isReference = isReference,
            proxyTypeUsed = null,
            lumaFallbackUsed = false,
            targetExposureTimeSeconds = frame.exposureTimeSeconds.takeIf { it > 0.0 },
            referenceExposureTimeSeconds = referenceExposureSeconds,
            phaseResponse = if (isReference) null else 1.0,
            dxRawPixels = 0.0,
            dyRawPixels = 0.0,
            overlapFraction = 1.0,
            accepted = true,
            lowConfidence = false,
            rejectionReason = null,
            diagnosticMessage = alignment.diagnosticMessage,
            warnings = emptyList(),
            alignmentResult = alignment
        )
    }
}
