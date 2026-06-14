package com.lab.bracketlab.processing.raw

import android.hardware.camera2.CameraCharacteristics

enum class CfaPattern {
    RGGB,
    GRBG,
    GBRG,
    BGGR,
    MONO,
    UNKNOWN
}

enum class CfaColor {
    RED,
    GREEN_RED_ROW,
    GREEN_BLUE_ROW,
    BLUE,
    MONO,
    UNKNOWN
}

object BayerUtils {
    fun fromCameraCharacteristicsValue(value: Int?): CfaPattern =
        when (value) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> CfaPattern.RGGB
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> CfaPattern.GRBG
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> CfaPattern.GBRG
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> CfaPattern.BGGR
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> CfaPattern.MONO
            else -> CfaPattern.UNKNOWN
        }

    fun colorAt(pattern: CfaPattern?, x: Int, y: Int): CfaColor {
        val safePattern = pattern ?: CfaPattern.UNKNOWN
        if (safePattern == CfaPattern.MONO) return CfaColor.MONO
        if (safePattern == CfaPattern.UNKNOWN) return CfaColor.UNKNOWN

        val row = y and 1
        val col = x and 1
        return when (safePattern) {
            CfaPattern.RGGB ->
                if (row == 0) {
                    if (col == 0) CfaColor.RED else CfaColor.GREEN_RED_ROW
                } else {
                    if (col == 0) CfaColor.GREEN_BLUE_ROW else CfaColor.BLUE
                }

            CfaPattern.GRBG ->
                if (row == 0) {
                    if (col == 0) CfaColor.GREEN_RED_ROW else CfaColor.RED
                } else {
                    if (col == 0) CfaColor.BLUE else CfaColor.GREEN_BLUE_ROW
                }

            CfaPattern.GBRG ->
                if (row == 0) {
                    if (col == 0) CfaColor.GREEN_BLUE_ROW else CfaColor.BLUE
                } else {
                    if (col == 0) CfaColor.RED else CfaColor.GREEN_RED_ROW
                }

            CfaPattern.BGGR ->
                if (row == 0) {
                    if (col == 0) CfaColor.BLUE else CfaColor.GREEN_BLUE_ROW
                } else {
                    if (col == 0) CfaColor.GREEN_RED_ROW else CfaColor.RED
                }

            CfaPattern.MONO -> CfaColor.MONO
            CfaPattern.UNKNOWN -> CfaColor.UNKNOWN
        }
    }

    fun blackLevelAt(blackLevelPattern: IntArray?, x: Int, y: Int, fallback: Int = 0): Int {
        if (blackLevelPattern == null || blackLevelPattern.size < 4) return fallback
        val index = (y and 1) * 2 + (x and 1)
        return blackLevelPattern[index]
    }
}
