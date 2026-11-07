package com.bxane.nuvx

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal fun observeCanvasVisibility(lifecycle: Lifecycle, onVisible: (Boolean) -> Unit): AutoCloseable {
    val observer = LifecycleEventObserver { _, _ -> onVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    lifecycle.addObserver(observer)
    onVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    return AutoCloseable {
        lifecycle.removeObserver(observer)
        onVisible(false)
    }
}
