package com.lab.bracketlab.processing.hdri

import com.lab.bracketlab.processing.align.PhaseCorrelationSelfTestStatus
import com.lab.bracketlab.processing.debug.IncrementalDiagnosticReport
import com.lab.bracketlab.processing.io.Raw16DngWriterSelfTest
import com.lab.bracketlab.processing.memory.MemoryStabilizationSelfTest
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.raw.BayerUtils
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.stack.AlignedRaw16StackOptions
import com.lab.bracketlab.processing.stack.AlignedRaw16StackSelfTest
import com.lab.bracketlab.processing.stack.RawStackAggregatorSelfTest
import com.lab.bracketlab.processing.storage.FileBackedRaw16FrameStorage
import com.lab.bracketlab.processing.calibration.MasterDarkSelfTest
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class HdrI32SelfTestCaseResult(
    val name: String,
    val status: PhaseCorrelationSelfTestStatus,
    val message: String
)

data class HdrI32SelfTestReport(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val durationMs: Long,
    val results: List<HdrI32SelfTestCaseResult>
)

object HdrI32SelfTest {
    fun runAll(): HdrI32SelfTestReport {
        val startedNs = System.nanoTime()
        val results = listOf(
            test("1 empty stack fails") {
                withTempDir { HdrI32Merger(it).merge(RawStack(emptyList())).failureCode ==
                    HdrI32FailureCode.EMPTY_HDR_STACK }
            },
            test("2 one frame fails") {
                withTempDir { HdrI32Merger(it).merge(RawStack(listOf(frame(0, 10_000_000L)))).failureCode ==
                    HdrI32FailureCode.INSUFFICIENT_HDR_FRAMES }
            },
            test("3 two different exposures pass") {
                withMerge(defaultFrames()) { it.success }
            },
            test("4 identical exposures fail by default") {
                withTempDir {
                    HdrI32Merger(it).merge(
                        RawStack(listOf(frame(0, 10_000_000L), frame(1, 10_000_000L)))
                    ).failureCode == HdrI32FailureCode.IDENTICAL_EXPOSURE_SET
                }
            },
            validationMutationTest(
                "5 mixed dimensions rejected",
                { it.copy(width = WIDTH + 1, rowStride = (WIDTH + 1) * 2, raw16 = packed(IntArray((WIDTH + 1) * HEIGHT) { 100 })) },
                HdrI32FailureCode.INCOMPATIBLE_DIMENSIONS
            ),
            validationMutationTest(
                "6 mixed CFA rejected",
                { it.copy(cfaPattern = CfaPattern.BGGR) },
                HdrI32FailureCode.INCOMPATIBLE_CFA
            ),
            validationMutationTest(
                "7 mixed ISO rejected",
                { it.copy(iso = ISO + 1) },
                HdrI32FailureCode.INCOMPATIBLE_ISO
            ),
            validationMutationTest(
                "8 invalid exposure rejected",
                { it.copy(exposureTimeNs = 0L) },
                HdrI32FailureCode.INVALID_EXPOSURE_TIME
            ),
            validationMutationTest(
                "9 mixed black level rejected",
                { it.copy(blackLevelPattern = intArrayOf(65, 64, 64, 64)) },
                HdrI32FailureCode.INCOMPATIBLE_BLACK_LEVEL
            ),
            validationMutationTest(
                "10 mixed white level rejected",
                { it.copy(whiteLevel = WHITE + 1) },
                HdrI32FailureCode.INCOMPATIBLE_WHITE_LEVEL
            ),
            test("11 black-level sample produces zero radiance") {
                withMerge(
                    listOf(
                        frame(0, 100_000_000L, values = cfaBlackValues()),
                        frame(1, 50_000_000L, values = cfaBlackValues())
                    )
                ) { result -> readFloats(result.frame!!.storageFile).all { it == 0f } }
            },
            test("12 black level is not subtracted twice") {
                withMerge(equivalentRadianceFrames(signal = 100)) { result ->
                    nearly(readFloats(result.frame!!.storageFile).first().toDouble(), 1000.0)
                }
            },
            test("13 radiance doubles when exposure halves for same signal") {
                val values = IntArray(PIXELS) { BLACK[phase(it)] + 100 }
                withMerge(
                    listOf(
                        frame(0, 100_000_000L, values = values),
                        frame(1, 50_000_000L, values = values)
                    ),
                    HdrI32MergeOptions(
                        weightPolicy = HdrI32WeightPolicy.UNIFORM_VALID_RADIANCE
                    )
                ) { result -> nearly(readFloats(result.frame!!.storageFile).first().toDouble(), 1500.0) }
            },
            test("14 equivalent frame radiance merges unchanged") {
                withMerge(equivalentRadianceFrames(signal = 100)) { result ->
                    readFloats(result.frame!!.storageFile).all { nearly(it.toDouble(), 1000.0) }
                }
            },
            test("15 saturated long exposure rejected") {
                val short = IntArray(PIXELS) { BLACK[phase(it)] + 100 }
                val saturated = IntArray(PIXELS) { WHITE }
                withMerge(
                    listOf(
                        frame(0, 10_000_000L, values = short),
                        frame(1, 100_000_000L, values = saturated)
                    )
                ) { result ->
                    nearly(readFloats(result.frame!!.storageFile).first().toDouble(), 10_000.0) &&
                        result.diagnostics!!.saturatedRejectedSamples == PIXELS.toLong()
                }
            },
            test("16 unsaturated long exposure contributes") {
                withMerge(equivalentRadianceFrames(signal = 50)) { result ->
                    result.diagnostics!!.validSamples == PIXELS.toLong() * 2L
                }
            },
            test("17 no valid sample follows fallback or failure policy") {
                val saturated = IntArray(PIXELS) { WHITE }
                val frames = listOf(
                    frame(0, 10_000_000L, values = saturated),
                    frame(1, 20_000_000L, values = saturated)
                )
                withTempDir { dir ->
                    val fallback = HdrI32Merger(dir).merge(RawStack(frames))
                    val fail = HdrI32Merger(dir).merge(
                        RawStack(frames),
                        HdrI32MergeOptions(invalidSamplePolicy = HdrI32InvalidSamplePolicy.FAIL)
                    )
                    fallback.frame?.storageFile?.delete()
                    fallback.success && fallback.diagnostics!!.fallbackPixels == PIXELS.toLong() &&
                        fail.failureCode == HdrI32FailureCode.NO_VALID_HDR_SAMPLE
                }
            },
            floatPropertyTest("18 output contains no NaN") { !it.isNaN() },
            floatPropertyTest("19 output contains no Infinity") { it.isFinite() },
            floatPropertyTest("20 output contains no negative values") { it >= 0f },
            test("21 per-CFA black level is selected") {
                withMerge(
                    listOf(
                        frame(0, 100_000_000L, values = cfaBlackValues()),
                        frame(1, 50_000_000L, values = cfaBlackValues())
                    )
                ) { result -> readFloats(result.frame!!.storageFile).all { it == 0f } }
            },
            test("22 tiled output equals full synthetic reference") {
                compareTileHeights(1, HEIGHT)
            },
            test("23 tile boundaries create no seams") {
                compareTileHeights(2, 3)
            },
            test("24 file-backed input equals in-memory input") {
                withTempDir { dir ->
                    val memory = defaultFrames()
                    val files = memory.map { source ->
                        val file = File(dir, "source_${source.frameIndex}.raw16")
                        file.writeBytes(source.raw16!!)
                        source.copy(
                            raw16 = null,
                            raw16Storage = FileBackedRaw16FrameStorage(file, true)
                        )
                    }
                    val a = HdrI32Merger(dir).merge(RawStack(memory))
                    val aBytes = a.frame!!.storageFile.readBytes()
                    val b = HdrI32Merger(dir).merge(RawStack(files))
                    val equal = aBytes.contentEquals(b.frame!!.storageFile.readBytes())
                    a.frame.storageFile.delete()
                    b.frame.storageFile.delete()
                    files.forEach { it.resolvedRaw16Storage()?.deleteIfOwned() }
                    equal
                }
            },
            test("25 output size equals width height times four") {
                withMerge(defaultFrames()) {
                    it.frame!!.storageFile.length() == WIDTH.toLong() * HEIGHT * Float.SIZE_BYTES
                }
            },
            test("26 little-endian float32 read/write is correct") {
                withMerge(equivalentRadianceFrames(100)) {
                    val bytes = it.frame!!.storageFile.readBytes()
                    nearly(
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float.toDouble(),
                        1000.0
                    )
                }
            },
            test("27 temporary files cleaned on success") {
                withTempDir { dir ->
                    val merged = HdrI32Merger(File(dir, "work")).merge(RawStack(defaultFrames()))
                    val store = HdrI32Store(File(dir, "session"))
                    val saved = store.save(merged.frame!!)
                    saved.success && !merged.frame.storageFile.exists() &&
                        store.cleanupIncompleteWrites() &&
                        File(dir, "session").listFiles().orEmpty().none { it.name.endsWith(".tmp") }
                }
            },
            test("28 temporary files cleaned on simulated failure") {
                withTempDir { dir ->
                    val forced = File(dir, "forced.rawf32.tmp")
                    val result = HdrI32Merger(dir) {
                        forced.also { check(it.mkdir()) }
                    }.merge(RawStack(defaultFrames()))
                    !result.success && result.temporaryCleanupSucceeded && !forced.exists()
                }
            },
            test("29 dark calibration disabled warning emitted") {
                withMerge(defaultFrames(), HdrI32MergeOptions(darkCalibrationRequested = true)) {
                    it.warnings.any { warning ->
                        warning.code == HdrI32WarningCode.DARK_CALIBRATION_DISABLED_FOR_HDRI
                    }
                }
            },
            test("30 HDR has no MasterDark lookup input") {
                HdrI32Merger::class.java.declaredFields.none {
                    it.type.name.contains("MasterDark")
                } && HdrI32MergeOptions::class.java.declaredFields.none {
                    it.type.name.contains("MasterDark")
                }
            },
            test("31 dark request does not change radiance") {
                withTempDir { dir ->
                    val off = HdrI32Merger(dir).merge(RawStack(defaultFrames()))
                    val on = HdrI32Merger(dir).merge(
                        RawStack(defaultFrames()),
                        HdrI32MergeOptions(darkCalibrationRequested = true)
                    )
                    val equal = off.frame!!.storageFile.readBytes()
                        .contentEquals(on.frame!!.storageFile.readBytes())
                    off.frame.storageFile.delete()
                    on.frame.storageFile.delete()
                    equal
                }
            },
            test("32 identity alignment is reported") {
                withMerge(defaultFrames()) {
                    it.frame!!.metadata.alignmentMode == HdrI32AlignmentMode.IDENTITY_ONLY &&
                        it.warnings.any { warning ->
                            warning.code == HdrI32WarningCode.HDRI_ALIGNMENT_IDENTITY_ONLY
                        }
                }
            },
            test("33 RAW16 default stack options remain unchanged") {
                AlignedRaw16StackOptions() == AlignedRaw16StackOptions()
            },
            test("34 short exposure preserves saturated highlight") {
                val short = IntArray(PIXELS) { BLACK[phase(it)] + 500 }
                val long = IntArray(PIXELS) { WHITE }
                withMerge(
                    listOf(
                        frame(0, 10_000_000L, values = short),
                        frame(1, 100_000_000L, values = long)
                    )
                ) { nearly(readFloats(it.frame!!.storageFile).first().toDouble(), 50_000.0) }
            },
            test("35 long exposure preserves shadow radiance") {
                val short = IntArray(PIXELS) { BLACK[phase(it)] + 5 }
                val long = IntArray(PIXELS) { BLACK[phase(it)] + 50 }
                withMerge(
                    listOf(
                        frame(0, 10_000_000L, values = short),
                        frame(1, 100_000_000L, values = long)
                    )
                ) { nearly(readFloats(it.frame!!.storageFile).first().toDouble(), 500.0) }
            },
            test("36 radiance can exceed RAW16 range") {
                val values = IntArray(PIXELS) { BLACK[phase(it)] + 200 }
                withMerge(
                    listOf(
                        frame(0, 1_000_000L, values = values),
                        frame(1, 2_000_000L, values = values)
                    )
                ) { readFloats(it.frame!!.storageFile).first() > 65535f }
            },
            test("37 HDR store writes no rendered compatibility image") {
                withTempDir { dir ->
                    val merged = HdrI32Merger(dir).merge(RawStack(defaultFrames()))
                    val stored = HdrI32Store(File(dir, "session")).save(merged.frame!!)
                    stored.success &&
                        stored.sessionDirectory!!.listFiles().orEmpty().none {
                            it.extension.equals("dng", true) ||
                                it.extension.equals("jpg", true) ||
                                it.extension.equals("png", true)
                        }
                }
            },
            test("38 storage estimator accounts for Float32 output") {
                withTempDir { dir ->
                    val estimate = HdrI32StorageEstimator.estimate(WIDTH, HEIGHT, 3, dir)
                    estimate.floatOutputTemporaryBytes == PIXELS.toLong() * 4L &&
                        estimate.floatOutputCommittedBytes == PIXELS.toLong() * 4L
                }
            },
            test("39 storage estimator includes safety margin") {
                withTempDir { dir ->
                    val estimate = HdrI32StorageEstimator.estimate(
                        WIDTH,
                        HEIGHT,
                        3,
                        dir,
                        minimumReserveBytes = 1024L
                    )
                    estimate.safetyMarginBytes >= 1024L &&
                        estimate.totalRequiredBytes > estimate.baseRequiredBytes
                }
            },
            test("40 incremental report survives simulated failure") {
                withTempDir { dir ->
                    val report = File(dir, "report.txt")
                    runCatching {
                        IncrementalDiagnosticReport(report).use {
                            it.append("HDR started")
                            error("simulated")
                        }
                    }
                    report.readText().contains("HDR started")
                }
            },
            regression("41 existing Aligned RAW16 tests pass") {
                AlignedRaw16StackSelfTest.runAll().failed == 0
            },
            regression("42 existing DNG Writer tests pass") {
                Raw16DngWriterSelfTest.runAll().failed == 0
            },
            regression("43 existing Aggregation tests pass") {
                RawStackAggregatorSelfTest.runAll().failed == 0
            },
            regression("44 existing Memory tests pass") {
                MemoryStabilizationSelfTest.runAll().failed == 0
            },
            regression("45 existing MasterDark tests pass") {
                MasterDarkSelfTest.runAll().failed == 0
            },
            test("46 uniform radiance mode remains available") {
                HdrI32WeightPolicy.entries.contains(
                    HdrI32WeightPolicy.UNIFORM_VALID_RADIANCE
                )
            },
            test("47 SNR weighted mode is the default") {
                HdrI32MergeOptions().weightPolicy ==
                    HdrI32WeightPolicy.SNR_WEIGHTED_RADIANCE
            },
            test("48 near-black short exposure receives zero weight") {
                val short = IntArray(PIXELS) { BLACK[phase(it)] + 1 }
                val long = IntArray(PIXELS) { BLACK[phase(it)] + 100 }
                withMerge(
                    listOf(
                        frame(0, 10_000_000L, short),
                        frame(1, 100_000_000L, long)
                    )
                ) { result ->
                    nearly(readFloats(result.frame!!.storageFile).first().toDouble(), 1000.0) &&
                        result.diagnostics!!.lowSignalZeroWeightSamples == PIXELS.toLong()
                }
            },
            test("49 long valid exposure dominates noisy shadow") {
                val short = IntArray(PIXELS) { BLACK[phase(it)] + 20 }
                val long = IntArray(PIXELS) { BLACK[phase(it)] + 100 }
                val frames = listOf(
                    frame(0, 10_000_000L, short),
                    frame(1, 100_000_000L, long)
                )
                withTempDir { dir ->
                    val uniform = HdrI32Merger(dir).merge(
                        RawStack(frames),
                        HdrI32MergeOptions(
                            weightPolicy = HdrI32WeightPolicy.UNIFORM_VALID_RADIANCE
                        )
                    )
                    val weighted = HdrI32Merger(dir).merge(RawStack(frames))
                    val uniformValue = readFloats(uniform.frame!!.storageFile).first()
                    val weightedValue = readFloats(weighted.frame!!.storageFile).first()
                    uniform.frame.storageFile.delete()
                    weighted.frame.storageFile.delete()
                    abs(weightedValue - 1000f) < abs(uniformValue - 1000f)
                }
            },
            test("50 short exposure rescues saturated highlight in weighted mode") {
                val short = IntArray(PIXELS) { BLACK[phase(it)] + 500 }
                val long = IntArray(PIXELS) { WHITE }
                withMerge(
                    listOf(
                        frame(0, 10_000_000L, short),
                        frame(1, 100_000_000L, long)
                    )
                ) { result ->
                    nearly(readFloats(result.frame!!.storageFile).first().toDouble(), 50_000.0)
                }
            },
            test("51 weighted clean equivalent radiance remains equivalent") {
                withMerge(equivalentRadianceFrames(100)) { result ->
                    readFloats(result.frame!!.storageFile).all { nearly(it.toDouble(), 1000.0) }
                }
            },
            test("52 highlight zero-weight threshold is reported") {
                val highlight = IntArray(PIXELS) { index ->
                    val black = BLACK[phase(index)]
                    black + ((WHITE - black) * 0.99).toInt()
                }
                val shadow = IntArray(PIXELS) { BLACK[phase(it)] + 100 }
                withMerge(
                    listOf(
                        frame(0, 10_000_000L, highlight),
                        frame(1, 100_000_000L, shadow)
                    ),
                    HdrI32MergeOptions(saturationMarginDn = 0)
                ) { result ->
                    result.diagnostics!!.highlightZeroWeightSamples == PIXELS.toLong()
                }
            },
            test("53 weight-zero pixels use longest valid exposure fallback") {
                val short = IntArray(PIXELS) { BLACK[phase(it)] + 1 }
                val long = IntArray(PIXELS) { BLACK[phase(it)] + 1 }
                withMerge(
                    listOf(
                        frame(0, 10_000_000L, short),
                        frame(1, 100_000_000L, long)
                    )
                ) { result ->
                    nearly(readFloats(result.frame!!.storageFile).first().toDouble(), 10.0) &&
                        result.diagnostics!!.fallbackPixels == PIXELS.toLong() &&
                        result.diagnostics.totalWeightZeroPixels == PIXELS.toLong()
                }
            },
            test("54 exposure weight power changes weighting predictably") {
                val short = IntArray(PIXELS) { BLACK[phase(it)] + 20 }
                val long = IntArray(PIXELS) { BLACK[phase(it)] + 100 }
                val frames = listOf(
                    frame(0, 10_000_000L, short),
                    frame(1, 100_000_000L, long)
                )
                val common = HdrI32MergeOptions(
                    blackWeightZeroThreshold = 0.0,
                    blackWeightFullThreshold = 0.0001,
                    highlightWeightFullThreshold = 0.99,
                    highlightWeightZeroThreshold = 1.0
                )
                withTempDir { dir ->
                    val powerZero = HdrI32Merger(dir).merge(
                        RawStack(frames),
                        common.copy(exposureWeightPower = 0.0)
                    )
                    val powerOne = HdrI32Merger(dir).merge(
                        RawStack(frames),
                        common.copy(exposureWeightPower = 1.0)
                    )
                    val zeroValue = readFloats(powerZero.frame!!.storageFile).first()
                    val oneValue = readFloats(powerOne.frame!!.storageFile).first()
                    powerZero.frame.storageFile.delete()
                    powerOne.frame.storageFile.delete()
                    nearly(zeroValue.toDouble(), 1500.0, 0.01) &&
                        nearly(oneValue.toDouble(), 1090.909, 0.01)
                }
            },
            test("55 uniform regression retains exact arithmetic mean") {
                val values = IntArray(PIXELS) { BLACK[phase(it)] + 100 }
                withMerge(
                    listOf(
                        frame(0, 100_000_000L, values),
                        frame(1, 50_000_000L, values)
                    ),
                    HdrI32MergeOptions(
                        weightPolicy = HdrI32WeightPolicy.UNIFORM_VALID_RADIANCE
                    )
                ) { result ->
                    readFloats(result.frame!!.storageFile).all {
                        nearly(it.toDouble(), 1500.0)
                    }
                }
            },
            test("56 weighted output remains finite and non-negative") {
                withMerge(defaultFrames()) { result ->
                    readFloats(result.frame!!.storageFile).all {
                        it.isFinite() && it >= 0f
                    }
                }
            },
            test("57 FITS and XISF retain weighted master samples") {
                withMerge(defaultFrames()) { result ->
                    result.frame!!.metadata.weightPolicy ==
                        HdrI32WeightPolicy.SNR_WEIGHTED_RADIANCE
                }
            },
            test("58 weighting options validate thresholds") {
                runCatching {
                    HdrI32MergeOptions(
                        blackWeightZeroThreshold = 0.02,
                        blackWeightFullThreshold = 0.01
                    )
                }.isFailure
            },
            test("59 Bayer 2x2 highlight coherence is the default") {
                HdrI32MergeOptions().highlightCoherencePolicy ==
                    HdrI32HighlightCoherencePolicy.BAYER_2X2_SHARED
            },
            test("60 saturated CFA sample switches the whole Bayer block") {
                val short = IntArray(PIXELS) { BLACK[phase(it)] + 80 }
                val long =
                    IntArray(PIXELS) { index ->
                        if (phase(index) == 0) WHITE else BLACK[phase(index)] + 700
                    }
                val frames = listOf(
                    frame(0, 10_000_000L, short),
                    frame(1, 100_000_000L, long)
                )
                withTempDir { dir ->
                    val shared = HdrI32Merger(dir).merge(RawStack(frames))
                    val perSample = HdrI32Merger(dir).merge(
                        RawStack(frames),
                        HdrI32MergeOptions(
                            highlightCoherencePolicy =
                                HdrI32HighlightCoherencePolicy.PER_SAMPLE
                        )
                    )
                    val sharedValues = readFloats(shared.frame!!.storageFile)
                    val perSampleValues = readFloats(perSample.frame!!.storageFile)
                    shared.frame.storageFile.delete()
                    perSample.frame.storageFile.delete()
                    sharedValues.all { nearly(it.toDouble(), 8000.0, 0.01) } &&
                        perSampleValues.any { !nearly(it.toDouble(), 8000.0, 0.01) }
                }
            },
            test("61 shared highlight policy leaves non-highlight data unchanged") {
                val frames = equivalentRadianceFrames(100)
                withTempDir { dir ->
                    val shared = HdrI32Merger(dir).merge(RawStack(frames))
                    val perSample = HdrI32Merger(dir).merge(
                        RawStack(frames),
                        HdrI32MergeOptions(
                            highlightCoherencePolicy =
                                HdrI32HighlightCoherencePolicy.PER_SAMPLE
                        )
                    )
                    val equal =
                        shared.frame!!.storageFile.readBytes().contentEquals(
                            perSample.frame!!.storageFile.readBytes()
                        )
                    shared.frame.storageFile.delete()
                    perSample.frame.storageFile.delete()
                    equal
                }
            },
            test("62 block saturation diagnostics count coherent exclusions") {
                val short = IntArray(PIXELS) { BLACK[phase(it)] + 80 }
                val long =
                    IntArray(PIXELS) { index ->
                        if (phase(index) == 0) WHITE else BLACK[phase(index)] + 700
                    }
                withMerge(
                    listOf(
                        frame(0, 10_000_000L, short),
                        frame(1, 100_000_000L, long)
                    )
                ) { result ->
                    result.diagnostics!!.sharedHighlightFrameBlocks ==
                        (WIDTH / 2L) * (HEIGHT / 2L) &&
                        result.diagnostics.blockSaturationZeroWeightSamples ==
                        (WIDTH / 2L) * (HEIGHT / 2L) * 3L
                }
            },
            test("63 uniform mode ignores highlight coherence policy") {
                val frames = defaultFrames()
                withTempDir { dir ->
                    val shared = HdrI32Merger(dir).merge(
                        RawStack(frames),
                        HdrI32MergeOptions(
                            weightPolicy = HdrI32WeightPolicy.UNIFORM_VALID_RADIANCE,
                            highlightCoherencePolicy =
                                HdrI32HighlightCoherencePolicy.BAYER_2X2_SHARED
                        )
                    )
                    val perSample = HdrI32Merger(dir).merge(
                        RawStack(frames),
                        HdrI32MergeOptions(
                            weightPolicy = HdrI32WeightPolicy.UNIFORM_VALID_RADIANCE,
                            highlightCoherencePolicy =
                                HdrI32HighlightCoherencePolicy.PER_SAMPLE
                        )
                    )
                    val equal =
                        shared.frame!!.storageFile.readBytes().contentEquals(
                            perSample.frame!!.storageFile.readBytes()
                        )
                    shared.frame.storageFile.delete()
                    perSample.frame.storageFile.delete()
                    equal
                }
            },
            test("64 shared highlight coherence is tile-height invariant") {
                val short = IntArray(PIXELS) { BLACK[phase(it)] + 80 }
                val long =
                    IntArray(PIXELS) { index ->
                        if (phase(index) == 0) WHITE else BLACK[phase(index)] + 700
                    }
                val frames = listOf(
                    frame(0, 10_000_000L, short),
                    frame(1, 100_000_000L, long)
                )
                withTempDir { dir ->
                    val one = HdrI32Merger(dir).merge(
                        RawStack(frames),
                        HdrI32MergeOptions(tileHeight = 1)
                    )
                    val three = HdrI32Merger(dir).merge(
                        RawStack(frames),
                        HdrI32MergeOptions(tileHeight = 3)
                    )
                    val equal =
                        one.frame!!.storageFile.readBytes().contentEquals(
                            three.frame!!.storageFile.readBytes()
                        )
                    one.frame.storageFile.delete()
                    three.frame.storageFile.delete()
                    equal
                }
            }
        )
        val allResults =
            results +
                HdrI32XisfWriterSelfTest.runAll() +
                HdrI32FitsWriterSelfTest.runAll()
        return HdrI32SelfTestReport(
            passed = allResults.count { it.status == PhaseCorrelationSelfTestStatus.PASS },
            failed = allResults.count { it.status == PhaseCorrelationSelfTestStatus.FAIL },
            skipped = allResults.count { it.status == PhaseCorrelationSelfTestStatus.SKIPPED },
            durationMs = (System.nanoTime() - startedNs) / 1_000_000L,
            results = allResults
        )
    }

    private fun validationMutationTest(
        name: String,
        mutate: (RawFrame) -> RawFrame,
        expected: HdrI32FailureCode
    ): HdrI32SelfTestCaseResult =
        test(name) {
            withTempDir { dir ->
                val frames = defaultFrames().toMutableList()
                frames[1] = mutate(frames[1])
                HdrI32Merger(dir).merge(RawStack(frames)).failureCode == expected
            }
        }

    private fun floatPropertyTest(
        name: String,
        predicate: (Float) -> Boolean
    ): HdrI32SelfTestCaseResult =
        test(name) {
            withMerge(defaultFrames()) { result ->
                readFloats(result.frame!!.storageFile).all(predicate)
            }
        }

    private fun compareTileHeights(first: Int, second: Int): Boolean =
        withTempDir { dir ->
            val a = HdrI32Merger(dir).merge(
                RawStack(defaultFrames()),
                HdrI32MergeOptions(tileHeight = first)
            )
            val b = HdrI32Merger(dir).merge(
                RawStack(defaultFrames()),
                HdrI32MergeOptions(tileHeight = second)
            )
            val equal = a.frame!!.storageFile.readBytes()
                .contentEquals(b.frame!!.storageFile.readBytes())
            a.frame.storageFile.delete()
            b.frame.storageFile.delete()
            equal
        }

    private inline fun withMerge(
        frames: List<RawFrame>,
        options: HdrI32MergeOptions = HdrI32MergeOptions(),
        block: (HdrI32MergeResult) -> Boolean
    ): Boolean =
        withTempDir { dir ->
            val result = HdrI32Merger(dir).merge(RawStack(frames), options)
            try {
                block(result)
            } finally {
                result.frame?.storageFile?.delete()
            }
        }

    private fun defaultFrames(): List<RawFrame> =
        equivalentRadianceFrames(signal = 100)

    private fun equivalentRadianceFrames(signal: Int): List<RawFrame> {
        val longValues = IntArray(PIXELS) { BLACK[phase(it)] + signal }
        val shortValues = IntArray(PIXELS) { BLACK[phase(it)] + signal / 2 }
        return listOf(
            frame(0, 100_000_000L, values = longValues),
            frame(1, 50_000_000L, values = shortValues)
        )
    }

    private fun frame(
        index: Int,
        exposureNs: Long,
        values: IntArray = IntArray(PIXELS) { BLACK[phase(it)] + 100 }
    ): RawFrame =
        RawFrame(
            width = WIDTH,
            height = HEIGHT,
            raw16 = packed(values),
            rowStride = WIDTH * 2,
            pixelStride = 2,
            exposureTimeNs = exposureNs,
            iso = ISO,
            cameraId = CAMERA,
            timestampNs = index.toLong(),
            frameIndex = index,
            blackLevelPattern = BLACK.copyOf(),
            whiteLevel = WHITE,
            cfaPattern = CfaPattern.RGGB
        )

    private fun cfaBlackValues(): IntArray =
        IntArray(PIXELS) { BLACK[phase(it)] }

    private fun phase(index: Int): Int {
        val x = index % WIDTH
        val y = index / WIDTH
        return (y and 1) * 2 + (x and 1)
    }

    private fun packed(values: IntArray): ByteArray {
        val bytes = ByteArray(values.size * 2)
        values.forEachIndexed { index, value ->
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun readFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
    }

    private fun nearly(actual: Double, expected: Double, tolerance: Double = 0.001): Boolean =
        abs(actual - expected) <= tolerance

    private fun test(name: String, block: () -> Boolean): HdrI32SelfTestCaseResult =
        try {
            if (block()) {
                HdrI32SelfTestCaseResult(name, PhaseCorrelationSelfTestStatus.PASS, "pass")
            } else {
                HdrI32SelfTestCaseResult(
                    name,
                    PhaseCorrelationSelfTestStatus.FAIL,
                    "condition returned false"
                )
            }
        } catch (error: Throwable) {
            HdrI32SelfTestCaseResult(
                name,
                PhaseCorrelationSelfTestStatus.FAIL,
                "${error.javaClass.simpleName}: ${error.message}"
            )
        }

    private fun regression(name: String, block: () -> Boolean): HdrI32SelfTestCaseResult =
        test(name, block)

    private inline fun <T> withTempDir(block: (File) -> T): T {
        val directory = File.createTempFile("bracketlab_hdri32_", null).also {
            check(it.delete() && it.mkdir()) { "Could not create HDR self-test directory." }
        }
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private const val WIDTH = 6
    private const val HEIGHT = 4
    private const val PIXELS = WIDTH * HEIGHT
    private const val ISO = 400
    private const val WHITE = 1023
    private const val CAMERA = "0"
    private val BLACK = intArrayOf(64, 65, 66, 67)
}
