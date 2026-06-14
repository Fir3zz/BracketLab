package com.lab.bracketlab.processing.align

import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack

object ReferenceFrameSelector {
    fun select(rawStack: RawStack): ReferenceFrameSelection {
        val frames = rawStack.frames
        if (frames.isEmpty()) {
            return ReferenceFrameSelection(
                selectedPosition = -1,
                selectedFrameIndex = -1,
                method = ReferenceSelectionMethod.NONE,
                referenceExposureTimeSeconds = null,
                failureReason = LandscapeAlignmentRejectionReason.EMPTY_STACK,
                message = "RawStack is empty."
            )
        }

        if (frames.size == 1) {
            val frame = frames.first()
            return ReferenceFrameSelection(
                selectedPosition = 0,
                selectedFrameIndex = frame.frameIndex,
                method = ReferenceSelectionMethod.ONLY_FRAME,
                referenceExposureTimeSeconds = validExposureSeconds(frame)
            )
        }

        val warnings = mutableListOf<String>()
        val exposures = frames.map { validExposureSeconds(it) }
        if (exposures.any { it == null }) {
            warnings += "Invalid or missing exposure metadata; using middle frame in original order."
            val selectedPosition = frames.size / 2
            val selected = frames[selectedPosition]
            return ReferenceFrameSelection(
                selectedPosition = selectedPosition,
                selectedFrameIndex = selected.frameIndex,
                method = ReferenceSelectionMethod.FALLBACK_MIDDLE_FRAME,
                referenceExposureTimeSeconds = validExposureSeconds(selected),
                warnings = warnings
            )
        }

        val validExposures = exposures.filterNotNull()
        val sameExposure = validExposures.all { it == validExposures.first() }
        if (sameExposure) {
            val selectedPosition = middleByFrameIndex(frames)
            val selected = frames[selectedPosition]
            return ReferenceFrameSelection(
                selectedPosition = selectedPosition,
                selectedFrameIndex = selected.frameIndex,
                method = ReferenceSelectionMethod.MIDDLE_FRAME_INDEX,
                referenceExposureTimeSeconds = validExposureSeconds(selected)
            )
        }

        val selectedPosition = medianExposurePosition(frames)
        val selected = frames[selectedPosition]
        return ReferenceFrameSelection(
            selectedPosition = selectedPosition,
            selectedFrameIndex = selected.frameIndex,
            method = ReferenceSelectionMethod.MEDIAN_EXPOSURE_TIME,
            referenceExposureTimeSeconds = validExposureSeconds(selected)
        )
    }

    private fun middleByFrameIndex(frames: List<RawFrame>): Int {
        val ordered =
            frames.withIndex()
                .sortedWith(compareBy<IndexedValue<RawFrame>> { it.value.frameIndex }.thenBy { it.index })
        // Even-sized stacks use the upper middle frame deterministically.
        return ordered[ordered.size / 2].index
    }

    private fun medianExposurePosition(frames: List<RawFrame>): Int {
        val ordered =
            frames.withIndex()
                .sortedWith(compareBy<IndexedValue<RawFrame>> { it.value.exposureTimeNs }.thenBy { it.index })
        // Even-sized exposure brackets use the upper median exposure deterministically.
        return ordered[ordered.size / 2].index
    }

    private fun validExposureSeconds(frame: RawFrame): Double? =
        frame.exposureTimeSeconds.takeIf { it > 0.0 && it.isUsableFinite() }

    private fun Double.isUsableFinite(): Boolean =
        !isNaN() && this != Double.POSITIVE_INFINITY && this != Double.NEGATIVE_INFINITY
}
