package com.lab.bracketlab.processing.model

data class ProcessingResult(
    val success: Boolean,
    val outputFiles: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val diagnostics: Map<String, String> = emptyMap(),
    val rejectedFrameCount: Int = 0,
    val durationMs: Long? = null
) {
    companion object {
        fun success(
            outputFiles: List<String> = emptyList(),
            warnings: List<String> = emptyList(),
            diagnostics: Map<String, String> = emptyMap(),
            durationMs: Long? = null
        ): ProcessingResult =
            ProcessingResult(
                success = true,
                outputFiles = outputFiles,
                warnings = warnings,
                diagnostics = diagnostics,
                durationMs = durationMs
            )

        fun failure(
            errors: List<String>,
            warnings: List<String> = emptyList(),
            diagnostics: Map<String, String> = emptyMap()
        ): ProcessingResult =
            ProcessingResult(
                success = false,
                warnings = warnings,
                errors = errors,
                diagnostics = diagnostics
            )
    }
}
