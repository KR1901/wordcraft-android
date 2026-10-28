package com.wordcraft.android.keyboard

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * An `InputMethodService` is not a lifecycle/saved-state/ViewModel owner, and `ComposeView` needs
 * all three to start a composition. Without them it throws, in this order:
 *
 *   IllegalStateException: ViewTreeLifecycleOwner not found from android.widget.LinearLayout
 *   IllegalStateException: Composed into the View which doesn't propagate ViewTreeSavedStateRegistryOwner!
 *
 * Two details cost two releases each:
 *
 * 1. Compose looks the owners up **from the window's root view and walks upwards**, so they must
 *    sit on an ancestor of the ComposeView — the IME window's decor view — not only on the
 *    ComposeView itself.
 * 2. The `ViewTree*` accessors are file-level Kotlin functions with `@JvmName("set")`, and they are
 *    not visible to Kotlin from this module on the lifecycle version this project resolves. They
 *    are bound reflectively, with the raw view tag (which is literally what the accessor reads) as
 *    a fallback.
 */
class KeyboardLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performRestore(null)
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

    /** Publishes all three owners into the view tree rooted at [view]. */
    fun attachTo(view: View) {
        bindOwner(view, "androidx.lifecycle.ViewTreeLifecycleOwner", "view_tree_lifecycle_owner",
            LifecycleOwner::class.java, this)
        bindOwner(view, "androidx.lifecycle.ViewTreeViewModelStoreOwner", "view_tree_view_model_store_owner",
            ViewModelStoreOwner::class.java, this)
        bindOwner(view, "androidx.savedstate.ViewTreeSavedStateRegistryOwner", "view_tree_saved_state_registry_owner",
            SavedStateRegistryOwner::class.java, this)
    }

    /**
     * Whether Compose would find the owners it insists on, checked before `setContent`. The throw
     * happens inside the framework's `setInputView`, where it cannot be caught, so the caller uses
     * this to fall back instead of crashing.
     */
    fun isVisibleFrom(root: View): Boolean =
        accessorVisible(root, "androidx.lifecycle.ViewTreeLifecycleOwner") &&
            accessorVisible(root, "androidx.savedstate.ViewTreeSavedStateRegistryOwner")

    private fun accessorVisible(root: View, className: String): Boolean = runCatching {
        val type = Class.forName(className)
        type.getMethod("get", View::class.java).invoke(null, root) != null
    }.getOrDefault(true)

    private fun bindOwner(view: View, className: String, tagName: String, ownerType: Class<*>, owner: Any) {
        // Preferred: call the public JVM method the accessor is compiled to.
        val viaMethod = runCatching {
            val type = Class.forName(className)
            val method = type.getMethod("set", View::class.java, ownerType)
            val receiver = runCatching { type.getField("INSTANCE").get(null) }.getOrNull()
            method.invoke(receiver, view, owner)
            true
        }.getOrDefault(false)
        if (viaMethod) return

        // Fallback: write the tag the accessor reads. Library resource ids are resolved by name.
        runCatching {
            val resources = view.resources
            val id = resources.getIdentifier(tagName, "id", view.context.packageName)
            val resolved = if (id != 0) id else resources.getIdentifier(tagName, "id", "androidx.lifecycle.runtime")
            if (resolved != 0) view.setTag(resolved, owner)
        }
    }
}
