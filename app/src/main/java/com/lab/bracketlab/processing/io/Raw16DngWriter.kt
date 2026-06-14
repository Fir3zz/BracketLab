package com.lab.bracketlab.processing.io

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.os.Build
import android.util.Size
import com.lab.bracketlab.processing.stack.AlignedRaw16StackResult
import java.io.IOException
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Raw16DngWriter {
    fun write(request: Raw16DngWriteRequest): Raw16DngWriteResult =
        writeWithEngine(
            request = request,
            engine = AndroidDngCreatorEngine,
            requireAndroidMetadata = true
        )

    internal fun writeWithEngine(
        request: Raw16DngWriteRequest,
        engine: Raw16DngWriteEngine,
        requireAndroidMetadata: Boolean
    ): Raw16DngWriteResult {
        val startedNs = System.nanoTime()
        val validation = validateRequest(request, requireAndroidMetadata = requireAndroidMetadata)
        if (!validation.success) {
            val validationWarnings = validation.warnings.toMutableList()
            cleanupPackedInputIfRequested(request, null)?.let { validationWarnings += it }
            return Raw16DngWriteResult.failure(
                code = validation.failureCode ?: Raw16DngFailureCode.INVALID_STACK_RESULT,
                message = validation.message ?: "Invalid RAW16 DNG write request.",
                warnings = validationWarnings,
                width = request.alignedResult?.width ?: 0,
                height = request.alignedResult?.height ?: 0,
                referenceFrameIndex = request.alignedResult?.referenceFrameIndex,
                acceptedFrameCount = request.alignedResult?.acceptedFrameCount ?: 0,
                writingDurationMs = elapsedMs(startedNs),
                writerMethod =
                    if (request.alignedResult?.outputRaw16FilePath != null) {
                        Raw16DngWriterMethod.INPUT_STREAM
                    } else {
                        engine.method
                    }
            )
        }

        val alignedResult = request.alignedResult!!
        val destination = request.destination!!
        val description = descriptionFor(request)
        val warnings = validation.warnings.toMutableList()
        if (request.thumbnail != null) {
            warnings += Raw16DngWarning(
                Raw16DngWarningCode.THUMBNAIL_NOT_INCLUDED,
                "Thumbnail was requested but is intentionally disabled for RAW writer validation."
            )
        }

        var opened: Raw16DngOpenedOutput? = null
        var source: Raw16DngSource? = null
        return try {
            source = createDisposableRawSource(alignedResult)
            opened = destination.open()
            warnings += opened.warnings

            engine.write(request, opened.outputStream, source, description)
            opened.outputStream.flush()
            opened.close()

            val committed = opened.commit()
            if (!committed) {
                cleanupPackedInputIfRequested(request, source)?.let { warnings += it }
                val cleaned = opened.cleanup()
                val cleanupResult =
                    if (cleaned) Raw16DngCleanupResult.CLEANED_UP else Raw16DngCleanupResult.CLEANUP_FAILED
                Raw16DngWriteResult.failure(
                    code = Raw16DngFailureCode.OUTPUT_COMMIT_FAILED,
                    message = "DNG output commit failed.",
                    cleanupResult = cleanupResult,
                    warnings = warnings,
                    width = alignedResult.width,
                    height = alignedResult.height,
                    referenceFrameIndex = alignedResult.referenceFrameIndex,
                    acceptedFrameCount = alignedResult.acceptedFrameCount,
                    writingDurationMs = elapsedMs(startedNs),
                    writerMethod = source.method
                )
            } else {
                cleanupPackedInputIfRequested(request, source)?.let { warnings += it }
                Raw16DngWriteResult(
                    success = true,
                    finalPath = opened.finalPath,
                    finalUri = opened.finalUri,
                    filename = opened.filename,
                    mimeType = destination.mimeType,
                    bytesWritten = opened.bytesWritten(),
                    width = alignedResult.width,
                    height = alignedResult.height,
                    referenceFrameIndex = alignedResult.referenceFrameIndex,
                    acceptedFrameCount = alignedResult.acceptedFrameCount,
                    writingDurationMs = elapsedMs(startedNs),
                    descriptionWritten = description,
                    warnings = warnings,
                    cleanupResult = Raw16DngCleanupResult.NOT_NEEDED,
                    writerMethod = source.method
                )
            }
        } catch (e: Throwable) {
            runCatching { opened?.close() }
            val cleanupResult = cleanupPartial(opened)
            cleanupPackedInputIfRequested(request, source)?.let { warnings += it }
            val classified = classifyException(e)
            Raw16DngWriteResult.failure(
                code = classified,
                message = e.message ?: classified.name,
                exception = e,
                cleanupResult = cleanupResult,
                warnings = warnings,
                width = alignedResult.width,
                height = alignedResult.height,
                referenceFrameIndex = alignedResult.referenceFrameIndex,
                acceptedFrameCount = alignedResult.acceptedFrameCount,
                writingDurationMs = elapsedMs(startedNs),
                writerMethod = source?.method ?: engine.method
            )
        }
    }

    internal fun validateRequest(
        request: Raw16DngWriteRequest,
        requireAndroidMetadata: Boolean = true,
        requireCameraCharacteristics: Boolean = requireAndroidMetadata,
        requireCaptureResult: Boolean = requireAndroidMetadata,
        requireDestination: Boolean = true
    ): Raw16DngValidation {
        val alignedResult = request.alignedResult
            ?: return Raw16DngValidation.failure(
                Raw16DngFailureCode.INVALID_STACK_RESULT,
                "Aligned RAW16 stack result is missing."
            )

        val packed = validatePackedResult(alignedResult)
        if (!packed.success) return packed

        val warnings = packed.warnings.toMutableList()
        val resultReference = alignedResult.referenceFrameIndex
            ?: return Raw16DngValidation.failure(
                Raw16DngFailureCode.MISSING_REFERENCE_METADATA,
                "Aligned result has no reference frame index."
            )

        if (request.referenceFrameIndex != null && request.referenceFrameIndex != resultReference) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.REFERENCE_METADATA_MISMATCH,
                "Requested reference frame ${request.referenceFrameIndex} does not match stack reference $resultReference."
            )
        }
        if (request.metadataFrameIndex == null) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.MISSING_REFERENCE_METADATA,
                "Reference CaptureResult frame index is missing."
            )
        }
        if (request.metadataFrameIndex != resultReference) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.REFERENCE_METADATA_MISMATCH,
                "CaptureResult frame ${request.metadataFrameIndex} does not match stack reference $resultReference."
            )
        }
        if (
            request.expectedCameraId != null &&
            request.metadataCameraId != null &&
            request.expectedCameraId != request.metadataCameraId
        ) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.REFERENCE_METADATA_MISMATCH,
                "Reference camera ID ${request.metadataCameraId} does not match expected camera ID ${request.expectedCameraId}."
            )
        }
        if (
            request.expectedWidth != null &&
            request.expectedHeight != null &&
            (request.expectedWidth != alignedResult.width || request.expectedHeight != alignedResult.height)
        ) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.REFERENCE_FRAME_SIZE_MISMATCH,
                "Stack output ${alignedResult.width}x${alignedResult.height} differs from reference RAW ${request.expectedWidth}x${request.expectedHeight}."
            )
        }

        if (requireCameraCharacteristics && request.cameraCharacteristics == null) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.MISSING_CAMERA_CHARACTERISTICS,
                "CameraCharacteristics is required for DngCreator."
            )
        }
        if (requireCaptureResult && request.captureResult == null) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.MISSING_CAPTURE_RESULT,
                "CaptureResult is required for DngCreator."
            )
        }
        if (requireDestination && request.destination == null) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.DESTINATION_CREATION_FAILED,
                "Output destination is missing."
            )
        }

        if (requireAndroidMetadata) {
            if (request.cameraCharacteristics == null) {
                return Raw16DngValidation.failure(
                    Raw16DngFailureCode.MISSING_CAMERA_CHARACTERISTICS,
                    "CameraCharacteristics is required for DngCreator."
                )
            }
            if (request.captureResult == null) {
                return Raw16DngValidation.failure(
                    Raw16DngFailureCode.MISSING_CAPTURE_RESULT,
                    "CaptureResult is required for DngCreator."
                )
            }
            warnings += activeArrayWarnings(request.cameraCharacteristics, alignedResult)
        }

        return Raw16DngValidation.success(warnings)
    }

    internal fun validatePackedResult(result: AlignedRaw16StackResult): Raw16DngValidation {
        if (!result.success) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.STACK_RESULT_NOT_SUCCESSFUL,
                "Aligned stack result is not successful."
            )
        }
        if (result.width <= 0 || result.height <= 0) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.INVALID_OUTPUT_DIMENSIONS,
                "DNG dimensions must be positive; got ${result.width}x${result.height}."
            )
        }
        val requiredBytes = requiredByteCount(result.width, result.height)
            ?: return Raw16DngValidation.failure(
                Raw16DngFailureCode.INVALID_OUTPUT_DIMENSIONS,
                "DNG dimensions are too large."
            )
        val buffer = result.outputRaw16
        val file = result.outputRaw16FilePath?.let(::File)
        if (buffer == null && file == null) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.INVALID_STACK_RESULT,
                "Aligned stack packed RAW16 output is missing."
            )
        }
        if (buffer != null && buffer.capacity() < requiredBytes) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.BUFFER_SIZE_MISMATCH,
                "RAW16 buffer capacity ${buffer.capacity()} is smaller than required $requiredBytes bytes."
            )
        }
        if (file != null && (!file.isFile || file.length() < requiredBytes.toLong())) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.BUFFER_SIZE_MISMATCH,
                "RAW16 file length ${file.length()} is smaller than required $requiredBytes bytes."
            )
        }
        if (result.outputRowStride != result.width * 2) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.UNSUPPORTED_ROW_STRIDE,
                "RAW16 DNG writer requires packed rowStride width*2; got ${result.outputRowStride}."
            )
        }
        if (result.outputPixelStride != 2) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.UNSUPPORTED_PIXEL_STRIDE,
                "RAW16 DNG writer requires pixelStride 2; got ${result.outputPixelStride}."
            )
        }
        if (result.outputByteOrder != ByteOrder.LITTLE_ENDIAN) {
            return Raw16DngValidation.failure(
                Raw16DngFailureCode.INVALID_BYTE_ORDER,
                "RAW16 DNG writer requires little-endian output."
            )
        }

        val warnings = mutableListOf<Raw16DngWarning>()
        if (result.singleContributorPixelCount > 0 || result.fullContributorPixelCount < result.width * result.height) {
            warnings += Raw16DngWarning(
                Raw16DngWarningCode.BORDER_HAS_REDUCED_SAMPLE_COUNT,
                "Shifted borders have reduced sample counts."
            )
        }
        if (result.rejectedFrameCount > 0) {
            warnings += Raw16DngWarning(
                Raw16DngWarningCode.REJECTED_FRAMES_EXCLUDED,
                "Rejected frames were excluded from the aligned RAW16 average."
            )
        }
        if (result.appliedTranslations.any { it.residualMagnitudeRaw > 0.0 }) {
            warnings += Raw16DngWarning(
                Raw16DngWarningCode.CFA_SAFE_TRANSLATION_RESIDUAL_PRESENT,
                "Subpixel alignment estimates were quantized to CFA-safe even-pixel translations."
            )
        }
        return Raw16DngValidation.success(warnings)
    }

    internal fun createDisposableRawBufferDuplicate(result: AlignedRaw16StackResult): ByteBuffer {
        val requiredBytes = requiredByteCount(result.width, result.height)
            ?: error("Invalid RAW16 dimensions.")
        val source = result.outputRaw16 ?: error("Missing RAW16 output.")
        val duplicate = source.asReadOnlyBuffer()
        duplicate.order(ByteOrder.LITTLE_ENDIAN)
        duplicate.position(0)
        duplicate.limit(requiredBytes)
        return duplicate
    }

    internal fun createDisposableRawSource(result: AlignedRaw16StackResult): Raw16DngSource {
        result.outputRaw16?.let {
            return Raw16DngSource.Buffer(createDisposableRawBufferDuplicate(result))
        }
        val file = result.outputRaw16FilePath?.let(::File)
            ?: error("Missing packed RAW16 output.")
        return Raw16DngSource.FileSource(file)
    }

    internal fun descriptionFor(request: Raw16DngWriteRequest): String {
        val result = request.alignedResult
        return request.description ?: buildString {
            appendLine("BracketLab aligned RAW16 average stack")
            appendLine("Frames accepted: ${result?.acceptedFrameCount ?: 0}")
            appendLine("Reference frame: ${result?.referenceFrameIndex ?: "unknown"}")
            append("Alignment: landscape translation, CFA-safe even-pixel application")
        }
    }

    private fun activeArrayWarnings(
        characteristics: CameraCharacteristics?,
        result: AlignedRaw16StackResult
    ): List<Raw16DngWarning> {
        if (characteristics == null) return emptyList()
        val warnings = mutableListOf<Raw16DngWarning>()
        fun Rect.differsFromResult(): Boolean = width() != result.width || height() != result.height

        characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { active ->
            if (active.differsFromResult()) {
                warnings += Raw16DngWarning(
                    Raw16DngWarningCode.SENSOR_ACTIVE_ARRAY_DIFFERS_FROM_RAW_SIZE,
                    "Sensor active array ${active.width()}x${active.height()} differs from RAW output ${result.width}x${result.height}; DngCreator will validate final compatibility."
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.let { active ->
                if (active.differsFromResult()) {
                    warnings += Raw16DngWarning(
                        Raw16DngWarningCode.PRE_CORRECTION_ACTIVE_ARRAY_DIFFERS_FROM_RAW_SIZE,
                        "Pre-correction active array ${active.width()}x${active.height()} differs from RAW output ${result.width}x${result.height}; DngCreator will validate final compatibility."
                    )
                }
            }
        }
        return warnings
    }

    private fun classifyException(e: Throwable): Raw16DngFailureCode =
        when (e) {
            is IllegalArgumentException -> Raw16DngFailureCode.DNGCREATOR_ILLEGAL_ARGUMENT
            is IllegalStateException -> Raw16DngFailureCode.DNGCREATOR_ILLEGAL_STATE
            is SecurityException -> Raw16DngFailureCode.OUTPUT_PERMISSION_FAILURE
            is IOException -> Raw16DngFailureCode.OUTPUT_IO_FAILURE
            else -> Raw16DngFailureCode.OUTPUT_IO_FAILURE
        }

    private fun cleanupPartial(opened: Raw16DngOpenedOutput?): Raw16DngCleanupResult {
        if (opened == null) return Raw16DngCleanupResult.NOT_ATTEMPTED
        return if (runCatching { opened.cleanup() }.getOrDefault(false)) {
            Raw16DngCleanupResult.CLEANED_UP
        } else {
            Raw16DngCleanupResult.CLEANUP_FAILED
        }
    }

    private fun cleanupPackedInputIfRequested(
        request: Raw16DngWriteRequest,
        source: Raw16DngSource?
    ): Raw16DngWarning? {
        if (!request.deletePackedInputAfterWrite) return null
        val file =
            (source as? Raw16DngSource.FileSource)?.file
                ?: request.alignedResult?.outputRaw16FilePath?.let(::File)
                ?: return null
        if (!file.exists() || file.delete()) return null
        return Raw16DngWarning(
            Raw16DngWarningCode.TEMPORARY_RAW_CLEANUP_FAILED,
            "Temporary packed RAW16 file could not be removed: ${file.absolutePath}"
        )
    }

    private fun requiredByteCount(width: Int, height: Int): Int? {
        val count = width.toLong() * height.toLong() * 2L
        return if (count in 1L..Int.MAX_VALUE.toLong()) count.toInt() else null
    }

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L
}

internal data class Raw16DngValidation(
    val success: Boolean,
    val failureCode: Raw16DngFailureCode?,
    val message: String?,
    val warnings: List<Raw16DngWarning>
) {
    companion object {
        fun success(warnings: List<Raw16DngWarning> = emptyList()): Raw16DngValidation =
            Raw16DngValidation(true, null, null, warnings)

        fun failure(code: Raw16DngFailureCode, message: String): Raw16DngValidation =
            Raw16DngValidation(false, code, message, emptyList())
    }
}

internal interface Raw16DngWriteEngine {
    val method: Raw16DngWriterMethod

    @Throws(Exception::class)
    fun write(
        request: Raw16DngWriteRequest,
        outputStream: OutputStream,
        rawSource: Raw16DngSource,
        description: String
    )
}

internal sealed class Raw16DngSource(val method: Raw16DngWriterMethod) {
    class Buffer(val buffer: ByteBuffer) : Raw16DngSource(Raw16DngWriterMethod.BYTE_BUFFER)
    class FileSource(val file: File) : Raw16DngSource(Raw16DngWriterMethod.INPUT_STREAM)
}

private object AndroidDngCreatorEngine : Raw16DngWriteEngine {
    override val method: Raw16DngWriterMethod = Raw16DngWriterMethod.BYTE_BUFFER

    override fun write(
        request: Raw16DngWriteRequest,
        outputStream: OutputStream,
        rawSource: Raw16DngSource,
        description: String
    ) {
        val characteristics = request.cameraCharacteristics
            ?: throw IllegalArgumentException("CameraCharacteristics is required.")
        val captureResult = request.captureResult
            ?: throw IllegalArgumentException("CaptureResult is required.")
        val result = request.alignedResult
            ?: throw IllegalArgumentException("Aligned stack result is required.")

        val creator = DngCreator(characteristics, captureResult)
        try {
            request.orientation?.let { creator.setOrientation(it) }
            request.location?.let { creator.setLocation(it) }
            creator.setDescription(description)
            if (request.thumbnail != null) {
                @Suppress("UNUSED_VARIABLE")
                val ignored: Bitmap = request.thumbnail
            }
            when (rawSource) {
                is Raw16DngSource.Buffer ->
                    creator.writeByteBuffer(
                        outputStream,
                        Size(result.width, result.height),
                        rawSource.buffer,
                        0L
                    )
                is Raw16DngSource.FileSource ->
                    FileInputStream(rawSource.file).use { input ->
                        creator.writeInputStream(
                            outputStream,
                            Size(result.width, result.height),
                            input,
                            0L
                        )
                    }
            }
        } finally {
            creator.close()
        }
    }
}
