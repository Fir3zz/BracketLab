package com.lab.bracketlab.processing.io

import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.stack.AlignedRaw16StackResult
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

data class Raw16ScientificWriteResult(
    val success: Boolean,
    val outputPath: String? = null,
    val outputBytes: Long = 0L,
    val failureMessage: String? = null,
    val cleanupSucceeded: Boolean = true
)

object Raw16CfaXisfWriter {
    fun write(
        result: AlignedRaw16StackResult,
        referenceFrame: RawFrame,
        targetFile: File
    ): Raw16ScientificWriteResult {
        val source = result.outputRaw16FilePath?.let(::File)
            ?: return failure("Packed RAW16 file is unavailable.")
        val payloadBytes = result.width.toLong() * result.height.toLong() * 2L
        if (!valid(result, referenceFrame, source, payloadBytes)) {
            return failure("RAW16 stack metadata or source size is invalid.")
        }
        val target = collisionFree(targetFile)
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        return try {
            target.parentFile?.mkdirs()
            val header = header(result, referenceFrame, payloadBytes).toByteArray(Charsets.UTF_8)
            require(header.size <= ATTACHMENT_OFFSET - FILE_HEADER_BYTES) {
                "XISF header is too large."
            }
            BufferedOutputStream(FileOutputStream(temporary), BUFFER_BYTES).use { output ->
                output.write(SIGNATURE)
                output.writeLittleEndianInt(header.size)
                output.writeLittleEndianInt(0)
                output.write(header)
                var padding = ATTACHMENT_OFFSET - FILE_HEADER_BYTES - header.size
                val zeroes = ByteArray(4096)
                while (padding > 0) {
                    val count = minOf(padding, zeroes.size)
                    output.write(zeroes, 0, count)
                    padding -= count
                }
                FileInputStream(source).use { it.copyTo(output, BUFFER_BYTES) }
                output.flush()
            }
            check(temporary.length() == ATTACHMENT_OFFSET.toLong() + payloadBytes) {
                "Unexpected XISF output size."
            }
            check(temporary.renameTo(target)) { "Could not commit XISF output." }
            Raw16ScientificWriteResult(true, target.absolutePath, target.length())
        } catch (error: Throwable) {
            val cleaned = !temporary.exists() || temporary.delete()
            failure(error.message ?: "XISF write failed.", cleaned)
        }
    }

    private fun header(
        result: AlignedRaw16StackResult,
        reference: RawFrame,
        payloadBytes: Long
    ): String {
        val cfa = requireNotNull(reference.cfaPattern).name
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<xisf version=\"1.0\" xmlns=\"http://www.pixinsight.com/xisf\">\n")
            append(
                "  <Image geometry=\"${result.width}:${result.height}:1\" " +
                    "sampleFormat=\"UInt16\" bounds=\"0:65535\" colorSpace=\"Gray\" " +
                    "byteOrder=\"little\" location=\"attachment:$ATTACHMENT_OFFSET:$payloadBytes\">\n"
            )
            append("    <ColorFilterArray pattern=\"$cfa\" width=\"2\" height=\"2\" name=\"Bayer $cfa\"/>\n")
            append("    <FITSKeyword name=\"BAYERPAT\" value=\"'$cfa'\" comment=\"Sensor CFA pattern\"/>\n")
            append("    <FITSKeyword name=\"ISOSPEED\" value=\"${reference.iso}\" comment=\"Reference ISO\"/>\n")
            append(
                "    <FITSKeyword name=\"EXPTIME\" value=\"${reference.exposureTimeSeconds}\" " +
                    "comment=\"Reference exposure seconds\"/>\n"
            )
            append("    <FITSKeyword name=\"NFRAMES\" value=\"${result.acceptedFrameCount}\" comment=\"Contributing frames\"/>\n")
            append("  </Image>\n")
            append("  <Metadata>\n")
            append("    <Property id=\"XISF:CreatorApplication\" type=\"String\">BracketLab</Property>\n")
            append(
                "    <Property id=\"XISF:CreationTime\" type=\"String\">" +
                    iso8601(System.currentTimeMillis()) +
                    "</Property>\n"
            )
            append("  </Metadata>\n")
            append("</xisf>\n")
        }
    }

    private fun BufferedOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun failure(message: String, cleanup: Boolean = true) =
        Raw16ScientificWriteResult(false, failureMessage = message, cleanupSucceeded = cleanup)

    private val SIGNATURE = "XISF0100".toByteArray(Charsets.US_ASCII)
    private const val ATTACHMENT_OFFSET = 4096
    private const val FILE_HEADER_BYTES = 16
    private const val BUFFER_BYTES = 128 * 1024
}

object Raw16CfaFitsWriter {
    fun write(
        result: AlignedRaw16StackResult,
        referenceFrame: RawFrame,
        targetFile: File
    ): Raw16ScientificWriteResult {
        val source = result.outputRaw16FilePath?.let(::File)
            ?: return failure("Packed RAW16 file is unavailable.")
        val payloadBytes = result.width.toLong() * result.height.toLong() * 2L
        if (!valid(result, referenceFrame, source, payloadBytes)) {
            return failure("RAW16 stack metadata or source size is invalid.")
        }
        val target = collisionFree(targetFile)
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        return try {
            target.parentFile?.mkdirs()
            val header = fitsHeader(result, referenceFrame)
            BufferedOutputStream(FileOutputStream(temporary), BUFFER_BYTES).use { output ->
                output.write(header)
                BufferedInputStream(FileInputStream(source), BUFFER_BYTES).use { input ->
                    copyUnsignedLittleEndianRaw16ToFits(input, output, payloadBytes)
                }
                writePadding(output, paddingFor(payloadBytes))
                output.flush()
            }
            val expected = header.size.toLong() + payloadBytes + paddingFor(payloadBytes)
            check(temporary.length() == expected) { "Unexpected FITS output size." }
            check(temporary.renameTo(target)) { "Could not commit FITS output." }
            Raw16ScientificWriteResult(true, target.absolutePath, target.length())
        } catch (error: Throwable) {
            val cleaned = !temporary.exists() || temporary.delete()
            failure(error.message ?: "FITS write failed.", cleaned)
        }
    }

    private fun fitsHeader(result: AlignedRaw16StackResult, reference: RawFrame): ByteArray {
        val cfa = requireNotNull(reference.cfaPattern).name
        val cards =
            listOf(
                card("SIMPLE", "T", "conforms to FITS standard"),
                card("BITPIX", "16", "signed 16-bit storage with BZERO"),
                card("NAXIS", "2", "two-dimensional image"),
                card("NAXIS1", result.width.toString(), "image width"),
                card("NAXIS2", result.height.toString(), "image height"),
                card("BSCALE", "1", "physical value scale"),
                card("BZERO", "32768", "unsigned RAW16 offset"),
                stringCard("BAYERPAT", cfa, "sensor CFA pattern"),
                stringCard("COLORTYP", cfa, "sensor CFA pattern"),
                card("ISOSPEED", reference.iso.toString(), "reference ISO"),
                card("EXPTIME", reference.exposureTimeSeconds.toString(), "reference exposure seconds"),
                card("NFRAMES", result.acceptedFrameCount.toString(), "contributing frames"),
                stringCard("IMAGETYP", "RAW16 CFA STACK", "non-demosaiced Bayer mosaic"),
                stringCard("CREATOR", "BracketLab", "writer application"),
                "END".padEnd(CARD_BYTES)
            )
        val raw = cards.joinToString("").toByteArray(Charsets.US_ASCII)
        return ByteArray(roundBlock(raw.size.toLong()).toInt()) { ' '.code.toByte() }
            .also { raw.copyInto(it) }
    }

    private fun copyUnsignedLittleEndianRaw16ToFits(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        expectedBytes: Long
    ) {
        val buffer = ByteArray(BUFFER_BYTES - (BUFFER_BYTES % 2))
        var total = 0L
        while (total < expectedBytes) {
            val requested = minOf(buffer.size.toLong(), expectedBytes - total).toInt()
            var read = 0
            while (read < requested) {
                val count = input.read(buffer, read, requested - read)
                if (count < 0) throw IOException("Unexpected end of RAW16 source.")
                read += count
            }
            for (index in 0 until requested step 2) {
                val unsigned =
                    (buffer[index].toInt() and 0xFF) or
                        ((buffer[index + 1].toInt() and 0xFF) shl 8)
                val signed = unsigned - 32768
                output.write((signed ushr 8) and 0xFF)
                output.write(signed and 0xFF)
            }
            total += requested
        }
    }

    private fun card(keyword: String, value: String, comment: String): String =
        (keyword.padEnd(8).take(8) + "= " + value.padStart(20) + " / " + comment)
            .padEnd(CARD_BYTES)
            .take(CARD_BYTES)

    private fun stringCard(keyword: String, value: String, comment: String): String =
        card(keyword, "'${value.replace("'", "''")}'", comment)

    private fun paddingFor(bytes: Long): Long = roundBlock(bytes) - bytes

    private fun roundBlock(bytes: Long): Long =
        ((bytes + FITS_BLOCK_BYTES - 1L) / FITS_BLOCK_BYTES) * FITS_BLOCK_BYTES

    private fun writePadding(output: BufferedOutputStream, count: Long) {
        var remaining = count
        val zeroes = ByteArray(2880)
        while (remaining > 0L) {
            val size = minOf(remaining, zeroes.size.toLong()).toInt()
            output.write(zeroes, 0, size)
            remaining -= size
        }
    }

    private fun failure(message: String, cleanup: Boolean = true) =
        Raw16ScientificWriteResult(false, failureMessage = message, cleanupSucceeded = cleanup)

    private const val FITS_BLOCK_BYTES = 2880L
    private const val CARD_BYTES = 80
    private const val BUFFER_BYTES = 128 * 1024
}

private fun valid(
    result: AlignedRaw16StackResult,
    referenceFrame: RawFrame,
    source: File,
    payloadBytes: Long
): Boolean =
    result.success &&
        result.width == referenceFrame.width &&
        result.height == referenceFrame.height &&
        result.outputRowStride == result.width * 2 &&
        result.outputPixelStride == 2 &&
        referenceFrame.cfaPattern != null &&
        source.isFile &&
        source.length() == payloadBytes

private fun collisionFree(requested: File): File {
    requested.parentFile?.mkdirs()
    if (!requested.exists()) return requested
    val base = requested.nameWithoutExtension
    val extension = requested.extension
    for (index in 1..999) {
        val candidate =
            File(
                requested.parentFile,
                "${base}_${index.toString().padStart(3, '0')}.$extension"
            )
        if (!candidate.exists()) return candidate
    }
    error("Could not resolve scientific output filename collision.")
}

private fun iso8601(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timestamp))
