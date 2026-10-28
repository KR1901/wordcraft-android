package com.wordcraft.android.keyboard

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * An `InputMethodService` is not a `LifecycleOwner`, and a `ComposeView` refuses to start its
 * recomposer without one:
 *
 *   IllegalStateException: ViewTreeLifecycleOwner not found from android.widget.LinearLayout
 *     at WindowRecomposer_androidKt.createLifecycleAwareWindowRecomposer
 *
 * So the IME owns a lifecycle and publishes it into the view tree before the composition starts.
 *
 * The `ViewTreeLifecycleOwner` / `ViewTreeViewModelStoreOwner` accessors are bound reflectively:
 * they are not visible to Kotlin from this module in the lifecycle version this project resolves
 * (the import fails to compile), but they are public at the JVM level and stable across versions.
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
        bind("androidx.lifecycle.ViewTreeLifecycleOwner", view, LifecycleOwner::class.java, this)
        bind("androidx.lifecycle.ViewTreeViewModelStoreOwner", view, ViewModelStoreOwner::class.java, this)
    }

    private fun bind(className: String, view: View, ownerType: Class<*>, owner: Any) {
        runCatching {
            val type = Class.forName(className)
            val method = type.getMethod("set", View::class.java, ownerType)
            // Kotlin object -> INSTANCE; a Java-style holder -> null for the static call.
            val receiver = runCatching { type.getField("INSTANCE").get(null) }
                .getOrElse { runCatching { type.getDeclaredConstructor().newInstance() }.getOrNull() }
            method.invoke(receiver, view, owner)
        }
    }
}
