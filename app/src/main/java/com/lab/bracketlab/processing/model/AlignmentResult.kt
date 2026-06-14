package com.lab.bracketlab.processing.model

data class AlignmentResult(
    val frameIndex: Int,
    val mode: ResolvedAlignmentMode = ResolvedAlignmentMode.NONE,
    val transform: RawTransform = RawTransform.IDENTITY,
    val confidence: Double = 1.0,
    val accepted: Boolean = true,
    val rejectionReason: String? = null,
    val diagnosticMessage: String? = null,
    val proxyDx: Double? = null,
    val proxyDy: Double? = null,
    val rawDx: Double? = null,
    val rawDy: Double? = null,
    val response: Double? = null
)
