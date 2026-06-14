package com.lab.bracketlab.processing.align

import org.opencv.android.OpenCVLoader

sealed class OpenCvLoadResult {
    object Success : OpenCvLoadResult()
    object AlreadyLoaded : OpenCvLoadResult()
    data class Failure(
        val reason: String,
        val exceptionMessage: String? = null
    ) : OpenCvLoadResult()
}

object OpenCvRuntime {
    @Volatile
    private var loaded = false

    @Volatile
    private var failureReason: String? = null

    @Synchronized
    fun ensureLoaded(): OpenCvLoadResult {
        if (loaded) return OpenCvLoadResult.AlreadyLoaded

        return try {
            if (OpenCVLoader.initLocal()) {
                loaded = true
                failureReason = null
                OpenCvLoadResult.Success
            } else {
                val reason = "OpenCVLoader.initLocal() returned false."
                failureReason = reason
                OpenCvLoadResult.Failure(reason)
            }
        } catch (e: Throwable) {
            val reason = "OpenCV initialization threw ${e::class.java.simpleName}."
            failureReason = reason
            OpenCvLoadResult.Failure(reason, e.message)
        }
    }

    fun isLoaded(): Boolean = loaded

    fun lastFailureReason(): String? = failureReason
}
