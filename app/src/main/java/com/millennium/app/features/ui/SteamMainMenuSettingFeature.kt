package com.millennium.app.features.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import com.millennium.app.features.steamdb.ui.SteamDbUi
import java.util.IdentityHashMap

/** Inserts a Millennium row before Steam's native Store row in the bottom-menu page. */
internal class SteamMainMenuSettingFeature(
    private val emit: (priority: Int, message: String) -> Unit,
    private val iconProvider: (Activity) -> Drawable?,
    private val onOpen: (Activity) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val states = IdentityHashMap<Activity, State>()

    fun attach(activity: Activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { attach(activity) }
            return
        }
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val existing = states[activity]
        if (existing != null) {
            schedule(existing)
            return
        }

        val state = State(activity, decor)
        state.layoutListener = ViewTreeObserver.OnGlobalLayoutListener { schedule(state) }
        state.drawListener = ViewTreeObserver.OnPreDrawListener {
            val layout = state.layout
            if (layout != null) {
                if (layout.isAttached) layout.update() else {
                    layout.remove()
                    state.layout = null
                    schedule(state)
                }
            }
            true
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(state.layoutListener)
        decor.viewTreeObserver.addOnPreDrawListener(state.drawListener)
        states[activity] = state
        RETRY_DELAYS_MS.forEach { delay ->
            handler.postDelayed({
                if (states[activity] === state) insertIfReady(state)
            }, delay)
        }
        insertIfReady(state)
        emit(android.util.Log.INFO, "menu setting entry watcher attached activity=${activity.javaClass.name}")
    }

    fun detach(activity: Activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { detach(activity) }
            return
        }
        val state = states.remove(activity) ?: return
        state.decor.viewTreeObserver.takeIf { it.isAlive }?.let {
            it.removeOnGlobalLayoutListener(state.layoutListener)
            it.removeOnPreDrawListener(state.drawListener)
        }
        state.layout?.remove()
        state.layout = null
        emit(android.util.Log.DEBUG, "menu setting entry removed activity=${activity.javaClass.name}")
    }

    private fun schedule(state: State) {
        if (state.pending || states[state.activity] !== state) return
        state.pending = true
        handler.postDelayed({
            state.pending = false
            if (states[state.activity] === state) insertIfReady(state)
        }, LAYOUT_DEBOUNCE_MS)
    }

    private fun insertIfReady(state: State) {
        if (state.activity.isFinishing || state.activity.isDestroyed) return
        state.layout?.let { layout ->
            if (layout.isAttached) {
                layout.update()
                return
            }
            layout.remove()
            state.layout = null
        }

        val anchor = findStoreAnchor(state.decor)
        if (anchor == null) {
            if (!state.waiting) emit(android.util.Log.DEBUG, "menu setting entry waiting for store anchor")
            state.waiting = true
            return
        }
        val contentParent = anchor.parent as? ViewGroup ?: return
        val scroll = contentParent.parent as? ScrollView ?: return
        state.waiting = false
        val entry = buildEntry(state.activity, anchor)
        val layout = SteamMainMenuSettingLayout(state.decor, anchor, contentParent, scroll, entry, emit)
        runCatching {
            layout.attach()
            state.layout = layout
            emit(
                android.util.Log.INFO,
                "menu setting entry inserted beforeStore=true host=${state.decor.javaClass.name} " +
                        "contentParent=${contentParent.javaClass.name} " +
                        "index=${contentParent.indexOfChild(anchor)} height=${anchor.height}",
            )
        }.onFailure {
            layout.remove()
            state.layout = null
            emit(
                android.util.Log.WARN,
                "menu setting entry insertion failed host=${state.decor.javaClass.name} " +
                        "error=${it.javaClass.simpleName}:${it.message}",
            )
        }
    }

    private fun buildEntry(activity: Activity, anchor: View): View {
        val rowHeight = anchor.height.takeIf { it > 0 } ?: dp(activity, DEFAULT_ROW_HEIGHT_DP)
        val anchorBounds = Rect().also { anchor.getGlobalVisibleRect(it) }
        val sourceLabel = findStoreLabel(anchor)
        val latinLabel = findMenuLabel(anchor) { text ->
            text.contains("Steam") && text.contains("令牌")
        }
        val textStyle = readHostTextStyle(sourceLabel, anchorBounds)
        val iconBounds = findLeadingIconBounds(anchor, sourceLabel, anchorBounds)
        val iconWidth = iconBounds?.width()?.takeIf { it > 0 } ?: dp(activity, FALLBACK_ICON_SIZE_DP)
        val iconHeight = iconBounds?.height()?.takeIf { it > 0 } ?: iconWidth
        val iconLeft = iconBounds?.let { (it.left - anchorBounds.left).coerceAtLeast(0) }
            ?: dp(activity, FALLBACK_ICON_LEFT_DP)
        val labelLeft = textStyle.leftMargin ?: dp(activity, FALLBACK_LABEL_LEFT_DP)
        val labelRight = textStyle.rightMargin ?: dp(activity, FALLBACK_LABEL_RIGHT_DP)
        val labelWidth = textStyle.width ?: (anchorBounds.width() - labelLeft - labelRight).coerceAtLeast(1)
        val labelHeight = textStyle.height ?: rowHeight
        val labelTop = textStyle.topMargin ?: ((rowHeight - labelHeight) / 2).coerceAtLeast(0)
        val row = FrameLayout(activity).apply {
            tag = ENTRY_TAG
            contentDescription = ENTRY_LABEL
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            minimumHeight = rowHeight
            background = Color.TRANSPARENT.toDrawable()
            setOnClickListener {
                emit(android.util.Log.INFO, "menu setting entry clicked")
                onOpen(activity)
            }
        }

        val icon = ImageView(activity).apply {
            contentDescription = ENTRY_LABEL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageDrawable(runCatching { iconProvider(activity) }.getOrNull())
            imageTintList = ColorStateList.valueOf(SteamDbUi.TEXT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(
            icon,
            FrameLayout.LayoutParams(iconWidth, iconHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = iconLeft
                topMargin = iconBounds?.let { (it.top - anchorBounds.top).coerceAtLeast(0) }
                    ?: ((rowHeight - iconHeight) / 2).coerceAtLeast(0)
            },
        )

        val label = TextView(activity).apply {
            text = copyHostFontSpans(latinLabel ?: sourceLabel)
            paint.set(textStyle.paint)
            setTextColor(SteamDbUi.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textStyle.textSizePx)
            typeface = textStyle.typeface
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = textStyle.includeFontPadding
            letterSpacing = textStyle.letterSpacing
            textScaleX = textStyle.textScaleX
            fontFeatureSettings = textStyle.fontFeatureSettings
            fontVariationSettings = textStyle.fontVariationSettings
            breakStrategy = textStyle.breakStrategy
            hyphenationFrequency = textStyle.hyphenationFrequency
            setLineSpacing(textStyle.lineSpacingExtra, textStyle.lineSpacingMultiplier)
            textAlignment = textStyle.textAlignment
            maxLines = textStyle.maxLines
            ellipsize = textStyle.ellipsize
        }
        row.addView(
            label,
            FrameLayout.LayoutParams(
                labelWidth,
                labelHeight,
            ).apply {
                leftMargin = labelLeft
                topMargin = labelTop
            },
        )
        val divider = View(activity).apply { setBackgroundColor(DIVIDER_COLOR) }
        row.addView(
            divider,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, DIVIDER_HEIGHT_DP)).apply {
                gravity = Gravity.BOTTOM
            },
        )
        return row
    }

    private fun findStoreLabel(root: View): TextView? {
        if (root is TextView && matchesStoreLabel(root)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findStoreLabel(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun copyHostFontSpans(source: TextView?): CharSequence {
        val target = ENTRY_LABEL
        val sourceText = source?.text as? android.text.Spanned ?: return target
        if (sourceText.isEmpty()) return target
        val styled = android.text.SpannableString(target)
        sourceText.getSpans(0, sourceText.length, Any::class.java).forEach { span ->
            val isFontSpan = span is android.text.style.MetricAffectingSpan ||
                    span is android.text.style.ForegroundColorSpan
            if (!isFontSpan) return@forEach
            styled.setSpan(span, 0, target.length, sourceText.getSpanFlags(span))
        }
        return styled
    }

    private fun findMenuLabel(anchor: View, matches: (String) -> Boolean): TextView? {
        var current: View? = anchor
        repeat(MAX_MENU_LABEL_PARENT_LEVELS) {
            val root = current ?: return null
            findMenuLabelInTree(root, matches)?.let { return it }
            current = root.parent as? View
        }
        return null
    }

    private fun findMenuLabelInTree(root: View, matches: (String) -> Boolean): TextView? {
        if (root is TextView && matches(root.text?.toString()?.trim().orEmpty())) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findMenuLabelInTree(root.getChildAt(index), matches)?.let { return it }
            }
        }
        return null
    }

    private fun readHostTextStyle(source: TextView?, anchorBounds: Rect): HostTextStyle {
        if (source == null) {
            return HostTextStyle(
                paint = android.text.TextPaint(),
                textSizePx = 20f,
                typeface = Typeface.DEFAULT,
                gravity = Gravity.CENTER_VERTICAL,
                includeFontPadding = true,
                letterSpacing = 0f,
                textScaleX = 1f,
                fontFeatureSettings = null,
                fontVariationSettings = null,
                breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE,
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE,
                lineSpacingExtra = 0f,
                lineSpacingMultiplier = 1f,
                textAlignment = View.TEXT_ALIGNMENT_GRAVITY,
                maxLines = 1,
                ellipsize = android.text.TextUtils.TruncateAt.END,
                leftMargin = null,
                rightMargin = null,
                topMargin = null,
                width = null,
                height = null,
            )
        }
        val bounds = Rect().also { source.getGlobalVisibleRect(it) }
        return HostTextStyle(
            paint = android.text.TextPaint().apply { set(source.paint) },
            textSizePx = source.textSize.takeIf { it > 0f } ?: dp(source.context, FALLBACK_LABEL_SIZE_SP).toFloat(),
            typeface = source.paint.typeface ?: source.typeface ?: Typeface.DEFAULT,
            gravity = source.gravity,
            includeFontPadding = source.includeFontPadding,
            letterSpacing = source.letterSpacing,
            textScaleX = source.textScaleX,
            fontFeatureSettings = source.fontFeatureSettings,
            fontVariationSettings = source.fontVariationSettings,
            breakStrategy = source.breakStrategy,
            hyphenationFrequency = source.hyphenationFrequency,
            lineSpacingExtra = source.lineSpacingExtra,
            lineSpacingMultiplier = source.lineSpacingMultiplier,
            textAlignment = source.textAlignment,
            maxLines = source.maxLines,
            ellipsize = source.ellipsize,
            leftMargin = (bounds.left - anchorBounds.left).coerceAtLeast(0),
            rightMargin = (anchorBounds.right - bounds.right).coerceAtLeast(0),
            topMargin = (bounds.top - anchorBounds.top).coerceAtLeast(0),
            width = bounds.width().takeIf { it > 0 },
            height = bounds.height().takeIf { it > 0 },
        )
    }

    private fun findLeadingIconBounds(anchor: View, label: TextView?, anchorBounds: Rect): Rect? {
        val labelBounds = label?.let { Rect().also { rect -> it.getGlobalVisibleRect(rect) } } ?: return null
        val minimumHeight = (labelBounds.height() * 0.5f).toInt()
        val maximumWidth = (anchorBounds.width() / 3).coerceAtLeast(labelBounds.width())
        val candidates = ArrayList<Rect>()
        fun visit(view: View) {
            if (view !== anchor && view !is TextView && view.isVisible && view.alpha > 0f) {
                val bounds = Rect()
                if (view.getGlobalVisibleRect(bounds) && bounds.height() >= minimumHeight &&
                    bounds.width() <= maximumWidth && bounds.left < labelBounds.left
                ) candidates += bounds
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(anchor)
        return candidates.minWithOrNull(compareBy({ it.left }, { it.width() * it.height() }))
    }

    private data class HostTextStyle(
        val paint: android.text.TextPaint,
        val textSizePx: Float,
        val typeface: Typeface,
        val gravity: Int,
        val includeFontPadding: Boolean,
        val letterSpacing: Float,
        val textScaleX: Float,
        val fontFeatureSettings: String?,
        val fontVariationSettings: String?,
        val breakStrategy: Int,
        val hyphenationFrequency: Int,
        val lineSpacingExtra: Float,
        val lineSpacingMultiplier: Float,
        val textAlignment: Int,
        val maxLines: Int,
        val ellipsize: android.text.TextUtils.TruncateAt?,
        val leftMargin: Int?,
        val rightMargin: Int?,
        val topMargin: Int?,
        val width: Int?,
        val height: Int?,
    )

    private fun findStoreAnchor(root: View, clickableAncestor: View? = null): View? {
        if (root.visibility != View.VISIBLE || root.alpha <= 0f) return null
        if (root.tag == ENTRY_TAG || root.tag == SteamMainMenuSettingLayout.ROOT_TAG) return null
        val currentClickableAncestor = if (root is ViewGroup && root.isClickable) {
            root
        } else {
            clickableAncestor
        }
        if (matchesStoreLabel(root)) {
            val parent = currentClickableAncestor?.parent as? ViewGroup
            if (currentClickableAncestor != null && currentClickableAncestor.isShown &&
                parent?.parent is ScrollView && currentClickableAncestor.width > 0 &&
                currentClickableAncestor.height > 0
            ) return currentClickableAncestor
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findStoreAnchor(root.getChildAt(index), currentClickableAncestor)?.let { return it }
            }
        }
        return null
    }

    private fun matchesStoreLabel(view: View): Boolean {
        val description = view.contentDescription?.toString().orEmpty()
        val text = (view as? TextView)?.text?.toString().orEmpty()
        return listOf(description, text).any { value ->
            val label = value.trim()
            label == "商店" || label.startsWith("商店 ") || label.startsWith("商店（") ||
                    label == "Store" || label.startsWith("Store ")
        }
    }

    private class State(val activity: Activity, val decor: ViewGroup) {
        lateinit var layoutListener: ViewTreeObserver.OnGlobalLayoutListener
        lateinit var drawListener: ViewTreeObserver.OnPreDrawListener
        var pending = false
        var waiting = false
        var layout: SteamMainMenuSettingLayout? = null
    }

    private companion object {
        const val ENTRY_TAG = "millennium.steam.menu.setting.entry"
        const val ENTRY_LABEL = "Millennium"
        const val FALLBACK_LABEL_SIZE_SP = 20
        const val FALLBACK_ICON_SIZE_DP = 24
        const val FALLBACK_ICON_LEFT_DP = 20
        const val FALLBACK_LABEL_LEFT_DP = 56
        const val FALLBACK_LABEL_RIGHT_DP = 24
        const val DEFAULT_ROW_HEIGHT_DP = 64
        const val DIVIDER_HEIGHT_DP = 1
        const val LAYOUT_DEBOUNCE_MS = 80L
        const val MAX_MENU_LABEL_PARENT_LEVELS = 6
        val RETRY_DELAYS_MS = longArrayOf(0L, 100L, 300L, 800L, 1600L)
        const val DIVIDER_COLOR = 0x33394046

        fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
