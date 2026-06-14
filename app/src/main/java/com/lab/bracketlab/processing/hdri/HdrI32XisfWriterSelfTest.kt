package com.lab.bracketlab.processing.hdri

import com.lab.bracketlab.processing.align.PhaseCorrelationSelfTestStatus
import com.lab.bracketlab.processing.raw.CfaPattern
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object HdrI32XisfWriterSelfTest {
    fun runAll(): List<HdrI32SelfTestCaseResult> =
        listOf(
            test("46 XISF writes monolithic Float32 master") {
                withFixture { frame, directory ->
                    val output = File(directory, "master.xisf")
                    val result = HdrI32XisfWriter.write(frame, output)
                    result.success &&
                        output.length() ==
                        HdrI32XisfWriter.ATTACHMENT_OFFSET +
                        frame.storageFile.length()
                }
            },
            test("47 XISF header declares Float32 Gray CFA geometry") {
                withFixture { frame, directory ->
                    val output = File(directory, "master.xisf")
                    HdrI32XisfWriter.write(frame, output)
                    val header = readHeader(output)
                        header.contains("geometry=\"2:2:1\"") &&
                        header.contains("sampleFormat=\"Float32\"") &&
                        header.contains("colorSpace=\"Gray\"") &&
                        header.contains("byteOrder=\"little\"") &&
                        header.contains("ColorFilterArray pattern=\"GRBG\"")
                }
            },
            test("48 XISF attachment is aligned and correctly located") {
                withFixture { frame, directory ->
                    val output = File(directory, "master.xisf")
                    HdrI32XisfWriter.write(frame, output)
                    val header = readHeader(output)
                    header.contains(
                        "location=\"attachment:${HdrI32XisfWriter.ATTACHMENT_OFFSET}:" +
                            "${frame.storageFile.length()}\""
                    )
                }
            },
            test("49 XISF attachment remains byte-identical") {
                withFixture { frame, directory ->
                    val source = frame.storageFile.readBytes()
                    val output = File(directory, "master.xisf")
                    HdrI32XisfWriter.write(frame, output)
                    output.readBytes()
                        .copyOfRange(HdrI32XisfWriter.ATTACHMENT_OFFSET, output.length().toInt())
                        .contentEquals(source)
                }
            },
            test("50 XISF source is not modified") {
                withFixture { frame, directory ->
                    val before = frame.storageFile.readBytes()
                    HdrI32XisfWriter.write(frame, File(directory, "master.xisf"))
                    frame.storageFile.readBytes().contentEquals(before)
                }
            },
            test("51 XISF rejects invalid source size") {
                withFixture { frame, directory ->
                    frame.storageFile.appendBytes(byteArrayOf(0))
                    val output = File(directory, "invalid.xisf")
                    val result = HdrI32XisfWriter.write(frame, output)
                    !result.success &&
                        result.failureCode == HdrI32XisfFailureCode.INVALID_SOURCE_SIZE &&
                        !output.exists()
                }
            },
            test("52 XISF reports the actual merge weighting policy") {
                withFixture { frame, directory ->
                    val weighted =
                        frame.copy(
                            metadata =
                                frame.metadata.copy(
                                    weightPolicy =
                                        HdrI32WeightPolicy.SNR_WEIGHTED_RADIANCE
                                )
                        )
                    val output = File(directory, "weighted.xisf")
                    HdrI32XisfWriter.write(weighted, output)
                    val header = readHeader(output)
                    header.contains("SNR_WEIGHTED_RADIANCE") &&
                        header.contains("BAYER_2X2_SHARED")
                }
            }
        )

    private inline fun withFixture(block: (HdrI32Frame, File) -> Boolean): Boolean =
        withTempDir { directory ->
            val source = File(directory, "source.rawf32")
            val floats = floatArrayOf(0f, 1f, 1000f, 500_000f)
            val bytes =
                ByteBuffer.allocate(floats.size * Float.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
            floats.forEach(bytes::putFloat)
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

    private fun readHeader(file: File): String {
        val bytes = file.readBytes()
        check(bytes.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "XISF0100")
        val length =
            ByteBuffer.wrap(bytes, 8, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
        return bytes.copyOfRange(16, 16 + length).toString(Charsets.UTF_8)
    }

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
        val directory = File.createTempFile("bracketlab_xisf_", null).also {
            check(it.delete() && it.mkdir())
        }
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
