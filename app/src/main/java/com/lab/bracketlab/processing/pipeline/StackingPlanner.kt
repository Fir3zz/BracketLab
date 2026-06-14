package com.lab.bracketlab.processing.pipeline

import com.lab.bracketlab.processing.model.AlignToggle
import com.lab.bracketlab.processing.model.AstroState
import com.lab.bracketlab.processing.model.DarkPolicy
import com.lab.bracketlab.processing.model.OutputDepth
import com.lab.bracketlab.processing.model.ProcessingIntent
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import com.lab.bracketlab.processing.model.StackingConfig
import com.lab.bracketlab.processing.model.StackingOperation

data class ResolvedProcessingPlan(
    val intent: ProcessingIntent,
    val alignmentMode: ResolvedAlignmentMode,
    val outputDepth: OutputDepth,
    val darkPolicy: DarkPolicy,
    val robustStackingEnabled: Boolean,
    val debugExportEnabled: Boolean,
    val warnings: List<String> = emptyList(),
    val unsupportedReason: String? = null
) {
    val isSupported: Boolean
        get() = unsupportedReason == null
}

object StackingPlanner {
    fun resolve(config: StackingConfig): ResolvedProcessingPlan {
        val warnings = mutableListOf<String>()

        if (config.astroState == AstroState.DARK_CAPTURE) {
            return plan(
                config = config,
                intent = ProcessingIntent.DARK_CAPTURE,
                alignmentMode = ResolvedAlignmentMode.NONE,
                darkPolicy = DarkPolicy.CAPTURE_MASTER_DARK
            )
        }

        if (config.operation == StackingOperation.OFF) {
            return plan(
                config = config,
                intent = ProcessingIntent.SINGLE_CAPTURE,
                alignmentMode = ResolvedAlignmentMode.NONE
            )
        }

        if (config.operation == StackingOperation.NORMAL && config.astroState == AstroState.STAR) {
            if (config.alignToggle == AlignToggle.AUTO) {
                warnings += "Align AUTO ignored because Astro STAR uses star alignment."
            }
            return plan(
                config = config,
                intent = ProcessingIntent.STAR_STACK,
                alignmentMode = ResolvedAlignmentMode.STAR_ALIGNMENT,
                warnings = warnings
            )
        }

        if (config.operation == StackingOperation.NORMAL && config.astroState == AstroState.OFF) {
            return plan(
                config = config,
                intent = ProcessingIntent.NORMAL_STACK,
                alignmentMode = landscapeAlignment(config)
            )
        }

        if (config.operation == StackingOperation.HDRI) {
            val outputDepth =
                if (config.outputDepth == OutputDepth.FLOAT32_32F) {
                    OutputDepth.FLOAT32_32F
                } else {
                    warnings += "HDR requires FLOAT32_32F output; forcing 32F plan."
                    OutputDepth.FLOAT32_32F
                }

            if (config.astroState == AstroState.STAR) {
                return plan(
                    config = config,
                    intent = ProcessingIntent.HDRI_MERGE,
                    alignmentMode = ResolvedAlignmentMode.STAR_ALIGNMENT,
                    outputDepth = outputDepth,
                    warnings = warnings,
                    unsupportedReason =
                        "HDR with Stars alignment is not supported because the foreground would blur."
                )
            }

            return plan(
                config = config,
                intent = ProcessingIntent.HDRI_MERGE,
                alignmentMode = landscapeAlignment(config),
                outputDepth = outputDepth,
                warnings = warnings
            )
        }

        return plan(
            config = config,
            intent = ProcessingIntent.SINGLE_CAPTURE,
            alignmentMode = ResolvedAlignmentMode.NONE,
            unsupportedReason = "Unsupported stacking configuration."
        )
    }

    private fun landscapeAlignment(config: StackingConfig): ResolvedAlignmentMode =
        if (config.alignToggle == AlignToggle.AUTO) {
            ResolvedAlignmentMode.LANDSCAPE_TRANSLATION
        } else {
            ResolvedAlignmentMode.NONE
        }

    private fun plan(
        config: StackingConfig,
        intent: ProcessingIntent,
        alignmentMode: ResolvedAlignmentMode,
        darkPolicy: DarkPolicy = config.darkPolicy,
        outputDepth: OutputDepth = config.outputDepth,
        warnings: List<String> = emptyList(),
        unsupportedReason: String? = null
    ): ResolvedProcessingPlan =
        ResolvedProcessingPlan(
            intent = intent,
            alignmentMode = alignmentMode,
            outputDepth = outputDepth,
            darkPolicy = darkPolicy,
            robustStackingEnabled = config.robustStackingEnabled,
            debugExportEnabled = config.debugExportEnabled,
            warnings = warnings,
            unsupportedReason = unsupportedReason
        )
}
