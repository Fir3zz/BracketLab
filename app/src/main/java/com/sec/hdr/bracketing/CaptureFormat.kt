package com.sec.hdr.bracketing

import java.util.Locale
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

data class HdrBracketResult(
    val exposures: List<Long>,
    val frames: Int,
    val rangeStops: Double,
    val actualSpacing: Double
)

/** Converts a user exposure value like "1/30" or "0.5" into nanoseconds. */
fun parseExpString(s: String): Long? = runCatching {
    val clean = s.trim().replace(',', '.')
    val ns = if ('/' in clean) {
        val p = clean.split("/")
        p[0].toDouble() / p[1].toDouble() * 1_000_000_000L
    } else {
        clean.toDouble() * 1_000_000_000L
    }
    ns.toLong().coerceAtLeast(1_000L)
}.getOrNull()

/** Builds the HDR exposure sequence between highlight and shadow limits. */
fun buildHdrBrackets(highNs: Long, lowNs: Long, ev: Double): HdrBracketResult {
    val rangeStops = log2(lowNs.toDouble() / highNs.toDouble())
    val frames = (rangeStops / ev).toInt() + 1
    val actualSpacing = if (frames > 1) rangeStops / (frames - 1) else 0.0
    val exposures = (0 until frames).map { i ->
        (lowNs.toDouble() * 2.0.pow(-i * actualSpacing)).toLong().coerceAtLeast(1_000L)
    }
    return HdrBracketResult(exposures, frames, rangeStops, actualSpacing)
}

/** Creates evenly spaced manual-focus distances for focus bracketing. */
fun buildFocusBrackets(start: Float, end: Float, frames: Int): List<Float> =
    (0 until frames).map { i ->
        start + ((end - start) * i / (frames - 1))
    }

/** Formats nanoseconds back into the compact exposure text accepted by inputs. */
fun expNsToInputText(ns: Long): String {
    val sec = ns / 1_000_000_000.0
    return if (sec < 1.0) {
        "1/${(1.0 / sec).roundToInt().coerceAtLeast(1)}"
    } else {
        "%.1f".format(Locale.US, sec).trimEnd('0').trimEnd('.')
    }
}

/** Formats an exposure time in nanoseconds for display with seconds suffix. */
fun fmtExpNs(ns: Long): String {
    val s = ns / 1_000_000_000.0
    return if (s < 1.0) "1/${(1.0 / s).roundToInt()}s" else "${
        "%.1f".format(Locale.US, s).trimEnd('0').trimEnd('.')
    }s"
}

/** Keeps compatibility with callers that use the shorter exposure formatter name. */
fun fmtExp(ns: Long): String = fmtExpNs(ns)

/** Formats a raw nanosecond value using the most readable time unit. */
fun fmtNs(ns: Long): String = when {
    ns >= 1_000_000_000L -> "${ns / 1_000_000_000L}s"
    ns >= 1_000_000L -> "${ns / 1_000_000L}ms"
    ns >= 1_000L -> "${ns / 1_000L}us"
    else -> "${ns}ns"
}
