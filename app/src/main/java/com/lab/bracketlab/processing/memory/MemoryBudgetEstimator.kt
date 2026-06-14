package com.lab.bracketlab.processing.memory

import com.lab.bracketlab.processing.model.RawStack

enum class Raw16ProcessingStrategy {
    IN_MEMORY,
    FILE_BACKED_TILED
}

data class MemoryBudgetEstimate(
    val runtimeMaxMemoryBytes: Long,
    val runtimeTotalMemoryBytes: Long,
    val runtimeFreeMemoryBytes: Long,
    val estimatedResidentInputBytes: Long,
    val estimatedOutputBytes: Long,
    val estimatedDiagnosticMapBytes: Long,
    val safetyMarginBytes: Long,
    val estimatedPeakAdditionalBytes: Long,
    val selectedStrategy: Raw16ProcessingStrategy,
    val reason: String
)

object MemoryBudgetEstimator {
    fun estimate(
        rawStack: RawStack,
        includeValidCountMap: Boolean = false,
        includeRejectionCountMap: Boolean = false,
        preferFileBackedForRealFrames: Boolean = true,
        runtime: Runtime = Runtime.getRuntime()
    ): MemoryBudgetEstimate {
        val first = rawStack.frames.firstOrNull()
        val outputBytes =
            if (first == null) 0L else safeMultiply(safeMultiply(first.width.toLong(), first.height.toLong()), 2L)
        val pixelCount =
            if (first == null) 0L else safeMultiply(first.width.toLong(), first.height.toLong())
        val mapCount = (if (includeValidCountMap) 1L else 0L) +
            (if (includeRejectionCountMap) 1L else 0L)
        val mapBytes = safeMultiply(safeMultiply(pixelCount, Int.SIZE_BYTES.toLong()), mapCount)
        val residentInputs = rawStack.frames.fold(0L) { total, frame ->
            safeAdd(total, frame.resolvedRaw16Storage()?.residentByteCount ?: 0L)
        }

        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = (totalMemory - freeMemory).coerceAtLeast(0L)
        val remainingToLimit = (maxMemory - usedMemory).coerceAtLeast(0L)
        val safetyMargin = maxOf(MINIMUM_SAFETY_MARGIN_BYTES, maxMemory / 4L)
        val safeRemaining = (remainingToLimit - safetyMargin).coerceAtLeast(0L)
        val peakAdditional = safeAdd(outputBytes, mapBytes)
        val hasFileBackedInput = rawStack.frames.any { it.resolvedRaw16Storage()?.isFileBacked == true }

        val strategy = when {
            preferFileBackedForRealFrames && hasFileBackedInput ->
                Raw16ProcessingStrategy.FILE_BACKED_TILED
            safeAdd(residentInputs, peakAdditional) > safeRemaining ->
                Raw16ProcessingStrategy.FILE_BACKED_TILED
            else ->
                Raw16ProcessingStrategy.IN_MEMORY
        }
        val reason = when {
            strategy == Raw16ProcessingStrategy.FILE_BACKED_TILED && hasFileBackedInput ->
                "Real captured frames are file-backed; tiled processing avoids restoring them to Java heap."
            strategy == Raw16ProcessingStrategy.FILE_BACKED_TILED ->
                "Estimated resident inputs plus output exceed the configured Java-heap safety budget."
            else ->
                "Estimated resident inputs and output fit within the Java-heap safety budget."
        }

        return MemoryBudgetEstimate(
            runtimeMaxMemoryBytes = maxMemory,
            runtimeTotalMemoryBytes = totalMemory,
            runtimeFreeMemoryBytes = freeMemory,
            estimatedResidentInputBytes = residentInputs,
            estimatedOutputBytes = outputBytes,
            estimatedDiagnosticMapBytes = mapBytes,
            safetyMarginBytes = safetyMargin,
            estimatedPeakAdditionalBytes = peakAdditional,
            selectedStrategy = strategy,
            reason = reason
        )
    }

    internal fun safeMultiply(left: Long, right: Long): Long {
        if (left == 0L || right == 0L) return 0L
        if (left < 0L || right < 0L) return Long.MAX_VALUE
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE
        return left * right
    }

    internal fun safeAdd(left: Long, right: Long): Long {
        if (left < 0L || right < 0L) return Long.MAX_VALUE
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE
        return left + right
    }

    private const val MINIMUM_SAFETY_MARGIN_BYTES = 48L * 1024L * 1024L
}
