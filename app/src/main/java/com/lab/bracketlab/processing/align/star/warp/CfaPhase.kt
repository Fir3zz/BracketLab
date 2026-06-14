package com.lab.bracketlab.processing.align.star.warp

enum class CfaPhase {
    R,
    G1,
    G2,
    B
}

data class CfaPhaseOffset(
    val x: Int,
    val y: Int
) {
    init {
        require(x in 0..1 && y in 0..1)
    }
}
