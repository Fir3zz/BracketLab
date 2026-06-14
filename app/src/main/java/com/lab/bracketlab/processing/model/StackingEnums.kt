package com.lab.bracketlab.processing.model

enum class StackingOperation {
    OFF,
    NORMAL,
    HDRI
}

enum class InputSource {
    CAMERA,
    FILES
}

enum class OutputDepth {
    RAW16_16I,
    FLOAT32_32F
}

enum class AlignToggle {
    OFF,
    AUTO
}

enum class AstroState {
    OFF,
    STAR,
    DARK_CAPTURE
}

enum class ResolvedAlignmentMode {
    NONE,
    LANDSCAPE_TRANSLATION,
    LANDSCAPE_AFFINE,
    STAR_ALIGNMENT
}

enum class DarkPolicy {
    OFF,
    USE_COMPATIBLE_MASTER_DARK,
    CAPTURE_MASTER_DARK
}

enum class ProcessingIntent {
    SINGLE_CAPTURE,
    NORMAL_STACK,
    HDRI_MERGE,
    STAR_STACK,
    DARK_CAPTURE
}
