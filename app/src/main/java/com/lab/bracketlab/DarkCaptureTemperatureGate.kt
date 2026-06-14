package com.lab.bracketlab

enum class DarkCaptureTemperatureStatus {
    WAITING,
    READY,
    UNAVAILABLE
}

data class DarkCaptureTemperatureDecision(
    val status: DarkCaptureTemperatureStatus,
    val initialCelsius: Float?,
    val currentCelsius: Float?,
    val readyLimitCelsius: Float?
)

object DarkCaptureTemperatureGate {
    const val DEFAULT_TOLERANCE_CELSIUS = 0.2f

    fun evaluate(
        initialCelsius: Float?,
        currentCelsius: Float?,
        toleranceCelsius: Float = DEFAULT_TOLERANCE_CELSIUS
    ): DarkCaptureTemperatureDecision {
        require(toleranceCelsius >= 0f && toleranceCelsius.isFinite())
        if (
            initialCelsius == null ||
            currentCelsius == null ||
            !initialCelsius.isFinite() ||
            !currentCelsius.isFinite()
        ) {
            return DarkCaptureTemperatureDecision(
                status = DarkCaptureTemperatureStatus.UNAVAILABLE,
                initialCelsius = initialCelsius,
                currentCelsius = currentCelsius,
                readyLimitCelsius = initialCelsius?.plus(toleranceCelsius)
            )
        }
        val limit = initialCelsius + toleranceCelsius
        return DarkCaptureTemperatureDecision(
            status =
                if (currentCelsius <= limit) {
                    DarkCaptureTemperatureStatus.READY
                } else {
                    DarkCaptureTemperatureStatus.WAITING
                },
            initialCelsius = initialCelsius,
            currentCelsius = currentCelsius,
            readyLimitCelsius = limit
        )
    }
}
