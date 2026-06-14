package com.lab.bracketlab.processing.align.star

import com.lab.bracketlab.processing.align.PhaseCorrelationSelfTestStatus
import com.lab.bracketlab.processing.debug.IncrementalDiagnosticReport
import com.lab.bracketlab.processing.model.RawFrame
import com.lab.bracketlab.processing.model.RawStack
import com.lab.bracketlab.processing.raw.BayerUtils
import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.raw.RawProxy
import com.lab.bracketlab.processing.raw.RawProxyType
import com.lab.bracketlab.processing.storage.FileBackedRaw16FrameStorage
import java.io.File
import kotlin.math.abs
import kotlin.math.exp

data class StarDetectionSelfTestCase(
    val name: String,
    val status: PhaseCorrelationSelfTestStatus,
    val message: String
)

data class StarDetectionSelfTestReport(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val durationMs: Long,
    val results: List<StarDetectionSelfTestCase>
) {
    fun logLines(): List<String> =
        buildList {
            add("Star detection self-test: ${statusLabel()} ($passed pass, $failed fail, $skipped skipped)")
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

object StarDetectionCoreSelfTest {
    fun runAll(): StarDetectionSelfTestReport {
        val startedNs = System.nanoTime()
        val tests =
            mutableListOf(
                test("1 empty stack returns EMPTY_STACK") {
                    StarDetector().detect(RawStack(emptyList())).failureCode ==
                        StarDetectionFailureCode.EMPTY_STACK
                },
                test("2 invalid frame returns INVALID_FRAME") {
                    val result =
                        StarDetector().detect(
                            RawStack(listOf(rawFrame().copy(width = 0)))
                        )
                    result.catalogs.single().statusCode == StarDetectionFailureCode.INVALID_FRAME
                },
                test("3 synthetic single bright point is detected") {
                    detectProxy(proxyWithStars(listOf(24.0 to 20.0))).starCount == 1
                },
                test("4 synthetic multiple bright points are detected") {
                    detectProxy(
                        proxyWithStars(listOf(12.0 to 12.0, 25.0 to 20.0, 38.0 to 28.0))
                    ).starCount == 3
                },
                test("5 proxy coordinates convert to full RAW coordinates") {
                    val catalog =
                        detectProxy(
                            proxyWithStars(
                                listOf(20.0 to 16.0),
                                scaleX = 4.0,
                                scaleY = 3.0
                            ),
                            sourceWidth = WIDTH * 4,
                            sourceHeight = HEIGHT * 3
                        )
                    val star = catalog.stars.single()
                    nearly(star.fullX, star.proxyX * 4.0) &&
                        nearly(star.fullY, star.proxyY * 3.0)
                },
                test("6 catalog contains frame metadata") {
                    val catalog = detectProxy(proxyWithStars(listOf(20.0 to 16.0)))
                    catalog.frameIndex == FRAME_INDEX &&
                        catalog.sourceTimestampNs == TIMESTAMP &&
                        catalog.sourceWidth == WIDTH &&
                        catalog.sourceHeight == HEIGHT
                },
                test("7 catalog retains no RAW or proxy array") {
                    StarCatalog::class.java.declaredFields.none {
                        it.type == FloatArray::class.java || it.type == ByteArray::class.java
                    }
                },
                test("8 median background works on constant image") {
                    val estimate = StarBackgroundEstimator.estimate(constantProxy(0.25f), 4096)
                    nearly(estimate.background, 0.25) && nearly(estimate.sigma, 0.0)
                },
                test("9 MAD noise estimate works") {
                    val data = FloatArray(WIDTH * HEIGHT) { if ((it and 1) == 0) 0.09f else 0.11f }
                    val estimate = StarBackgroundEstimator.estimate(proxy(data), 4096)
                    estimate.sigma > 0.014 && estimate.sigma < 0.016
                },
                test("10 threshold rejects pure bounded noise") {
                    val data = FloatArray(WIDTH * HEIGHT) { 0.1f + ((it % 7) - 3) * 0.001f }
                    detectProxy(proxy(data)).starCount == 0
                },
                test("11 brighter-than-threshold point is accepted") {
                    detectProxy(proxyWithStars(listOf(20.0 to 18.0))).starCount == 1
                },
                test("12 below-threshold point is rejected") {
                    val proxy = proxyWithStars(listOf(20.0 to 18.0), amplitude = 0.002)
                    detectProxy(proxy).starCount == 0
                },
                test("13 centered centroid is accurate") {
                    val star = detectProxy(proxyWithStars(listOf(20.0 to 18.0))).stars.single()
                    abs(star.proxyX - 20.0) < 0.05 && abs(star.proxyY - 18.0) < 0.05
                },
                test("14 asymmetric subpixel centroid shifts correctly") {
                    val star = detectProxy(proxyWithStars(listOf(20.35 to 18.65))).stars.single()
                    abs(star.proxyX - 20.35) < 0.20 && abs(star.proxyY - 18.65) < 0.20
                },
                test("15 zero-weight centroid is rejected") {
                    StarCentroidEstimator.estimate(constantProxy(0.1f), 20, 20, 3, 0.01) == null
                },
                test("16 edge candidate is rejected") {
                    val catalog = detectProxy(proxyWithStars(listOf(1.0 to 1.0)))
                    catalog.starCount == 0 &&
                        catalog.rejectedCandidateCounts.getValue(
                            StarCandidateRejectionReason.TOO_CLOSE_TO_EDGE
                        ) > 0
                },
                test("17 saturated candidate is rejected") {
                    val catalog = detectProxy(proxyWithStars(listOf(20.0 to 18.0), amplitude = 1.2))
                    catalog.starCount == 0 &&
                        catalog.rejectedCandidateCounts.getValue(
                            StarCandidateRejectionReason.SATURATED
                        ) > 0
                },
                test("18 duplicate nearby candidates are suppressed") {
                    val catalog =
                        detectProxy(
                            proxyWithStars(
                                listOf(20.0 to 18.0, 23.0 to 18.0),
                                sigma = 0.7
                            ),
                            options().copy(localMaxRadius = 1, minimumDistancePixels = 4.0)
                        )
                    catalog.starCount == 1 &&
                        catalog.rejectedCandidateCounts.getValue(
                            StarCandidateRejectionReason.DUPLICATE_NEARBY
                        ) == 1
                },
                test("19 SNR is positive") {
                    detectProxy(proxyWithStars(listOf(20.0 to 18.0))).stars.single().snr > 0.0
                },
                test("20 flux is positive") {
                    detectProxy(proxyWithStars(listOf(20.0 to 18.0))).stars.single().flux > 0.0
                },
                test("21 sharpness and radius are finite") {
                    val star = detectProxy(proxyWithStars(listOf(20.0 to 18.0))).stars.single()
                    star.sharpness.isFinite() && star.radius.isFinite() &&
                        star.secondMoment.isFinite()
                },
                test("22 non-finite proxy values are rejected safely") {
                    val data = proxyWithStars(listOf(20.0 to 18.0)).data.copyOf()
                    data[18 * WIDTH + 20] = Float.NaN
                    val catalog = detectProxy(proxy(data))
                    catalog.rejectedCandidateCounts.getValue(
                        StarCandidateRejectionReason.NON_FINITE_VALUE
                    ) > 0
                },
                test("23 maxStars limit is enforced") {
                    val catalog =
                        detectProxy(
                            proxyWithStars(
                                listOf(
                                    10.0 to 10.0,
                                    20.0 to 10.0,
                                    30.0 to 10.0,
                                    40.0 to 10.0
                                )
                            ),
                            options().copy(maxStars = 2)
                        )
                    catalog.starCount == 2
                },
                test("24 quality sorting is deterministic") {
                    val input = proxyWithStars(listOf(12.0 to 12.0, 32.0 to 24.0))
                    detectProxy(input).stars == detectProxy(input).stars
                },
                test("25 RGGB proxy star is detected") { detectRaw(CfaPattern.RGGB).starCount > 0 },
                test("26 GRBG proxy star is detected") { detectRaw(CfaPattern.GRBG).starCount > 0 },
                test("27 GBRG proxy star is detected") { detectRaw(CfaPattern.GBRG).starCount > 0 },
                test("28 BGGR proxy star is detected") { detectRaw(CfaPattern.BGGR).starCount > 0 },
                test("29 GREEN proxy works") {
                    detectRaw(CfaPattern.RGGB).proxyType == RawProxyType.GREEN
                },
                test("30 LUMA fallback works when enabled") {
                    val frame = redOnlyStarFrame()
                    val result =
                        StarDetector().detect(
                            RawStack(listOf(frame)),
                            options().copy(
                                allowLumaFallback = true,
                                minimumStarsForFutureAlignment = 1
                            )
                        )
                    result.catalogs.single().proxyType == RawProxyType.LUMA_ALIGNMENT &&
                        result.catalogs.single().starCount > 0
                },
                test("31 file-backed RAW source works") {
                    withTempDir { directory ->
                        val memory = rawFrame()
                        val file = File(directory, "frame.raw16")
                        file.writeBytes(requireNotNull(memory.raw16))
                        val backed =
                            memory.copy(
                                raw16 = null,
                                raw16Storage = FileBackedRaw16FrameStorage(file, false)
                            )
                        StarDetector().detect(
                            RawStack(listOf(backed)),
                            options()
                        ).catalogs.single().starCount > 0
                    }
                },
                test("32 in-memory RAW source works") {
                    StarDetector().detect(
                        RawStack(listOf(rawFrame())),
                        options()
                    ).catalogs.single().starCount > 0
                },
                test("33 file-backed and memory detections are comparable") {
                    withTempDir { directory ->
                        val memory = rawFrame()
                        val file = File(directory, "frame.raw16")
                        file.writeBytes(requireNotNull(memory.raw16))
                        val backed =
                            memory.copy(
                                raw16 = null,
                                raw16Storage = FileBackedRaw16FrameStorage(file, false)
                            )
                        val first =
                            StarDetector().detect(RawStack(listOf(memory)), options()).catalogs.single()
                        val second =
                            StarDetector().detect(RawStack(listOf(backed)), options()).catalogs.single()
                        first.starCount == second.starCount &&
                            nearly(first.stars.first().proxyX, second.stars.first().proxyX)
                    }
                },
                test("34 file-backed storage remains non-resident") {
                    withTempDir { directory ->
                        val file = File(directory, "frame.raw16")
                        file.writeBytes(requireNotNull(rawFrame().raw16))
                        val storage = FileBackedRaw16FrameStorage(file, false)
                        storage.residentByteCount == 0L
                    }
                },
                test("35 JSON catalog write succeeds") {
                    withTempDir { directory ->
                        val result =
                            StarCatalogStore.writeCatalogs(
                                listOf(detectProxy(proxyWithStars(listOf(20.0 to 18.0)))),
                                directory
                            )
                        result.success && result.catalogPaths.single().endsWith(".json")
                    }
                },
                test("36 TXT report write succeeds") {
                    withTempDir { directory ->
                        val report = File(directory, "report.txt")
                        IncrementalDiagnosticReport(report).use { it.append("star detection") }
                        report.readText().contains("star detection")
                    }
                },
                test("37 catalog write failure is reported") {
                    withTempDir { directory ->
                        val blocker = File(directory, "not_a_directory").apply { writeText("x") }
                        val result =
                            StarCatalogStore.writeCatalogs(
                                listOf(detectProxy(proxyWithStars(listOf(20.0 to 18.0)))),
                                blocker
                            )
                        !result.success &&
                            result.failureCode == StarDetectionFailureCode.CATALOG_WRITE_FAILED
                    }
                },
                test("38 no-star diagnostic data remains valid") {
                    val catalog = detectProxy(constantProxy(0.1f))
                    catalog.stars.isEmpty() &&
                        catalog.statusCode == StarDetectionFailureCode.NO_STARS_DETECTED &&
                        catalog.durationMs >= 0L
                },
                test("39 options are recorded") {
                    val configured = options().copy(thresholdSigma = 6.0)
                    StarDetector().detect(
                        RawStack(listOf(rawFrame())),
                        configured
                    ).options == configured
                },
                test("40 duration is recorded") {
                    StarDetector().detect(
                        RawStack(listOf(rawFrame())),
                        options()
                    ).durationMs >= 0L
                }
            )
        return StarDetectionSelfTestReport(
            passed = tests.count { it.status == PhaseCorrelationSelfTestStatus.PASS },
            failed = tests.count { it.status == PhaseCorrelationSelfTestStatus.FAIL },
            skipped = tests.count { it.status == PhaseCorrelationSelfTestStatus.SKIPPED },
            durationMs = (System.nanoTime() - startedNs) / 1_000_000L,
            results = tests
        )
    }

    private fun detectProxy(
        proxy: RawProxy,
        configured: StarDetectionOptions = options(),
        sourceWidth: Int = WIDTH,
        sourceHeight: Int = HEIGHT
    ): StarCatalog =
        StarDetector().detectProxy(
            proxy,
            rawFrame(sourceWidth, sourceHeight, empty = true),
            configured
        )

    private fun detectRaw(pattern: CfaPattern): StarCatalog =
        StarDetector().detect(
            RawStack(listOf(rawFrame(pattern = pattern))),
            options()
        ).catalogs.single()

    private fun options(): StarDetectionOptions =
        StarDetectionOptions(
            proxyMaxDimension = WIDTH,
            thresholdSigma = 4.0,
            minSignalAboveBackground = 0.01,
            localMaxRadius = 2,
            centroidRadius = 3,
            minSnr = 2.0,
            edgeMargin = 4,
            minimumDistancePixels = 3.0,
            minimumRadius = 0.1,
            maximumRadius = 4.0,
            minimumStarsForFutureAlignment = 1,
            backgroundSampleLimit = 4096
        )

    private fun proxyWithStars(
        stars: List<Pair<Double, Double>>,
        amplitude: Double = 0.75,
        sigma: Double = 1.15,
        scaleX: Double = 1.0,
        scaleY: Double = 1.0
    ): RawProxy {
        val data = FloatArray(WIDTH * HEIGHT) { 0.05f }
        for ((centerX, centerY) in stars) {
            for (y in 0 until HEIGHT) {
                for (x in 0 until WIDTH) {
                    val dx = x - centerX
                    val dy = y - centerY
                    val value = amplitude * exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma))
                    data[y * WIDTH + x] += value.toFloat()
                }
            }
        }
        return proxy(data, scaleX, scaleY)
    }

    private fun constantProxy(value: Float): RawProxy =
        proxy(FloatArray(WIDTH * HEIGHT) { value })

    private fun proxy(
        data: FloatArray,
        scaleX: Double = 1.0,
        scaleY: Double = 1.0
    ): RawProxy =
        RawProxy(
            width = WIDTH,
            height = HEIGHT,
            data = data,
            scaleX = scaleX,
            scaleY = scaleY,
            sourceFrameIndex = FRAME_INDEX,
            exposureNormalized = false,
            proxyType = RawProxyType.GREEN
        )

    private fun rawFrame(
        width: Int = WIDTH,
        height: Int = HEIGHT,
        pattern: CfaPattern = CfaPattern.RGGB,
        empty: Boolean = false
    ): RawFrame {
        val rowStride = width * 2
        val bytes = ByteArray(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val background = 50
                val dx = x - width * 0.5
                val dy = y - height * 0.5
                val star =
                    if (empty) 0.0
                    else 850.0 * exp(-(dx * dx + dy * dy) / (2.0 * 1.4 * 1.4))
                val sample = (background + star).toInt().coerceIn(0, 1000)
                val offset = y * rowStride + x * 2
                bytes[offset] = sample.toByte()
                bytes[offset + 1] = (sample ushr 8).toByte()
            }
        }
        return RawFrame(
            width = width,
            height = height,
            raw16 = bytes,
            rowStride = rowStride,
            pixelStride = 2,
            exposureTimeNs = 1_000_000_000L,
            iso = 100,
            cameraId = "0",
            timestampNs = TIMESTAMP,
            frameIndex = FRAME_INDEX,
            blackLevelPattern = intArrayOf(0, 0, 0, 0),
            whiteLevel = 1000,
            cfaPattern = pattern
        )
    }

    private fun redOnlyStarFrame(): RawFrame {
        val width = WIDTH
        val height = HEIGHT
        val rowStride = width * 2
        val bytes = ByteArray(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = BayerUtils.colorAt(CfaPattern.RGGB, x, y)
                val dx = x - width * 0.5
                val dy = y - height * 0.5
                val star =
                    if (color.name == "RED") {
                        850.0 * exp(-(dx * dx + dy * dy) / (2.0 * 1.5 * 1.5))
                    } else {
                        0.0
                    }
                val sample = (50 + star).toInt().coerceIn(0, 1000)
                val offset = y * rowStride + x * 2
                bytes[offset] = sample.toByte()
                bytes[offset + 1] = (sample ushr 8).toByte()
            }
        }
        return rawFrame().copy(raw16 = bytes)
    }

    private inline fun test(
        name: String,
        block: () -> Boolean
    ): StarDetectionSelfTestCase =
        runCatching { block() }
            .fold(
                onSuccess = {
                    StarDetectionSelfTestCase(
                        name,
                        if (it) PhaseCorrelationSelfTestStatus.PASS
                        else PhaseCorrelationSelfTestStatus.FAIL,
                        if (it) "ok" else "condition returned false"
                    )
                },
                onFailure = {
                    StarDetectionSelfTestCase(
                        name,
                        PhaseCorrelationSelfTestStatus.FAIL,
                        "${it.javaClass.simpleName}: ${it.message}"
                    )
                }
            )

    private inline fun <T> withTempDir(block: (File) -> T): T {
        val marker = File.createTempFile("bracketlab_star_", ".tmp")
        check(marker.delete())
        check(marker.mkdirs())
        return try {
            block(marker)
        } finally {
            marker.deleteRecursively()
        }
    }

    private fun nearly(left: Double, right: Double, tolerance: Double = 1e-6): Boolean =
        abs(left - right) <= tolerance

    private const val WIDTH = 48
    private const val HEIGHT = 40
    private const val FRAME_INDEX = 7
    private const val TIMESTAMP = 123_456_789L
}
