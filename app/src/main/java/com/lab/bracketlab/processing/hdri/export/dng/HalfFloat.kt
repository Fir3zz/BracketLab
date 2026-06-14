package com.lab.bracketlab.processing.hdri.export.dng

data class HalfFloatDiagnostics(
    var valuesAboveOne: Long = 0L,
    var maximumClamps: Long = 0L,
    var invalidReplacements: Long = 0L,
    var negativeClamps: Long = 0L
)

object HalfFloat {
    const val MAX_FINITE_VALUE = 65504.0f
    const val MAX_FINITE_BITS = 0x7BFF

    fun fromFloat(value: Float, diagnostics: HalfFloatDiagnostics? = null): Short {
        var safe = value
        when {
            safe.isNaN() -> {
                diagnostics?.invalidReplacements = diagnostics?.invalidReplacements?.plus(1L) ?: 0L
                safe = 0f
            }
            safe == Float.POSITIVE_INFINITY -> {
                diagnostics?.invalidReplacements = diagnostics?.invalidReplacements?.plus(1L) ?: 0L
                diagnostics?.maximumClamps = diagnostics?.maximumClamps?.plus(1L) ?: 0L
                safe = MAX_FINITE_VALUE
            }
            safe == Float.NEGATIVE_INFINITY -> {
                diagnostics?.invalidReplacements = diagnostics?.invalidReplacements?.plus(1L) ?: 0L
                diagnostics?.negativeClamps = diagnostics?.negativeClamps?.plus(1L) ?: 0L
                safe = 0f
            }
            safe < 0f -> {
                diagnostics?.negativeClamps = diagnostics?.negativeClamps?.plus(1L) ?: 0L
                safe = 0f
            }
            safe > MAX_FINITE_VALUE -> {
                diagnostics?.maximumClamps = diagnostics?.maximumClamps?.plus(1L) ?: 0L
                safe = MAX_FINITE_VALUE
            }
        }
        if (safe > 1f) {
            diagnostics?.valuesAboveOne = diagnostics?.valuesAboveOne?.plus(1L) ?: 0L
        }
        if (safe == 0f) return 0
        if (safe == MAX_FINITE_VALUE) return MAX_FINITE_BITS.toShort()

        val bits = java.lang.Float.floatToRawIntBits(safe)
        val exponent = ((bits ushr 23) and 0xFF) - 127
        val mantissa = bits and 0x7FFFFF

        if (exponent < -24) return 0
        if (exponent < -14) {
            val significand = mantissa or 0x800000
            val shift = -exponent - 1
            var halfMantissa = significand ushr shift
            val remainderMask = (1 shl shift) - 1
            val remainder = significand and remainderMask
            val halfway = 1 shl (shift - 1)
            if (remainder > halfway || (remainder == halfway && (halfMantissa and 1) != 0)) {
                halfMantissa++
            }
            return halfMantissa.toShort()
        }

        var halfExponent = exponent + 15
        var halfMantissa = mantissa ushr 13
        val remainder = mantissa and 0x1FFF
        if (remainder > 0x1000 || (remainder == 0x1000 && (halfMantissa and 1) != 0)) {
            halfMantissa++
            if (halfMantissa == 0x400) {
                halfMantissa = 0
                halfExponent++
            }
        }
        if (halfExponent >= 31) {
            diagnostics?.maximumClamps = diagnostics?.maximumClamps?.plus(1L) ?: 0L
            return MAX_FINITE_BITS.toShort()
        }
        return ((halfExponent shl 10) or halfMantissa).toShort()
    }

    fun toFloat(bits: Short): Float {
        val value = bits.toInt() and 0xFFFF
        val sign = (value ushr 15) and 1
        val exponent = (value ushr 10) and 0x1F
        val mantissa = value and 0x3FF
        val floatBits =
            when (exponent) {
                0 -> {
                    if (mantissa == 0) {
                        sign shl 31
                    } else {
                        var normalized = mantissa
                        var adjustedExponent = -14
                        while ((normalized and 0x400) == 0) {
                            normalized = normalized shl 1
                            adjustedExponent--
                        }
                        normalized = normalized and 0x3FF
                        (sign shl 31) or
                            ((adjustedExponent + 127) shl 23) or
                            (normalized shl 13)
                    }
                }
                31 ->
                    (sign shl 31) or (0xFF shl 23) or (mantissa shl 13)
                else ->
                    (sign shl 31) or
                        ((exponent - 15 + 127) shl 23) or
                        (mantissa shl 13)
            }
        return java.lang.Float.intBitsToFloat(floatBits)
    }
}
