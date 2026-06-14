package com.lab.bracketlab.processing.hdri

import com.lab.bracketlab.processing.align.PhaseCorrelationSelfTestStatus
import com.lab.bracketlab.processing.raw.CfaPattern
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object HdrI32FitsWriterSelfTest {
    fun runAll(): List<HdrI32SelfTestCaseResult> =
        listOf(
            test("59 FITS writes primary Float32 CFA image") {
                withFixture { frame, directory ->
                    val output = File(directory, "master.fits")
                    val result = HdrI32FitsWriter.write(frame, output)
                    result.success &&
                        output.length() % HdrI32FitsWriter.FITS_BLOCK_BYTES == 0L
                }
            },
            test("60 FITS header declares BITPIX minus 32 and dimensions") {
                withFixture { frame, directory ->
                    val output = File(directory, "master.fits")
                    val result = HdrI32FitsWriter.write(frame, output)
                    val header = output.readBytes().copyOfRange(0, result.headerBytes.toInt())
                        .toString(Charsets.US_ASCII)
                    header.contains("BITPIX  =                  -32") &&
                        header.contains("NAXIS1  =                    2") &&
                        header.contains("NAXIS2  =                    2")
                }
            },
            test("61 FITS header preserves CFA and top-down row order") {
                withFixture { frame, directory ->
                    val output = File(directory, "master.fits")
                    val result = HdrI32FitsWriter.write(frame, output)
                    val header = output.readBytes().copyOfRange(0, result.headerBytes.toInt())
                        .toString(Charsets.US_ASCII)
                    header.contains("BAYERPAT") &&
                        header.contains("'GRBG'") &&
                        header.contains("ROWORDER") &&
                        header.contains("'TOP-DOWN'")
                }
            },
            test("62 FITS payload preserves Float32 values as big-endian") {
                withFixture { frame, directory ->
                    val output = File(directory, "master.fits")
                    val result = HdrI32FitsWriter.write(frame, output)
                    val bytes = output.readBytes()
                    val data = ByteBuffer.wrap(
                        bytes,
                        result.headerBytes.toInt(),
                        frame.width * frame.height * Float.SIZE_BYTES
                    ).order(ByteOrder.BIG_ENDIAN)
                    floatArrayOf(data.float, data.float, data.float, data.float)
                        .contentEquals(floatArrayOf(0f, 1f, 1000f, 500_000f))
                }
            },
            test("63 FITS source master remains unchanged") {
                withFixture { frame, directory ->
                    val before = frame.storageFile.readBytes()
                    HdrI32FitsWriter.write(frame, File(directory, "master.fits"))
                    frame.storageFile.readBytes().contentEquals(before)
                }
            },
            test("64 FITS rejects invalid source size") {
                withFixture { frame, directory ->
                    frame.storageFile.appendBytes(byteArrayOf(0))
                    val output = File(directory, "invalid.fits")
                    val result = HdrI32FitsWriter.write(frame, output)
                    !result.success &&
                        result.failureCode == HdrI32FitsFailureCode.INVALID_SOURCE_SIZE &&
                        !output.exists()
                }
            },
            test("65 storage estimator accounts for FITS master") {
                withTempDir { directory ->
                    val estimate = HdrI32StorageEstimator.estimate(
                        width = 4032,
                        height = 3024,
                        frameCount = 3,
                        outputDirectory = directory
                    )
                    estimate.fitsMasterBytes >= 4032L * 3024L * Float.SIZE_BYTES
                }
            },
            test("66 FITS is independently selectable in backend options") {
                val options = HdrI32ExportOptions(
                    writeXisf32 = false,
                    writeFits32 = true,
                    writeLinearRgbFloat16Dng = false
                )
                options.writeFits32 &&
                    !options.writeXisf32 &&
                    !options.writeLinearRgbFloat16Dng
            },
            test("67 FITS reports the actual merge weighting policy") {
                withFixture { frame, directory ->
                    val weighted =
                        frame.copy(
                            metadata =
                                frame.metadata.copy(
                                    weightPolicy =
                                        HdrI32WeightPolicy.SNR_WEIGHTED_RADIANCE
                                )
                        )
                    val output = File(directory, "weighted.fits")
                    val result = HdrI32FitsWriter.write(weighted, output)
                    val header =
                        output.readBytes()
                            .copyOfRange(0, result.headerBytes.toInt())
                            .toString(Charsets.US_ASCII)
                    header.contains("HDRMERGE") &&
                        header.contains("'SNRWEIGHT'") &&
                        header.contains("HDRCOHER") &&
                        header.contains("'BAYER2X2'")
                }
            }
        )

    private inline fun withFixture(block: (HdrI32Frame, File) -> Boolean): Boolean =
        withTempDir { directory ->
            val source = File(directory, "source.rawf32")
            val values = floatArrayOf(0f, 1f, 1000f, 500_000f)
            val bytes =
                ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
            values.forEach(bytes::putFloat)
            source.writeBytes(bytes.array())
            block(HdrI32Frame(metadata(source), source), directory)
        }

    private fun metadata(source: File): HdrI32Metadata =
        HdrI32Metadata(
            width = 2,
            height = 2,
            rowStrideBytes = 8,
            pixelStrideBytes = 4,
            cfaPattern = CfaPattern.GRBG,
            cameraId = "0",
            iso = 50,
            inputExposureTimesNs = listOf(100_000_000L, 10_000_000L),
            referenceFrameIndex = 1,
            referenceExposureTimeNs = 10_000_000L,
            frameCount = 2,
            whiteLevel = 1023,
            blackLevelPattern = intArrayOf(64, 64, 64, 64),
            saturationMarginDn = 32,
            weightPolicy = HdrI32WeightPolicy.UNIFORM_VALID_RADIANCE,
            invalidSamplePolicy = HdrI32InvalidSamplePolicy.LEAST_SATURATED_FALLBACK,
            alignmentMode = HdrI32AlignmentMode.IDENTITY_ONLY,
            storagePath = source.absolutePath,
            createdAtMillis = 1_700_000_000_000L,
            appVersion = "test",
            sourceFrames = emptyList(),
            totalInputSamples = 8,
            validSamples = 8,
            saturatedRejectedSamples = 0,
            fallbackPixels = 0,
            noValidSamplePixels = 0,
            minimumRadiance = 0.0,
            maximumRadiance = 500_000.0,
            meanRadiance = 125_250.25
        )

    private fun test(
        name: String,
        block: () -> Boolean
    ): HdrI32SelfTestCaseResult =
        try {
            if (block()) {
                HdrI32SelfTestCaseResult(name, PhaseCorrelationSelfTestStatus.PASS, "pass")
            } else {
                HdrI32SelfTestCaseResult(
                    name,
                    PhaseCorrelationSelfTestStatus.FAIL,
                    "condition returned false"
                )
            }
        } catch (error: Throwable) {
            HdrI32SelfTestCaseResult(
                name,
                PhaseCorrelationSelfTestStatus.FAIL,
                "${error.javaClass.simpleName}: ${error.message}"
            )
        }

    private inline fun <T> withTempDir(block: (File) -> T): T {
        val directory = File.createTempFile("bracketlab_fits_", null).also {
            check(it.delete() && it.mkdir())
        }
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
