package com.lab.bracketlab.processing.debug

import java.util.concurrent.atomic.AtomicBoolean

class SingleFlightGuard {
    private val active = AtomicBoolean(false)

    fun tryAcquire(): Boolean = active.compareAndSet(false, true)

    fun release() {
        active.set(false)
    }

    fun isActive(): Boolean = active.get()
}
