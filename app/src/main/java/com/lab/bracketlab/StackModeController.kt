package com.lab.bracketlab

enum class StackMode {
    OFF,
    NORMAL,
    HDR
}

class StackModeController {
    var mode: StackMode = StackMode.OFF
        private set

    val normalEnabled: Boolean
        get() = mode == StackMode.NORMAL

    val hdrEnabled: Boolean
        get() = mode == StackMode.HDR

    fun toggleNormal(): StackMode {
        mode = if (mode == StackMode.NORMAL) StackMode.OFF else StackMode.NORMAL
        return mode
    }

    fun toggleHdr(): StackMode {
        mode = if (mode == StackMode.HDR) StackMode.OFF else StackMode.HDR
        return mode
    }

    fun reset() {
        mode = StackMode.OFF
    }
}
