package com.lab.bracketlab.processing.debug

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

class IncrementalDiagnosticReport(
    val file: File
) : Closeable {
    private val writer: BufferedWriter

    init {
        file.parentFile?.mkdirs()
        writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(file, false), Charsets.UTF_8)
        )
    }

    @Synchronized
    fun append(line: String = "") {
        writer.write(line)
        writer.newLine()
        writer.flush()
    }

    @Synchronized
    fun appendLines(lines: Iterable<String>) {
        lines.forEach {
            writer.write(it)
            writer.newLine()
        }
        writer.flush()
    }

    override fun close() {
        writer.close()
    }
}
