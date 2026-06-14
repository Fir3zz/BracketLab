package com.lab.bracketlab.processing.calibration

import com.lab.bracketlab.processing.align.LandscapeAlignmentOptions
import com.lab.bracketlab.processing.align.LandscapeAlignmentReport
import com.lab.bracketlab.processing.align.LandscapeAlignmentStatus
import com.lab.bracketlab.processing.align.LandscapeFrameAlignment
import com.lab.bracketlab.processing.align.PhaseCorrelationSelfTestStatus
import com.lab.bracketlab.processing.align.ReferenceSelectionMethod
import com.lab.bracketlab.processing.debug.IncrementalDiagnosticReport
import com.lab.bracketlab.processing.debug.SingleFlightGuard
import com.lab.bracketlab.processing.io.Raw16DngWriter
import com.lab.bracketlab.processing.model.AlignmentResult
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.stack.AlignedRaw16StackOptions
import com.lab.bracketlab.processing.stack.AlignedRaw16StackProcessor
import com.lab.bracketlab.processing.stack.RawStackAggregationMode
import com.lab.bracketlab.processing.stack.RawStackAggregationOptions
import com.lab.bracketlab.processing.storage.FileBackedRaw16FrameStorage
import java.io.File

data class MasterDarkSelfTestCaseResult(
    val name: String,
    val status: PhaseCorrelationSelfTestStatus,
    val message: String
)

data class MasterDarkSelfTestReport(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val durationMs: Long,
    val results: List<MasterDarkSelfTestCaseResult>
)

object MasterDarkSelfTest {
    fun runAll(): MasterDarkSelfTestReport {
        val startedNs = System.nanoTime()
        val results = listOf(
            test("1 empty dark stack fails") {
                withTempDir { dir ->
                    MasterDarkProcessor(dir)
                        .createMasterDark(RawStack(emptyList()))
                        .failureCode == DarkCalibrationFailureCode.EMPTY_DARK_STACK
                }
            },
            test("2 single dark frame follows minimum policy") {
                withTempDir { dir ->
                    val frame = darkFrames(1).first()
                    val allowed = MasterDarkProcessor(dir).createMasterDark(
                        RawStack(listOf(frame)),
                        options(minimum = 1, allowSingle = true)
                    )
                    allowed.masterDark?.rawFile?.delete()
                    val rejected = MasterDarkProcessor(dir).createMasterDark(
                        RawStack(listOf(frame)),
                        options(minimum = 2, allowSingle = false)
                    )
                    allowed.success &&
                        rejected.failureCode == DarkCalibrationFailureCode.INSUFFICIENT_DARK_FRAMES
                }
            },
            test("3 identical inputs produce identical master") {
                withTempDir { dir ->
                    val frames = darkFrames(7)
                    val first = create(dir, frames, DarkAggregationPolicy.AUTO)
                    val firstBytes = first.masterDark!!.rawFile.readBytes()
                    val second = create(dir, frames, DarkAggregationPolicy.AUTO)
                    val secondBytes = second.masterDark!!.rawFile.readBytes()
                    first.masterDark.rawFile.delete()
                    second.masterDark.rawFile.delete()
                    firstBytes.contentEquals(secondBytes)
                }
            },
            creationModeTest("4 mean aggregation works", DarkAggregationPolicy.MEAN, RawStackAggregationMode.MEAN),
            creationModeTest(
                "5 min-max aggregation works",
                DarkAggregationPolicy.MIN_MAX_REJECTED_MEAN,
                RawStackAggregationMode.MIN_MAX_REJECTED_MEAN
            ),
            creationModeTest(
                "6 sigma aggregation works",
                DarkAggregationPolicy.SIGMA_CLIPPED_MEAN,
                RawStackAggregationMode.SIGMA_CLIPPED_MEAN
            ),
            incompatibilityTest(
                "7 mixed dimensions rejected",
                { it.copy(width = it.width + 1, rowStride = (it.width + 1) * 2, raw16 = packed(IntArray((it.width + 1) * it.height) { 70 })) },
                DarkCalibrationFailureCode.INCOMPATIBLE_DARK_DIMENSIONS
            ),
            incompatibilityTest(
                "8 mixed CFA rejected",
                { it.copy(cfaPattern = CfaPattern.BGGR) },
                DarkCalibrationFailureCode.INCOMPATIBLE_DARK_CFA
            ),
            incompatibilityTest(
                "9 mixed ISO rejected",
                { it.copy(iso = it.iso + 1) },
                DarkCalibrationFailureCode.INCOMPATIBLE_DARK_ISO
            ),
            incompatibilityTest(
                "10 mixed exposure rejected",
                { it.copy(exposureTimeNs = it.exposureTimeNs * 2) },
                DarkCalibrationFailureCode.INCOMPATIBLE_DARK_EXPOSURE
            ),
            incompatibilityTest(
                "11 mixed camera rejected",
                { it.copy(cameraId = "other") },
                DarkCalibrationFailureCode.INCOMPATIBLE_DARK_CAMERA
            ),
            test("12 file-backed source equals in-memory source") {
                withTempDir { dir ->
                    val memory = darkFrames(7)
                    val fileBacked = memory.map { frame ->
                        val file = File(dir, "source_${frame.frameIndex}.raw16")
                        file.writeBytes(frame.raw16!!)
                        frame.copy(
                            raw16 = null,
                            raw16Storage = FileBackedRaw16FrameStorage(file, true)
                        )
                    }
                    val memoryResult = create(dir, memory, DarkAggregationPolicy.SIGMA_CLIPPED_MEAN)
                    val fileResult = create(dir, fileBacked, DarkAggregationPolicy.SIGMA_CLIPPED_MEAN)
                    val equal = memoryResult.masterDark!!.rawFile.readBytes()
                        .contentEquals(fileResult.masterDark!!.rawFile.readBytes())
                    memoryResult.masterDark.rawFile.delete()
                    fileResult.masterDark.rawFile.delete()
                    fileBacked.forEach { it.resolvedRaw16Storage()?.deleteIfOwned() }
                    equal
                }
            },
            test("13 tiled master equals full-frame reference") {
                withTempDir { dir ->
                    val frames = darkFrames(7)
                    val tiled = MasterDarkProcessor(dir).createMasterDark(
                        RawStack(frames),
                        options(DarkAggregationPolicy.SIGMA_CLIPPED_MEAN, tileHeight = 1)
                    )
                    val full = MasterDarkProcessor(dir).createMasterDark(
                        RawStack(frames),
                        options(DarkAggregationPolicy.SIGMA_CLIPPED_MEAN, tileHeight = HEIGHT)
                    )
                    val equal = tiled.masterDark!!.rawFile.readBytes()
                        .contentEquals(full.masterDark!!.rawFile.readBytes())
                    tiled.masterDark.rawFile.delete()
                    full.masterDark.rawFile.delete()
                    equal
                }
            },
            test("14 temporary files cleaned on store success") {
                withTempDir { dir ->
                    val result = create(dir, darkFrames(7), DarkAggregationPolicy.SIGMA_CLIPPED_MEAN)
                    val source = result.masterDark!!.rawFile
                    val store = MasterDarkStore(File(dir, "store"))
                    val stored = store.save(result.masterDark)
                    stored.success && !source.exists() &&
                        stored.masterDark!!.rawFile.exists() &&
                        stored.masterDark.metadataFile!!.exists() &&
                        store.cleanupIncompleteWrites() &&
                        File(dir, "store").walkTopDown().none { it.name.endsWith(".tmp") }
                }
            },
            test("15 temporary files cleaned on simulated failure") {
                withTempDir { dir ->
                    val forcedOutput = File(dir, "forced_failure.raw16.tmp")
                    val result = MasterDarkProcessor(dir) { workingDirectory ->
                        File(workingDirectory, forcedOutput.name).also {
                            check(it.mkdir())
                        }
                    }.createMasterDark(
                        RawStack(darkFrames(7)),
                        options(DarkAggregationPolicy.SIGMA_CLIPPED_MEAN)
                    )
                    check(!result.success) { "Simulated output failure unexpectedly succeeded." }
                    check(result.temporaryCleanupSucceeded) {
                        "Processor reported unsuccessful temporary cleanup."
                    }
                    check(!forcedOutput.exists()) {
                        "Forced temporary output still exists at ${forcedOutput.absolutePath}."
                    }
                    true
                }
            },
            test("16 dark equal black leaves light unchanged") {
                DarkSubtractor.subtractDarkRaw16(1000, 64, 64) == 1000
            },
            test("17 dark signal above black is subtracted") {
                DarkSubtractor.subtractDarkRaw16(1000, 74, 64) == 990
            },
            test("18 corrected value clamps to black") {
                DarkSubtractor.subtractDarkRaw16(66, 100, 64) == 64
            },
            test("19 unsigned RAW16 values are handled") {
                DarkSubtractor.subtractDarkRaw16(65535, 65535, 64) == 64
            },
            test("20 per-CFA black level is used") {
                val black = intArrayOf(10, 20, 30, 40)
                val values = listOf(
                    DarkSubtractor.subtractDarkRaw16At(100, 30, black, 0, 0),
                    DarkSubtractor.subtractDarkRaw16At(100, 30, black, 1, 0),
                    DarkSubtractor.subtractDarkRaw16At(100, 30, black, 0, 1),
                    DarkSubtractor.subtractDarkRaw16At(100, 50, black, 1, 1)
                )
                values == listOf(80, 90, 100, 90)
            },
            test("21 black level is not subtracted twice") {
                DarkSubtractor.subtractDarkRaw16(200, 80, 64) == 184
            },
            test("22 subtraction never returns negative") {
                DarkSubtractor.subtractDarkRaw16(0, 65535, 0) == 0
            },
            test("23 subtraction never exceeds RAW16") {
                DarkSubtractor.subtractDarkRaw16(65535, 0, 0) == 65535
            },
            matcherTest("24 exact matching master selected") { light, masters ->
                MasterDarkMatcher.findCompatibleMasterDark(light, masters, strictOptions()).selected == masters.first()
            },
            matcherTest("25 incompatible ISO rejected") { light, masters ->
                MasterDarkMatcher.findCompatibleMasterDark(
                    light.copy(iso = light.iso + 1),
                    masters,
                    strictOptions()
                ).failureCode == DarkCalibrationFailureCode.MASTER_DARK_NOT_FOUND
            },
            matcherTest("26 incompatible exposure rejected") { light, masters ->
                MasterDarkMatcher.findCompatibleMasterDark(
                    light.copy(exposureTimeNs = light.exposureTimeNs * 2),
                    masters,
                    strictOptions()
                ).failureCode == DarkCalibrationFailureCode.MASTER_DARK_NOT_FOUND
            },
            matcherTest("27 incompatible CFA rejected") { light, masters ->
                MasterDarkMatcher.findCompatibleMasterDark(
                    light.copy(cfaPattern = CfaPattern.BGGR),
                    masters,
                    strictOptions()
                ).failureCode == DarkCalibrationFailureCode.MASTER_DARK_NOT_FOUND
            },
            matcherTest("28 incompatible dimensions rejected") { light, masters ->
                MasterDarkMatcher.findCompatibleMasterDark(
                    light.copy(width = light.width + 2),
                    masters,
                    strictOptions()
                ).failureCode == DarkCalibrationFailureCode.MASTER_DARK_NOT_FOUND
            },
            test("29 newest selected when otherwise equal") {
                withTempDir { dir ->
                    val older = fakeMasterDark(dir, "older", frameCount = 7, created = 1000L)
                    val newer = fakeMasterDark(dir, "newer", frameCount = 7, created = 2000L)
                    MasterDarkMatcher.findCompatibleMasterDark(
                        lightFrames().first(),
                        listOf(older, newer),
                        strictOptions()
                    ).selected == newer
                }
            },
            test("30 highest frame count selected before newest") {
                withTempDir { dir ->
                    val many = fakeMasterDark(dir, "many", frameCount = 15, created = 1000L)
                    val newFew = fakeMasterDark(dir, "few", frameCount = 7, created = 2000L)
                    MasterDarkMatcher.findCompatibleMasterDark(
                        lightFrames().first(),
                        listOf(newFew, many),
                        strictOptions()
                    ).selected == many
                }
            },
            test("31 no compatible dark returns clear result") {
                MasterDarkMatcher.findCompatibleMasterDark(
                    lightFrames().first(),
                    emptyList(),
                    strictOptions()
                ).failureCode == DarkCalibrationFailureCode.MASTER_DARK_NOT_FOUND
            },
            test("32 stack without dark remains byte-identical") {
                val frames = lightFrames()
                val baseline = stack(frames, RawStackAggregationMode.MEAN).outputRaw16Copy()
                val explicitOff = stack(
                    frames,
                    RawStackAggregationMode.MEAN,
                    darkInput = DarkCalibrationInput.OFF
                ).outputRaw16Copy()
                baseline != null && baseline.contentEquals(explicitOff)
            },
            test("33 stack with dark subtracts expected signal") {
                withTempDir { dir ->
                    val master = fakeMasterDark(dir, "expected")
                    val result = stack(
                        constantLightFrames(1000),
                        RawStackAggregationMode.MEAN,
                        DarkCalibrationInput.use(master, strictOptions())
                    )
                    result.darkCalibrationApplied && unpack(result.outputRaw16Copy()!!).all { it == 990 }
                }
            },
            darkIntegrationModeTest("34 mean with dark works", RawStackAggregationMode.MEAN),
            darkIntegrationModeTest(
                "35 min-max with dark works",
                RawStackAggregationMode.MIN_MAX_REJECTED_MEAN
            ),
            darkIntegrationModeTest(
                "36 sigma with dark works",
                RawStackAggregationMode.SIGMA_CLIPPED_MEAN
            ),
            test("37 missing dark warns or fails by option") {
                val frames = lightFrames()
                val warn = stack(
                    frames,
                    RawStackAggregationMode.MEAN,
                    DarkCalibrationInput.use(
                        null,
                        strictOptions().copy(
                            missingMasterDarkBehavior = MissingMasterDarkBehavior.WARN_AND_CONTINUE
                        )
                    )
                )
                val fail = stack(
                    frames,
                    RawStackAggregationMode.MEAN,
                    DarkCalibrationInput.use(
                        null,
                        strictOptions().copy(
                            missingMasterDarkBehavior = MissingMasterDarkBehavior.FAIL
                        )
                    )
                )
                warn.success && !warn.darkCalibrationApplied &&
                    warn.warnings.any { it.code.name == "DARK_CALIBRATION_SKIPPED" } &&
                    !fail.success
            },
            test("38 DNG writer accepts dark-corrected result") {
                withTempDir { dir ->
                    val result = stack(
                        lightFrames(),
                        RawStackAggregationMode.MEAN,
                        DarkCalibrationInput.use(fakeMasterDark(dir, "writer"), strictOptions())
                    )
                    Raw16DngWriter.validatePackedResult(result).success
                }
            },
            test("39 source light frames remain unchanged") {
                withTempDir { dir ->
                    val frames = lightFrames()
                    val before = frames.map { it.raw16!!.copyOf() }
                    stack(
                        frames,
                        RawStackAggregationMode.SIGMA_CLIPPED_MEAN,
                        DarkCalibrationInput.use(fakeMasterDark(dir, "light_unchanged"), strictOptions())
                    )
                    frames.indices.all { before[it].contentEquals(frames[it].raw16) }
                }
            },
            test("40 MasterDark source remains unchanged") {
                withTempDir { dir ->
                    val master = fakeMasterDark(dir, "dark_unchanged")
                    val before = master.rawFile.readBytes()
                    stack(
                        lightFrames(),
                        RawStackAggregationMode.MEAN,
                        DarkCalibrationInput.use(master, strictOptions())
                    )
                    before.contentEquals(master.rawFile.readBytes())
                }
            },
            test("41 file-backed tiled path avoids full-frame heap output") {
                withTempDir { dir ->
                    val master = fakeMasterDark(dir, "filebacked")
                    val output = File(dir, "stack.raw16")
                    val frames = fileBackedFrames(dir, lightFrames())
                    val result = AlignedRaw16StackProcessor().processToPackedFile(
                        RawStack(frames),
                        identityReport(frames),
                        AlignedRaw16StackOptions(
                            aggregationOptions = RawStackAggregationOptions(),
                            darkCalibration = DarkCalibrationInput.use(master, strictOptions()),
                            tileHeight = 2
                        ),
                        output
                    )
                    result.success && result.outputRaw16 == null &&
                        output.length() == WIDTH.toLong() * HEIGHT.toLong() * 2L
                }
            },
            storageEstimatorTest("42 storage estimator handles 50 frames", 50),
            storageEstimatorTest("43 storage estimator handles 100 frames", 100),
            test("44 incremental report survives failure") {
                withTempDir { dir ->
                    val file = File(dir, "report.txt")
                    runCatching {
                        IncrementalDiagnosticReport(file).use {
                            it.append("dark session started")
                            it.append("master creation started")
                            error("simulated")
                        }
                    }
                    file.readText().contains("master creation started")
                }
            },
            test("45 dark processing guard prevents concurrency") {
                val guard = SingleFlightGuard()
                val first = guard.tryAcquire()
                val second = guard.tryAcquire()
                guard.release()
                first && !second
            }
        )
        return MasterDarkSelfTestReport(
            passed = results.count { it.status == PhaseCorrelationSelfTestStatus.PASS },
            failed = results.count { it.status == PhaseCorrelationSelfTestStatus.FAIL },
            skipped = results.count { it.status == PhaseCorrelationSelfTestStatus.SKIPPED },
            durationMs = (System.nanoTime() - startedNs) / 1_000_000L,
            results = results
        )
    }

    private fun creationModeTest(
        name: String,
        policy: DarkAggregationPolicy,
        expectedMode: RawStackAggregationMode
    ): MasterDarkSelfTestCaseResult =
        test(name) {
            withTempDir { dir ->
                val result = create(dir, darkFrames(7), policy)
                val pass = result.success && result.aggregationMode == expectedMode
                result.masterDark?.rawFile?.delete()
                pass
            }
        }

    private fun incompatibilityTest(
        name: String,
        mutate: (RawFrame) -> RawFrame,
        expected: DarkCalibrationFailureCode
    ): MasterDarkSelfTestCaseResult =
        test(name) {
            withTempDir { dir ->
                val frames = darkFrames(3).toMutableList()
                frames[2] = mutate(frames[2])
                MasterDarkProcessor(dir)
                    .createMasterDark(RawStack(frames), options())
                    .failureCode == expected
            }
        }

    private fun matcherTest(
        name: String,
        block: (RawFrame, List<MasterDark>) -> Boolean
    ): MasterDarkSelfTestCaseResult =
        test(name) {
            withTempDir { dir ->
                block(lightFrames().first(), listOf(fakeMasterDark(dir, "exact")))
            }
        }

    private fun darkIntegrationModeTest(
        name: String,
        mode: RawStackAggregationMode
    ): MasterDarkSelfTestCaseResult =
        test(name) {
            withTempDir { dir ->
                val result = stack(
                    lightFrames(),
                    mode,
                    DarkCalibrationInput.use(fakeMasterDark(dir, mode.name), strictOptions())
                )
                result.success && result.darkCalibrationApplied
            }
        }

    private fun storageEstimatorTest(
        name: String,
        frames: Int
    ): MasterDarkSelfTestCaseResult =
        test(name) {
            withTempDir { dir ->
                val estimate = DarkStorageEstimator.estimate(
                    width = 4032,
                    height = 3024,
                    darkFrameCount = frames,
                    outputDirectory = dir
                )
                estimate.darkInputBytes ==
                    estimate.frameBytes * frames.toLong() &&
                    estimate.totalRequiredBytes > estimate.baseRequiredBytes &&
                    estimate.availableBytes >= 0L &&
                    estimate.selectedStrategy == DarkStorageStrategy.FILE_BACKED_TILED
            }
        }

    private fun create(
        directory: File,
        frames: List<RawFrame>,
        policy: DarkAggregationPolicy
    ): DarkCalibrationResult =
        MasterDarkProcessor(directory).createMasterDark(
            RawStack(frames),
            options(policy)
        )

    private fun options(
        policy: DarkAggregationPolicy = DarkAggregationPolicy.AUTO,
        minimum: Int = 1,
        allowSingle: Boolean = true,
        tileHeight: Int = 2
    ): DarkCalibrationOptions =
        DarkCalibrationOptions(
            darkPolicy = DarkPolicy.CAPTURE_MASTER_DARK,
            aggregationPolicy = policy,
            minimumDarkFrames = minimum,
            allowSingleDarkFrame = allowSingle,
            exposureRelativeTolerance = 0.0,
            exposureAbsoluteToleranceNs = 0L,
            tileHeight = tileHeight
        )

    private fun strictOptions(): DarkCalibrationOptions =
        DarkCalibrationOptions(
            darkPolicy = DarkPolicy.USE_COMPATIBLE_MASTER_DARK,
            exposureRelativeTolerance = 0.0,
            exposureAbsoluteToleranceNs = 0L,
            missingMasterDarkBehavior = MissingMasterDarkBehavior.FAIL
        )

    private fun darkFrames(count: Int): List<RawFrame> =
        List(count) { frameIndex ->
            val values = IntArray(WIDTH * HEIGHT) { pixel ->
                val base = BLACK_LEVELS[(pixel / WIDTH and 1) * 2 + (pixel % WIDTH and 1)] + 10
                when {
                    frameIndex == count - 1 && count >= 5 && pixel == 3 -> 65535
                    else -> base + (frameIndex % 3)
                }
            }
            frame(values, frameIndex)
        }

    private fun lightFrames(): List<RawFrame> =
        List(7) { frameIndex ->
            frame(IntArray(WIDTH * HEIGHT) { 1000 + frameIndex }, frameIndex)
        }

    private fun constantLightFrames(value: Int): List<RawFrame> =
        List(7) { frameIndex -> frame(IntArray(WIDTH * HEIGHT) { value }, frameIndex) }

    private fun frame(values: IntArray, frameIndex: Int): RawFrame =
        RawFrame(
            width = WIDTH,
            height = HEIGHT,
            raw16 = packed(values),
            rowStride = WIDTH * 2,
            pixelStride = 2,
            exposureTimeNs = EXPOSURE_NS,
            iso = ISO,
            cameraId = CAMERA_ID,
            timestampNs = frameIndex.toLong(),
            frameIndex = frameIndex,
            blackLevelPattern = BLACK_LEVELS.copyOf(),
            whiteLevel = 65535,
            cfaPattern = CfaPattern.RGGB
        )

    private fun fakeMasterDark(
        directory: File,
        id: String,
        darkValue: Int? = null,
        frameCount: Int = 7,
        created: Long = 1000L
    ): MasterDark {
        val file = File(directory, "$id.raw16")
        val values = IntArray(WIDTH * HEIGHT) { index ->
            darkValue ?: (BLACK_LEVELS[((index / WIDTH) and 1) * 2 + (index % WIDTH and 1)] + 10)
        }
        file.writeBytes(packed(values))
        return MasterDark(
            metadata = MasterDarkMetadata(
                id = id,
                width = WIDTH,
                height = HEIGHT,
                rowStride = WIDTH * 2,
                pixelStride = 2,
                cfaPattern = CfaPattern.RGGB,
                cameraId = CAMERA_ID,
                iso = ISO,
                exposureTimeNs = EXPOSURE_NS,
                frameCount = frameCount,
                aggregationMode = RawStackAggregationMode.SIGMA_CLIPPED_MEAN,
                blackLevelPattern = BLACK_LEVELS.copyOf(),
                whiteLevel = 65535,
                createdAtMillis = created,
                appVersion = "test",
                rawFilename = file.name,
                sourceFrames = emptyList(),
                minimumDarkValue = values.minOrNull(),
                maximumDarkValue = values.maxOrNull(),
                meanDarkValue = values.average()
            ),
            rawFile = file
        )
    }

    private fun fileBackedFrames(directory: File, frames: List<RawFrame>): List<RawFrame> =
        frames.map { frame ->
            val file = File(directory, "light_${frame.frameIndex}.raw16")
            file.writeBytes(frame.raw16!!)
            frame.copy(
                raw16 = null,
                raw16Storage = FileBackedRaw16FrameStorage(file, true),
                sourceFilePath = file.absolutePath
            )
        }

    private fun stack(
        frames: List<RawFrame>,
        mode: RawStackAggregationMode,
        darkInput: DarkCalibrationInput = DarkCalibrationInput.OFF
    ) =
        AlignedRaw16StackProcessor().process(
            RawStack(frames),
            identityReport(frames),
            AlignedRaw16StackOptions(
                aggregationOptions = RawStackAggregationOptions(mode = mode),
                darkCalibration = darkInput,
                tileHeight = 2
            )
        )

    private fun identityReport(frames: List<RawFrame>): LandscapeAlignmentReport {
        val referencePosition = frames.size / 2
        val reference = frames[referencePosition]
        val results = frames.mapIndexed { position, frame ->
            val alignment = AlignmentResult(
                frameIndex = frame.frameIndex,
                mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
                transform = RawTransform.IDENTITY,
                confidence = 1.0,
                accepted = true,
                response = if (position == referencePosition) null else 1.0
            )
            LandscapeFrameAlignment(
                framePosition = position,
                frameIndex = frame.frameIndex,
                isReference = position == referencePosition,
                proxyTypeUsed = null,
                lumaFallbackUsed = false,
                targetExposureTimeSeconds = frame.exposureTimeSeconds,
                referenceExposureTimeSeconds = reference.exposureTimeSeconds,
                phaseResponse = alignment.response,
                dxRawPixels = 0.0,
                dyRawPixels = 0.0,
                overlapFraction = 1.0,
                accepted = true,
                lowConfidence = false,
                rejectionReason = null,
                diagnosticMessage = "identity",
                warnings = emptyList(),
                alignmentResult = alignment
            )
        }
        return LandscapeAlignmentReport(
            status = LandscapeAlignmentStatus.SUCCESS,
            success = true,
            partialSuccess = false,
            selectedReferenceFrameIndex = reference.frameIndex,
            selectedReferencePosition = referencePosition,
            referenceSelectionMethod = ReferenceSelectionMethod.MIDDLE_FRAME_INDEX,
            referenceExposureTimeSeconds = reference.exposureTimeSeconds,
            totalFrameCount = frames.size,
            acceptedFrameCount = frames.size,
            rejectedFrameCount = 0,
            lowConfidenceFrameCount = 0,
            frameResults = results,
            alignmentResults = results.map { it.alignmentResult },
            warnings = emptyList(),
            fatalError = null,
            fatalMessage = null,
            processingDurationMs = 0L,
            options = LandscapeAlignmentOptions(exposureNormalizeProxies = false)
        )
    }

    private fun packed(values: IntArray): ByteArray {
        val bytes = ByteArray(values.size * 2)
        values.forEachIndexed { index, value ->
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun unpack(bytes: ByteArray): IntArray =
        IntArray(bytes.size / 2) { index ->
            (bytes[index * 2].toInt() and 0xFF) or
                ((bytes[index * 2 + 1].toInt() and 0xFF) shl 8)
        }

    private inline fun <T> withTempDir(block: (File) -> T): T {
        val directory = File.createTempFile("bracketlab_masterdark_", null).also {
            check(it.delete() && it.mkdir()) {
                "Could not create MasterDark self-test directory."
            }
        }
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun test(
        name: String,
        block: () -> Boolean
    ): MasterDarkSelfTestCaseResult =
        try {
            if (block()) {
                MasterDarkSelfTestCaseResult(name, PhaseCorrelationSelfTestStatus.PASS, "pass")
            } else {
                MasterDarkSelfTestCaseResult(
                    name,
                    PhaseCorrelationSelfTestStatus.FAIL,
                    "condition returned false"
                )
            }
        } catch (error: Throwable) {
            MasterDarkSelfTestCaseResult(
                name,
                PhaseCorrelationSelfTestStatus.FAIL,
                "${error.javaClass.simpleName}: ${error.message}"
            )
        }

    private const val WIDTH = 6
    private const val HEIGHT = 4
    private const val ISO = 800
    private const val EXPOSURE_NS = 100_000_000L
    private const val CAMERA_ID = "0"
    private val BLACK_LEVELS = intArrayOf(64, 65, 66, 67)
}
