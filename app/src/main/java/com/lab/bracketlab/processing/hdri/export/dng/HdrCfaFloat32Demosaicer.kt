package com.lab.bracketlab.processing.hdri.export.dng

import com.lab.bracketlab.processing.hdri.HdrI32Frame
import com.lab.bracketlab.processing.raw.BayerUtils
import com.lab.bracketlab.processing.raw.CfaColor
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class NormalizedCfaTile(
    val startY: Int,
    val outputStartY: Int,
    val outputRowCount: Int,
    val width: Int,
    val rowCount: Int,
    val data: FloatArray,
    val invalidInputCount: Long,
    val negativeClampCount: Long
) {
    fun sample(x: Int, y: Int): Float =
        data[(y - startY) * width + x]
}

class HdrCfaFloat32Demosaicer(
    private val frame: HdrI32Frame,
    referenceExposureTimeNs: Long = frame.metadata.referenceExposureTimeNs
) : Closeable {
    private val source = RandomAccessFile(frame.storageFile, "r")
    private val referenceExposureSeconds =
        referenceExposureTimeNs.toDouble() / 1_000_000_000.0

    init {
        require(referenceExposureTimeNs > 0L) { "Reference exposure must be positive." }
        require(frame.metadata.blackLevelPattern.size >= 4) {
            "Per-CFA black level is required."
        }
        require(frame.metadata.whiteLevel > 0) { "White level must be positive." }
    }

    fun readTile(outputStartY: Int, outputRowCount: Int, halo: Int = 1): NormalizedCfaTile {
        require(outputStartY in 0 until frame.height)
        require(outputRowCount > 0 && outputStartY + outputRowCount <= frame.height)
        require(halo >= 1)
        val startY = (outputStartY - halo).coerceAtLeast(0)
        val endY = (outputStartY + outputRowCount + halo).coerceAtMost(frame.height)
        val rowCount = endY - startY
        val data = FloatArray(rowCount * frame.width)
        val rowBytes = ByteArray(frame.width * Float.SIZE_BYTES)
        var invalid = 0L
        var negatives = 0L
        for (localY in 0 until rowCount) {
            val y = startY + localY
            source.seek(y.toLong() * frame.rowStrideBytes.toLong())
            source.readFully(rowBytes)
            val row = ByteBuffer.wrap(rowBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (x in 0 until frame.width) {
                val radiance = row.float
                val black = BayerUtils.blackLevelAt(frame.metadata.blackLevelPattern, x, y)
                val fullScale = (frame.metadata.whiteLevel - black).coerceAtLeast(1)
                val belongsToOutput =
                    y in outputStartY until outputStartY + outputRowCount
                val normalized =
                    when {
                        !radiance.isFinite() -> {
                            if (belongsToOutput) invalid++
                            0f
                        }
                        radiance < 0f -> {
                            if (belongsToOutput) negatives++
                            0f
                        }
                        else ->
                            (
                                radiance.toDouble() *
                                    referenceExposureSeconds /
                                    fullScale.toDouble()
                                ).toFloat()
                    }
                data[localY * frame.width + x] = normalized
            }
        }
        return NormalizedCfaTile(
            startY = startY,
            outputStartY = outputStartY,
            outputRowCount = outputRowCount,
            width = frame.width,
            rowCount = rowCount,
            data = data,
            invalidInputCount = invalid,
            negativeClampCount = negatives
        )
    }

    fun demosaicRow(tile: NormalizedCfaTile, y: Int, rgbOutput: FloatArray) {
        require(y in tile.outputStartY until tile.outputStartY + tile.outputRowCount)
        require(rgbOutput.size >= frame.width * 3)
        for (x in 0 until frame.width) {
            val base = x * 3
            rgbOutput[base] = channelAt(tile, x, y, Channel.RED)
            rgbOutput[base + 1] = channelAt(tile, x, y, Channel.GREEN)
            rgbOutput[base + 2] = channelAt(tile, x, y, Channel.BLUE)
        }
    }

    private fun channelAt(
        tile: NormalizedCfaTile,
        x: Int,
        y: Int,
        channel: Channel
    ): Float {
        if (matches(BayerUtils.colorAt(frame.cfaPattern, x, y), channel)) {
            return tile.sample(x, y)
        }
        var total = 0.0
        var count = 0
        val minY = maxOf(0, y - 1)
        val maxY = minOf(frame.height - 1, y + 1)
        val minX = maxOf(0, x - 1)
        val maxX = minOf(frame.width - 1, x + 1)
        for (sampleY in minY..maxY) {
            for (sampleX in minX..maxX) {
                if (
                    matches(
                        BayerUtils.colorAt(frame.cfaPattern, sampleX, sampleY),
                        channel
                    )
                ) {
                    total += tile.sample(sampleX, sampleY).toDouble()
                    count++
                }
            }
        }
        return if (count > 0) (total / count.toDouble()).toFloat() else 0f
    }

    private fun matches(color: CfaColor, channel: Channel): Boolean =
        when (channel) {
            Channel.RED -> color == CfaColor.RED
            Channel.GREEN ->
                color == CfaColor.GREEN_RED_ROW ||
                    color == CfaColor.GREEN_BLUE_ROW
            Channel.BLUE -> color == CfaColor.BLUE
        }

    override fun close() {
        source.close()
    }

    private enum class Channel {
        RED,
        GREEN,
        BLUE
    }
}
