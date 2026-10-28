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
 * Two details matter and both were wrong at first:
 *
 * 1. The recomposer looks the owner up **from the window's root view and walks upwards**, so the
 *    owner has to sit on an ancestor of the ComposeView — in practice the IME window's decor view.
 *    Tagging only the ComposeView is invisible to that lookup.
 * 2. The `ViewTreeLifecycleOwner` accessors are file-level Kotlin functions with `@JvmName("set")`,
 *    and they are not visible to Kotlin from this module on the lifecycle version this project
 *    resolves. They are bound reflectively, with the raw view tag as a fallback, because the
 *    accessor is literally `view.setTag(R.id.view_tree_lifecycle_owner, owner)`.
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

    /** Publishes this owner into the view tree rooted at [view]. */
    fun attachTo(view: View) {
        bindOwner(view, "androidx.lifecycle.ViewTreeLifecycleOwner", "view_tree_lifecycle_owner",
            LifecycleOwner::class.java, this)
        bindOwner(view, "androidx.lifecycle.ViewTreeViewModelStoreOwner", "view_tree_view_model_store_owner",
            ViewModelStoreOwner::class.java, this)
    }

    /** Whether the accessor can already see an owner from [root] — used to avoid an uncatchable crash. */
    fun isVisibleFrom(root: View): Boolean = runCatching {
        val type = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
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
            val id = view.resources.getIdentifier(tagName, "id", view.context.packageName)
            val resolved = if (id != 0) id else view.resources.getIdentifier(tagName, "id", "androidx.lifecycle.runtime")
            if (resolved != 0) view.setTag(resolved, owner)
        }
    }
}
