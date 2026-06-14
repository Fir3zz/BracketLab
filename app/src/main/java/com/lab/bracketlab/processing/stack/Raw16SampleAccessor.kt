package com.lab.bracketlab.processing.stack

import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.storage.Raw16FrameReader
import java.io.Closeable

data class Raw16BufferValidation(
    val valid: Boolean,
    val failureCode: AlignedRaw16StackFailureCode?,
    val message: String?
)

class Raw16SampleAccessor private constructor(
    private val reader: Raw16FrameReader
) : Closeable {
    fun sampleAt(x: Int, y: Int): Int = reader.sampleAt(x, y)

    override fun close() {
        reader.close()
    }

    companion object {
        fun validate(frame: RawFrame): Raw16BufferValidation {
            if (frame.width <= 0 || frame.height <= 0) {
                return Raw16BufferValidation(
                    valid = false,
                    failureCode = AlignedRaw16StackFailureCode.INVALID_RAW_DIMENSIONS,
                    message = "RAW dimensions must be positive."
                )
            }
            if (frame.pixelStride < 2) {
                return Raw16BufferValidation(
                    valid = false,
                    failureCode = AlignedRaw16StackFailureCode.INVALID_PIXEL_STRIDE,
                    message = "pixelStride must be at least 2 bytes for RAW16."
                )
            }
            val minimumRowStride = safeAdd(safeMultiply(frame.width - 1, frame.pixelStride), 2)
                ?: return Raw16BufferValidation(
                    valid = false,
                    failureCode = AlignedRaw16StackFailureCode.INVALID_ROW_STRIDE,
                    message = "rowStride calculation overflowed."
                )
            if (frame.rowStride < minimumRowStride) {
                return Raw16BufferValidation(
                    valid = false,
                    failureCode = AlignedRaw16StackFailureCode.INVALID_ROW_STRIDE,
                    message = "rowStride is too small for width and pixelStride."
                )
            }
            val storage = frame.resolvedRaw16Storage()
                ?: return Raw16BufferValidation(
                    valid = false,
                    failureCode = AlignedRaw16StackFailureCode.INVALID_RAW_BUFFER,
                    message = "RawFrame RAW16 storage is missing."
                )
            val lastOffset =
                safeAdd(
                    safeMultiply(frame.height - 1, frame.rowStride),
                    safeAdd(safeMultiply(frame.width - 1, frame.pixelStride), 1)
                )
                    ?: return Raw16BufferValidation(
                        valid = false,
                        failureCode = AlignedRaw16StackFailureCode.INVALID_RAW_BUFFER,
                        message = "RAW byte offset calculation overflowed."
                    )
            if (lastOffset < 0 || lastOffset.toLong() >= storage.byteCount) {
                return Raw16BufferValidation(
                    valid = false,
                    failureCode = AlignedRaw16StackFailureCode.INVALID_RAW_BUFFER,
                    message = "RAW buffer is too small for dimensions and strides."
                )
            }
            return Raw16BufferValidation(valid = true, failureCode = null, message = null)
        }

        fun create(frame: RawFrame): Raw16SampleAccessor {
            val validation = validate(frame)
            require(validation.valid) { validation.message ?: "Invalid RAW16 frame." }
            val storage = requireNotNull(frame.resolvedRaw16Storage())
            return Raw16SampleAccessor(
                reader = storage.openReader(
                    width = frame.width,
                    height = frame.height,
                    rowStride = frame.rowStride,
                    pixelStride = frame.pixelStride
                )
            )
        }

        private fun safeMultiply(left: Int, right: Int): Int? {
            val value = left.toLong() * right.toLong()
            return if (value >= Int.MIN_VALUE.toLong() && value <= Int.MAX_VALUE.toLong()) value.toInt() else null
        }

        private fun safeAdd(left: Int?, right: Int?): Int? {
            if (left == null || right == null) return null
            val value = left.toLong() + right.toLong()
            return if (value >= Int.MIN_VALUE.toLong() && value <= Int.MAX_VALUE.toLong()) value.toInt() else null
        }
    }
}
