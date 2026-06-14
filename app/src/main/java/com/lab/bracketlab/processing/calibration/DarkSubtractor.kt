package com.lab.bracketlab.processing.calibration

import com.lab.bracketlab.processing.raw.BayerUtils

object DarkSubtractor {
    fun subtractDarkRaw16(
        lightSample: Int,
        masterDarkSample: Int,
        blackLevel: Int
    ): Int {
        val safeLight = lightSample.coerceIn(0, 65535)
        val safeDark = masterDarkSample.coerceIn(0, 65535)
        val safeBlack = blackLevel.coerceIn(0, 65535)
        val darkSignal = (safeDark - safeBlack).coerceAtLeast(0)
        return (safeLight - darkSignal)
            .coerceAtLeast(safeBlack)
            .coerceIn(0, 65535)
    }

    fun subtractDarkRaw16At(
        lightSample: Int,
        masterDarkSample: Int,
        blackLevelPattern: IntArray?,
        x: Int,
        y: Int
    ): Int =
        subtractDarkRaw16(
            lightSample = lightSample,
            masterDarkSample = masterDarkSample,
            blackLevel = BayerUtils.blackLevelAt(blackLevelPattern, x, y)
        )
}
