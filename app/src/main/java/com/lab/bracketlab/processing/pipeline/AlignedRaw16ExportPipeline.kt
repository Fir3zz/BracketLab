package com.lab.bracketlab.processing.pipeline

import com.lab.bracketlab.processing.align.LandscapeAlignmentOptions
import com.lab.bracketlab.processing.align.LandscapeAlignmentProcessor
import com.lab.bracketlab.processing.align.LandscapeAlignmentReport
import com.lab.bracketlab.processing.io.Raw16DngFailureCode
import com.lab.bracketlab.processing.io.Raw16DngOutputDestination
import com.lab.bracketlab.processing.io.Raw16DngWriteOptions
import com.lab.bracketlab.processing.io.Raw16DngWriteRequest
import com.lab.bracketlab.processing.io.Raw16DngWriteResult
import com.lab.bracketlab.processing.io.Raw16DngWriter
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.stack.AlignedRaw16StackOptions
import com.lab.bracketlab.processing.stack.AlignedRaw16StackProcessor
import com.lab.bracketlab.processing.stack.AlignedRaw16StackResult

enum class AlignedRaw16ExportFailureStage {
    INPUT,
    ALIGNMENT,
    STACKING,
    METADATA,
    WRITING,
    STORAGE
}

data class AlignedRaw16ExportResult(
    val overallSuccess: Boolean,
    val failureStage: AlignedRaw16ExportFailureStage?,
    val failureCode: Raw16DngFailureCode?,
    val failureMessage: String?,
    val alignmentReport: LandscapeAlignmentReport?,
    val alignedStackResult: AlignedRaw16StackResult?,
    val dngWriteResult: Raw16DngWriteResult?,
    val totalDurationMs: Long,
    val warnings: List<String>
)

data class AlignedRaw16ExportPipelineDependencies(
    val align: (RawStack, LandscapeAlignmentOptions) -> LandscapeAlignmentReport =
        { stack, options -> LandscapeAlignmentProcessor().align(stack, options) },
    val stackAligned: (RawStack, LandscapeAlignmentReport, AlignedRaw16StackOptions) -> AlignedRaw16StackResult =
        { rawStack, report, options -> AlignedRaw16StackProcessor().process(rawStack, report, options) },
    val write: (Raw16DngWriteRequest) -> Raw16DngWriteResult =
        { request -> Raw16DngWriter.write(request) }
)

/**
 * Backend-only export pipeline. It is synchronous and must be called from a
 * worker thread, never directly from Android UI callbacks.
 */
class AlignedRaw16ExportPipeline(
    private val dependencies: AlignedRaw16ExportPipelineDependencies =
        AlignedRaw16ExportPipelineDependencies()
) {
    fun processAndWrite(
        rawStack: RawStack,
        cameraMetadataProvider: CameraMetadataProvider,
        alignmentOptions: LandscapeAlignmentOptions = LandscapeAlignmentOptions(),
        stackOptions: AlignedRaw16StackOptions = AlignedRaw16StackOptions(),
        writeOptions: Raw16DngWriteOptions = Raw16DngWriteOptions(),
        outputDestination: Raw16DngOutputDestination
    ): AlignedRaw16ExportResult {
        val startedNs = System.nanoTime()
        if (rawStack.frames.isEmpty()) {
            return failure(
                stage = AlignedRaw16ExportFailureStage.INPUT,
                code = Raw16DngFailureCode.INVALID_STACK_RESULT,
                message = "RawStack is empty.",
                startedNs = startedNs
            )
        }

        val alignmentReport = dependencies.align(rawStack, alignmentOptions)
        if (alignmentReport.fatalError != null || alignmentReport.acceptedFrameCount == 0) {
            return failure(
                stage = AlignedRaw16ExportFailureStage.ALIGNMENT,
                code = Raw16DngFailureCode.PIPELINE_ALIGNMENT_FAILED,
                message = alignmentReport.fatalMessage ?: "Alignment failed.",
                startedNs = startedNs,
                alignmentReport = alignmentReport,
                warnings = alignmentReport.warnings
            )
        }

        val stackResult = dependencies.stackAligned(rawStack, alignmentReport, stackOptions)
        if (!stackResult.success) {
            return failure(
                stage = AlignedRaw16ExportFailureStage.STACKING,
                code = Raw16DngFailureCode.PIPELINE_STACKING_FAILED,
                message = stackResult.fatalMessage ?: stackResult.fatalError?.name ?: "Aligned RAW16 stacking failed.",
                startedNs = startedNs,
                alignmentReport = alignmentReport,
                alignedStackResult = stackResult,
                warnings = combinedWarnings(alignmentReport, stackResult)
            )
        }

        val referenceFrameIndex = stackResult.referenceFrameIndex
        if (referenceFrameIndex == null) {
            return failure(
                stage = AlignedRaw16ExportFailureStage.METADATA,
                code = Raw16DngFailureCode.PIPELINE_METADATA_FAILED,
                message = "Stack result has no reference frame index.",
                startedNs = startedNs,
                alignmentReport = alignmentReport,
                alignedStackResult = stackResult,
                warnings = combinedWarnings(alignmentReport, stackResult)
            )
        }
        val referenceFrame = rawStack.frames.firstOrNull { it.frameIndex == referenceFrameIndex }
        if (referenceFrame == null) {
            return failure(
                stage = AlignedRaw16ExportFailureStage.METADATA,
                code = Raw16DngFailureCode.PIPELINE_METADATA_FAILED,
                message = "Reference frame $referenceFrameIndex is missing from RawStack.",
                startedNs = startedNs,
                alignmentReport = alignmentReport,
                alignedStackResult = stackResult,
                warnings = combinedWarnings(alignmentReport, stackResult)
            )
        }

        val cameraId = referenceFrame.cameraId ?: rawStack.cameraId
        val metadata = cameraMetadataProvider.getReferenceMetadata(referenceFrameIndex, cameraId)
        if (metadata.frameIndex != referenceFrameIndex || !metadata.complete) {
            return failure(
                stage = AlignedRaw16ExportFailureStage.METADATA,
                code = Raw16DngFailureCode.PIPELINE_METADATA_FAILED,
                message = "Camera2 metadata for reference frame $referenceFrameIndex is unavailable or mismatched.",
                startedNs = startedNs,
                alignmentReport = alignmentReport,
                alignedStackResult = stackResult,
                warnings = combinedWarnings(alignmentReport, stackResult)
            )
        }

        val request = Raw16DngWriteRequest(
            alignedResult = stackResult,
            cameraCharacteristics = metadata.cameraCharacteristics,
            captureResult = metadata.captureResult,
            destination = outputDestination,
            referenceFrameIndex = referenceFrameIndex,
            metadataFrameIndex = metadata.frameIndex,
            expectedCameraId = cameraId,
            metadataCameraId = metadata.cameraId,
            expectedWidth = referenceFrame.width,
            expectedHeight = referenceFrame.height,
            orientation = writeOptions.orientation ?: metadata.dngOrientation,
            description = writeOptions.description,
            location = writeOptions.location,
            sequenceIdentifier = writeOptions.sequenceIdentifier
        )
        val writeResult = dependencies.write(request)
        if (!writeResult.success) {
            return failure(
                stage = AlignedRaw16ExportFailureStage.WRITING,
                code = writeResult.failureCode ?: Raw16DngFailureCode.PIPELINE_WRITING_FAILED,
                message = writeResult.failureMessage ?: "DNG writer failed.",
                startedNs = startedNs,
                alignmentReport = alignmentReport,
                alignedStackResult = stackResult,
                dngWriteResult = writeResult,
                warnings = combinedWarnings(alignmentReport, stackResult, writeResult)
            )
        }

        return AlignedRaw16ExportResult(
            overallSuccess = true,
            failureStage = null,
            failureCode = null,
            failureMessage = null,
            alignmentReport = alignmentReport,
            alignedStackResult = stackResult,
            dngWriteResult = writeResult,
            totalDurationMs = elapsedMs(startedNs),
            warnings = combinedWarnings(alignmentReport, stackResult, writeResult)
        )
    }

    private fun failure(
        stage: AlignedRaw16ExportFailureStage,
        code: Raw16DngFailureCode,
        message: String,
        startedNs: Long,
        alignmentReport: LandscapeAlignmentReport? = null,
        alignedStackResult: AlignedRaw16StackResult? = null,
        dngWriteResult: Raw16DngWriteResult? = null,
        warnings: List<String> = emptyList()
    ): AlignedRaw16ExportResult =
        AlignedRaw16ExportResult(
            overallSuccess = false,
            failureStage = stage,
            failureCode = code,
            failureMessage = message,
            alignmentReport = alignmentReport,
            alignedStackResult = alignedStackResult,
            dngWriteResult = dngWriteResult,
            totalDurationMs = elapsedMs(startedNs),
            warnings = warnings
        )

    private fun combinedWarnings(
        alignmentReport: LandscapeAlignmentReport?,
        stackResult: AlignedRaw16StackResult? = null,
        writeResult: Raw16DngWriteResult? = null
    ): List<String> =
        buildList {
            alignmentReport?.warnings?.let { addAll(it) }
            stackResult?.warnings?.mapTo(this) { "${it.code}: ${it.message}" }
            writeResult?.warnings?.mapTo(this) { "${it.code}: ${it.message}" }
        }

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L
}
