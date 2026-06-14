package com.lab.bracketlab.processing.align.star.warp

import kotlin.math.abs
import kotlin.math.floor

enum class CfaSamplingPath {
    DIRECT,
    HORIZONTAL_LINEAR,
    VERTICAL_LINEAR,
    BILINEAR,
    INVALID
}

data class CfaWarpedSample(
    val valid: Boolean,
    val value: Double = 0.0,
    val path: CfaSamplingPath = CfaSamplingPath.INVALID
)

class CfaSafeSimilaritySampler(
    private val layout: CfaPhaseLayout,
    private val integralEpsilon: Double
) {
    fun sample(
        outputX: Int,
        outputY: Int,
        inverse: SimilarityTransformInverse,
        window: SourceTileWindow,
        sourceWidth: Int,
        sourceHeight: Int,
        sampleProvider: (Int, Int) -> Int
    ): CfaWarpedSample {
        val phase = layout.phaseAt(outputX, outputY)
        val offset = layout.offsetOf(phase)
        val targetFull =
            inverse.mapReferenceToTarget(outputX.toDouble(), outputY.toDouble())
        if (!targetFull.x.isFinite() || !targetFull.y.isFinite()) return CfaWarpedSample(false)
        val plane = CfaPlaneCoordinateMapper.fullToPlane(targetFull.x, targetFull.y, offset)
        val nearestU = kotlin.math.round(plane.x)
        val nearestV = kotlin.math.round(plane.y)
        val integralU = abs(plane.x - nearestU) <= integralEpsilon
        val integralV = abs(plane.y - nearestV) <= integralEpsilon
        val u0 = if (integralU) nearestU.toInt() else floor(plane.x).toInt()
        val v0 = if (integralV) nearestV.toInt() else floor(plane.y).toInt()
        val u1 = u0 + 1
        val v1 = v0 + 1

        fun read(u: Int, v: Int): Double? {
            val (x, y) = CfaPlaneCoordinateMapper.planeToFull(u, v, offset)
            if (x !in 0 until sourceWidth || y !in 0 until sourceHeight || !window.contains(x, y)) {
                return null
            }
            return sampleProvider(x, y).toDouble()
        }

        if (integralU && integralV) {
            return read(u0, v0)?.let { CfaWarpedSample(true, it, CfaSamplingPath.DIRECT) }
                ?: CfaWarpedSample(false)
        }
        val fu = plane.x - u0
        val fv = plane.y - v0
        if (!integralU && integralV) {
            val a = read(u0, v0) ?: return CfaWarpedSample(false)
            val b = read(u1, v0) ?: return CfaWarpedSample(false)
            return CfaWarpedSample(
                true,
                a * (1.0 - fu) + b * fu,
                CfaSamplingPath.HORIZONTAL_LINEAR
            )
        }
        if (integralU) {
            val a = read(u0, v0) ?: return CfaWarpedSample(false)
            val b = read(u0, v1) ?: return CfaWarpedSample(false)
            return CfaWarpedSample(
                true,
                a * (1.0 - fv) + b * fv,
                CfaSamplingPath.VERTICAL_LINEAR
            )
        }
        val p00 = read(u0, v0) ?: return CfaWarpedSample(false)
        val p10 = read(u1, v0) ?: return CfaWarpedSample(false)
        val p01 = read(u0, v1) ?: return CfaWarpedSample(false)
        val p11 = read(u1, v1) ?: return CfaWarpedSample(false)
        val top = p00 * (1.0 - fu) + p10 * fu
        val bottom = p01 * (1.0 - fu) + p11 * fu
        return CfaWarpedSample(
            true,
            top * (1.0 - fv) + bottom * fv,
            CfaSamplingPath.BILINEAR
        )
    }
}
