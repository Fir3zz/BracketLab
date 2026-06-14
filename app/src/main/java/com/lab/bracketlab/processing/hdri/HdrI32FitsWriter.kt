package com.lab.bracketlab.processing.hdri

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class HdrI32FitsFailureCode {
    INVALID_FRAME,
    INVALID_SOURCE_SIZE,
    OUTPUT_IO_FAILURE,
    OUTPUT_COMMIT_FAILED,
    PARTIAL_OUTPUT_CLEANUP_FAILED
}

data class HdrI32FitsWriteResult(
    val success: Boolean,
    val outputPath: String? = null,
    val outputBytes: Long = 0L,
    val headerBytes: Long = 0L,
    val failureCode: HdrI32FitsFailureCode? = null,
    val failureMessage: String? = null,
    val exceptionClass: String? = null,
    val cleanupSucceeded: Boolean = true,
    val durationMs: Long = 0L
)

/**
 * Writes the linear radiance CFA master as a primary 32-bit floating-point FITS image.
 *
 * FITS stores numerical samples in big-endian order, so the writer byte-swaps
 * the little-endian rawf32 source while streaming it. No image sample is scaled.
 */
object HdrI32FitsWriter {
    fun write(
        frame: HdrI32Frame,
        targetFile: File,
        overwrite: Boolean = false
    ): HdrI32FitsWriteResult {
        val startedNs = System.nanoTime()
        val payloadBytes =
            frame.width.toLong() * frame.height.toLong() * Float.SIZE_BYTES.toLong()
        if (
            frame.width <= 0 ||
            frame.height <= 0 ||
            frame.metadata.cfaPattern.name.length != 4
        ) {
            return failure(
                HdrI32FitsFailureCode.INVALID_FRAME,
                "HDR frame metadata is invalid for FITS CFA output.",
                startedNs
            )
        }
        if (!frame.storageFile.isFile || frame.storageFile.length() != payloadBytes) {
            return failure(
                HdrI32FitsFailureCode.INVALID_SOURCE_SIZE,
                "Float32 source size=${frame.storageFile.length()}, expected=$payloadBytes.",
                startedNs
            )
        }

        val resolvedTarget = resolveCollision(targetFile, overwrite)
        val parent = resolvedTarget.parentFile
            ?: return failure(
                HdrI32FitsFailureCode.OUTPUT_IO_FAILURE,
                "FITS output requires a parent directory.",
                startedNs
            )
        parent.mkdirs()
        val temporary = File(parent, ".${resolvedTarget.name}.${System.nanoTime()}.tmp")
        var committed = false
        return try {
            val header = buildHeader(frame)
            BufferedOutputStream(FileOutputStream(temporary, false), STREAM_BUFFER_BYTES).use { output ->
                output.write(header)
                BufferedInputStream(
                    FileInputStream(frame.storageFile),
                    STREAM_BUFFER_BYTES
                ).use { input ->
                    copyLittleEndianFloat32AsFitsBigEndian(input, output, payloadBytes)
                }
                writeZeroPadding(output, paddingFor(payloadBytes))
                output.flush()
            }

            val expectedBytes = header.size.toLong() + payloadBytes + paddingFor(payloadBytes)
            if (temporary.length() != expectedBytes) {
                throw IOException(
                    "FITS size=${temporary.length()}, expected=$expectedBytes."
                )
            }
            if (resolvedTarget.exists() && overwrite && !resolvedTarget.delete()) {
                throw IOException("Could not replace existing FITS output.")
            }
            if (!temporary.renameTo(resolvedTarget)) {
                throw IOException("Could not commit FITS output.")
            }
            committed = true
            HdrI32FitsWriteResult(
                success = true,
                outputPath = resolvedTarget.absolutePath,
                outputBytes = resolvedTarget.length(),
                headerBytes = header.size.toLong(),
                durationMs = elapsedMs(startedNs)
            )
        } catch (error: Throwable) {
            val cleanup = !temporary.exists() || temporary.delete()
            HdrI32FitsWriteResult(
                success = false,
                failureCode =
                    if (!cleanup) {
                        HdrI32FitsFailureCode.PARTIAL_OUTPUT_CLEANUP_FAILED
                    } else if (committed) {
                        HdrI32FitsFailureCode.OUTPUT_COMMIT_FAILED
                    } else {
                        HdrI32FitsFailureCode.OUTPUT_IO_FAILURE
                    },
                failureMessage = error.message,
                exceptionClass = error.javaClass.simpleName,
                cleanupSucceeded = cleanup,
                durationMs = elapsedMs(startedNs)
            )
        }
    }

    internal fun buildHeader(frame: HdrI32Frame): ByteArray {
        val metadata = frame.metadata
        val cfa = metadata.cfaPattern.name
        val exposureSeconds = metadata.referenceExposureTimeNs / 1_000_000_000.0
        val cards = mutableListOf(
            logicalCard("SIMPLE", true, "conforms to FITS standard"),
            integerCard("BITPIX", -32, "32-bit IEEE floating point"),
            integerCard("NAXIS", 2, "two-dimensional image"),
            integerCard("NAXIS1", frame.width.toLong(), "image width"),
            integerCard("NAXIS2", frame.height.toLong(), "image height"),
            logicalCard("EXTEND", true, "extensions may be present"),
            stringCard("BUNIT", "ADU/S", "linear signal radiance"),
            stringCard("BAYERPAT", cfa, "sensor CFA pattern"),
            stringCard("COLORTYP", cfa, "sensor CFA pattern"),
            integerCard("XBAYROFF", 0, "CFA horizontal offset"),
            integerCard("YBAYROFF", 0, "CFA vertical offset"),
            stringCard("ROWORDER", "TOP-DOWN", "first data row is top image row"),
            integerCard("ISOSPEED", metadata.iso.toLong(), "reference ISO"),
            floatingCard("EXPTIME", exposureSeconds, "reference exposure seconds"),
            integerCard("NFRAMES", metadata.frameCount.toLong(), "merged bracket frames"),
            integerCard(
                "REFFRAME",
                metadata.referenceFrameIndex.toLong(),
                "reference frame index"
            ),
            integerCard("BLACKLVL", 0, "radiance data is above black baseline"),
            stringCard(
                "HDRMERGE",
                when (metadata.weightPolicy) {
                    HdrI32WeightPolicy.UNIFORM_VALID_RADIANCE -> "UNIFORM"
                    HdrI32WeightPolicy.SNR_WEIGHTED_RADIANCE -> "SNRWEIGHT"
                },
                "radiance merge weighting policy"
            ),
            stringCard(
                "HDRCOHER",
                when (metadata.highlightCoherencePolicy) {
                    HdrI32HighlightCoherencePolicy.PER_SAMPLE -> "SAMPLE"
                    HdrI32HighlightCoherencePolicy.BAYER_2X2_SHARED -> "BAYER2X2"
                },
                "highlight exposure coherence"
            ),
            stringCard("IMAGETYP", "HDR CFA MASTER", "linear non-demosaiced master"),
            stringCard("CREATOR", creator(metadata), "writer application"),
            stringCard("DATE", iso8601(metadata.createdAtMillis), "file creation UTC"),
            endCard()
        )
        val rawHeader = cards.joinToString(separator = "").toByteArray(Charsets.US_ASCII)
        val paddedSize = roundUpToBlock(rawHeader.size.toLong()).toInt()
        return ByteArray(paddedSize) { ' '.code.toByte() }.also {
            rawHeader.copyInto(it)
        }
    }

    private fun copyLittleEndianFloat32AsFitsBigEndian(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        expectedBytes: Long
    ) {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var total = 0L
        while (total < expectedBytes) {
            val requested = minOf(buffer.size.toLong(), expectedBytes - total).toInt()
            var offset = 0
            while (offset < requested) {
                val count = input.read(buffer, offset, requested - offset)
                if (count < 0) {
                    throw IOException("Unexpected end of Float32 source at byte $total.")
                }
                offset += count
            }
            if (requested % Float.SIZE_BYTES != 0) {
                throw IOException("Float32 source chunk is not sample-aligned.")
            }
            for (index in 0 until requested step Float.SIZE_BYTES) {
                val first = buffer[index]
                val second = buffer[index + 1]
                buffer[index] = buffer[index + 3]
                buffer[index + 1] = buffer[index + 2]
                buffer[index + 2] = second
                buffer[index + 3] = first
            }
            output.write(buffer, 0, requested)
            total += requested.toLong()
        }
        if (input.read() != -1) {
            throw IOException("Float32 source contains trailing bytes.")
        }
    }

    private fun logicalCard(keyword: String, value: Boolean, comment: String): String =
        valueCard(keyword, if (value) "T" else "F", comment)

    private fun integerCard(keyword: String, value: Long, comment: String): String =
        valueCard(keyword, value.toString(), comment)

    private fun floatingCard(keyword: String, value: Double, comment: String): String =
        valueCard(keyword, java.lang.Double.toString(value), comment)

    private fun stringCard(keyword: String, value: String, comment: String): String =
        valueCard(keyword, "'${value.replace("'", "''")}'", comment)

    private fun valueCard(keyword: String, value: String, comment: String): String {
        val prefix = keyword.padEnd(8).take(8) + "= "
        val body = prefix + value.padStart(20) + " / " + comment
        return body.padEnd(CARD_BYTES).take(CARD_BYTES)
    }

    private fun endCard(): String = "END".padEnd(CARD_BYTES)

    private fun creator(metadata: HdrI32Metadata): String =
        metadata.appVersion?.takeIf(String::isNotBlank)?.let { "BracketLab $it" }
            ?: "BracketLab"

    private fun iso8601(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))

    private fun paddingFor(payloadBytes: Long): Long =
        roundUpToBlock(payloadBytes) - payloadBytes

    private fun roundUpToBlock(value: Long): Long =
        ((value + FITS_BLOCK_BYTES - 1L) / FITS_BLOCK_BYTES) * FITS_BLOCK_BYTES

    private fun writeZeroPadding(output: BufferedOutputStream, count: Long) {
        var remaining = count
        val zeroes = ByteArray(minOf(STREAM_BUFFER_BYTES.toLong(), remaining).toInt())
        while (remaining > 0L) {
            val size = minOf(remaining, zeroes.size.toLong()).toInt()
            output.write(zeroes, 0, size)
            remaining -= size.toLong()
        }
    }

    private fun resolveCollision(file: File, overwrite: Boolean): File {
        if (overwrite || !file.exists()) return file
        val parent = requireNotNull(file.parentFile)
        val base = file.nameWithoutExtension
        val extension = file.extension.ifBlank { "fits" }
        for (index in 1..999) {
            val candidate =
                File(parent, "${base}_${index.toString().padStart(3, '0')}.$extension")
            if (!candidate.exists()) return candidate
        }
        throw IOException("Could not find a collision-free FITS filename.")
    }

    private fun failure(
        code: HdrI32FitsFailureCode,
        message: String,
        startedNs: Long
    ): HdrI32FitsWriteResult =
        HdrI32FitsWriteResult(
            success = false,
            failureCode = code,
            failureMessage = message,
            durationMs = elapsedMs(startedNs)
        )

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L

    const val DEFAULT_FILENAME = "BracketLab_HDR_Master_CFA_Float32.fits"
    internal const val FITS_BLOCK_BYTES = 2880L
    internal const val CARD_BYTES = 80
    private const val STREAM_BUFFER_BYTES = 64 * 1024
}
