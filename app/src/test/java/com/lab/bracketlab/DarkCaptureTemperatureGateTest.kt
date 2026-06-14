package com.lab.bracketlab

import org.junit.Assert.assertEquals
import org.junit.Test

class DarkCaptureTemperatureGateTest {
    @Test
    fun waitsWhileCameraIsWarmerThanLightStart() {
        val decision = DarkCaptureTemperatureGate.evaluate(31.0f, 32.0f)

        assertEquals(DarkCaptureTemperatureStatus.WAITING, decision.status)
    }

    @Test
    fun becomesReadyAtInitialTemperatureTolerance() {
        val decision = DarkCaptureTemperatureGate.evaluate(31.0f, 31.2f)

        assertEquals(DarkCaptureTemperatureStatus.READY, decision.status)
    }

    @Test
    fun lowerTemperatureIsReady() {
        val decision = DarkCaptureTemperatureGate.evaluate(31.0f, 30.5f)

        assertEquals(DarkCaptureTemperatureStatus.READY, decision.status)
    }

    @Test
    fun missingSensorIsUnavailable() {
        val decision = DarkCaptureTemperatureGate.evaluate(null, 31.0f)

        assertEquals(DarkCaptureTemperatureStatus.UNAVAILABLE, decision.status)
    }
}
