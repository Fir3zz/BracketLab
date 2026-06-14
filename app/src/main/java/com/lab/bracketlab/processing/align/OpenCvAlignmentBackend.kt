package com.lab.bracketlab.processing.align

import com.lab.bracketlab.processing.model.AlignmentResult
import com.lab.bracketlab.processing.model.RawTransform
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import com.lab.bracketlab.processing.raw.RawProxy
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

class OpenCvAlignmentBackend(
    private val minimumVariance: Double = DEFAULT_MINIMUM_VARIANCE
) : AlignmentBackend {
    override fun isAvailable(): Boolean =
        when (OpenCvRuntime.ensureLoaded()) {
            OpenCvLoadResult.Success,
            OpenCvLoadResult.AlreadyLoaded -> true
            is OpenCvLoadResult.Failure -> false
        }

    override fun estimateTranslation(reference: RawProxy, target: RawProxy): AlignmentResult {
        validateProxies(reference, target)?.let { reason ->
            return rejected(target, reason)
        }

        when (val loadResult = OpenCvRuntime.ensureLoaded()) {
            OpenCvLoadResult.Success,
            OpenCvLoadResult.AlreadyLoaded -> Unit
            is OpenCvLoadResult.Failure ->
                return rejected(target, "${loadResult.reason} ${loadResult.exceptionMessage.orEmpty()}".trim())
        }

        val referenceMat = Mat()
        val targetMat = Mat()
        val hannWindow = Mat()
        return try {
            fillMat(referenceMat, reference)
            fillMat(targetMat, target)
            Imgproc.createHanningWindow(
                hannWindow,
                Size(reference.width.toDouble(), reference.height.toDouble()),
                CvType.CV_32F
            )

            val response = DoubleArray(1)
            val openCvShift = Imgproc.phaseCorrelate(referenceMat, targetMat, hannWindow, response)
            if (!openCvShift.x.isFinite() || !openCvShift.y.isFinite()) {
                return rejected(target, "OpenCV returned a non-finite shift.")
            }

            val transform = opencvShiftToTargetToReferenceTransform(
                target = target,
                openCvDxProxy = openCvShift.x,
                openCvDyProxy = openCvShift.y
            )

            AlignmentResult(
                frameIndex = target.sourceFrameIndex,
                mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
                transform = transform,
                confidence = response[0],
                accepted = true,
                diagnosticMessage =
                    "phaseCorrelate proxy=(${openCvShift.x}, ${openCvShift.y}) raw=(${transform.dx}, ${transform.dy})",
                proxyDx = -openCvShift.x,
                proxyDy = -openCvShift.y,
                rawDx = transform.dx,
                rawDy = transform.dy,
                response = response[0]
            )
        } catch (e: Throwable) {
            rejected(target, "OpenCV phase correlation failed: ${e.message}")
        } finally {
            referenceMat.release()
            targetMat.release()
            hannWindow.release()
        }
    }

    /**
     * OpenCV phaseCorrelate(reference, target) reports target displacement in proxy
     * coordinates. BracketLab stores the forward transform from target/source into
     * reference coordinates, so the sign is converted here by name instead of being
     * hidden at the call site.
     */
    fun opencvShiftToTargetToReferenceTransform(
        target: RawProxy,
        openCvDxProxy: Double,
        openCvDyProxy: Double
    ): RawTransform =
        RawTransform(
            dx = target.proxyDxToRawDx(-openCvDxProxy),
            dy = target.proxyDyToRawDy(-openCvDyProxy)
        )

    private fun validateProxies(reference: RawProxy, target: RawProxy): String? {
        if (reference.width != target.width || reference.height != target.height) {
            return "Proxy dimensions differ: reference=${reference.width}x${reference.height}, target=${target.width}x${target.height}."
        }
        if (reference.width <= 1 || reference.height <= 1) {
            return "Proxy dimensions must be greater than 1."
        }
        if (reference.data.size != reference.width * reference.height) {
            return "Reference proxy data size does not match dimensions."
        }
        if (target.data.size != target.width * target.height) {
            return "Target proxy data size does not match dimensions."
        }
        if (!reference.scaleX.isFinite() || !reference.scaleY.isFinite() ||
            !target.scaleX.isFinite() || !target.scaleY.isFinite() ||
            reference.scaleX <= 0.0 || reference.scaleY <= 0.0 ||
            target.scaleX <= 0.0 || target.scaleY <= 0.0
        ) {
            return "Proxy scale metadata is invalid."
        }
        if (!reference.data.all { it.isFinite() }) return "Reference proxy contains non-finite values."
        if (!target.data.all { it.isFinite() }) return "Target proxy contains non-finite values."
        if (variance(reference.data) < minimumVariance) return "Reference proxy is constant or nearly textureless."
        if (variance(target.data) < minimumVariance) return "Target proxy is constant or nearly textureless."
        return null
    }

    private fun fillMat(mat: Mat, proxy: RawProxy) {
        mat.create(proxy.height, proxy.width, CvType.CV_32F)
        mat.put(0, 0, proxy.data)
    }

    private fun variance(values: FloatArray): Double {
        if (values.isEmpty()) return 0.0
        var sum = 0.0
        for (value in values) sum += value.toDouble()
        val mean = sum / values.size.toDouble()
        var squared = 0.0
        for (value in values) {
            val delta = value.toDouble() - mean
            squared += delta * delta
        }
        return squared / values.size.toDouble()
    }

    private fun rejected(target: RawProxy, reason: String): AlignmentResult =
        AlignmentResult(
            frameIndex = target.sourceFrameIndex,
            mode = ResolvedAlignmentMode.LANDSCAPE_TRANSLATION,
            accepted = false,
            confidence = 0.0,
            rejectionReason = reason,
            diagnosticMessage = reason
        )

    private fun Double.isFinite(): Boolean =
        !isNaN() && abs(this) != Double.POSITIVE_INFINITY

    private fun Float.isFinite(): Boolean =
        !isNaN() && abs(this) != Float.POSITIVE_INFINITY

    companion object {
        private const val DEFAULT_MINIMUM_VARIANCE = 1.0e-12
    }
}
