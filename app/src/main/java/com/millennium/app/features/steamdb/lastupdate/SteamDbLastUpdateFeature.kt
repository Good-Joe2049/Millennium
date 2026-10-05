package com.millennium.app.features.steamdb.lastupdate

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.millennium.app.features.steamdb.data.SteamDbAppInfo
import com.millennium.app.features.steamdb.ui.SteamDbSectionView
import com.millennium.app.features.steamdb.ui.SteamDbUi as Ui

internal class SteamDbLastUpdateFeature(context: Context) {
    val section = LastUpdateSection(context)
    fun show(result: Result<SteamDbAppInfo>) {
        result.onSuccess { info ->
            info.updatedAt?.let(section::show) ?: section.unavailable("SteamDB 暂无更新日期")
        }.onFailure { section.unavailable("更新日期暂时不可用") }
    }
}

internal class LastUpdateSection(context: Context) : SteamDbSectionView {
    override val root = Ui.row(context).apply {
        minimumHeight = Ui.dp(context, 44)
        setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 4))
    }
    private val date = Ui.text(context, "—", 16f, Ui.TEXT, medium = true).apply { gravity = Gravity.END }

    init {
        root.addView(Ui.label(context, "最近更新"), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(date, LinearLayout.LayoutParams(-2, -2))
    }

    fun show(timestamp: Long) {
        date.text = Ui.date(timestamp)
        date.contentDescription = "最近更新 ${Ui.dateWithRelative(timestamp)}"
        date.tooltipText = null
    }

    override fun loading() {
        date.text = "…"
        date.contentDescription = "正在读取更新日期"
    }

    override fun unavailable(message: String) {
        date.text = "—"
        date.contentDescription = message
        date.tooltipText = message
    }
}
