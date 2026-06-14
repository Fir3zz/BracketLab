package com.lab.bracketlab.processing.model

data class StackingConfig(
    val operation: StackingOperation = StackingOperation.OFF,
    val inputSource: InputSource = InputSource.CAMERA,
    val outputDepth: OutputDepth = OutputDepth.RAW16_16I,
    val alignToggle: AlignToggle = AlignToggle.OFF,
    val astroState: AstroState = AstroState.OFF,
    val darkPolicy: DarkPolicy = DarkPolicy.OFF,
    val robustStackingEnabled: Boolean = false,
    val debugExportEnabled: Boolean = false
) {
    fun isStackingActive(): Boolean = this != DEFAULT

    companion object {
        val DEFAULT = StackingConfig()
    }
}
