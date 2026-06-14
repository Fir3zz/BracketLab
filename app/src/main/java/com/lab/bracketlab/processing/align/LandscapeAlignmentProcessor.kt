package com.lab.bracketlab.processing.align

import com.lab.bracketlab.processing.model.AlignmentResult
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.raw.RawProxy
import com.lab.bracketlab.processing.raw.RawProxyGenerator
import com.lab.bracketlab.processing.raw.RawProxyType
import com.lab.bracketlab.processing.stack.Raw16SampleAccessor
import kotlin.math.abs
import kotlin.math.max

/**
 * Coordinates landscape translation estimation for a whole RawStack.
 *
 * This class is synchronous and UI-free. Callers must run it outside the main
 * thread. It estimates transforms only; it never resamples RAW data.
 */
class LandscapeAlignmentProcessor(
    private val alignmentBackend: AlignmentBackend = OpenCvAlignmentBackend()
) {
    fun align(
        rawStack: RawStack,
        options: LandscapeAlignmentOptions = LandscapeAlignmentOptions()
    ): LandscapeAlignmentReport {
        val startedNs = System.nanoTime()
        val globalWarnings = mutableListOf<String>()

        val selection = ReferenceFrameSelector.select(rawStack)
        if (!selection.success) {
            return buildReport(
                rawStack = rawStack,
                selection = selection,
                frameResults = emptyList(),
                warnings = selection.warnings,
                fatalError = selection.failureReason,
                fatalMessage = selection.message,
                startedNs = startedNs,
                options = options
            )
        }

        globalWarnings += selection.warnings
        if (hasDuplicateFrameIndices(rawStack.frames)) {
            globalWarnings += "Duplicate frame indices detected; original list position remains authoritative."
        }

        val reference = rawStack.frames[selection.selectedPosition]
        val referenceValidation = validateFrame(reference, reference)
        if (referenceValidation != null) {
            return buildFatalFrameReport(
                rawStack = rawStack,
                selection = selection,
                reason = referenceValidation.reason,
                message = referenceValidation.message,
                warnings = globalWarnings,
                startedNs = startedNs,
                options = options
            )
        }

        if (rawStack.frames.size == 1) {
            val identity = identityFrameResult(
                frame = reference,
                framePosition = selection.selectedPosition,
                referenceExposureSeconds = selection.referenceExposureTimeSeconds
            )
            return buildReport(
                rawStack = rawStack,
                selection = selection,
                frameResults = listOf(identity),
                warnings = globalWarnings,
                fatalError = null,
                fatalMessage = null,
                startedNs = startedNs,
                options = options
            )
        }

        if (options.exposureNormalizeProxies && selection.referenceExposureTimeSeconds == null) {
            globalWarnings += "Reference exposure metadata is invalid; proxy exposure normalization disabled."
        }

        val referenceProxy =
            runCatching { createProxy(reference, options.primaryProxyType, options, referenceExposureSeconds = null) }
                .getOrElse {
                    return buildFatalFrameReport(
                        rawStack = rawStack,
                        selection = selection,
                        reason = LandscapeAlignmentRejectionReason.PROXY_GENERATION_FAILED,
                        message = "Reference proxy generation failed: ${it.message}",
                        warnings = globalWarnings,
                        startedNs = startedNs,
                        options = options
                    )
                }
        var referenceLumaProxy: RawProxy? = null

        val frameResults = rawStack.frames.mapIndexed { position, frame ->
            if (position == selection.selectedPosition) {
                identityFrameResult(
                    frame = frame,
                    framePosition = position,
                    referenceExposureSeconds = selection.referenceExposureTimeSeconds
                )
            } else {
                alignTargetFrame(
                    frame = frame,
                    framePosition = position,
                    reference = reference,
                    referenceProxy = referenceProxy,
                    referenceLumaProxyProvider = {
                        val existing = referenceLumaProxy
                        if (existing != null) {
                            existing
                        } else {
                            createProxy(reference, RawProxyType.LUMA_ALIGNMENT, options, referenceExposureSeconds = null)
                                .also { referenceLumaProxy = it }
                        }
                    },
                    referenceExposureSeconds = selection.referenceExposureTimeSeconds,
                    options = options
                )
            }
        }

        val orderedResults =
            if (options.preserveInputOrder) {
                frameResults
            } else {
                frameResults.sortedWith(compareBy<LandscapeFrameAlignment> { it.frameIndex }.thenBy { it.framePosition })
            }

        return buildReport(
            rawStack = rawStack,
            selection = selection,
            frameResults = orderedResults,
            warnings = globalWarnings,
            fatalError = null,
            fatalMessage = null,
            startedNs = startedNs,
            options = options
        )
    }

    private fun alignTargetFrame(
        frame: RawFrame,
        framePosition: Int,
        reference: RawFrame,
        referenceProxy: RawProxy,
        referenceLumaProxyProvider: () -> RawProxy,
        referenceExposureSeconds: Double?,
        options: LandscapeAlignmentOptions
    ): LandscapeFrameAlignment {
        val warnings = mutableListOf<String>()
        validateFrame(frame, reference)?.let {
            return rejectedFrameResult(
                frame = frame,
                framePosition = framePosition,
                referenceExposureSeconds = referenceExposureSeconds,
                reason = it.reason,
                message = it.message,
                warnings = warnings
            )
        }

        val normalizationExposure = referenceExposureForTarget(frame, referenceExposureSeconds, options, warnings)
        val targetProxy =
            runCatching { createProxy(frame, options.primaryProxyType, options, normalizationExposure) }
                .getOrElse {
                    return rejectedFrameResult(
                        frame = frame,
                        framePosition = framePosition,
                        referenceExposureSeconds = referenceExposureSeconds,
                        reason = LandscapeAlignmentRejectionReason.PROXY_GENERATION_FAILED,
                        message = "Target proxy generation failed: ${it.message}",
                        warnings = warnings
                    )
                }

        val primaryAttempt = estimateSafely(referenceProxy, targetProxy)
        val primaryReason = if (primaryAttempt.result.accepted) {
            null
        } else {
            mapBackendRejection(primaryAttempt.result)
        }

        val finalAttempt =
            if (
                options.allowLumaFallback &&
                primaryReason == LandscapeAlignmentRejectionReason.LOW_TEXTURE
            ) {
                val fallback = runCatching {
                    val referenceLuma = referenceLumaProxyProvider()
                    val targetLuma = createProxy(frame, RawProxyType.LUMA_ALIGNMENT, options, normalizationExposure)
                    estimateSafely(referenceLuma, targetLuma)
                }.getOrElse {
                    BackendAttempt(
                        result = rejectedAlignmentResult(
                            frame = frame,
                            reason = LandscapeAlignmentRejectionReason.PROXY_GENERATION_FAILED,
                            message = "Luma fallback proxy generation failed: ${it.message}"
                        ),
                        proxyType = RawProxyType.LUMA_ALIGNMENT,
                        lumaFallbackUsed = true
                    )
                }
                fallback.copy(lumaFallbackUsed = true, previousDiagnostic = primaryAttempt.result.diagnosticMessage)
            } else {
                primaryAttempt
            }

        return validateBackendResult(
            frame = frame,
            framePosition = framePosition,
            referenceExposureSeconds = referenceExposureSeconds,
            attempt = finalAttempt,
            options = options,
            warnings = warnings
        )
    }

    private fun referenceExposureForTarget(
        frame: RawFrame,
        referenceExposureSeconds: Double?,
        options: LandscapeAlignmentOptions,
        warnings: MutableList<String>
    ): Double? {
        if (!options.exposureNormalizeProxies) return null
        val targetExposureSeconds = validExposureSeconds(frame)
        if (targetExposureSeconds == null || referenceExposureSeconds == null) {
            warnings += "Exposure normalization skipped because exposure metadata is invalid."
            return null
        }
        return referenceExposureSeconds
    }

    private fun validateBackendResult(
        frame: RawFrame,
        framePosition: Int,
        referenceExposureSeconds: Double?,
        attempt: BackendAttempt,
        options: LandscapeAlignmentOptions,
        warnings: MutableList<String>
    ): LandscapeFrameAlignment {
        val result = attempt.result
        val backendReason = if (result.accepted) null else mapBackendRejection(result)
        if (backendReason != null) {
            val backendMessage =
                mergeDiagnostics(
                    attempt.previousDiagnostic,
                    result.diagnosticMessage ?: result.rejectionReason ?: backendReason.name
                ) ?: backendReason.name
            return rejectedFrameResult(
                frame = frame,
                framePosition = framePosition,
                referenceExposureSeconds = referenceExposureSeconds,
                reason = backendReason,
                message = backendMessage,
                warnings = warnings,
                proxyType = attempt.proxyType,
                lumaFallbackUsed = attempt.lumaFallbackUsed,
                alignmentResult = result
            )
        }

        val transform = result.transform
        if (options.rejectNonFiniteShift && (!transform.dx.isUsableFinite() || !transform.dy.isUsableFinite())) {
            return rejectedFromAcceptedResult(
                frame = frame,
                framePosition = framePosition,
                referenceExposureSeconds = referenceExposureSeconds,
                result = result,
                reason = LandscapeAlignmentRejectionReason.NON_FINITE_SHIFT,
                message = "Alignment transform contains a non-finite shift.",
                warnings = warnings,
                attempt = attempt
            )
        }

        if (result.mode != ResolvedAlignmentMode.LANDSCAPE_TRANSLATION || !isTranslationOnly(transform)) {
            return rejectedFromAcceptedResult(
                frame = frame,
                framePosition = framePosition,
                referenceExposureSeconds = referenceExposureSeconds,
                result = result,
                reason = LandscapeAlignmentRejectionReason.INVALID_TRANSFORM_TYPE,
                message = "Alignment transform is not pure translation.",
                warnings = warnings,
                attempt = attempt
            )
        }

        val maxDx = frame.width.toDouble() * options.maximumShiftFractionX
        val maxDy = frame.height.toDouble() * options.maximumShiftFractionY
        if (abs(transform.dx) > maxDx || abs(transform.dy) > maxDy) {
            return rejectedFromAcceptedResult(
                frame = frame,
                framePosition = framePosition,
                referenceExposureSeconds = referenceExposureSeconds,
                result = result,
                reason = LandscapeAlignmentRejectionReason.EXCESSIVE_SHIFT,
                message = "Shift exceeds configured maximum fraction.",
                warnings = warnings,
                attempt = attempt
            )
        }

        val overlap = overlapFraction(frame.width, frame.height, transform.dx, transform.dy)
        if (overlap < options.minimumOverlapFraction) {
            return rejectedFromAcceptedResult(
                frame = frame,
                framePosition = framePosition,
                referenceExposureSeconds = referenceExposureSeconds,
                result = result,
                reason = LandscapeAlignmentRejectionReason.INSUFFICIENT_OVERLAP,
                message = "Overlap is below configured minimum.",
                warnings = warnings,
                attempt = attempt,
                overlapFraction = overlap
            )
        }

        val response = result.response ?: result.confidence
        val lowConfidence = response < options.minimumPhaseResponse
        if (lowConfidence) {
            val message = "Phase response $response is below provisional threshold ${options.minimumPhaseResponse}."
            if (options.strictResponseRejection) {
                return rejectedFromAcceptedResult(
                    frame = frame,
                    framePosition = framePosition,
                    referenceExposureSeconds = referenceExposureSeconds,
                    result = result,
                    reason = LandscapeAlignmentRejectionReason.LOW_PHASE_RESPONSE,
                    message = message,
                    warnings = warnings,
                    attempt = attempt,
                    overlapFraction = overlap
                )
            }
            warnings += message
        }

        return LandscapeFrameAlignment(
            framePosition = framePosition,
            frameIndex = frame.frameIndex,
            isReference = false,
            proxyTypeUsed = attempt.proxyType,
            lumaFallbackUsed = attempt.lumaFallbackUsed,
            targetExposureTimeSeconds = validExposureSeconds(frame),
            referenceExposureTimeSeconds = referenceExposureSeconds,
            phaseResponse = response,
            dxRawPixels = transform.dx,
            dyRawPixels = transform.dy,
            overlapFraction = overlap,
            accepted = true,
            lowConfidence = lowConfidence,
            rejectionReason = null,
            diagnosticMessage = mergeDiagnostics(attempt.previousDiagnostic, result.diagnosticMessage),
            warnings = warnings.toList(),
            alignmentResult = result
        )
    }

    private fun estimateSafely(referenceProxy: RawProxy, targetProxy: RawProxy): BackendAttempt =
        try {
            BackendAttempt(
                result = alignmentBackend.estimateTranslation(referenceProxy, targetProxy),
                proxyType = targetProxy.proxyType,
                lumaFallbackUsed = targetProxy.proxyType == RawProxyType.LUMA_ALIGNMENT
            )
        } catch (e: Throwable) {
            BackendAttempt(
                result = AlignmentResult(
                    frameIndex = targetProxy.sourceFrameIndex,
                    mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
                    accepted = false,
                    confidence = 0.0,
                    rejectionReason = LandscapeAlignmentRejectionReason.BACKEND_FAILURE.name,
                    diagnosticMessage = "Alignment backend threw ${e::class.java.simpleName}: ${e.message}"
                ),
                proxyType = targetProxy.proxyType,
                lumaFallbackUsed = targetProxy.proxyType == RawProxyType.LUMA_ALIGNMENT
            )
        }

    private fun createProxy(
        frame: RawFrame,
        proxyType: RawProxyType,
        options: LandscapeAlignmentOptions,
        referenceExposureSeconds: Double?
    ): RawProxy =
        when (proxyType) {
            RawProxyType.GREEN ->
                RawProxyGenerator.extractGreenProxy(frame, options.proxyMaxDimension, referenceExposureSeconds)
            RawProxyType.LUMA_ALIGNMENT ->
                RawProxyGenerator.extractLumaProxyForAlignment(frame, options.proxyMaxDimension, referenceExposureSeconds)
        }

    private fun validateFrame(frame: RawFrame, reference: RawFrame): FrameValidationFailure? {
        if (frame.frameIndex < 0) {
            return FrameValidationFailure(
                LandscapeAlignmentRejectionReason.INVALID_FRAME_INDEX,
                "Frame index must be non-negative."
            )
        }
        if (frame.width <= 1 || frame.height <= 1 || reference.width <= 1 || reference.height <= 1) {
            return FrameValidationFailure(
                LandscapeAlignmentRejectionReason.INVALID_FRAME_DIMENSIONS,
                "Frame dimensions must be greater than 1."
            )
        }
        if (frame.width != reference.width || frame.height != reference.height) {
            return FrameValidationFailure(
                LandscapeAlignmentRejectionReason.INVALID_FRAME_DIMENSIONS,
                "Frame dimensions differ from reference."
            )
        }
        if (!isSupportedCfa(frame.cfaPattern) || !isSupportedCfa(reference.cfaPattern)) {
            return FrameValidationFailure(
                LandscapeAlignmentRejectionReason.INVALID_CFA_PATTERN,
                "CFA pattern is missing or unknown."
            )
        }
        if (frame.cfaPattern != reference.cfaPattern) {
            return FrameValidationFailure(
                LandscapeAlignmentRejectionReason.INVALID_CFA_PATTERN,
                "CFA pattern differs from reference."
            )
        }
        if (frame.cameraId != null && reference.cameraId != null && frame.cameraId != reference.cameraId) {
            return FrameValidationFailure(
                LandscapeAlignmentRejectionReason.INCOMPATIBLE_CAMERA,
                "Frame camera ID differs from reference."
            )
        }
        if (frame.pixelStride <= 0 || frame.rowStride <= 0) {
            return FrameValidationFailure(
                LandscapeAlignmentRejectionReason.INVALID_RAW_BUFFER,
                "RAW rowStride/pixelStride must be positive."
            )
        }
        val storageValidation = Raw16SampleAccessor.validate(frame)
        if (!storageValidation.valid) {
            return FrameValidationFailure(
                LandscapeAlignmentRejectionReason.INVALID_RAW_BUFFER,
                storageValidation.message ?: "RAW storage is invalid."
            )
        }
        return null
    }

    private fun identityFrameResult(
        frame: RawFrame,
        framePosition: Int,
        referenceExposureSeconds: Double?
    ): LandscapeFrameAlignment {
        val result = AlignmentResult(
            frameIndex = frame.frameIndex,
            mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
            transform = RawTransform.IDENTITY,
            confidence = 1.0,
            accepted = true,
            diagnosticMessage = "Reference frame identity transform."
        )
        return LandscapeFrameAlignment(
            framePosition = framePosition,
            frameIndex = frame.frameIndex,
            isReference = true,
            proxyTypeUsed = null,
            lumaFallbackUsed = false,
            targetExposureTimeSeconds = validExposureSeconds(frame),
            referenceExposureTimeSeconds = referenceExposureSeconds,
            phaseResponse = null,
            dxRawPixels = 0.0,
            dyRawPixels = 0.0,
            overlapFraction = 1.0,
            accepted = true,
            lowConfidence = false,
            rejectionReason = null,
            diagnosticMessage = result.diagnosticMessage,
            warnings = emptyList(),
            alignmentResult = result
        )
    }

    private fun rejectedFrameResult(
        frame: RawFrame,
        framePosition: Int,
        referenceExposureSeconds: Double?,
        reason: LandscapeAlignmentRejectionReason,
        message: String,
        warnings: List<String>,
        proxyType: RawProxyType? = null,
        lumaFallbackUsed: Boolean = false,
        alignmentResult: AlignmentResult = rejectedAlignmentResult(frame, reason, message),
        overlapFraction: Double? = null
    ): LandscapeFrameAlignment =
        LandscapeFrameAlignment(
            framePosition = framePosition,
            frameIndex = frame.frameIndex,
            isReference = false,
            proxyTypeUsed = proxyType,
            lumaFallbackUsed = lumaFallbackUsed,
            targetExposureTimeSeconds = validExposureSeconds(frame),
            referenceExposureTimeSeconds = referenceExposureSeconds,
            phaseResponse = alignmentResult.response ?: alignmentResult.confidence,
            dxRawPixels = alignmentResult.transform.dx,
            dyRawPixels = alignmentResult.transform.dy,
            overlapFraction = overlapFraction,
            accepted = false,
            lowConfidence = false,
            rejectionReason = reason,
            diagnosticMessage = message,
            warnings = warnings,
            alignmentResult = alignmentResult
        )

    private fun rejectedFromAcceptedResult(
        frame: RawFrame,
        framePosition: Int,
        referenceExposureSeconds: Double?,
        result: AlignmentResult,
        reason: LandscapeAlignmentRejectionReason,
        message: String,
        warnings: List<String>,
        attempt: BackendAttempt,
        overlapFraction: Double? = null
    ): LandscapeFrameAlignment {
        val rejected = result.copy(
            accepted = false,
            rejectionReason = reason.name,
            diagnosticMessage = mergeDiagnostics(result.diagnosticMessage, message)
        )
        return rejectedFrameResult(
            frame = frame,
            framePosition = framePosition,
            referenceExposureSeconds = referenceExposureSeconds,
            reason = reason,
            message = message,
            warnings = warnings,
            proxyType = attempt.proxyType,
            lumaFallbackUsed = attempt.lumaFallbackUsed,
            alignmentResult = rejected,
            overlapFraction = overlapFraction
        )
    }

    private fun rejectedAlignmentResult(
        frame: RawFrame,
        reason: LandscapeAlignmentRejectionReason,
        message: String
    ): AlignmentResult =
        AlignmentResult(
            frameIndex = frame.frameIndex,
            mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
            transform = RawTransform.IDENTITY,
            confidence = 0.0,
            accepted = false,
            rejectionReason = reason.name,
            diagnosticMessage = message
        )

    private fun buildFatalFrameReport(
        rawStack: RawStack,
        selection: ReferenceFrameSelection,
        reason: LandscapeAlignmentRejectionReason,
        message: String,
        warnings: List<String>,
        startedNs: Long,
        options: LandscapeAlignmentOptions
    ): LandscapeAlignmentReport {
        val frameResults = rawStack.frames.mapIndexed { position, frame ->
            val isReference = position == selection.selectedPosition
            rejectedFrameResult(
                frame = frame,
                framePosition = position,
                referenceExposureSeconds = selection.referenceExposureTimeSeconds,
                reason = if (isReference) reason else LandscapeAlignmentRejectionReason.BACKEND_FAILURE,
                message = if (isReference) message else "Reference frame failed: $message",
                warnings = emptyList()
            ).copy(isReference = isReference)
        }
        return buildReport(
            rawStack = rawStack,
            selection = selection,
            frameResults = frameResults,
            warnings = warnings,
            fatalError = reason,
            fatalMessage = message,
            startedNs = startedNs,
            options = options
        )
    }

    private fun buildReport(
        rawStack: RawStack,
        selection: ReferenceFrameSelection,
        frameResults: List<LandscapeFrameAlignment>,
        warnings: List<String>,
        fatalError: LandscapeAlignmentRejectionReason?,
        fatalMessage: String?,
        startedNs: Long,
        options: LandscapeAlignmentOptions
    ): LandscapeAlignmentReport {
        val accepted = frameResults.count { it.accepted }
        val rejected = frameResults.count { !it.accepted }
        val lowConfidence = frameResults.count { it.lowConfidence }
        val status = when {
            fatalError != null -> LandscapeAlignmentStatus.FAILURE
            rejected == 0 -> LandscapeAlignmentStatus.SUCCESS
            accepted > 0 -> LandscapeAlignmentStatus.PARTIAL_SUCCESS
            else -> LandscapeAlignmentStatus.FAILURE
        }
        val frameWarnings = frameResults.flatMap { frame ->
            frame.warnings.map { "Frame ${frame.frameIndex}: $it" }
        }
        return LandscapeAlignmentReport(
            status = status,
            success = status == LandscapeAlignmentStatus.SUCCESS,
            partialSuccess = status == LandscapeAlignmentStatus.PARTIAL_SUCCESS,
            selectedReferenceFrameIndex = selection.selectedFrameIndex.takeIf { selection.success },
            selectedReferencePosition = selection.selectedPosition.takeIf { selection.success },
            referenceSelectionMethod = selection.method,
            referenceExposureTimeSeconds = selection.referenceExposureTimeSeconds,
            totalFrameCount = rawStack.frameCount,
            acceptedFrameCount = accepted,
            rejectedFrameCount = rejected,
            lowConfidenceFrameCount = lowConfidence,
            frameResults = frameResults,
            alignmentResults = frameResults.map { it.alignmentResult },
            warnings = warnings + frameWarnings,
            fatalError = fatalError,
            fatalMessage = fatalMessage,
            processingDurationMs = (System.nanoTime() - startedNs) / 1_000_000L,
            options = options
        )
    }

    private fun mapBackendRejection(result: AlignmentResult): LandscapeAlignmentRejectionReason {
        val text = "${result.rejectionReason.orEmpty()} ${result.diagnosticMessage.orEmpty()}".lowercase()
        return when {
            "textureless" in text || "low texture" in text -> LandscapeAlignmentRejectionReason.LOW_TEXTURE
            "opencvloader" in text || "opencv initialization" in text || "native runtime" in text ->
                LandscapeAlignmentRejectionReason.OPENCV_UNAVAILABLE
            "non-finite" in text -> LandscapeAlignmentRejectionReason.NON_FINITE_SHIFT
            "dimension" in text -> LandscapeAlignmentRejectionReason.INVALID_FRAME_DIMENSIONS
            "proxy" in text -> LandscapeAlignmentRejectionReason.PROXY_GENERATION_FAILED
            "backend_failure" in text -> LandscapeAlignmentRejectionReason.BACKEND_FAILURE
            else -> LandscapeAlignmentRejectionReason.BACKEND_FAILURE
        }
    }

    private fun overlapFraction(width: Int, height: Int, dx: Double, dy: Double): Double {
        val overlapWidth = max(0.0, width.toDouble() - abs(dx))
        val overlapHeight = max(0.0, height.toDouble() - abs(dy))
        return (overlapWidth * overlapHeight) / (width.toDouble() * height.toDouble())
    }

    private fun isTranslationOnly(transform: RawTransform): Boolean =
        transform.rotationDegrees.isUsableFinite() &&
            transform.scale.isUsableFinite() &&
            abs(transform.rotationDegrees) <= 0.000001 &&
            abs(transform.scale - 1.0) <= 0.000001

    private fun isSupportedCfa(pattern: CfaPattern?): Boolean =
        pattern != null && pattern != CfaPattern.UNKNOWN

    private fun validExposureSeconds(frame: RawFrame): Double? =
        frame.exposureTimeSeconds.takeIf { it > 0.0 && it.isUsableFinite() }

    private fun hasDuplicateFrameIndices(frames: List<RawFrame>): Boolean =
        frames.map { it.frameIndex }.toSet().size != frames.size

    private fun mergeDiagnostics(first: String?, second: String?): String? {
        val merged = listOfNotNull(first, second).filter { it.isNotBlank() }.joinToString(" | ")
        return merged.takeIf { it.isNotBlank() }
    }

    private fun Double.isUsableFinite(): Boolean =
        !isNaN() && abs(this) != Double.POSITIVE_INFINITY

    private data class FrameValidationFailure(
        val reason: LandscapeAlignmentRejectionReason,
        val message: String
    )

    private data class BackendAttempt(
        val result: AlignmentResult,
        val proxyType: RawProxyType,
        val lumaFallbackUsed: Boolean,
        val previousDiagnostic: String? = null
    )
}
