package com.lab.bracketlab.processing.pipeline

import com.lab.bracketlab.processing.model.AlignToggle
import com.lab.bracketlab.processing.model.AstroState
import com.lab.bracketlab.processing.model.DarkPolicy
import com.lab.bracketlab.processing.model.OutputDepth
import com.lab.bracketlab.processing.model.ProcessingIntent
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import com.lab.bracketlab.processing.model.StackingConfig
import com.lab.bracketlab.processing.model.StackingOperation

object StackingPlannerSelfTest {
    fun run(): List<String> {
        val failures = mutableListOf<String>()

        checkPlan("default single capture", failures) {
            val plan = StackingPlanner.resolve(StackingConfig())
            plan.intent == ProcessingIntent.SINGLE_CAPTURE &&
                plan.alignmentMode == ResolvedAlignmentMode.NONE
        }

        checkPlan("normal align auto uses translation", failures) {
            val plan = StackingPlanner.resolve(
                StackingConfig(
                    operation = StackingOperation.NORMAL,
                    alignToggle = AlignToggle.AUTO
                )
            )
            plan.intent == ProcessingIntent.NORMAL_STACK &&
                plan.alignmentMode == ResolvedAlignmentMode.LANDSCAPE_TRANSLATION
        }

        checkPlan("normal align off uses no alignment", failures) {
            val plan = StackingPlanner.resolve(
                StackingConfig(
                    operation = StackingOperation.NORMAL,
                    astroState = AstroState.OFF,
                    alignToggle = AlignToggle.OFF
                )
            )
            plan.intent == ProcessingIntent.NORMAL_STACK &&
                plan.alignmentMode == ResolvedAlignmentMode.NONE
        }

        checkPlan("astro star overrides align", failures) {
            val plan = StackingPlanner.resolve(
                StackingConfig(
                    operation = StackingOperation.NORMAL,
                    alignToggle = AlignToggle.AUTO,
                    astroState = AstroState.STAR
                )
            )
            plan.intent == ProcessingIntent.STAR_STACK &&
                plan.alignmentMode == ResolvedAlignmentMode.STAR_ALIGNMENT &&
                plan.warnings.isNotEmpty()
        }

        checkPlan("dark capture has priority", failures) {
            val plan = StackingPlanner.resolve(
                StackingConfig(
                    operation = StackingOperation.HDRI,
                    astroState = AstroState.DARK_CAPTURE
                )
            )
            plan.intent == ProcessingIntent.DARK_CAPTURE &&
                plan.darkPolicy == DarkPolicy.CAPTURE_MASTER_DARK &&
                plan.alignmentMode == ResolvedAlignmentMode.NONE
        }

        checkPlan("hdri forces 32f", failures) {
            val plan = StackingPlanner.resolve(
                StackingConfig(
                    operation = StackingOperation.HDRI,
                    outputDepth = OutputDepth.RAW16_16I
                )
            )
            plan.intent == ProcessingIntent.HDRI_MERGE &&
                plan.outputDepth == OutputDepth.FLOAT32_32F &&
                plan.warnings.isNotEmpty()
        }

        return failures
    }

    private fun checkPlan(
        name: String,
        failures: MutableList<String>,
        predicate: () -> Boolean
    ) {
        if (!predicate()) failures += name
    }
}
