package com.lab.bracketlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StackModesUiStateTest {
    @Test
    fun framesOnlyAlwaysKeepsOriginalDngFrames() {
        val state =
            StackModesUiState(
                processing = StackProcessingSelection.FRAMES_ONLY,
                useDarks = true,
                keepSourceFrames = false,
                outputDng = false,
                outputXisf = true,
                outputFits = true
            ).normalized()

        assertTrue(state.keepSourceFrames)
        assertFalse(state.useDarks)
        assertTrue(state.outputDng)
        assertFalse(state.outputXisf)
        assertFalse(state.outputFits)
    }

    @Test
    fun starTrailForcesAlignmentAndDitheringOff() {
        val state =
            StackModesUiState(
                processing = StackProcessingSelection.STAR_TRAIL,
                alignment = StackAlignmentSelection.STARS,
                dithering = StackDitheringSelection.MANUAL_ASSISTED
            ).normalized()

        assertEquals(StackAlignmentSelection.OFF, state.alignment)
        assertEquals(StackDitheringSelection.OFF, state.dithering)
    }

    @Test
    fun internalStackCannotDitherWithoutStarAlignment() {
        val state =
            StackModesUiState(
                processing = StackProcessingSelection.STACK,
                alignment = StackAlignmentSelection.LANDSCAPE,
                dithering = StackDitheringSelection.MANUAL_ASSISTED
            ).normalized()

        assertEquals(StackDitheringSelection.OFF, state.dithering)
    }

    @Test
    fun framesOnlyMayUseManualDitheringForExternalProcessing() {
        val state =
            StackModesUiState(
                processing = StackProcessingSelection.FRAMES_ONLY,
                alignment = StackAlignmentSelection.OFF,
                dithering = StackDitheringSelection.MANUAL_ASSISTED
            ).normalized()

        assertEquals(StackDitheringSelection.MANUAL_ASSISTED, state.dithering)
    }

    @Test
    fun starAlignedStackMayUseManualDithering() {
        val state =
            StackModesUiState(
                processing = StackProcessingSelection.STACK,
                alignment = StackAlignmentSelection.STARS,
                dithering = StackDitheringSelection.MANUAL_ASSISTED
            ).normalized()

        assertEquals(StackDitheringSelection.MANUAL_ASSISTED, state.dithering)
    }

    @Test
    fun processedModeAlwaysHasAtLeastOneOutput() {
        val state =
            StackModesUiState(
                processing = StackProcessingSelection.HDR,
                outputDng = false,
                outputXisf = false,
                outputFits = false
            ).normalized()

        assertTrue(state.outputDng)
    }

    @Test
    fun hdrForcesStarAlignmentOff() {
        val state =
            StackModesUiState(
                processing = StackProcessingSelection.HDR,
                alignment = StackAlignmentSelection.STARS,
                dithering = StackDitheringSelection.MANUAL_ASSISTED
            ).normalized()

        assertEquals(StackAlignmentSelection.OFF, state.alignment)
        assertEquals(StackDitheringSelection.OFF, state.dithering)
    }

    @Test
    fun hdrAllowsLandscapeAlignment() {
        val state =
            StackModesUiState(
                processing = StackProcessingSelection.HDR,
                alignment = StackAlignmentSelection.LANDSCAPE
            ).normalized()

        assertEquals(StackAlignmentSelection.LANDSCAPE, state.alignment)
    }

    @Test
    fun hdrForcesDarkCalibrationOff() {
        val state =
            StackModesUiState(
                processing = StackProcessingSelection.HDR,
                useDarks = true
            ).normalized()

        assertFalse(state.useDarks)
    }

    @Test
    fun stackAndStarTrailMayUseDarks() {
        val stack =
            StackModesUiState(
                processing = StackProcessingSelection.STACK,
                useDarks = true
            ).normalized()
        val trail =
            StackModesUiState(
                processing = StackProcessingSelection.STAR_TRAIL,
                useDarks = true
            ).normalized()

        assertTrue(stack.useDarks)
        assertTrue(trail.useDarks)
    }
}
