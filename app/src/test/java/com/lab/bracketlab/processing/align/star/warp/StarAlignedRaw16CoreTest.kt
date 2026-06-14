package com.lab.bracketlab.processing.align.star.warp

import org.junit.Assert.assertEquals
import org.junit.Test

class StarAlignedRaw16CoreTest {
    @Test
    fun coreSelfTestsPass() {
        val report = StarAlignedRaw16SelfTest.runCore()
        val failures =
            report.results
                .filter { it.status.name == "FAIL" }
                .joinToString("\n") { "${it.name}: ${it.message}" }
        assertEquals(failures, 0, report.failed)
        assertEquals(70, report.passed)
    }

    @Test
    fun completeSelfTestsPass() {
        val report = StarAlignedRaw16SelfTest.runAll()
        val failures =
            report.results
                .filter { it.status.name == "FAIL" }
                .joinToString("\n") { "${it.name}: ${it.message}" }
        assertEquals(failures, 0, report.failed)
        assertEquals(80, report.passed + report.skipped)
    }
}
