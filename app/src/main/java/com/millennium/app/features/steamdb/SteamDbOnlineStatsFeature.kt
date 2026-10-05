package com.millennium.app.features.steamdb

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import com.millennium.app.features.steamdb.data.SteamDbAppInfo
import com.millennium.app.features.steamdb.ui.SteamDbSectionView
import com.millennium.app.features.steamdb.ui.SteamDbUi as Ui

internal class SteamDbOnlineStatsFeature(context: Context) {
    val section = OnlineStatsSection(context)
    private var appInfo: SteamDbAppInfo? = null
    private var currentPlayers: Int? = null
    private var appFinished = false
    private var playersFinished = false

    fun loading() {
        appInfo = null
        currentPlayers = null
        appFinished = false
        playersFinished = false
        section.loading()
    }

    fun showAppInfo(result: Result<SteamDbAppInfo>) {
        appInfo = result.getOrNull()
        appFinished = true
        render()
    }

    fun showCurrentPlayers(result: Result<Int>) {
        currentPlayers = result.getOrNull()
        playersFinished = true
        render()
    }

    private fun render() {
        val current = currentPlayers ?: appInfo?.currentPlayers
        section.setValue(0, if (current == null && (!playersFinished || !appFinished)) "…" else Ui.number(current))
        listOf(appInfo?.peakToday, appInfo?.peakAll, appInfo?.followers).forEachIndexed { index, value ->
            section.setValue(index + 1, if (!appFinished) "…" else Ui.number(value))
        }
    }
}

@SuppressLint("SetTextI18n")
internal class OnlineStatsSection(context: Context) : SteamDbSectionView {
    override val root = Ui.column(context).apply {
        background = Ui.background(context, 8, Ui.CARD, Ui.BORDER)
        setPadding(1, 1, 1, 1)
        clipToOutline = true
    }
    private val values = ArrayList<TextView>(4)

    init {
        val labels = listOf("实时在线", "24 小时峰值", "历史峰值", "关注人数")
        repeat(2) { rowIndex ->
            if (rowIndex == 1) root.addView(Ui.divider(context), LinearLayout.LayoutParams(-1, 1))
            val row = Ui.row(context)
            repeat(2) { columnIndex ->
                if (columnIndex == 1) row.addView(Ui.divider(context), LinearLayout.LayoutParams(1, -1))
                val index = rowIndex * 2 + columnIndex
                val cell = Ui.column(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(context, 11), Ui.dp(context, 13), Ui.dp(context, 9), Ui.dp(context, 11))
                    minimumHeight = Ui.dp(context, 72)
                }
                cell.addView(Ui.label(context, labels[index]), LinearLayout.LayoutParams(-1, -2))
                val value = Ui.value(context).apply {
                    if (index == 0) setTextColor(Ui.ACCENT)
                    setSingleLine()
                    setAutoSizeTextTypeUniformWithConfiguration(14, 24, 1, TypedValue.COMPLEX_UNIT_SP)
                    minHeight = Ui.dp(context, 32)
                }
                values += value
                cell.addView(value, LinearLayout.LayoutParams(-1, -2))
                row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
            }
            root.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    fun setValue(index: Int, text: String) { values[index].text = text }
    override fun loading() {
        root.contentDescription = null
        values.forEach { it.text = "…" }
    }
    override fun unavailable(message: String) {
        values.forEach { it.text = "—" }
        root.contentDescription = message
    }
}
