package com.millennium.app.features.ui.glass

import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.millennium.app.R
import com.millennium.app.core.ModuleLocalization
import com.millennium.app.features.ui.settings.LiquidBottomTab
import com.millennium.app.features.ui.settings.LiquidBottomTabs
import com.millennium.app.features.ui.settings.SteamModuleSettings
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Discovers Steam's current tab order instead of storing icons, route names or React tags. */
internal class SteamBottomBarGlassFeature(private val emit: (Int, String) -> Unit) {
    private val states = IdentityHashMap<Activity, State>()

    fun update(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33 || !SteamModuleSettings.isSteamBottomGlassEnabled(activity)) {
            detach(activity)
            return
        }
        val decor = activity.window.decorView as? ViewGroup ?: return
        if (!decor.hasWindowFocus() || activity.isFinishing || activity.isDestroyed) return
        states[activity]?.takeIf { it.decor === decor }?.let {
            it.refreshWebInsets()
            return
        }
        detach(activity)
        val state = State(activity, decor)
        states[activity] = state
        decor.viewTreeObserver.addOnPreDrawListener(state)
        decor.invalidate()
    }

    fun detach(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33) states.remove(activity)?.close()
    }

    @RequiresApi(33)
    private inner class State(val activity: Activity, val decor: ViewGroup) : ViewTreeObserver.OnPreDrawListener {
        private var session: Session? = null
        private var failed = false
        fun refreshWebInsets() { session?.host?.refreshWebInsets() }
        override fun onPreDraw(): Boolean {
            if (failed || !decor.hasWindowFocus()) return true
            try {
                if (ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                    session?.close(); session = null
                    return true
                }
                val found = SteamBottomBarHost.find(decor, session?.host)
                if (found == null) {
                    session?.close(); session = null
                    return true
                }
                if (session?.host?.matches(found) != true) {
                    session?.close()
                    session = Session(activity, decor, found)
                    return false // Measure the new island before its first visible frame.
                }
                session?.frame()
            } catch (error: Exception) {
                failed = true
                decor.post {
                    session?.close(); session = null
                    emit(Log.WARN, "Steam bottom glass restored native bar: ${error.javaClass.simpleName}: ${error.message}")
                }
            }
            return true
        }

        fun close() {
            decor.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            session?.close(); session = null
        }
    }

    @RequiresApi(33)
    private class Session(
        val activity: Activity,
        val decor: ViewGroup,
        val host: SteamBottomBarHost,
    ) {
        val backdrop = SteamViewBackdrop(host.source)
        private val glass = SteamGlassHostView(moduleContext(activity))
        private var selected by mutableIntStateOf(host.selectedIndex().coerceAtLeast(0))
        private var drawing by mutableIntStateOf(0)
        private var selectionRevision by mutableIntStateOf(0)
        private var closed = false
        private var lastBounds = Rect()
        private val settle = Runnable { if (!closed) selectionRevision++ }

        init {
            glass.tag = ROOT_TAG
            glass.visibility = View.INVISIBLE
            glass.content { Content() }
            decor.addView(glass, FrameLayout.LayoutParams(1, 1))
            position()
        }

        fun frame() {
            if (closed || !host.updateBounds()) return
            val current = host.selectedIndex()
            if (current < 0) {
                // During navigation the host may briefly have no confirmed selection.
                return
            }
            val moved = position()
            if (!glass.isLaidOut || glass.width <= 1) return
            val dirty = host.source.isDirty || !backdrop.ready || moved || current != selected
            selected = current
            if (dirty) host.tabs.forEach { it.record() }
            host.hideNative()
            val window = Rect()
            decor.getGlobalVisibleRect(window)
            val reserved = decor.height - lastBounds.top
            host.underlap(window.bottom, reserved)
            if (dirty || host.source.isDirty || backdrop.needsCapture) {
                backdrop.record()
                drawing++
            }
            if (backdrop.ready) glass.visibility = View.VISIBLE
        }

        private fun position(): Boolean {
            if (!host.updateBounds()) return false
            val density = activity.resources.displayMetrics.density
            val inset = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            val margin = (16 * density).roundToInt()
            // The island includes 12dp of draw room for the pressed lens and its shadow.
            val height = (88 * density).roundToInt()
            val area = Rect(
                host.bounds.left - location[0] + margin - (12 * density).roundToInt(),
                decor.height - inset - (8 * density).roundToInt() - height + (12 * density).roundToInt(),
                host.bounds.right - location[0] - margin + (12 * density).roundToInt(),
                decor.height - inset - (8 * density).roundToInt() + (12 * density).roundToInt(),
            )
            if (area == lastBounds) return false
            lastBounds = area
            glass.layoutParams = FrameLayout.LayoutParams(area.width(), area.height()).apply {
                leftMargin = area.left; topMargin = area.top
            }
            return true
        }

        private fun select(index: Int) {
            if (closed) return
            host.select(index)
            // JS confirms asynchronously. Reconcile a rejected/blocked navigation as well.
            glass.removeCallbacks(settle)
            glass.postDelayed(settle, 600)
            decor.invalidate()
        }

        @Composable
        private fun Content() {
            val current = rememberUpdatedState(selected)
            val selection = remember { { current.value } }
            // Detection already sorts physical screen positions, including in RTL hosts.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(Modifier.fillMaxSize().padding(12.dp)) {
                    LiquidBottomTabs(
                        selectedTabIndex = selection,
                        onTabSelected = { if (it != current.value) select(it) },
                        backdrop = backdrop,
                        tabsCount = host.tabs.size,
                        darkTheme = true,
                        onSelectedTabClick = ::select,
                        selectionRevision = selectionRevision,
                    ) {
                        host.tabs.forEachIndexed { index, tab ->
                            LiquidBottomTab(
                                onClick = { select(index) },
                                modifier = Modifier.semantics {
                                    selected = current.value == index
                                    contentDescription = tab.view.contentDescription?.toString()
                                        ?: ModuleLocalization.text(activity, R.string.module_steam_glass_tab, index + 1)
                                },
                            ) {
                                Canvas(Modifier.fillMaxSize()) {
                                    // Observe the recorded frame in the draw phase so live icons and badges redraw.
                                    if (drawing != 0) {
                                        tab.draw(drawContext.canvas.nativeCanvas, size.width, size.height)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            glass.removeCallbacks(settle)
            glass.close()
            decor.removeView(glass)
            backdrop.close()
            host.close()
            decor.invalidate()
        }
    }

    private companion object {
        const val ROOT_TAG = "millennium.steam.bottom.glass"

        fun moduleContext(activity: Activity): Context {
            val resources = ModuleLocalization.resources(activity)
            return object : ContextThemeWrapper(activity, android.R.style.Theme_Material_NoActionBar) {
                override fun getResources(): Resources = resources
                override fun getAssets(): AssetManager = resources.assets
                override fun getClassLoader(): ClassLoader = SteamGlassHostView::class.java.classLoader!!
            }
        }
    }
}
