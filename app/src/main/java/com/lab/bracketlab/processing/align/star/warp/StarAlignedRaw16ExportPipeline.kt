package com.lab.bracketlab.processing.align.star.warp

import com.lab.bracketlab.processing.align.star.StarAlignmentReport
import com.lab.bracketlab.processing.io.Raw16DngFailureCode
import com.lab.bracketlab.processing.io.Raw16DngOutputDestination
import com.lab.bracketlab.processing.io.Raw16DngWriteOptions
import com.lab.bracketlab.processing.io.Raw16DngWriteRequest
import com.lab.bracketlab.processing.io.Raw16DngWriteResult
import com.lab.bracketlab.processing.io.Raw16DngWriter
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.pipeline.CameraMetadataProvider
import java.io.File

enum class StarAlignedRaw16ExportFailureStage {
    INPUT,
    STACKING,
    METADATA,
    WRITING
}

data class StarAlignedRaw16ExportResult(
    val success: Boolean,
    val failureStage: StarAlignedRaw16ExportFailureStage? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val stackResult: StarAlignedRaw16StackResult? = null,
    val dngWriteResult: Raw16DngWriteResult? = null,
    val warnings: List<String> = emptyList(),
    val totalDurationMs: Long = 0L,
    val temporaryRawCleanupSucceeded: Boolean = true
)

data class StarAlignedRaw16ExportDependencies(
    val stack:
        (RawStack, StarAlignmentReport, StarAlignedRaw16StackOptions, File) ->
            StarAlignedRaw16StackResult =
        { rawStack, alignment, options, output ->
            StarAlignedRaw16StackProcessor().processToPackedFile(
                rawStack,
                alignment,
                options,
                output
            )
        },
    val write: (Raw16DngWriteRequest) -> Raw16DngWriteResult =
        { request -> Raw16DngWriter.write(request) }
)

/**
 * Backend-only star stack export. The supplied alignment report is consumed
 * verbatim; this class never reruns detection, matching, RANSAC or refinement.
 */
class StarAlignedRaw16ExportPipeline(
    private val dependencies: StarAlignedRaw16ExportDependencies =
        StarAlignedRaw16ExportDependencies()
) {
    fun processAndWrite(
        rawStack: RawStack,
        alignmentReport: StarAlignmentReport,
        cameraMetadataProvider: CameraMetadataProvider,
        stackOptions: StarAlignedRaw16StackOptions,
        writeOptions: Raw16DngWriteOptions,
        outputDestination: Raw16DngOutputDestination,
        temporaryPackedRaw16: File
    ): StarAlignedRaw16ExportResult {
        val startedNs = System.nanoTime()
        if (rawStack.frames.isEmpty()) {
            return failure(
                StarAlignedRaw16ExportFailureStage.INPUT,
                StarAlignedRaw16FailureCode.EMPTY_STAR_STACK.name,
                "RawStack is empty.",
                startedNs
            )
        }
        if (!alignmentReport.success && !alignmentReport.partialSuccess) {
            return failure(
                StarAlignedRaw16ExportFailureStage.INPUT,
                StarAlignedRaw16FailureCode.INVALID_STAR_ALIGNMENT_REPORT.name,
                alignmentReport.fatalMessage ?: "Star alignment report is not usable.",
                startedNs,
                warnings = alignmentReport.warnings
            )
        }

        val stackResult =
            dependencies.stack(
                rawStack,
                alignmentReport,
                stackOptions,
                temporaryPackedRaw16
            )
        val stackWarnings = stackResult.warnings.map { "${it.code}: ${it.message}" }
        if (!stackResult.success) {
            val cleaned =
                !temporaryPackedRaw16.exists() || temporaryPackedRaw16.delete()
            return failure(
                StarAlignedRaw16ExportFailureStage.STACKING,
                stackResult.fatalError?.name
                    ?: StarAlignedRaw16FailureCode.WARP_PROCESSING_FAILED.name,
                stackResult.fatalMessage ?: "Star-aligned RAW16 stack failed.",
                startedNs,
                stackResult,
                warnings = alignmentReport.warnings + stackWarnings,
                cleanupSucceeded = cleaned
            )
        }

        val referenceIndex = stackResult.referenceFrameIndex
        val referenceFrame =
            rawStack.frames.firstOrNull { it.frameIndex == referenceIndex }
        if (referenceIndex == null || referenceFrame == null) {
            val cleaned =
                !temporaryPackedRaw16.exists() || temporaryPackedRaw16.delete()
            return failure(
                StarAlignedRaw16ExportFailureStage.METADATA,
                Raw16DngFailureCode.PIPELINE_METADATA_FAILED.name,
                "The star reference frame is missing from RawStack.",
                startedNs,
                stackResult,
                warnings = alignmentReport.warnings + stackWarnings,
                cleanupSucceeded = cleaned
            )
        }
        val cameraId = referenceFrame.cameraId ?: rawStack.cameraId
        val metadata =
            cameraMetadataProvider.getReferenceMetadata(referenceIndex, cameraId)
        if (
            !metadata.complete ||
            metadata.frameIndex != referenceIndex ||
            (cameraId != null && metadata.cameraId != null && metadata.cameraId != cameraId)
        ) {
            val cleaned =
                !temporaryPackedRaw16.exists() || temporaryPackedRaw16.delete()
            return failure(
                StarAlignedRaw16ExportFailureStage.METADATA,
                Raw16DngFailureCode.REFERENCE_METADATA_MISMATCH.name,
                "Camera2 metadata does not belong to star reference frame $referenceIndex.",
                startedNs,
                stackResult,
                warnings = alignmentReport.warnings + stackWarnings,
                cleanupSucceeded = cleaned
            )
        }

        val description =
            writeOptions.description
                ?: defaultDescription(stackResult, stackOptions)
        val writerResult =
            dependencies.write(
                Raw16DngWriteRequest(
                    alignedResult = stackResult.asWriterCompatibleResult(),
                    cameraCharacteristics = metadata.cameraCharacteristics,
                    captureResult = metadata.captureResult,
                    destination = outputDestination,
                    referenceFrameIndex = referenceIndex,
                    metadataFrameIndex = metadata.frameIndex,
                    expectedCameraId = cameraId,
                    metadataCameraId = metadata.cameraId,
                    expectedWidth = referenceFrame.width,
                    expectedHeight = referenceFrame.height,
                    orientation = writeOptions.orientation ?: metadata.dngOrientation,
                    description = description,
                    location = writeOptions.location,
                    sequenceIdentifier = writeOptions.sequenceIdentifier,
                    deletePackedInputAfterWrite = true
                )
            )
        val cleaned = !temporaryPackedRaw16.exists()
        if (!writerResult.success) {
            return failure(
                StarAlignedRaw16ExportFailureStage.WRITING,
                writerResult.failureCode?.name
                    ?: StarAlignedRaw16FailureCode.DNG_WRITE_FAILED.name,
                writerResult.failureMessage ?: "DNG writing failed.",
                startedNs,
                stackResult,
                writerResult,
                alignmentReport.warnings +
                    stackWarnings +
                    writerResult.warnings.map { "${it.code}: ${it.message}" },
                cleaned
            )
        }
        return StarAlignedRaw16ExportResult(
            success = true,
            stackResult = stackResult,
            dngWriteResult = writerResult,
            warnings =
                alignmentReport.warnings +
                    stackWarnings +
                    writerResult.warnings.map { "${it.code}: ${it.message}" },
            totalDurationMs = elapsedMs(startedNs),
            temporaryRawCleanupSucceeded = cleaned
        )
    }

    private fun defaultDescription(
        result: StarAlignedRaw16StackResult,
        options: StarAlignedRaw16StackOptions
    ): String =
        buildString {
            appendLine("BracketLab Star-Aligned RAW16 Stack")
            appendLine("Alignment: star similarity transform")
            appendLine("Warp: CFA-phase-safe bilinear")
            appendLine("Aggregation: ${options.aggregationOptions.mode}")
            appendLine("Frames accepted: ${result.acceptedFrameCount}")
            appendLine(
                "MasterDark: " +
                    if (result.darkCalibrationApplied) "applied" else "not applied"
            )
            append("No demosaic, tone mapping, gamma or white balance applied")
        }

    private fun failure(
        stage: StarAlignedRaw16ExportFailureStage,
        code: String,
        message: String,
        startedNs: Long,
        stackResult: StarAlignedRaw16StackResult? = null,
        writerResult: Raw16DngWriteResult? = null,
        warnings: List<String> = emptyList(),
        cleanupSucceeded: Boolean = true
    ): StarAlignedRaw16ExportResult =
        StarAlignedRaw16ExportResult(
            success = false,
            failureStage = stage,
            failureCode = code,
            failureMessage = message,
            stackResult = stackResult,
            dngWriteResult = writerResult,
            warnings = warnings,
            totalDurationMs = elapsedMs(startedNs),
            temporaryRawCleanupSucceeded = cleanupSucceeded
        )

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L
}
