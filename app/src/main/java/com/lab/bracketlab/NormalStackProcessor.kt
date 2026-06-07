package com.lab.bracketlab

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class NormalStackProcessor(
    private val context: Context,
    private val captureFolder: String?,
    private val cameraCharacteristics: CameraCharacteristics?,
    private val wbGains: RggbChannelVector?,
    private val log: (String) -> Unit
) {
    private var width = 0
    private var height = 0
    private var referenceExposureNs: Long? = null
    private var referenceIso: Int? = null
    private var firstResult: TotalCaptureResult? = null
    private var firstDngOrientation: Int? = null
    private var frameCount = 0
    private var disabled = false
    private val tempFiles = mutableListOf<File>()
    private val tempDir = File(
        context.cacheDir,
        "normal_stack_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}"
    )

    fun accept(
        image: Image,
        result: TotalCaptureResult,
        dngOrientation: Int,
        expNs: Long,
        iso: Int
    ) {
        if (disabled) return
        if (image.format != ImageFormat.RAW_SENSOR) {
            disable("Normal Stack skipped: RAW format mismatch")
            return
        }
        if (!ensureReady(image, result, dngOrientation, expNs, iso)) return

        try {
            tempDir.mkdirs()
            val file = File(tempDir, "frame_${frameCount + 1}.raw16")
            writeRawTemp(image, file)
            tempFiles += file
            frameCount++
            log("Normal Stack buffered $frameCount")
        } catch (e: Exception) {
            disable("Normal Stack skipped: ${e.message}")
        } catch (e: OutOfMemoryError) {
            disable("Normal Stack skipped: not enough memory")
        }
    }

    fun finish() {
        if (disabled || frameCount <= 1) {
            if (!disabled && frameCount == 1) log("Normal Stack skipped: only 1 frame")
            deleteTempFiles()
            return
        }

        val name = "NORMAL_STACK_${frameCount}f_${width}x${height}.dng"
        try {
            log("Normal Stack processing $frameCount frames")
            val stackedRaw = writeAveragedRaw()
            saveStackDng(name, stackedRaw)
            log("Normal Stack DNG saved $name")
        } catch (e: Exception) {
            log("Normal Stack error: ${e.message}")
        } catch (e: OutOfMemoryError) {
            log("Normal Stack error: not enough memory")
        } finally {
            deleteTempFiles()
        }
    }

    private fun ensureReady(
        image: Image,
        result: TotalCaptureResult,
        dngOrientation: Int,
        expNs: Long,
        iso: Int
    ): Boolean {
        if (referenceExposureNs == null) {
            width = image.width
            height = image.height
            referenceExposureNs = expNs
            referenceIso = iso
            firstResult = result
            firstDngOrientation = dngOrientation
            log("Normal Stack armed ${width}x${height}")
            return true
        }

        if (image.width != width || image.height != height) {
            disable("Normal Stack skipped: size mismatch")
            return false
        }
        if (expNs != referenceExposureNs || iso != referenceIso) {
            disable("Normal Stack skipped: exposure/ISO mismatch")
            return false
        }
        return true
    }

    private fun writeRawTemp(image: Image, file: File) {
        val plane = image.planes.firstOrNull() ?: error("missing RAW plane")
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride < 2) error("unsupported pixel stride")

        val buffer = plane.buffer.duplicate()
        val row = ByteArray(width * 2)
        FileOutputStream(file).buffered().use { out ->
            for (y in 0 until height) {
                val rowBase = y * rowStride
                if (pixelStride == 2) {
                    val length = width * 2
                    if (rowBase + length > buffer.limit()) error("RAW row stride mismatch")
                    buffer.position(rowBase)
                    buffer.get(row, 0, length)
                } else {
                    for (x in 0 until width) {
                        val offset = rowBase + x * pixelStride
                        if (offset + 1 >= buffer.limit()) error("RAW row stride mismatch")
                        row[x * 2] = buffer.get(offset)
                        row[x * 2 + 1] = buffer.get(offset + 1)
                    }
                }
                out.write(row)
            }
        }
    }

    private fun writeAveragedRaw(): File {
        val accumulator = IntArray(width * height)
        val row = ByteArray(width * 2)
        tempFiles.forEachIndexed { index, file ->
            FileInputStream(file).buffered().use { input ->
                var dst = 0
                for (y in 0 until height) {
                    readFully(input, row)
                    var src = 0
                    for (x in 0 until width) {
                        val lo = row[src++].toInt() and 0xFF
                        val hi = row[src++].toInt() and 0xFF
                        accumulator[dst++] += lo or (hi shl 8)
                    }
                }
            }
            log("Normal Stack read ${index + 1}/$frameCount")
        }

        val stackedRaw = File(tempDir, "normal_stack_average.raw16")
        FileOutputStream(stackedRaw).buffered().use { out ->
            var src = 0
            for (y in 0 until height) {
                var rowPos = 0
                for (x in 0 until width) {
                    val value = ((accumulator[src++] + frameCount / 2) / frameCount)
                        .coerceIn(0, 0xFFFF)
                    row[rowPos++] = (value and 0xFF).toByte()
                    row[rowPos++] = ((value ushr 8) and 0xFF).toByte()
                }
                out.write(row)
                if (y > 0 && y % max(1, height / 8) == 0) {
                    log("Normal Stack average ${(y * 100) / height}%")
                }
            }
        }
        return stackedRaw
    }

    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) error("temp read failed")
            offset += read
        }
    }

    private fun saveStackDng(name: String, stackedRaw: File) {
        val c = cameraCharacteristics ?: error("camera data missing")
        val result = firstResult ?: error("capture result missing")
        val bytes = createStackDngBytes(c, result, stackedRaw)
        saveDngBytes(name, bytes)
    }

    private fun createStackDngBytes(
        c: CameraCharacteristics,
        result: TotalCaptureResult,
        stackedRaw: File
    ): ByteArray {
        val bytes = ByteArrayOutputStream().use { output ->
            val creator = DngCreator(c, result)
            try {
                firstDngOrientation?.let { creator.setOrientation(it) }
                creator.setDescription("BracketLab Normal Stack, $frameCount frames")
                FileInputStream(stackedRaw).buffered().use { input ->
                    creator.writeInputStream(output, Size(width, height), input, 0L)
                }
            } finally {
                creator.close()
            }
            output.toByteArray()
        }

        if (wbGains != null) {
            val patched = patchDngAsShotNeutral(bytes, wbGains)
            log(if (patched) "Stack DNG WB metadata patched" else "Stack DNG WB patch skipped")
        }
        return bytes
    }

    private fun saveDngBytes(name: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativeFolder())
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("insert failed")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("stream failed")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                relativeFolder().removePrefix("DCIM/")
            ).also { it.mkdirs() }
            FileOutputStream(File(dir, name)).use { it.write(bytes) }
        }
    }

    private fun relativeFolder(): String =
        if (captureFolder == null) {
            "DCIM/BracketLab"
        } else {
            "DCIM/BracketLab/$captureFolder"
        }

    private fun patchDngAsShotNeutral(bytes: ByteArray, gains: RggbChannelVector): Boolean {
        if (bytes.size < 8) return false
        val littleEndian = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> true
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> false
            else -> return false
        }
        if (readUInt16(bytes, 2, littleEndian) != 42) return false

        val green = (gains.greenEven + gains.greenOdd) / 2f
        val neutral = floatArrayOf(
            (green / gains.red).coerceAtLeast(0.0001f),
            1f,
            (green / gains.blue).coerceAtLeast(0.0001f)
        )

        var ifdOffset = readUInt32(bytes, 4, littleEndian)
        var visited = 0
        while (ifdOffset > 0 && visited < 16) {
            if (ifdOffset + 2 > bytes.size) return false
            val entries = readUInt16(bytes, ifdOffset, littleEndian)
            val entriesStart = ifdOffset + 2
            val nextIfdOffset = entriesStart + entries * 12
            if (nextIfdOffset + 4 > bytes.size) return false

            repeat(entries) { index ->
                val entry = entriesStart + index * 12
                val tag = readUInt16(bytes, entry, littleEndian)
                if (tag == TIFF_TAG_AS_SHOT_NEUTRAL) {
                    val type = readUInt16(bytes, entry + 2, littleEndian)
                    val count = readUInt32(bytes, entry + 4, littleEndian)
                    if (count < 3 || type !in intArrayOf(5, 10)) return@repeat

                    val valueOffset = readUInt32(bytes, entry + 8, littleEndian)
                    if (valueOffset + 24 > bytes.size) return@repeat

                    writeRational(bytes, valueOffset, neutral[0], littleEndian)
                    writeRational(bytes, valueOffset + 8, neutral[1], littleEndian)
                    writeRational(bytes, valueOffset + 16, neutral[2], littleEndian)
                    return true
                }
            }

            ifdOffset = readUInt32(bytes, nextIfdOffset, littleEndian)
            visited++
        }
        return false
    }

    private fun readUInt16(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
        if (offset + 2 > bytes.size) return 0
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        return if (littleEndian) b0 or (b1 shl 8) else (b0 shl 8) or b1
    }

    private fun readUInt32(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
        if (offset + 4 > bytes.size) return 0
        val b0 = bytes[offset].toLong() and 0xFF
        val b1 = bytes[offset + 1].toLong() and 0xFF
        val b2 = bytes[offset + 2].toLong() and 0xFF
        val b3 = bytes[offset + 3].toLong() and 0xFF
        val value =
            if (littleEndian) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        return value.toInt()
    }

    private fun writeRational(bytes: ByteArray, offset: Int, value: Float, littleEndian: Boolean) {
        val denominator = 1_000_000
        val numerator = (value * denominator).roundToInt().coerceAtLeast(1)
        writeInt32(bytes, offset, numerator, littleEndian)
        writeInt32(bytes, offset + 4, denominator, littleEndian)
    }

    private fun writeInt32(bytes: ByteArray, offset: Int, value: Int, littleEndian: Boolean) {
        if (offset + 4 > bytes.size) return
        if (littleEndian) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        } else {
            bytes[offset] = ((value ushr 24) and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 3] = (value and 0xFF).toByte()
        }
    }

    private fun disable(message: String) {
        disabled = true
        deleteTempFiles()
        log(message)
    }

    private fun deleteTempFiles() {
        tempFiles.clear()
        runCatching { tempDir.deleteRecursively() }
    }

    private companion object {
        const val TIFF_TAG_AS_SHOT_NEUTRAL = 0xC628
    }
}
