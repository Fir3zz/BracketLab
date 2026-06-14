package com.lab.bracketlab.processing.align.star.warp

import com.lab.bracketlab.processing.align.star.StarAlignmentProcessor
import com.lab.bracketlab.processing.align.star.StarAlignmentReport
import com.lab.bracketlab.processing.align.star.StarCatalog
import com.lab.bracketlab.processing.align.star.StarDetectionOptions
import com.lab.bracketlab.processing.align.star.StarDetector
import com.lab.bracketlab.processing.align.star.StarSimilarityTransform
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.storage.FileBackedRaw16FrameStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

/**
 * Manual workstation validation for the three real Xiaomi 12 DNGs.
 *
 * This test is skipped unless BRACKETLAB_RUN_THREE_DNG_INTEGRATION=1. It
 * intentionally writes reproducible artifacts next to the input DNGs.
 */
class ThreeDngStarAlignedIntegrationTest {
    @Test
    fun verifyThreeRealDngsAndWriteComparisonOutputs() {
        assumeTrue(System.getenv("BRACKETLAB_RUN_THREE_DNG_INTEGRATION") == "1")
        val root = locateProjectRoot()
        val inputs =
            INPUT_NAMES.map { name ->
                File(root, name).also { assertTrue("Missing ${it.absolutePath}", it.isFile) }
            }
        inputs.forEach {
            assertEquals(DNG_FILE_BYTES, it.length())
        }

        val outputDirectory = File(root, OUTPUT_DIRECTORY).apply { mkdirs() }
        val workDirectory = File(outputDirectory, ".work").apply {
            deleteRecursively()
            mkdirs()
        }
        val rawFiles =
            inputs.mapIndexed { index, input ->
                File(workDirectory, "frame_$index.raw16").also {
                    extractRawStrip(input, it)
                    assertEquals(RAW_PAYLOAD_BYTES, it.length())
                }
            }
        val frames =
            rawFiles.mapIndexed { index, rawFile ->
                RawFrame(
                    width = WIDTH,
                    height = HEIGHT,
                    raw16Storage = FileBackedRaw16FrameStorage(rawFile, deleteOnCleanup = false),
                    rowStride = WIDTH * 2,
                    pixelStride = 2,
                    exposureTimeNs = 1_000_000_000L,
                    iso = 100,
                    cameraId = "xiaomi12_validation",
                    timestampNs = index.toLong(),
                    frameIndex = index,
                    sourceFilePath = inputs[index].absolutePath,
                    blackLevelPattern = intArrayOf(64, 64, 64, 64),
                    whiteLevel = 1023,
                    cfaPattern = CfaPattern.BGGR
                )
            }
        val stack = RawStack(frames)
        val detectionOptions =
            StarDetectionOptions(
                proxyMaxDimension = 1536,
                maxStars = 500,
                suppressHotPixelsForProxy = false
            )
        val detection = StarDetector().detect(stack, detectionOptions)
        assertTrue(detection.failureMessage, detection.success)
        assertEquals(3, detection.catalogs.size)
        assertTrue(detection.catalogs.all { it.starCount == 500 })

        val alignment = StarAlignmentProcessor().align(detection.catalogs)
        assertTrue(alignment.fatalMessage, alignment.success)
        assertEquals(1, alignment.referenceFrameIndex)
        assertEquals(3, alignment.acceptedFrameCount)

        val controlReport = identityReport(alignment)
        val controlRaw = File(workDirectory, "control_identity.raw16")
        val alignedRaw = File(workDirectory, "star_aligned.raw16")
        val processor = StarAlignedRaw16StackProcessor()
        val options =
            StarAlignedRaw16StackOptions(
                preferredTileWidth = 256,
                preferredTileHeight = 128,
                tileWorkingMemoryBudgetBytes = 24L * 1024L * 1024L
            )
        val control = processor.processToPackedFile(stack, controlReport, options, controlRaw)
        assertTrue(control.fatalMessage, control.success)
        assertEquals(RAW_PAYLOAD_BYTES, controlRaw.length())
        val aligned = processor.processToPackedFile(stack, alignment, options, alignedRaw)
        assertTrue(aligned.fatalMessage, aligned.success)
        assertEquals(RAW_PAYLOAD_BYTES, alignedRaw.length())

        val referenceDng = inputs[1]
        val controlDng = File(outputDirectory, CONTROL_DNG)
        val alignedDng = File(outputDirectory, ALIGNED_DNG)
        writeDngWithReplacementStrip(referenceDng, controlRaw, controlDng)
        writeDngWithReplacementStrip(referenceDng, alignedRaw, alignedDng)
        assertEquals(DNG_FILE_BYTES, controlDng.length())
        assertEquals(DNG_FILE_BYTES, alignedDng.length())

        val controlCatalog = detectOutputCatalog(controlRaw)
        val alignedCatalog = detectOutputCatalog(alignedRaw)
        val reportFile = File(outputDirectory, REPORT_NAME)
        reportFile.writeText(
            buildReport(
                inputs,
                detection.catalogs,
                alignment,
                control,
                aligned,
                controlDng,
                alignedDng,
                controlCatalog,
                alignedCatalog
            )
        )

        println("Three-DNG validation report: ${reportFile.absolutePath}")
        println("Identity control DNG: ${controlDng.absolutePath}")
        println("Star-aligned DNG: ${alignedDng.absolutePath}")
        assertTrue(reportFile.isFile && reportFile.length() > 0L)
        assertTrue(controlDng.isFile && controlDng.length() > RAW_PAYLOAD_BYTES)
        assertTrue(alignedDng.isFile && alignedDng.length() > RAW_PAYLOAD_BYTES)
        workDirectory.deleteRecursively()
    }

    private fun detectOutputCatalog(rawFile: File): StarCatalog {
        val frame =
            RawFrame(
                width = WIDTH,
                height = HEIGHT,
                raw16Storage = FileBackedRaw16FrameStorage(rawFile, deleteOnCleanup = false),
                rowStride = WIDTH * 2,
                pixelStride = 2,
                exposureTimeNs = 1_000_000_000L,
                iso = 100,
                cameraId = "xiaomi12_validation_output",
                timestampNs = 0L,
                frameIndex = 0,
                blackLevelPattern = intArrayOf(64, 64, 64, 64),
                whiteLevel = 1023,
                cfaPattern = CfaPattern.BGGR
            )
        val result =
            StarDetector().detect(
                RawStack(listOf(frame)),
                StarDetectionOptions(proxyMaxDimension = 1536, maxStars = 500)
            )
        assertTrue(result.failureMessage, result.success)
        return result.catalogs.single()
    }

    private fun identityReport(report: StarAlignmentReport): StarAlignmentReport {
        val frameResults =
            report.frameResults.map { frame ->
                frame.copy(
                    transform = StarSimilarityTransform.IDENTITY,
                    alignmentResult =
                        frame.alignmentResult.copy(
                            transform = RawTransform.IDENTITY,
                            diagnosticMessage =
                                if (frame.isReference) {
                                    "Identity control reference."
                                } else {
                                    "Identity control; star transform deliberately disabled."
                                }
                        )
                )
            }
        return report.copy(
            frameResults = frameResults,
            alignmentResults = frameResults.map { it.alignmentResult },
            warnings = report.warnings + "Identity control report for workstation validation."
        )
    }

    private fun extractRawStrip(input: File, output: File) {
        RandomAccessFile(input, "r").use { source ->
            source.seek(RAW_STRIP_OFFSET)
            BufferedOutputStream(FileOutputStream(output)).use { destination ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var remaining = RAW_PAYLOAD_BYTES
                while (remaining > 0L) {
                    val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(count > 0) { "Unexpected EOF in ${input.name} RAW strip." }
                    destination.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
    }

    private fun writeDngWithReplacementStrip(reference: File, raw: File, output: File) {
        BufferedInputStream(FileInputStream(reference)).use { input ->
            BufferedOutputStream(FileOutputStream(output)).use { destination ->
                input.copyTo(destination, COPY_BUFFER_BYTES)
            }
        }
        RandomAccessFile(output, "rw").use { destination ->
            destination.seek(RAW_STRIP_OFFSET)
            BufferedInputStream(FileInputStream(raw)).use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var written = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                    written += count
                }
                check(written == RAW_PAYLOAD_BYTES) {
                    "Replacement RAW strip has $written bytes; expected $RAW_PAYLOAD_BYTES."
                }
            }
        }
    }

    private fun buildReport(
        inputs: List<File>,
        catalogs: List<StarCatalog>,
        alignment: StarAlignmentReport,
        control: StarAlignedRaw16StackResult,
        aligned: StarAlignedRaw16StackResult,
        controlDng: File,
        alignedDng: File,
        controlCatalog: StarCatalog,
        alignedCatalog: StarCatalog
    ): String =
        buildString {
            appendLine("BracketLab Prompt 10C three-DNG workstation validation")
            appendLine("Inputs=${inputs.joinToString { it.name }}")
            appendLine("DNG layout=uncompressed single-strip CFA RAW16")
            appendLine("Dimensions=${WIDTH}x$HEIGHT CFA=BGGR")
            appendLine("RawStripOffset=$RAW_STRIP_OFFSET RawStripBytes=$RAW_PAYLOAD_BYTES")
            appendLine("ReferenceFrame=${alignment.referenceFrameIndex} (${inputs[1].name})")
            appendLine("DetectionDurationMs=${catalogs.sumOf { it.durationMs }}")
            catalogs.forEach { catalog ->
                appendLine(
                    "Catalog frame=${catalog.frameIndex} stars=${catalog.starCount} " +
                        "localMaxima=${catalog.localMaximumCount} " +
                        "background=${format(catalog.backgroundEstimate)} " +
                        "sigma=${format(catalog.noiseEstimate)} threshold=${format(catalog.thresholdUsed)}"
                )
            }
            appendLine("AlignmentDurationMs=${alignment.durationMs}")
            alignment.frameResults.forEach { frame ->
                appendLine(
                    "Transform frame=${frame.frameIndex} reference=${frame.isReference} " +
                        "accepted=${frame.accepted} strategy=${frame.matchingStrategy} " +
                        "candidates=${frame.candidateMatchCount} inliers=${frame.ransacInlierCount} " +
                        "outliers=${frame.outlierCount} rms=${format(frame.rmsResidualRawPixels)} " +
                        "tx=${format(frame.transform.tx)} ty=${format(frame.transform.ty)} " +
                        "rotationDeg=${format(frame.transform.rotationDegrees)} " +
                        "scale=${format(frame.transform.scale)}"
                )
            }
            appendStack("IdentityControl", control)
            appendStack("StarAligned", aligned)
            appendLine("IdentityOutput=${controlDng.absolutePath}")
            appendLine("IdentityBytes=${controlDng.length()} SHA256=${sha256(controlDng)}")
            appendLine("StarAlignedOutput=${alignedDng.absolutePath}")
            appendLine("StarAlignedBytes=${alignedDng.length()} SHA256=${sha256(alignedDng)}")
            appendCatalogQuality("IdentityControl", controlCatalog)
            appendCatalogQuality("StarAligned", alignedCatalog)
            appendLine(
                "MedianSharpnessRatioAlignedToControl=" +
                    format(median(alignedCatalog.stars.map { it.sharpness }) /
                        median(controlCatalog.stars.map { it.sharpness }))
            )
            appendLine(
                "MedianRadiusRatioAlignedToControl=" +
                    format(median(alignedCatalog.stars.map { it.radius }) /
                        median(controlCatalog.stars.map { it.radius }))
            )
            appendLine("RawTransformApplied=true")
            appendLine("ExistingInputDngsModified=false")
        }

    private fun StringBuilder.appendStack(
        label: String,
        result: StarAlignedRaw16StackResult
    ) {
        appendLine(
            "$label success=${result.success} durationMs=${result.processingDurationMs} " +
                "accepted=${result.acceptedFrameCount} rejected=${result.rejectedFrameCount}"
        )
        appendLine(
            "$label validCount min=${result.minimumValidCount} " +
                "max=${result.maximumValidCount} mean=${format(result.meanValidCount)} " +
                "referenceOnly=${result.referenceOnlyPixelCount} " +
                "fullContributors=${result.fullContributorPixelCount}"
        )
        appendLine(
            "$label tile=${result.tilePlan?.tileWidth}x${result.tilePlan?.tileHeight} " +
                "workingBytes=${result.tilePlan?.estimatedWorkingBytes}"
        )
        result.frameDiagnostics.forEach { frame ->
            appendLine(
                "$label frame=${frame.frameIndex} reference=${frame.reference} " +
                    "validFraction=${format(frame.validFraction)} direct=${frame.directSamples} " +
                    "horizontal=${frame.horizontalLinearSamples} vertical=${frame.verticalLinearSamples} " +
                    "bilinear=${frame.bilinearSamples} invalid=${frame.invalidSamples}"
            )
        }
        result.warnings.forEach {
            appendLine("$label warning=${it.code}: ${it.message}")
        }
    }

    private fun StringBuilder.appendCatalogQuality(label: String, catalog: StarCatalog) {
        appendLine(
            "$label catalog stars=${catalog.starCount} localMaxima=${catalog.localMaximumCount} " +
                "medianSnr=${format(median(catalog.stars.map { it.snr }))} " +
                "medianSharpness=${format(median(catalog.stars.map { it.sharpness }))} " +
                "medianRadius=${format(median(catalog.stars.map { it.radius }))} " +
                "medianFlux=${format(median(catalog.stars.map { it.flux }))}"
        )
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if ((sorted.size and 1) == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) * 0.5
        }
    }

    private fun format(value: Double?): String =
        if (value == null) "n/a" else String.format(Locale.US, "%.8f", value)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun locateProjectRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            if (INPUT_NAMES.all { name -> File(current, name).isFile }) return current
            current = current.parentFile ?: return@repeat
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }

    companion object {
        private const val WIDTH = 4096
        private const val HEIGHT = 3072
        private const val RAW_STRIP_OFFSET = 5552L
        private const val RAW_PAYLOAD_BYTES = 25_165_824L
        private const val DNG_FILE_BYTES = 25_171_376L
        private const val COPY_BUFFER_BYTES = 1 shl 20
        private const val OUTPUT_DIRECTORY = "star_aligned_validation_3dng"
        private const val CONTROL_DNG = "BracketLab_3DNG_IdentityControl_RAW16.dng"
        private const val ALIGNED_DNG = "BracketLab_3DNG_StarAligned_RAW16.dng"
        private const val REPORT_NAME = "BracketLab_3DNG_StarAligned_Report.txt"
        private val INPUT_NAMES =
            listOf("1000111154.dng", "1000111155.dng", "1000111156.dng")
    }
}
