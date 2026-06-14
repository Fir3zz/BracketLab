package com.lab.bracketlab.processing.hdri.export.dng

import com.lab.bracketlab.processing.hdri.HdrI32Frame
import java.io.File

data class LinearRgbFloat16DngExportOptions(
    val rowsPerStrip: Int = 64,
    val referenceExposureTimeNs: Long? = null,
    val missingColorMetadataBehavior: MissingColorMetadataBehavior =
        MissingColorMetadataBehavior.FAIL,
    val baselineExposureEv: Double = 0.0,
    val overwrite: Boolean = false
) {
    init {
        require(rowsPerStrip in 1..1024)
        require(referenceExposureTimeNs == null || referenceExposureTimeNs > 0L)
        require(baselineExposureEv.isFinite())
    }
}

data class LinearRgbFloat16DngWriteRequest(
    val hdrFrame: HdrI32Frame,
    val metadata: LinearRgbFloat16DngMetadata,
    val outputFile: File,
    val options: LinearRgbFloat16DngExportOptions =
        LinearRgbFloat16DngExportOptions()
)
