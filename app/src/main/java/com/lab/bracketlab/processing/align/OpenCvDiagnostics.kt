package com.lab.bracketlab.processing.align

import com.lab.bracketlab.processing.stack.AlignedRaw16StackSelfTest
import com.lab.bracketlab.processing.io.Raw16DngWriterSelfTest
import com.lab.bracketlab.processing.stack.RawStackAggregatorSelfTest
import com.lab.bracketlab.processing.memory.MemoryStabilizationSelfTest
import com.lab.bracketlab.processing.calibration.MasterDarkSelfTest
import com.lab.bracketlab.processing.hdri.HdrI32SelfTest
import com.lab.bracketlab.processing.hdri.export.dng.LinearRgbFloat16DngSelfTest

object OpenCvDiagnostics {
    fun runPhaseCorrelationCheck(): List<String> {
        val lines = mutableListOf<String>()
        val loadResult = OpenCvRuntime.ensureLoaded()
        when (loadResult) {
            OpenCvLoadResult.Success,
            OpenCvLoadResult.AlreadyLoaded -> lines += "OpenCV loaded successfully"
            is OpenCvLoadResult.Failure -> {
                lines += "OpenCV load failed: ${loadResult.reason} ${loadResult.exceptionMessage.orEmpty()}".trim()
            }
        }

        val results = PhaseCorrelationSelfTest.runOpenCvRuntimeTests()
        val status = when {
            results.any { it.status == PhaseCorrelationSelfTestStatus.FAIL } -> PhaseCorrelationSelfTestStatus.FAIL
            results.all { it.status == PhaseCorrelationSelfTestStatus.SKIPPED } -> PhaseCorrelationSelfTestStatus.SKIPPED
            else -> PhaseCorrelationSelfTestStatus.PASS
        }
        val passed = results.count { it.status == PhaseCorrelationSelfTestStatus.PASS }
        val failed = results.count { it.status == PhaseCorrelationSelfTestStatus.FAIL }
        val skipped = results.count { it.status == PhaseCorrelationSelfTestStatus.SKIPPED }
        lines += "Phase correlation self-test: $status ($passed pass, $failed fail, $skipped skipped)"

        results.firstOrNull { it.status != PhaseCorrelationSelfTestStatus.PASS }?.let {
            lines += "Phase correlation ${it.status}: ${it.name} - ${it.message}"
        }
        lines += landscapeSelfTestLines()
        lines += alignedRaw16StackSelfTestLines()
        lines += raw16DngWriterSelfTestLines()
        lines += rawStackAggregatorSelfTestLines()
        lines += memoryStabilizationSelfTestLines()
        lines += masterDarkSelfTestLines()
        lines += hdri32SelfTestLines()
        lines += linearRgbFloat16DngSelfTestLines()
        return lines
    }

    private fun landscapeSelfTestLines(): List<String> {
        val results = LandscapeAlignmentSelfTest.runAll()
        val status = when {
            results.any { it.status == PhaseCorrelationSelfTestStatus.FAIL } -> PhaseCorrelationSelfTestStatus.FAIL
            results.any { it.status == PhaseCorrelationSelfTestStatus.SKIPPED } -> PhaseCorrelationSelfTestStatus.SKIPPED
            else -> PhaseCorrelationSelfTestStatus.PASS
        }
        val passed = results.count { it.status == PhaseCorrelationSelfTestStatus.PASS }
        val failed = results.count { it.status == PhaseCorrelationSelfTestStatus.FAIL }
        val skipped = results.count { it.status == PhaseCorrelationSelfTestStatus.SKIPPED }
        val lines = mutableListOf(
            "Landscape alignment self-test: $status ($passed pass, $failed fail, $skipped skipped)"
        )
        results.firstOrNull { it.status != PhaseCorrelationSelfTestStatus.PASS }?.let {
            lines += "Landscape alignment ${it.status}: ${it.name} - ${it.message}"
        }
        return lines
    }

    private fun alignedRaw16StackSelfTestLines(): List<String> {
        val report = AlignedRaw16StackSelfTest.runAll()
        val status = when {
            report.failed > 0 -> PhaseCorrelationSelfTestStatus.FAIL
            report.skipped > 0 -> PhaseCorrelationSelfTestStatus.SKIPPED
            else -> PhaseCorrelationSelfTestStatus.PASS
        }
        val lines = mutableListOf(
            "Aligned RAW16 stack self-test: $status (${report.passed} pass, ${report.failed} fail, ${report.skipped} skipped)"
        )
        report.results.firstOrNull { it.status != PhaseCorrelationSelfTestStatus.PASS }?.let {
            lines += "Aligned RAW16 stack ${it.status}: ${it.name} - ${it.message}"
        }
        return lines
    }

    private fun raw16DngWriterSelfTestLines(): List<String> {
        val report = Raw16DngWriterSelfTest.runAll()
        val status = when {
            report.failed > 0 -> PhaseCorrelationSelfTestStatus.FAIL
            report.skipped > 0 -> PhaseCorrelationSelfTestStatus.SKIPPED
            else -> PhaseCorrelationSelfTestStatus.PASS
        }
        val lines = mutableListOf(
            "RAW16 DNG writer contract self-test: $status (${report.passed} pass, ${report.failed} fail, ${report.skipped} skipped)"
        )
        report.results.firstOrNull { it.status != PhaseCorrelationSelfTestStatus.PASS }?.let {
            lines += "RAW16 DNG writer ${it.status}: ${it.name} - ${it.message}"
        }
        return lines
    }

    private fun rawStackAggregatorSelfTestLines(): List<String> {
        val report = RawStackAggregatorSelfTest.runAll()
        val status = when {
            report.failed > 0 -> PhaseCorrelationSelfTestStatus.FAIL
            report.skipped > 0 -> PhaseCorrelationSelfTestStatus.SKIPPED
            else -> PhaseCorrelationSelfTestStatus.PASS
        }
        val lines = mutableListOf(
            "RAW16 aggregation self-test: $status (${report.passed} pass, ${report.failed} fail, ${report.skipped} skipped, ${report.processingDurationMs} ms)"
        )
        report.results.firstOrNull { it.status != PhaseCorrelationSelfTestStatus.PASS }?.let {
            lines += "RAW16 aggregation ${it.status}: ${it.name} - ${it.message}"
        }
        return lines
    }

    private fun memoryStabilizationSelfTestLines(): List<String> {
        val report = MemoryStabilizationSelfTest.runAll()
        val status = when {
            report.failed > 0 -> PhaseCorrelationSelfTestStatus.FAIL
            report.skipped > 0 -> PhaseCorrelationSelfTestStatus.SKIPPED
            else -> PhaseCorrelationSelfTestStatus.PASS
        }
        return buildList {
            add(
                "Memory stabilization self-test: $status " +
                    "(${report.passed} passed, ${report.failed} failed, ${report.skipped} skipped)"
            )
            report.results.firstOrNull { it.status != PhaseCorrelationSelfTestStatus.PASS }?.let {
                add("Memory stabilization first issue: ${it.name}: ${it.message}")
            }
        }
    }

    private fun masterDarkSelfTestLines(): List<String> {
        val report = MasterDarkSelfTest.runAll()
        val status = when {
            report.failed > 0 -> PhaseCorrelationSelfTestStatus.FAIL
            report.skipped > 0 -> PhaseCorrelationSelfTestStatus.SKIPPED
            else -> PhaseCorrelationSelfTestStatus.PASS
        }
        return buildList {
            add(
                "MasterDark self-test: $status " +
                    "(${report.passed} passed, ${report.failed} failed, ${report.skipped} skipped, ${report.durationMs} ms)"
            )
            report.results.firstOrNull { it.status != PhaseCorrelationSelfTestStatus.PASS }?.let {
                add("MasterDark first issue: ${it.name}: ${it.message}")
            }
        }
    }

    private fun hdri32SelfTestLines(): List<String> {
        val report = HdrI32SelfTest.runAll()
        val status = when {
            report.failed > 0 -> PhaseCorrelationSelfTestStatus.FAIL
            report.skipped > 0 -> PhaseCorrelationSelfTestStatus.SKIPPED
            else -> PhaseCorrelationSelfTestStatus.PASS
        }
        return buildList {
            add(
                "HDR 32F self-test: $status " +
                    "(${report.passed} passed, ${report.failed} failed, " +
                    "${report.skipped} skipped, ${report.durationMs} ms)"
            )
            report.results.firstOrNull { it.status != PhaseCorrelationSelfTestStatus.PASS }?.let {
                add("HDR 32F first issue: ${it.name}: ${it.message}")
            }
        }
    }

    private fun linearRgbFloat16DngSelfTestLines(): List<String> {
        val report = LinearRgbFloat16DngSelfTest.runAll()
        val status = when {
            report.failed > 0 -> PhaseCorrelationSelfTestStatus.FAIL
            report.skipped > 0 -> PhaseCorrelationSelfTestStatus.SKIPPED
            else -> PhaseCorrelationSelfTestStatus.PASS
        }
        return buildList {
            add(
                "Linear RGB Float16 DNG self-test: $status " +
                    "(${report.passed} passed, ${report.failed} failed, " +
                    "${report.skipped} skipped, ${report.durationMs} ms)"
            )
            report.results.firstOrNull {
                it.status != PhaseCorrelationSelfTestStatus.PASS
            }?.let {
                add("Linear RGB Float16 DNG first issue: ${it.name}: ${it.message}")
            }
        }
    }
}
