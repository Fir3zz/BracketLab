package com.lab.bracketlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureSequenceTest {
    private fun parseExposure(value: String): Long? =
        value.removeSuffix("s").toLongOrNull()?.times(1_000_000_000L)

    @Test
    fun pauseEverySplitsRepeatedExposureWithoutTrailingPause() {
        val expanded =
            ExposureSequenceParser.expand("15s+8, pause(4)", ::parseExposure)

        assertEquals(
            listOf("15s", "15s", "15s", "15s", "Pause", "15s", "15s", "15s", "15s"),
            expanded?.lines()
        )
    }

    @Test
    fun parsedSequenceCountsOnlyRealExposuresAsFrames() {
        val expanded =
            ExposureSequenceParser.expand("15s+8, pause(4)", ::parseExposure)!!
        val sequence = ExposureSequenceParser.parse(expanded, ::parseExposure)!!

        assertEquals(8, sequence.exposureCount)
        assertEquals(1, sequence.pauseCount)
        assertEquals(9, sequence.steps.size)
    }

    @Test
    fun intervalDoesNotAppendPauseAfterFinalFrame() {
        val expanded =
            ExposureSequenceParser.expand("15s+4, pause(4)", ::parseExposure)

        assertEquals(listOf("15s", "15s", "15s", "15s"), expanded?.lines())
    }

    @Test
    fun pauseIntervalContinuesAcrossMixedExposureGroups() {
        val expanded =
            ExposureSequenceParser.expand("15s+3, 30s+3, pause(4)", ::parseExposure)

        assertEquals(
            listOf("15s", "15s", "15s", "30s", "Pause", "30s", "30s"),
            expanded?.lines()
        )
    }

    @Test
    fun explicitPauseLineIsSupported() {
        val sequence =
            ExposureSequenceParser.parse("15s\nPause\n30s", ::parseExposure)!!

        assertEquals(2, sequence.exposureCount)
        assertEquals(1, sequence.pauseCount)
        assertTrue(sequence.steps[1] === ExposureSequenceStep.Pause)
    }

    @Test
    fun invalidPauseFormsAreRejected() {
        assertNull(ExposureSequenceParser.expand("15s+4, pause(0)", ::parseExposure))
        assertNull(
            ExposureSequenceParser.expand(
                "15s+4, pause(2), pause(3)",
                ::parseExposure
            )
        )
        assertNull(ExposureSequenceParser.parse("15s\nPause", ::parseExposure))
        assertNull(ExposureSequenceParser.parse("15s\nPause\nPause\n15s", ::parseExposure))
    }
}
