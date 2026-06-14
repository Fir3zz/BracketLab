package com.lab.bracketlab.processing.align.star.warp

import kotlin.math.max

data class StarTilePlan(
    val tileWidth: Int,
    val tileHeight: Int,
    val estimatedWorkingBytes: Long,
    val memoryBudgetBytes: Long,
    val reducedFromPreferred: Boolean
)

object AdaptiveStarTilePlanner {
    fun plan(
        imageWidth: Int,
        imageHeight: Int,
        acceptedFrameCount: Int,
        preferredWidth: Int,
        preferredHeight: Int,
        memoryBudgetBytes: Long
    ): StarTilePlan {
        require(imageWidth > 0 && imageHeight > 0)
        require(acceptedFrameCount > 0)
        require(memoryBudgetBytes >= MINIMUM_BUDGET_BYTES)
        var width = preferredWidth.coerceIn(8, imageWidth)
        var height = preferredHeight.coerceIn(8, imageHeight)
        val originalWidth = width
        val originalHeight = height
        while (estimate(width, height, acceptedFrameCount) > memoryBudgetBytes) {
            if (width >= height && width > 8) {
                width = max(8, width / 2)
            } else if (height > 8) {
                height = max(8, height / 2)
            } else {
                error("Working-memory budget is too small for an 8x8 star tile.")
            }
        }
        return StarTilePlan(
            tileWidth = width,
            tileHeight = height,
            estimatedWorkingBytes = estimate(width, height, acceptedFrameCount),
            memoryBudgetBytes = memoryBudgetBytes,
            reducedFromPreferred = width != originalWidth || height != originalHeight
        )
    }

    fun tiles(width: Int, height: Int, plan: StarTilePlan): Sequence<OutputTile> =
        sequence {
            var top = 0
            while (top < height) {
                val tileHeight = minOf(plan.tileHeight, height - top)
                var left = 0
                while (left < width) {
                    val tileWidth = minOf(plan.tileWidth, width - left)
                    yield(OutputTile(left, top, tileWidth, tileHeight))
                    left += tileWidth
                }
                top += tileHeight
            }
        }

    fun estimate(tileWidth: Int, tileHeight: Int, frameCount: Int): Long {
        val pixels = tileWidth.toLong() * tileHeight.toLong()
        val sampleCube = pixels * frameCount.toLong() * Float.SIZE_BYTES
        val outputTile = pixels * 2L
        val countMap = pixels * Int.SIZE_BYTES
        // A rotated inverse-mapped rectangle can approach a square whose side
        // is the sum of both tile dimensions. This deliberately conservative
        // bound keeps the real source window inside the configured budget.
        val sourceWindowSide = tileWidth.toLong() + tileHeight.toLong() + 8L
        val sourceWindow = sourceWindowSide * sourceWindowSide * Int.SIZE_BYTES
        val perPixelScratch =
            frameCount.toLong() * (Double.SIZE_BYTES + Float.SIZE_BYTES + 2L)
        return sampleCube + outputTile + countMap + sourceWindow + perPixelScratch + FIXED_OVERHEAD_BYTES
    }

    private const val MINIMUM_BUDGET_BYTES = 1L * 1024L * 1024L
    private const val FIXED_OVERHEAD_BYTES = 256L * 1024L
}
