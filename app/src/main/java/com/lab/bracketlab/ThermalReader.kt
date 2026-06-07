package com.lab.bracketlab

import java.io.File
import java.util.Locale
import kotlin.math.abs

class ThermalReader(
    private val thermalRoot: File = File("/sys/class/thermal")
) {
    data class Reading(
        val skinCelsius: Float?,
        val cameraUsrCelsius: Float?
    )

    /** Reads the skin and camera thermal zones exposed by the device. */
    fun read(): Reading {
        var skin: Float? = null
        var skinFallback: Float? = null
        var cameraUsr: Float? = null

        for (zone in thermalZones()) {
            val type = readText(zone.resolve("type"))
                ?.trim()
                ?.lowercase(Locale.US)
                ?: continue
            val temperature = readTemperature(zone.resolve("temp")) ?: continue

            when (type) {
                "skin" -> if (skin == null) skin = temperature
                "modem-skin-usr" -> if (skinFallback == null) skinFallback = temperature
                "camera-usr" -> if (cameraUsr == null) cameraUsr = temperature
            }

            if (skin != null && cameraUsr != null) break
        }

        return Reading(
            skinCelsius = skin ?: skinFallback,
            cameraUsrCelsius = cameraUsr
        )
    }

    /** Lists Linux thermal-zone folders without failing on restricted devices. */
    private fun thermalZones(): List<File> =
        runCatching {
            thermalRoot.listFiles { file ->
                file.name.startsWith("thermal_zone") && file.isDirectory
            }?.toList().orEmpty()
        }.getOrDefault(emptyList())

    /** Reads a thermal value and normalizes millidegrees to Celsius when needed. */
    private fun readTemperature(file: File): Float? {
        val raw = readText(file)?.trim()?.toFloatOrNull() ?: return null
        val celsius = if (abs(raw) > 200f) raw / 1000f else raw
        return celsius.takeIf { it in -60f..150f }
    }

    /** Reads a sysfs text file safely, returning null when access is blocked. */
    private fun readText(file: File): String? =
        runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
}
