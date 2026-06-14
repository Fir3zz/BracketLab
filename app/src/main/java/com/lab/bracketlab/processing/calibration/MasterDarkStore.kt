package com.lab.bracketlab.processing.calibration

import com.lab.bracketlab.processing.raw.CfaPattern
import com.lab.bracketlab.processing.stack.RawStackAggregationMode
import java.io.File
import java.io.IOException

data class MasterDarkStoreResult(
    val success: Boolean,
    val masterDark: MasterDark? = null,
    val failureCode: DarkCalibrationFailureCode? = null,
    val message: String? = null,
    val cleanupSucceeded: Boolean = true
)

enum class MasterDarkStoreScanIssueCode {
    ROOT_NOT_FOUND,
    ROOT_NOT_DIRECTORY,
    ROOT_NOT_READABLE,
    DIRECTORY_LIST_FAILED,
    METADATA_PARSE_FAILED,
    RAW_NOT_FOUND,
    RAW_SIZE_MISMATCH
}

data class MasterDarkStoreScanIssue(
    val code: MasterDarkStoreScanIssueCode,
    val path: String,
    val message: String
)

data class MasterDarkStoreScanResult(
    val masterDarks: List<MasterDark>,
    val issues: List<MasterDarkStoreScanIssue>,
    val rootExists: Boolean,
    val rootCanRead: Boolean,
    val filesScanned: Int
)

class MasterDarkStore(
    private val rootDirectory: File
) {
    fun save(masterDark: MasterDark): MasterDarkStoreResult {
        val metadata = masterDark.metadata
        val directory = directoryFor(metadata).also { it.mkdirs() }
        val finalRaw = File(directory, metadata.rawFilename)
        val finalMetadata = File(directory, "${metadata.id}.json")
        val tempRaw = File(directory, ".${metadata.id}.${System.nanoTime()}.raw16.tmp")
        val tempMetadata = File(directory, ".${metadata.id}.${System.nanoTime()}.json.tmp")
        var rawCommitted = false

        return try {
            masterDark.rawFile.inputStream().use { input ->
                tempRaw.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            tempMetadata.writeText(MasterDarkMetadataJson.encode(metadata), Charsets.UTF_8)
            if (tempRaw.length() != metadata.width.toLong() * metadata.height.toLong() * 2L) {
                throw IOException("Stored MasterDark RAW size is invalid.")
            }
            if (finalRaw.exists() && !finalRaw.delete()) {
                throw IOException("Could not replace existing MasterDark RAW.")
            }
            if (finalMetadata.exists() && !finalMetadata.delete()) {
                throw IOException("Could not replace existing MasterDark metadata.")
            }
            if (!tempRaw.renameTo(finalRaw)) {
                throw IOException("Could not commit MasterDark RAW file.")
            }
            rawCommitted = true
            if (!tempMetadata.renameTo(finalMetadata)) {
                throw IOException("Could not commit MasterDark metadata file.")
            }
            if (masterDark.rawFile.absolutePath != finalRaw.absolutePath) {
                masterDark.rawFile.delete()
            }
            MasterDarkStoreResult(
                success = true,
                masterDark = MasterDark(metadata, finalRaw, finalMetadata)
            )
        } catch (error: Throwable) {
            val cleaned =
                (!tempRaw.exists() || tempRaw.delete()) &&
                    (!tempMetadata.exists() || tempMetadata.delete()) &&
                    (!rawCommitted || !finalRaw.exists() || finalRaw.delete()) &&
                    (!finalMetadata.exists() || finalMetadata.delete())
            MasterDarkStoreResult(
                success = false,
                failureCode = DarkCalibrationFailureCode.MASTER_DARK_STORAGE_FAILED,
                message = error.message,
                cleanupSucceeded = cleaned
            )
        }
    }

    fun listAvailable(): List<MasterDark> = scanAvailable().masterDarks

    fun scanAvailable(): MasterDarkStoreScanResult {
        cleanupIncompleteWrites()
        val issues = mutableListOf<MasterDarkStoreScanIssue>()
        val discoveredFiles = mutableListOf<File>()
        val rootExists = rootDirectory.exists()
        val rootCanRead = rootDirectory.canRead()
        if (!rootExists) {
            issues += scanIssue(
                MasterDarkStoreScanIssueCode.ROOT_NOT_FOUND,
                rootDirectory,
                "MasterDark root does not exist."
            )
        } else if (!rootDirectory.isDirectory) {
            issues += scanIssue(
                MasterDarkStoreScanIssueCode.ROOT_NOT_DIRECTORY,
                rootDirectory,
                "MasterDark root is not a directory."
            )
        } else if (!rootCanRead) {
            issues += scanIssue(
                MasterDarkStoreScanIssueCode.ROOT_NOT_READABLE,
                rootDirectory,
                "MasterDark root is not readable by the app."
            )
        } else {
            collectFiles(rootDirectory, discoveredFiles, issues)
        }

        val masterDarks =
            discoveredFiles
                .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                .mapNotNull { metadataFile ->
                    val metadata =
                        runCatching {
                            MasterDarkMetadataJson.decode(metadataFile.readText(Charsets.UTF_8))
                        }.getOrElse { error ->
                            issues += scanIssue(
                                MasterDarkStoreScanIssueCode.METADATA_PARSE_FAILED,
                                metadataFile,
                                error.message ?: error.javaClass.simpleName
                            )
                            return@mapNotNull null
                        }
                    val rawFile = File(metadataFile.parentFile, metadata.rawFilename)
                    val expectedSize = metadata.width.toLong() * metadata.height.toLong() * 2L
                    when {
                        !rawFile.isFile -> {
                            issues += scanIssue(
                                MasterDarkStoreScanIssueCode.RAW_NOT_FOUND,
                                rawFile,
                                "RAW file declared by ${metadataFile.name} was not found."
                            )
                            null
                        }
                        rawFile.length() != expectedSize -> {
                            issues += scanIssue(
                                MasterDarkStoreScanIssueCode.RAW_SIZE_MISMATCH,
                                rawFile,
                                "RAW size=${rawFile.length()}, expected=$expectedSize."
                            )
                            null
                        }
                        else -> MasterDark(metadata, rawFile, metadataFile)
                    }
                }
                .sortedByDescending { it.metadata.createdAtMillis }
        return MasterDarkStoreScanResult(
            masterDarks = masterDarks,
            issues = issues,
            rootExists = rootExists,
            rootCanRead = rootCanRead,
            filesScanned = discoveredFiles.size
        )
    }

    private fun collectFiles(
        directory: File,
        output: MutableList<File>,
        issues: MutableList<MasterDarkStoreScanIssue>
    ) {
        val children = directory.listFiles()
        if (children == null) {
            issues += scanIssue(
                MasterDarkStoreScanIssueCode.DIRECTORY_LIST_FAILED,
                directory,
                "Directory listing returned null."
            )
            return
        }
        for (child in children) {
            if (child.isDirectory) {
                collectFiles(child, output, issues)
            } else {
                output += child
            }
        }
    }

    private fun scanIssue(
        code: MasterDarkStoreScanIssueCode,
        file: File,
        message: String
    ): MasterDarkStoreScanIssue =
        MasterDarkStoreScanIssue(code, file.absolutePath, message)

    fun loadMetadata(metadataFile: File): MasterDarkMetadata? =
        runCatching {
            MasterDarkMetadataJson.decode(metadataFile.readText(Charsets.UTF_8))
        }.getOrNull()

    fun delete(masterDark: MasterDark): Boolean {
        val rawDeleted = !masterDark.rawFile.exists() || masterDark.rawFile.delete()
        val metadataDeleted =
            masterDark.metadataFile == null ||
                !masterDark.metadataFile.exists() ||
                masterDark.metadataFile.delete()
        return rawDeleted && metadataDeleted
    }

    fun cleanupIncompleteWrites(): Boolean {
        if (!rootDirectory.exists()) return true
        return rootDirectory
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".tmp") }
            .fold(true) { cleaned, file -> (!file.exists() || file.delete()) && cleaned }
    }

    private fun directoryFor(metadata: MasterDarkMetadata): File {
        val camera = safePath(metadata.cameraId ?: "unknown")
        return File(
            rootDirectory,
            "camera_$camera${File.separator}ISO_${metadata.iso}${File.separator}EXP_${metadata.exposureTimeNs}ns"
        )
    }

    private fun safePath(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}

internal object MasterDarkMetadataJson {
    fun encode(metadata: MasterDarkMetadata): String =
        buildString {
            appendLine("{")
            appendLine("  \"id\": \"${escape(metadata.id)}\",")
            appendLine("  \"width\": ${metadata.width},")
            appendLine("  \"height\": ${metadata.height},")
            appendLine("  \"rowStride\": ${metadata.rowStride},")
            appendLine("  \"pixelStride\": ${metadata.pixelStride},")
            appendLine("  \"cfaPattern\": \"${metadata.cfaPattern.name}\",")
            appendLine("  \"cameraId\": ${metadata.cameraId?.let { "\"${escape(it)}\"" } ?: "null"},")
            appendLine("  \"iso\": ${metadata.iso},")
            appendLine("  \"exposureTimeNs\": ${metadata.exposureTimeNs},")
            appendLine("  \"frameCount\": ${metadata.frameCount},")
            appendLine("  \"aggregationMode\": \"${metadata.aggregationMode.name}\",")
            appendLine("  \"blackLevelPattern\": ${intArray(metadata.blackLevelPattern)},")
            appendLine("  \"whiteLevel\": ${metadata.whiteLevel ?: "null"},")
            appendLine("  \"createdAtMillis\": ${metadata.createdAtMillis},")
            appendLine("  \"appVersion\": ${metadata.appVersion?.let { "\"${escape(it)}\"" } ?: "null"},")
            appendLine("  \"rawFilename\": \"${escape(metadata.rawFilename)}\",")
            appendLine("  \"minimumDarkValue\": ${metadata.minimumDarkValue ?: "null"},")
            appendLine("  \"maximumDarkValue\": ${metadata.maximumDarkValue ?: "null"},")
            appendLine("  \"meanDarkValue\": ${metadata.meanDarkValue ?: "null"},")
            appendLine("  \"sourceFrames\": [")
            metadata.sourceFrames.forEachIndexed { index, frame ->
                append(
                    "    {\"frameIndex\": ${frame.frameIndex}, \"timestampNs\": " +
                        "${frame.timestampNs ?: "null"}, \"iso\": ${frame.iso}, " +
                        "\"exposureTimeNs\": ${frame.exposureTimeNs}}"
                )
                if (index != metadata.sourceFrames.lastIndex) append(',')
                appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }

    fun decode(json: String): MasterDarkMetadata {
        val sourceFramesBody =
            Regex("\"sourceFrames\"\\s*:\\s*\\[(.*)\\]", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(json)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        val sourceFrames =
            Regex("\\{([^{}]+)\\}")
                .findAll(sourceFramesBody)
                .map { match ->
                    val body = match.value
                    DarkSourceFrameSummary(
                        frameIndex = requiredLong(body, "frameIndex").toInt(),
                        timestampNs = nullableLong(body, "timestampNs"),
                        iso = requiredLong(body, "iso").toInt(),
                        exposureTimeNs = requiredLong(body, "exposureTimeNs")
                    )
                }
                .toList()
        return MasterDarkMetadata(
            id = requiredString(json, "id"),
            width = requiredLong(json, "width").toInt(),
            height = requiredLong(json, "height").toInt(),
            rowStride = requiredLong(json, "rowStride").toInt(),
            pixelStride = requiredLong(json, "pixelStride").toInt(),
            cfaPattern = CfaPattern.valueOf(requiredString(json, "cfaPattern")),
            cameraId = nullableString(json, "cameraId"),
            iso = requiredLong(json, "iso").toInt(),
            exposureTimeNs = requiredLong(json, "exposureTimeNs"),
            frameCount = requiredLong(json, "frameCount").toInt(),
            aggregationMode =
                RawStackAggregationMode.valueOf(requiredString(json, "aggregationMode")),
            blackLevelPattern = nullableIntArray(json, "blackLevelPattern"),
            whiteLevel = nullableLong(json, "whiteLevel")?.toInt(),
            createdAtMillis = requiredLong(json, "createdAtMillis"),
            appVersion = nullableString(json, "appVersion"),
            rawFilename = requiredString(json, "rawFilename"),
            sourceFrames = sourceFrames,
            minimumDarkValue = nullableLong(json, "minimumDarkValue")?.toInt(),
            maximumDarkValue = nullableLong(json, "maximumDarkValue")?.toInt(),
            meanDarkValue = nullableDouble(json, "meanDarkValue")
        )
    }

    private fun requiredString(json: String, key: String): String =
        nullableString(json, key) ?: error("Missing metadata field $key.")

    private fun nullableString(json: String, key: String): String? {
        val match =
            Regex("\"${Regex.escape(key)}\"\\s*:\\s*(null|\"((?:\\\\.|[^\"])*)\")")
                .find(json)
                ?: return null
        if (match.groupValues[1] == "null") return null
        return unescape(match.groupValues[2])
    }

    private fun requiredLong(json: String, key: String): Long =
        nullableLong(json, key) ?: error("Missing metadata field $key.")

    private fun nullableLong(json: String, key: String): Long? {
        val value =
            Regex("\"${Regex.escape(key)}\"\\s*:\\s*(null|-?\\d+)")
                .find(json)
                ?.groupValues
                ?.get(1)
                ?: return null
        return value.takeUnless { it == "null" }?.toLong()
    }

    private fun nullableDouble(json: String, key: String): Double? {
        val value =
            Regex("\"${Regex.escape(key)}\"\\s*:\\s*(null|-?\\d+(?:\\.\\d+)?)")
                .find(json)
                ?.groupValues
                ?.get(1)
                ?: return null
        return value.takeUnless { it == "null" }?.toDouble()
    }

    private fun nullableIntArray(json: String, key: String): IntArray? {
        val value =
            Regex("\"${Regex.escape(key)}\"\\s*:\\s*(null|\\[([^]]*)\\])")
                .find(json)
                ?: return null
        if (value.groupValues[1] == "null") return null
        val body = value.groupValues[2].trim()
        if (body.isEmpty()) return IntArray(0)
        return body.split(',').map { it.trim().toInt() }.toIntArray()
    }

    private fun intArray(values: IntArray?): String =
        values?.joinToString(prefix = "[", postfix = "]") ?: "null"

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unescape(value: String): String =
        value.replace("\\\"", "\"").replace("\\\\", "\\")
}
