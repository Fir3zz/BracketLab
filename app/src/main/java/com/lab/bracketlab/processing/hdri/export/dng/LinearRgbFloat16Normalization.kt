package com.lab.bracketlab.processing.hdri.export.dng

import kotlin.math.ln

data class LinearRgbFloat16Normalization(
    val maxRgbBeforeScale: Double,
    val globalScale: Double,
    val baselineExposureEv: Double
) {
    companion object {
        fun fromMaximum(maxRgb: Double): LinearRgbFloat16Normalization {
            val safeMaximum = maxRgb.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            if (safeMaximum <= 1.0) {
                return LinearRgbFloat16Normalization(
                    maxRgbBeforeScale = safeMaximum,
                    globalScale = 1.0,
                    baselineExposureEv = 0.0
                )
            }
            return LinearRgbFloat16Normalization(
                maxRgbBeforeScale = safeMaximum,
                globalScale = 1.0 / safeMaximum,
                baselineExposureEv = ln(safeMaximum) / ln(2.0)
            )
        }
    }
}
