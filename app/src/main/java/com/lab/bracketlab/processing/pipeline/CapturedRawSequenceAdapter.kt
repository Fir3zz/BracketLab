package com.lab.bracketlab.processing.pipeline

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import com.lab.bracketlab.processing.raw.BayerUtils
import com.lab.bracketlab.processing.raw.RawFrameMetadataExtractor
import com.lab.bracketlab.processing.storage.FileBackedRaw16FrameStorage
import com.lab.bracketlab.processing.debug.DevRawDiagnosticMode
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

data class CapturedFrameAcceptResult(
    val accepted: Boolean,
    val frameIndex: Int?,
    val message: String
)

class CapturedRawSequenceAdapter(
    private val cameraId: String,
    private val cameraCharacteristics: CameraCharacteristics,
    private val outputDirectoryPath: String,
    val diagnosticMode: DevRawDiagnosticMode? = null,
    val hdriDarkCalibrationRequested: Boolean = false,
    val isDarkCalibrationSequence: Boolean = false
) {
    private val nextFrameIndex = AtomicInteger(0)
    private val records = mutableListOf<CapturedFrameRecord>()
    private val rejected = mutableListOf<String>()

    @Synchronized
    fun accept(
        image: Image,
        result: TotalCaptureResult,
        sequenceNumber: Int,
        captureFrameNumber: Int,
        requestedExposureTimeNs: Long,
        requestedIso: Int,
        dngOrientation: Int,
        dngOrientationDegrees: Int
    ): CapturedFrameAcceptResult {
        if (image.format != ImageFormat.RAW_SENSOR) {
            return reject("Frame $captureFrameNumber rejected: image format is not RAW_SENSOR.")
        }
        val plane = image.planes.firstOrNull()
            ?: return reject("Frame $captureFrameNumber rejected: missing RAW plane.")
        if (plane.pixelStride < 2 || plane.rowStride <= 0) {
            return reject("Frame $captureFrameNumber rejected: unsupported RAW stride.")
        }

        val imageTimestamp = image.timestamp
        val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        if (
            resultTimestamp != null &&
            imageTimestamp > 0L &&
            resultTimestamp > 0L &&
            imageTimestamp != resultTimestamp
        ) {
            return reject(
                "Frame $captureFrameNumber rejected: image timestamp $imageTimestamp does not match CaptureResult timestamp $resultTimestamp."
            )
        }

        val frameIndex = nextFrameIndex.getAndIncrement()
        val rawFile = copyRawPlaneToTemporaryFile(
            buffer = plane.buffer,
            width = image.width,
            height = image.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            frameIndex = frameIndex,
            timestampNs = resultTimestamp ?: imageTimestamp
        ) ?: return reject("Frame $captureFrameNumber rejected: RAW plane could not be copied to temporary storage.")

        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: requestedExposureTimeNs
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: requestedIso
        records += CapturedFrameRecord(
            frameIndex = frameIndex,
            sequenceNumber = sequenceNumber,
            captureFrameNumber = captureFrameNumber,
            raw16Storage = FileBackedRaw16FrameStorage(rawFile, deleteOnCleanup = true),
            width = image.width,
            height = image.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            imageTimestampNs = imageTimestamp,
            resultTimestampNs = resultTimestamp,
            exposureTimeNs = exposureTimeNs,
            iso = iso,
            cameraId = cameraId,
            cfaPattern = BayerUtils.fromCameraCharacteristicsValue(
                cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ),
            blackLevelPattern = RawFrameMetadataExtractor.extractBlackLevelPattern(
                cameraCharacteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ),
            whiteLevel = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            totalCaptureResult = result,
            cameraCharacteristics = cameraCharacteristics,
            dngOrientation = dngOrientation,
            dngOrientationDegrees = dngOrientationDegrees
        )
        return CapturedFrameAcceptResult(
            accepted = true,
            frameIndex = frameIndex,
            message =
                "Frame $captureFrameNumber accepted for " +
                    (
                        diagnosticMode?.let { "DEV ${it.name}" }
                            ?: if (isDarkCalibrationSequence) "Stack Modes Darks" else "Stack Modes"
                    ) +
                    " " +
                    "as frameIndex $frameIndex."
        )
    }

    @Synchronized
    fun snapshot(): CapturedRawSequence =
        CapturedRawSequence(
            cameraId = cameraId,
            cameraCharacteristics = cameraCharacteristics,
            records = records.toList(),
            rejectedFrames = rejected.toList(),
            outputDirectoryPath = outputDirectoryPath
        )

    @Synchronized
    private fun reject(message: String): CapturedFrameAcceptResult {
        rejected += message
        return CapturedFrameAcceptResult(false, null, message)
    }

    private fun copyRawPlaneToTemporaryFile(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        frameIndex: Int,
        timestampNs: Long
    ): File? {
        val requiredBytes =
            (height - 1).toLong() * rowStride.toLong() +
                (width - 1).toLong() * pixelStride.toLong() +
                2L
        if (requiredBytes <= 0L) return null

        val duplicate = buffer.duplicate()
        duplicate.position(0)
        if (requiredBytes > duplicate.limit().toLong()) return null
        duplicate.limit(requiredBytes.toInt())

        val rawDir = File(outputDirectoryPath, TEMP_RAW_DIRECTORY).also { it.mkdirs() }
        val outputFile = File(
            rawDir,
            "frame_${frameIndex}_${timestampNs.coerceAtLeast(0L)}.raw16"
        )
        return try {
            BufferedOutputStream(FileOutputStream(outputFile), COPY_BUFFER_BYTES).use { output ->
                val chunk = ByteArray(COPY_BUFFER_BYTES)
                while (duplicate.hasRemaining()) {
                    val count = minOf(chunk.size, duplicate.remaining())
                    duplicate.get(chunk, 0, count)
                    output.write(chunk, 0, count)
                }
                output.flush()
            }
            if (outputFile.length() == requiredBytes) {
                outputFile
            } else {
                outputFile.delete()
                null
            }
        } catch (_: IOException) {
            outputFile.delete()
            null
        } catch (_: SecurityException) {
            outputFile.delete()
            null
        }
    }

    companion object {
        private const val TEMP_RAW_DIRECTORY = ".raw16_frames"
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
