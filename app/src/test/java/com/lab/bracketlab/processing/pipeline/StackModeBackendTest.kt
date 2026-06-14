package com.lab.bracketlab.processing.pipeline

import com.lab.bracketlab.processing.align.IdentityAlignmentReportFactory
import com.lab.bracketlab.processing.hdri.HdrI32AlignmentMode
import com.lab.bracketlab.processing.hdri.HdrI32HighlightCoherencePolicy
import com.lab.bracketlab.processing.hdri.HdrI32MergeOptions
import com.lab.bracketlab.processing.hdri.HdrI32Merger
import com.lab.bracketlab.processing.hdri.HdrI32WeightPolicy
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.stack.RawStackAggregationMode
import com.lab.bracketlab.processing.stack.RawStackAggregationOptions
import com.lab.bracketlab.processing.stack.RawStackAggregator
import com.lab.bracketlab.processing.stack.AlignedRaw16StackProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StackModeBackendTest {
    @Test
    fun alignmentOffIdentityIsAcceptedByRaw16StackProcessor() {
        val first = frame(0, 100_000_000L, intArrayOf(100, 200, 300, 400, 500, 600, 700, 800))
        val second = frame(1, 100_000_000L, intArrayOf(200, 300, 400, 500, 600, 700, 800, 900))
        val stack = RawStack(listOf(first, second), cameraId = "0")
        val identity = IdentityAlignmentReportFactory.create(stack)

        val result = AlignedRaw16StackProcessor().process(stack, identity)

        assertTrue(result.fatalMessage, result.success)
        assertTrue(identity.frameResults.all {
            it.alignmentResult.mode ==
                com.lab.bracketlab.processing.model.ResolvedAlignmentMode.LANDSCAPE_TRANSLATION
        })
    }

    @Test
    fun starTrailMaximumKeepsBrightestRaw16Sample() {
        val result =
            RawStackAggregator.aggregate(
                intArrayOf(64, 512, 128, 1023),
                4,
                RawStackAggregationOptions(mode = RawStackAggregationMode.MAXIMUM)
            )

        assertEquals(1023, result.outputValue)
        assertEquals(RawStackAggregationMode.MAXIMUM, result.appliedMode)
        assertEquals(4, result.acceptedSampleCount)
    }

    @Test
    fun landscapeTranslationIsAppliedBeforeHdrRadianceMerge() {
        val reference =
            frame(
                index = 0,
                exposureNs = 100_000_000L,
                values = intArrayOf(100, 100, 100, 100, 100, 100, 100, 100)
            )
        val target =
            frame(
                index = 1,
                exposureNs = 50_000_000L,
                values = intArrayOf(0, 0, 50, 50, 50, 50, 0, 0)
            )
        val stack = RawStack(listOf(reference, target), cameraId = "0")
        val identity = IdentityAlignmentReportFactory.create(stack)
        val translatedFrames =
            identity.frameResults.map {
                if (it.frameIndex == target.frameIndex) {
                    val transform = RawTransform(dx = -2.0, dy = 0.0)
                    it.copy(
                        dxRawPixels = transform.dx,
                        dyRawPixels = transform.dy,
                        alignmentResult =
                            it.alignmentResult.copy(
                                transform = transform,
                                rawDx = transform.dx,
                                rawDy = transform.dy
                            )
                    )
                } else {
                    it
                }
            }
        val alignment =
            identity.copy(
                frameResults = translatedFrames,
                alignmentResults = translatedFrames.map { it.alignmentResult }
            )
        val directory =
            File(
                System.getProperty("java.io.tmpdir"),
                "bracketlab_hdr_align_${System.nanoTime()}"
            ).also(File::mkdirs)
        try {
            val merged =
                HdrI32Merger(directory).merge(
                    rawStack = stack,
                    options =
                        HdrI32MergeOptions(
                            weightPolicy = HdrI32WeightPolicy.UNIFORM_VALID_RADIANCE,
                            highlightCoherencePolicy =
                                HdrI32HighlightCoherencePolicy.PER_SAMPLE,
                            alignmentMode = HdrI32AlignmentMode.LANDSCAPE_TRANSLATION
                        ),
                    alignmentReport = alignment
                )

            assertTrue(merged.failureMessage, merged.success)
            val frame = requireNotNull(merged.frame)
            assertEquals(
                HdrI32AlignmentMode.LANDSCAPE_TRANSLATION,
                frame.metadata.alignmentMode
            )
            val first =
                ByteBuffer.wrap(frame.storageFile.readBytes())
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .float
            assertEquals(1000.0f, first, 0.01f)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun frame(index: Int, exposureNs: Long, values: IntArray): RawFrame {
        val bytes =
            ByteBuffer.allocate(values.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply { values.forEach { putShort(it.toShort()) } }
                .array()
        return RawFrame(
            width = 4,
            height = 2,
            raw16 = bytes,
            rowStride = 8,
            pixelStride = 2,
            exposureTimeNs = exposureNs,
            iso = 100,
            cameraId = "0",
            frameIndex = index,
            blackLevelPattern = intArrayOf(0, 0, 0, 0),
            whiteLevel = 1023,
            cfaPattern = CfaPattern.RGGB
        )
    }
}
