package com.millennium.app.features.ui.menu

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import java.util.IdentityHashMap
import kotlin.math.ceil

/** Keeps the native entry inside Steam's ScrollView content so overscroll transforms apply. */
internal class SteamMainMenuSettingLayout(
    private val decor: ViewGroup,
    private val anchor: View,
    private val content: ViewGroup,
    private val scroll: ScrollView,
    private val entry: View,
    private val emit: (Int, String) -> Unit,
) {
    /** The host is a child of the scrolling content instead of the window decor overlay. */
    private val overlay = EntryOverlay(decor.context)
    private val shiftedViews = IdentityHashMap<View, Float>()
    private val visibleRect = Rect()
    private var shift = 0
    private var originalHeight = content.height
    private var appliedHeight = 0

    val isAttached: Boolean
        get() = overlay.parent === content && entry.parent === overlay &&
                anchor.parent === content && content.parent === scroll &&
                anchor.isAttachedToWindow && anchor.rootView === decor

    fun attach() {
        entry.visibility = View.INVISIBLE
        overlay.addView(entry, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        // Put the host above the native rows while keeping it inside the transformed content.
        content.addView(overlay, ViewGroup.LayoutParams(-1, 0))
        update()
    }

    /** Called before drawing so scrolling and RN screen transitions cannot leave a stale host. */
    fun update() {
        visibleRect.setEmpty()
        if (!isAttached || !hasVisibleAncestors() || !scroll.getLocalVisibleRect(visibleRect)) {
            hide("menu-not-visible")
            return
        }
        val width = anchor.width
        val height = anchor.height
        if (width <= 0 || height <= 0 || content.width <= 0 || content.height <= 0) {
            hide("awaiting-layout")
            return
        }
        reserveSpace(height)

        // reserveSpace stores the anchor's pre-insertion translation. The entry occupies
        // that original slot while the native Store row and following rows stay shifted down.
        val baseTranslationY = shiftedViews[anchor] ?: (anchor.translationY - shift)
        val left = anchor.left.toFloat() + anchor.translationX
        val top = anchor.top.toFloat() + baseTranslationY
        layoutOverlay(width, height, left, top)
        overlay.touchBounds.set(0, 0, width, height)

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
        entry.x = 0f
        entry.y = 0f
        entry.clipBounds = null
        val shown = entry.visibility != View.VISIBLE
        overlay.visibility = View.VISIBLE
        if (shown) entry.visibility = View.VISIBLE
        if (resized || shown) emit(
            Log.INFO,
            "menu setting entry laid out actual=${entry.width}x${entry.height} " +
                    "left=${left.toInt()} top=${top.toInt()} content=${content.width}x${content.height} visible=true",
        )
    }

    private fun layoutOverlay(width: Int, height: Int, left: Float, top: Float) {
        // Keep the host out of React Native's measured content height. Its actual drawing
        // bounds are set below and refreshed on every pre-draw after content relayouts.
        val params = overlay.layoutParams
        if (params.width != -1 || params.height != 0) {
            params.width = -1
            params.height = 0
            overlay.layoutParams = params
        }
        // RN chooses JS touch targets from view bounds before Android dispatchTouchEvent.
        // A content-sized native host wins that search even when it later returns false.
        // Restrict the host itself to the entry row so native menu rows remain reachable.
        if (overlay.width != width || overlay.height != height || overlay.isLayoutRequested) {
            overlay.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            overlay.layout(0, 0, width, height)
        }
        if (overlay.x != left) overlay.x = left
        if (overlay.y != top) overlay.y = top
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
            if (child === overlay || child.parent !== content || content.indexOfChild(child) < index) {
                if (child.parent === content && child !== overlay && child.translationY == original + shift) {
                    child.translationY = original
                }
                iterator.remove()
            }
        }
        for (position in index until content.childCount) {
            val child = content.getChildAt(position)
            if (child === overlay) continue
            val original = shiftedViews.getOrPut(child) { child.translationY }
            if (child.translationY != original + height) child.translationY = original + height
        }
        // Translation alone does not increase ScrollView's scroll range. Extend the native
        // content bounds too, and reapply only when RN publishes a different layout.
        if (content.height != appliedHeight) originalHeight = content.height
        var requiredHeight = originalHeight + height
        for (position in 0 until content.childCount) {
            val child = content.getChildAt(position)
            if (child === overlay || child.isGone) continue
            requiredHeight = maxOf(
                requiredHeight,
                ceil(child.bottom + child.translationY).toInt() + content.paddingBottom,
            )
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
        overlay.visibility = View.INVISIBLE
        // RN hit testing does not use this native view's visibility or dispatch result.
        // Collapse its bounds too, including while the menu is awaiting a new layout.
        if (overlay.width != 0 || overlay.height != 0) overlay.layout(0, 0, 0, 0)
        if (entry.isVisible) {
            entry.visibility = View.INVISIBLE
            emit(Log.DEBUG, "menu setting entry hidden reason=$reason")
        }
    }

    fun remove() {
        hide("detached")
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        shiftedViews.forEach { (child, original) ->
            if (child.parent === content && child.translationY == original + shift) {
                child.translationY = original
            }
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
            clipChildren = true
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
