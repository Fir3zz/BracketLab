package com.lab.bracketlab.processing.align.star.warp

data class SourceTileWindow(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val samples: IntArray
) {
    val rightExclusive: Int
        get() = left + width
    val bottomExclusive: Int
        get() = top + height

    fun contains(x: Int, y: Int): Boolean =
        x >= left && y >= top && x < rightExclusive && y < bottomExclusive

    fun sampleAt(x: Int, y: Int): Int {
        require(contains(x, y))
        return samples[(y - top) * width + (x - left)]
    }
}

data class OutputTile(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    val rightExclusive: Int
        get() = left + width
    val bottomExclusive: Int
        get() = top + height
    val pixelCount: Int
        get() = width * height
}
