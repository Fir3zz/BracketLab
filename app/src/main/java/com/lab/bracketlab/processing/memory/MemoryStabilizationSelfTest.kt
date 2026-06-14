package com.lab.bracketlab.processing.memory

import com.lab.bracketlab.processing.align.LandscapeAlignmentOptions
import com.lab.bracketlab.processing.align.LandscapeAlignmentReport
import com.lab.bracketlab.processing.align.LandscapeAlignmentStatus
import com.lab.bracketlab.processing.align.LandscapeFrameAlignment
import com.lab.bracketlab.processing.align.PhaseCorrelationSelfTestStatus
import com.lab.bracketlab.processing.align.ReferenceSelectionMethod
import com.lab.bracketlab.processing.debug.IncrementalDiagnosticReport
import com.lab.bracketlab.processing.debug.RealAlignedStackDngResult
import com.lab.bracketlab.processing.debug.SingleFlightGuard
import com.lab.bracketlab.processing.io.Raw16DngOpenedOutput
import com.lab.bracketlab.processing.io.Raw16DngOutputDestination
import com.lab.bracketlab.processing.io.Raw16DngSource
import com.lab.bracketlab.processing.io.Raw16DngWriteEngine
import com.lab.bracketlab.processing.io.Raw16DngWriteRequest
import com.lab.bracketlab.processing.io.Raw16DngWriter
import com.lab.bracketlab.processing.io.Raw16DngWriterMethod
import com.lab.bracketlab.processing.model.AlignmentResult
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.stack.AlignedRaw16StackOptions
import com.lab.bracketlab.processing.stack.AlignedRaw16StackProcessor
import com.lab.bracketlab.processing.stack.RawStackAggregationMode
import com.lab.bracketlab.processing.stack.RawStackAggregationOptions
import com.lab.bracketlab.processing.storage.FileBackedRaw16FrameStorage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

data class MemoryStabilizationSelfTestCaseResult(
    val name: String,
    val status: PhaseCorrelationSelfTestStatus,
    val message: String
)

data class MemoryStabilizationSelfTestReport(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val processingDurationMs: Long,
    val results: List<MemoryStabilizationSelfTestCaseResult>
)

object MemoryStabilizationSelfTest {
    fun runAll(): MemoryStabilizationSelfTestReport {
        val startedNs = System.nanoTime()
        val results = listOf(
            test("sequential summaries retain no full RAW buffer") {
                RealAlignedStackDngResult::class.java.declaredFields.none {
                    it.type == ByteBuffer::class.java || it.type == ByteArray::class.java
                }
            },
            reusableBufferTest(RawStackAggregationMode.MEAN),
            reusableBufferTest(RawStackAggregationMode.MIN_MAX_REJECTED_MEAN),
            reusableBufferTest(RawStackAggregationMode.SIGMA_CLIPPED_MEAN),
            test("file-backed source equals in-memory source") {
                val memoryFrames = frames()
                val fileFrames = fileBackedCopy(memoryFrames)
                try {
                    val expected = process(memoryFrames, RawStackAggregationMode.MEAN).outputRaw16Copy()
                    val actual = process(fileFrames, RawStackAggregationMode.MEAN).outputRaw16Copy()
                    expected != null && expected.contentEquals(actual)
                } finally {
                    fileFrames.forEach { it.resolvedRaw16Storage()?.deleteIfOwned() }
                }
            },
            tiledEqualityTest(RawStackAggregationMode.MEAN),
            tiledEqualityTest(RawStackAggregationMode.MIN_MAX_REJECTED_MEAN),
            tiledEqualityTest(RawStackAggregationMode.SIGMA_CLIPPED_MEAN),
            test("tile boundaries create no seams") {
                tiledBytes(frames(), RawStackAggregationMode.MEAN, 1)
                    .contentEquals(tiledBytes(frames(), RawStackAggregationMode.MEAN, HEIGHT))
            },
            test("shifted borders remain equal across tiles") {
                val input = frames()
                tiledBytes(input, RawStackAggregationMode.MEAN, 2, shifted = true)
                    .contentEquals(tiledBytes(input, RawStackAggregationMode.MEAN, HEIGHT, shifted = true))
            },
            test("CFA phase remains unchanged") {
                val input = frames()
                val result = process(input, RawStackAggregationMode.MEAN)
                val expected = oldMeanBytes(input)
                result.outputRaw16Copy()?.contentEquals(expected) == true
            },
            test("temporary input files are removed") {
                val file = File.createTempFile("bracketlab_input_", ".raw16")
                file.writeBytes(ByteArray(16))
                val storage = FileBackedRaw16FrameStorage(file, deleteOnCleanup = true)
                storage.deleteIfOwned() && !file.exists()
            },
            test("temporary packed output removed after success") {
                writerCleanupTest(fail = false)
            },
            test("failed DNG write cleans temporary output") {
                writerCleanupTest(fail = true)
            },
            test("incremental report survives simulated failure") {
                val file = File.createTempFile("bracketlab_report_", ".txt")
                try {
                    runCatching {
                        IncrementalDiagnosticReport(file).use {
                            it.append("session started")
                            it.append("stage completed")
                            throw IOException("simulated")
                        }
                    }
                    val text = file.readText()
                    "session started" in text && "stage completed" in text
                } finally {
                    file.delete()
                }
            },
            test("processing guard rejects concurrent execution") {
                val guard = SingleFlightGuard()
                val first = guard.tryAcquire()
                val second = guard.tryAcquire()
                guard.release()
                first && !second && guard.tryAcquire().also { guard.release() }
            },
            test("memory estimator Long math saturates safely") {
                MemoryBudgetEstimator.safeMultiply(Long.MAX_VALUE, 2L) == Long.MAX_VALUE &&
                    MemoryBudgetEstimator.safeAdd(Long.MAX_VALUE, 1L) == Long.MAX_VALUE
            },
            test("fifteen full-resolution frames select file-backed strategy") {
                val file = File.createTempFile("bracketlab_budget_", ".raw16")
                try {
                    val storage = FileBackedRaw16FrameStorage(file, deleteOnCleanup = false)
                    val stack = RawStack(
                        List(15) { index ->
                            RawFrame(
                                width = 4032,
                                height = 3024,
                                raw16Storage = storage,
                                rowStride = 8064,
                                pixelStride = 2,
                                exposureTimeNs = 10_000_000L,
                                iso = 100,
                                frameIndex = index,
                                cfaPattern = CfaPattern.RGGB
                            )
                        }
                    )
                    MemoryBudgetEstimator.estimate(stack).selectedStrategy ==
                        Raw16ProcessingStrategy.FILE_BACKED_TILED
                } finally {
                    file.delete()
                }
            },
            test("validated MEAN formula remains byte-identical") {
                val input = frames()
                process(input, RawStackAggregationMode.MEAN)
                    .outputRaw16Copy()
                    ?.contentEquals(oldMeanBytes(input)) == true
            },
            test("source RAW buffers remain unchanged") {
                val input = frames()
                val before = input.map { it.raw16!!.copyOf() }
                process(input, RawStackAggregationMode.SIGMA_CLIPPED_MEAN)
                input.indices.all { before[it].contentEquals(input[it].raw16) }
            }
        )
        return MemoryStabilizationSelfTestReport(
            passed = results.count { it.status == PhaseCorrelationSelfTestStatus.PASS },
            failed = results.count { it.status == PhaseCorrelationSelfTestStatus.FAIL },
            skipped = results.count { it.status == PhaseCorrelationSelfTestStatus.SKIPPED },
            processingDurationMs = (System.nanoTime() - startedNs) / 1_000_000L,
            results = results
        )
    }

    private fun reusableBufferTest(
        mode: RawStackAggregationMode
    ): MemoryStabilizationSelfTestCaseResult =
        test("reusable output buffer matches $mode") {
            val input = frames()
            val report = identityReport(input, shifted = false)
            val options = options(mode, HEIGHT)
            val expected = AlignedRaw16StackProcessor()
                .process(RawStack(input), report, options)
                .outputRaw16Copy()
            val reusable = ByteBuffer.allocate(WIDTH * HEIGHT * 2)
            val actual = AlignedRaw16StackProcessor()
                .processIntoPackedBuffer(RawStack(input), report, options, reusable)
                .outputRaw16Copy()
            expected != null && expected.contentEquals(actual)
        }

    private fun tiledEqualityTest(
        mode: RawStackAggregationMode
    ): MemoryStabilizationSelfTestCaseResult =
        test("tiled $mode equals full-frame $mode") {
            val input = frames()
            val expected = process(input, mode, HEIGHT).outputRaw16Copy()
            val actual = tiledBytes(input, mode, 2)
            expected != null && expected.contentEquals(actual)
        }

    private fun tiledBytes(
        input: List<RawFrame>,
        mode: RawStackAggregationMode,
        tileHeight: Int,
        shifted: Boolean = false
    ): ByteArray {
        val file = File.createTempFile("bracketlab_stack_", ".raw16")
        return try {
            val report = identityReport(input, shifted)
            val result = AlignedRaw16StackProcessor().processToPackedFile(
                RawStack(input),
                report,
                options(mode, tileHeight),
                file
            )
            check(result.success)
            file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun process(
        input: List<RawFrame>,
        mode: RawStackAggregationMode,
        tileHeight: Int = HEIGHT
    ) =
        AlignedRaw16StackProcessor().process(
            RawStack(input),
            identityReport(input, shifted = false),
            options(mode, tileHeight)
        )

    private fun options(mode: RawStackAggregationMode, tileHeight: Int) =
        AlignedRaw16StackOptions(
            tileHeight = tileHeight,
            aggregationOptions = RawStackAggregationOptions(mode = mode)
        )

    private fun frames(): List<RawFrame> =
        List(7) { frameIndex ->
            val samples = IntArray(WIDTH * HEIGHT) { pixel ->
                val base = 1000 + pixel * 13
                when {
                    frameIndex == 6 && pixel == 10 -> 65535
                    frameIndex == 0 && pixel == 17 -> 0
                    else -> base + frameIndex
                }
            }
            RawFrame(
                width = WIDTH,
                height = HEIGHT,
                raw16 = packed(samples),
                rowStride = WIDTH * 2,
                pixelStride = 2,
                exposureTimeNs = 10_000_000L,
                iso = 100,
                cameraId = "0",
                frameIndex = frameIndex,
                blackLevelPattern = intArrayOf(64, 64, 64, 64),
                whiteLevel = 65535,
                cfaPattern = CfaPattern.RGGB
            )
        }

    private fun fileBackedCopy(frames: List<RawFrame>): List<RawFrame> =
        frames.map { frame ->
            val file = File.createTempFile("bracketlab_frame_${frame.frameIndex}_", ".raw16")
            file.writeBytes(frame.raw16!!)
            frame.copy(
                raw16 = null,
                raw16Storage = FileBackedRaw16FrameStorage(file, deleteOnCleanup = true),
                sourceFilePath = file.absolutePath
            )
        }

    private fun identityReport(
        frames: List<RawFrame>,
        shifted: Boolean
    ): LandscapeAlignmentReport {
        val referencePosition = frames.size / 2
        val referenceFrame = frames[referencePosition]
        val frameResults = frames.mapIndexed { position, frame ->
            val dx = if (shifted && position != referencePosition) {
                if (position % 2 == 0) 2.0 else -2.0
            } else {
                0.0
            }
            val transform = RawTransform(dx = dx, dy = 0.0)
            val result = AlignmentResult(
                frameIndex = frame.frameIndex,
                mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
                transform = transform,
                confidence = 1.0,
                accepted = true,
                response = if (position == referencePosition) null else 1.0
            )
            LandscapeFrameAlignment(
                framePosition = position,
                frameIndex = frame.frameIndex,
                isReference = position == referencePosition,
                proxyTypeUsed = null,
                lumaFallbackUsed = false,
                targetExposureTimeSeconds = frame.exposureTimeSeconds,
                referenceExposureTimeSeconds = referenceFrame.exposureTimeSeconds,
                phaseResponse = result.response,
                dxRawPixels = dx,
                dyRawPixels = 0.0,
                overlapFraction = 1.0,
                accepted = true,
                lowConfidence = false,
                rejectionReason = null,
                diagnosticMessage = "memory stabilization synthetic transform",
                warnings = emptyList(),
                alignmentResult = result
            )
        }
        return LandscapeAlignmentReport(
            status = LandscapeAlignmentStatus.SUCCESS,
            success = true,
            partialSuccess = false,
            selectedReferenceFrameIndex = referenceFrame.frameIndex,
            selectedReferencePosition = referencePosition,
            referenceSelectionMethod = ReferenceSelectionMethod.MIDDLE_FRAME_INDEX,
            referenceExposureTimeSeconds = referenceFrame.exposureTimeSeconds,
            totalFrameCount = frames.size,
            acceptedFrameCount = frames.size,
            rejectedFrameCount = 0,
            lowConfidenceFrameCount = 0,
            frameResults = frameResults,
            alignmentResults = frameResults.map { it.alignmentResult },
            warnings = emptyList(),
            fatalError = null,
            fatalMessage = null,
            processingDurationMs = 0L,
            options = LandscapeAlignmentOptions(exposureNormalizeProxies = false)
        )
    }

    private fun oldMeanBytes(frames: List<RawFrame>): ByteArray {
        val output = ByteArray(WIDTH * HEIGHT * 2)
        for (pixel in 0 until WIDTH * HEIGHT) {
            var sum = 0L
            frames.forEach { frame ->
                val offset = pixel * 2
                sum += (frame.raw16!![offset].toInt() and 0xFF) or
                    ((frame.raw16[offset + 1].toInt() and 0xFF) shl 8)
            }
            val value = ((sum + frames.size / 2) / frames.size).toInt()
            output[pixel * 2] = (value and 0xFF).toByte()
            output[pixel * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        return output
    }

    private fun writerCleanupTest(fail: Boolean): Boolean {
        val input = frames()
        val packedFile = File.createTempFile("bracketlab_writer_", ".raw16")
        val stackResult = AlignedRaw16StackProcessor().processToPackedFile(
            RawStack(input),
            identityReport(input, shifted = false),
            options(RawStackAggregationMode.MEAN, 2),
            packedFile
        )
        val result = Raw16DngWriter.writeWithEngine(
            request = Raw16DngWriteRequest(
                alignedResult = stackResult,
                cameraCharacteristics = null,
                captureResult = null,
                destination = MemoryDestination(),
                referenceFrameIndex = stackResult.referenceFrameIndex,
                metadataFrameIndex = stackResult.referenceFrameIndex,
                expectedWidth = WIDTH,
                expectedHeight = HEIGHT,
                deletePackedInputAfterWrite = true
            ),
            engine = MemoryWriteEngine(fail),
            requireAndroidMetadata = false
        )
        return result.success == !fail && !packedFile.exists()
    }

    private fun packed(samples: IntArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value ->
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun test(
        name: String,
        block: () -> Boolean
    ): MemoryStabilizationSelfTestCaseResult =
        try {
            if (block()) {
                MemoryStabilizationSelfTestCaseResult(
                    name,
                    PhaseCorrelationSelfTestStatus.PASS,
                    "pass"
                )
            } else {
                MemoryStabilizationSelfTestCaseResult(
                    name,
                    PhaseCorrelationSelfTestStatus.FAIL,
                    "condition returned false"
                )
            }
        } catch (e: Throwable) {
            MemoryStabilizationSelfTestCaseResult(
                name,
                PhaseCorrelationSelfTestStatus.FAIL,
                "${e.javaClass.simpleName}: ${e.message}"
            )
        }

    private class MemoryDestination : Raw16DngOutputDestination {
        private val bytes = ByteArrayOutputStream()
        override val requestedFilename: String = "memory-test.dng"

        override fun open(): Raw16DngOpenedOutput =
            Raw16DngOpenedOutput(
                outputStream = bytes,
                filename = requestedFilename,
                finalPath = "/memory-test.dng",
                finalUri = null,
                bytesWrittenProvider = { bytes.size().toLong() },
                commitAction = { true },
                cleanupAction = { true }
            )
    }

    private class MemoryWriteEngine(
        private val fail: Boolean
    ) : Raw16DngWriteEngine {
        override val method: Raw16DngWriterMethod = Raw16DngWriterMethod.INPUT_STREAM

        override fun write(
            request: Raw16DngWriteRequest,
            outputStream: OutputStream,
            rawSource: Raw16DngSource,
            description: String
        ) {
            outputStream.write(1)
            if (fail) throw IOException("simulated DNG write failure")
        }
    }

    private const val WIDTH = 8
    private const val HEIGHT = 6
}
