package com.millennium.app.features.ui.glass

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Isolated Compose owners avoid mixing the module's AndroidX classes with Steam's. */
internal class SteamGlassHostView(context: Context) : FrameLayout(context), AutoCloseable {
    private val owner = Owner()
    private var scope: CoroutineScope? = null
    private var recomposer: Recomposer? = null
    private val compose = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
    }

    init {
        clipChildren = false
        clipToPadding = false
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        addView(compose, LayoutParams(-1, -1))
    }

    fun content(block: @Composable () -> Unit) = compose.setContent(block)

    override fun onAttachedToWindow() {
        val context = AndroidUiDispatcher.CurrentThread
        val next = Recomposer(context)
        recomposer = next
        scope = CoroutineScope(context + SupervisorJob()).also { it.launch { next.runRecomposeAndApplyChanges() } }
        compose.setParentCompositionContext(next)
        owner.registry.currentState = Lifecycle.State.RESUMED
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    override fun close() {
        compose.disposeComposition()
        recomposer?.cancel()
        scope?.cancel()
        recomposer = null
        scope = null
        owner.registry.currentState = Lifecycle.State.DESTROYED
    }

    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        init { controller.performAttach(); controller.performRestore(null); registry.currentState = Lifecycle.State.CREATED }
    }
}
