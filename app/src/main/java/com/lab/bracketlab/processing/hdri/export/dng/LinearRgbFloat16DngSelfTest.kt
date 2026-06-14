package com.lab.bracketlab.processing.hdri.export.dng

import com.lab.bracketlab.processing.align.PhaseCorrelationSelfTestStatus
import com.lab.bracketlab.processing.calibration.MasterDarkSelfTest
import com.lab.bracketlab.processing.debug.IncrementalDiagnosticReport
import com.lab.bracketlab.processing.hdri.HdrI32AlignmentMode
import com.lab.bracketlab.processing.hdri.HdrI32Frame
import com.lab.bracketlab.processing.hdri.HdrI32InvalidSamplePolicy
import com.lab.bracketlab.processing.hdri.HdrI32Metadata
import com.lab.bracketlab.processing.hdri.HdrI32SourceFrameMetadata
import com.lab.bracketlab.processing.hdri.HdrI32WeightPolicy
import com.lab.bracketlab.processing.hdri.HdrI32FitsWriter
import com.lab.bracketlab.processing.hdri.HdrI32XisfWriter
import com.lab.bracketlab.processing.raw.CfaColor
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.stack.AlignedRaw16StackSelfTest
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log2

data class LinearRgbFloat16DngSelfTestCase(
    val name: String,
    val status: PhaseCorrelationSelfTestStatus,
    val message: String
)

data class LinearRgbFloat16DngSelfTestReport(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val durationMs: Long,
    val results: List<LinearRgbFloat16DngSelfTestCase>
)

object LinearRgbFloat16DngSelfTest {
    fun runAll(): LinearRgbFloat16DngSelfTestReport {
        val startedNs = System.nanoTime()
        val results = listOf(
            test("1 half 0.0") { HalfFloat.fromFloat(0f).u16() == 0x0000 },
            test("2 half 1.0") { HalfFloat.fromFloat(1f).u16() == 0x3C00 },
            test("3 half above 1 preserved") {
                nearly(HalfFloat.toFloat(HalfFloat.fromFloat(2f)), 2f)
            },
            test("4 half negative clamps to zero") {
                val stats = HalfFloatDiagnostics()
                HalfFloat.fromFloat(-1f, stats).u16() == 0 && stats.negativeClamps == 1L
            },
            test("5 half NaN replaced and counted") {
                val stats = HalfFloatDiagnostics()
                HalfFloat.fromFloat(Float.NaN, stats).u16() == 0 &&
                    stats.invalidReplacements == 1L
            },
            test("6 half infinity clamped and counted") {
                val stats = HalfFloatDiagnostics()
                HalfFloat.fromFloat(Float.POSITIVE_INFINITY, stats).u16() ==
                    HalfFloat.MAX_FINITE_BITS &&
                    stats.invalidReplacements == 1L &&
                    stats.maximumClamps == 1L
            },
            test("7 half large finite clamps") {
                val stats = HalfFloatDiagnostics()
                HalfFloat.fromFloat(100_000f, stats).u16() == HalfFloat.MAX_FINITE_BITS &&
                    stats.maximumClamps == 1L
            },
            test("8 half little-endian bytes") {
                val bits = HalfFloat.fromFloat(1f).u16()
                (bits and 0xFF) == 0x00 && ((bits ushr 8) and 0xFF) == 0x3C
            },
            test("9 zero radiance normalizes to zero") {
                normalizedSample(CfaPattern.RGGB, 0f, 0, 0) == 0f
            },
            test("10 reference white normalizes near one") {
                nearly(normalizedSample(CfaPattern.RGGB, 900f, 0, 0), 1f)
            },
            test("11 HDR highlight normalizes above one") {
                normalizedSample(CfaPattern.RGGB, 1800f, 0, 0) > 1f
            },
            test("12 per-CFA black level affects full scale") {
                val red = normalizedSample(CfaPattern.RGGB, 900f, 0, 0)
                val blue = normalizedSample(CfaPattern.RGGB, 900f, 1, 1)
                nearly(red, 1f) && nearly(blue, 900f / 960f)
            },
            test("13 black is not subtracted twice") {
                nearly(normalizedSample(CfaPattern.RGGB, 450f, 0, 0), 0.5f)
            },
            test("14 RGGB bilinear demosaic") { demosaicPattern(CfaPattern.RGGB) },
            test("15 GRBG bilinear demosaic") { demosaicPattern(CfaPattern.GRBG) },
            test("16 GBRG bilinear demosaic") { demosaicPattern(CfaPattern.GBRG) },
            test("17 BGGR bilinear demosaic") { demosaicPattern(CfaPattern.BGGR) },
            test("18 constant CFA gives constant RGB") {
                withColorFrame(CfaPattern.RGGB, 0.5f, 0.5f, 0.5f) { frame, _ ->
                    readDemosaic(frame).all { nearly(it, 0.5f) }
                }
            },
            test("19 values above one survive demosaic") {
                withColorFrame(CfaPattern.GRBG, 2f, 3f, 4f) { frame, _ ->
                    readDemosaic(frame).maxOrNull()!! > 1f
                }
            },
            test("20 tile boundaries have no seams") {
                withColorFrame(CfaPattern.RGGB, 0.2f, 0.5f, 0.8f) { frame, _ ->
                    readDemosaic(frame, frame.height).contentEquals(readDemosaic(frame, 2))
                }
            },
            test("21 edge handling deterministic") {
                withColorFrame(CfaPattern.BGGR, 0.2f, 0.5f, 0.8f) { frame, _ ->
                    readDemosaic(frame).contentEquals(readDemosaic(frame))
                }
            },
            test("22 valid little-endian TIFF header") {
                withWrittenDng { file, _, _ ->
                    val bytes = file.readBytes()
                    bytes[0].toInt().toChar() == 'I' &&
                        bytes[1].toInt().toChar() == 'I' &&
                        ByteBuffer.wrap(bytes, 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() == 42
                }
            },
            test("23 IFD tags are increasing") {
                withWrittenDng { file, _, _ ->
                    val tags = TiffInspector(file).tags()
                    tags == tags.sorted()
                }
            },
            test("24 strip offsets are valid") {
                withWrittenDng { file, _, _ ->
                    val inspector = TiffInspector(file)
                    inspector.longs(DngTag.STRIP_OFFSETS).all { it in 1 until file.length() }
                }
            },
            test("25 strip byte counts match payload") {
                withWrittenDng { file, frame, _ ->
                    TiffInspector(file).longs(DngTag.STRIP_BYTE_COUNTS).sum() ==
                        frame.width.toLong() * frame.height * 6L
                }
            },
            test("26 bits per sample are 16 16 16") {
                withWrittenDng { file, _, _ ->
                    TiffInspector(file).shorts(DngTag.BITS_PER_SAMPLE)
                        .contentEquals(intArrayOf(16, 16, 16))
                }
            },
            test("27 sample format is IEEE float") {
                withWrittenDng { file, _, _ ->
                    TiffInspector(file).shorts(DngTag.SAMPLE_FORMAT)
                        .contentEquals(intArrayOf(3, 3, 3))
                }
            },
            test("28 samples per pixel is three") {
                withWrittenDng { file, _, _ ->
                    TiffInspector(file).shorts(DngTag.SAMPLES_PER_PIXEL)
                        .contentEquals(intArrayOf(3))
                }
            },
            test("29 planar configuration is chunky") {
                withWrittenDng { file, _, _ ->
                    TiffInspector(file).shorts(DngTag.PLANAR_CONFIGURATION)
                        .contentEquals(intArrayOf(1))
                }
            },
            test("30 main image declared and CFA tags absent") {
                withWrittenDng { file, _, _ ->
                    val inspector = TiffInspector(file)
                    val tags = inspector.tags()
                    inspector.longs(DngTag.NEW_SUBFILE_TYPE)
                        .contentEquals(longArrayOf(0L)) &&
                        33421 !in tags &&
                        33422 !in tags
                }
            },
            test("31 white level present") {
                withWrittenDng { file, _, _ ->
                    TiffInspector(file).longs(DngTag.WHITE_LEVEL)
                        .contentEquals(longArrayOf(1, 1, 1))
                }
            },
            test("32 black level present") {
                withWrittenDng { file, _, _ ->
                    TiffInspector(file).rationals(DngTag.BLACK_LEVEL)
                        .all { it == 0.0 }
                }
            },
            test("33 DNG version present") {
                withWrittenDng { file, _, _ ->
                    val inspector = TiffInspector(file)
                    inspector.bytes(DngTag.DNG_VERSION)
                        .contentEquals(byteArrayOf(1, 4, 0, 0)) &&
                        inspector.longs(DngTag.DEFAULT_BLACK_RENDER)
                            .contentEquals(longArrayOf(1L))
                }
            },
            test("34 color metadata written") {
                withWrittenDng { file, _, _ ->
                    val tags = TiffInspector(file).tags()
                    DngTag.COLOR_MATRIX_1 in tags && DngTag.AS_SHOT_NEUTRAL in tags
                }
            },
            test("35 missing color metadata rejected by contract") {
                val metadata = metadata()
                val invalid = metadata.copy(colorMatrix1 = emptyList())
                withColorFrame(CfaPattern.RGGB, 0.2f, 0.5f, 0.8f) { frame, directory ->
                    val result = LinearRgbFloat16DngWriter.write(
                        LinearRgbFloat16DngWriteRequest(
                            frame,
                            invalid,
                            File(directory, "invalid.dng")
                        )
                    )
                    result.failureCode ==
                        LinearRgbFloat16DngFailureCode.INVALID_COLOR_METADATA
                }
            },
            test("36 no full RGB image retained") {
                LinearRgbFloat16DngWriter::class.java.declaredFields.none {
                    it.type == FloatArray::class.java || it.type == ByteArray::class.java
                }
            },
            test("37 uncompressed output byte count correct") {
                withWrittenDng { file, frame, _ ->
                    val inspector = TiffInspector(file)
                    file.length() - inspector.longs(DngTag.STRIP_OFFSETS).first() ==
                        frame.width.toLong() * frame.height * 6L
                }
            },
            test("38 temporary files cleaned on success") {
                withWrittenDng { file, _, _ ->
                    file.parentFile!!.listFiles().orEmpty().none { it.name.endsWith(".tmp") }
                }
            },
            test("39 temporary files cleaned on failure") {
                withColorFrame(CfaPattern.RGGB, 0.2f, 0.5f, 0.8f) { frame, directory ->
                    val blocker = File(directory, "blocker").also { it.writeText("x") }
                    val result = LinearRgbFloat16DngWriter.write(
                        LinearRgbFloat16DngWriteRequest(
                            frame,
                            metadata(),
                            File(blocker, "failed.dng")
                        )
                    )
                    !result.success &&
                        directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") }
                }
            },
            test("40 report survives simulated failure") {
                withTempDir { directory ->
                    val report = File(directory, "report.txt")
                    runCatching {
                        IncrementalDiagnosticReport(report).use {
                            it.append("Linear RGB export started")
                            error("simulated")
                        }
                    }
                    report.readText().contains("Linear RGB export started")
                }
            },
            test("41 output globally normalizes values above one") {
                withWrittenDng(2f, 3f, 4f) { file, _, result ->
                    result.diagnostics!!.valuesAboveOneBeforeScale > 0 &&
                        result.diagnostics.valuesAboveOneAfterScale == 0L &&
                        readFirstPixel(file).all { it <= 1f }
                }
            },
            test("42 relative ratios remain linear") {
                withWrittenDng(0.5f, 1f, 2f) { file, _, _ ->
                    val rgb = readFirstPixel(file)
                    nearly(rgb[1] / rgb[0], 2f, 0.01f) &&
                        nearly(rgb[2] / rgb[1], 2f, 0.01f)
                }
            },
            test("43 no gamma changes ratios") {
                withWrittenDng(0.25f, 0.5f, 1f) { file, _, _ ->
                    val rgb = readFirstPixel(file)
                    nearly(rgb[0], 0.25f, 0.01f) &&
                        nearly(rgb[1], 0.5f, 0.01f)
                }
            },
            test("44 description states no tone mapping") {
                withWrittenDng { file, _, _ ->
                    TiffInspector(file).ascii(DngTag.IMAGE_DESCRIPTION)
                        .contains("no tone mapping or gamma")
                }
            },
            test("46 HDR CFA master unchanged") {
                withColorFrame(CfaPattern.RGGB, 0.2f, 0.5f, 2f) { frame, directory ->
                    val before = frame.storageFile.readBytes()
                    LinearRgbFloat16DngWriter.write(
                        LinearRgbFloat16DngWriteRequest(
                            frame,
                            metadata(),
                            File(directory, "output.dng")
                        )
                    )
                    frame.storageFile.readBytes().contentEquals(before)
                }
            },
            test("47 FITS32 and XISF32 paths remain valid") {
                withColorFrame(CfaPattern.RGGB, 0.2f, 0.5f, 2f) { frame, directory ->
                    HdrI32FitsWriter.write(frame, File(directory, "master.fits")).success &&
                        HdrI32XisfWriter.write(frame, File(directory, "master.xisf")).success
                }
            },
            regression("49 existing RAW16 stack tests pass") {
                AlignedRaw16StackSelfTest.runAll().failed == 0
            },
            regression("50 existing MasterDark tests pass") {
                MasterDarkSelfTest.runAll().failed == 0
            },
            test("51 max RGB at or below one keeps scale and baseline neutral") {
                withWrittenDng(0.25f, 0.5f, 1f) { _, _, result ->
                    nearly(result.diagnostics!!.globalScaleApplied.toFloat(), 1f) &&
                        nearly(result.diagnostics.baselineExposureWritten.toFloat(), 0f)
                }
            },
            test("52 max RGB four uses quarter scale and two EV baseline") {
                withWrittenDng(2f, 3f, 4f) { file, _, result ->
                    val diagnostics = result.diagnostics!!
                    nearly(diagnostics.maxRgbBeforeScale.toFloat(), 4f) &&
                        nearly(diagnostics.globalScaleApplied.toFloat(), 0.25f) &&
                        nearly(diagnostics.baselineExposureWritten.toFloat(), 2f) &&
                        nearly(
                            TiffInspector(file)
                                .signedRationals(DngTag.BASELINE_EXPOSURE)
                                .first()
                                .toFloat(),
                            2f
                        )
                }
            },
            test("53 max RGB 4.23 writes computed logarithmic baseline") {
                withWrittenDng(1f, 2f, 4.23f) { _, _, result ->
                    nearly(
                        result.diagnostics!!.baselineExposureWritten.toFloat(),
                        log2(4.23f),
                        0.001f
                    )
                }
            },
            test("54 one global scale preserves channel ratios") {
                withWrittenDng(1f, 2f, 4f) { file, _, _ ->
                    val rgb = readFirstPixel(file)
                    nearly(rgb[1] / rgb[0], 2f, 0.01f) &&
                        nearly(rgb[2] / rgb[1], 2f, 0.01f)
                }
            },
            test("55 values are not independently channel clipped") {
                withWrittenDng(2f, 3f, 4f) { file, _, _ ->
                    val rgb = readFirstPixel(file)
                    nearly(rgb[0], 0.5f, 0.01f) &&
                        nearly(rgb[1], 0.75f, 0.01f) &&
                        nearly(rgb[2], 1f, 0.01f)
                }
            },
            test("56 expected RGB payload is width height three channels half") {
                withWrittenDng { _, frame, result ->
                    result.diagnostics!!.expectedImageBytes ==
                        frame.width.toLong() * frame.height.toLong() * 3L * 2L
                }
            },
            test("57 strip byte counts include all three channels") {
                withWrittenDng(rowsPerStrip = 3) { file, frame, result ->
                    val counts = TiffInspector(file).longs(DngTag.STRIP_BYTE_COUNTS)
                    counts.first() == frame.width.toLong() * 3L * 3L * 2L &&
                        counts.last() == frame.width.toLong() * 1L * 3L * 2L &&
                        counts.sum() == result.diagnostics!!.expectedImageBytes
                }
            },
            test("58 strip offsets and extents stay inside file") {
                withWrittenDng(rowsPerStrip = 3) { file, _, result ->
                    val inspector = TiffInspector(file)
                    val offsets = inspector.longs(DngTag.STRIP_OFFSETS)
                    val counts = inspector.longs(DngTag.STRIP_BYTE_COUNTS)
                    offsets.zip(counts).all { (offset, count) ->
                        offset + count <= file.length()
                    } &&
                        (1 until offsets.size).all { index ->
                            offsets[index] > offsets[index - 1]
                        } &&
                        result.diagnostics!!.stripTableValid
                }
            },
            test("59 strip byte-count sum matches expected payload") {
                withWrittenDng { _, _, result ->
                    result.diagnostics!!.sumStripByteCounts ==
                        result.diagnostics.expectedImageBytes
                }
            },
            test("60 chunky payload begins RGB RGB order") {
                withWrittenDng(0.25f, 0.5f, 1f) { file, _, _ ->
                    val rgb = readFirstPixel(file)
                    nearly(rgb[0], 0.25f, 0.01f) &&
                        nearly(rgb[1], 0.5f, 0.01f) &&
                        nearly(rgb[2], 1f, 0.01f)
                }
            },
            test("61 global normalization leaves CFA HDR master unchanged") {
                withColorFrame(CfaPattern.RGGB, 2f, 3f, 4f) { frame, directory ->
                    val before = frame.storageFile.readBytes()
                    val result = LinearRgbFloat16DngWriter.write(
                        LinearRgbFloat16DngWriteRequest(
                            frame,
                            metadata(),
                            File(directory, "normalized.dng")
                        )
                    )
                    result.success && frame.storageFile.readBytes().contentEquals(before)
                }
            },
            test("62 normalization helper is deterministic") {
                LinearRgbFloat16Normalization.fromMaximum(4.0) ==
                    LinearRgbFloat16Normalization.fromMaximum(4.0)
            },
            test("63 no CFA tags are introduced by normalization") {
                withWrittenDng(2f, 3f, 4f) { file, _, _ ->
                    val tags = TiffInspector(file).tags()
                    33421 !in tags && 33422 !in tags
                }
            },
            test("64 description records compensated global normalization") {
                withWrittenDng(2f, 3f, 4f) { file, _, _ ->
                    TiffInspector(file).ascii(DngTag.IMAGE_DESCRIPTION)
                        .contains("BaselineExposure")
                }
            }
        )
        return LinearRgbFloat16DngSelfTestReport(
            passed = results.count { it.status == PhaseCorrelationSelfTestStatus.PASS },
            failed = results.count { it.status == PhaseCorrelationSelfTestStatus.FAIL },
            skipped = results.count { it.status == PhaseCorrelationSelfTestStatus.SKIPPED },
            durationMs = (System.nanoTime() - startedNs) / 1_000_000L,
            results = results
        )
    }

    private fun normalizedSample(pattern: CfaPattern, radiance: Float, x: Int, y: Int): Float =
        withFrame(pattern, FloatArray(PIXELS) { radiance }) { frame, _ ->
            HdrCfaFloat32Demosaicer(frame).use {
                it.readTile(0, frame.height).sample(x, y)
            }
        }

    private fun demosaicPattern(pattern: CfaPattern): Boolean =
        withColorFrame(pattern, 0.2f, 0.5f, 0.8f) { frame, _ ->
            val rgb = readDemosaic(frame)
            rgb.indices.step(3).all { index ->
                nearly(rgb[index], 0.2f) &&
                    nearly(rgb[index + 1], 0.5f) &&
                    nearly(rgb[index + 2], 0.8f)
            }
        }

    private fun readDemosaic(frame: HdrI32Frame, tileHeight: Int = 2): FloatArray {
        val output = FloatArray(frame.width * frame.height * 3)
        HdrCfaFloat32Demosaicer(frame).use { demosaicer ->
            val row = FloatArray(frame.width * 3)
            for (startY in 0 until frame.height step tileHeight) {
                val rows = minOf(tileHeight, frame.height - startY)
                val tile = demosaicer.readTile(startY, rows)
                for (y in startY until startY + rows) {
                    demosaicer.demosaicRow(tile, y, row)
                    row.copyInto(output, y * frame.width * 3)
                }
            }
        }
        return output
    }

    private inline fun withWrittenDng(
        red: Float = 0.2f,
        green: Float = 0.5f,
        blue: Float = 2f,
        rowsPerStrip: Int = 2,
        block: (File, HdrI32Frame, LinearRgbFloat16DngWriteResult) -> Boolean
    ): Boolean =
        withColorFrame(CfaPattern.RGGB, red, green, blue) { frame, directory ->
            val output = File(directory, "linear.dng")
            val result = LinearRgbFloat16DngWriter.write(
                LinearRgbFloat16DngWriteRequest(
                    hdrFrame = frame,
                    metadata = metadata(),
                    outputFile = output,
                    options = LinearRgbFloat16DngExportOptions(
                        rowsPerStrip = rowsPerStrip
                    )
                )
            )
            result.success && block(output, frame, result)
        }

    private inline fun withColorFrame(
        pattern: CfaPattern,
        red: Float,
        green: Float,
        blue: Float,
        block: (HdrI32Frame, File) -> Boolean
    ): Boolean {
        val values = FloatArray(PIXELS)
        for (index in values.indices) {
            val x = index % WIDTH
            val y = index / WIDTH
            val black = BLACK_LEVELS[(y and 1) * 2 + (x and 1)]
            val fullScale = (WHITE_LEVEL - black).toFloat()
            values[index] =
                when (colorAt(pattern, x, y)) {
                    CfaColor.RED -> red * fullScale
                    CfaColor.BLUE -> blue * fullScale
                    else -> green * fullScale
                }
        }
        return withFrame(pattern, values, block)
    }

    private inline fun <T> withFrame(
        pattern: CfaPattern,
        values: FloatArray,
        block: (HdrI32Frame, File) -> T
    ): T =
        withTempDir { directory ->
            val source = File(directory, "master.rawf32")
            val bytes =
                ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
            values.forEach(bytes::putFloat)
            source.writeBytes(bytes.array())
            block(HdrI32Frame(hdrMetadata(source, pattern), source), directory)
        }

    private fun hdrMetadata(source: File, pattern: CfaPattern): HdrI32Metadata =
        HdrI32Metadata(
            width = WIDTH,
            height = HEIGHT,
            rowStrideBytes = WIDTH * 4,
            pixelStrideBytes = 4,
            cfaPattern = pattern,
            cameraId = "0",
            iso = 100,
            inputExposureTimesNs = listOf(1_000_000_000L, 100_000_000L),
            referenceFrameIndex = 0,
            referenceExposureTimeNs = 1_000_000_000L,
            frameCount = 2,
            whiteLevel = WHITE_LEVEL,
            blackLevelPattern = BLACK_LEVELS.copyOf(),
            saturationMarginDn = 32,
            weightPolicy = HdrI32WeightPolicy.UNIFORM_VALID_RADIANCE,
            invalidSamplePolicy = HdrI32InvalidSamplePolicy.LEAST_SATURATED_FALLBACK,
            alignmentMode = HdrI32AlignmentMode.IDENTITY_ONLY,
            storagePath = source.absolutePath,
            createdAtMillis = 1L,
            appVersion = "test",
            sourceFrames = listOf(HdrI32SourceFrameMetadata(0, 1L, 1_000_000_000L, 100)),
            totalInputSamples = PIXELS.toLong() * 2L,
            validSamples = PIXELS.toLong() * 2L,
            saturatedRejectedSamples = 0,
            fallbackPixels = 0,
            noValidSamplePixels = 0,
            minimumRadiance = 0.0,
            maximumRadiance = valuesMaxForMetadata,
            meanRadiance = 1.0
        )

    private fun metadata(): LinearRgbFloat16DngMetadata =
        LinearRgbFloat16DngMetadata(
            make = "BracketLab",
            model = "Synthetic",
            uniqueCameraModel = "BracketLab-Synthetic",
            colorMatrix1 = identityMatrix(),
            colorMatrix2 = identityMatrix(),
            forwardMatrix1 = identityMatrix(),
            forwardMatrix2 = identityMatrix(),
            cameraCalibration1 = identityMatrix(),
            cameraCalibration2 = identityMatrix(),
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            asShotNeutral =
                listOf(DngRational(1, 1), DngRational(1, 1), DngRational(1, 1)),
            orientation = 1,
            software = "BracketLab test",
            dateTime = "2026:06:11 00:00:00",
            baselineExposure = DngRational(0, 1)
        )

    private fun identityMatrix(): List<DngRational> =
        listOf(
            1, 0, 0,
            0, 1, 0,
            0, 0, 1
        ).map { DngRational(it, 1) }

    private fun readFirstPixel(file: File): FloatArray {
        val inspector = TiffInspector(file)
        val offset = inspector.longs(DngTag.STRIP_OFFSETS).first()
        RandomAccessFile(file, "r").use { source ->
            source.seek(offset)
            val bytes = ByteArray(6)
            source.readFully(bytes)
            return FloatArray(3) { index ->
                val bits =
                    ((bytes[index * 2 + 1].toInt() and 0xFF) shl 8) or
                        (bytes[index * 2].toInt() and 0xFF)
                HalfFloat.toFloat(bits.toShort())
            }
        }
    }

    private fun colorAt(pattern: CfaPattern, x: Int, y: Int): CfaColor =
        com.lab.bracketlab.processing.raw.BayerUtils.colorAt(pattern, x, y)

    private fun Short.u16(): Int = toInt() and 0xFFFF

    private fun nearly(actual: Float, expected: Float, tolerance: Float = 0.001f): Boolean =
        abs(actual - expected) <= tolerance

    private fun test(
        name: String,
        block: () -> Boolean
    ): LinearRgbFloat16DngSelfTestCase =
        try {
            if (block()) {
                LinearRgbFloat16DngSelfTestCase(
                    name,
                    PhaseCorrelationSelfTestStatus.PASS,
                    "pass"
                )
            } else {
                LinearRgbFloat16DngSelfTestCase(
                    name,
                    PhaseCorrelationSelfTestStatus.FAIL,
                    "condition returned false"
                )
            }
        } catch (error: Throwable) {
            LinearRgbFloat16DngSelfTestCase(
                name,
                PhaseCorrelationSelfTestStatus.FAIL,
                "${error.javaClass.simpleName}: ${error.message}"
            )
        }

    private fun regression(
        name: String,
        block: () -> Boolean
    ): LinearRgbFloat16DngSelfTestCase = test(name, block)

    private inline fun <T> withTempDir(block: (File) -> T): T {
        val directory = File.createTempFile("bracketlab_linear_dng_", null).also {
            check(it.delete() && it.mkdir())
        }
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class TiffInspector(private val file: File) {
        private val bytes = file.readBytes()
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val entries: Map<Int, Entry>

        init {
            check(bytes[0].toInt().toChar() == 'I' && bytes[1].toInt().toChar() == 'I')
            val ifdOffset = buffer.getInt(4)
            val count = buffer.getShort(ifdOffset).toInt() and 0xFFFF
            val parsed = mutableMapOf<Int, Entry>()
            for (index in 0 until count) {
                val offset = ifdOffset + 2 + index * 12
                val tag = buffer.getShort(offset).toInt() and 0xFFFF
                val type = buffer.getShort(offset + 2).toInt() and 0xFFFF
                val valueCount = buffer.getInt(offset + 4).toLong() and 0xFFFF_FFFFL
                val typeSize =
                    when (type) {
                        1, 2 -> 1
                        3 -> 2
                        4 -> 4
                        5, 10 -> 8
                        else -> error("Unsupported TIFF type $type")
                    }
                val dataSize = (valueCount * typeSize).toInt()
                val dataOffset =
                    if (dataSize <= 4) offset + 8 else buffer.getInt(offset + 8)
                parsed[tag] = Entry(type, valueCount.toInt(), dataOffset, dataSize)
            }
            entries = parsed
        }

        fun tags(): List<Int> = entries.keys.toList()

        fun bytes(tag: Int): ByteArray {
            val entry = requireNotNull(entries[tag])
            return bytes.copyOfRange(entry.offset, entry.offset + entry.dataSize)
        }

        fun shorts(tag: Int): IntArray {
            val entry = requireNotNull(entries[tag])
            return IntArray(entry.count) {
                buffer.getShort(entry.offset + it * 2).toInt() and 0xFFFF
            }
        }

        fun longs(tag: Int): LongArray {
            val entry = requireNotNull(entries[tag])
            return LongArray(entry.count) {
                buffer.getInt(entry.offset + it * 4).toLong() and 0xFFFF_FFFFL
            }
        }

        fun rationals(tag: Int): DoubleArray {
            val entry = requireNotNull(entries[tag])
            return DoubleArray(entry.count) {
                val offset = entry.offset + it * 8
                val numerator = buffer.getInt(offset).toLong() and 0xFFFF_FFFFL
                val denominator = buffer.getInt(offset + 4).toLong() and 0xFFFF_FFFFL
                numerator.toDouble() / denominator.toDouble()
            }
        }

        fun signedRationals(tag: Int): DoubleArray {
            val entry = requireNotNull(entries[tag])
            return DoubleArray(entry.count) {
                val offset = entry.offset + it * 8
                val numerator = buffer.getInt(offset)
                val denominator = buffer.getInt(offset + 4)
                numerator.toDouble() / denominator.toDouble()
            }
        }

        fun ascii(tag: Int): String =
            bytes(tag).toString(Charsets.US_ASCII).trimEnd('\u0000')

        private data class Entry(
            val type: Int,
            val count: Int,
            val offset: Int,
            val dataSize: Int
        )
    }

    private const val WIDTH = 6
    private const val HEIGHT = 4
    private const val PIXELS = WIDTH * HEIGHT
    private const val WHITE_LEVEL = 1000
    private val BLACK_LEVELS = intArrayOf(100, 80, 60, 40)
    private const val valuesMaxForMetadata = 4000.0
}
