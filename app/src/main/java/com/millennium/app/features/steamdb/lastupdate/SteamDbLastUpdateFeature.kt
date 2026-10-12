package com.millennium.app.features.steamdb.lastupdate

import com.millennium.app.R
import com.millennium.app.core.ModuleLocalization as L

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.millennium.app.features.steamdb.data.SteamDbAppInfo
import com.millennium.app.features.steamdb.ui.SteamDbSectionView
import com.millennium.app.features.steamdb.ui.SteamDbUi as Ui

internal class SteamDbLastUpdateFeature(private val context: Context) {
    val section = LastUpdateSection(context)
    fun show(result: Result<SteamDbAppInfo>) {
        result.onSuccess { info ->
            info.updatedAt?.let(section::show) ?: section.unavailable(L.text(context, R.string.db_date_missing))
        }.onFailure { section.unavailable(L.text(context, R.string.db_date_unavailable)) }
    }
}

internal class LastUpdateSection(private val context: Context) : SteamDbSectionView {
    override val root = Ui.row(context).apply {
        minimumHeight = Ui.dp(context, 44)
        setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 4))
    }
    private val date = Ui.text(context, "—", 16f, Ui.TEXT, medium = true).apply { gravity = Gravity.END }

    init {
        root.addView(Ui.label(context, L.text(context, R.string.db_updated)), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(date, LinearLayout.LayoutParams(-2, -2))
    }

    fun show(timestamp: Long) {
        date.text = Ui.date(timestamp)
        date.contentDescription = L.text(context, R.string.db_updated_description, Ui.dateWithRelative(context, timestamp))
        date.tooltipText = null
    }

    override fun loading() {
        date.text = "…"
        date.contentDescription = L.text(context, R.string.db_loading_date)
    }

    override fun unavailable(message: String) {
        date.text = "—"
        date.contentDescription = message
        date.tooltipText = message
    }
}
