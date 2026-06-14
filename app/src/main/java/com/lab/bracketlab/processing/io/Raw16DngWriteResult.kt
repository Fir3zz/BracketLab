package com.lab.bracketlab.processing.io

import android.net.Uri

data class Raw16DngWriteResult(
    val success: Boolean,
    val finalPath: String? = null,
    val finalUri: Uri? = null,
    val filename: String? = null,
    val mimeType: String = Raw16DngOutputDestination.DNG_MIME_TYPE,
    val bytesWritten: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    val referenceFrameIndex: Int? = null,
    val acceptedFrameCount: Int = 0,
    val writingDurationMs: Long = 0L,
    val descriptionWritten: String? = null,
    val warnings: List<Raw16DngWarning> = emptyList(),
    val failureCode: Raw16DngFailureCode? = null,
    val failureMessage: String? = null,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val cleanupResult: Raw16DngCleanupResult = Raw16DngCleanupResult.NOT_NEEDED,
    val writerMethod: Raw16DngWriterMethod? = null
) {
    companion object {
        fun failure(
            code: Raw16DngFailureCode,
            message: String,
            exception: Throwable? = null,
            cleanupResult: Raw16DngCleanupResult = Raw16DngCleanupResult.NOT_NEEDED,
            warnings: List<Raw16DngWarning> = emptyList(),
            width: Int = 0,
            height: Int = 0,
            referenceFrameIndex: Int? = null,
            acceptedFrameCount: Int = 0,
            writingDurationMs: Long = 0L,
            writerMethod: Raw16DngWriterMethod? = null
        ): Raw16DngWriteResult =
            Raw16DngWriteResult(
                success = false,
                width = width,
                height = height,
                referenceFrameIndex = referenceFrameIndex,
                acceptedFrameCount = acceptedFrameCount,
                writingDurationMs = writingDurationMs,
                warnings = warnings,
                failureCode = code,
                failureMessage = message,
                exceptionClass = exception?.javaClass?.simpleName,
                exceptionMessage = exception?.message,
                cleanupResult = cleanupResult,
                writerMethod = writerMethod
            )
    }
}
