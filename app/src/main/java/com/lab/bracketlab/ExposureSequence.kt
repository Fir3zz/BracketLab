package com.lab.bracketlab

sealed class ExposureSequenceStep {
    data class Exposure(
        val label: String,
        val exposureTimeNs: Long
    ) : ExposureSequenceStep()

    object Pause : ExposureSequenceStep()
}

data class ExposureSequence(
    val steps: List<ExposureSequenceStep>
) {
    val exposures: List<Long>
        get() = steps.mapNotNull {
            (it as? ExposureSequenceStep.Exposure)?.exposureTimeNs
        }

    val exposureCount: Int
        get() = exposures.size

    val pauseCount: Int
        get() = steps.count { it === ExposureSequenceStep.Pause }
}

object ExposureSequenceParser {
    private val pauseEveryPattern =
        Regex("""pause\s*\(\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE)
    private val pauseMarkerPattern =
        Regex("""pause(?:\s*\(\s*\))?""", RegexOption.IGNORE_CASE)

    fun expand(
        text: String,
        parseExposure: (String) -> Long?
    ): String? {
        val tokens =
            text.split(Regex("[,\\r\\n]+"))
                .map(String::trim)
                .filter(String::isNotEmpty)
        if (tokens.isEmpty()) return null

        var pauseEvery: Int? = null
        val expanded = mutableListOf<String>()
        for (token in tokens) {
            val pauseMatch = pauseEveryPattern.matchEntire(token)
            if (pauseMatch != null) {
                val interval = pauseMatch.groupValues[1].toIntOrNull() ?: return null
                if (interval <= 0 || pauseEvery != null) return null
                pauseEvery = interval
                continue
            }
            if (pauseMarkerPattern.matches(token)) {
                expanded += PAUSE_LABEL
                continue
            }

            val plusIndex = token.lastIndexOf('+')
            if (plusIndex < 0) {
                if (parseExposure(token) == null) return null
                expanded += token
                continue
            }
            val exposure = token.substring(0, plusIndex).trim()
            val count = token.substring(plusIndex + 1).trim().toIntOrNull()
            if (
                exposure.isEmpty() ||
                count == null ||
                count <= 0 ||
                parseExposure(exposure) == null
            ) {
                return null
            }
            repeat(count) { expanded += exposure }
        }
        if (expanded.isEmpty()) return null

        val interval = pauseEvery
        if (interval != null) {
            if (expanded.any { it == PAUSE_LABEL }) return null
            val exposures = expanded.toList()
            expanded.clear()
            exposures.forEachIndexed { index, exposure ->
                expanded += exposure
                val completed = index + 1
                if (completed < exposures.size && completed % interval == 0) {
                    expanded += PAUSE_LABEL
                }
            }
        }
        return expanded.joinToString("\n")
    }

    fun parse(
        expandedText: String,
        parseExposure: (String) -> Long?
    ): ExposureSequence? {
        val steps = mutableListOf<ExposureSequenceStep>()
        for (
            line in expandedText.lines()
                .map(String::trim)
                .filter(String::isNotEmpty)
        ) {
            if (line.equals(PAUSE_LABEL, ignoreCase = true)) {
                if (steps.isEmpty() || steps.last() === ExposureSequenceStep.Pause) return null
                steps += ExposureSequenceStep.Pause
                continue
            }
            val exposure = parseExposure(line) ?: return null
            steps += ExposureSequenceStep.Exposure(line, exposure)
        }
        if (steps.lastOrNull() === ExposureSequenceStep.Pause) return null
        return ExposureSequence(steps).takeIf { it.exposureCount > 0 }
    }

    const val PAUSE_LABEL = "Pause"
}
