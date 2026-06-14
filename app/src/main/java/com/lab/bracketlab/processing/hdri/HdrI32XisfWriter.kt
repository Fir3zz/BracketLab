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

enum class HdrI32XisfFailureCode {
    INVALID_FRAME,
    INVALID_SOURCE_SIZE,
    HEADER_TOO_LARGE,
    OUTPUT_IO_FAILURE,
    OUTPUT_COMMIT_FAILED,
    PARTIAL_OUTPUT_CLEANUP_FAILED
}

data class HdrI32XisfWriteResult(
    val success: Boolean,
    val outputPath: String? = null,
    val outputBytes: Long = 0L,
    val attachmentOffset: Long = 0L,
    val failureCode: HdrI32XisfFailureCode? = null,
    val failureMessage: String? = null,
    val exceptionClass: String? = null,
    val cleanupSucceeded: Boolean = true,
    val durationMs: Long = 0L
)

/**
 * Streams the Float32 CFA master into a monolithic XISF attachment.
 *
 * The image remains one-channel linear radiance. ColorFilterArray records the
 * sensor mosaic so PixInsight can perform the eventual debayer operation.
 */
object HdrI32XisfWriter {
    fun write(
        frame: HdrI32Frame,
        targetFile: File,
        overwrite: Boolean = false
    ): HdrI32XisfWriteResult {
        val startedNs = System.nanoTime()
        val expectedPayloadBytes =
            frame.width.toLong() * frame.height.toLong() * Float.SIZE_BYTES.toLong()
        if (
            frame.width <= 0 ||
            frame.height <= 0 ||
            frame.metadata.maximumRadiance?.let { !it.isFinite() || it < 0.0 } == true
        ) {
            return failure(
                HdrI32XisfFailureCode.INVALID_FRAME,
                "HDR frame metadata is invalid.",
                startedNs
            )
        }
        if (!frame.storageFile.isFile || frame.storageFile.length() != expectedPayloadBytes) {
            return failure(
                HdrI32XisfFailureCode.INVALID_SOURCE_SIZE,
                "Float32 source size=${frame.storageFile.length()}, expected=$expectedPayloadBytes.",
                startedNs
            )
        }

        val resolvedTarget = resolveCollision(targetFile, overwrite)
        val parent = resolvedTarget.parentFile
            ?: return failure(
                HdrI32XisfFailureCode.OUTPUT_IO_FAILURE,
                "XISF output requires a parent directory.",
                startedNs
            )
        parent.mkdirs()
        val temporary = File(parent, ".${resolvedTarget.name}.${System.nanoTime()}.tmp")
        var committed = false
        return try {
            val header = buildHeader(frame, expectedPayloadBytes)
            val headerBytes = header.toByteArray(Charsets.UTF_8)
            if (headerBytes.size > ATTACHMENT_OFFSET - FILE_HEADER_BYTES) {
                return failure(
                    HdrI32XisfFailureCode.HEADER_TOO_LARGE,
                    "XISF XML header is ${headerBytes.size} bytes.",
                    startedNs
                )
            }

            BufferedOutputStream(FileOutputStream(temporary, false), STREAM_BUFFER_BYTES).use { output ->
                output.write(SIGNATURE)
                output.writeLittleEndianInt(headerBytes.size)
                output.writeLittleEndianInt(0)
                output.write(headerBytes)
                var padding = ATTACHMENT_OFFSET - FILE_HEADER_BYTES - headerBytes.size
                val zeroes = ByteArray(minOf(padding, 4096))
                while (padding > 0) {
                    val count = minOf(padding, zeroes.size)
                    output.write(zeroes, 0, count)
                    padding -= count
                }
                BufferedInputStream(
                    FileInputStream(frame.storageFile),
                    STREAM_BUFFER_BYTES
                ).use { input -> input.copyTo(output, STREAM_BUFFER_BYTES) }
                output.flush()
            }

            val expectedFileBytes = ATTACHMENT_OFFSET.toLong() + expectedPayloadBytes
            if (temporary.length() != expectedFileBytes) {
                throw IOException(
                    "XISF size=${temporary.length()}, expected=$expectedFileBytes."
                )
            }
            if (resolvedTarget.exists() && overwrite && !resolvedTarget.delete()) {
                throw IOException("Could not replace existing XISF output.")
            }
            if (!temporary.renameTo(resolvedTarget)) {
                throw IOException("Could not commit XISF output.")
            }
            committed = true
            HdrI32XisfWriteResult(
                success = true,
                outputPath = resolvedTarget.absolutePath,
                outputBytes = resolvedTarget.length(),
                attachmentOffset = ATTACHMENT_OFFSET.toLong(),
                durationMs = elapsedMs(startedNs)
            )
        } catch (error: Throwable) {
            val cleanup = !temporary.exists() || temporary.delete()
            HdrI32XisfWriteResult(
                success = false,
                failureCode =
                    if (!cleanup) {
                        HdrI32XisfFailureCode.PARTIAL_OUTPUT_CLEANUP_FAILED
                    } else if (committed) {
                        HdrI32XisfFailureCode.OUTPUT_COMMIT_FAILED
                    } else {
                        HdrI32XisfFailureCode.OUTPUT_IO_FAILURE
                    },
                failureMessage = error.message,
                exceptionClass = error.javaClass.simpleName,
                cleanupSucceeded = cleanup,
                durationMs = elapsedMs(startedNs)
            )
        }
    }

    internal fun buildHeader(frame: HdrI32Frame, payloadBytes: Long): String {
        val metadata = frame.metadata
        val upperBound =
            metadata.maximumRadiance
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: 1.0
        val creationTime = iso8601(metadata.createdAtMillis)
        val cfa = metadata.cfaPattern.name
        val creator = buildString {
            append("BracketLab")
            metadata.appVersion?.takeIf(String::isNotBlank)?.let { append(" ").append(it) }
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append(
                "<xisf version=\"1.0\" xmlns=\"http://www.pixinsight.com/xisf\" " +
                    "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                    "xsi:schemaLocation=\"http://www.pixinsight.com/xisf " +
                    "http://pixinsight.com/xisf/xisf-1.0.xsd\">\n"
            )
            append(
                "  <Image geometry=\"${frame.width}:${frame.height}:1\" " +
                    "sampleFormat=\"Float32\" bounds=\"0:${formatNumber(upperBound)}\" " +
                    "colorSpace=\"Gray\" byteOrder=\"little\" " +
                    "location=\"attachment:$ATTACHMENT_OFFSET:$payloadBytes\">\n"
            )
            append(
                "    <ColorFilterArray pattern=\"$cfa\" width=\"2\" height=\"2\" " +
                    "name=\"Bayer $cfa\"/>\n"
            )
            append(
                "    <FITSKeyword name=\"BAYERPAT\" value=\"'$cfa'\" " +
                    "comment=\"Sensor CFA pattern\"/>\n"
            )
            append(
                "    <FITSKeyword name=\"COLORTYP\" value=\"'$cfa'\" " +
                    "comment=\"Sensor CFA pattern\"/>\n"
            )
            append(
                "    <FITSKeyword name=\"ROWORDER\" value=\"'TOP-DOWN'\" " +
                    "comment=\"First serialized row is the top image row\"/>\n"
            )
            append(
                "    <FITSKeyword name=\"ISOSPEED\" value=\"${metadata.iso}\" " +
                    "comment=\"Reference ISO\"/>\n"
            )
            append(
                "    <FITSKeyword name=\"EXPTIME\" " +
                    "value=\"${formatNumber(metadata.referenceExposureTimeNs / 1_000_000_000.0)}\" " +
                    "comment=\"Reference exposure in seconds\"/>\n"
            )
            append(
                "    <FITSKeyword name=\"HDRMERGE\" " +
                    "value=\"'${metadata.weightPolicy.name}'\" " +
                    "comment=\"Radiance merge weighting policy\"/>\n"
            )
            append(
                "    <FITSKeyword name=\"HDRCOHER\" " +
                    "value=\"'${metadata.highlightCoherencePolicy.name}'\" " +
                    "comment=\"Highlight exposure coherence\"/>\n"
            )
            append("  </Image>\n")
            append("  <Metadata>\n")
            append(
                "    <Property id=\"XISF:CreationTime\" type=\"String\">" +
                    escapeXml(creationTime) +
                    "</Property>\n"
            )
            append(
                "    <Property id=\"XISF:CreatorApplication\" type=\"String\">" +
                    escapeXml(creator) +
                    "</Property>\n"
            )
            append(
                "    <Property id=\"XISF:BlockAlignmentSize\" type=\"UInt16\" " +
                    "value=\"$ATTACHMENT_OFFSET\"/>\n"
            )
            append(
                "    <Property id=\"BracketLab:HdrWeightPolicy\" type=\"String\">" +
                    escapeXml(metadata.weightPolicy.name) +
                    "</Property>\n"
            )
            append(
                "    <Property id=\"BracketLab:HdrHighlightCoherence\" type=\"String\">" +
                    escapeXml(metadata.highlightCoherencePolicy.name) +
                    "</Property>\n"
            )
            append("  </Metadata>\n")
            append("</xisf>\n")
        }
    }

    private fun resolveCollision(file: File, overwrite: Boolean): File {
        if (overwrite || !file.exists()) return file
        val parent = requireNotNull(file.parentFile)
        val base = file.nameWithoutExtension
        val extension = file.extension.ifBlank { "xisf" }
        for (index in 1..999) {
            val candidate =
                File(parent, "${base}_${index.toString().padStart(3, '0')}.$extension")
            if (!candidate.exists()) return candidate
        }
        throw IOException("Could not find a collision-free XISF filename.")
    }

    private fun BufferedOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun iso8601(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))

    private fun formatNumber(value: Double): String =
        java.lang.Double.toString(value)

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun failure(
        code: HdrI32XisfFailureCode,
        message: String,
        startedNs: Long
    ): HdrI32XisfWriteResult =
        HdrI32XisfWriteResult(
            success = false,
            failureCode = code,
            failureMessage = message,
            durationMs = elapsedMs(startedNs)
        )

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L

    const val DEFAULT_FILENAME = "BracketLab_HDR_Master_CFA_Float32.xisf"
    internal const val ATTACHMENT_OFFSET = 4096
    private const val FILE_HEADER_BYTES = 16
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private val SIGNATURE = "XISF0100".toByteArray(Charsets.US_ASCII)
}
