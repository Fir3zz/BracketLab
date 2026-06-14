package com.lab.bracketlab.processing.raw

enum class RawProxyType {
    GREEN,
    LUMA_ALIGNMENT
}

data class RawProxy(
    val width: Int,
    val height: Int,
    val data: FloatArray,
    /** Full-resolution RAW pixels represented by one proxy pixel on X. */
    val scaleX: Double,
    /** Full-resolution RAW pixels represented by one proxy pixel on Y. */
    val scaleY: Double,
    val sourceFrameIndex: Int,
    val exposureNormalized: Boolean,
    val proxyType: RawProxyType,
    val notes: String? = null
) {
    init {
        require(width > 0) { "Proxy width must be positive." }
        require(height > 0) { "Proxy height must be positive." }
        require(data.size == width * height) { "Proxy data size must match dimensions." }
        require(scaleX > 0.0 && scaleY > 0.0) { "Proxy scale must be positive." }
    }

    val rawPixelsPerProxyPixelX: Double
        get() = scaleX

    val rawPixelsPerProxyPixelY: Double
        get() = scaleY

    fun proxyDxToRawDx(dxProxy: Double): Double = dxProxy * scaleX

    fun proxyDyToRawDy(dyProxy: Double): Double = dyProxy * scaleY
}
