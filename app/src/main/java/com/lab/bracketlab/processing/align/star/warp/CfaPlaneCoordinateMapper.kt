package com.lab.bracketlab.processing.align.star.warp

import com.lab.bracketlab.processing.align.star.StarPoint

object CfaPlaneCoordinateMapper {
    fun fullToPlane(
        fullX: Double,
        fullY: Double,
        offset: CfaPhaseOffset
    ): StarPoint =
        StarPoint(
            x = (fullX - offset.x.toDouble()) * 0.5,
            y = (fullY - offset.y.toDouble()) * 0.5
        )

    fun planeToFull(
        planeX: Int,
        planeY: Int,
        offset: CfaPhaseOffset
    ): Pair<Int, Int> =
        Pair(
            planeX * 2 + offset.x,
            planeY * 2 + offset.y
        )
}
