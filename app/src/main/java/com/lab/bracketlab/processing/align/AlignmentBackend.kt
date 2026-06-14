package com.lab.bracketlab.processing.align

import com.lab.bracketlab.processing.model.AlignmentResult
import com.lab.bracketlab.processing.raw.RawProxy

/**
 * Backend contract for estimating transforms.
 *
 * Transform convention:
 * Returned transforms map TARGET/SOURCE frame coordinates into REFERENCE frame coordinates:
 *
 * referenceCoordinate = transform(targetCoordinate)
 *
 * OpenCV-specific objects such as Mat must stay behind this interface.
 * Callers should invoke this backend from a worker thread, not the UI thread.
 */
interface AlignmentBackend {
    fun estimateTranslation(reference: RawProxy, target: RawProxy): AlignmentResult

    fun isAvailable(): Boolean = true
}
