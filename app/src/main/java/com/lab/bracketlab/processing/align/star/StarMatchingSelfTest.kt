package com.lab.bracketlab.processing.align.star

import com.lab.bracketlab.processing.align.LandscapeAlignmentSelfTest
import com.lab.bracketlab.processing.align.PhaseCorrelationSelfTestStatus
import com.lab.bracketlab.processing.calibration.MasterDarkSelfTest
import com.lab.bracketlab.processing.hdri.HdrI32SelfTest
import com.lab.bracketlab.processing.raw.RawProxySelfTest
import com.lab.bracketlab.processing.raw.RawProxyType
import com.lab.bracketlab.processing.stack.AlignedRaw16StackSelfTest
import kotlin.math.abs
import kotlin.math.hypot

data class StarMatchingSelfTestCase(
    val name: String,
    val status: PhaseCorrelationSelfTestStatus,
    val message: String
)

data class StarMatchingSelfTestReport(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val durationMs: Long,
    val results: List<StarMatchingSelfTestCase>
) {
    fun logLines(): List<String> =
        buildList {
            add("Star matching self-test: ${statusLabel()} ($passed pass, $failed fail, $skipped skipped)")
            results.filter { it.status != PhaseCorrelationSelfTestStatus.PASS }.forEach {
                add("${it.status}: ${it.name}: ${it.message}")
            }
        }

    private fun statusLabel(): String =
        when {
            failed > 0 -> "FAIL"
            passed > 0 -> "PASS"
            else -> "SKIPPED"
        }
}

object StarMatchingSelfTest {
    fun runAll(): StarMatchingSelfTestReport {
        val startedNs = System.nanoTime()
        val results = coreTests() + regressionTests()
        return report(startedNs, results)
    }

    fun runCore(): StarMatchingSelfTestReport {
        val startedNs = System.nanoTime()
        return report(startedNs, coreTests())
    }

    private fun report(
        startedNs: Long,
        results: List<StarMatchingSelfTestCase>
    ): StarMatchingSelfTestReport {
        return StarMatchingSelfTestReport(
            passed = results.count { it.status == PhaseCorrelationSelfTestStatus.PASS },
            failed = results.count { it.status == PhaseCorrelationSelfTestStatus.FAIL },
            skipped = results.count { it.status == PhaseCorrelationSelfTestStatus.SKIPPED },
            durationMs = (System.nanoTime() - startedNs) / 1_000_000L,
            results = results
        )
    }

    private fun coreTests(): List<StarMatchingSelfTestCase> {
        val base = baseStars()
        return listOf(
            test("1 empty catalog list fails") {
                StarAlignmentProcessor().align(emptyList()).fatalError ==
                    StarMatchingFailureCode.EMPTY_CATALOG_SET
            },
            test("2 one catalog returns identity") {
                val report = StarAlignmentProcessor().align(listOf(catalog(0, base)))
                report.success && report.frameResults.single().transform ==
                    StarSimilarityTransform.IDENTITY
            },
            test("3 middle catalog selected") {
                val report =
                    StarAlignmentProcessor().align(
                        listOf(catalog(0, base), catalog(1, base), catalog(2, base))
                    )
                report.referenceFrameIndex == 1 &&
                    report.referenceSelectionReason == StarReferenceSelectionReason.MIDDLE_CATALOG
            },
            test("4 weak middle catalog triggers deterministic fallback") {
                val report =
                    StarAlignmentProcessor().align(
                        listOf(
                            catalog(0, base),
                            catalog(1, base.take(2)),
                            catalog(2, base + extraStars())
                        )
                    )
                report.referenceFrameIndex == 2 &&
                    report.referenceSelectionReason ==
                    StarReferenceSelectionReason.NEAREST_STRONG_CATALOG_FALLBACK
            },
            test("5 reference result is identity") {
                val report =
                    alignTransform(StarSimilarityTransform.fromParameters(8.0, -3.0, 0.0, 1.0))
                report.frameResults.single { it.isReference }.transform ==
                    StarSimilarityTransform.IDENTITY
            },
            transformTest("6 pure positive X translation", 24.0, 0.0, 0.0, 1.0),
            transformTest("7 pure negative X translation", -18.0, 0.0, 0.0, 1.0),
            transformTest("8 pure positive Y translation", 0.0, 21.0, 0.0, 1.0),
            transformTest("9 combined X/Y translation", 17.0, -13.0, 0.0, 1.0),
            transformTest("10 small positive rotation", 0.0, 0.0, 1.25, 1.0),
            transformTest("11 small negative rotation", 0.0, 0.0, -1.10, 1.0),
            transformTest("12 uniform scale above 1", 0.0, 0.0, 0.0, 1.025),
            transformTest("13 uniform scale below 1", 0.0, 0.0, 0.0, 0.975),
            transformTest("14 combined translation rotation scale", 28.0, -19.0, 1.4, 1.018),
            test("15 one incorrect match is rejected") {
                val candidates = exactCandidates(base, StarSimilarityTransform.IDENTITY).toMutableList()
                candidates += candidate(base[0], star(99, 990.0, 790.0))
                val result = StarRansacEstimator().estimate(candidates, options())
                result.success && result.outlierCount >= 1 && result.inlierCount >= base.size
            },
            test("16 many incorrect matches with sufficient inliers succeeds") {
                val candidates = exactCandidates(base, StarSimilarityTransform.IDENTITY).toMutableList()
                repeat(5) {
                    candidates += candidate(base[it], star(100 + it, 950.0 - it * 31.0, 60.0 + it * 47.0))
                }
                val result = StarRansacEstimator().estimate(candidates, options(minimumInlierRatio = 0.45))
                result.success && result.inlierCount >= base.size
            },
            test("17 too many outliers fails validation") {
                val good = exactCandidates(base.take(4), StarSimilarityTransform.IDENTITY)
                val bad =
                    (0 until 14).map {
                        candidate(
                            star(200 + it, 100.0 + it * 31.0, 120.0 + (it % 4) * 73.0),
                            star(300 + it, 900.0 - it * 19.0, 60.0 + (it % 5) * 91.0)
                        )
                    }
                val result =
                    StarRansacEstimator().estimate(
                        good + bad,
                        options(minimumInlierRatio = 0.50)
                    )
                result.inlierRatio < 0.50
            },
            test("18 isolated hot-pixel detections do not dominate") {
                val reference = base + star(90, 1030.0, 70.0, snr = 6.0, radius = 0.11)
                val target =
                    transformedStars(base, StarSimilarityTransform.fromParameters(12.0, -7.0, 0.0, 1.0)) +
                        star(91, 70.0, 800.0, snr = 6.0, radius = 0.11)
                val report =
                    StarAlignmentProcessor().align(
                        listOf(catalog(0, target), catalog(1, reference)),
                        options()
                    )
                report.frameResults.first().accepted
            },
            test("19 duplicate match candidates are removed") {
                val matches =
                    StarMatcher().match(
                        catalog(1, base),
                        catalog(0, base + base.first().copy(id = 99)),
                        options()
                    ).candidates
                matches.map { it.referenceStar.id }.distinct().size == matches.size &&
                    matches.map { it.targetStar.id }.distinct().size == matches.size
            },
            test("20 one-to-one matching enforced") {
                val result =
                    StarMatcher().match(catalog(1, base), catalog(0, base), options())
                result.candidates.map { it.referenceStar.id }.toSet().size == result.candidates.size &&
                    result.candidates.map { it.targetStar.id }.toSet().size == result.candidates.size
            },
            test("21 small centroid noise recovers transform") {
                val transform = StarSimilarityTransform.fromParameters(9.0, -6.0, 0.3, 1.0)
                val target = transformedStars(base, transform).mapIndexed { index, star ->
                    star.copy(
                        fullX = star.fullX + if ((index and 1) == 0) 0.35 else -0.25,
                        fullY = star.fullY + if ((index and 1) == 0) -0.20 else 0.30,
                        proxyX = (star.fullX + if ((index and 1) == 0) 0.35 else -0.25) / SCALE,
                        proxyY = (star.fullY + if ((index and 1) == 0) -0.20 else 0.30) / SCALE
                    )
                }
                val result =
                    StarAlignmentProcessor().align(
                        listOf(catalog(0, target), catalog(1, base)),
                        options(maximumRms = 2.0)
                    ).frameResults.first()
                result.accepted && (result.rmsResidualRawPixels ?: 99.0) < 1.0
            },
            test("22 RMS increases predictably with noise") {
                val clean = noisyRansac(0.0)
                val noisy = noisyRansac(1.0)
                (clean.rmsResidualRawPixels ?: 99.0) < (noisy.rmsResidualRawPixels ?: 0.0)
            },
            test("23 excessive centroid noise fails RMS policy") {
                val transform = StarSimilarityTransform.fromParameters(8.0, 4.0, 0.0, 1.0)
                val target = transformedStars(base, transform).mapIndexed { index, star ->
                    val noise = if ((index and 1) == 0) 5.0 else -5.0
                    star.copy(
                        fullX = star.fullX + noise,
                        fullY = star.fullY - noise,
                        proxyX = (star.fullX + noise) / SCALE,
                        proxyY = (star.fullY - noise) / SCALE
                    )
                }
                val frame =
                    StarAlignmentProcessor().align(
                        listOf(catalog(0, target), catalog(1, base)),
                        options(maximumRms = 1.0, reprojection = 12.0)
                    ).frameResults.first()
                !frame.accepted &&
                    frame.failureCode == StarMatchingFailureCode.EXCESSIVE_RMS_ERROR
            },
            test("24 collinear stars rejected") {
                val line = (0 until 8).map { star(it, 100.0 + it * 90.0, 200.0 + it * 30.0) }
                val target =
                    transformedStars(line, StarSimilarityTransform.fromParameters(8.0, 4.0, 0.0, 1.0))
                val frame =
                    StarAlignmentProcessor().align(
                        listOf(catalog(0, target), catalog(1, line)),
                        options()
                    ).frameResults.first()
                !frame.accepted &&
                    frame.failureCode == StarMatchingFailureCode.DEGENERATE_GEOMETRY
            },
            test("25 tiny-region stars rejected") {
                val tiny =
                    (0 until 8).map {
                        star(it, 500.0 + (it % 4) * 2.0, 400.0 + (it / 4) * 2.0)
                    }
                val target =
                    transformedStars(tiny, StarSimilarityTransform.fromParameters(1.0, 1.0, 0.0, 1.0))
                val frame =
                    StarAlignmentProcessor().align(
                        listOf(catalog(0, target), catalog(1, tiny)),
                        options(minimumCoverage = 0.01)
                    ).frameResults.first()
                !frame.accepted &&
                    frame.failureCode == StarMatchingFailureCode.INSUFFICIENT_SPATIAL_COVERAGE
            },
            test("26 too few stars fails") {
                val report =
                    StarAlignmentProcessor().align(
                        listOf(catalog(0, base.take(2)), catalog(1, base))
                    )
                report.frameResults.first().failureCode ==
                    StarMatchingFailureCode.TOO_FEW_TARGET_STARS
            },
            test("27 duplicate coordinates fail safely") {
                val duplicates = List(6) { star(it, 300.0, 300.0) }
                val report =
                    StarAlignmentProcessor().align(
                        listOf(catalog(0, duplicates), catalog(1, base))
                    )
                !report.frameResults.first().accepted
            },
            test("28 excessive rotation rejected") {
                val frame =
                    alignTransform(
                        StarSimilarityTransform.fromParameters(0.0, 0.0, 8.0, 1.0),
                        options(maxRotation = 2.0)
                    ).frameResults.first()
                !frame.accepted &&
                    frame.failureCode == StarMatchingFailureCode.EXCESSIVE_ROTATION
            },
            test("29 invalid scale rejected") {
                val frame =
                    alignTransform(
                        StarSimilarityTransform.fromParameters(0.0, 0.0, 0.0, 1.08),
                        options(minScale = 0.98, maxScale = 1.02)
                    ).frameResults.first()
                !frame.accepted && frame.failureCode == StarMatchingFailureCode.INVALID_SCALE
            },
            test("30 excessive translation rejected") {
                val frame =
                    alignTransform(
                        StarSimilarityTransform.fromParameters(300.0, 0.0, 0.0, 1.0),
                        options(maxTranslationFraction = 0.10)
                    ).frameResults.first()
                check(!frame.accepted) {
                    "accepted tx=${frame.transform.tx} strategy=${frame.matchingStrategy}"
                }
                check(frame.failureCode == StarMatchingFailureCode.EXCESSIVE_TRANSLATION) {
                    "failure=${frame.failureCode} tx=${frame.transform.tx} message=${frame.diagnosticMessage}"
                }
                true
            },
            test("31 non-finite coordinates rejected") {
                val invalid = base.toMutableList()
                invalid[0] = invalid[0].copy(fullX = Double.NaN)
                val report =
                    StarAlignmentProcessor().align(
                        listOf(catalog(0, invalid.take(4)), catalog(1, base)),
                        options()
                    )
                !report.frameResults.first().accepted
            },
            test("32 non-finite transform rejected") {
                !StarSimilarityTransform(Double.NaN, 0.0, 0.0, 0.0).isFinite()
            },
            test("33 small-motion matcher works") {
                val transform = StarSimilarityTransform.fromParameters(12.0, -8.0, 0.0, 1.0)
                val result =
                    StarMatcher().match(
                        catalog(1, base),
                        catalog(0, transformedStars(base, transform)),
                        options()
                    )
                result.success && result.strategy == StarMatchingStrategy.SMALL_MOTION
            },
            test("34 triangle fallback works") {
                val transform = StarSimilarityTransform.fromParameters(260.0, -130.0, 1.0, 1.0)
                val result =
                    StarMatcher().match(
                        catalog(1, base),
                        catalog(0, transformedStars(base, transform)),
                        options(smallRadius = 20.0)
                    )
                result.success && result.strategy == StarMatchingStrategy.GEOMETRIC_PATTERN
            },
            test("35 brightness changes preserve geometric matching") {
                val transform = StarSimilarityTransform.fromParameters(250.0, -110.0, 0.7, 1.0)
                val changed =
                    transformedStars(base.reversed(), transform).mapIndexed { index, star ->
                        star.copy(
                            flux = 100.0 + index * 17.0,
                            peak = 0.2 + index * 0.01,
                            detectionQuality = 30.0 - index
                        )
                    }
                StarMatcher().match(
                    catalog(1, base),
                    catalog(0, changed),
                    options(smallRadius = 20.0)
                ).success
            },
            test("36 star ordering does not affect result") {
                val transform = StarSimilarityTransform.fromParameters(15.0, 9.0, 0.0, 1.0)
                val normal = alignCatalogs(base, transformedStars(base, transform))
                val reversed = alignCatalogs(base.reversed(), transformedStars(base, transform).reversed())
                nearly(normal.transform.tx, reversed.transform.tx, 0.05) &&
                    nearly(normal.transform.ty, reversed.transform.ty, 0.05)
            },
            test("37 missing stars still allows matching") {
                val transform = StarSimilarityTransform.fromParameters(11.0, -5.0, 0.0, 1.0)
                alignCatalogs(base, transformedStars(base.drop(2), transform)).accepted
            },
            test("38 extra target stars still allow matching") {
                val transform = StarSimilarityTransform.fromParameters(11.0, -5.0, 0.0, 1.0)
                val target =
                    transformedStars(base, transform) +
                        listOf(star(90, 80.0, 80.0), star(91, 1080.0, 780.0))
                alignCatalogs(base, target).accepted
            },
            test("39 full-resolution RAW values are correct") {
                val frame =
                    alignTransform(
                        StarSimilarityTransform.fromParameters(20.0, -14.0, 0.0, 1.0)
                    ).frameResults.first()
                nearly(frame.transform.tx, 20.0, 0.05) &&
                    nearly(frame.transform.ty, -14.0, 0.05)
            },
            test("40 proxy scaling is not applied twice") {
                val frame =
                    alignTransform(
                        StarSimilarityTransform.fromParameters(16.0, 12.0, 0.0, 1.0)
                    ).frameResults.first()
                nearly(frame.transform.tx, 16.0, 0.05) &&
                    nearly(frame.transform.ty, 12.0, 0.05)
            },
            test("41 target-to-reference sign is correct") {
                val transform = StarSimilarityTransform.fromParameters(25.0, -10.0, 0.0, 1.0)
                val target = transformedStars(base, transform)
                val frame = alignCatalogs(base, target)
                val mapped = frame.transform.map(target.first().fullX, target.first().fullY)
                nearly(mapped.x, base.first().fullX, 0.05) &&
                    nearly(mapped.y, base.first().fullY, 0.05)
            },
            test("42 reference transform is identity") {
                alignTransform(
                    StarSimilarityTransform.fromParameters(4.0, 3.0, 0.0, 1.0)
                ).frameResults.last().transform == StarSimilarityTransform.IDENTITY
            },
            test("43 results preserve original frame order") {
                val report =
                    StarAlignmentProcessor().align(
                        listOf(catalog(8, base), catalog(2, base), catalog(5, base))
                    )
                report.frameResults.map { it.frameIndex } == listOf(8, 2, 5)
            }
        )
    }

    private fun regressionTests(): List<StarMatchingSelfTestCase> =
        listOf(
            regression("44 StarDetection tests remain passing") {
                StarDetectionCoreSelfTest.runAll().failed == 0
            },
            regression("45 RawProxy tests remain passing") {
                RawProxySelfTest.run().isEmpty()
            },
            regression("46 Landscape alignment tests remain passing") {
                LandscapeAlignmentSelfTest.runAll().none {
                    it.status == PhaseCorrelationSelfTestStatus.FAIL
                }
            },
            regression("47 HDR tests remain passing") {
                HdrI32SelfTest.runAll().failed == 0
            },
            regression("48 MasterDark tests remain passing") {
                MasterDarkSelfTest.runAll().failed == 0
            },
            regression("49 RAW16 stack tests remain passing") {
                AlignedRaw16StackSelfTest.runAll().failed == 0
            }
        )

    private fun transformTest(
        name: String,
        tx: Double,
        ty: Double,
        rotation: Double,
        scale: Double
    ): StarMatchingSelfTestCase =
        test(name) {
            val expected = StarSimilarityTransform.fromParameters(tx, ty, rotation, scale)
            val frame = alignTransform(expected).frameResults.first()
            check(frame.accepted) {
                "rejected=${frame.failureCode} strategy=${frame.matchingStrategy} " +
                    "message=${frame.diagnosticMessage}"
            }
            check(nearly(frame.transform.tx, tx, 0.12)) {
                "tx=${frame.transform.tx} expected=$tx"
            }
            check(nearly(frame.transform.ty, ty, 0.12)) {
                "ty=${frame.transform.ty} expected=$ty"
            }
            check(nearly(frame.transform.rotationDegrees, rotation, 0.02)) {
                "rotation=${frame.transform.rotationDegrees} expected=$rotation"
            }
            check(nearly(frame.transform.scale, scale, 0.0005)) {
                "scale=${frame.transform.scale} expected=$scale"
            }
            true
        }

    private fun alignTransform(
        transform: StarSimilarityTransform,
        options: StarMatchingOptions = options()
    ): StarAlignmentReport {
        val reference = baseStars()
        val target = transformedStars(reference, transform)
        return StarAlignmentProcessor().align(
            listOf(catalog(0, target), catalog(1, reference)),
            options
        )
    }

    private fun alignCatalogs(
        reference: List<DetectedStar>,
        target: List<DetectedStar>
    ): StarFrameAlignment =
        StarAlignmentProcessor().align(
            listOf(catalog(0, target), catalog(1, reference)),
            options()
        ).frameResults.first()

    private fun transformedStars(
        reference: List<DetectedStar>,
        targetToReference: StarSimilarityTransform
    ): List<DetectedStar> {
        val inverse = checkNotNull(targetToReference.inverseOrNull())
        return reference.mapIndexed { index, referenceStar ->
            val target = inverse.map(referenceStar.fullX, referenceStar.fullY)
            star(
                id = 1000 + index,
                x = target.x,
                y = target.y,
                snr = referenceStar.snr,
                radius = referenceStar.radius,
                quality = referenceStar.detectionQuality
            )
        }
    }

    private fun exactCandidates(
        reference: List<DetectedStar>,
        transform: StarSimilarityTransform
    ): List<StarMatchCandidate> {
        val target = transformedStars(reference, transform)
        return reference.indices.map {
            candidate(reference[it], target[it])
        }
    }

    private fun candidate(
        reference: DetectedStar,
        target: DetectedStar
    ): StarMatchCandidate =
        StarMatchCandidate(
            referenceStar = reference,
            targetStar = target,
            geometricScore = 1.0,
            initialResidualRawPixels = hypot(
                reference.fullX - target.fullX,
                reference.fullY - target.fullY
            ),
            strategy = StarMatchingStrategy.SMALL_MOTION
        )

    private fun noisyRansac(noise: Double): StarRansacResult {
        val transform = StarSimilarityTransform.fromParameters(7.0, -4.0, 0.2, 1.0)
        val reference = baseStars()
        val target =
            transformedStars(reference, transform).mapIndexed { index, star ->
                val offset = if ((index and 1) == 0) noise else -noise
                star.copy(
                    fullX = star.fullX + offset,
                    fullY = star.fullY - offset,
                    proxyX = (star.fullX + offset) / SCALE,
                    proxyY = (star.fullY - offset) / SCALE
                )
            }
        return StarRansacEstimator().estimate(
            reference.indices.map { candidate(reference[it], target[it]) },
            options(reprojection = 8.0)
        )
    }

    private fun baseStars(): List<DetectedStar> =
        listOf(
            star(0, 110.0, 120.0),
            star(1, 320.0, 90.0),
            star(2, 610.0, 150.0),
            star(3, 980.0, 110.0),
            star(4, 180.0, 360.0),
            star(5, 470.0, 430.0),
            star(6, 820.0, 350.0),
            star(7, 1050.0, 500.0),
            star(8, 130.0, 720.0),
            star(9, 560.0, 690.0),
            star(10, 920.0, 760.0)
        )

    private fun extraStars(): List<DetectedStar> =
        listOf(star(50, 760.0, 610.0), star(51, 360.0, 610.0))

    private fun star(
        id: Int,
        x: Double,
        y: Double,
        snr: Double = 30.0,
        radius: Double = 1.4,
        quality: Double = 100.0 - id.coerceAtMost(80)
    ): DetectedStar =
        DetectedStar(
            id = id,
            frameIndex = 0,
            proxyX = x / SCALE,
            proxyY = y / SCALE,
            fullX = x,
            fullY = y,
            peak = 0.8,
            flux = 500.0 + id,
            background = 0.05,
            snr = snr,
            radius = radius,
            secondMoment = radius * radius,
            sharpness = 0.75,
            saturated = false,
            detectionQuality = quality
        )

    private fun catalog(
        frameIndex: Int,
        stars: List<DetectedStar>
    ): StarCatalog =
        StarCatalog(
            frameIndex = frameIndex,
            sourceTimestampNs = 1_000_000L + frameIndex,
            proxyType = RawProxyType.GREEN,
            proxyWidth = (WIDTH / SCALE).toInt(),
            proxyHeight = (HEIGHT / SCALE).toInt(),
            sourceWidth = WIDTH,
            sourceHeight = HEIGHT,
            scaleX = SCALE,
            scaleY = SCALE,
            exposureNormalized = false,
            backgroundEstimate = 0.05,
            noiseEstimate = 0.01,
            thresholdUsed = 0.10,
            thresholdSigma = 5.0,
            localMaximumCount = stars.size,
            stars = stars.map { it.copy(frameIndex = frameIndex) },
            rejectedCandidateCounts = emptyMap(),
            warnings = emptyList(),
            statusCode = null,
            durationMs = 1L
        )

    private fun options(
        minimumInlierRatio: Double = 0.35,
        maximumRms: Double = 4.0,
        reprojection: Double = 6.0,
        minimumCoverage: Double = 0.002,
        maxRotation: Double = 15.0,
        minScale: Double = 0.90,
        maxScale: Double = 1.10,
        maxTranslationFraction: Double = 0.50,
        smallRadius: Double = 180.0
    ): StarMatchingOptions =
        StarMatchingOptions(
            minimumInlierRatio = minimumInlierRatio,
            maximumRmsErrorRawPixels = maximumRms,
            reprojectionThresholdRawPixels = reprojection,
            minimumSpatialCoverageFraction = minimumCoverage,
            maximumAbsoluteRotationDegrees = maxRotation,
            minimumScale = minScale,
            maximumScale = maxScale,
            maximumTranslationFractionX = maxTranslationFraction,
            maximumTranslationFractionY = maxTranslationFraction,
            smallMotionSearchRadiusRawPixels = smallRadius
        )

    private inline fun test(
        name: String,
        block: () -> Boolean
    ): StarMatchingSelfTestCase =
        runCatching { block() }.fold(
            onSuccess = {
                StarMatchingSelfTestCase(
                    name,
                    if (it) PhaseCorrelationSelfTestStatus.PASS
                    else PhaseCorrelationSelfTestStatus.FAIL,
                    if (it) "ok" else "condition returned false"
                )
            },
            onFailure = {
                StarMatchingSelfTestCase(
                    name,
                    PhaseCorrelationSelfTestStatus.FAIL,
                    "${it.javaClass.simpleName}: ${it.message}"
                )
            }
        )

    private inline fun regression(
        name: String,
        block: () -> Boolean
    ): StarMatchingSelfTestCase = test(name, block)

    private fun nearly(
        actual: Double,
        expected: Double,
        tolerance: Double = 1e-6
    ): Boolean =
        actual.isFinite() && expected.isFinite() && abs(actual - expected) <= tolerance

    private const val WIDTH = 1200
    private const val HEIGHT = 900
    private const val SCALE = 2.0
}
