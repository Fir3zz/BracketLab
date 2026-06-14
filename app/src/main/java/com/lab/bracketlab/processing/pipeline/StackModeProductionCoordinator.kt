package com.lab.bracketlab.processing.pipeline

import android.content.Context
import com.lab.bracketlab.processing.align.IdentityAlignmentReportFactory
import com.lab.bracketlab.processing.align.LandscapeAlignmentOptions
import com.lab.bracketlab.processing.align.LandscapeAlignmentProcessor
import com.lab.bracketlab.processing.align.LandscapeAlignmentReport
import com.lab.bracketlab.processing.align.star.StarAlignmentProcessor
import com.lab.bracketlab.processing.align.star.StarDetectionOptions
import com.lab.bracketlab.processing.align.star.StarDetector
import com.lab.bracketlab.processing.align.star.StarMatchingOptions
import com.lab.bracketlab.processing.align.star.warp.StarAlignedRaw16StackOptions
import com.lab.bracketlab.processing.align.star.warp.StarAlignedRaw16StackProcessor
import com.lab.bracketlab.processing.debug.SingleFlightGuard
import com.lab.bracketlab.processing.calibration.DarkCalibrationInput
import com.lab.bracketlab.processing.hdri.HdrI32Diagnostic
import com.lab.bracketlab.processing.hdri.HdrI32ExportOptions
import com.lab.bracketlab.processing.io.DcimOutputPublisher
import com.lab.bracketlab.processing.io.MediaStoreRaw16DngOutputDestination
import com.lab.bracketlab.processing.io.PublishedOutput
import com.lab.bracketlab.processing.io.Raw16CfaFitsWriter
import com.lab.bracketlab.processing.io.Raw16CfaXisfWriter
import com.lab.bracketlab.processing.io.Raw16DngWriteRequest
import com.lab.bracketlab.processing.io.Raw16DngWriter
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.stack.AlignedRaw16StackOptions
import com.lab.bracketlab.processing.stack.AlignedRaw16StackProcessor
import com.lab.bracketlab.processing.stack.AlignedRaw16StackResult
import com.lab.bracketlab.processing.stack.RawStackAggregationMode
import com.lab.bracketlab.processing.stack.RawStackAggregationOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ProductionStackMode {
    STACK,
    HDR,
    STAR_TRAIL
}

enum class ProductionAlignmentMode {
    OFF,
    LANDSCAPE,
    STARS
}

data class ProductionOutputOptions(
    val dng: Boolean,
    val xisf: Boolean,
    val fits: Boolean
) {
    init {
        require(dng || xisf || fits) { "At least one production output is required." }
    }
}

data class StackModeProductionRequest(
    val sequence: CapturedRawSequence,
    val mode: ProductionStackMode,
    val alignment: ProductionAlignmentMode,
    val outputs: ProductionOutputOptions,
    val dcimRelativeDirectory: String,
    val workingDirectory: File,
    val darkCalibration: DarkCalibrationInput = DarkCalibrationInput.OFF
)

data class StackModeProductionResult(
    val success: Boolean,
    val outputs: List<PublishedOutput>,
    val warnings: List<String>,
    val failureMessage: String? = null,
    val processingDurationMs: Long = 0L,
    val cleanupSucceeded: Boolean = true
)

/**
 * Production boundary for Stack Modes.
 *
 * Every alignment report is completed before a stack/merge processor receives
 * samples. Call this synchronous API from a worker thread only.
 */
class StackModeProductionCoordinator(private val context: Context) {
    fun process(request: StackModeProductionRequest): StackModeProductionResult {
        val startedNs = System.nanoTime()
        if (!guard.tryAcquire()) {
            return StackModeProductionResult(
                success = false,
                outputs = emptyList(),
                warnings = emptyList(),
                failureMessage = "Another full-resolution Stack Modes job is active."
            )
        }
        request.workingDirectory.mkdirs()
        val outputs = mutableListOf<PublishedOutput>()
        val warnings = mutableListOf<String>()
        lateinit var result: StackModeProductionResult
        var cleanupSucceeded: Boolean
        try {
            val stack = request.sequence.toRawStack()
            require(!stack.isEmpty) { "Captured RAW sequence is empty." }
            when (request.mode) {
                ProductionStackMode.HDR ->
                    processHdr(request, outputs, warnings)

                ProductionStackMode.STACK,
                ProductionStackMode.STAR_TRAIL ->
                    processRaw16(request, outputs, warnings)
            }
            val allSucceeded = outputs.isNotEmpty() && outputs.all(PublishedOutput::success)
            result = StackModeProductionResult(
                success = allSucceeded,
                outputs = outputs,
                warnings = warnings,
                failureMessage =
                    outputs.firstOrNull { !it.success }?.failureMessage,
                processingDurationMs = elapsedMs(startedNs)
            )
        } catch (error: Throwable) {
            result = StackModeProductionResult(
                success = false,
                outputs = outputs,
                warnings = warnings,
                failureMessage = "${error.javaClass.simpleName}: ${error.message}",
                processingDurationMs = elapsedMs(startedNs)
            )
        } finally {
            cleanupSucceeded =
                runCatching { request.sequence.cleanupTemporaryRawFrames() }.getOrDefault(false)
            cleanupSucceeded =
                runCatching {
                    !request.workingDirectory.exists() ||
                        request.workingDirectory.deleteRecursively()
                }.getOrDefault(false) &&
                    cleanupSucceeded
            guard.release()
        }
        return result.copy(cleanupSucceeded = cleanupSucceeded)
    }

    private fun processRaw16(
        request: StackModeProductionRequest,
        published: MutableList<PublishedOutput>,
        warnings: MutableList<String>
    ) {
        val rawStack = request.sequence.toRawStack()
        val packedFile = File(request.workingDirectory, ".stack_output.raw16")
        val result =
            if (request.alignment == ProductionAlignmentMode.STARS) {
                require(request.mode == ProductionStackMode.STACK) {
                    "Star alignment is only valid for Stack mode."
                }
                val detection =
                    StarDetector().detect(
                        rawStack,
                        StarDetectionOptions(
                            allowLumaFallback = true,
                            suppressHotPixelsForProxy = false
                        )
                    )
                require(detection.catalogs.size == rawStack.frameCount) {
                    detection.failureMessage ?: "Star detection failed."
                }
                val alignment =
                    StarAlignmentProcessor().align(
                        detection.catalogs,
                        StarMatchingOptions()
                    )
                require(alignment.success || alignment.partialSuccess) {
                    alignment.fatalMessage ?: "Star matching/RANSAC failed."
                }
                warnings += detection.globalWarnings
                warnings += alignment.warnings
                StarAlignedRaw16StackProcessor()
                    .processToPackedFile(
                        rawStack,
                        alignment,
                        StarAlignedRaw16StackOptions(
                            aggregationOptions =
                                RawStackAggregationOptions(
                                    mode = RawStackAggregationMode.MEAN
                                ),
                            darkCalibration = request.darkCalibration
                        ),
                        packedFile
                    )
                    .asWriterCompatibleResult()
            } else {
                val alignment =
                    when (request.alignment) {
                        ProductionAlignmentMode.OFF ->
                            IdentityAlignmentReportFactory.create(rawStack)
                        ProductionAlignmentMode.LANDSCAPE ->
                            LandscapeAlignmentProcessor().align(
                                rawStack,
                                LandscapeAlignmentOptions()
                            )
                        ProductionAlignmentMode.STARS ->
                            error("Unexpected Stars alignment branch.")
                    }
                require(alignment.success || alignment.partialSuccess) {
                    alignment.fatalMessage ?: "Landscape alignment failed."
                }
                warnings += alignment.warnings
                val aggregation =
                    if (request.mode == ProductionStackMode.STAR_TRAIL) {
                        RawStackAggregationMode.MAXIMUM
                    } else {
                        RawStackAggregationMode.MEAN
                    }
                AlignedRaw16StackProcessor().processToPackedFile(
                    rawStack = rawStack,
                    alignmentReport = alignment,
                    options =
                        AlignedRaw16StackOptions(
                            aggregationOptions =
                                RawStackAggregationOptions(mode = aggregation),
                            darkCalibration = request.darkCalibration
                        ),
                    outputFile = packedFile
                )
            }
        require(result.success) {
            result.fatalMessage ?: result.fatalError?.name ?: "RAW16 stack failed."
        }
        val referenceIndex = requireNotNull(result.referenceFrameIndex)
        val reference =
            rawStack.frames.firstOrNull { it.frameIndex == referenceIndex }
                ?: error("Reference RAW frame is missing.")
        val baseName =
            when {
                request.mode == ProductionStackMode.STAR_TRAIL -> "BracketLab_StarTrail"
                request.alignment == ProductionAlignmentMode.STARS ->
                    "BracketLab_StarAligned_Stack"
                request.alignment == ProductionAlignmentMode.LANDSCAPE ->
                    "BracketLab_LandscapeAligned_Stack"
                else -> "BracketLab_Stack"
            }
        exportRaw16(
            request,
            result,
            reference,
            baseName,
            published,
            warnings
        )
        val packedCleanup = !packedFile.exists() || packedFile.delete()
        writeAndPublishReport(
            request,
            listOf(
                "BracketLab Stack Modes production report",
                "Mode=${request.mode}",
                "Alignment=${request.alignment}",
                "Alignment completed before sample aggregation=true",
                "Reference frame=$referenceIndex",
                "Frames accepted=${result.acceptedFrameCount}",
                "Frames rejected=${result.rejectedFrameCount}",
                "Aggregation=${result.options.aggregationOptions.mode}",
                "Dark calibration requested=${request.darkCalibration.policy}",
                "Dark calibration applied=${result.darkCalibrationApplied}",
                "Output dimensions=${result.width}x${result.height}",
                "Temporary packed RAW deleted=$packedCleanup"
            ),
            published
        )
    }

    private fun exportRaw16(
        request: StackModeProductionRequest,
        result: AlignedRaw16StackResult,
        reference: RawFrame,
        baseName: String,
        published: MutableList<PublishedOutput>,
        warnings: MutableList<String>
    ) {
        val packedFile = requireNotNull(result.outputRaw16FilePath).let(::File)
        if (request.outputs.xisf) {
            val work = File(request.workingDirectory, "$baseName.xisf")
            val written = Raw16CfaXisfWriter.write(result, reference, work)
            published +=
                if (written.success) {
                    DcimOutputPublisher(context).publish(
                        work,
                        request.dcimRelativeDirectory
                    )
                } else {
                    failedPublished(work.name, written.failureMessage)
                }
        }
        if (request.outputs.fits) {
            val work = File(request.workingDirectory, "$baseName.fits")
            val written = Raw16CfaFitsWriter.write(result, reference, work)
            published +=
                if (written.success) {
                    DcimOutputPublisher(context).publish(
                        work,
                        request.dcimRelativeDirectory
                    )
                } else {
                    failedPublished(work.name, written.failureMessage)
                }
        }
        if (request.outputs.dng) {
            val metadata =
                request.sequence.getReferenceMetadata(reference.frameIndex, reference.cameraId)
            require(metadata.complete && metadata.frameIndex == reference.frameIndex) {
                "Reference Camera2 metadata is unavailable."
            }
            val write =
                Raw16DngWriter.write(
                    Raw16DngWriteRequest(
                        alignedResult = result,
                        cameraCharacteristics = metadata.cameraCharacteristics,
                        captureResult = metadata.captureResult,
                        destination =
                            MediaStoreRaw16DngOutputDestination(
                                context,
                                request.dcimRelativeDirectory,
                                "$baseName.dng"
                            ),
                        referenceFrameIndex = reference.frameIndex,
                        metadataFrameIndex = metadata.frameIndex,
                        expectedCameraId = reference.cameraId,
                        metadataCameraId = metadata.cameraId,
                        expectedWidth = reference.width,
                        expectedHeight = reference.height,
                        orientation = metadata.dngOrientation,
                        description =
                            "BracketLab ${request.mode}; alignment ${request.alignment}; " +
                                "aggregation ${result.options.aggregationOptions.mode}; " +
                                "dark calibration ${
                                    if (result.darkCalibrationApplied) "MasterDark applied"
                                    else "not applied"
                                }",
                        sequenceIdentifier = baseName,
                        deletePackedInputAfterWrite = false
                    )
                )
            warnings += write.warnings.map { "${it.code}: ${it.message}" }
            published +=
                PublishedOutput(
                    success = write.success,
                    displayName = write.filename ?: "$baseName.dng",
                    uri = write.finalUri,
                    path = write.finalPath,
                    storageDirectory = request.dcimRelativeDirectory,
                    bytes = write.bytesWritten ?: 0L,
                    failureMessage = write.failureMessage
                )
        }
        check(packedFile.exists()) {
            "Packed RAW16 was removed before all selected exports completed."
        }
    }

    private fun processHdr(
        request: StackModeProductionRequest,
        published: MutableList<PublishedOutput>,
        warnings: MutableList<String>
    ) {
        require(request.alignment != ProductionAlignmentMode.STARS) {
            "HDR does not support Stars alignment."
        }
        require(request.darkCalibration == DarkCalibrationInput.OFF) {
            "HDR dark calibration is not supported."
        }
        val rawStack = request.sequence.toRawStack()
        val alignment: LandscapeAlignmentReport? =
            if (request.alignment == ProductionAlignmentMode.LANDSCAPE) {
                LandscapeAlignmentProcessor().align(
                    rawStack,
                    LandscapeAlignmentOptions(exposureNormalizeProxies = true)
                ).also {
                    require(it.success || it.partialSuccess) {
                        it.fatalMessage ?: "HDR landscape alignment failed."
                    }
                    warnings += it.warnings
                }
            } else {
                null
            }
        val hdrRoot = File(request.workingDirectory, "hdr")
        val diagnostic =
            HdrI32Diagnostic(hdrRoot).run(
                sequence = request.sequence,
                darkCalibrationRequested = false,
                exportOptions =
                    HdrI32ExportOptions(
                        writeXisf32 = request.outputs.xisf,
                        writeFits32 = request.outputs.fits,
                        writeLinearRgbFloat16Dng = request.outputs.dng
                    ),
                landscapeAlignmentReport = alignment
            )
        warnings += diagnostic.warnings.map { "${it.code}: ${it.message}" }
        require(diagnostic.success) {
            diagnostic.failureMessage ?: diagnostic.failureCode?.name ?: "HDR export failed."
        }
        listOfNotNull(
            diagnostic.linearRgbFloat16DngPath,
            diagnostic.xisfPath,
            diagnostic.fitsPath,
            diagnostic.metadataPath,
            diagnostic.reportPath
        ).map(::File).forEach { file ->
            published +=
                DcimOutputPublisher(context).publish(
                    file,
                    request.dcimRelativeDirectory
                )
        }
        val sidecars =
            diagnostic.linearRgbFloat16DngPath
                ?.let(::File)
                ?.parentFile
                ?.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
        sidecars
            .filterNot { it.absolutePath == diagnostic.metadataPath }
            .forEach {
                published +=
                    DcimOutputPublisher(context).publish(
                        it,
                        request.dcimRelativeDirectory
                    )
            }
    }

    private fun writeAndPublishReport(
        request: StackModeProductionRequest,
        lines: List<String>,
        published: MutableList<PublishedOutput>
    ) {
        val report =
            File(request.workingDirectory, "BracketLab_Stack_Report.txt").apply {
                writeText(lines.joinToString(separator = "\n", postfix = "\n"))
            }
        published +=
            DcimOutputPublisher(context).publish(
                report,
                request.dcimRelativeDirectory
            )
    }

    private fun failedPublished(name: String, message: String?): PublishedOutput =
        PublishedOutput(
            success = false,
            displayName = name,
            failureMessage = message ?: "Output writer failed."
        )

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L

    companion object {
        private val guard = SingleFlightGuard()

        fun isProcessing(): Boolean = guard.isActive()

        fun sessionName(createdAtMillis: Long): String =
            "session_" +
                SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(Date(createdAtMillis))
    }
}
