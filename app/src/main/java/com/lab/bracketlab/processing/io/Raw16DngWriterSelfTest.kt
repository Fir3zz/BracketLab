package com.lab.bracketlab.processing.io

import com.lab.bracketlab.processing.align.LandscapeAlignmentOptions
import com.lab.bracketlab.processing.align.LandscapeAlignmentRejectionReason
import com.lab.bracketlab.processing.align.LandscapeAlignmentReport
import com.lab.bracketlab.processing.align.LandscapeAlignmentStatus
import com.lab.bracketlab.processing.align.PhaseCorrelationSelfTestStatus
import com.lab.bracketlab.processing.align.ReferenceSelectionMethod
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.pipeline.AlignedRaw16ExportFailureStage
import com.lab.bracketlab.processing.pipeline.AlignedRaw16ExportPipeline
import com.lab.bracketlab.processing.pipeline.AlignedRaw16ExportPipelineDependencies
import com.lab.bracketlab.processing.pipeline.CameraMetadataProvider
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.stack.AlignedRaw16StackFailureCode
import com.lab.bracketlab.processing.stack.AlignedRaw16StackOptions
import com.lab.bracketlab.processing.stack.AlignedRaw16StackResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Raw16DngWriterSelfTestCaseResult(
    val name: String,
    val status: PhaseCorrelationSelfTestStatus,
    val message: String
)

data class Raw16DngWriterSelfTestReport(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val processingDurationMs: Long,
    val results: List<Raw16DngWriterSelfTestCaseResult>
)

object Raw16DngWriterSelfTest {
    fun runAll(): Raw16DngWriterSelfTestReport {
        val startedNs = System.nanoTime()
        val results = listOf(
            test("valid packed result accepted") {
                Raw16DngWriter.validatePackedResult(stackResult()).success
            },
            test("invalid width rejected") {
                Raw16DngWriter.validatePackedResult(stackResult(width = 0)).failureCode ==
                    Raw16DngFailureCode.INVALID_OUTPUT_DIMENSIONS
            },
            test("invalid height rejected") {
                Raw16DngWriter.validatePackedResult(stackResult(height = 0)).failureCode ==
                    Raw16DngFailureCode.INVALID_OUTPUT_DIMENSIONS
            },
            test("insufficient buffer rejected") {
                Raw16DngWriter.validatePackedResult(stackResult(bufferBytes = 6)).failureCode ==
                    Raw16DngFailureCode.BUFFER_SIZE_MISMATCH
            },
            test("wrong row stride rejected") {
                Raw16DngWriter.validatePackedResult(stackResult(rowStride = 10)).failureCode ==
                    Raw16DngFailureCode.UNSUPPORTED_ROW_STRIDE
            },
            test("wrong pixel stride rejected") {
                Raw16DngWriter.validatePackedResult(stackResult(pixelStride = 4)).failureCode ==
                    Raw16DngFailureCode.UNSUPPORTED_PIXEL_STRIDE
            },
            test("non little endian declaration rejected") {
                Raw16DngWriter.validatePackedResult(stackResult(byteOrder = ByteOrder.BIG_ENDIAN)).failureCode ==
                    Raw16DngFailureCode.INVALID_BYTE_ORDER
            },
            test("missing CameraCharacteristics rejected") {
                Raw16DngWriter.validateRequest(request()).failureCode ==
                    Raw16DngFailureCode.MISSING_CAMERA_CHARACTERISTICS
            },
            test("missing CaptureResult rejected") {
                Raw16DngWriter.validateRequest(
                    request(),
                    requireAndroidMetadata = false,
                    requireCameraCharacteristics = false,
                    requireCaptureResult = true
                ).failureCode == Raw16DngFailureCode.MISSING_CAPTURE_RESULT
            },
            test("missing reference metadata rejected") {
                Raw16DngWriter.validateRequest(
                    request(stackResult(referenceFrameIndex = null), metadataFrameIndex = null),
                    requireAndroidMetadata = false
                ).failureCode == Raw16DngFailureCode.MISSING_REFERENCE_METADATA
            },
            test("mismatched reference index rejected") {
                Raw16DngWriter.validateRequest(
                    request(referenceFrameIndex = 3),
                    requireAndroidMetadata = false
                ).failureCode == Raw16DngFailureCode.REFERENCE_METADATA_MISMATCH
            },
            test("original buffer position unchanged") {
                val result = stackResult(bufferPosition = 2)
                val before = result.outputRaw16!!.position()
                Raw16DngWriter.createDisposableRawBufferDuplicate(result)
                result.outputRaw16.position() == before
            },
            test("original buffer limit unchanged") {
                val result = stackResult(bufferLimit = 12)
                val before = result.outputRaw16!!.limit()
                Raw16DngWriter.createDisposableRawBufferDuplicate(result)
                result.outputRaw16.limit() == before
            },
            test("original buffer content unchanged") {
                val result = stackResult()
                val before = result.outputRaw16Copy()!!.toList()
                val duplicate = Raw16DngWriter.createDisposableRawBufferDuplicate(result)
                if (duplicate.remaining() > 0) duplicate.get()
                result.outputRaw16Copy()!!.toList() == before
            },
            test("disposable duplicate covers packed buffer") {
                val result = stackResult(width = 3, height = 2)
                val duplicate = Raw16DngWriter.createDisposableRawBufferDuplicate(result)
                duplicate.position() == 0 && duplicate.limit() == 12 && duplicate.remaining() == 12
            },
            test("failure result contains stable code") {
                Raw16DngWriter.writeWithEngine(
                    request(stackResult(width = 0)),
                    ContractEngine(),
                    requireAndroidMetadata = false
                ).failureCode == Raw16DngFailureCode.INVALID_OUTPUT_DIMENSIONS
            },
            test("destination cleanup on simulated write failure") {
                val destination = FakeDestination()
                val result = Raw16DngWriter.writeWithEngine(
                    request(destination = destination),
                    ContractEngine(fail = true),
                    requireAndroidMetadata = false
                )
                !result.success && destination.cleanupCalled && !destination.commitCalled
            },
            test("destination commit only after success") {
                val destination = FakeDestination()
                val result = Raw16DngWriter.writeWithEngine(
                    request(destination = destination),
                    ContractEngine(),
                    requireAndroidMetadata = false
                )
                result.success && destination.commitCalled && !destination.cleanupCalled
            },
            test("coordinator stops after alignment failure") {
                var stackCalled = false
                var writerCalled = false
                val pipeline = pipeline(
                    align = { _, _ -> alignmentFailureReport() },
                    stack = { _, _, _ -> stackCalled = true; stackResult(success = false) },
                    write = { writerCalled = true; successfulWriteResult() }
                )
                val result = pipeline.processAndWrite(
                    rawStack(),
                    EmptyMetadataProvider,
                    outputDestination = FakeDestination()
                )
                !result.overallSuccess &&
                    result.failureStage == AlignedRaw16ExportFailureStage.ALIGNMENT &&
                    !stackCalled &&
                    !writerCalled
            },
            test("coordinator stops after stack failure") {
                var writerCalled = false
                val pipeline = pipeline(
                    align = { _, _ -> alignmentSuccessReport() },
                    stack = { _, _, _ -> stackResult(success = false) },
                    write = { writerCalled = true; successfulWriteResult() }
                )
                val result = pipeline.processAndWrite(
                    rawStack(),
                    EmptyMetadataProvider,
                    outputDestination = FakeDestination()
                )
                !result.overallSuccess &&
                    result.failureStage == AlignedRaw16ExportFailureStage.STACKING &&
                    !writerCalled
            },
            test("coordinator does not call writer when metadata fails") {
                var writerCalled = false
                val pipeline = pipeline(
                    align = { _, _ -> alignmentSuccessReport() },
                    stack = { _, _, _ -> stackResult() },
                    write = { writerCalled = true; successfulWriteResult() }
                )
                val result = pipeline.processAndWrite(
                    rawStack(),
                    EmptyMetadataProvider,
                    outputDestination = FakeDestination()
                )
                !result.overallSuccess &&
                    result.failureStage == AlignedRaw16ExportFailureStage.METADATA &&
                    !writerCalled
            },
            test("deterministic request validation") {
                val req = request(referenceFrameIndex = 4)
                val a = Raw16DngWriter.validateRequest(req, requireAndroidMetadata = false)
                val b = Raw16DngWriter.validateRequest(req, requireAndroidMetadata = false)
                a == b
            }
        )
        return Raw16DngWriterSelfTestReport(
            passed = results.count { it.status == PhaseCorrelationSelfTestStatus.PASS },
            failed = results.count { it.status == PhaseCorrelationSelfTestStatus.FAIL },
            skipped = results.count { it.status == PhaseCorrelationSelfTestStatus.SKIPPED },
            processingDurationMs = (System.nanoTime() - startedNs) / 1_000_000L,
            results = results
        )
    }

    private fun test(name: String, block: () -> Boolean): Raw16DngWriterSelfTestCaseResult =
        try {
            if (block()) {
                Raw16DngWriterSelfTestCaseResult(name, PhaseCorrelationSelfTestStatus.PASS, "pass")
            } else {
                Raw16DngWriterSelfTestCaseResult(name, PhaseCorrelationSelfTestStatus.FAIL, "condition returned false")
            }
        } catch (e: Throwable) {
            Raw16DngWriterSelfTestCaseResult(
                name,
                PhaseCorrelationSelfTestStatus.FAIL,
                "${e.javaClass.simpleName}: ${e.message}"
            )
        }

    private fun request(
        alignedResult: AlignedRaw16StackResult = stackResult(),
        destination: Raw16DngOutputDestination = FakeDestination(),
        referenceFrameIndex: Int? = alignedResult.referenceFrameIndex,
        metadataFrameIndex: Int? = alignedResult.referenceFrameIndex
    ): Raw16DngWriteRequest =
        Raw16DngWriteRequest(
            alignedResult = alignedResult,
            cameraCharacteristics = null,
            captureResult = null,
            destination = destination,
            referenceFrameIndex = referenceFrameIndex,
            metadataFrameIndex = metadataFrameIndex,
            expectedCameraId = "0",
            metadataCameraId = "0",
            expectedWidth = alignedResult.width.takeIf { it > 0 },
            expectedHeight = alignedResult.height.takeIf { it > 0 }
        )

    private fun stackResult(
        width: Int = 2,
        height: Int = 2,
        success: Boolean = true,
        bufferBytes: Int = (width.coerceAtLeast(1) * height.coerceAtLeast(1) * 2),
        rowStride: Int = width * 2,
        pixelStride: Int = 2,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        referenceFrameIndex: Int? = 1,
        bufferPosition: Int = 0,
        bufferLimit: Int? = null
    ): AlignedRaw16StackResult {
        val capacity = bufferBytes.coerceAtLeast(0)
        val buffer = ByteBuffer.allocate(capacity.coerceAtLeast(1)).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until buffer.capacity()) buffer.put((i and 0xFF).toByte())
        buffer.position(bufferPosition.coerceIn(0, buffer.capacity()))
        buffer.limit((bufferLimit ?: buffer.capacity()).coerceIn(buffer.position(), buffer.capacity()))
        val output = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        return AlignedRaw16StackResult(
            success = success,
            width = width,
            height = height,
            outputRaw16 = if (success) output else null,
            outputByteOrder = byteOrder,
            outputRowStride = rowStride,
            outputPixelStride = pixelStride,
            referenceFrameIndex = referenceFrameIndex,
            inputFrameCount = 2,
            acceptedFrameCount = if (success) 2 else 0,
            rejectedFrameCount = 0,
            appliedTranslations = emptyList(),
            minimumValidCount = if (success) 2 else 0,
            maximumValidCount = if (success) 2 else 0,
            meanValidCount = if (success) 2.0 else 0.0,
            singleContributorPixelCount = 0,
            fullContributorPixelCount = if (success && width > 0 && height > 0) width * height else 0,
            commonOverlapRect = null,
            warnings = emptyList(),
            fatalError = if (success) null else AlignedRaw16StackFailureCode.INVALID_RAW_BUFFER,
            fatalMessage = if (success) null else "simulated stack failure",
            processingDurationMs = 0L,
            options = AlignedRaw16StackOptions()
        )
    }

    private fun rawStack(): RawStack =
        RawStack(
            frames = listOf(
                RawFrame(
                    width = 2,
                    height = 2,
                    raw16 = ByteArray(8),
                    rowStride = 4,
                    pixelStride = 2,
                    exposureTimeNs = 10_000_000L,
                    iso = 100,
                    cameraId = "0",
                    frameIndex = 1,
                    blackLevelPattern = intArrayOf(0, 0, 0, 0),
                    whiteLevel = 1023,
                    cfaPattern = CfaPattern.RGGB
                )
            )
        )

    private fun alignmentSuccessReport(): LandscapeAlignmentReport =
        LandscapeAlignmentReport(
            status = LandscapeAlignmentStatus.SUCCESS,
            success = true,
            partialSuccess = false,
            selectedReferenceFrameIndex = 1,
            selectedReferencePosition = 0,
            referenceSelectionMethod = ReferenceSelectionMethod.ONLY_FRAME,
            referenceExposureTimeSeconds = 0.01,
            totalFrameCount = 1,
            acceptedFrameCount = 1,
            rejectedFrameCount = 0,
            lowConfidenceFrameCount = 0,
            frameResults = emptyList(),
            alignmentResults = emptyList(),
            warnings = emptyList(),
            fatalError = null,
            fatalMessage = null,
            processingDurationMs = 0L,
            options = LandscapeAlignmentOptions()
        )

    private fun alignmentFailureReport(): LandscapeAlignmentReport =
        alignmentSuccessReport().copy(
            status = LandscapeAlignmentStatus.FAILURE,
            success = false,
            acceptedFrameCount = 0,
            fatalError = LandscapeAlignmentRejectionReason.EMPTY_STACK,
            fatalMessage = "simulated alignment failure"
        )

    private fun pipeline(
        align: (RawStack, LandscapeAlignmentOptions) -> LandscapeAlignmentReport,
        stack: (RawStack, LandscapeAlignmentReport, AlignedRaw16StackOptions) -> AlignedRaw16StackResult,
        write: (Raw16DngWriteRequest) -> Raw16DngWriteResult
    ): AlignedRaw16ExportPipeline =
        AlignedRaw16ExportPipeline(
            AlignedRaw16ExportPipelineDependencies(
                align = align,
                stackAligned = stack,
                write = write
            )
        )

    private fun successfulWriteResult(): Raw16DngWriteResult =
        Raw16DngWriteResult(
            success = true,
            filename = "contract.dng",
            width = 2,
            height = 2,
            referenceFrameIndex = 1,
            acceptedFrameCount = 2,
            writerMethod = Raw16DngWriterMethod.BYTE_BUFFER
        )

    private object EmptyMetadataProvider : CameraMetadataProvider {
        override fun getCameraCharacteristics(cameraId: String?) = null

        override fun getCaptureResult(frameIndex: Int) = null
    }

    private class FakeDestination(
        private val commitSucceeds: Boolean = true
    ) : Raw16DngOutputDestination {
        var commitCalled = false
        var cleanupCalled = false
        private val bytes = ByteArrayOutputStream()

        override val requestedFilename: String? = "contract.dng"

        override fun open(): Raw16DngOpenedOutput =
            Raw16DngOpenedOutput(
                outputStream = bytes,
                filename = requestedFilename,
                finalPath = "/tmp/contract.dng",
                finalUri = null,
                bytesWrittenProvider = { bytes.size().toLong() },
                commitAction = {
                    commitCalled = true
                    commitSucceeds
                },
                cleanupAction = {
                    cleanupCalled = true
                    true
                }
            )
    }

    private class ContractEngine(
        private val fail: Boolean = false
    ) : Raw16DngWriteEngine {
        override val method: Raw16DngWriterMethod = Raw16DngWriterMethod.BYTE_BUFFER

        override fun write(
            request: Raw16DngWriteRequest,
            outputStream: OutputStream,
            rawSource: Raw16DngSource,
            description: String
        ) {
            outputStream.write(1)
            if (fail) throw IOException("simulated write failure")
        }
    }
}
