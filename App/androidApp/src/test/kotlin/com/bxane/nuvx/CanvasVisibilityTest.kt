package com.bxane.nuvx

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasVisibilityTest {
    @Test fun backgroundPausesAndReturningResumesUntilDisposed() {
        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner { override val lifecycle get() = registry }
        registry = LifecycleRegistry.createUnsafe(owner)
        val observed = mutableListOf<Boolean>()
        val subscription = observeCanvasVisibility(registry) { observed += it }
        assertEquals(false, observed.last())
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(true, observed.last())
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(false, observed.last())
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(true, observed.last())
        subscription.close()
        assertEquals(false, observed.last())
        val count = observed.size
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(count, observed.size)
    }
}
