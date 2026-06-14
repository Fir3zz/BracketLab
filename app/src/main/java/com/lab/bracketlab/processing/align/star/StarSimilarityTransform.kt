package com.lab.bracketlab.processing.align.star

import com.lab.bracketlab.processing.model.RawTransform
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Maps TARGET/SOURCE full-resolution RAW coordinates into REFERENCE coordinates:
 *
 * xRef = a * xTarget - b * yTarget + tx
 * yRef = b * xTarget + a * yTarget + ty
 */
data class StarSimilarityTransform(
    val a: Double,
    val b: Double,
    val tx: Double,
    val ty: Double
) {
    val scale: Double
        get() = sqrt(a * a + b * b)

    val rotationDegrees: Double
        get() = Math.toDegrees(atan2(b, a))

    val matrix2x3: DoubleArray
        get() = doubleArrayOf(a, -b, tx, b, a, ty)

    fun map(x: Double, y: Double): StarPoint =
        StarPoint(
            x = a * x - b * y + tx,
            y = b * x + a * y + ty
        )

    fun inverseOrNull(): StarSimilarityTransform? {
        val determinant = a * a + b * b
        if (!determinant.isFinite() || determinant <= 1e-18) return null
        val inverseA = a / determinant
        val inverseB = -b / determinant
        return StarSimilarityTransform(
            a = inverseA,
            b = inverseB,
            tx = -inverseA * tx + inverseB * ty,
            ty = -inverseB * tx - inverseA * ty
        )
    }

    fun toRawTransform(): RawTransform =
        RawTransform(
            dx = tx,
            dy = ty,
            rotationDegrees = rotationDegrees,
            scale = scale
        )

    fun isFinite(): Boolean =
        a.isFinite() && b.isFinite() && tx.isFinite() && ty.isFinite() &&
            scale.isFinite() && rotationDegrees.isFinite()

    companion object {
        val IDENTITY = StarSimilarityTransform(1.0, 0.0, 0.0, 0.0)

        fun fromParameters(
            tx: Double,
            ty: Double,
            rotationDegrees: Double,
            scale: Double
        ): StarSimilarityTransform {
            val radians = Math.toRadians(rotationDegrees)
            return StarSimilarityTransform(
                a = scale * cos(radians),
                b = scale * sin(radians),
                tx = tx,
                ty = ty
            )
        }
    }
}

data class StarPoint(
    val x: Double,
    val y: Double
)
