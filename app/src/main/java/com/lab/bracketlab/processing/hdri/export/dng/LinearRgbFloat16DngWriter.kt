package com.lab.bracketlab.processing.hdri.export.dng

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object LinearRgbFloat16DngWriter {
    fun write(request: LinearRgbFloat16DngWriteRequest): LinearRgbFloat16DngWriteResult {
        val startedNs = System.nanoTime()
        val frame = request.hdrFrame
        val expectedSourceBytes =
            frame.width.toLong() * frame.height.toLong() * Float.SIZE_BYTES.toLong()
        if (
            frame.width <= 0 ||
            frame.height <= 0 ||
            !frame.storageFile.isFile ||
            frame.storageFile.length() != expectedSourceBytes
        ) {
            return failure(
                LinearRgbFloat16DngFailureCode.INVALID_HDR_FRAME,
                "HDR Float32 CFA source is invalid.",
                request
            )
        }
        if (
            request.metadata.colorMatrix1.size != 9 ||
            request.metadata.asShotNeutral.size != 3
        ) {
            return failure(
                LinearRgbFloat16DngFailureCode.INVALID_COLOR_METADATA,
                "ColorMatrix1 and AsShotNeutral are required.",
                request
            )
        }

        val rowsPerStrip = request.options.rowsPerStrip.coerceAtMost(frame.height)
        var minimumRgb = Double.POSITIVE_INFINITY
        var maximumRgb = Double.NEGATIVE_INFINITY
        var rgbSum = 0.0
        var rgbCount = 0L
        var inputInvalid = 0L
        var inputNegative = 0L
        var valuesAboveOneBeforeScale = 0L
        val scanFailure =
            runCatching {
                HdrCfaFloat32Demosaicer(
                    frame,
                    request.options.referenceExposureTimeNs
                        ?: frame.metadata.referenceExposureTimeNs
                ).use { demosaicer ->
                    val rgbRow = FloatArray(frame.width * CHANNELS)
                    for (stripStartY in 0 until frame.height step rowsPerStrip) {
                        val rowCount = minOf(rowsPerStrip, frame.height - stripStartY)
                        val tile = demosaicer.readTile(stripStartY, rowCount)
                        inputInvalid += tile.invalidInputCount
                        inputNegative += tile.negativeClampCount
                        for (y in stripStartY until stripStartY + rowCount) {
                            demosaicer.demosaicRow(tile, y, rgbRow)
                            for (value in rgbRow) {
                                val safe =
                                    value.takeIf { it.isFinite() && it >= 0f } ?: 0f
                                val doubleValue = safe.toDouble()
                                minimumRgb = minOf(minimumRgb, doubleValue)
                                maximumRgb = maxOf(maximumRgb, doubleValue)
                                rgbSum += doubleValue
                                rgbCount++
                                if (doubleValue > 1.0) valuesAboveOneBeforeScale++
                            }
                        }
                    }
                }
            }.exceptionOrNull()
        if (scanFailure != null) {
            return failure(
                LinearRgbFloat16DngFailureCode.OUTPUT_IO_FAILURE,
                "Could not scan Linear RGB maximum: ${scanFailure.message}",
                request
            )
        }
        val normalization =
            LinearRgbFloat16Normalization.fromMaximum(
                maximumRgb.takeIf(Double::isFinite) ?: 0.0
            )
        val stripCount = (frame.height + rowsPerStrip - 1) / rowsPerStrip
        val emptyOffsets = LongArray(stripCount)
        val provisionalCounts =
            LinearRgbFloat16StripLayout.create(
                frame.width,
                frame.height,
                rowsPerStrip,
                0L
            ).byteCounts
        val placeholderEntries =
            buildEntries(
                request,
                rowsPerStrip,
                emptyOffsets,
                provisionalCounts,
                normalization.baselineExposureEv
            )
        val placeholder =
            runCatching { TiffIfdBuilder.build(placeholderEntries) }
                .getOrElse {
                    return failure(
                        LinearRgbFloat16DngFailureCode.INVALID_TIFF_TAGS,
                        it.message ?: "Invalid TIFF/DNG tags.",
                        request
                    )
                }
        val stripLayout =
            LinearRgbFloat16StripLayout.create(
                frame.width,
                frame.height,
                rowsPerStrip,
                placeholder.imageDataOffset
            )
        if (!stripLayout.isValid()) {
            return failure(
                LinearRgbFloat16DngFailureCode.INVALID_TIFF_TAGS,
                "RGB Float16 strip table is inconsistent.",
                request
            )
        }
        if (stripLayout.expectedFileBytes > 0xFFFF_FFFFL) {
            return failure(
                LinearRgbFloat16DngFailureCode.FILE_TOO_LARGE_FOR_CLASSIC_TIFF,
                "Linear RGB DNG exceeds classic TIFF's 4 GiB limit.",
                request
            )
        }
        val header =
            TiffIfdBuilder.build(
                buildEntries(
                    request,
                    rowsPerStrip,
                    stripLayout.offsets,
                    stripLayout.byteCounts,
                    normalization.baselineExposureEv
                )
            )
        check(header.imageDataOffset == placeholder.imageDataOffset)

        val target = resolveCollision(request.outputFile, request.options.overwrite)
        val parent = target.parentFile
            ?: return failure(
                LinearRgbFloat16DngFailureCode.OUTPUT_IO_FAILURE,
                "DNG output requires a parent directory.",
                request
            )
        parent.mkdirs()
        val temporary = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        var committed = false
        val halfDiagnostics = HalfFloatDiagnostics()
        return try {
            BufferedOutputStream(FileOutputStream(temporary, false), STREAM_BUFFER_BYTES).use { output ->
                output.write(header.bytes)
                HdrCfaFloat32Demosaicer(
                    frame,
                    request.options.referenceExposureTimeNs
                        ?: frame.metadata.referenceExposureTimeNs
                ).use { demosaicer ->
                    val rgbRow = FloatArray(frame.width * CHANNELS)
                    val halfRow = ByteArray(frame.width * CHANNELS * HALF_BYTES)
                    for (stripIndex in 0 until stripCount) {
                        val startY = stripIndex * rowsPerStrip
                        val rowCount = minOf(rowsPerStrip, frame.height - startY)
                        val tile = demosaicer.readTile(startY, rowCount)
                        for (y in startY until startY + rowCount) {
                            demosaicer.demosaicRow(tile, y, rgbRow)
                            var byteIndex = 0
                            for (value in rgbRow) {
                                val safe =
                                    value.takeIf { it.isFinite() && it >= 0f } ?: 0f
                                val scaled =
                                    (safe.toDouble() * normalization.globalScale).toFloat()
                                val bits = HalfFloat.fromFloat(scaled, halfDiagnostics)
                                halfRow[byteIndex++] = (bits.toInt() and 0xFF).toByte()
                                halfRow[byteIndex++] =
                                    ((bits.toInt() ushr 8) and 0xFF).toByte()
                            }
                            output.write(halfRow)
                        }
                    }
                }
                output.flush()
            }
            if (
                temporary.length() != stripLayout.expectedFileBytes ||
                !stripLayout.isValid(temporary.length())
            ) {
                throw IOException(
                    "DNG size=${temporary.length()}, " +
                        "expected=${stripLayout.expectedFileBytes}; strip table invalid."
                )
            }
            if (target.exists() && request.options.overwrite && !target.delete()) {
                throw IOException("Could not replace existing Linear RGB DNG.")
            }
            if (!temporary.renameTo(target)) {
                throw IOException("Could not commit Linear RGB DNG.")
            }
            committed = true
            LinearRgbFloat16DngWriteResult(
                success = true,
                outputPath = target.absolutePath,
                outputBytes = target.length(),
                width = frame.width,
                height = frame.height,
                rowsPerStrip = rowsPerStrip,
                stripCount = stripCount,
                diagnostics = LinearRgbFloat16DngDiagnostics(
                    pixelCount = frame.width.toLong() * frame.height.toLong(),
                    channelSampleCount = rgbCount,
                    valuesAboveOne = halfDiagnostics.valuesAboveOne,
                    maximumClamps = halfDiagnostics.maximumClamps,
                    invalidValueReplacements = halfDiagnostics.invalidReplacements,
                    negativeClamps = halfDiagnostics.negativeClamps,
                    minimumRgb = minimumRgb.takeIf(Double::isFinite),
                    maximumRgb = maximumRgb.takeIf(Double::isFinite),
                    meanRgb = if (rgbCount > 0L) rgbSum / rgbCount.toDouble() else null,
                    inputInvalidValues = inputInvalid,
                    inputNegativeClamps = inputNegative,
                    processingDurationMs = elapsedMs(startedNs),
                    maxRgbBeforeScale = normalization.maxRgbBeforeScale,
                    globalScaleApplied = normalization.globalScale,
                    baselineExposureWritten = normalization.baselineExposureEv,
                    valuesAboveOneBeforeScale = valuesAboveOneBeforeScale,
                    valuesAboveOneAfterScale = halfDiagnostics.valuesAboveOne,
                    expectedImageBytes = stripLayout.expectedImageBytes,
                    sumStripByteCounts = stripLayout.sumStripByteCounts,
                    stripTableValid = stripLayout.isValid(target.length())
                ),
                warnings = request.metadata.warnings
            )
        } catch (error: Throwable) {
            val cleanup = !temporary.exists() || temporary.delete()
            LinearRgbFloat16DngWriteResult(
                success = false,
                width = frame.width,
                height = frame.height,
                rowsPerStrip = rowsPerStrip,
                stripCount = stripCount,
                warnings = request.metadata.warnings,
                failureCode =
                    if (!cleanup) {
                        LinearRgbFloat16DngFailureCode.PARTIAL_OUTPUT_CLEANUP_FAILED
                    } else if (committed) {
                        LinearRgbFloat16DngFailureCode.OUTPUT_COMMIT_FAILED
                    } else {
                        LinearRgbFloat16DngFailureCode.OUTPUT_IO_FAILURE
                    },
                failureMessage = error.message,
                exceptionClass = error.javaClass.simpleName,
                cleanupSucceeded = cleanup
            )
        }
    }

    internal fun buildEntries(
        request: LinearRgbFloat16DngWriteRequest,
        rowsPerStrip: Int,
        stripOffsets: LongArray,
        stripByteCounts: LongArray,
        baselineExposureEv: Double
    ): List<TiffIfdEntry> {
        require(stripOffsets.size == stripByteCounts.size)
        val frame = request.hdrFrame
        val metadata = request.metadata
        val entries = mutableListOf(
            entryLong(
                DngTag.NEW_SUBFILE_TYPE,
                DngTag.SUBFILE_TYPE_MAIN_IMAGE.toLong()
            ),
            entryLong(DngTag.IMAGE_WIDTH, frame.width.toLong()),
            entryLong(DngTag.IMAGE_LENGTH, frame.height.toLong()),
            entryShorts(DngTag.BITS_PER_SAMPLE, intArrayOf(16, 16, 16)),
            entryShort(DngTag.COMPRESSION, DngTag.COMPRESSION_NONE),
            entryShort(
                DngTag.PHOTOMETRIC_INTERPRETATION,
                DngTag.PHOTOMETRIC_LINEAR_RAW
            ),
            entryAscii(
                DngTag.IMAGE_DESCRIPTION,
                "BracketLab HDR Linear RGB Float16 DNG; no tone mapping or gamma; " +
                    "global Adobe normalization compensated by BaselineExposure."
            ),
            entryAscii(DngTag.MAKE, metadata.make),
            entryAscii(DngTag.MODEL, metadata.model),
            entryLongs(DngTag.STRIP_OFFSETS, stripOffsets),
            entryShort(DngTag.ORIENTATION, metadata.orientation),
            entryShort(DngTag.SAMPLES_PER_PIXEL, CHANNELS),
            entryLong(DngTag.ROWS_PER_STRIP, rowsPerStrip.toLong()),
            entryLongs(DngTag.STRIP_BYTE_COUNTS, stripByteCounts),
            entryShort(DngTag.PLANAR_CONFIGURATION, DngTag.PLANAR_CHUNKY),
            entryAscii(DngTag.SOFTWARE, metadata.software),
            entryAscii(DngTag.DATE_TIME, metadata.dateTime),
            entryShorts(
                DngTag.SAMPLE_FORMAT,
                intArrayOf(
                    DngTag.SAMPLE_FORMAT_IEEE_FLOAT,
                    DngTag.SAMPLE_FORMAT_IEEE_FLOAT,
                    DngTag.SAMPLE_FORMAT_IEEE_FLOAT
                )
            ),
            entryBytes(DngTag.DNG_VERSION, intArrayOf(1, 4, 0, 0)),
            entryBytes(DngTag.DNG_BACKWARD_VERSION, intArrayOf(1, 4, 0, 0)),
            entryAscii(DngTag.UNIQUE_CAMERA_MODEL, metadata.uniqueCameraModel),
            entryShorts(DngTag.BLACK_LEVEL_REPEAT_DIM, intArrayOf(1, 1)),
            entryRationals(
                DngTag.BLACK_LEVEL,
                listOf(DngRational(0, 1), DngRational(0, 1), DngRational(0, 1))
            ),
            entryLongs(DngTag.WHITE_LEVEL, longArrayOf(1L, 1L, 1L)),
            entryRationals(
                DngTag.DEFAULT_SCALE,
                listOf(DngRational(1, 1), DngRational(1, 1))
            ),
            entryLongs(DngTag.DEFAULT_CROP_ORIGIN, longArrayOf(0L, 0L)),
            entryLongs(
                DngTag.DEFAULT_CROP_SIZE,
                longArrayOf(frame.width.toLong(), frame.height.toLong())
            ),
            entrySignedRationals(DngTag.COLOR_MATRIX_1, metadata.colorMatrix1),
            entryRationals(DngTag.AS_SHOT_NEUTRAL, metadata.asShotNeutral),
            entrySignedRationals(
                DngTag.BASELINE_EXPOSURE,
                listOf(decimalToDngRational(baselineExposureEv))
            ),
            entryShort(
                DngTag.CALIBRATION_ILLUMINANT_1,
                metadata.calibrationIlluminant1
            ),
            entryLong(DngTag.DEFAULT_BLACK_RENDER, 1L)
        )
        metadata.colorMatrix2?.let {
            entries += entrySignedRationals(DngTag.COLOR_MATRIX_2, it)
        }
        metadata.cameraCalibration1?.let {
            entries += entrySignedRationals(DngTag.CAMERA_CALIBRATION_1, it)
        }
        metadata.cameraCalibration2?.let {
            entries += entrySignedRationals(DngTag.CAMERA_CALIBRATION_2, it)
        }
        metadata.forwardMatrix1?.let {
            entries += entrySignedRationals(DngTag.FORWARD_MATRIX_1, it)
        }
        metadata.forwardMatrix2?.let {
            entries += entrySignedRationals(DngTag.FORWARD_MATRIX_2, it)
        }
        metadata.calibrationIlluminant2?.let {
            entries += entryShort(DngTag.CALIBRATION_ILLUMINANT_2, it)
        }
        return entries
    }

    private fun entryBytes(tag: Int, values: IntArray): TiffIfdEntry =
        TiffIfdEntry(tag, TiffFieldType.BYTE, values.size.toLong(), TiffIfdBuilder.bytes(*values))

    private fun entryAscii(tag: Int, value: String): TiffIfdEntry {
        val data = TiffIfdBuilder.ascii(value)
        return TiffIfdEntry(tag, TiffFieldType.ASCII, data.size.toLong(), data)
    }

    private fun entryShort(tag: Int, value: Int): TiffIfdEntry =
        entryShorts(tag, intArrayOf(value))

    private fun entryShorts(tag: Int, values: IntArray): TiffIfdEntry =
        TiffIfdEntry(
            tag,
            TiffFieldType.SHORT,
            values.size.toLong(),
            TiffIfdBuilder.shorts(*values)
        )

    private fun entryLong(tag: Int, value: Long): TiffIfdEntry =
        entryLongs(tag, longArrayOf(value))

    private fun entryLongs(tag: Int, values: LongArray): TiffIfdEntry =
        TiffIfdEntry(
            tag,
            TiffFieldType.LONG,
            values.size.toLong(),
            TiffIfdBuilder.longs(*values)
        )

    private fun entryRationals(tag: Int, values: List<DngRational>): TiffIfdEntry =
        TiffIfdEntry(
            tag,
            TiffFieldType.RATIONAL,
            values.size.toLong(),
            TiffIfdBuilder.rationals(values)
        )

    private fun entrySignedRationals(tag: Int, values: List<DngRational>): TiffIfdEntry =
        TiffIfdEntry(
            tag,
            TiffFieldType.SRATIONAL,
            values.size.toLong(),
            TiffIfdBuilder.signedRationals(values)
        )

    private fun resolveCollision(file: File, overwrite: Boolean): File {
        if (overwrite || !file.exists()) return file
        val parent = requireNotNull(file.parentFile)
        val base = file.nameWithoutExtension
        val extension = file.extension.ifBlank { "dng" }
        for (index in 1..999) {
            val candidate =
                File(parent, "${base}_${index.toString().padStart(3, '0')}.$extension")
            if (!candidate.exists()) return candidate
        }
        throw IOException("Could not find a collision-free Linear RGB DNG filename.")
    }

    private fun failure(
        code: LinearRgbFloat16DngFailureCode,
        message: String,
        request: LinearRgbFloat16DngWriteRequest
    ): LinearRgbFloat16DngWriteResult =
        LinearRgbFloat16DngWriteResult(
            success = false,
            width = request.hdrFrame.width,
            height = request.hdrFrame.height,
            rowsPerStrip = request.options.rowsPerStrip,
            warnings = request.metadata.warnings,
            failureCode = code,
            failureMessage = message
        )

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L

    const val DEFAULT_FILENAME = "BracketLab_HDR_LinearRGB_Float16.dng"
    private const val CHANNELS = 3
    private const val HALF_BYTES = 2
    private const val STREAM_BUFFER_BYTES = 128 * 1024
}
