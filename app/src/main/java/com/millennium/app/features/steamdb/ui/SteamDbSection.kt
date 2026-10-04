package com.millennium.app.features.steamdb.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small shared presentation helper; feature code owns labels and states. */
@SuppressLint("SetTextI18n")
internal class SteamDbSection(context: Context, title: String, labels: List<String>) {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(context, 4), 0, dp(context, 14))
    }
    private val values = labels.map { label ->
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 28)
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(Color.rgb(176, 183, 196))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        TextView(context).apply {
            text = "—"
            textSize = 15f
            setTextColor(Color.rgb(102, 192, 244))
            setTypeface(typeface, Typeface.BOLD)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            row.addView(this, LinearLayout.LayoutParams(-2, -2))
            root.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private val status = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.rgb(135, 143, 158))
        setPadding(0, dp(context, 4), 0, 0)
    }

    init {
        root.addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(context, 6))
        }, 0, LinearLayout.LayoutParams(-1, -2))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        unavailable("仅在 Steam 游戏商店页面显示")
    }

    fun value(index: Int, text: String) { values[index].text = text }
    fun status(message: String) { status.text = message }
    fun loading() {
        values.forEach { it.text = "…" }
        status.text = "正在读取数据"
    }
    fun unavailable(message: String) {
        values.forEach { it.text = "—" }
        status.text = message
    }

    companion object {
        fun number(value: Number?): String = value?.let {
            NumberFormat.getIntegerInstance(Locale.US).format(it)
        } ?: "—"

        fun date(seconds: Long?): String = seconds?.let {
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(it))
        } ?: "—"

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
