package com.millennium.app.features.steamdb.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

internal interface SteamDbSectionView {
    val root: View
    fun loading()
    fun unavailable(message: String)
}

/** Shared styling and date formatting for the four independent feature views. */
internal object SteamDbUi {
    const val TEXT = 0xffffffff.toInt()
    const val LABEL = 0xffc6d4df.toInt()
    const val MUTED = 0xff8f98a0.toInt()
    const val ACCENT = 0xff66c0f4.toInt()
    const val RIPPLE = 0x301a9fff
    const val LINE = 0x0fffffff
    const val BORDER = 0x1affffff
    const val PANEL = 0xff171a21.toInt()
    const val CARD = 0xff121a24.toInt()
    const val PADDING = 16
    const val HEADER_HEIGHT = 48
    const val HEADER_GAP = 14
    const val ICON_SIZE = 42
    const val RADIUS = 22

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun text(context: Context, value: String, size: Float = 11f, color: Int = MUTED, medium: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        }

    fun label(context: Context, value: String) = text(context, value, color = LABEL)

    fun value(context: Context, value: String = "—") =
        text(context, value, 24f, TEXT, medium = true).apply {
            letterSpacing = -0.04f
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER_VERTICAL
        }

    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun divider(context: Context) = View(context).apply {
        setBackgroundColor(LINE)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun background(context: Context, radius: Int, fill: Int = Color.TRANSPARENT, border: Int? = null) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radius).toFloat()
            border?.let { setStroke(dp(context, 1), it) }
        }

    fun number(value: Number?): String = value?.let {
        NumberFormat.getIntegerInstance(Locale.US).format(it)
    } ?: "—"

    fun date(seconds: Long?): String = seconds?.let {
        DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(it))
    } ?: "—"

    // BrowserExtension FormatRelativeDate: floor days, >30 days => round(days / 30).
    // Chinese Intl.RelativeTimeFormat(numeric: "auto") uses these special day/month names.
    fun relativeDate(seconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val days = Math.floorDiv(nowMillis - seconds * 1000L, 86_400_000L)
        if (days > 30) {
            val months = (days / 30.0).roundToLong()
            return if (months == 1L) "上个月" else "${months}个月前"
        }
        return when (days) {
            0L -> "今天"
            1L -> "昨天"
            2L -> "前天"
            -1L -> "明天"
            -2L -> "后天"
            else -> if (days > 0) "${days}天前" else "${-days}天后"
        }
    }

    fun dateWithRelative(seconds: Long): String = "${date(seconds)}（${relativeDate(seconds)}）"
}
