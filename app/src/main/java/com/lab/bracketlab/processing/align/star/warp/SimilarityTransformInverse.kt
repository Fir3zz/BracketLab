package com.lab.bracketlab.processing.align.star.warp

import com.lab.bracketlab.processing.align.star.StarPoint
import com.lab.bracketlab.processing.align.star.StarSimilarityTransform

data class SimilarityTransformInverse(
    val a: Double,
    val b: Double,
    val tx: Double,
    val ty: Double
) {
    fun mapReferenceToTarget(x: Double, y: Double): StarPoint =
        StarPoint(
            x = a * x - b * y + tx,
            y = b * x + a * y + ty
        )

    fun isFinite(): Boolean =
        a.isFinite() && b.isFinite() && tx.isFinite() && ty.isFinite()

    companion object {
        fun fromTargetToReference(transform: StarSimilarityTransform): SimilarityTransformInverse? {
            if (!transform.isFinite()) return null
            val determinant = transform.a * transform.a + transform.b * transform.b
            if (!determinant.isFinite() || determinant <= 1e-18) return null
            val inverseA = transform.a / determinant
            val inverseB = -transform.b / determinant
            return SimilarityTransformInverse(
                a = inverseA,
                b = inverseB,
                tx = -inverseA * transform.tx + inverseB * transform.ty,
                ty = -inverseB * transform.tx - inverseA * transform.ty
            ).takeIf { it.isFinite() }
        }
    }
}
