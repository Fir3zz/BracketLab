package com.lab.bracketlab.processing.hdri.export.dng

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Build
import android.util.Rational
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DngRational(
    val numerator: Int,
    val denominator: Int
) {
    init {
        require(denominator != 0)
    }
}

data class LinearRgbFloat16DngMetadata(
    val make: String,
    val model: String,
    val uniqueCameraModel: String,
    val colorMatrix1: List<DngRational>,
    val colorMatrix2: List<DngRational>?,
    val forwardMatrix1: List<DngRational>?,
    val forwardMatrix2: List<DngRational>?,
    val cameraCalibration1: List<DngRational>?,
    val cameraCalibration2: List<DngRational>?,
    val calibrationIlluminant1: Int,
    val calibrationIlluminant2: Int?,
    val asShotNeutral: List<DngRational>,
    val orientation: Int,
    val software: String,
    val dateTime: String,
    val baselineExposure: DngRational,
    val warnings: List<String> = emptyList()
)

enum class MissingColorMetadataBehavior {
    FAIL,
    WARN_AND_USE_NEUTRAL
}

data class LinearRgbFloat16MetadataResult(
    val success: Boolean,
    val metadata: LinearRgbFloat16DngMetadata? = null,
    val failureMessage: String? = null,
    val warnings: List<String> = emptyList()
)

object LinearRgbFloat16DngMetadataExtractor {
    fun extract(
        characteristics: CameraCharacteristics?,
        captureResult: CaptureResult?,
        orientation: Int?,
        appVersion: String?,
        missingBehavior: MissingColorMetadataBehavior =
            MissingColorMetadataBehavior.FAIL,
        baselineExposureEv: Double = 0.0
    ): LinearRgbFloat16MetadataResult {
        if (characteristics == null) {
            return LinearRgbFloat16MetadataResult(
                false,
                failureMessage = "CameraCharacteristics is required for Linear RGB DNG."
            )
        }
        if (captureResult == null) {
            return LinearRgbFloat16MetadataResult(
                false,
                failureMessage = "Reference CaptureResult is required for Linear RGB DNG."
            )
        }

        val warnings = mutableListOf<String>()
        val colorMatrix1 =
            characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
                ?.toDngMatrix()
        val illuminant1 =
            characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
        if (colorMatrix1 == null || illuminant1 == null) {
            return LinearRgbFloat16MetadataResult(
                false,
                failureMessage =
                    "SENSOR_COLOR_TRANSFORM1 and SENSOR_REFERENCE_ILLUMINANT1 are required."
            )
        }

        val neutral =
            captureResult.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
                ?.takeIf { it.size >= 3 }
                ?.take(3)
                ?.map { it.toDngRationalValue() }
                ?: when (missingBehavior) {
                    MissingColorMetadataBehavior.FAIL ->
                        return LinearRgbFloat16MetadataResult(
                            false,
                            failureMessage = "SENSOR_NEUTRAL_COLOR_POINT is required."
                        )
                    MissingColorMetadataBehavior.WARN_AND_USE_NEUTRAL -> {
                        warnings +=
                            "COLOR_METADATA_INCOMPLETE: AsShotNeutral unavailable; neutral 1,1,1 used."
                        listOf(
                            DngRational(1, 1),
                            DngRational(1, 1),
                            DngRational(1, 1)
                        )
                    }
                }

        val make = Build.MANUFACTURER.ifBlank { "unknown" }
        val model = Build.MODEL.ifBlank { "unknown" }
        val software =
            appVersion?.takeIf(String::isNotBlank)?.let { "BracketLab $it" }
                ?: "BracketLab"
        return LinearRgbFloat16MetadataResult(
            success = true,
            metadata = LinearRgbFloat16DngMetadata(
                make = make,
                model = model,
                uniqueCameraModel = "$model-$make-$make",
                colorMatrix1 = colorMatrix1,
                colorMatrix2 =
                    characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
                        ?.toDngMatrix(),
                forwardMatrix1 =
                    characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
                        ?.toDngMatrix(),
                forwardMatrix2 =
                    characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)
                        ?.toDngMatrix(),
                cameraCalibration1 =
                    characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)
                        ?.toDngMatrix(),
                cameraCalibration2 =
                    characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)
                        ?.toDngMatrix(),
                calibrationIlluminant1 = illuminant1,
                calibrationIlluminant2 =
                    characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)
                        ?.toInt()
                        ?.and(0xFF),
                asShotNeutral = neutral,
                orientation = orientation?.takeIf { it in 1..8 } ?: 1,
                software = software,
                dateTime =
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                        .format(Date()),
                baselineExposure = decimalToDngRational(baselineExposureEv),
                warnings = warnings
            ),
            warnings = warnings
        )
    }

    private fun ColorSpaceTransform.toDngMatrix(): List<DngRational> =
        buildList(9) {
            for (row in 0..2) {
                for (column in 0..2) {
                    add(getElement(column, row).toDngRationalValue())
                }
            }
        }

    private fun Rational.toDngRationalValue(): DngRational =
        DngRational(numerator, denominator)

}

internal fun decimalToDngRational(value: Double): DngRational {
    require(value.isFinite())
    val denominator = 1_000_000
    val numerator =
        (value * denominator.toDouble())
            .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
            .toInt()
    return DngRational(numerator, denominator)
}
