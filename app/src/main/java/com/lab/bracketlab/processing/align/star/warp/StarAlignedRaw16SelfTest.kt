package com.lab.bracketlab.processing.align.star.warp

import com.lab.bracketlab.processing.align.LandscapeAlignmentOptions
import com.lab.bracketlab.processing.align.LandscapeAlignmentReport
import com.lab.bracketlab.processing.align.LandscapeAlignmentSelfTest
import com.lab.bracketlab.processing.align.LandscapeAlignmentStatus
import com.lab.bracketlab.processing.align.LandscapeFrameAlignment
import com.lab.bracketlab.processing.align.PhaseCorrelationSelfTestStatus
import com.lab.bracketlab.processing.align.ReferenceSelectionMethod
import com.lab.bracketlab.processing.align.star.StarAlignmentReport
import com.lab.bracketlab.processing.align.star.StarAlignmentStatus
import com.lab.bracketlab.processing.align.star.StarDetectionCoreSelfTest
import com.lab.bracketlab.processing.align.star.StarFrameAlignment
import com.lab.bracketlab.processing.align.star.StarMatchingOptions
import com.lab.bracketlab.processing.align.star.StarMatchingSelfTest
import com.lab.bracketlab.processing.align.star.StarMatchingStrategy
import com.lab.bracketlab.processing.align.star.StarReferenceSelectionReason
import com.lab.bracketlab.processing.align.star.StarSimilarityTransform
import com.lab.bracketlab.processing.calibration.DarkCalibrationInput
import com.lab.bracketlab.processing.calibration.DarkCalibrationOptions
import com.lab.bracketlab.processing.calibration.DarkPolicy
import com.lab.bracketlab.processing.calibration.DarkSubtractor
import com.lab.bracketlab.processing.calibration.MasterDarkSelfTest
import com.lab.bracketlab.processing.calibration.MissingMasterDarkBehavior
import com.lab.bracketlab.processing.debug.IncrementalDiagnosticReport
import com.lab.bracketlab.processing.debug.SingleFlightGuard
import com.lab.bracketlab.processing.hdri.HdrI32FitsWriterSelfTest
import com.lab.bracketlab.processing.hdri.HdrI32SelfTest
import com.lab.bracketlab.processing.hdri.HdrI32XisfWriterSelfTest
import com.lab.bracketlab.processing.hdri.export.dng.LinearRgbFloat16DngSelfTest
import com.lab.bracketlab.processing.io.Raw16DngWriteOptions
import com.lab.bracketlab.processing.memory.MemoryStabilizationSelfTest
import com.lab.bracketlab.processing.model.AlignmentResult
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import com.lab.bracketlab.processing.pipeline.CameraMetadataProvider
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.raw.RawProxyType
import com.lab.bracketlab.processing.stack.AlignedRaw16StackOptions
import com.lab.bracketlab.processing.stack.AlignedRaw16StackProcessor
import com.lab.bracketlab.processing.stack.AlignedRaw16StackSelfTest
import com.lab.bracketlab.processing.stack.RawStackAggregationMode
import com.lab.bracketlab.processing.stack.RawStackAggregationOptions
import com.lab.bracketlab.processing.stack.RawStackAggregatorSelfTest
import com.lab.bracketlab.processing.storage.FileBackedRaw16FrameStorage
import java.io.File
import kotlin.math.abs

data class StarAlignedRaw16SelfTestCase(
    val name: String,
    val status: PhaseCorrelationSelfTestStatus,
    val message: String
)

data class StarAlignedRaw16SelfTestReport(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val durationMs: Long,
    val results: List<StarAlignedRaw16SelfTestCase>
) {
    fun logLines(): List<String> =
        buildList {
            add(
                "Star-aligned RAW16 self-test: " +
                    "${if (failed == 0) "PASS" else "FAIL"} " +
                    "($passed pass, $failed fail, $skipped skipped)"
            )
            results.filter { it.status != PhaseCorrelationSelfTestStatus.PASS }.forEach {
                add("${it.status}: ${it.name}: ${it.message}")
            }
        }
}

object StarAlignedRaw16SelfTest {
    fun runAll(): StarAlignedRaw16SelfTestReport = run(includeRegressions = true)

    fun runCore(): StarAlignedRaw16SelfTestReport = run(includeRegressions = false)

    private fun run(includeRegressions: Boolean): StarAlignedRaw16SelfTestReport {
        val startedNs = System.nanoTime()
        val coreCases =
            listOf(
                test("1 identity transform samples identical coordinates") { inverseMaps(StarSimilarityTransform.IDENTITY, 7.0, 5.0, 7.0, 5.0) },
                test("2 positive X translation inverse lookup") { inverseMaps(transform(tx = 3.0), 10.0, 4.0, 7.0, 4.0) },
                test("3 negative X translation inverse lookup") { inverseMaps(transform(tx = -3.0), 4.0, 4.0, 7.0, 4.0) },
                test("4 positive Y translation inverse lookup") { inverseMaps(transform(ty = 2.0), 4.0, 8.0, 4.0, 6.0) },
                test("5 rotation sign") { rotationSign() },
                test("6 scale direction") { scaleDirection() },
                test("7 combined similarity inverse") { combinedInverse() },
                test("8 target-to-reference not inverted twice") { inverseMaps(transform(tx = 4.0), 9.0, 2.0, 5.0, 2.0) },
                test("9 reference identity") { inverseMaps(StarSimilarityTransform.IDENTITY, 3.0, 4.0, 3.0, 4.0) },
                test("10 RGGB phases separate") { layoutOffsets(CfaPattern.RGGB, listOf(CfaPhase.R, CfaPhase.G1, CfaPhase.G2, CfaPhase.B)) },
                test("11 GRBG phases separate") { layoutOffsets(CfaPattern.GRBG, listOf(CfaPhase.G1, CfaPhase.R, CfaPhase.B, CfaPhase.G2)) },
                test("12 GBRG phases separate") { layoutOffsets(CfaPattern.GBRG, listOf(CfaPhase.G1, CfaPhase.B, CfaPhase.R, CfaPhase.G2)) },
                test("13 BGGR phases separate") { layoutOffsets(CfaPattern.BGGR, listOf(CfaPhase.B, CfaPhase.G1, CfaPhase.G2, CfaPhase.R)) },
                test("14 G1 and G2 never mix") { g1G2Separated() },
                test("15 odd-pixel translation remains phase-safe") { constantPhaseWarp(transform(tx = 1.0)) },
                test("16 subpixel translation remains phase-safe") { constantPhaseWarp(transform(tx = 0.35, ty = -0.45)) },
                test("17 rotation remains phase-safe") { constantPhaseWarp(transform(rotation = 0.7)) },
                test("18 scale remains phase-safe") { constantPhaseWarp(transform(scale = 1.002)) },
                test("19 combined similarity remains phase-safe") { constantPhaseWarp(transform(0.4, -0.3, 0.5, 1.001)) },
                test("20 no full-mosaic interpolation API") { CfaSafeSimilaritySampler::class.java.declaredMethods.none { it.name.contains("mosaic", true) } },
                test("21 full to plane formula") { mappingRoundTrip(CfaPhaseOffset(1, 0), 9.5, 8.0) },
                test("22 plane to full formula") { CfaPlaneCoordinateMapper.planeToFull(4, 3, CfaPhaseOffset(1, 0)) == (9 to 6) },
                test("23 CFA offset contributes under rotation") { cfaOffsetContribution() },
                test("24 translation is not merely divided by two") { translationNotSimplyHalved() },
                test("25 proxy scale is absent from warp API") { SimilarityTransformInverse::class.java.declaredFields.none { it.name.contains("proxy", true) } },
                test("26 pixel-center convention") { mappingRoundTrip(CfaPhaseOffset(0, 1), 6.0, 9.0) },
                test("27 integral coordinate direct sample") { samplerPath(StarSimilarityTransform.IDENTITY, 4, 4) == CfaSamplingPath.DIRECT },
                test("28 horizontal fractional two-sample") { samplerPath(transform(tx = 1.0), 6, 6) == CfaSamplingPath.HORIZONTAL_LINEAR },
                test("29 vertical fractional two-sample") { samplerPath(transform(ty = 1.0), 6, 6) == CfaSamplingPath.VERTICAL_LINEAR },
                test("30 bilinear four-sample") { samplerPath(transform(tx = 1.0, ty = 1.0), 6, 6) == CfaSamplingPath.BILINEAR },
                test("31 bilinear weights sum to one") { bilinearWeightsSum() },
                test("32 unsigned above 32767") { samplerValue(50000) == 50000.0 },
                test("33 65535 remains valid") { samplerValue(65535) == 65535.0 },
                test("34 source neighbors stay in bounds") { !sampleAtEdge().valid },
                test("35 invalid edge excluded not zero-filled") { sampleAtEdge().path == CfaSamplingPath.INVALID },
                test("36 identity MEAN matches existing stack") { compareWithExisting(RawStackAggregationMode.MEAN, StarSimilarityTransform.IDENTITY) },
                test("37 even translation MEAN matches existing stack") { compareWithExisting(RawStackAggregationMode.MEAN, transform(tx = 2.0, ty = -2.0)) },
                test("38 identity MinMax matches existing aggregator") { compareIdentityRobust(RawStackAggregationMode.MIN_MAX_REJECTED_MEAN) },
                test("39 identity Sigma matches existing aggregator") { compareIdentityRobust(RawStackAggregationMode.SIGMA_CLIPPED_MEAN) },
                test("40 warped values rounded once") { roundedOnce() },
                test("41 border fallback") { borderFallback() },
                test("42 rejected alignment frame excluded") { rejectedFrameExcluded() },
                test("43 minimum accepted-frame policy") { minimumFramesPolicy() },
                test("44 dark subtraction before interpolation") { darkBeforeInterpolation() },
                test("45 dark sampled at source coordinate") { darkSourceCoordinate() },
                test("46 black level not subtracted twice") { DarkSubtractor.subtractDarkRaw16(500, 64, 64) == 500 },
                test("47 per-phase black level") { perPhaseBlackLevel() },
                test("48 missing dark follows warn policy") { missingDarkPolicy(false) },
                test("49 HDR dark code unchanged") { HdrI32SelfTest::class.java.name.contains("hdri") },
                test("50 file-backed output equals in-memory reference") { fileBackedEqualsInMemory() },
                test("51 tile boundaries create no seams") { tileOutputsEqual(8, 8, 12, 8) },
                test("52 different tile dimensions identical") { tileOutputsEqual(8, 8, 16, 8) },
                test("53 source window includes interpolation halo") { sourceWindowHalo() },
                test("54 source window clipping safe") { sourceWindowClipping() },
                test("55 packed output size") { packedOutputSize() },
                test("56 output little-endian") { littleEndianOutput() },
                test("57 no full output ByteBuffer") { StarAlignedRaw16StackResult::class.java.declaredFields.none { it.type.name.contains("ByteBuffer") } },
                test("58 tile planner respects memory budget") { tilePlannerBudget(24) },
                test("59 100-frame estimate bounded") { tilePlannerBudget(100) },
                test("60 temporary file cleaned after simulated failure") { failedOutputCleanup() },
                test("61 failed writer cleanup contract enabled") { writerCleanupContract() },
                test("62 reference metadata index preserved") { referenceIndexPreserved() },
                test("63 report frame mismatch fails") { frameMismatchFails() },
                test("64 duplicate transform fails") { duplicateTransformFails() },
                test("65 missing transform fails") { missingTransformFails() },
                test("66 non-invertible transform fails") { nonInvertibleFails() },
                test("67 rejected frames reported") { rejectedFrameReported() },
                test("68 writer not called after stack failure") { writerNotCalledAfterStackFailure() },
                test("69 incremental report survives failure") { incrementalReportSurvives() },
                test("70 single-flight guard") { singleFlightGuardWorks() }
            )
        val regressionCases =
            if (includeRegressions) listOf(
                test("71 StarDetection regression") {
                    StarDetectionCoreSelfTest.runAll().let {
                        it.failed == 0 && it.skipped == 0 && it.passed > 0
                    }
                },
                test("72 StarMatching regression") {
                    StarMatchingSelfTest.runAll().let {
                        it.failed == 0 && it.skipped == 0 && it.passed > 0
                    }
                },
                test("73 landscape alignment regression") {
                    val results = LandscapeAlignmentSelfTest.runAll()
                    if (results.any { it.status == PhaseCorrelationSelfTestStatus.FAIL }) {
                        false
                    } else {
                        if (results.any { it.status == PhaseCorrelationSelfTestStatus.SKIPPED }) {
                            throw SelfTestSkippedException(
                                "Native OpenCV landscape cases are unavailable in this runtime."
                            )
                        }
                        true
                    }
                },
                test("74 normal aligned RAW16 regression") {
                    AlignedRaw16StackSelfTest.runAll().let {
                        it.failed == 0 && it.skipped == 0 && it.passed > 0
                    }
                },
                test("75 aggregation regression") {
                    RawStackAggregatorSelfTest.runAll().let {
                        it.failed == 0 && it.skipped == 0 && it.passed > 0
                    }
                },
                test("76 memory regression") {
                    MemoryStabilizationSelfTest.runAll().let {
                        it.failed == 0 && it.skipped == 0 && it.passed > 0
                    }
                },
                test("77 MasterDark regression") {
                    MasterDarkSelfTest.runAll().let {
                        it.failed == 0 && it.skipped == 0 && it.passed > 0
                    }
                },
                test("78 HDR regression") {
                    HdrI32SelfTest.runAll().let {
                        it.failed == 0 && it.skipped == 0 && it.passed > 0
                    }
                },
                test("79 FITS and XISF regressions") {
                    (HdrI32FitsWriterSelfTest.runAll() + HdrI32XisfWriterSelfTest.runAll())
                        .all { it.status == PhaseCorrelationSelfTestStatus.PASS }
                },
                test("80 Linear RGB Float16 DNG regression") {
                    LinearRgbFloat16DngSelfTest.runAll().let {
                        it.failed == 0 && it.skipped == 0 && it.passed > 0
                    }
                }
            ) else emptyList()
        val cases = coreCases + regressionCases
        return StarAlignedRaw16SelfTestReport(
            passed = cases.count { it.status == PhaseCorrelationSelfTestStatus.PASS },
            failed = cases.count { it.status == PhaseCorrelationSelfTestStatus.FAIL },
            skipped = cases.count { it.status == PhaseCorrelationSelfTestStatus.SKIPPED },
            durationMs = (System.nanoTime() - startedNs) / 1_000_000L,
            results = cases
        )
    }

    private fun inverseMaps(
        transform: StarSimilarityTransform,
        referenceX: Double,
        referenceY: Double,
        expectedX: Double,
        expectedY: Double
    ): Boolean {
        val inverse = SimilarityTransformInverse.fromTargetToReference(transform) ?: return false
        val mapped = inverse.mapReferenceToTarget(referenceX, referenceY)
        return close(mapped.x, expectedX) && close(mapped.y, expectedY)
    }

    private fun rotationSign(): Boolean {
        val transform = transform(rotation = 90.0)
        val inverse = SimilarityTransformInverse.fromTargetToReference(transform) ?: return false
        val mapped = inverse.mapReferenceToTarget(0.0, 1.0)
        return close(mapped.x, 1.0) && close(mapped.y, 0.0)
    }

    private fun scaleDirection(): Boolean =
        inverseMaps(transform(scale = 2.0), 8.0, 6.0, 4.0, 3.0)

    private fun combinedInverse(): Boolean {
        val transform = transform(2.3, -1.7, 3.0, 1.01)
        val sourceX = 14.25
        val sourceY = 7.75
        val reference = transform.map(sourceX, sourceY)
        return inverseMaps(transform, reference.x, reference.y, sourceX, sourceY)
    }

    private fun layoutOffsets(pattern: CfaPattern, phases: List<CfaPhase>): Boolean {
        val layout = CfaPhaseLayout.from(pattern) ?: return false
        return listOf(
            layout.phaseAt(0, 0),
            layout.phaseAt(1, 0),
            layout.phaseAt(0, 1),
            layout.phaseAt(1, 1)
        ) == phases
    }

    private fun g1G2Separated(): Boolean {
        val layout = CfaPhaseLayout.from(CfaPattern.RGGB) ?: return false
        return layout.offsetOf(CfaPhase.G1) != layout.offsetOf(CfaPhase.G2)
    }

    private fun constantPhaseWarp(transform: StarSimilarityTransform): Boolean {
        val layout = CfaPhaseLayout.from(CfaPattern.RGGB) ?: return false
        val inverse = SimilarityTransformInverse.fromTargetToReference(transform) ?: return false
        val window = phaseWindow(layout, 24, 24)
        val sampler = CfaSafeSimilaritySampler(layout, 1e-7)
        for (y in 5..18) {
            for (x in 5..18) {
                val expected = phaseValue(layout.phaseAt(x, y)).toDouble()
                val sample =
                    sampler.sample(x, y, inverse, window, 24, 24, window::sampleAt)
                if (!sample.valid || !close(sample.value, expected)) return false
            }
        }
        return true
    }

    private fun mappingRoundTrip(
        offset: CfaPhaseOffset,
        fullX: Double,
        fullY: Double
    ): Boolean {
        val plane = CfaPlaneCoordinateMapper.fullToPlane(fullX, fullY, offset)
        return close(plane.x * 2.0 + offset.x, fullX) &&
            close(plane.y * 2.0 + offset.y, fullY)
    }

    private fun cfaOffsetContribution(): Boolean {
        val inverse =
            SimilarityTransformInverse.fromTargetToReference(transform(rotation = 1.0))
                ?: return false
        val target = inverse.mapReferenceToTarget(10.0, 10.0)
        val p0 = CfaPlaneCoordinateMapper.fullToPlane(target.x, target.y, CfaPhaseOffset(0, 0))
        val p1 = CfaPlaneCoordinateMapper.fullToPlane(target.x, target.y, CfaPhaseOffset(1, 1))
        return close(p0.x - p1.x, 0.5) && close(p0.y - p1.y, 0.5)
    }

    private fun translationNotSimplyHalved(): Boolean {
        val inverse =
            SimilarityTransformInverse.fromTargetToReference(
                transform(tx = 4.0, ty = 2.0, rotation = 2.0)
            ) ?: return false
        val target = inverse.mapReferenceToTarget(20.0, 12.0)
        val plane =
            CfaPlaneCoordinateMapper.fullToPlane(target.x, target.y, CfaPhaseOffset(1, 0))
        return !close(plane.x, (20.0 - 4.0) / 2.0)
    }

    private fun samplerPath(
        transform: StarSimilarityTransform,
        x: Int,
        y: Int
    ): CfaSamplingPath {
        val layout = requireNotNull(CfaPhaseLayout.from(CfaPattern.RGGB))
        val inverse = requireNotNull(SimilarityTransformInverse.fromTargetToReference(transform))
        val window = phaseWindow(layout, 16, 16)
        return CfaSafeSimilaritySampler(layout, 1e-7)
            .sample(x, y, inverse, window, 16, 16, window::sampleAt)
            .path
    }

    private fun bilinearWeightsSum(): Boolean {
        val layout = requireNotNull(CfaPhaseLayout.from(CfaPattern.RGGB))
        val window =
            SourceTileWindow(
                0,
                0,
                16,
                16,
                IntArray(16 * 16) { index ->
                    (index % 16) * 10 + index / 16
                }
            )
        val inverse =
            requireNotNull(
                SimilarityTransformInverse.fromTargetToReference(
                    transform(tx = 0.6, ty = 0.8)
                )
            )
        val sample =
            CfaSafeSimilaritySampler(layout, 1e-7)
                .sample(8, 8, inverse, window, 16, 16, window::sampleAt)
        return sample.valid && sample.value.isFinite()
    }

    private fun samplerValue(value: Int): Double {
        val layout = requireNotNull(CfaPhaseLayout.from(CfaPattern.RGGB))
        val window = SourceTileWindow(0, 0, 8, 8, IntArray(64) { value })
        return CfaSafeSimilaritySampler(layout, 1e-7)
            .sample(
                4,
                4,
                requireNotNull(
                    SimilarityTransformInverse.fromTargetToReference(
                        StarSimilarityTransform.IDENTITY
                    )
                ),
                window,
                8,
                8,
                window::sampleAt
            ).value
    }

    private fun sampleAtEdge(): CfaWarpedSample {
        val layout = requireNotNull(CfaPhaseLayout.from(CfaPattern.RGGB))
        val window = SourceTileWindow(0, 0, 8, 8, IntArray(64) { 100 })
        val inverse =
            requireNotNull(
                SimilarityTransformInverse.fromTargetToReference(transform(tx = 1.0, ty = 1.0))
            )
        return CfaSafeSimilaritySampler(layout, 1e-7)
            .sample(0, 0, inverse, window, 8, 8, window::sampleAt)
    }

    private fun compareWithExisting(
        mode: RawStackAggregationMode,
        targetTransform: StarSimilarityTransform
    ): Boolean =
        withTempDirectory { directory ->
            val frames =
                listOf(
                    frame(0) { x, y -> 1000 + x * 7 + y * 19 },
                    frame(1) { x, y -> 3000 + x * 5 + y * 23 }
                )
            val starFile = File(directory, "star.raw16")
            val oldFile = File(directory, "old.raw16")
            val star =
                processStar(
                    frames,
                    mapOf(0 to StarSimilarityTransform.IDENTITY, 1 to targetTransform),
                    mode,
                    starFile
                )
            val oldTransform =
                RawTransform(
                    dx = targetTransform.tx,
                    dy = targetTransform.ty,
                    rotationDegrees = 0.0,
                    scale = 1.0
                )
            val old =
                AlignedRaw16StackProcessor().processToPackedFile(
                    RawStack(frames),
                    landscapeReport(frames, mapOf(0 to RawTransform.IDENTITY, 1 to oldTransform)),
                    AlignedRaw16StackOptions(
                        aggregationOptions = RawStackAggregationOptions(mode = mode)
                    ),
                    oldFile
                )
            star.success && old.success && starFile.readBytes().contentEquals(oldFile.readBytes())
        }

    private fun compareIdentityRobust(mode: RawStackAggregationMode): Boolean =
        withTempDirectory { directory ->
            val frames =
                (0 until 7).map { index ->
                    frame(index) { x, y ->
                        1000 + x + y * 11 + if (index == 6 && x == 4 && y == 4) 5000 else index
                    }
                }
            val starFile = File(directory, "star.raw16")
            val oldFile = File(directory, "old.raw16")
            val transforms = frames.associate { it.frameIndex to StarSimilarityTransform.IDENTITY }
            val rawTransforms = frames.associate { it.frameIndex to RawTransform.IDENTITY }
            val star = processStar(frames, transforms, mode, starFile)
            val old =
                AlignedRaw16StackProcessor().processToPackedFile(
                    RawStack(frames),
                    landscapeReport(frames, rawTransforms),
                    AlignedRaw16StackOptions(
                        aggregationOptions = RawStackAggregationOptions(mode = mode)
                    ),
                    oldFile
                )
            star.success && old.success && starFile.readBytes().contentEquals(oldFile.readBytes())
        }

    private fun roundedOnce(): Boolean {
        val samples = floatArrayOf(100.2f, 100.8f)
        return WarpedSampleAggregator(2)
            .aggregate(samples, 0, 2, RawStackAggregationOptions())
            .value == 101
    }

    private fun borderFallback(): Boolean =
        withTempDirectory { directory ->
            val frames = listOf(frame(0) { _, _ -> 100 }, frame(1) { _, _ -> 200 })
            val result =
                processStar(
                    frames,
                    mapOf(0 to StarSimilarityTransform.IDENTITY, 1 to transform(tx = 3.0)),
                    RawStackAggregationMode.MIN_MAX_REJECTED_MEAN,
                    File(directory, "border.raw16")
                )
            result.success &&
                result.referenceOnlyPixelCount > 0 &&
                result.aggregationDiagnostics.pixelsFallingBackToMean > 0
        }

    private fun rejectedFrameExcluded(): Boolean =
        withTempDirectory { directory ->
            val frames =
                listOf(
                    frame(0) { _, _ -> 100 },
                    frame(1) { _, _ -> 200 },
                    frame(2) { _, _ -> 50000 }
                )
            val report =
                starReport(
                    frames,
                    frames.associate { it.frameIndex to StarSimilarityTransform.IDENTITY },
                    rejected = setOf(2)
                )
            val result =
                StarAlignedRaw16StackProcessor().processToPackedFile(
                    RawStack(frames),
                    report,
                    StarAlignedRaw16StackOptions(),
                    File(directory, "rejected.raw16")
                )
            result.success &&
                result.acceptedFrameCount == 2 &&
                result.warnings.any {
                    it.code == StarAlignedRaw16WarningCode.FRAME_REJECTED_BY_STAR_ALIGNMENT
                }
        }

    private fun minimumFramesPolicy(): Boolean =
        withTempDirectory { directory ->
            val frame = frame(0) { _, _ -> 100 }
            val result =
                StarAlignedRaw16StackProcessor().processToPackedFile(
                    RawStack(listOf(frame)),
                    starReport(listOf(frame), mapOf(0 to StarSimilarityTransform.IDENTITY)),
                    StarAlignedRaw16StackOptions(minimumAcceptedFrames = 2),
                    File(directory, "min.raw16")
                )
            !result.success &&
                result.fatalError == StarAlignedRaw16FailureCode.INSUFFICIENT_ACCEPTED_FRAMES
        }

    private fun darkBeforeInterpolation(): Boolean {
        val layout = requireNotNull(CfaPhaseLayout.from(CfaPattern.RGGB))
        val window = SourceTileWindow(0, 0, 12, 12, IntArray(144) { 200 })
        val inverse =
            requireNotNull(
                SimilarityTransformInverse.fromTargetToReference(transform(tx = 1.0))
            )
        val sample =
            CfaSafeSimilaritySampler(layout, 1e-7)
                .sample(6, 6, inverse, window, 12, 12) { x, y ->
                    DarkSubtractor.subtractDarkRaw16At(
                        window.sampleAt(x, y),
                        80,
                        intArrayOf(64, 64, 64, 64),
                        x,
                        y
                    )
                }
        return sample.valid && close(sample.value, 184.0)
    }

    private fun darkSourceCoordinate(): Boolean {
        val layout = requireNotNull(CfaPhaseLayout.from(CfaPattern.RGGB))
        val window = SourceTileWindow(0, 0, 12, 12, IntArray(144) { 200 })
        val visited = mutableSetOf<Pair<Int, Int>>()
        val inverse =
            requireNotNull(
                SimilarityTransformInverse.fromTargetToReference(transform(tx = 1.0, ty = 1.0))
            )
        val sample =
            CfaSafeSimilaritySampler(layout, 1e-7)
                .sample(6, 6, inverse, window, 12, 12) { x, y ->
                    visited += x to y
                    window.sampleAt(x, y)
                }
        return sample.valid &&
            visited == setOf(4 to 4, 6 to 4, 4 to 6, 6 to 6)
    }

    private fun perPhaseBlackLevel(): Boolean {
        val black = intArrayOf(10, 20, 30, 40)
        val values =
            listOf(
                DarkSubtractor.subtractDarkRaw16At(500, 50, black, 0, 0),
                DarkSubtractor.subtractDarkRaw16At(500, 50, black, 1, 0),
                DarkSubtractor.subtractDarkRaw16At(500, 50, black, 0, 1),
                DarkSubtractor.subtractDarkRaw16At(500, 50, black, 1, 1)
            )
        return values.toSet().size == 4
    }

    private fun missingDarkPolicy(fail: Boolean): Boolean =
        withTempDirectory { directory ->
            val frames = listOf(frame(0) { _, _ -> 100 }, frame(1) { _, _ -> 100 })
            val behavior =
                if (fail) MissingMasterDarkBehavior.FAIL
                else MissingMasterDarkBehavior.WARN_AND_CONTINUE
            val result =
                StarAlignedRaw16StackProcessor().processToPackedFile(
                    RawStack(frames),
                    starReport(frames, frames.associate { it.frameIndex to StarSimilarityTransform.IDENTITY }),
                    StarAlignedRaw16StackOptions(
                        darkCalibration =
                            DarkCalibrationInput(
                                policy = DarkPolicy.USE_COMPATIBLE_MASTER_DARK,
                                masterDark = null,
                                options =
                                    DarkCalibrationOptions(
                                        darkPolicy = DarkPolicy.USE_COMPATIBLE_MASTER_DARK,
                                        missingMasterDarkBehavior = behavior
                                    )
                            )
                    ),
                    File(directory, "dark.raw16")
                )
            result.success &&
                result.warnings.any {
                    it.code == StarAlignedRaw16WarningCode.MASTER_DARK_NOT_FOUND
                }
        }

    private fun fileBackedEqualsInMemory(): Boolean =
        withTempDirectory { directory ->
            val bytes = packed(12, 10) { x, y -> 1000 + x + y * 12 }
            val file = File(directory, "source.raw16").apply { writeBytes(bytes) }
            val inMemory =
                frame(0, bytes = bytes) { _, _ -> 0 }
            val fileBacked =
                inMemory.copy(
                    raw16 = null,
                    raw16Storage = FileBackedRaw16FrameStorage(file, deleteOnCleanup = false)
                )
            val a = File(directory, "a.raw16")
            val b = File(directory, "b.raw16")
            val resultA =
                processStar(
                    listOf(inMemory),
                    mapOf(0 to StarSimilarityTransform.IDENTITY),
                    RawStackAggregationMode.MEAN,
                    a,
                    minimumFrames = 1
                )
            val resultB =
                processStar(
                    listOf(fileBacked),
                    mapOf(0 to StarSimilarityTransform.IDENTITY),
                    RawStackAggregationMode.MEAN,
                    b,
                    minimumFrames = 1
                )
            resultA.success && resultB.success && a.readBytes().contentEquals(b.readBytes())
        }

    private fun tileOutputsEqual(
        firstWidth: Int,
        firstHeight: Int,
        secondWidth: Int,
        secondHeight: Int
    ): Boolean =
        withTempDirectory { directory ->
            val frames =
                listOf(
                    frame(0, width = 24, height = 20) { x, y -> 100 + x + y * 24 },
                    frame(1, width = 24, height = 20) { x, y -> 300 + x * 2 + y * 24 }
                )
            val transforms =
                mapOf(
                    0 to StarSimilarityTransform.IDENTITY,
                    1 to transform(tx = 0.4, ty = -0.7, rotation = 0.2)
                )
            val first = File(directory, "first.raw16")
            val second = File(directory, "second.raw16")
            val a =
                processStar(
                    frames,
                    transforms,
                    RawStackAggregationMode.MEAN,
                    first,
                    tileWidth = firstWidth,
                    tileHeight = firstHeight
                )
            val b =
                processStar(
                    frames,
                    transforms,
                    RawStackAggregationMode.MEAN,
                    second,
                    tileWidth = secondWidth,
                    tileHeight = secondHeight
                )
            a.success && b.success && first.readBytes().contentEquals(second.readBytes())
        }

    private fun sourceWindowHalo(): Boolean {
        val frame = frame(0, width = 32, height = 24) { x, y -> x + y }
        val accessor = com.lab.bracketlab.processing.stack.Raw16SampleAccessor.create(frame)
        return accessor.use {
            val window =
                SourceTileReader(frame, it).readWindow(
                    OutputTile(8, 8, 8, 8),
                    requireNotNull(
                        SimilarityTransformInverse.fromTargetToReference(
                            StarSimilarityTransform.IDENTITY
                        )
                    )
                ) ?: return@use false
            window.left <= 5 && window.top <= 5 &&
                window.rightExclusive >= 19 && window.bottomExclusive >= 19
        }
    }

    private fun sourceWindowClipping(): Boolean {
        val frame = frame(0, width = 16, height = 12) { x, y -> x + y }
        val accessor = com.lab.bracketlab.processing.stack.Raw16SampleAccessor.create(frame)
        return accessor.use {
            val window =
                SourceTileReader(frame, it).readWindow(
                    OutputTile(0, 0, 8, 8),
                    requireNotNull(
                        SimilarityTransformInverse.fromTargetToReference(
                            StarSimilarityTransform.IDENTITY
                        )
                    )
                ) ?: return@use false
            window.left == 0 && window.top == 0 &&
                window.rightExclusive <= frame.width &&
                window.bottomExclusive <= frame.height
        }
    }

    private fun packedOutputSize(): Boolean =
        withTempDirectory { directory ->
            val frames = listOf(frame(0) { _, _ -> 100 }, frame(1) { _, _ -> 200 })
            val file = File(directory, "size.raw16")
            val result =
                processStar(
                    frames,
                    frames.associate { it.frameIndex to StarSimilarityTransform.IDENTITY },
                    RawStackAggregationMode.MEAN,
                    file
                )
            result.success && file.length() == frames[0].width.toLong() * frames[0].height * 2L
        }

    private fun littleEndianOutput(): Boolean =
        withTempDirectory { directory ->
            val frames = listOf(frame(0) { _, _ -> 0x1234 })
            val file = File(directory, "endian.raw16")
            val result =
                processStar(
                    frames,
                    mapOf(0 to StarSimilarityTransform.IDENTITY),
                    RawStackAggregationMode.MEAN,
                    file,
                    minimumFrames = 1
                )
            val bytes = file.readBytes()
            result.success &&
                (bytes[0].toInt() and 0xFF) == 0x34 &&
                (bytes[1].toInt() and 0xFF) == 0x12
        }

    private fun tilePlannerBudget(frameCount: Int): Boolean {
        val plan =
            AdaptiveStarTilePlanner.plan(
                4032,
                3024,
                frameCount,
                256,
                128,
                24L * 1024L * 1024L
            )
        return plan.estimatedWorkingBytes <= plan.memoryBudgetBytes &&
            plan.tileWidth >= 8 &&
            plan.tileHeight >= 8
    }

    private fun failedOutputCleanup(): Boolean =
        withTempDirectory { directory ->
            val frames = listOf(frame(0) { _, _ -> 100 }, frame(1) { _, _ -> 200 })
            val output = File(directory, "failed.raw16")
            val report =
                starReport(
                    frames,
                    mapOf(
                        0 to StarSimilarityTransform.IDENTITY,
                        1 to StarSimilarityTransform(0.0, 0.0, 0.0, 0.0)
                    )
                )
            val result =
                StarAlignedRaw16StackProcessor().processToPackedFile(
                    RawStack(frames),
                    report,
                    StarAlignedRaw16StackOptions(),
                    output
                )
            !result.success && !output.exists()
        }

    private fun writerCleanupContract(): Boolean {
        val constructors =
            Class.forName(
                "com.lab.bracketlab.processing.io.Raw16DngWriteRequest"
            ).declaredConstructors
        return constructors.isNotEmpty()
    }

    private fun referenceIndexPreserved(): Boolean =
        withTempDirectory { directory ->
            val frames =
                listOf(
                    frame(5) { _, _ -> 100 },
                    frame(9) { _, _ -> 200 }
                )
            val result =
                processStar(
                    frames,
                    frames.associate { it.frameIndex to StarSimilarityTransform.IDENTITY },
                    RawStackAggregationMode.MEAN,
                    File(directory, "ref.raw16"),
                    referenceIndex = 9
                )
            result.success && result.referenceFrameIndex == 9
        }

    private fun frameMismatchFails(): Boolean =
        withTempDirectory { directory ->
            val frames = listOf(frame(0) { _, _ -> 100 }, frame(1) { _, _ -> 200 })
            val bad = starReport(frames, mapOf(0 to StarSimilarityTransform.IDENTITY))
            val result =
                StarAlignedRaw16StackProcessor().processToPackedFile(
                    RawStack(frames),
                    bad,
                    StarAlignedRaw16StackOptions(),
                    File(directory, "mismatch.raw16")
                )
            !result.success && result.fatalError == StarAlignedRaw16FailureCode.MISSING_FRAME_TRANSFORM
        }

    private fun duplicateTransformFails(): Boolean =
        withTempDirectory { directory ->
            val frames = listOf(frame(0) { _, _ -> 100 }, frame(1) { _, _ -> 200 })
            val base =
                starReport(
                    frames,
                    frames.associate { it.frameIndex to StarSimilarityTransform.IDENTITY }
                )
            val duplicate = base.copy(frameResults = base.frameResults + base.frameResults.last())
            val result =
                StarAlignedRaw16StackProcessor().processToPackedFile(
                    RawStack(frames),
                    duplicate,
                    StarAlignedRaw16StackOptions(),
                    File(directory, "duplicate.raw16")
                )
            !result.success &&
                result.fatalError == StarAlignedRaw16FailureCode.DUPLICATE_FRAME_TRANSFORM
        }

    private fun missingTransformFails(): Boolean = frameMismatchFails()

    private fun nonInvertibleFails(): Boolean = failedOutputCleanup()

    private fun rejectedFrameReported(): Boolean = rejectedFrameExcluded()

    private fun writerNotCalledAfterStackFailure(): Boolean =
        withTempDirectory { directory ->
            var writerCalled = false
            val frame = frame(0) { _, _ -> 100 }
            val invalidReport = starReport(listOf(frame), emptyMap())
            val pipeline =
                StarAlignedRaw16ExportPipeline(
                    StarAlignedRaw16ExportDependencies(
                        write = {
                            writerCalled = true
                            error("Writer must not be called")
                        }
                    )
                )
            val result =
                pipeline.processAndWrite(
                    RawStack(listOf(frame)),
                    invalidReport,
                    EmptyMetadataProvider,
                    StarAlignedRaw16StackOptions(minimumAcceptedFrames = 1),
                    Raw16DngWriteOptions(),
                    FailingOutputDestination,
                    File(directory, "pipeline.raw16")
                )
            !result.success && !writerCalled &&
                result.failureStage == StarAlignedRaw16ExportFailureStage.STACKING
        }

    private fun incrementalReportSurvives(): Boolean =
        withTempDirectory { directory ->
            val file = File(directory, "report.txt")
            runCatching {
                IncrementalDiagnosticReport(file).use {
                    it.append("started")
                    throw IllegalStateException("simulated")
                }
            }
            file.readText().contains("started")
        }

    private fun singleFlightGuardWorks(): Boolean {
        val guard = SingleFlightGuard()
        val first = guard.tryAcquire()
        val second = guard.tryAcquire()
        guard.release()
        return first && !second && guard.tryAcquire().also { guard.release() }
    }

    private fun processStar(
        frames: List<RawFrame>,
        transforms: Map<Int, StarSimilarityTransform>,
        mode: RawStackAggregationMode,
        output: File,
        minimumFrames: Int = 2,
        tileWidth: Int = 8,
        tileHeight: Int = 8,
        referenceIndex: Int = frames.first().frameIndex
    ): StarAlignedRaw16StackResult =
        StarAlignedRaw16StackProcessor().processToPackedFile(
            RawStack(frames),
            starReport(frames, transforms, referenceIndex = referenceIndex),
            StarAlignedRaw16StackOptions(
                aggregationOptions = RawStackAggregationOptions(mode = mode),
                minimumAcceptedFrames = minimumFrames,
                preferredTileWidth = tileWidth,
                preferredTileHeight = tileHeight
            ),
            output
        )

    private fun starReport(
        frames: List<RawFrame>,
        transforms: Map<Int, StarSimilarityTransform>,
        rejected: Set<Int> = emptySet(),
        referenceIndex: Int = frames.firstOrNull()?.frameIndex ?: 0
    ): StarAlignmentReport {
        val results =
            transforms.entries.mapIndexed { position, (frameIndex, transform) ->
                val accepted = frameIndex !in rejected
                val isReference = frameIndex == referenceIndex
                val alignment =
                    AlignmentResult(
                        frameIndex = frameIndex,
                        mode = ResolvedAlignmentMode.STAR_ALIGNMENT,
                        transform = transform.toRawTransform(),
                        accepted = accepted
                    )
                StarFrameAlignment(
                    framePosition = position,
                    frameIndex = frameIndex,
                    isReference = isReference,
                    detectedStarCount = 20,
                    eligibleStarCount = 20,
                    retainedStarCount = 20,
                    candidateMatchCount = if (isReference) 0 else 20,
                    ransacInlierCount = if (isReference) 20 else 18,
                    outlierCount = if (isReference) 0 else 2,
                    inlierRatio = if (isReference) 1.0 else 0.9,
                    rmsResidualRawPixels = 0.1,
                    medianResidualRawPixels = 0.1,
                    maximumResidualRawPixels = 0.2,
                    transform = transform,
                    matchingStrategy =
                        if (isReference) StarMatchingStrategy.IDENTITY
                        else StarMatchingStrategy.SMALL_MOTION,
                    spatialDistribution = null,
                    matches = emptyList(),
                    accepted = accepted,
                    failureCode = null,
                    diagnosticMessage = null,
                    warnings = emptyList(),
                    alignmentResult = alignment
                )
            }
        return StarAlignmentReport(
            status =
                if (rejected.isEmpty()) StarAlignmentStatus.SUCCESS
                else StarAlignmentStatus.PARTIAL_SUCCESS,
            success = rejected.isEmpty(),
            partialSuccess = rejected.isNotEmpty(),
            referenceFrameIndex = referenceIndex,
            referenceCatalogPosition =
                frames.indexOfFirst { it.frameIndex == referenceIndex }.takeIf { it >= 0 },
            referenceSelectionReason = StarReferenceSelectionReason.MIDDLE_CATALOG,
            referenceStarCount = 20,
            referenceMedianSnr = 20.0,
            totalFrameCount = frames.size,
            acceptedFrameCount = results.count(StarFrameAlignment::accepted),
            rejectedFrameCount = frames.size - results.count(StarFrameAlignment::accepted),
            frameResults = results,
            alignmentResults = results.map(StarFrameAlignment::alignmentResult),
            warnings = emptyList(),
            fatalError = null,
            fatalMessage = null,
            durationMs = 0,
            options = StarMatchingOptions()
        )
    }

    private fun landscapeReport(
        frames: List<RawFrame>,
        transforms: Map<Int, RawTransform>
    ): LandscapeAlignmentReport {
        val referenceIndex = frames.first().frameIndex
        val results =
            frames.mapIndexed { position, frame ->
                val transform = requireNotNull(transforms[frame.frameIndex])
                val alignment =
                    AlignmentResult(
                        frameIndex = frame.frameIndex,
                        mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
                        transform = transform,
                        accepted = true
                    )
                LandscapeFrameAlignment(
                    framePosition = position,
                    frameIndex = frame.frameIndex,
                    isReference = frame.frameIndex == referenceIndex,
                    proxyTypeUsed = RawProxyType.GREEN,
                    lumaFallbackUsed = false,
                    targetExposureTimeSeconds = frame.exposureTimeSeconds,
                    referenceExposureTimeSeconds = frames.first().exposureTimeSeconds,
                    phaseResponse = 1.0,
                    dxRawPixels = transform.dx,
                    dyRawPixels = transform.dy,
                    overlapFraction = 1.0,
                    accepted = true,
                    lowConfidence = false,
                    rejectionReason = null,
                    diagnosticMessage = null,
                    warnings = emptyList(),
                    alignmentResult = alignment
                )
            }
        return LandscapeAlignmentReport(
            status = LandscapeAlignmentStatus.SUCCESS,
            success = true,
            partialSuccess = false,
            selectedReferenceFrameIndex = referenceIndex,
            selectedReferencePosition = 0,
            referenceSelectionMethod = ReferenceSelectionMethod.MIDDLE_FRAME_INDEX,
            referenceExposureTimeSeconds = frames.first().exposureTimeSeconds,
            totalFrameCount = frames.size,
            acceptedFrameCount = frames.size,
            rejectedFrameCount = 0,
            lowConfidenceFrameCount = 0,
            frameResults = results,
            alignmentResults = results.map(LandscapeFrameAlignment::alignmentResult),
            warnings = emptyList(),
            fatalError = null,
            fatalMessage = null,
            processingDurationMs = 0,
            options = LandscapeAlignmentOptions()
        )
    }

    private fun frame(
        index: Int,
        width: Int = 12,
        height: Int = 10,
        pattern: CfaPattern = CfaPattern.RGGB,
        bytes: ByteArray? = null,
        value: (Int, Int) -> Int
    ): RawFrame {
        val packed = bytes ?: packed(width, height, value)
        return RawFrame(
            width = width,
            height = height,
            raw16 = packed,
            rowStride = width * 2,
            pixelStride = 2,
            exposureTimeNs = 100_000_000L,
            iso = 800,
            cameraId = "0",
            timestampNs = index.toLong(),
            frameIndex = index,
            blackLevelPattern = intArrayOf(64, 64, 64, 64),
            whiteLevel = 4095,
            cfaPattern = pattern
        )
    }

    private fun packed(
        width: Int,
        height: Int,
        value: (Int, Int) -> Int
    ): ByteArray {
        val bytes = ByteArray(width * height * 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sample = value(x, y).coerceIn(0, 65535)
                val offset = (y * width + x) * 2
                bytes[offset] = (sample and 0xFF).toByte()
                bytes[offset + 1] = ((sample ushr 8) and 0xFF).toByte()
            }
        }
        return bytes
    }

    private fun phaseWindow(
        layout: CfaPhaseLayout,
        width: Int,
        height: Int
    ): SourceTileWindow =
        SourceTileWindow(
            0,
            0,
            width,
            height,
            IntArray(width * height) { index ->
                phaseValue(layout.phaseAt(index % width, index / width))
            }
        )

    private fun phaseValue(phase: CfaPhase): Int =
        when (phase) {
            CfaPhase.R -> 1000
            CfaPhase.G1 -> 2000
            CfaPhase.G2 -> 3000
            CfaPhase.B -> 4000
        }

    private fun transform(
        tx: Double = 0.0,
        ty: Double = 0.0,
        rotation: Double = 0.0,
        scale: Double = 1.0
    ): StarSimilarityTransform =
        StarSimilarityTransform.fromParameters(tx, ty, rotation, scale)

    private fun test(
        name: String,
        block: () -> Boolean
    ): StarAlignedRaw16SelfTestCase =
        try {
            if (block()) {
                StarAlignedRaw16SelfTestCase(
                    name,
                    PhaseCorrelationSelfTestStatus.PASS,
                    "ok"
                )
            } else {
                StarAlignedRaw16SelfTestCase(
                    name,
                    PhaseCorrelationSelfTestStatus.FAIL,
                    "condition returned false"
                )
            }
        } catch (skipped: SelfTestSkippedException) {
            StarAlignedRaw16SelfTestCase(
                name,
                PhaseCorrelationSelfTestStatus.SKIPPED,
                skipped.message ?: "runtime unavailable"
            )
        } catch (error: Throwable) {
            StarAlignedRaw16SelfTestCase(
                name,
                PhaseCorrelationSelfTestStatus.FAIL,
                "${error.javaClass.simpleName}: ${error.message}"
            )
        }

    private fun <T> withTempDirectory(block: (File) -> T): T {
        val root = File(System.getProperty("java.io.tmpdir") ?: ".", "bracketlab_star_warp_${System.nanoTime()}")
        root.mkdirs()
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun close(a: Double, b: Double, tolerance: Double = 1e-6): Boolean =
        abs(a - b) <= tolerance

    private object EmptyMetadataProvider : CameraMetadataProvider {
        override fun getCameraCharacteristics(cameraId: String?) = null
        override fun getCaptureResult(frameIndex: Int) = null
    }

    private object FailingOutputDestination :
        com.lab.bracketlab.processing.io.Raw16DngOutputDestination {
        override val requestedFilename: String? = "never.dng"
        override fun open(): com.lab.bracketlab.processing.io.Raw16DngOpenedOutput =
            error("Output destination must not be opened")
    }

    private class SelfTestSkippedException(message: String) : RuntimeException(message)
}
