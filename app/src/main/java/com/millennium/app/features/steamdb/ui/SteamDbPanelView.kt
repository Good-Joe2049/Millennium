package com.millennium.app.features.steamdb.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.net.toUri
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.millennium.app.features.steamdb.ui.SteamDbUi as Ui

/** Panel chrome only; individual features own their data and display state. */
@SuppressLint("SetTextI18n")
internal class SteamDbPanelView(context: Context, sections: List<SteamDbSectionView>) {
    val root = Ui.column(context).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
    private val refreshButton = SteamDbPanelIcon.button(context, SteamDbPanelIcon.Kind.REFRESH, "刷新当前游戏数据")
    private val gameName = Ui.text(context, "Steam 游戏", 13f, Ui.TEXT, medium = true).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val appLabel = Ui.text(context, "APP —", 9f).apply { typeface = Typeface.MONOSPACE }
    private val stateLabel = Ui.text(context, "在线数据", 10f, Ui.LABEL)
    private val dot = View(context).apply { background = Ui.background(context, 3, Ui.MUTED) }
    private val collected = Ui.text(context, "—", 9f)
    private val link = Button(context).apply {
        text = "SteamDB"
        contentDescription = "在浏览器打开当前游戏的 SteamDB 页面"
        textSize = 9f
        isAllCaps = false
        setTextColor(Ui.MUTED)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = Ui.dp(context, 48)
        setPadding(Ui.dp(context, 8), 0, 0, 0)
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        stateListAnimator = null
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE), null, Ui.background(context, 6, Ui.TEXT))
        val arrow = SteamDbPanelIcon(SteamDbPanelIcon.Kind.EXTERNAL).apply {
            setBounds(0, 0, Ui.dp(context, 12), Ui.dp(context, 12))
        }
        compoundDrawablePadding = Ui.dp(context, 3)
        setCompoundDrawables(null, null, arrow, null)
    }
    private var appId: Int? = null
    var onRefresh: () -> Unit = {}

    init {
        root.addView(Ui.row(context).apply {
            minimumHeight = Ui.dp(context, 34)
            setPadding(0, 0, 0, Ui.dp(context, 8))
            addView(gameName, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(context, 8) })
            addView(appLabel, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Ui.divider(context), LinearLayout.LayoutParams(-1, 1))
        root.addView(Ui.row(context).apply {
            addView(dot, LinearLayout.LayoutParams(Ui.dp(context, 5), Ui.dp(context, 5)).apply {
                marginEnd = Ui.dp(context, 5)
            })
            addView(stateLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(refreshButton, LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)))
        }, LinearLayout.LayoutParams(-1, -2))
        refreshButton.setOnClickListener { onRefresh() }
        sections.forEachIndexed { index, section ->
            if (index == 2 || index == 3) root.addView(Ui.divider(context), LinearLayout.LayoutParams(-1, 1))
            root.addView(section.root, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(Ui.row(context).apply {
            addView(collected, LinearLayout.LayoutParams(0, -2, 1f))
            addView(link, LinearLayout.LayoutParams(-2, Ui.dp(context, 48)))
        }, LinearLayout.LayoutParams(-1, -2))
        link.setOnClickListener {
            appId?.let { id ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://steamdb.info/app/$id/".toUri()))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "未找到可以打开链接的浏览器", Toast.LENGTH_SHORT).show()
                }
            }
        }
        setPage(null, null)
    }

    fun setPage(name: String?, id: Int?) {
        appId = id
        gameName.text = name?.takeIf { it.isNotBlank() } ?: id?.let { "App $it" } ?: "Steam 游戏"
        appLabel.text = id?.let { "APP $it" } ?: "APP —"
        link.isEnabled = id != null
        link.alpha = if (id != null) 1f else 0.4f
    }

    fun loading() {
        dot.background = Ui.background(root.context, 3, Ui.MUTED)
        collected.text = "正在读取…"
        refreshButton.isEnabled = false
        refreshButton.alpha = 0.4f
        stateLabel.text = "在线数据 · 正在读取"
    }

    fun fetchedAt(timeMillis: Long) {
        collected.text = "采集于 " + DateTimeFormatter.ofPattern("MM/dd HH:mm")
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timeMillis))
    }

    fun finished(partial: Boolean, available: Boolean) {
        refreshButton.isEnabled = true
        refreshButton.alpha = 1f
        stateLabel.text = when {
            !available -> "在线数据 · 暂时不可用"
            partial -> "在线数据 · 部分不可用"
            else -> "在线数据"
        }
        dot.background = Ui.background(root.context, 3, if (available) Ui.ACCENT else Ui.MUTED)
        if (collected.text == "正在读取…") collected.text = if (available) "部分数据可用" else "暂未取得数据"
    }

    fun unavailable(message: String) {
        setPage(null, null)
        finished(partial = false, available = false)
        stateLabel.text = "在线数据"
        collected.text = message
    }

    fun wrap(onClose: () -> Unit): View {
        val context = root.context
        val panel = Ui.column(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(Ui.dp(context, Ui.PADDING), Ui.dp(context, Ui.PADDING),
                Ui.dp(context, Ui.PADDING), Ui.dp(context, Ui.PADDING))
            isClickable = true
        }
        val header = Ui.row(context)
        header.addView(View(context), LinearLayout.LayoutParams(Ui.dp(context, Ui.ICON_SIZE), Ui.dp(context, Ui.ICON_SIZE)).apply {
            marginEnd = Ui.dp(context, 10)
        })
        header.addView(Ui.column(context).apply {
            addView(Ui.text(context, "Millennium", 16f, Ui.TEXT, medium = true))
            addView(Ui.text(context, "GAME INSIGHTS", 9f).apply {
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.06f
                setPadding(0, Ui.dp(context, 4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(SteamDbPanelIcon.button(context, SteamDbPanelIcon.Kind.CLOSE, "收起面板").apply {
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)))
        panel.addView(header, LinearLayout.LayoutParams(-1, Ui.dp(context, Ui.HEADER_HEIGHT)).apply {
            bottomMargin = Ui.dp(context, Ui.HEADER_GAP)
        })
        panel.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(root)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return panel
    }

    fun heightForWidth(width: Int): Int {
        val context = root.context
        val contentWidth = (width - Ui.dp(context, 2 * Ui.PADDING)).coerceAtLeast(1)
        root.measure(View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return root.measuredHeight + Ui.dp(context, 2 * Ui.PADDING + Ui.HEADER_HEIGHT + Ui.HEADER_GAP)
    }
}
