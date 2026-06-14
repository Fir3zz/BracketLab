package com.lab.bracketlab.processing.hdri

data class HdrI32ExportOptions(
    val writeXisf32: Boolean = true,
    val writeFits32: Boolean = true,
    val writeLinearRgbFloat16Dng: Boolean = true
) {
    init {
        require(
            writeXisf32 ||
                writeFits32 ||
                writeLinearRgbFloat16Dng
        ) {
            "At least one HDR export format must be enabled."
        }
    }
}
