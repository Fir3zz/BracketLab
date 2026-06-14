package com.lab.bracketlab.processing.hdri.export.dng

internal data class LinearRgbFloat16StripLayout(
    val byteCounts: LongArray,
    val offsets: LongArray,
    val expectedImageBytes: Long,
    val expectedFileBytes: Long
) {
    val sumStripByteCounts: Long
        get() = byteCounts.sum()

    fun isValid(fileSize: Long = expectedFileBytes): Boolean {
        if (byteCounts.isEmpty() || byteCounts.size != offsets.size) return false
        if (sumStripByteCounts != expectedImageBytes) return false
        var previousOffset = -1L
        for (index in offsets.indices) {
            val offset = offsets[index]
            val count = byteCounts[index]
            if (offset <= previousOffset || count <= 0L) return false
            if (offset > Long.MAX_VALUE - count || offset + count > fileSize) return false
            previousOffset = offset
        }
        return offsets.last() + byteCounts.last() == expectedFileBytes
    }

    companion object {
        fun create(
            width: Int,
            height: Int,
            rowsPerStrip: Int,
            imageDataOffset: Long
        ): LinearRgbFloat16StripLayout {
            require(width > 0 && height > 0 && rowsPerStrip > 0)
            val stripCount = (height + rowsPerStrip - 1) / rowsPerStrip
            val bytesPerRow = width.toLong() * CHANNELS * HALF_BYTES
            val counts =
                LongArray(stripCount) { index ->
                    val startY = index * rowsPerStrip
                    val rows = minOf(rowsPerStrip, height - startY)
                    bytesPerRow * rows.toLong()
                }
            val offsets = LongArray(stripCount)
            var offset = imageDataOffset
            for (index in offsets.indices) {
                offsets[index] = offset
                offset += counts[index]
            }
            return LinearRgbFloat16StripLayout(
                byteCounts = counts,
                offsets = offsets,
                expectedImageBytes = bytesPerRow * height.toLong(),
                expectedFileBytes = offset
            )
        }

        private const val CHANNELS = 3L
        private const val HALF_BYTES = 2L
    }
}
