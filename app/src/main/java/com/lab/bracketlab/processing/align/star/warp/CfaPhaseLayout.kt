package com.lab.bracketlab.processing.align.star.warp

import com.lab.bracketlab.processing.raw.CfaPattern

data class CfaPhaseLayout(
    val pattern: CfaPattern,
    private val offsets: Map<CfaPhase, CfaPhaseOffset>
) {
    fun offsetOf(phase: CfaPhase): CfaPhaseOffset =
        requireNotNull(offsets[phase])

    fun phaseAt(x: Int, y: Int): CfaPhase {
        val px = x and 1
        val py = y and 1
        return offsets.entries.first { it.value.x == px && it.value.y == py }.key
    }

    companion object {
        fun from(pattern: CfaPattern?): CfaPhaseLayout? =
            when (pattern) {
                CfaPattern.RGGB -> layout(pattern, R = 0 to 0, G1 = 1 to 0, G2 = 0 to 1, B = 1 to 1)
                CfaPattern.GRBG -> layout(pattern, G1 = 0 to 0, R = 1 to 0, B = 0 to 1, G2 = 1 to 1)
                CfaPattern.GBRG -> layout(pattern, G1 = 0 to 0, B = 1 to 0, R = 0 to 1, G2 = 1 to 1)
                CfaPattern.BGGR -> layout(pattern, B = 0 to 0, G1 = 1 to 0, G2 = 0 to 1, R = 1 to 1)
                else -> null
            }

        private fun layout(
            pattern: CfaPattern,
            R: Pair<Int, Int>,
            G1: Pair<Int, Int>,
            G2: Pair<Int, Int>,
            B: Pair<Int, Int>
        ): CfaPhaseLayout =
            CfaPhaseLayout(
                pattern,
                mapOf(
                    CfaPhase.R to CfaPhaseOffset(R.first, R.second),
                    CfaPhase.G1 to CfaPhaseOffset(G1.first, G1.second),
                    CfaPhase.G2 to CfaPhaseOffset(G2.first, G2.second),
                    CfaPhase.B to CfaPhaseOffset(B.first, B.second)
                )
            )
    }
}
