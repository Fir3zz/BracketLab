package com.lab.bracketlab.processing.raw

import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.storage.Raw16FrameReader
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object RawProxyGenerator {
    fun extractGreenProxy(
        rawFrame: RawFrame,
        targetMaxSize: Int,
        referenceExposureTimeSeconds: Double? = null
    ): RawProxy {
        val reader = openRawReader(rawFrame)
        try {
            val size = proxySize(rawFrame, targetMaxSize)
            val data = FloatArray(size.width * size.height)
            val whiteLevel = rawFrame.whiteLevel ?: DEFAULT_WHITE_LEVEL

            for (py in 0 until size.height) {
                for (px in 0 until size.width) {
                    val region = sourceRegion(rawFrame, size, px, py)
                    data[py * size.width + px] =
                        averageGreen(rawFrame, reader, whiteLevel, region)
                }
            }

            val proxy = RawProxy(
                width = size.width,
                height = size.height,
                data = data,
                scaleX = size.scaleX,
                scaleY = size.scaleY,
                sourceFrameIndex = rawFrame.frameIndex,
                exposureNormalized = false,
                proxyType = RawProxyType.GREEN,
                notes = "Green-only Bayer proxy for alignment estimation."
            )

            return referenceExposureTimeSeconds?.let {
                normalizeProxyForExposure(proxy, rawFrame.exposureTimeSeconds, it)
            } ?: proxy
        } finally {
            reader.close()
        }
    }

    fun extractLumaProxyForAlignment(
        rawFrame: RawFrame,
        targetMaxSize: Int,
        referenceExposureTimeSeconds: Double? = null
    ): RawProxy {
        val reader = openRawReader(rawFrame)
        try {
            val size = proxySize(rawFrame, targetMaxSize)
            val data = FloatArray(size.width * size.height)
            val whiteLevel = rawFrame.whiteLevel ?: DEFAULT_WHITE_LEVEL

            for (py in 0 until size.height) {
                for (px in 0 until size.width) {
                    val region = sourceRegion(rawFrame, size, px, py)
                    data[py * size.width + px] =
                        averageBayerLuma(rawFrame, reader, whiteLevel, region)
                }
            }

            val proxy = RawProxy(
                width = size.width,
                height = size.height,
                data = data,
                scaleX = size.scaleX,
                scaleY = size.scaleY,
                sourceFrameIndex = rawFrame.frameIndex,
                exposureNormalized = false,
                proxyType = RawProxyType.LUMA_ALIGNMENT,
                notes = "Approximate Bayer-aware luma proxy for alignment estimation."
            )

            return referenceExposureTimeSeconds?.let {
                normalizeProxyForExposure(proxy, rawFrame.exposureTimeSeconds, it)
            } ?: proxy
        } finally {
            reader.close()
        }
    }

    fun normalizeProxyForExposure(
        proxy: RawProxy,
        frameExposureTimeSeconds: Double,
        referenceExposureTimeSeconds: Double
    ): RawProxy {
        val scale = RawMath.evScaleFromExposure(
            exposureTimeSeconds = frameExposureTimeSeconds,
            referenceExposureTimeSeconds = referenceExposureTimeSeconds
        )
        val normalized = FloatArray(proxy.data.size)
        for (index in proxy.data.indices) {
            normalized[index] = (proxy.data[index] / scale.toFloat()).coerceAtLeast(0f)
        }
        return proxy.copy(
            data = normalized,
            exposureNormalized = true,
            notes = appendNote(proxy.notes, "Exposure-normalized for alignment only.")
        )
    }

    fun suppressHotPixelsForProxy(proxy: RawProxy): RawProxy {
        if (proxy.width < 3 || proxy.height < 3) return proxy

        val output = proxy.data.copyOf()
        for (y in 1 until proxy.height - 1) {
            for (x in 1 until proxy.width - 1) {
                val index = y * proxy.width + x
                val center = proxy.data[index]
                val neighbors = neighborValues(proxy, x, y)
                val median = median(neighbors)
                val maxNeighbor = neighbors.maxOrNull() ?: median

                // Conservative proxy-only hot-pixel suppression. Astro star detection
                // will need its own threshold later to avoid deleting real compact stars.
                if (center > maxNeighbor * 8f && center > median + 0.25f) {
                    output[index] = median
                }
            }
        }

        return proxy.copy(
            data = output,
            notes = appendNote(proxy.notes, "Conservative proxy-only hot-pixel suppression.")
        )
    }

    private fun openRawReader(rawFrame: RawFrame): Raw16FrameReader {
        val storage = rawFrame.resolvedRaw16Storage()
            ?: error("RawFrame RAW16 storage is required for proxy generation.")
        return storage.openReader(
            width = rawFrame.width,
            height = rawFrame.height,
            rowStride = rawFrame.rowStride,
            pixelStride = rawFrame.pixelStride
        )
    }

    private fun proxySize(rawFrame: RawFrame, targetMaxSize: Int): ProxySize {
        val safeTarget = targetMaxSize.coerceAtLeast(1)
        val maxDimension = max(rawFrame.width, rawFrame.height).coerceAtLeast(1)
        val scale = min(1.0, safeTarget.toDouble() / maxDimension.toDouble())
        val width = max(1, (rawFrame.width * scale).roundToInt())
        val height = max(1, (rawFrame.height * scale).roundToInt())
        return ProxySize(
            width = width,
            height = height,
            scaleX = rawFrame.width.toDouble() / width.toDouble(),
            scaleY = rawFrame.height.toDouble() / height.toDouble()
        )
    }

    private fun sourceRegion(
        rawFrame: RawFrame,
        proxySize: ProxySize,
        px: Int,
        py: Int
    ): SourceRegion {
        val xStart = (px * proxySize.scaleX).toInt().coerceIn(0, rawFrame.width - 1)
        val yStart = (py * proxySize.scaleY).toInt().coerceIn(0, rawFrame.height - 1)
        val xEnd = ceil((px + 1) * proxySize.scaleX).toInt().coerceIn(xStart + 1, rawFrame.width)
        val yEnd = ceil((py + 1) * proxySize.scaleY).toInt().coerceIn(yStart + 1, rawFrame.height)
        return SourceRegion(xStart, xEnd, yStart, yEnd)
    }

    private fun averageGreen(
        rawFrame: RawFrame,
        reader: Raw16FrameReader,
        whiteLevel: Int,
        region: SourceRegion
    ): Float {
        val direct = averageSamples(rawFrame, reader, whiteLevel, region) { color ->
            color == CfaColor.GREEN_RED_ROW || color == CfaColor.GREEN_BLUE_ROW || color == CfaColor.MONO
        }
        if (direct != null) return direct

        val expanded = region.expand(rawFrame.width, rawFrame.height, radius = 1)
        return averageSamples(rawFrame, reader, whiteLevel, expanded) { color ->
            color == CfaColor.GREEN_RED_ROW || color == CfaColor.GREEN_BLUE_ROW || color == CfaColor.MONO
        } ?: 0f
    }

    private fun averageBayerLuma(
        rawFrame: RawFrame,
        reader: Raw16FrameReader,
        whiteLevel: Int,
        region: SourceRegion
    ): Float =
        averageSamples(rawFrame, reader, whiteLevel, region) { color ->
            color != CfaColor.UNKNOWN
        } ?: 0f

    private fun averageSamples(
        rawFrame: RawFrame,
        reader: Raw16FrameReader,
        whiteLevel: Int,
        region: SourceRegion,
        include: (CfaColor) -> Boolean
    ): Float? {
        var sum = 0.0
        var count = 0
        for (y in region.yStart until region.yEnd) {
            for (x in region.xStart until region.xEnd) {
                val color = BayerUtils.colorAt(rawFrame.cfaPattern, x, y)
                if (!include(color)) continue
                val sample = reader.sampleAt(x, y)
                val black = BayerUtils.blackLevelAt(rawFrame.blackLevelPattern, x, y)
                sum += RawMath.normalizeRaw(sample, black, whiteLevel)
                count++
            }
        }
        return if (count > 0) (sum / count).toFloat() else null
    }

    private fun neighborValues(proxy: RawProxy, x: Int, y: Int): FloatArray {
        val values = FloatArray(8)
        var index = 0
        for (yy in y - 1..y + 1) {
            for (xx in x - 1..x + 1) {
                if (xx == x && yy == y) continue
                values[index++] = proxy.data[yy * proxy.width + xx]
            }
        }
        return values
    }

    private fun median(values: FloatArray): Float {
        val sorted = values.copyOf()
        sorted.sort()
        return (sorted[3] + sorted[4]) * 0.5f
    }

    private fun appendNote(existing: String?, addition: String): String =
        if (existing.isNullOrBlank()) addition else "$existing $addition"

    private data class ProxySize(
        val width: Int,
        val height: Int,
        val scaleX: Double,
        val scaleY: Double
    )

    private data class SourceRegion(
        val xStart: Int,
        val xEnd: Int,
        val yStart: Int,
        val yEnd: Int
    ) {
        fun expand(width: Int, height: Int, radius: Int): SourceRegion =
            SourceRegion(
                xStart = (xStart - radius).coerceAtLeast(0),
                xEnd = (xEnd + radius).coerceAtMost(width),
                yStart = (yStart - radius).coerceAtLeast(0),
                yEnd = (yEnd + radius).coerceAtMost(height)
            )
    }

    private const val DEFAULT_WHITE_LEVEL = 65535
}
