package com.millennium.app.features.ui.glass

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RenderNode
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ScrollView
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import androidx.core.view.isInvisible
import java.util.IdentityHashMap
import kotlin.math.abs

/** Steam owns these views and all React events; none are moved to another parent. */
@RequiresApi(33)
internal class SteamBottomBarHost(
    val source: ViewGroup,
    val tabs: List<Tab>,
    val page: View,
    private val backgrounds: List<View>,
) : AutoCloseable {
    val bounds = Rect()
    private val visibility = IdentityHashMap<View, Int>()
    private val accessibility = IdentityHashMap<View, Int>()
    private val geometry = IdentityHashMap<View, Geometry>()
    private val padding = IdentityHashMap<ViewGroup, Padding>()
    private val webInsets = IdentityHashMap<WebView, Int>()

    class Tab(val view: ViewGroup) {
        private val node by lazy { RenderNode("Millennium Steam tab") }
        private var width = 0
        private var height = 0

        fun selected(): Boolean = view.isSelected || view.children().any {
            it.isVisible && it.javaClass.name == GRADIENT &&
                    it.top <= view.height / 10 && it.height in 1..(view.height / 8).coerceAtLeast(1)
        }

        fun record() {
            width = view.width
            height = view.height
            if (width <= 0 || height <= 0) return
            node.setPosition(0, 0, width, height)
            val canvas = node.beginRecording()
            try {
                // Keep Steam's live icon and badge, but replace its thin selected marker.
                canvas.clipRect(0, (view.resources.displayMetrics.density * 4).toInt(), width, height)
                view.draw(canvas)
            } finally { node.endRecording() }
        }

        fun draw(canvas: Canvas, targetWidth: Float, targetHeight: Float) {
            if (width == 0 || height == 0 || !node.hasDisplayList()) return
            val scale = minOf(targetWidth / width, targetHeight / height)
            val save = canvas.save()
            try {
                canvas.translate((targetWidth - width * scale) / 2, (targetHeight - height * scale) / 2)
                canvas.scale(scale, scale)
                canvas.drawRenderNode(node)
            } finally { canvas.restoreToCount(save) }
        }

        fun close() = node.discardDisplayList()
    }

    fun matches(other: SteamBottomBarHost): Boolean = source === other.source && page === other.page &&
            tabs.map { it.view } == other.tabs.map { it.view } && backgrounds == other.backgrounds

    fun selectedIndex(): Int = tabs.indexOfFirst { it.selected() }

    fun updateBounds(): Boolean {
        val first = Rect()
        val last = Rect()
        if (!tabs.first().view.getGlobalVisibleRect(first) || !tabs.last().view.getGlobalVisibleRect(last)) return false
        bounds.set(first.left, first.top, last.right, last.bottom)
        return bounds.width() > 0 && bounds.height() > 0
    }

    fun hideNative() {
        (tabs.map { it.view } + backgrounds).forEach { view ->
            val prior = visibility[view]
            if (prior == null || view.visibility != View.INVISIBLE) visibility[view] = view.visibility
            if (!accessibility.containsKey(view)) accessibility[view] = view.importantForAccessibility
            view.visibility = View.INVISIBLE
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    fun isOwnedHidden(view: View): Boolean = visibility.containsKey(view) && view.isInvisible

    fun select(index: Int) {
        val tab = tabs.getOrNull(index)?.view ?: return
        if (!tab.isAttachedToWindow || !tab.isEnabled) return
        val point = IntArray(2)
        val origin = IntArray(2)
        tab.getLocationOnScreen(point)
        source.getLocationOnScreen(origin)
        val x = point[0] - origin[0] + tab.width / 2f
        val y = point[1] - origin[1] + tab.height / 2f
        val time = SystemClock.uptimeMillis()
        val previous = tab.visibility
        // React Native dispatches onPress at its root. View.performClick() misses that path.
        tab.visibility = View.VISIBLE
        try {
            for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
                try { source.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        } finally { tab.visibility = previous }
    }

    /** Extend only the bottom-anchored viewport chain; scroll children keep their native size. */
    fun underlap(bottom: Int, reserved: Int) {
        val parent = page.parent as? View ?: return
        val location = IntArray(2)
        parent.getLocationOnScreen(location)
        // Tabs and ScreenContainer are siblings. Their boundary remains native even
        // when Screens remeasures a newly mounted fragment to our expanded height.
        val native = Rect(page.left, page.top, page.right, tabs.first().view.top)
        val targetBottom = bottom - location[1] + parent.scrollY
        if (native.isEmpty || targetBottom <= native.bottom) return
        val changes = LinkedHashMap<View, Geometry>()
        collectExtension(page, native, targetBottom, changes)
        changes.forEach { (view, bounds) ->
            geometry[view] = bounds
            val applied = bounds.applied
            if (Rect(view.left, view.top, view.right, view.bottom) != applied) {
                view.layout(applied.left, applied.top, applied.right, applied.bottom)
            }
            when (view) {
                is WebView -> {
                    if (webInsets.put(view, reserved) != reserved) applyWebInset(view, reserved)
                }
                is ViewGroup -> {
                    if (view.javaClass.name.contains("ScrollView")) {
                        val original = padding.getOrPut(view) { Padding(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom, view.clipToPadding) }
                        view.clipToPadding = false
                        view.setPadding(original.left, original.top, original.right, original.bottom + reserved)
                    }
                }
            }
        }
        restoreGeometry(geometry.keys.filter { it !in changes })
    }

    private fun collectExtension(view: View, native: Rect, bottom: Int, changes: MutableMap<View, Geometry>) {
        changes[view] = Geometry(native, Rect(native).apply { this.bottom = bottom })
        if (view !is ViewGroup || view is WebView || view is ScrollView ||
            view.javaClass.name.contains("ScrollView")) return
        val targetHeight = bottom - native.top
        // A fragment wrapper can already fill the expanded parent while its Yoga
        // children still use the native height. Accept either edge at every level;
        // set an absolute target instead of adding the bar height a second time.
        val edges = intArrayOf(native.height(), view.height, targetHeight)
        for (child in view.children()) {
            if (!child.isVisible || child.height <= 0) continue
            val gap = when {
                edges.any { abs(child.bottom - it) <= 2 } -> 0
                view.paddingBottom > 0 && edges.any { abs(child.bottom - (it - view.paddingBottom)) <= 2 } -> view.paddingBottom
                else -> continue
            }
            val childNative = Rect(child.left, child.top, child.right, native.height() - gap)
            if (!childNative.isEmpty) collectExtension(child, childNative, targetHeight - gap, changes)
        }
    }

    // Called by the existing UI poll, so a new document in the same WebView also
    // receives its inset. No WebViewClient replacement or additional timer is needed.
    fun refreshWebInsets() = webInsets.forEach { (view, height) -> applyWebInset(view, height) }

    private fun applyWebInset(view: WebView, height: Int) {
        if (!view.isAttachedToWindow || !view.settings.javaScriptEnabled) return
        view.evaluateJavascript("""
            (() => {
                if (!document.body) return;
                let spacer = document.getElementById('$WEB_INSET_ID');
                if (!spacer) {
                    spacer = document.createElement('div');
                    spacer.id = '$WEB_INSET_ID';
                    spacer.setAttribute('aria-hidden', 'true');
                    document.body.appendChild(spacer);
                }
                const height = ($height * innerWidth / ${view.width.coerceAtLeast(1)}) + 'px';
                if (spacer.style.height !== height) {
                    spacer.style.cssText = 'all:initial;display:block!important;clear:both;flex:none;pointer-events:none;width:1px;min-height:0;';
                    spacer.style.setProperty('height', height, 'important');
                }
            })();
        """.trimIndent(), null)
    }

    private fun removeWebInset(view: WebView) {
        if (view.settings.javaScriptEnabled) {
            view.evaluateJavascript("document.getElementById('$WEB_INSET_ID')?.remove();", null)
        }
    }

    private fun restoreGeometry(views: List<View>) {
        // Parent restoration can synchronously relayout children too. Decide which
        // bounds we own before restoring any parent, then restore top to bottom.
        val owned = views.mapNotNull { view ->
            geometry.remove(view)?.takeIf {
                Rect(view.left, view.top, view.right, view.bottom) == it.applied
            }?.let { view to it.native }
        }
        owned.sortedBy { depth(it.first) }.forEach { (view, b) -> view.layout(b.left, b.top, b.right, b.bottom) }
        views.forEach { view ->
            if (view is ViewGroup) padding.remove(view)?.restore(view)
            if (view is WebView && webInsets.remove(view) != null) removeWebInset(view)
        }
    }

    override fun close() {
        visibility.forEach { (view, value) -> if (view.isInvisible) view.visibility = value }
        accessibility.forEach { (view, value) -> view.importantForAccessibility = value }
        restoreGeometry(geometry.keys.toList())
        tabs.forEach(Tab::close)
        visibility.clear(); accessibility.clear(); geometry.clear(); padding.clear(); webInsets.clear()
    }

    private data class Geometry(val native: Rect, val applied: Rect)
    private data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int, val clip: Boolean) {
        fun restore(view: ViewGroup) { view.setPadding(left, top, right, bottom); view.clipToPadding = clip }
    }

    companion object {
        private const val SVG = "com.horcrux.svg.SvgView"
        private const val GRADIENT = "expo.modules.lineargradient.LinearGradientView"
        private const val WEB_INSET_ID = "millennium-steam-glass-inset"

        fun find(decor: ViewGroup, previous: SteamBottomBarHost?): SteamBottomBarHost? {
            val window = Rect()
            if (!decor.getGlobalVisibleRect(window)) return null
            var visited = 0
            fun scan(view: View, depth: Int): SteamBottomBarHost? {
                if (++visited > 800 || depth > 25 || view !is ViewGroup || view.alpha <= 0f ||
                    (view.visibility != View.VISIBLE && previous?.isOwnedHidden(view) != true)) return null
                val children = view.children()
                val candidates = children.mapNotNull { child ->
                    if (child !is ViewGroup || !child.isClickable || child.height <= 0 || child.alpha <= 0f ||
                        (child.visibility != View.VISIBLE && previous?.isOwnedHidden(child) != true)) return@mapNotNull null
                    findIcon(child, 0) ?: return@mapNotNull null
                    Tab(child)
                }.sortedBy { it.view.left }
                if (candidates.size in 3..8) {
                    val first = candidates.first().view
                    val last = candidates.last().view
                    val rect = Rect()
                    first.getGlobalVisibleRect(rect)
                    val aligned = candidates.all { abs(it.view.top - first.top) <= 2 && abs(it.view.bottom - first.bottom) <= 2 }
                    if (aligned && rect.top > window.top + window.height() * .65f &&
                        last.right - first.left >= window.width() * .75f) {
                        val page = children.firstOrNull {
                            it.javaClass.name == "com.swmansion.rnscreens.ScreenContainer" &&
                                    it.width >= view.width * .75f && it.top < first.top &&
                                    (abs(it.bottom - first.top) <= 2 || it === previous?.page)
                        }
                        var root: View? = view
                        while (root != null && root.javaClass.name != "com.facebook.react.runtime.ReactSurfaceView") root = root.parent as? View
                        if (page != null && root is ViewGroup) {
                            val backgrounds = children.filter { it !in candidates.map(Tab::view) &&
                                    !it.isClickable && it.top >= first.top - 2 && it.width >= view.width * .9f }
                            return SteamBottomBarHost(root, candidates, page, backgrounds)
                        }
                    }
                }
                for (child in children) scan(child, depth + 1)?.let { return it }
                return null
            }
            return scan(decor, 0)
        }

        private fun findIcon(view: View, depth: Int): View? {
            if (view.javaClass.name == SVG) return view
            if (depth >= 5 || view !is ViewGroup) return null
            for (child in view.children()) findIcon(child, depth + 1)?.let { return it }
            return null
        }

        private fun ViewGroup.children(): List<View> = List(childCount, ::getChildAt)
        private fun depth(view: View): Int = generateSequence(view.parent) { it.parent }.count()
    }
}
