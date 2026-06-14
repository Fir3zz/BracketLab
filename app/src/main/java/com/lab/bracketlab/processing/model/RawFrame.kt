package com.lab.bracketlab.processing.model

import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.storage.InMemoryRaw16FrameStorage
import com.lab.bracketlab.processing.storage.Raw16FrameStorage

data class RawFrame(
    val width: Int,
    val height: Int,
    val raw16: ByteArray? = null,
    val raw16Storage: Raw16FrameStorage? = null,
    val rowStride: Int,
    val pixelStride: Int,
    val exposureTimeNs: Long,
    val iso: Int,
    val cameraId: String? = null,
    val timestampNs: Long? = null,
    val frameIndex: Int = 0,
    val sourceFilePath: String? = null,
    val blackLevelPattern: IntArray? = null,
    val whiteLevel: Int? = null,
    val cfaPattern: CfaPattern? = null
) {
    val exposureTimeSeconds: Double
        get() = exposureTimeNs / 1_000_000_000.0

    fun resolvedRaw16Storage(): Raw16FrameStorage? =
        raw16Storage ?: raw16?.let(::InMemoryRaw16FrameStorage)
}
