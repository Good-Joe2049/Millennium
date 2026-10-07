package com.millennium.app.features.ui

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.view.isVisible
import java.util.IdentityHashMap
import kotlin.math.ceil

/** Owns native entry layout without adding foreign children to React Native's managed list. */
internal class SteamMainMenuSettingLayout(
    private val decor: ViewGroup,
    private val anchor: View,
    private val content: ViewGroup,
    private val scroll: ScrollView,
    private val entry: View,
    private val emit: (Int, String) -> Unit,
) {
    private val overlay = EntryOverlay(decor.context)
    private val shiftedViews = IdentityHashMap<View, Float>()
    private val anchorLocation = IntArray(2)
    private val overlayLocation = IntArray(2)
    private val scrollLocation = IntArray(2)
    private val viewport = Rect()
    private val clippedEntry = Rect()
    private var shift = 0
    private var originalHeight = content.height
    private var appliedHeight = 0

    val isAttached: Boolean
        get() = overlay.parent === decor && entry.parent === overlay &&
                anchor.parent === content && content.parent === scroll &&
                anchor.isAttachedToWindow && anchor.rootView === decor

    fun attach() {
        entry.visibility = View.INVISIBLE
        overlay.addView(entry, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        // Keep the existing floating panel and its scrim above this menu entry.
        val floatingIndex = (0 until decor.childCount).firstOrNull {
            decor.getChildAt(it).tag == "millennium.floating.panel.root"
        } ?: decor.childCount
        decor.addView(overlay, floatingIndex, ViewGroup.LayoutParams(-1, -1))
        update()
    }

    /** Called before drawing so scrolling and RN screen transitions cannot leave a stale overlay. */
    fun update() {
        if (!isAttached || !hasVisibleAncestors() || !scroll.getLocalVisibleRect(viewport)) {
            hide("menu-not-visible")
            return
        }
        val width = anchor.width
        val height = anchor.height
        if (width <= 0 || height <= 0 || overlay.width <= 0 || overlay.height <= 0) {
            hide("awaiting-layout")
            return
        }
        reserveSpace(height)
        anchor.getLocationInWindow(anchorLocation)
        overlay.getLocationInWindow(overlayLocation)
        scroll.getLocationInWindow(scrollLocation)
        val left = anchorLocation[0] - overlayLocation[0]
        val top = anchorLocation[1] - overlayLocation[1] - shift
        viewport.offset(
            scrollLocation[0] - overlayLocation[0] - scroll.scrollX,
            scrollLocation[1] - overlayLocation[1] - scroll.scrollY,
        )
        clippedEntry.set(left, top, left + width, top + height)
        if (!clippedEntry.intersect(viewport)) {
            hide("scrolled-out")
            return
        }
        overlay.touchBounds.set(clippedEntry)
        clippedEntry.offset(-left, -top)
        if (entry.clipBounds != clippedEntry) entry.clipBounds = clippedEntry
        val resized = entry.width != width || entry.height != height
        val params = entry.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            entry.layoutParams = params
        }
        if (resized || entry.isLayoutRequested) {
            entry.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            entry.layout(0, 0, width, height)
        }
        entry.x = left.toFloat()
        entry.y = top.toFloat()
        val shown = entry.visibility != View.VISIBLE
        if (shown) entry.visibility = View.VISIBLE
        if (resized || shown) emit(
            Log.INFO,
            "menu setting entry laid out actual=${entry.width}x${entry.height} " +
                    "left=$left top=$top overlay=${overlay.width}x${overlay.height} visible=true",
        )
    }

    private fun hasVisibleAncestors(): Boolean {
        var current: View? = anchor
        while (current != null) {
            if (current.visibility != View.VISIBLE || current.alpha <= 0f) return false
            current = current.parent as? View
        }
        return true
    }

    private fun reserveSpace(height: Int) {
        val index = content.indexOfChild(anchor)
        if (index < 0) return
        val oldCount = shiftedViews.size
        val iterator = shiftedViews.entries.iterator()
        while (iterator.hasNext()) {
            val (child, original) = iterator.next()
            if (child.parent !== content || content.indexOfChild(child) < index) {
                if (child.translationY == original + shift) child.translationY = original
                iterator.remove()
            }
        }
        for (position in index until content.childCount) {
            val child = content.getChildAt(position)
            val original = shiftedViews.getOrPut(child) { child.translationY }
            if (child.translationY != original + height) child.translationY = original + height
        }
        // Translation alone does not increase ScrollView's scroll range. Extend the native
        // content bounds too, and reapply only when RN publishes a different layout.
        if (content.height != appliedHeight) originalHeight = content.height
        var requiredHeight = originalHeight + height
        for (position in 0 until content.childCount) {
            val child = content.getChildAt(position)
            if (child.visibility != View.GONE) {
                requiredHeight = maxOf(
                    requiredHeight,
                    ceil(child.bottom + child.translationY).toInt() + content.paddingBottom,
                )
            }
        }
        val changed = shift != height || oldCount != shiftedViews.size || content.height != requiredHeight
        shift = height
        if (content.height != requiredHeight) resizeContent(requiredHeight)
        appliedHeight = requiredHeight
        if (changed) emit(
            Log.DEBUG,
            "menu setting entry content shifted offset=$height children=${shiftedViews.size} " +
                    "contentHeight=$appliedHeight",
        )
    }

    private fun resizeContent(height: Int) {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(content.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        content.layout(content.left, content.top, content.right, content.top + height)
    }

    private fun hide(reason: String) {
        overlay.touchBounds.setEmpty()
        if (entry.isVisible) {
            entry.visibility = View.INVISIBLE
            emit(Log.DEBUG, "menu setting entry hidden reason=$reason")
        }
    }

    fun remove() {
        hide("detached")
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        shiftedViews.forEach { (child, original) ->
            if (child.translationY == original + shift) child.translationY = original
        }
        shiftedViews.clear()
        if (appliedHeight > 0 && content.height == appliedHeight) resizeContent(originalHeight)
        shift = 0
        appliedHeight = 0
    }

    private class EntryOverlay(context: Context) : FrameLayout(context) {
        val touchBounds = Rect()

        init {
            tag = ROOT_TAG
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                !touchBounds.contains(event.x.toInt(), event.y.toInt())
            ) return false
            return super.dispatchTouchEvent(event)
        }
    }

    companion object {
        const val ROOT_TAG = "millennium.steam.menu.setting.overlay"
    }
}
