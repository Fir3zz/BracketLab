package com.lab.bracketlab.processing.model

data class RawStack(
    val frames: List<RawFrame>,
    val cameraId: String? = frames.firstOrNull()?.cameraId
) {
    val frameCount: Int
        get() = frames.size

    val isEmpty: Boolean
        get() = frames.isEmpty()
}
