package com.wordcraft.android.keyboard

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner

/**
 * An `InputMethodService` is not a `LifecycleOwner`, and a `ComposeView` refuses to start its
 * recomposer without one:
 *
 *   IllegalStateException: ViewTreeLifecycleOwner not found from android.widget.LinearLayout
 *     at WindowRecomposer_androidKt.createLifecycleAwareWindowRecomposer
 *
 * So the IME owns a lifecycle and publishes it into the view tree before the composition starts.
 * The registry is driven by the service's own create/destroy, which is all Compose needs here.
 */
class KeyboardLifecycleOwner : LifecycleOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    fun start() {
        registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.STARTED
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun pause() {
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.currentState = Lifecycle.State.STARTED
        }
    }

    fun resume() {
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }

    fun attachTo(view: View) {
        ViewTreeLifecycleOwner.set(view, this)
        ViewTreeViewModelStoreOwner.set(view, this)
    }
}
