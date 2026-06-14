package com.lab.bracketlab.processing.io

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

data class PublishedOutput(
    val success: Boolean,
    val displayName: String,
    val uri: Uri? = null,
    val path: String? = null,
    val storageDirectory: String? = null,
    val bytes: Long = 0L,
    val failureMessage: String? = null
)

class MediaStoreRaw16DngOutputDestination(
    private val context: Context,
    private val relativeDirectory: String,
    override val requestedFilename: String
) : Raw16DngOutputDestination {
    override fun open(): Raw16DngOpenedOutput {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val directory =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    relativeDirectory.removePrefix("DCIM/")
                ).also(File::mkdirs)
            return FileRaw16DngOutputDestination(
                File(directory, requestedFilename),
                overwrite = false
            ).open()
        }

        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, requestedFilename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val resolver = context.contentResolver
        val uri =
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create MediaStore DNG destination.")
        val stream =
            resolver.openOutputStream(uri, "w")
                ?: run {
                    resolver.delete(uri, null, null)
                    error("Could not open MediaStore DNG output stream.")
                }
        return Raw16DngOpenedOutput(
            outputStream = stream,
            filename = requestedFilename,
            finalPath = null,
            finalUri = uri,
            bytesWrittenProvider = { null },
            commitAction = {
                ContentValues().run {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, this, null, null) == 1
                }
            },
            cleanupAction = { resolver.delete(uri, null, null) >= 0 }
        )
    }
}

class DcimOutputPublisher(private val context: Context) {
    fun publish(
        source: File,
        relativeDirectory: String,
        displayName: String = source.name,
        mimeType: String = mimeTypeFor(displayName)
    ): PublishedOutput {
        if (!source.isFile) {
            return PublishedOutput(
                success = false,
                displayName = displayName,
                failureMessage = "Source output does not exist: ${source.absolutePath}"
            )
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishMediaStore(source, relativeDirectory, displayName, mimeType)
        } else {
            publishLegacy(source, relativeDirectory, displayName)
        }
    }

    private fun publishMediaStore(
        source: File,
        relativeDirectory: String,
        displayName: String,
        mimeType: String
    ): PublishedOutput {
        val resolver = context.contentResolver
        val placement = mediaStorePlacement(relativeDirectory, mimeType)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, placement.relativeDirectory)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        var uri: Uri? = null
        return try {
            uri =
                resolver.insert(placement.collection, values)
                    ?: error("MediaStore insert returned null.")
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "MediaStore output stream is unavailable." }
                FileInputStream(source).use { input -> input.copyTo(output, COPY_BUFFER_BYTES) }
                output.flush()
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) {
                "MediaStore commit failed."
            }
            PublishedOutput(
                success = true,
                displayName = displayName,
                uri = uri,
                storageDirectory = placement.relativeDirectory,
                bytes = source.length()
            )
        } catch (error: Throwable) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            PublishedOutput(
                success = false,
                displayName = displayName,
                storageDirectory = placement.relativeDirectory,
                failureMessage = error.message
            )
        }
    }

    private fun publishLegacy(
        source: File,
        relativeDirectory: String,
        displayName: String
    ): PublishedOutput {
        val directory =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                relativeDirectory.removePrefix("DCIM/")
            ).also(File::mkdirs)
        val target = collisionFreeFile(directory, displayName)
        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                    output.flush()
                }
            }
            PublishedOutput(
                success = true,
                displayName = target.name,
                path = target.absolutePath,
                storageDirectory = relativeDirectory,
                bytes = target.length()
            )
        } catch (error: Throwable) {
            runCatching { target.delete() }
            PublishedOutput(
                success = false,
                displayName = displayName,
                storageDirectory = relativeDirectory,
                failureMessage = error.message
            )
        }
    }

    private fun collisionFreeFile(directory: File, displayName: String): File {
        val requested = File(directory, displayName)
        if (!requested.exists()) return requested
        val base = requested.nameWithoutExtension
        val extension = requested.extension
        for (index in 1..999) {
            val suffix = index.toString().padStart(3, '0')
            val candidate =
                File(directory, if (extension.isBlank()) "${base}_$suffix" else "${base}_$suffix.$extension")
            if (!candidate.exists()) return candidate
        }
        error("Could not resolve output filename collision.")
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 128 * 1024

        private data class MediaStorePlacement(
            val collection: Uri,
            val relativeDirectory: String
        )

        private fun mediaStorePlacement(
            requestedDirectory: String,
            mimeType: String
        ): MediaStorePlacement =
            if (mimeType.startsWith("image/")) {
                MediaStorePlacement(
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    relativeDirectory = requestedDirectory
                )
            } else {
                MediaStorePlacement(
                    collection = MediaStore.Files.getContentUri("external"),
                    relativeDirectory = documentsDirectoryFor(requestedDirectory)
                )
            }

        private fun documentsDirectoryFor(requestedDirectory: String): String {
            val normalized = requestedDirectory.replace('\\', '/').trim('/')
            val withoutPrimary =
                normalized
                    .removePrefix("${Environment.DIRECTORY_DCIM}/")
                    .removePrefix("${Environment.DIRECTORY_DOCUMENTS}/")
            return "${Environment.DIRECTORY_DOCUMENTS}/$withoutPrimary"
        }

        fun mimeTypeFor(filename: String): String =
            when (filename.substringAfterLast('.', "").lowercase()) {
                "dng" -> Raw16DngOutputDestination.DNG_MIME_TYPE
                "fits", "fit" -> "application/fits"
                "xisf" -> "application/octet-stream"
                "json" -> "application/json"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }
    }
}
