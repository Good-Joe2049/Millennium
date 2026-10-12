package com.millennium.app.features.steamdb.lowestprice

import com.millennium.app.R
import com.millennium.app.core.ModuleLocalization as L

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.millennium.app.features.steamdb.data.SteamDbLowestPrice
import com.millennium.app.features.steamdb.ui.SteamDbSectionView
import com.millennium.app.features.steamdb.ui.SteamDbUi as Ui

internal class SteamDbLowestPriceFeature(private val context: Context) {
    val section = LowestPriceSection(context)
    fun show(result: Result<SteamDbLowestPrice>) {
        result.onSuccess { section.show(it) }.onFailure { section.unavailable(L.text(context, R.string.db_lowest_unavailable)) }
    }
}

@SuppressLint("SetTextI18n")
internal class LowestPriceSection(private val context: Context) : SteamDbSectionView {
    override val root = Ui.column(context).apply {
        setPadding(0, Ui.dp(context, 13), 0, Ui.dp(context, 12))
    }
    private val priceValue = Ui.value(context)
    private val limitedLabel = Ui.label(context, L.text(context, R.string.db_lowest_limited)).apply { gravity = Gravity.END }
    private val limitedValue = Ui.value(context).apply { gravity = Gravity.END }
    private val discount = Ui.label(context, "").apply { gravity = Gravity.END }
    private val note = Ui.text(context, "", 10f).apply { minLines = 2 }

    init {
        val labels = Ui.row(context).apply {
            addView(Ui.label(context, L.text(context, R.string.db_lowest)), LinearLayout.LayoutParams(0, -2, 1f))
            addView(limitedLabel, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(labels, LinearLayout.LayoutParams(-1, -2))
        val values = Ui.row(context).apply {
            addView(priceValue, LinearLayout.LayoutParams(0, -2, 1f))
            addView(limitedValue, LinearLayout.LayoutParams(0, -2, 1f))
            addView(discount, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(values, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(context, 4)
        })
        root.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 4) })
        unavailable(L.text(context, R.string.db_open_store))
    }

    fun show(price: SteamDbLowestPrice) {
        priceValue.text = price.price
        val limited = price.limitedPrice?.takeIf { it.isNotBlank() }
        limitedLabel.visibility = if (limited != null) View.VISIBLE else View.GONE
        limitedValue.visibility = limitedLabel.visibility
        limitedValue.text = limited.orEmpty()
        discount.text = if (limited == null) price.discount?.let { "-$it%" }.orEmpty() else ""
        discount.visibility = if (discount.text.isNotEmpty()) View.VISIBLE else View.GONE
        val date = price.lastAt?.let { L.text(context, R.string.db_lowest_last, Ui.dateWithRelative(context, it)) } ?: L.text(context, R.string.db_lowest_date_missing)
        val count = price.occurrences?.takeIf { it > 1 }?.let { L.quantity(context, R.plurals.db_price_occurrences, it, it) }.orEmpty()
        note.text = date + count
    }

    fun free() {
        reset()
        priceValue.text = L.text(context, R.string.db_free)
        note.text = L.text(context, R.string.db_no_purchase)
    }

    override fun loading() {
        reset()
        priceValue.text = "…"
        note.text = L.text(context, R.string.db_loading_live)
    }

    override fun unavailable(message: String) {
        reset()
        priceValue.text = "—"
        note.text = message
    }

    private fun reset() {
        limitedLabel.visibility = View.GONE
        limitedValue.visibility = View.GONE
        limitedValue.text = ""
        discount.visibility = View.GONE
        discount.text = ""
    }
}
