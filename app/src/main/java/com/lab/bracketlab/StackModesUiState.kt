package com.lab.bracketlab

enum class StackProcessingSelection {
    FRAMES_ONLY,
    STACK,
    HDR,
    STAR_TRAIL
}

enum class StackAlignmentSelection {
    OFF,
    LANDSCAPE,
    STARS
}

enum class StackDitheringSelection {
    OFF,
    MANUAL_ASSISTED
}

/** Committed Stack Modes selection consumed by the production coordinator. */
data class StackModesUiState(
    val processing: StackProcessingSelection = StackProcessingSelection.FRAMES_ONLY,
    val alignment: StackAlignmentSelection = StackAlignmentSelection.OFF,
    val dithering: StackDitheringSelection = StackDitheringSelection.OFF,
    val useDarks: Boolean = false,
    val keepSourceFrames: Boolean = true,
    val outputDng: Boolean = true,
    val outputXisf: Boolean = false,
    val outputFits: Boolean = false
) {
    fun normalized(): StackModesUiState {
        if (processing == StackProcessingSelection.FRAMES_ONLY) {
            return copy(
                keepSourceFrames = true,
                useDarks = false,
                outputDng = true,
                outputXisf = false,
                outputFits = false
            )
        }
        if (processing == StackProcessingSelection.STAR_TRAIL) {
            return copy(
                alignment = StackAlignmentSelection.OFF,
                dithering = StackDitheringSelection.OFF
            ).withAtLeastOneOutput()
        }
        if (processing == StackProcessingSelection.HDR && alignment == StackAlignmentSelection.STARS) {
            return copy(
                alignment = StackAlignmentSelection.OFF,
                dithering = StackDitheringSelection.OFF,
                useDarks = false
            ).withAtLeastOneOutput()
        }
        if (processing == StackProcessingSelection.HDR) {
            return copy(useDarks = false).withAtLeastOneOutput()
        }
        val validDithering =
            dithering == StackDitheringSelection.OFF ||
                alignment == StackAlignmentSelection.STARS
        return copy(
            dithering =
                if (validDithering) dithering else StackDitheringSelection.OFF
        ).withAtLeastOneOutput()
    }

    private fun withAtLeastOneOutput(): StackModesUiState =
        if (outputDng || outputXisf || outputFits) this else copy(outputDng = true)
}
