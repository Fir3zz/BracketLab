package com.lab.bracketlab.processing.calibration

import com.lab.bracketlab.processing.model.RawFrame
import kotlin.math.abs

enum class MasterDarkRejectionReason {
    DIMENSIONS,
    CFA,
    CAMERA,
    ISO,
    EXPOSURE,
    BLACK_LEVEL,
    WHITE_LEVEL,
    INVALID_STORAGE
}

data class MasterDarkCandidateDiagnostic(
    val masterDarkId: String,
    val compatible: Boolean,
    val rejectionReasons: List<MasterDarkRejectionReason>,
    val message: String
)

data class MasterDarkMatchResult(
    val matched: Boolean,
    val selected: MasterDark? = null,
    val matchScore: Long = Long.MIN_VALUE,
    val warnings: List<String> = emptyList(),
    val candidateDiagnostics: List<MasterDarkCandidateDiagnostic> = emptyList(),
    val failureCode: DarkCalibrationFailureCode? = null,
    val failureMessage: String? = null
)

object MasterDarkMatcher {
    fun findCompatibleMasterDark(
        lightFrame: RawFrame,
        availableMasterDarks: List<MasterDark>,
        options: DarkCalibrationOptions = DarkCalibrationOptions(
            darkPolicy = DarkPolicy.USE_COMPATIBLE_MASTER_DARK
        )
    ): MasterDarkMatchResult {
        val candidates = availableMasterDarks.map { masterDark ->
            val reasons = rejectionReasons(lightFrame, masterDark, options)
            val exposureDifference =
                abs(lightFrame.exposureTimeNs - masterDark.metadata.exposureTimeNs)
            val score =
                if (reasons.isEmpty()) {
                    score(masterDark, exposureDifference)
                } else {
                    Long.MIN_VALUE
                }
            Candidate(masterDark, reasons, exposureDifference, score)
        }
        val selected =
            candidates
                .filter { it.reasons.isEmpty() }
                .sortedWith(
                    compareBy<Candidate> { it.exposureDifferenceNs }
                        .thenByDescending { it.masterDark.metadata.iso == lightFrame.iso }
                        .thenByDescending { it.masterDark.metadata.cameraId == lightFrame.cameraId }
                        .thenByDescending { it.masterDark.metadata.frameCount }
                        .thenByDescending { it.masterDark.metadata.createdAtMillis }
                )
                .firstOrNull()
        val diagnostics =
            if (options.candidateDiagnosticsEnabled) {
                candidates.map {
                    MasterDarkCandidateDiagnostic(
                        masterDarkId = it.masterDark.metadata.id,
                        compatible = it.reasons.isEmpty(),
                        rejectionReasons = it.reasons,
                        message =
                            if (it.reasons.isEmpty()) {
                                "Compatible MasterDark."
                            } else {
                                "Rejected: ${it.reasons.joinToString()}."
                            }
                    )
                }
            } else {
                emptyList()
            }
        if (selected == null) {
            val failureMessage =
                if (candidates.isEmpty()) {
                    "No stored MasterDark could be loaded."
                } else {
                    val details = candidates.joinToString(separator = "; ") {
                        "${it.masterDark.metadata.id}: ${it.reasons.joinToString()}"
                    }
                    "No compatible MasterDark was found. $details"
                }
            return MasterDarkMatchResult(
                matched = false,
                candidateDiagnostics = diagnostics,
                failureCode = DarkCalibrationFailureCode.MASTER_DARK_NOT_FOUND,
                failureMessage = failureMessage
            )
        }
        return MasterDarkMatchResult(
            matched = true,
            selected = selected.masterDark,
            matchScore = selected.score,
            candidateDiagnostics = diagnostics
        )
    }

    fun rejectionReasons(
        lightFrame: RawFrame,
        masterDark: MasterDark,
        options: DarkCalibrationOptions
    ): List<MasterDarkRejectionReason> {
        val metadata = masterDark.metadata
        val reasons = mutableListOf<MasterDarkRejectionReason>()
        if (lightFrame.width != metadata.width || lightFrame.height != metadata.height) {
            reasons += MasterDarkRejectionReason.DIMENSIONS
        }
        if (lightFrame.cfaPattern == null || lightFrame.cfaPattern != metadata.cfaPattern) {
            reasons += MasterDarkRejectionReason.CFA
        }
        if (
            options.requireSameCameraIdWhenKnown &&
            lightFrame.cameraId != null &&
            metadata.cameraId != null &&
            lightFrame.cameraId != metadata.cameraId
        ) {
            reasons += MasterDarkRejectionReason.CAMERA
        }
        if (
            options.requireSameIsoWhenKnown &&
            lightFrame.iso > 0 &&
            metadata.iso > 0 &&
            lightFrame.iso != metadata.iso
        ) {
            reasons += MasterDarkRejectionReason.ISO
        }
        if (!exposureMatches(lightFrame.exposureTimeNs, metadata.exposureTimeNs, options)) {
            reasons += MasterDarkRejectionReason.EXPOSURE
        }
        if (
            options.requireCompatibleBlackLevel &&
            !lightFrame.blackLevelPattern.contentEqualsOrBothNull(metadata.blackLevelPattern)
        ) {
            reasons += MasterDarkRejectionReason.BLACK_LEVEL
        }
        if (
            options.requireCompatibleWhiteLevel &&
            lightFrame.whiteLevel != null &&
            metadata.whiteLevel != null &&
            lightFrame.whiteLevel != metadata.whiteLevel
        ) {
            reasons += MasterDarkRejectionReason.WHITE_LEVEL
        }
        if (
            !masterDark.rawFile.isFile ||
            masterDark.rawFile.length() != metadata.width.toLong() * metadata.height.toLong() * 2L
        ) {
            reasons += MasterDarkRejectionReason.INVALID_STORAGE
        }
        return reasons
    }

    private fun exposureMatches(
        lightNs: Long,
        darkNs: Long,
        options: DarkCalibrationOptions
    ): Boolean {
        if (lightNs <= 0L || darkNs <= 0L) return false
        val difference = abs(lightNs - darkNs)
        return difference <= options.exposureAbsoluteToleranceNs ||
            difference.toDouble() / lightNs.toDouble() <= options.exposureRelativeTolerance
    }

    private fun score(masterDark: MasterDark, exposureDifference: Long): Long {
        val exposureComponent = (Long.MAX_VALUE / 4L - exposureDifference).coerceAtLeast(0L)
        return exposureComponent +
            masterDark.metadata.frameCount.toLong() * 1_000_000L +
            masterDark.metadata.createdAtMillis.coerceAtLeast(0L) / 1_000L
    }

    private fun IntArray?.contentEqualsOrBothNull(other: IntArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> contentEquals(other)
        }

    private data class Candidate(
        val masterDark: MasterDark,
        val reasons: List<MasterDarkRejectionReason>,
        val exposureDifferenceNs: Long,
        val score: Long
    )
}
