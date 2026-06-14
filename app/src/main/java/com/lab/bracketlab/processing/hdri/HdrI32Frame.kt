package com.lab.bracketlab.processing.hdri

import com.lab.bracketlab.processing.raw.CfaPattern
import java.io.File
import java.nio.ByteOrder

data class HdrI32Frame(
    val metadata: HdrI32Metadata,
    val storageFile: File,
    val metadataFile: File? = null
) {
    val width: Int
        get() = metadata.width
    val height: Int
        get() = metadata.height
    val rowStrideBytes: Int
        get() = width * Float.SIZE_BYTES
    val pixelStrideBytes: Int
        get() = Float.SIZE_BYTES
    val byteOrder: ByteOrder
        get() = ByteOrder.LITTLE_ENDIAN
    val cfaPattern: CfaPattern
        get() = metadata.cfaPattern
}
