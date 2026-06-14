package com.lab.bracketlab.processing.hdri.export.dng

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class TiffFieldType(val code: Int, val bytesPerValue: Int) {
    BYTE(1, 1),
    ASCII(2, 1),
    SHORT(3, 2),
    LONG(4, 4),
    RATIONAL(5, 8),
    SRATIONAL(10, 8)
}

data class TiffIfdEntry(
    val tag: Int,
    val type: TiffFieldType,
    val count: Long,
    val data: ByteArray
) {
    init {
        require(tag in 0..0xFFFF)
        require(count > 0L)
        require(data.size.toLong() == count * type.bytesPerValue.toLong())
    }
}

data class TiffIfdBuildResult(
    val bytes: ByteArray,
    val imageDataOffset: Long,
    val entries: List<TiffIfdEntry>
)

object TiffIfdBuilder {
    fun build(entries: List<TiffIfdEntry>): TiffIfdBuildResult {
        validate(entries)
        val sorted = entries.sortedBy(TiffIfdEntry::tag)
        val ifdBytes = 2 + sorted.size * 12 + 4
        var externalOffset = TIFF_HEADER_BYTES + ifdBytes
        val externalOffsets = mutableMapOf<Int, Int>()
        for (entry in sorted) {
            if (entry.data.size > 4) {
                externalOffset = align(externalOffset, alignment(entry.type))
                externalOffsets[entry.tag] = externalOffset
                externalOffset += entry.data.size
            }
        }
        val imageOffset = align(externalOffset, 4)
        val output = ByteArray(imageOffset)
        val buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('I'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.putShort(TIFF_MAGIC.toShort())
        buffer.putInt(TIFF_HEADER_BYTES)
        buffer.position(TIFF_HEADER_BYTES)
        buffer.putShort(sorted.size.toShort())
        for (entry in sorted) {
            buffer.putShort(entry.tag.toShort())
            buffer.putShort(entry.type.code.toShort())
            buffer.putInt(entry.count.toInt())
            if (entry.data.size <= 4) {
                buffer.put(entry.data)
                repeat(4 - entry.data.size) { buffer.put(0) }
            } else {
                buffer.putInt(requireNotNull(externalOffsets[entry.tag]))
            }
        }
        buffer.putInt(0)
        for (entry in sorted) {
            val offset = externalOffsets[entry.tag] ?: continue
            entry.data.copyInto(output, offset)
        }
        return TiffIfdBuildResult(output, imageOffset.toLong(), sorted)
    }

    fun validate(entries: List<TiffIfdEntry>) {
        require(entries.isNotEmpty()) { "TIFF IFD must contain entries." }
        require(entries.size <= 0xFFFF)
        val duplicates =
            entries.groupingBy(TiffIfdEntry::tag).eachCount().filterValues { it > 1 }
        require(duplicates.isEmpty()) { "Duplicate TIFF tags: ${duplicates.keys}" }
        entries.forEach {
            require(it.count <= 0xFFFF_FFFFL) { "TIFF count exceeds classic TIFF limits." }
        }
    }

    fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }

    fun shorts(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> values.forEach { buffer.putShort(it.toShort()) } }
            .array()

    fun longs(vararg values: Long): ByteArray =
        ByteBuffer.allocate(values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> values.forEach { buffer.putInt(it.toUInt32Int()) } }
            .array()

    fun ascii(value: String): ByteArray =
        (value + '\u0000').toByteArray(Charsets.US_ASCII)

    fun rationals(values: List<DngRational>): ByteArray =
        ByteBuffer.allocate(values.size * 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer ->
                values.forEach {
                    require(it.numerator >= 0 && it.denominator > 0)
                    buffer.putInt(it.numerator)
                    buffer.putInt(it.denominator)
                }
            }
            .array()

    fun signedRationals(values: List<DngRational>): ByteArray =
        ByteBuffer.allocate(values.size * 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer ->
                values.forEach {
                    buffer.putInt(it.numerator)
                    buffer.putInt(it.denominator)
                }
            }
            .array()

    private fun Long.toUInt32Int(): Int {
        require(this in 0L..0xFFFF_FFFFL) { "Value $this exceeds classic TIFF LONG." }
        return toInt()
    }

    private fun alignment(type: TiffFieldType): Int =
        if (type.bytesPerValue >= 4) 4 else 2

    private fun align(value: Int, alignment: Int): Int =
        ((value + alignment - 1) / alignment) * alignment

    private const val TIFF_HEADER_BYTES = 8
    private const val TIFF_MAGIC = 42
}
