package com.lab.bracketlab.processing.calibration

data class DarkCalibrationInput(
    val policy: DarkPolicy = DarkPolicy.OFF,
    val masterDark: MasterDark? = null,
    val options: DarkCalibrationOptions = DarkCalibrationOptions()
) {
    companion object {
        val OFF = DarkCalibrationInput()

        fun use(
            masterDark: MasterDark?,
            options: DarkCalibrationOptions = DarkCalibrationOptions(
                darkPolicy = DarkPolicy.USE_COMPATIBLE_MASTER_DARK
            )
        ): DarkCalibrationInput =
            DarkCalibrationInput(
                policy = DarkPolicy.USE_COMPATIBLE_MASTER_DARK,
                masterDark = masterDark,
                options = options.copy(darkPolicy = DarkPolicy.USE_COMPATIBLE_MASTER_DARK)
            )
    }
}
