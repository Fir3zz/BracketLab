package com.lab.bracketlab.processing.model

/**
 * Geometric transform convention:
 *
 * RawTransform maps TARGET/SOURCE frame coordinates into REFERENCE frame coordinates:
 *
 * referenceCoordinate = transform(targetCoordinate)
 *
 * The reference frame uses [IDENTITY]. Future resampling code may need the inverse transform
 * to sample a target frame for an output/reference pixel; do not mix that inverse sampling
 * transform with this forward target-to-reference convention.
 */
data class RawTransform(
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val rotationDegrees: Double = 0.0,
    val scale: Double = 1.0
) {
    companion object {
        val IDENTITY = RawTransform()
    }
}
