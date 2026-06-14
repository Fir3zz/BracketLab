package com.lab.bracketlab.processing.align.star.warp

import com.lab.bracketlab.processing.align.star.StarPoint
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.stack.Raw16SampleAccessor
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class SourceTileReader(
    private val frame: RawFrame,
    private val accessor: Raw16SampleAccessor
) {
    fun readWindow(
        outputTile: OutputTile,
        inverse: SimilarityTransformInverse,
        haloFullPixels: Int = 3
    ): SourceTileWindow? {
        val corners =
            listOf(
                inverse.mapReferenceToTarget(outputTile.left.toDouble(), outputTile.top.toDouble()),
                inverse.mapReferenceToTarget((outputTile.rightExclusive - 1).toDouble(), outputTile.top.toDouble()),
                inverse.mapReferenceToTarget(outputTile.left.toDouble(), (outputTile.bottomExclusive - 1).toDouble()),
                inverse.mapReferenceToTarget(
                    (outputTile.rightExclusive - 1).toDouble(),
                    (outputTile.bottomExclusive - 1).toDouble()
                )
            )
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val left = max(0, floor(corners.minOf(StarPoint::x)).toInt() - haloFullPixels)
        val top = max(0, floor(corners.minOf(StarPoint::y)).toInt() - haloFullPixels)
        val right =
            min(frame.width, ceil(corners.maxOf(StarPoint::x)).toInt() + haloFullPixels + 1)
        val bottom =
            min(frame.height, ceil(corners.maxOf(StarPoint::y)).toInt() + haloFullPixels + 1)
        if (right <= left || bottom <= top) return null
        val width = right - left
        val height = bottom - top
        val samples = IntArray(width * height)
        for (y in top until bottom) {
            val rowOffset = (y - top) * width
            for (x in left until right) {
                samples[rowOffset + x - left] = accessor.sampleAt(x, y)
            }
        }
        return SourceTileWindow(left, top, width, height, samples)
    }
}
