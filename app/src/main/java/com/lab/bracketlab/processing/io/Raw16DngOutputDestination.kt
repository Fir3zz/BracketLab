package com.lab.bracketlab.processing.io

import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream

interface Raw16DngOutputDestination {
    val requestedFilename: String?
    val mimeType: String
        get() = DNG_MIME_TYPE

    @Throws(Exception::class)
    fun open(): Raw16DngOpenedOutput

    companion object {
        const val DNG_MIME_TYPE = "image/x-adobe-dng"
    }
}

class Raw16DngOpenedOutput(
    val outputStream: OutputStream,
    val filename: String?,
    val finalPath: String?,
    val finalUri: Uri?,
    val warnings: List<Raw16DngWarning> = emptyList(),
    private val bytesWrittenProvider: () -> Long?,
    private val commitAction: () -> Boolean,
    private val cleanupAction: () -> Boolean
) : Closeable {
    fun bytesWritten(): Long? = bytesWrittenProvider()

    fun commit(): Boolean = commitAction()

    fun cleanup(): Boolean = cleanupAction()

    override fun close() {
        outputStream.close()
    }
}

class FileRaw16DngOutputDestination(
    private val targetFile: File,
    private val overwrite: Boolean = false
) : Raw16DngOutputDestination {
    override val requestedFilename: String?
        get() = targetFile.name

    override fun open(): Raw16DngOpenedOutput {
        val parent = targetFile.parentFile
            ?: throw IllegalArgumentException("Output file must have a parent directory.")
        parent.mkdirs()
        val resolution = resolveCollision(targetFile, overwrite)
        val tempFile = File(
            resolution.target.parentFile,
            ".${resolution.target.name}.${System.nanoTime()}.tmp"
        )
        val counting = CountingOutputStream(FileOutputStream(tempFile))
        return Raw16DngOpenedOutput(
            outputStream = counting,
            filename = resolution.target.name,
            finalPath = resolution.target.absolutePath,
            finalUri = null,
            warnings = resolution.warnings,
            bytesWrittenProvider = { counting.count },
            commitAction = {
                if (resolution.target.exists() && overwrite) {
                    resolution.target.delete()
                }
                tempFile.renameTo(resolution.target)
            },
            cleanupAction = {
                !tempFile.exists() || tempFile.delete()
            }
        )
    }

    private fun resolveCollision(file: File, overwrite: Boolean): CollisionResolution {
        if (overwrite || !file.exists()) {
            return CollisionResolution(file, emptyList())
        }
        val parent = file.parentFile ?: return CollisionResolution(file, emptyList())
        val base = file.nameWithoutExtension
        val extension = file.extension.ifBlank { "dng" }
        for (index in 1..999) {
            val candidate = File(parent, "${base}_${index.toString().padStart(3, '0')}.$extension")
            if (!candidate.exists()) {
                return CollisionResolution(
                    target = candidate,
                    warnings = listOf(
                        Raw16DngWarning(
                            Raw16DngWarningCode.OUTPUT_FILE_RENAMED_TO_AVOID_COLLISION,
                            "Output filename changed to avoid overwriting an existing DNG."
                        )
                    )
                )
            }
        }
        throw IllegalStateException("Could not find a collision-free DNG filename.")
    }

    private data class CollisionResolution(
        val target: File,
        val warnings: List<Raw16DngWarning>
    )
}

class PreparedRaw16DngOutputDestination(
    override val requestedFilename: String?,
    private val finalPathValue: String? = null,
    private val finalUriValue: Uri? = null,
    private val openStream: () -> OutputStream,
    private val commitAction: () -> Boolean,
    private val cleanupAction: () -> Boolean
) : Raw16DngOutputDestination {
    override fun open(): Raw16DngOpenedOutput {
        val counting = CountingOutputStream(openStream())
        return Raw16DngOpenedOutput(
            outputStream = counting,
            filename = requestedFilename,
            finalPath = finalPathValue,
            finalUri = finalUriValue,
            bytesWrittenProvider = { counting.count },
            commitAction = commitAction,
            cleanupAction = cleanupAction
        )
    }
}

private class CountingOutputStream(
    out: OutputStream
) : FilterOutputStream(out) {
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len.toLong()
    }
}
