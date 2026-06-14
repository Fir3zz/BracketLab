package com.lab.bracketlab.processing.align.star

import com.lab.bracketlab.processing.align.LandscapeAlignmentSelfTest
import com.lab.bracketlab.processing.align.PhaseCorrelationSelfTestStatus
import com.lab.bracketlab.processing.calibration.MasterDarkSelfTest
import com.lab.bracketlab.processing.hdri.HdrI32SelfTest
import com.lab.bracketlab.processing.raw.RawProxySelfTest
import com.lab.bracketlab.processing.stack.AlignedRaw16StackSelfTest

object StarDetectionSelfTest {
    fun runAll(): StarDetectionSelfTestReport {
        val startedNs = System.nanoTime()
        val core = StarDetectionCoreSelfTest.runAll()
        val regressions =
            listOf(
                regression("41 existing RawProxy tests pass") {
                    RawProxySelfTest.run().isEmpty()
                },
                regression("42 existing Landscape alignment tests pass") {
                    LandscapeAlignmentSelfTest.runAll().none {
                        it.status == PhaseCorrelationSelfTestStatus.FAIL
                    }
                },
                regression("43 existing HDR tests pass") {
                    HdrI32SelfTest.runAll().failed == 0
                },
                regression("44 existing MasterDark tests pass") {
                    MasterDarkSelfTest.runAll().failed == 0
                },
                regression("45 existing RAW16 stack tests pass") {
                    AlignedRaw16StackSelfTest.runAll().failed == 0
                }
            )
        val results = core.results + regressions
        return StarDetectionSelfTestReport(
            passed = results.count { it.status == PhaseCorrelationSelfTestStatus.PASS },
            failed = results.count { it.status == PhaseCorrelationSelfTestStatus.FAIL },
            skipped = results.count { it.status == PhaseCorrelationSelfTestStatus.SKIPPED },
            durationMs = (System.nanoTime() - startedNs) / 1_000_000L,
            results = results
        )
    }

    private inline fun regression(
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
}
