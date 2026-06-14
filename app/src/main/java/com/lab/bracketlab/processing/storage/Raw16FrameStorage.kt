package com.lab.bracketlab.processing.storage

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

interface Raw16FrameStorage {
    val byteCount: Long
    val residentByteCount: Long
    val isFileBacked: Boolean

    fun openReader(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): Raw16FrameReader

    fun deleteIfOwned(): Boolean = true
}

interface Raw16FrameReader : Closeable {
    fun sampleAt(x: Int, y: Int): Int
}

class InMemoryRaw16FrameStorage(
    val bytes: ByteArray
) : Raw16FrameStorage {
    override val byteCount: Long
        get() = bytes.size.toLong()

    override val residentByteCount: Long
        get() = byteCount

    override val isFileBacked: Boolean = false

    override fun openReader(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): Raw16FrameReader =
        InMemoryRaw16FrameReader(bytes, rowStride, pixelStride)
}

class FileBackedRaw16FrameStorage(
    val file: File,
    private val deleteOnCleanup: Boolean = true
) : Raw16FrameStorage {
    override val byteCount: Long
        get() = file.length()

    override val residentByteCount: Long = 0L

    override val isFileBacked: Boolean = true

    override fun openReader(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): Raw16FrameReader =
        FileBackedRaw16FrameReader(file, rowStride, pixelStride)

    override fun deleteIfOwned(): Boolean =
        !deleteOnCleanup || !file.exists() || file.delete()
}

private class InMemoryRaw16FrameReader(
    private val bytes: ByteArray,
    private val rowStride: Int,
    private val pixelStride: Int
) : Raw16FrameReader {
    override fun sampleAt(x: Int, y: Int): Int {
        val offset = y * rowStride + x * pixelStride
        val lo = bytes[offset].toInt() and 0xFF
        val hi = bytes[offset + 1].toInt() and 0xFF
        return lo or (hi shl 8)
    }

    override fun close() = Unit
}

private class FileBackedRaw16FrameReader(
    file: File,
    private val rowStride: Int,
    private val pixelStride: Int
) : Raw16FrameReader {
    private val input = RandomAccessFile(file, "r")
    private val cachedRow = ByteArray(rowStride)
    private var cachedRowIndex = -1

    override fun sampleAt(x: Int, y: Int): Int {
        if (cachedRowIndex != y) {
            input.seek(y.toLong() * rowStride.toLong())
            input.readFully(cachedRow)
            cachedRowIndex = y
        }
        val offset = x * pixelStride
        val lo = cachedRow[offset].toInt() and 0xFF
        val hi = cachedRow[offset + 1].toInt() and 0xFF
        return lo or (hi shl 8)
    }

    override fun close() {
        input.close()
    }
}
